package dev.picasso.harness

import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.mimic.RegistryBinding
import dev.picasso.mimic.RegistrySource
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import dev.picasso.mimic.control.v1.PullRegistryRequest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import dev.picasso.mimic.engine.TaskState as EngineState

/**
 * 완료 기준 15 — **개정판 활성화 시 진행 중 태스크는 완주, 새 태스크는 새
 * 개정판.** 롤백도 같은 경로.
 *
 * ## pinning이 15번의 전부다
 *
 * *"진행 중인 태스크는 시작 시점 개정판으로 끝까지 간다"*(§8.4). 이것이
 * 없으면 활성화가 **진행 중인 로봇의 발밑을 바꾼다** — 45초짜리 태스크가
 * 도는 중에 파라미터 범위가 좁아지면 그 태스크는 자기가 접수될 때 유효했던
 * 값으로 실패하고, 소비자는 자기가 보낸 것이 왜 틀렸는지 알 수 없다.
 *
 * **`mimic`은 `registry`를 빌드 의존하지 않는다**(§3.2). 여기서는 시험이
 * 그 자리에 in-process 출처를 끼운다 — 운영에서는 HTTP다.
 */
class RevisionSwitchTest {

    private val fixture: String by lazy {
        Files.readString(Path.of("..", "profile", "fixtures", "minimal.json").normalize())
            .replace("\r\n", "\n")
    }

    /** 개정판을 흉내내는 출처. 시험이 무엇을 내줄지 직접 정한다. */
    private class FakeRegistry : RegistrySource {
        var binding: RegistryBinding? = null
        override fun binding(robotId: String): RegistryBinding? = binding
    }

    private val registry = FakeRegistry()

    private fun harness() = Harness(
        mapOf(ROBOT to Path.of("..", "profile", "fixtures", "minimal.json").normalize()),
        registrySource = registry,
    )

    private fun Harness.dump() = oracle.dumpInternalState(
        DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build(),
    )

    private fun Harness.pull() = oracle.pullRegistry(
        PullRegistryRequest.newBuilder().setRobotId(ROBOT).build(),
    )

    private fun Harness.state(taskId: String) =
        dump().tasksList.single { it.taskId == taskId }.taskState

    /** `pick_place`의 `grip_force` 상한을 좁힌 개정판. */
    private fun narrowed(revisionId: Long): RegistryBinding {
        val text = fixture
            .replace("\"revision\": 1,", "\"revision\": 2,")
            .replace("\"max_value\": 120", "\"max_value\": 10")
        check(text != fixture) { "치환이 아무것도 바꾸지 못했다" }
        return RegistryBinding(revisionId, 2, text)
    }

    private val pickPlace = listOf(
        ParameterValue.newBuilder().setKey("object_id").setStringValue("b").build(),
        ParameterValue.newBuilder().setKey("destination").setStringValue("d").build(),
        ParameterValue.newBuilder().setKey("grip_force").setNumberValue(80.0).build(),
    )

    // ── 반영

    @Test
    fun `당기면 새 개정판이 반영된다`() {
        harness().use { harness ->
            assertEquals(0L, harness.dump().let { it.tasksList.size }.toLong(), "전제")
            registry.binding = narrowed(42)

            val pulled = harness.pull()
            assertTrue(pulled.changed, "안 바뀌었다")
            assertEquals(42L, pulled.profileRevisionId)
        }
    }

    @Test
    fun `레지스트리가 모르면 파일 모드로 돈다`() {
        // §3.2의 "없을 때" — 레지스트리가 안 떠 있어도 에뮬레이터는 돈다.
        harness().use { harness ->
            registry.binding = null
            assertTrue(!harness.pull().changed)

            val started = harness.client().start(ROBOT, "t1", 1, "pick_place", pickPlace)
            assertTrue(started.hasHandle(), "파일 모드가 안 돈다: ${started.rejection}")
        }
    }

    @Test
    fun `같은 개정판을 다시 당기면 아무 일도 없다`() {
        // 유령 세대는 소비자의 캐시를 헛되이 무효화한다.
        harness().use { harness ->
            registry.binding = narrowed(42)
            val first = harness.pull()
            val second = harness.pull()

            assertTrue(first.changed)
            assertTrue(!second.changed, "같은 개정판인데 바뀌었다고 한다")
            assertEquals(first.capabilityEpoch, second.capabilityEpoch, "세대가 헛되이 올랐다")
        }
    }

    @Test
    fun `반영이 epoch를 올린다`() {
        // §8.2 — **발신자가 올린다.** 레지스트리가 아니라 여기다.
        harness().use { harness ->
            val before = harness.dump().capabilityEpoch
            registry.binding = narrowed(42)
            harness.pull()
            assertTrue(
                harness.dump().capabilityEpoch > before,
                "반영했는데 세대가 그대로다 — 소비자가 캐시를 안 버린다",
            )
        }
    }

