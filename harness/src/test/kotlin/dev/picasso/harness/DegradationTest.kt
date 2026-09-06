package dev.picasso.harness

import dev.picasso.contracts.v1.CapabilityChangeCause
import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import dev.picasso.mimic.control.v1.RemoveCapabilityRequest
import dev.picasso.mimic.control.v1.RestoreCapabilityRequest
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import dev.picasso.mimic.engine.TaskState as EngineState

/**
 * 완료 기준 14 — `RemoveCapability` → epoch 증가 → 캐시 무효화 →
 * **해당 스킬만 `CAPABILITY_WITHDRAWN`, 이동은 계속됨.**
 *
 * **"이동은 계속됨"이 중심이다.** `pick_place`를 축소하고 `navigate_to`가
 * 그대로 도는 것을 **같은 시나리오 안에서** 본다 — 따로 보면 "전부 죽었다"와
 * 구별되지 않는다.
 */
class DegradationTest {

    private val profile = Path.of("..", "profile", "fixtures", "minimal.json").normalize()

    private fun harness() = Harness(mapOf(ROBOT to profile))

    private fun Harness.dump() = oracle.dumpInternalState(
        DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build(),
    )

    private fun Harness.remove(skill: String) = oracle.removeCapability(
        RemoveCapabilityRequest.newBuilder().setRobotId(ROBOT).setSkillType(skill).build(),
    )

    private fun Harness.restore(skill: String) = oracle.restoreCapability(
        RestoreCapabilityRequest.newBuilder().setRobotId(ROBOT).setSkillType(skill).build(),
    )

    /**
     * **새 클라이언트로 묻는다.** `PicassoClient`는 능력을 캐시하므로 같은
     * 것을 다시 쓰면 축소 전 값을 돌려줄 수 있다 — 캐시가 실제로 버려지는지는
     * 아래 `캐시가 무효화된다`가 따로 본다.
     */
    private fun Harness.capabilitySkills(): List<String> =
        client().capabilities(ROBOT).skillsList.map { it.skillType }

    /** 오라클의 세대. 계약 표면을 안 지나므로 캐시와 무관하다. */
    private fun Harness.epoch(): Long = dump().capabilityEpoch

    private fun Harness.changes(): List<Event> =
        publisher.events().filter { it.hasCapabilityChanged() }

    private val navigate = listOf(
        ParameterValue.newBuilder().setKey("location").setStringValue("dock-3").build(),
    )
    private val pickPlace = listOf(
        ParameterValue.newBuilder().setKey("object_id").setStringValue("b-1").build(),
        ParameterValue.newBuilder().setKey("destination").setStringValue("dock-3").build(),
    )

    // ── 네 가지가 한꺼번에 움직인다

    @Test
    fun `축소하면 유효 능력에서 빠진다`() {
        harness().use { harness ->
            assertTrue(PICK in harness.capabilitySkills(), "전제가 무너졌다")
            harness.remove(PICK)
            assertEquals(listOf(NAV), harness.capabilitySkills())
        }
    }

    @Test
    fun `축소하면 epoch가 오른다`() {
        // §5.5의 ETag. 안 오르면 소비자가 캐시를 영영 안 버린다.
        harness().use { harness ->
            val before = harness.epoch()
            val response = harness.remove(PICK)
            assertTrue(response.changed)
            assertTrue(
                response.capabilityEpoch > before,
                "세대가 안 올랐다: $before → ${response.capabilityEpoch}",
            )
            assertEquals(response.capabilityEpoch, harness.epoch(), "오라클과 응답이 다르다")
        }
    }

    @Test
    fun `축소가 CapabilityChanged로 나간다`() {
        // 전체 능력을 싣지 않는다(§8.2) — delta 와 epoch 로 소비자가 갱신한다.
        harness().use { harness ->
            harness.remove(PICK)

            val changed = harness.changes().single().capabilityChanged
            assertEquals(listOf(PICK), changed.removedList)
            assertEquals(emptyList(), changed.addedList)
            assertEquals(ROBOT, changed.robotId)
            assertEquals(
                CapabilityChangeCause.CAPABILITY_CHANGE_CAUSE_RUNTIME_DEGRADED,
                changed.cause,
                "런타임 축소가 아닌 사유로 나갔다",
            )
            assertTrue(changed.capabilityEpoch > 0)
        }
    }

