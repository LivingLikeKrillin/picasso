package dev.picasso.harness

import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import dev.picasso.mimic.control.v1.ForceFaultRequest
import dev.picasso.mimic.control.v1.ForceTerminalViolationRequest
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
 * 완료 기준 8c — 종착 확정 후 로봇이 계속 수행 중이면
 * `TERMINAL_STATE_VIOLATED`가 발행되고 **태스크 상태는 종착에 머문다**.
 *
 * **"머문다"는 아무도 안 건드리면 자명하다.** 그래서 위반을 관측한 **뒤에**
 * 명령을 보내고, 시간을 흘리고, 재시도까지 걸어 본 다음에도 종착인지 본다.
 *
 * 이것이 §4.4의 *"흡수했으면 흡수가 실패했다는 사실을 숨기지 않는다"*이다.
 * 계약의 단순함은 지키되, 계약이 실물과 어긋나 있다는 사실은 관측 가능하게
 * 만든다.
 */
class TerminalViolationTest {

    private val profile = Path.of("..", "profile", "fixtures", "minimal.json").normalize()

    private val parameters = listOf(
        ParameterValue.newBuilder().setKey("location").setStringValue("dock-3").build(),
    )

    private fun harness() = Harness(mapOf(ROBOT to profile))

    private fun Harness.dump() = oracle.dumpInternalState(
        DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build(),
    )

    private fun Harness.internal(taskId: String = TASK) =
        dump().tasksList.single { it.taskId == taskId }

    private fun Harness.violate(taskId: String = TASK) = oracle.forceTerminalViolation(
        ForceTerminalViolationRequest.newBuilder()
            .setRobotId(ROBOT).setTaskId(taskId).build(),
    )

    private fun Harness.forceFault(errorType: String, taskId: String = TASK) =
        oracle.forceFault(
            ForceFaultRequest.newBuilder()
                .setRobotId(ROBOT).setErrorType(errorType).setTaskId(taskId).build(),
        )

    /**
     * `navigate_to` 하나를 완주(`SUCCEEDED`)까지 민다.
     *
     * **전진 두 번이다.** `advance` 한 번은 `tick` 한 번이고, 첫 tick은
     * 접수한 태스크를 집어 드는 데(`ACCEPTED` → `RUNNING`) 쓴다. 한 번만
     * 밀면 `RUNNING`에서 멈춘다.
     */
    private fun Harness.succeeded(taskId: String = TASK): TaskHandle {
        val response = client().start(ROBOT, taskId, 1, "navigate_to", parameters)
        assertTrue(response.hasHandle(), "접수가 거절됐다: ${response.rejection}")
        advance(Duration.ofSeconds(1))
        advance(Duration.ofSeconds(30))
        assertEquals(EngineState.SUCCEEDED.name, internal(taskId).taskState, "완주를 못 했다")
        return response.handle
    }

    // ── 결함이 나가는가

    @Test
    fun `종착 확정 후 위반을 관측하면 결함이 나간다`() {
        harness().use { harness ->
            harness.succeeded()
            assertEquals(emptyList(), harness.dump().faultsList, "전제가 무너졌다 — 이미 결함이 있다")

            val response = harness.violate()
            assertTrue(response.raised, "결함이 안 섰다")

            val fault = harness.dump().faultsList.single()
            assertEquals("TERMINAL_STATE_VIOLATED", fault.errorType)
            assertEquals(TASK, fault.taskId, "어느 태스크인지 안 알려준다")
        }
    }

    @Test
    fun `결함의 두 불리언이 모두 false다`() {
        // §4.4 — 계약과 실물이 어긋나 있으므로 무엇도 계속할 수 없다.
        harness().use { harness ->
            harness.succeeded()
            harness.violate()

            val fault = harness.dump().faultsList.single()
            assertEquals(false, fault.canContinueCurrentTask)
            assertEquals(false, fault.canAcceptNewTask)
            assertTrue(fault.errorHint.isNotBlank(), "조치가 비어 있다")
        }
    }

