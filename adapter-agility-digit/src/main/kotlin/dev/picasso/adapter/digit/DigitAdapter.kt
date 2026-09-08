package dev.picasso.adapter.digit

import dev.picasso.adapter.core.Acceptance
import dev.picasso.adapter.core.AdapterIdentity
import dev.picasso.adapter.core.Applied
import dev.picasso.adapter.core.FaultObservation
import dev.picasso.adapter.core.Refusal
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.wire.isTerminal
import java.time.Instant

/**
 * Agility Digit 어댑터 — **정지 워치독과 래치가 두 축이다.**
 *
 * ## 이 어댑터가 앞의 둘과 다른 점
 *
 * | | G1 | Spot | **Digit** |
 * |---|---|---|---|
 * | 정지 | 로봇이 스스로(`duration`) | 로봇이 스스로(`end_time`) | **어댑터가 시켜야 한다** |
 * | 어댑터가 죽으면 | 로봇이 선다 | 로봇이 선다 | **계속 걷는다** |
 * | 종착 래치 | 관절 각속도로 추론 | 미션 상태가 확정 | **벤더가 되돌아간다고 명시** |
 * | 결함 어휘 | 온도에서 우리가 판정 | `BehaviorFault.Cause` | **자유 문자열뿐 — 분류를 포기한다** |
 *
 * 위 표의 첫 두 줄이 이 기종의 **실패 방향이 반대**라는 뜻이고, 어댑터는 그것을
 * [stopDueAt]으로 **드러내되 고치지 못한다.** 감시자가 그 시각을 보고 어댑터가
 * 살아 있는지 판단해야 하며, 그 감시자는 이 모듈 밖이다.
 */