    @Test
    fun `축소한 스킬은 CAPABILITY_WITHDRAWN이다`() {
        // **`SKILL_ABSENT`가 아니다.** 계약이 둘을 나눈 이유가 소비자의
        // 대응이 다르기 때문이다 — 이쪽은 캐시를 다시 세우면 된다.
        harness().use { harness ->
            harness.remove(PICK)
            val response = harness.client().start(ROBOT, "t1", 1, PICK, pickPlace)
            assertEquals(
                RejectionCode.REJECTION_CODE_CAPABILITY_WITHDRAWN,
                response.rejection.code,
                "축소를 SKILL_ABSENT로 알렸다 — 둘을 나눈 뜻이 죽는다",
            )
        }
    }

    @Test
    fun `선언한 적 없는 스킬은 여전히 SKILL_ABSENT다`() {
        // 위 시험의 짝. 둘이 섞이면 어느 쪽도 뜻을 안 갖는다.
        harness().use { harness ->
            harness.remove(PICK)
            val response = harness.client().start(ROBOT, "t1", 1, "weld", emptyList())
            assertEquals(RejectionCode.REJECTION_CODE_SKILL_ABSENT, response.rejection.code)
        }
    }

    // ── 이동은 계속됨

    @Test
    fun `해당 스킬만 막히고 나머지는 계속 받는다`() {
        // **완료 기준 14의 중심이다.** 같은 시나리오 안에서 둘을 본다 —
        // 따로 보면 "전부 죽었다"와 구별되지 않는다.
        harness().use { harness ->
            harness.remove(PICK)

            val blocked = harness.client().start(ROBOT, "p1", 1, PICK, pickPlace)
            assertEquals(RejectionCode.REJECTION_CODE_CAPABILITY_WITHDRAWN, blocked.rejection.code)

            val moving = harness.client().start(ROBOT, "n1", 1, NAV, navigate)
            assertTrue(moving.hasHandle(), "이동까지 막혔다: ${moving.rejection}")

            harness.advance(Duration.ofSeconds(1))
            harness.advance(Duration.ofSeconds(30))
            assertEquals(
                EngineState.SUCCEEDED.name,
                harness.dump().tasksList.single { it.taskId == "n1" }.taskState,
                "이동이 완주하지 못했다",
            )
        }
    }

    @Test
    fun `진행 중이던 태스크는 계속 돈다`() {
        // **축소는 "새로 못 받는다"이지 "하던 것을 무를 수 있다"가 아니다.**
        // 로봇이 물건을 든 채 있을 수 있고, 임의로 종착시키면 §4.4의 취소
        // 의미론(복구를 동반한다)을 우회한다.
        harness().use { harness ->
            harness.client().start(ROBOT, "p1", 1, PICK, pickPlace)
            harness.advance(Duration.ofSeconds(1))
            assertEquals("RUNNING", harness.dump().tasksList.single().taskState)

            harness.remove(PICK)
            assertEquals(
                "RUNNING", harness.dump().tasksList.single().taskState,
                "축소가 진행 중이던 태스크를 죽였다",
            )

            harness.advance(Duration.ofSeconds(60))
            assertEquals(
                EngineState.SUCCEEDED.name, harness.dump().tasksList.single().taskState,
                "축소된 뒤로 완주하지 못했다",
            )
        }
    }

    // ── 되돌리기

    @Test
    fun `복원하면 다시 받는다`() {
        harness().use { harness ->
            harness.remove(PICK)
            val restored = harness.restore(PICK)
            assertTrue(restored.changed)
            assertEquals(setOf(NAV, PICK), harness.capabilitySkills().toSet())

            assertTrue(
                harness.client().start(ROBOT, "p1", 1, PICK, pickPlace).hasHandle(),
                "복원했는데 못 받는다",
            )
        }
    }