    // ── pinning

    @Test
    fun `진행 중 태스크는 옛 개정판으로 완주한다`() {
        // **완료 기준 15의 중심이다.** 활성화가 진행 중인 로봇의 발밑을
        // 바꾸면 안 된다.
        harness().use { harness ->
            val started = harness.client().start(ROBOT, "t1", 1, "pick_place", pickPlace)
            assertTrue(started.hasHandle(), "접수가 거절됐다: ${started.rejection}")
            harness.advance(Duration.ofSeconds(1))
            assertEquals("RUNNING", harness.state("t1"))

            // grip_force 상한이 80보다 낮은 개정판으로 갈아탄다.
            registry.binding = narrowed(42)
            assertTrue(harness.pull().changed)

            harness.advance(Duration.ofSeconds(60))
            assertEquals(
                EngineState.SUCCEEDED.name, harness.state("t1"),
                "옛 개정판으로 접수된 태스크가 새 제약에 걸렸다 — pinning이 없다",
            )
        }
    }

    @Test
    fun `그 뒤 새 태스크는 새 개정판을 쓴다`() {
        // **pinning만 보면 "활성화가 아무 일도 안 한다"가 통과한다.**
        // 둘을 같은 시나리오에서 본다.
        harness().use { harness ->
            harness.client().start(ROBOT, "t1", 1, "pick_place", pickPlace)
            harness.advance(Duration.ofSeconds(1))

            registry.binding = narrowed(42)
            harness.pull()

            // 새 태스크는 좁아진 상한에 걸려야 한다.
            val blocked = harness.client().start(ROBOT, "t2", 1, "pick_place", pickPlace)
            assertEquals(
                RejectionCode.REJECTION_CODE_PARAMETER_INVALID, blocked.rejection.code,
                "새 태스크가 옛 제약을 쓴다 — 반영이 안 됐다",
            )

            // 그리고 옛 태스크는 그대로 완주한다.
            harness.advance(Duration.ofSeconds(60))
            assertEquals(EngineState.SUCCEEDED.name, harness.state("t1"))
        }
    }

    @Test
    fun `롤백도 같은 경로다`() {
        // §8.4 ⑥ — 이전 개정판을 다시 내주면 된다. 별도 경로가 아니다.
        harness().use { harness ->
            registry.binding = narrowed(42)
            harness.pull()
            assertEquals(
                RejectionCode.REJECTION_CODE_PARAMETER_INVALID,
                harness.client().start(ROBOT, "t1", 1, "pick_place", pickPlace).rejection.code,
                "전제가 무너졌다 — 좁아지지 않았다",
            )

            registry.binding = RegistryBinding(7, 1, fixture)
            assertTrue(harness.pull().changed, "롤백이 반영 안 됐다")

            assertTrue(
                harness.client().start(ROBOT, "t2", 1, "pick_place", pickPlace).hasHandle(),
                "롤백했는데 옛 제약이 안 돌아왔다",
            )
        }
    }

    @Test
    fun `롤백 중 진행 중이던 태스크도 pinning된다`() {
        harness().use { harness ->
            registry.binding = narrowed(42)
            harness.pull()

            // 좁아진 개정판에서 통과하는 값으로 건다.
            val ok = listOf(
                ParameterValue.newBuilder().setKey("object_id").setStringValue("b").build(),
                ParameterValue.newBuilder().setKey("destination").setStringValue("d").build(),
                ParameterValue.newBuilder().setKey("grip_force").setNumberValue(5.0).build(),
            )
            assertTrue(harness.client().start(ROBOT, "t1", 1, "pick_place", ok).hasHandle())
            harness.advance(Duration.ofSeconds(1))

            registry.binding = RegistryBinding(7, 1, fixture)
            harness.pull()

            harness.advance(Duration.ofSeconds(60))
            assertEquals(EngineState.SUCCEEDED.name, harness.state("t1"), "롤백이 발밑을 바꿨다")
        }
    }

