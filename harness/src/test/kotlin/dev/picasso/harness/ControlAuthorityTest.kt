package dev.picasso.harness

import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import dev.picasso.mimic.control.v1.ForceControlAuthorityLossRequest
import dev.picasso.mimic.control.v1.ForceFaultRequest
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import dev.picasso.mimic.engine.TaskState as EngineState

/**
 * 완료 기준 8d — 제어권을 잃으면 `CONTROL_AUTHORITY_LOST`로 종착하며,
 * **로봇 고장에 의한 `FAILED`와 결함의 `error_type`으로 구분된다.**
 *
 * 이것이 없으면 제어권을 빼앗겨 죽은 태스크가 `FAILED`로 떨어져 로봇 고장과
 * 구분되지 않고, 운영자가 "왜 실패했나"에 답할 수 없다(§4.9). §4.6에서 실패
 * 3분류를 빼고 두 불리언만 남긴 것과 같은 결정이다 — **판단은 밖으로, 사실은
 * 안으로.**
 *
 * **구분은 상태가 아니라 결함이 한다.** 두 경우 모두 태스크는 `FAILED`다.
 * 그래서 이 파일의 중심 시험은 **같은 시나리오 안에서 둘 다 만들어** 나란히
 * 놓는 것이다.
 */
class ControlAuthorityTest {

    /** `exclusive_control_required: true`인 프로파일. */
    private val exclusive = Path.of("..", "profile", "fixtures", "minimal.json").normalize()

    /** `false`인 실기종. 거절을 보는 데 쓴다. */
    private val shared = Path.of("..", "profile", "profiles", "quadruped-b.json").normalize()

    private val parameters = listOf(
        ParameterValue.newBuilder().setKey("location").setStringValue("dock-3").build(),
    )

    private fun harness(profile: Path = exclusive) = Harness(mapOf(ROBOT to profile))

    private fun Harness.dump() = oracle.dumpInternalState(
        DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build(),
    )

    private fun Harness.internal(taskId: String) =
        dump().tasksList.single { it.taskId == taskId }

    private fun Harness.loseAuthority() = oracle.forceControlAuthorityLoss(
        ForceControlAuthorityLossRequest.newBuilder().setRobotId(ROBOT).build(),
    )

    private fun Harness.forceFault(errorType: String, taskId: String) = oracle.forceFault(
        ForceFaultRequest.newBuilder()
            .setRobotId(ROBOT).setErrorType(errorType).setTaskId(taskId).build(),
    )

    /** `navigate_to` 하나를 `RUNNING`까지 민다. */
    private fun Harness.running(taskId: String): TaskHandle {
        val response = client().start(ROBOT, taskId, 1, "navigate_to", parameters)
        assertTrue(response.hasHandle(), "접수가 거절됐다: ${response.rejection}")
        advance(Duration.ofSeconds(1))
        assertEquals("RUNNING", internal(taskId).taskState, "RUNNING까지 못 밀었다")
        return response.handle
    }

    // ── 중심: 구분되는가

    @Test
    fun `로봇 고장의 FAILED와 error_type으로 구분된다`() {
        // **같은 시나리오 안에서 둘 다 만든다.** 하나만 만들면
        // "error_type이 하나뿐"이라 구분이 자명해진다.
        harness().use { harness ->
            harness.running("broken")
            harness.running("seized")

            // ① 로봇 고장 — 프로파일이 선언한 TERMINAL 모드.
            harness.forceFault(HARDWARE, "broken")
            // ② 제어권 상실 — 남은 태스크가 이것으로 죽는다.
            val lost = harness.loseAuthority()

            // **상태로는 구분되지 않는다.** 그것이 이 시험의 요점이다.
            assertEquals(EngineState.FAILED.name, harness.internal("broken").taskState)
            assertEquals(EngineState.FAILED.name, harness.internal("seized").taskState)
            assertEquals(listOf("seized"), lost.terminatedTaskIdsList)

            // 구분은 결함이 한다.
            val faults = harness.dump().faultsList.map { it.errorType }.toSet()
            assertEquals(setOf(HARDWARE, "CONTROL_AUTHORITY_LOST"), faults)
        }
    }

