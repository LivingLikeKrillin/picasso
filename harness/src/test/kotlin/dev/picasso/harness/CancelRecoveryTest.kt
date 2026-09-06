package dev.picasso.harness

import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import dev.picasso.mimic.control.v1.ForceFaultRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import dev.picasso.mimic.engine.TaskState as EngineState

/**
 * 완료 기준 8b — 소비자가 취소와 복구에서 무엇을 보는가.
 *
 * `RecoveryFailureTest`가 엔진 안에서 결함의 **내용**을 보고, 여기서는
 * 계약 표면으로 **관측 가능한가**를 본다. 둘이 나뉘는 이유는 §4.4의
 * "관측 순서가 `CANCELLING` → 종착"이 스트림 위의 주장이기 때문이다.
 */
class CancelRecoveryTest {

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

    private fun Harness.forceFault(errorType: String, taskId: String = TASK): String =
        oracle.forceFault(
            ForceFaultRequest.newBuilder()
                .setRobotId(ROBOT).setErrorType(errorType).setTaskId(taskId).build(),
        ).taskState

    /** `navigate_to`를 접수하고 `RUNNING`까지 민다. */
    private fun Harness.running(taskId: String = TASK): TaskHandle {
        val response = client().start(ROBOT, taskId, 1, "navigate_to", parameters)
        assertTrue(response.hasHandle(), "접수가 거절됐다: ${response.rejection}")
        advance(Duration.ofSeconds(1))
        assertEquals("RUNNING", internal(taskId).taskState, "RUNNING까지 못 밀었다")
        return response.handle
    }

    // ── 두 종착

    @Test
    fun `복구 성공은 CANCELLED다`() {
        harness().use { harness ->
            val handle = harness.running()
            val cancelled = harness.client().cancel(ROBOT, handle)
            assertEquals(
                TaskState.TASK_STATE_CANCELLING, cancelled.state,
                "취소가 즉시 종착했다 — §4.4는 복구를 동반한다고 못박았다",
            )

            harness.advance(Duration.ofSeconds(1))
            assertEquals(EngineState.CANCELLED.name, harness.internal().taskState)
            assertEquals(emptyList(), harness.dump().faultsList, "복구 성공인데 결함이 섰다")
        }
    }

    @Test
    fun `복구 실패는 CANCELLED_RECOVERY_FAILED다`() {
        harness().use { harness ->
            val handle = harness.running()
            harness.client().cancel(ROBOT, handle)
            assertEquals("CANCELLING", harness.internal().taskState, "창이 안 열렸다")

            assertEquals(
                EngineState.CANCELLED_RECOVERY_FAILED.name,
                harness.forceFault(INJECTED),
            )
        }
    }

    @Test
    fun `주입한 것과 다른 결함이 함께 선다`() {
        // **이것이 완료 기준 8b의 "동반한다"다.** 시험이 넣은 결함만 보면
        // 자기가 넣은 것을 자기가 확인하는 것이라 통째로 공허하다.
        harness().use { harness ->
            val handle = harness.running()
            harness.client().cancel(ROBOT, handle)
            harness.forceFault(INJECTED)

            val faults = harness.dump().faultsList.associateBy { it.errorType }
            assertEquals(setOf(INJECTED, "INTERNAL_ERROR"), faults.keys)

            val accompanying = faults.getValue("INTERNAL_ERROR")
            assertEquals(false, accompanying.canAcceptNewTask, "§4.4의 그 불리언이 참이다")
            assertEquals(false, accompanying.canContinueCurrentTask)
            assertEquals("", accompanying.skillId, "로봇 수준인데 스킬이 실렸다")
            assertEquals("KIND_UNTIL_CLEARED", accompanying.lifetimeKind)
            assertTrue(accompanying.errorHint.isNotBlank(), "조치가 비어 있다")
        }
    }

    // ── 관측 순서

    @Test
    fun `관측 순서가 CANCELLING → 종착이다`() {
        // §4.4 — 취소는 즉시가 아니다. `CANCELLING`을 건너뛰고 종착으로
        // 보내면 소비자는 로봇이 되돌리는 구간을 아예 못 본다.
        harness().use { harness ->
            val handle = harness.running()
            val follower = harness.client().follow(ROBOT, handle)

            harness.client().cancel(ROBOT, handle)
            harness.forceFault(INJECTED)

            val states = follower.updates.map { it.state }
            assertTrue(
                states.containsAll(
                    listOf(
                        TaskState.TASK_STATE_CANCELLING,
                        TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED,
                    ),
                ),
                "둘 중 하나가 안 나갔다: $states",
            )
            assertTrue(
                states.indexOf(TaskState.TASK_STATE_CANCELLING) <
                    states.indexOf(TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED),
                "종착이 CANCELLING보다 먼저다: $states",
            )
            assertEquals(
                TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED, states.last(),
                "종착 뒤에 더 나갔다: $states",
            )
        }
    }

    // ── 전제와 경계

    @Test
    fun `취소를 지원하지 않는 스킬은 여기 못 온다`() {
        // 픽스처의 `pick_place`는 `cancel_support: NO`다. 이 전제가 무너지면
        // 위 시험들이 어느 스킬을 도는지 알 수 없다.
        harness().use { harness ->
            val response = harness.client().start(
                ROBOT, "p1", 1, "pick_place",
                listOf(
                    ParameterValue.newBuilder().setKey("object_id").setStringValue("b").build(),
                    ParameterValue.newBuilder().setKey("destination").setStringValue("d").build(),
                ),
            )
            harness.advance(Duration.ofSeconds(1))

            val cancelled = harness.client().cancel(ROBOT, response.handle)
            assertEquals(
                RejectionCode.REJECTION_CODE_CANCEL_UNSUPPORTED,
                cancelled.rejection.code,
                "취소를 지원하지 않는다고 선언했는데 받았다",
            )
        }
    }

    @Test
    fun `새 태스크를 받는 것 자체는 막지 않는다`() {
        // §4.6 — **판단은 밖으로, 사실은 안으로.** 결함이
        // `can_accept_new_task=false`를 들지만 엔진은 그 판단을 대신하지
        // 않는다. 막으면 계약이 정책을 갖게 되고, 그 정책은 기종마다 다르다.
        // **그 선택이 보이도록 여기 적어 둔다** — 나중에 막고 싶어지면
        // 이 시험이 먼저 빨개진다.
        harness().use { harness ->
            val handle = harness.running()
            harness.client().cancel(ROBOT, handle)
            harness.forceFault(INJECTED)

            val faults = harness.dump().faultsList.single { it.errorType == "INTERNAL_ERROR" }
            assertEquals(false, faults.canAcceptNewTask, "전제가 무너졌다")

            val next = harness.client().start(ROBOT, "t2", 1, "navigate_to", parameters)
            assertTrue(
                next.hasHandle(),
                "엔진이 새 태스크를 막았다 — 판단이 안으로 들어왔다: ${next.rejection}",
            )
        }
    }

    private companion object {
        const val ROBOT = "r1"
        const val TASK = "t1"

        /** 복구 실패를 **일으키는** 결함. 엔진이 내는 것과 달라야 한다. */
        const val INJECTED = "LOCALIZATION_LOST"
    }
}