    @Test
    fun `복원해도 epoch는 되돌아가지 않는다`() {
        // §5.5의 ETag는 단조 증가다. 되돌아가면 소비자가 옛 캐시를 유효하다고
        // 판정한다.
        harness().use { harness ->
            val start = harness.epoch()
            val afterRemove = harness.remove(PICK).capabilityEpoch
            val afterRestore = harness.restore(PICK).capabilityEpoch

            assertTrue(afterRemove > start, "$start → $afterRemove")
            assertTrue(afterRestore > afterRemove, "$afterRemove → $afterRestore")
        }
    }

    @Test
    fun `복원이 CapabilityChanged로 나간다`() {
        harness().use { harness ->
            harness.remove(PICK)
            harness.restore(PICK)

            val last = harness.changes().last().capabilityChanged
            assertEquals(listOf(PICK), last.addedList)
            assertEquals(emptyList(), last.removedList)
        }
    }

    // ── 유령 세대와 오타

    @Test
    fun `이미 축소한 것을 또 축소하면 세대가 안 오른다`() {
        // 유령 세대는 소비자의 캐시를 헛되이 무효화한다.
        harness().use { harness ->
            val first = harness.remove(PICK)
            val second = harness.remove(PICK)

            assertTrue(first.changed)
            assertTrue(!second.changed, "두 번째가 바뀐 것으로 잡혔다")
            assertEquals(first.capabilityEpoch, second.capabilityEpoch, "세대가 헛되이 올랐다")
            assertEquals(1, harness.changes().size, "이벤트가 두 번 나갔다")
        }
    }

    @Test
    fun `축소한 적 없는 것을 복원해도 세대가 안 오른다`() {
        harness().use { harness ->
            val before = harness.epoch()
            val response = harness.restore(PICK)
            assertTrue(!response.changed)
            assertEquals(before, response.capabilityEpoch)
            assertEquals(emptyList(), harness.changes())
        }
    }

    @Test
    fun `선언한 적 없는 스킬은 축소할 수 없다`() {
        // 그것은 축소가 아니라 오타이고, 허용하면 소비자가 "있었는데
        // 사라졌다"로 읽는다.
        harness().use { harness ->
            val error = assertFailsWith<StatusRuntimeException> { harness.remove("weld") }
            assertEquals(Status.Code.INVALID_ARGUMENT, error.status.code)
            assertEquals(emptyList(), harness.changes(), "거절해 놓고 이벤트를 냈다")
        }
    }

    @Test
    fun `캐시가 무효화된다`() {
        // **완료 기준 14의 "캐시 무효화"다.** `PicassoClient`의 KDoc이
        // "끝에서 끝까지의 증명은 런타임 축소와 함께 온다"고 적어 뒀고,
        // 그것이 여기다.
        //
        // 소비자는 매 응답의 `capability_epoch`를 보고 캐시를 버린다(§5.5).
        // 안 버리면 **사라진 능력에 계속 태스크를 건다.**
        harness().use { harness ->
            val client = harness.client()
            assertEquals(setOf(NAV, PICK), client.capabilities(ROBOT).skillsList.map { it.skillType }.toSet())
            val rpcs = client.capabilityRpcCount

            // 캐시가 살아 있으면 다시 안 묻는다 — 아래 비교의 전제다.
            client.capabilities(ROBOT)
            assertEquals(rpcs, client.capabilityRpcCount, "캐시를 안 쓴다")

            harness.remove(PICK)

            // **아무 RPC나 하나면 된다.** 세대는 모든 응답 헤더에 실린다.
            client.start(ROBOT, "n1", 1, NAV, navigate)

            assertEquals(
                listOf(NAV), client.capabilities(ROBOT).skillsList.map { it.skillType },
                "캐시를 안 버렸다 — 사라진 능력을 계속 든다",
            )
            assertTrue(client.capabilityRpcCount > rpcs, "다시 묻지 않았다")
        }
    }

    @Test
    fun `모르는 기체는 NOT_FOUND다`() {
        harness().use { harness ->
            val error = assertFailsWith<StatusRuntimeException> {
                harness.oracle.removeCapability(
                    RemoveCapabilityRequest.newBuilder()
                        .setRobotId("r9").setSkillType(PICK).build(),
                )
            }
            assertEquals(Status.Code.NOT_FOUND, error.status.code)
        }
    }

    private companion object {
        const val ROBOT = "r1"
        const val PICK = "pick_place"
        const val NAV = "navigate_to"
    }
}