    @Test
    fun `결함이 스킬을 지목하지 않는다`() {
        // **스킬은 멀쩡하다.** 깨진 것은 "종착하면 끝"이라는 계약의 가정이다.
        // `skill_id`가 실리면 소비자가 "그 스킬만 못 쓴다"로 읽는데(§4.6이
        // 표현하라고 만든 바로 그 구분) 사실은 로봇이 계약과 어긋나게 움직이는
        // 것이므로 로봇 수준이다.
        harness().use { harness ->
            harness.succeeded()
            harness.violate()
            assertEquals("", harness.dump().faultsList.single().skillId)
        }
    }

    @Test
    fun `같은 태스크에 두 번 관측해도 결함은 하나다`() {
        // 내면 소비자의 목록이 부풀고 해소 하나로 안 지워지는 유령이 남는다.
        harness().use { harness ->
            harness.succeeded()
            assertTrue(harness.violate().raised)
            assertTrue(!harness.violate().raised, "두 번째가 새 결함으로 잡혔다")
            assertEquals(1, harness.dump().faultsList.size)
        }
    }

    // ── 그래도 종착에 머무는가

    @Test
    fun `그래도 태스크 상태는 종착에 머문다`() {
        harness().use { harness ->
            harness.succeeded()
            assertEquals(EngineState.SUCCEEDED.name, harness.violate().taskState)
            assertEquals(EngineState.SUCCEEDED.name, harness.internal().taskState)
        }
    }

    @Test
    fun `위반 뒤에 명령을 보내도 종착이다`() {
        // **아무도 안 건드리면 "머문다"가 자명하다.** §4.4의 명령 다섯 중
        // 종착에서 받을 수 있는 것이 없어야 한다.
        harness().use { harness ->
            val handle = harness.succeeded()
            harness.violate()

            val rejected = mapOf(
                "pause" to harness.client().pause(ROBOT, handle).rejection,
                "cancel" to harness.client().cancel(ROBOT, handle).rejection,
                "retry" to harness.client().retry(ROBOT, handle).rejection,
            )
            assertEquals(3, rejected.size, "표가 비면 아무것도 확인 안 한다")
            rejected.forEach { (name, rejection) ->
                assertEquals(
                    RejectionCode.REJECTION_CODE_INVALID_TRANSITION, rejection.code,
                    "$name 이 종착 태스크를 움직였다",
                )
            }
            assertEquals(EngineState.SUCCEEDED.name, harness.internal().taskState)
        }
    }

    @Test
    fun `위반 뒤에 시간을 흘려도 종착이다`() {
        // 명령만 막는 구현을 잡는다 — `tick()`이 상태를 되돌리면 래치가
        // 명령 경로에만 걸린 것이다(§4.4가 경계하는 바로 그것).
        harness().use { harness ->
            harness.succeeded()
            harness.violate()

            harness.advance(Duration.ofSeconds(600))
            assertEquals(EngineState.SUCCEEDED.name, harness.internal().taskState)
            assertEquals(1.0, harness.internal().progress, "진행률이 되감겼다")
        }
    }

    @Test
    fun `위반 뒤에 갱신을 보내도 종착이다`() {
        // 갱신은 명령이 아니라 `StartTask` 재전송이다 — §4.4의 두 번째 표.
        harness().use { harness ->
            harness.succeeded()
            harness.violate()

            val update = harness.client().start(ROBOT, TASK, 2, "navigate_to", parameters)
            assertEquals(
                RejectionCode.REJECTION_CODE_INVALID_TRANSITION, update.rejection.code,
                "종착 태스크가 갱신을 받았다",
            )
            assertEquals(EngineState.SUCCEEDED.name, harness.internal().taskState)
        }
    }

