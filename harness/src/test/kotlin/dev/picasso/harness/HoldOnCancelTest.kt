package dev.picasso.harness

import dev.picasso.client.TaskFollower
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.mimic.control.v1.ForceFaultRequest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 완료 기준 8b의 나머지 반 — 소비자가 취소 뒤에 **로봇이 무엇을 들고 있는지**를
 * 계약 표면에서 보는가(`WatchTaskResponse.hold`, 설계 §4.4).
 *
 * `CancelRecoveryTest`가 종착 둘과 동반 결함을 보고, 여기서는 그 종착에
 * 실린 잔여 물리 상태를 본다. 불변식은 하나다 — **`CANCELLED`와 `HOLDING`은
 * 같은 갱신에 오지 않는다.** 그것이 깨지면 소비자가 물건을 든 채 멈춘 기체에
 * 다음 일감을 준다.
 *
 * 프로파일은 [CancellablePickPlace] — 픽스처의 `pick_place`가 취소 불가라 한 값만 바꾼 사본이다.
 */
class HoldOnCancelTest {

    private val parameters = listOf(
        ParameterValue.newBuilder().setKey("object_id").setStringValue("SEQ-IN-02.BIN-A").build(),
        ParameterValue.newBuilder().setKey("destination").setStringValue("RACK-204.S01").build(),
    )

    private fun harness() = Harness(mapOf(ROBOT to CancellablePickPlace.profile()))

    private fun Harness.forceFault(errorType: String): String = oracle.forceFault(
        ForceFaultRequest.newBuilder().setRobotId(ROBOT).setErrorType(errorType).setTaskId(TASK).build(),
    ).taskState

    /** `pick_place`를 접수하고 `CANCELLING`까지 민 뒤 팔로워를 돌려준다. */
    private fun Harness.cancelling(): Pair<TaskHandle, TaskFollower> {
        val started = client().start(ROBOT, TASK, 1, "pick_place", parameters)
        assertTrue(started.hasHandle(), "접수가 거절됐다: ${started.rejection}")
        val follower = client().follow(ROBOT, started.handle)
        advance(Duration.ofSeconds(1))
        val cancelled = client().cancel(ROBOT, started.handle)
        assertEquals(TaskState.TASK_STATE_CANCELLING, cancelled.state, "취소가 CANCELLING 이 아니다")
        return started.handle to follower
    }

    @Test
    fun `복구 성공은 빈손이다 — CANCELLED 와 HOLDING 은 함께 오지 않는다`() {
        harness().use { harness ->
            val (_, follower) = harness.cancelling()
            harness.advance(Duration.ofSeconds(1))

            val updates = follower.awaitTerminal()
            val last = updates.last()
            assertEquals(TaskState.TASK_STATE_CANCELLED, last.state)
            assertEquals(HoldKind.HOLD_KIND_EMPTY, last.hold.kind)

            val offenders = updates.filter {
                it.state == TaskState.TASK_STATE_CANCELLED && it.hold.kind == HoldKind.HOLD_KIND_HOLDING
            }
            assertEquals(emptyList(), offenders, "복구까지 마쳤다면서 든 채다")
            assertTrue(updates.any { it.hold.kind == HoldKind.HOLD_KIND_HOLDING }, "든 적이 없다면 이 시험은 공허하다")
        }
    }

    @Test
    fun `복구 실패는 든 채이고 무엇을 들었는지 실린다`() {
        harness().use { harness ->
            val (_, follower) = harness.cancelling()
            assertEquals(
                "CANCELLED_RECOVERY_FAILED", harness.forceFault("LOCALIZATION_LOST"),
                "전제가 무너졌다 — 복구 실패가 안 됐다",
            )

            val last = follower.awaitTerminal().last()
            assertEquals(TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED, last.state)
            assertEquals(HoldKind.HOLD_KIND_HOLDING, last.hold.kind)
            assertEquals("SEQ-IN-02.BIN-A", last.hold.objectRef, "대상의 이름이 실려야 한다(§15.78)")
        }
    }

    @Test
    fun `놓쳐서 복구에 실패했으면 빈손이다`() {
        // 복구 실패가 언제나 든 채는 아니다 — PAYLOAD_LOST 가 그 반례다.
        harness().use { harness ->
            val (_, follower) = harness.cancelling()
            assertEquals("CANCELLED_RECOVERY_FAILED", harness.forceFault("PAYLOAD_LOST"))

            val last = follower.awaitTerminal().last()
            assertEquals(TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED, last.state)
            assertEquals(HoldKind.HOLD_KIND_EMPTY, last.hold.kind)
        }
    }

    private companion object {
        const val ROBOT = "r1"
        const val TASK = "t1"
    }
}