    @Test
    fun `새 개정판의 실패 모드가 걸린다`() {
        // **추첨이 문서에서 오는데 그 문서가 바뀌었다.** 옛 추첨을 캐시한
        // 채로 두면 새 개정판의 실패 모드가 영영 안 걸린다 — 실측으로 그
        // 주입이 안 잡혔다. 반영이 능력만 바꾸고 거동은 안 바꾸는 것이다.
        harness().use { harness ->
            // **먼저 한 번 돌려 옛 추첨을 캐시에 올린다.** 당김 전에 추첨을
            // 한 번도 안 하면 캐시가 비어 있어 "처음 한 번만 만든다"는
            // 구현도 통과한다 — 실측으로 그 주입이 안 잡혔다. 게다가 이쪽이
            // 현실적인 순서다: 에뮬레이터는 이미 돌고 있었다.
            harness.client().start(
                ROBOT, "warmup", 1, "navigate_to",
                listOf(ParameterValue.newBuilder().setKey("location").setStringValue("d").build()),
            )
            harness.advance(Duration.ofSeconds(1))
            harness.advance(Duration.ofSeconds(60))
            assertEquals(
                EngineState.SUCCEEDED.name, harness.state("warmup"),
                "옛 개정판에서는 안 걸려야 한다 — 전제가 무너졌다",
            )

            // navigate_to 가 반드시 실패하는 개정판으로 갈아탄다.
            val always = fixture
                .replace("\"revision\": 1,", "\"revision\": 3,")
                .replace("\"rate\": 0.002", "\"rate\": 1.0")
            check(always != fixture) { "치환이 아무것도 바꾸지 못했다" }
            registry.binding = RegistryBinding(55, 3, always)
            assertTrue(harness.pull().changed)


            harness.client().start(
                ROBOT, "n1", 1, "navigate_to",
                listOf(ParameterValue.newBuilder().setKey("location").setStringValue("d").build()),
            )
            harness.advance(Duration.ofSeconds(1))
            harness.advance(Duration.ofSeconds(60))

            assertEquals(
                EngineState.NEEDS_INTERVENTION.name, harness.state("n1"),
                "새 개정판의 실패 모드가 안 걸렸다 — 옛 추첨을 쓰고 있다",
            )
        }
    }

    @Test
    fun `진행 중 태스크의 갱신도 접수 시점 규칙으로 본다`() {
        // **pinning의 빠진 절반이었다.** `TaskMachine`은 전이표와 소요시간을
        // 들고 있었지만 **갱신의 파라미터 검사가 라이브 능력을 봤다.** 그래서
        // 개정판이 좁아진 뒤 갱신하면 자기가 접수될 때 유효했던 값이
        // 거절됐고, 소비자는 자기가 보낸 것이 왜 갑자기 틀렸는지 알 수 없었다.
        harness().use { harness ->
            assertTrue(harness.client().start(ROBOT, "t1", 1, "pick_place", pickPlace).hasHandle())
            harness.advance(Duration.ofSeconds(1))

            registry.binding = narrowed(42)
            assertTrue(harness.pull().changed)

            // 같은 값으로 revision 을 올려 갱신한다 — 접수 시점에는 유효했다.
            val updated = harness.client().start(ROBOT, "t1", 2, "pick_place", pickPlace)
            assertTrue(
                updated.hasHandle(),
                "진행 중 태스크의 갱신이 새 제약에 걸렸다 — pinning이 반쪽이다: " +
                    "${updated.rejection}",
            )

            // 그리고 새 태스크는 여전히 새 제약을 쓴다.
            assertEquals(
                RejectionCode.REJECTION_CODE_PARAMETER_INVALID,
                harness.client().start(ROBOT, "t2", 1, "pick_place", pickPlace).rejection.code,
                "새 태스크까지 옛 제약을 쓴다",
            )
        }
    }

    @Test
    fun `반영이 CapabilityChanged로 나간다`() {
        // 소비자는 이벤트나 헤더로 안다. 안 나가면 다음 요청까지 모른다.
        harness().use { harness ->
            registry.binding = narrowed(42)
            harness.pull()

            val changed = harness.publisher.events().filter { it.hasCapabilityChanged() }
            assertTrue(changed.isNotEmpty(), "반영이 이벤트로 안 나갔다")
            assertEquals(
                dev.picasso.contracts.v1.CapabilityChangeCause
                    .CAPABILITY_CHANGE_CAUSE_BINDING_CHANGED,
                changed.last().capabilityChanged.cause,
                "바인딩 변경을 런타임 축소로 알렸다 — 소비자의 대응이 다르다",
            )
        }
    }

    @Test
    fun `읽을 수 없는 문서는 반영하지 않는다`() {
        // 반쯤 반영된 상태가 남으면 그 기체가 무엇을 쓰는지 아무도 모른다.
        harness().use { harness ->
            val before = harness.dump().capabilityEpoch
            registry.binding = RegistryBinding(9, 9, "{ not json")

            assertTrue(!harness.pull().changed, "깨진 문서를 반영했다")
            assertEquals(before, harness.dump().capabilityEpoch, "세대만 올랐다")
            assertTrue(
                harness.client().start(ROBOT, "t1", 1, "pick_place", pickPlace).hasHandle(),
                "옛 프로파일이 망가졌다",
            )
        }
    }

    private companion object {
        const val ROBOT = "r1"
    }
}