class DigitAdapter(
    private val link: DigitLink,
    private val identity: AdapterIdentity,
) {

    private var task: RunningTask? = null
    private var issued = 0
    private var latchViolated = false

    /** 지금 든 태스크의 상태. 아무것도 안 들었으면 `UNSPECIFIED`. */
    val state: TaskState
        get() = task?.state ?: TaskState.TASK_STATE_UNSPECIFIED

    /**
     * 어댑터가 정지를 보내야 하는 시각. 없으면 갚을 빚이 없다.
     *
     * **이 값을 노출하는 것이 이 클래스가 할 수 있는 전부다.** `action-move`가
     * 지속시간을 안 받으므로 정지는 어댑터의 의무인데, 어댑터가 죽으면 그
     * 의무가 아무 데도 안 남고 로봇은 계속 걷는다. 밖에서 이 시각을 보고
     * 있으면 적어도 **누가 갚기로 했는지**가 보인다.
     */
    val stopDueAt: Instant?
        get() = task?.takeIf { !it.state.isTerminal && !it.stopSent }?.stopDueAt

    /**
     * 태스크를 받는다.
     *
     * 권한 검사가 스킬·파라미터 **뒤**에 온다 — 앞의 것들은 설정 실수이고
     * 권한은 환경 사실이다. 환경 탓을 먼저 하면 잘못 배선된 어댑터가 권한 탓으로
     * 읽힌다(G1·Spot 어댑터와 같은 순서).
     */
    fun accept(skillType: String, parameters: Map<String, Double>, startedAt: Instant): Acceptance {
        if (!identity.complete) {
            return Acceptance.Refused(Refusal.IDENTITY_UNSET, "기체 신원이 비어 있다")
        }
        if (skillType != SKILL) {
            return Acceptance.Refused(
                Refusal.UNSUPPORTED_SKILL,
                "이 어댑터가 드는 스킬은 '$SKILL' 하나다: '$skillType'",
            )
        }

        val missing = REQUIRED.filterNot { it in parameters }
        if (missing.isNotEmpty()) {
            return Acceptance.Refused(Refusal.PARAMETER_MISSING, "필수 파라미터가 없다: $missing")
        }

        if (link.privilege != PrivilegeState.HELD) {
            return Acceptance.Refused(
                Refusal.CONTROL_AUTHORITY_LOST,
                "change-action-command 권한이 없다 — 보내도 로봇이 안 듣는다",
            )
        }

        task?.takeIf { !it.state.isTerminal }?.let {
            return Acceptance.Refused(Refusal.ALREADY_RUNNING, "이미 도는 태스크가 있다: ${it.id}")
        }

        val duration = parameters.getValue(P_DURATION)
        val sent = link.move(
            yawRate = parameters.getValue(P_YAW),
            forward = parameters.getValue(P_FORWARD),
            lateral = parameters.getValue(P_LATERAL),
        )
        sent.exceptionOrNull()?.let {
            return Acceptance.Refused(Refusal.LINK_ERROR, "action-move 가 실패했다: ${it.message}")
        }

        issued += 1
        val id = "${identity.robotId}-$issued"
        task = RunningTask(
            id = id,
            // **여기서 빚이 생긴다.** 로봇은 이 시각을 모르고 우리만 안다.
            stopDueAt = startedAt.plusMillis((duration * MILLIS_PER_SECOND).toLong()),
            state = TaskState.TASK_STATE_RUNNING,
        )
        return Acceptance.Accepted(id)
    }

    /**
     * 시간이 됐으면 **정지를 보내고** 성공으로 정착시킨다.
     *
     * **이 호출이 빠지면 로봇이 계속 걷는다.** 다른 두 어댑터의 `poll`은
     * 관측일 뿐이지만 여기서는 조작이다. 정지 전송이 실패하면 성공으로 적지
     * 않는다 — 걷고 있는데 끝났다고 적는 것이 이 기종에서 가장 나쁜 거짓말이다.
     */
    fun poll(now: Instant): TaskState {
        val current = task ?: return TaskState.TASK_STATE_UNSPECIFIED

        // 권한 상실은 종착 여부보다 앞선다 — 로봇이 action-idle 로 리셋되므로
        // 하던 일이 사라진다.
        if (link.privilege != PrivilegeState.HELD && !current.state.isTerminal) {
            current.state = TaskState.TASK_STATE_FAILED
            return current.state
        }

        if (current.state.isTerminal) {
            // **§4.4의 래치.** 종착했다고 적었는데 벤더가 running 을 다시 보내면
            // 그것이 매뉴얼이 예고한 그 전이다. 상태는 안 되돌리고 결함을 남긴다.
            if (link.status() == ActionStatus.RUNNING) latchViolated = true
            return current.state
        }

        if (!now.isBefore(current.stopDueAt)) {
            val stopped = link.stand()
            if (stopped.isFailure) {
                // 멈추라고 시켰는데 실패했다. 로봇은 아직 걷고 있다.
                current.state = TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED
                return current.state
            }
            current.stopSent = true
            current.state = TaskState.TASK_STATE_SUCCEEDED
        }
        return current.state
    }

    /**
     * 취소 — 정지와 **같은 액션으로 나간다**.
     *
     * 벤더에게 취소 프리미티브가 없고 중단은 다른 액션으로 덮어쓰는 것이다.
     * 그래서 프로파일의 `cancel_support: YES`는 로봇의 선언이 아니라 어댑터가
     * 만든 것이며 출처 문서가 그렇게 적고 있다.
     */
    fun cancel(): Applied {
        val current = task ?: return Applied.Refused(Refusal.NO_TASK, "조작할 태스크가 없다")
        if (current.state.isTerminal) {
            return Applied.Refused(
                Refusal.TERMINAL_LATCHED,
                "${current.id} 은 이미 ${current.state} 다 — 종착은 되돌아가지 않는다(§4.4)",
            )
        }
        if (link.privilege != PrivilegeState.HELD) {
            current.state = TaskState.TASK_STATE_FAILED
            return Applied.Refused(Refusal.CONTROL_AUTHORITY_LOST, "권한이 없어 멈추라고 할 수도 없다")
        }

        current.state = TaskState.TASK_STATE_CANCELLING
        val stopped = link.stand()
        stopped.exceptionOrNull()?.let {
            current.state = TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED
            return Applied.Refused(Refusal.LINK_ERROR, "action-stand 가 실패했다: ${it.message}")
        }
        current.stopSent = true
        current.state = TaskState.TASK_STATE_CANCELLED
        return Applied.Ok
    }

    /** 일시정지 — 만들 수단이 없다. 언제나 거절한다. */
    fun pause(): Applied = Applied.Refused(
        Refusal.NO_VENDOR_PRIMITIVE,
        "Digit 에 일시정지 프리미티브가 없다. action-stand 는 덮어쓰기이지 재개가 아니다",
    )

    /**
     * 지금 관측되는 결함.
     *
     * **분류를 포기하는 것이 여기서는 정직한 처리다.** 벤더가 주는 실패 정보가
     * 사람이 읽는 자유 문자열 하나뿐이라, 그것을 코어 여덟 중 하나로 접으면
     * 로봇이 판정한 것처럼 보인다. 벤더 확장 하나로 내고 원문을 그대로 싣는다 —
     * **분류하지 못한다는 사실 자체가 정보다.**
     */
    fun faults(): FaultObservation {
        val faults = mutableListOf<Fault>()

        if (link.privilege != PrivilegeState.HELD) {
            faults += Fault.newBuilder()
                .setErrorType("CONTROL_AUTHORITY_LOST")
                .setCanContinueCurrentTask(false)
                .setCanAcceptNewTask(false)
                .setErrorHint("change-action-command 권한을 잃었습니다. 로봇이 action-idle 로 리셋됐습니다.")
                .build()
        }

        if (latchViolated) {
            faults += Fault.newBuilder()
                .setErrorType("TERMINAL_STATE_VIOLATED")
                .setCanContinueCurrentTask(false)
                .setCanAcceptNewTask(false)
                .setErrorHint("종착한 태스크에 running 이 다시 왔습니다. 벤더 매뉴얼이 예고한 전이입니다.")
                .build()
        }

        link.error()?.takeIf { it.isNotBlank() }?.let {
            faults += Fault.newBuilder()
                .setErrorType(UNCLASSIFIED)
                .setCanContinueCurrentTask(false)
                .setCanAcceptNewTask(true)
                .setErrorHint(it)
                .build()
        }

        return FaultObservation.Observed(faults)
    }

    private class RunningTask(
        val id: String,
        val stopDueAt: Instant,
        var state: TaskState,
        var stopSent: Boolean = false,
    )

    private companion object {
        const val SKILL = "move_relative"

        const val P_FORWARD = "forward_speed"
        const val P_LATERAL = "lateral_speed"
        const val P_YAW = "yaw_rate"
        const val P_DURATION = "duration"

        val REQUIRED = listOf(P_FORWARD, P_LATERAL, P_YAW, P_DURATION)

        /**
         * 벤더 확장(§4.6의 `X_<VENDOR>_` 규칙).
         *
         * **분류하지 못했다는 뜻의 이름이다.** 코어 여덟 중 하나로 접으면
         * 우리가 지어낸 분류가 로봇의 판정으로 읽힌다.
         */
        const val UNCLASSIFIED = "X_AGILITYROBOTICS_UNCLASSIFIED"

        const val MILLIS_PER_SECOND = 1000.0
    }
}