    @Test
    fun `제어권을 잃으면 진행 중 태스크가 FAILED로 종착한다`() {
        harness().use { harness ->
            harness.running(TASK)
            val lost = harness.loseAuthority()

            assertTrue(lost.raised, "결함이 안 섰다")
            assertEquals(listOf(TASK), lost.terminatedTaskIdsList)
            assertEquals(EngineState.FAILED.name, harness.internal(TASK).taskState)
        }
    }

    @Test
    fun `결함의 두 불리언이 모두 false다`() {
        // §4.9 — 권한이 없으면 무엇도 계속할 수 없다.
        harness().use { harness ->
            harness.running(TASK)
            harness.loseAuthority()

            val fault = harness.dump().faultsList.single { it.errorType == "CONTROL_AUTHORITY_LOST" }
            assertEquals(false, fault.canContinueCurrentTask)
            assertEquals(false, fault.canAcceptNewTask)
            assertTrue(fault.errorHint.isNotBlank(), "조치가 비어 있다")
            assertEquals("", fault.skillId, "권한은 기체의 것이지 스킬의 것이 아니다")
            assertEquals("", fault.taskId, "권한은 기체의 것이지 태스크의 것이 아니다")
        }
    }

    // ── 태스크가 없어도

    @Test
    fun `진행 중 태스크가 없어도 결함은 선다`() {
        // **태스크에만 붙이는 구현을 잡는다.** 유휴 상태에서 빼앗긴 것을
        // 소비자가 영영 모르면 다음 태스크를 보내고 나서야 안다.
        harness().use { harness ->
            val lost = harness.loseAuthority()
            assertTrue(lost.raised)
            assertEquals(emptyList(), lost.terminatedTaskIdsList)
            assertEquals(
                listOf("CONTROL_AUTHORITY_LOST"),
                harness.dump().faultsList.map { it.errorType },
            )
        }
    }

    @Test
    fun `종착한 태스크는 안 건드린다`() {
        // 래치는 §4.4의 불변식이다. 권한 상실이 그것을 뒤집으면 안 된다.
        harness().use { harness ->
            harness.running(TASK)
            harness.advance(Duration.ofSeconds(30))
            assertEquals(EngineState.SUCCEEDED.name, harness.internal(TASK).taskState)

            val lost = harness.loseAuthority()
            assertEquals(emptyList(), lost.terminatedTaskIdsList, "종착한 것을 죽였다")
            assertEquals(EngineState.SUCCEEDED.name, harness.internal(TASK).taskState)
        }
    }

    @Test
    fun `취소 중이던 태스크는 복구 실패가 된다`() {
        // §4.5 전파 규칙 3이 규칙 1을 이긴다 — 취소는 이미 결정된 것이고
        // 남은 질문은 "되돌리는 데 성공했는가"뿐이다.
        harness().use { harness ->
            val handle = harness.running(TASK)
            harness.client().cancel(ROBOT, handle)
            assertEquals("CANCELLING", harness.internal(TASK).taskState)

            harness.loseAuthority()
            assertEquals(
                EngineState.CANCELLED_RECOVERY_FAILED.name,
                harness.internal(TASK).taskState,
            )
        }
    }

    @Test
    fun `여러 태스크가 한꺼번에 죽는다`() {
        // 하나만 죽이는 구현을 잡는다.
        harness().use { harness ->
            harness.running("a")
            harness.running("b")

            val lost = harness.loseAuthority()
            assertEquals(setOf("a", "b"), lost.terminatedTaskIdsList.toSet())
            listOf("a", "b").forEach {
                assertEquals(EngineState.FAILED.name, harness.internal(it).taskState, it)
            }
        }
    }

    // ── 언제 성립하지 않는가