    @Test
    fun `위반 뒤에 결함을 주입해도 종착이다`() {
        // `ForceFault`는 종착 넷을 거절한다(Task 3). 그것이 위반 뒤에도
        // 그대로인지 본다 — 위반이 상태를 "덜 종착"으로 만들면 안 된다.
        harness().use { harness ->
            harness.succeeded()
            harness.violate()

            val error = assertFailsWith<StatusRuntimeException> {
                harness.forceFault("LOCALIZATION_LOST")
            }
            assertEquals(Status.Code.FAILED_PRECONDITION, error.status.code)
            assertEquals(EngineState.SUCCEEDED.name, harness.internal().taskState)
        }
    }

    // ── 언제 성립하지 않는가

    @Test
    fun `종착 전에는 위반을 만들 수 없다`() {
        // 아직 래치되지 않았으므로 "래치가 깨졌다"가 아무 뜻도 안 갖는다.
        //
        // **비종착 여섯을 전부 도는 것은 엔진 시험이 한다**
        // (`TerminalLatchTest`). 여기서는 표면으로 곧장 갈 수 있는 둘만
        // 보되, 거절이 상태를 안 건드리는 것까지 확인한다.
        harness().use { harness ->
            harness.client().start(ROBOT, TASK, 1, "navigate_to", parameters)
            assertEquals("ACCEPTED", harness.internal().taskState)
            assertRejectsViolation(harness, "ACCEPTED")

            harness.advance(Duration.ofSeconds(1))
            assertEquals("RUNNING", harness.internal().taskState)
            assertRejectsViolation(harness, "RUNNING")
        }
    }

    private fun assertRejectsViolation(harness: Harness, state: String) {
        val error = assertFailsWith<StatusRuntimeException>(message = state) { harness.violate() }
        assertEquals(Status.Code.FAILED_PRECONDITION, error.status.code, state)
        assertEquals(
            emptyList(),
            harness.dump().faultsList.filter { it.errorType == "TERMINAL_STATE_VIOLATED" },
            "$state 에서 거절해 놓고 결함을 세웠다",
        )
        assertEquals(false, harness.internal().terminalViolationSeen, "$state 에서 표시가 섰다")
        assertEquals(state, harness.internal().taskState, "$state 에서 거절하고도 상태를 바꿨다")
    }

    @Test
    fun `모르는 태스크는 NOT_FOUND다`() {
        harness().use { harness ->
            val error = assertFailsWith<StatusRuntimeException> { harness.violate("nope") }
            assertEquals(Status.Code.NOT_FOUND, error.status.code)
        }
    }

    // ── 오라클

    @Test
    fun `덤프의 terminal_violation_seen이 선다`() {
        // **결함 목록만으로는 "그 태스크에 대해" 관측했다는 사실을 태스크
        // 쪽에서 확인할 수 없다.** 태스크가 둘일 때 어느 쪽인지 갈린다.
        harness().use { harness ->
            harness.succeeded("t1")
            harness.succeeded("t2")
            assertTrue(harness.internal("t1").terminalViolationSeen.not())

            harness.violate("t1")

            assertEquals(true, harness.internal("t1").terminalViolationSeen)
            assertEquals(false, harness.internal("t2").terminalViolationSeen, "엉뚱한 태스크에 섰다")
        }
    }

    @Test
    fun `위반이 발행 축으로 나간다`() {
        // **위반은 전이가 아니라 관측이다.** 태스크 로그에 아무것도 안
        // 적히므로 열린 `WatchTask`에 밀 것이 없고, 소비자에게 닿는 길은
        // `EngineListener` → `EventStream` → 발행 축 하나뿐이다(§4.7).
        // 그래서 여기서는 발행자를 세는 것이 **맞다** — 다른 시험들과 달리
        // 이 RPC에는 밀어내기 축이 아예 없다.
        harness().use { harness ->
            harness.succeeded()
            val before = harness.publisher.publications.size

            harness.violate()
            assertTrue(harness.publisher.publications.size > before, "결함이 안 나갔다")
        }
    }

    private companion object {
        const val ROBOT = "r1"
        const val TASK = "t1"
    }
}