    @Test
    fun `배타 제어가 아닌 기종은 거절한다`() {
        // 프로파일이 "이 로봇은 배타 제어 모델이 아니다"라고 선언했으면
        // 빼앗길 권한이 없다. 허용하면 프로파일이 부정한 상황을 에뮬레이터가
        // 만들어 §10.1(거동은 프로파일에서 온다)이 깨진다.
        assertEquals(
            false,
            Files.readString(shared).contains("\"exclusive_control_required\": true"),
            "전제가 무너졌다 — 이 기종이 배타 제어가 됐다",
        )

        harness(shared).use { harness ->
            harness.running(TASK)
            val error = assertFailsWith<StatusRuntimeException> { harness.loseAuthority() }
            assertEquals(Status.Code.FAILED_PRECONDITION, error.status.code)

            assertEquals(emptyList(), harness.dump().faultsList, "거절해 놓고 결함을 세웠다")
            assertEquals("RUNNING", harness.internal(TASK).taskState, "거절해 놓고 태스크를 죽였다")
        }
    }

    @Test
    fun `CONTROL_AUTHORITY_LOST는 프로파일이 선언할 수 없다`() {
        // §10.5 — 어댑터가 런타임에 발행하는 것이라 스키마의 enum 에서
        // 빠져 있다. **스키마에서 읽는다** — 리터럴이면 계약이 자라도 조용하다.
        val declarable = Regex("\"errorType\"[\\s\\S]{0,900}?\"enum\"\\s*:\\s*\\[([^\\]]+)\\]")
            .find(Files.readString(schema))
            ?.groupValues?.get(1)
            ?.let { body -> Regex("\"([A-Z_]+)\"").findAll(body).map { it.groupValues[1] }.toList() }
        assertEquals(6, declarable?.size, "스키마에서 코어 여섯을 못 읽었다: $declarable")
        assertTrue("CONTROL_AUTHORITY_LOST" !in declarable!!)

        // 그러므로 ForceFault 로도 못 들어온다 — 전용 RPC가 따로 있다.
        harness().use { harness ->
            harness.running(TASK)
            val error = assertFailsWith<StatusRuntimeException> {
                harness.forceFault("CONTROL_AUTHORITY_LOST", TASK)
            }
            assertEquals(Status.Code.FAILED_PRECONDITION, error.status.code)
        }
    }

    @Test
    fun `모르는 기체는 NOT_FOUND다`() {
        harness().use { harness ->
            val error = assertFailsWith<StatusRuntimeException> {
                harness.oracle.forceControlAuthorityLoss(
                    ForceControlAuthorityLossRequest.newBuilder().setRobotId("r9").build(),
                )
            }
            assertEquals(Status.Code.NOT_FOUND, error.status.code)
        }
    }

    // ── 관측

    @Test
    fun `종착이 열린 스트림으로 나간다`() {
        // 여기서는 태스크 전이가 실제로 생기므로 **밀 것이 있다.**
        // 안 밀면 소비자가 그 자리를 결손으로 읽는다.
        harness().use { harness ->
            val handle = harness.running(TASK)
            val follower = harness.client().follow(ROBOT, handle)
            val before = follower.updates.size

            harness.loseAuthority()

            val added = follower.updates.drop(before)
            assertTrue(added.isNotEmpty(), "종착이 열린 스트림에 안 갔다")
            assertEquals(
                dev.picasso.contracts.v1.TaskState.TASK_STATE_FAILED,
                added.last().state,
            )
        }
    }

    private companion object {
        const val ROBOT = "r1"
        const val TASK = "t1"

        /** 픽스처가 선언한 `TERMINAL` 모드. 로봇 고장 쪽 `FAILED`를 만든다. */
        const val HARDWARE = "X_FIXTURE_SIMULATED_HARDWARE_FAULT"

        val schema: Path =
            Path.of("..", "profile", "schema", "capability-profile.schema.json").normalize()
    }
}
