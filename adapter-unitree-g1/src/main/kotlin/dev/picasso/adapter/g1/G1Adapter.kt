package dev.picasso.adapter.g1

import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.TaskState
import java.time.Instant
import kotlin.math.abs

/**
 * Unitree G1 어댑터 — 계약의 태스크를 벤더의 `sport` 서비스 위에 올린다.
 *
 * ## 이 클래스가 하는 일의 대부분은 **없는 것을 만드는 일**이다
 *
 * 조사(`profile/vendors/unitree-g1.json`)가 적은 대로 G1은 자기가 누구인지도,
 * 프로토콜 한계도, 결함도, 발행 주기도 말하지 않는다. 계약에는 그 자리가
 * 전부 있다. 그래서 어댑터가 채우며, **채우는 것 자체는 정당하다**(§9.7).
 * 정당하지 않은 것은 어댑터가 정한 값이 로봇의 선언인 척하는 것이고, 그것을
 * `profile/provenance/unitree-g1.json`이 항목마다 기록한다.
 *
 * ## 실물 없이 검증되는 범위
 *
 * 남쪽이 인터페이스이므로 여기 있는 거동은 전부 시험이 붙든다. **붙들지 못하는
 * 것은 "실물 G1이 이대로 행동하는가"이며 그것이 §9.7 ④·C-3이다.** 공식
 * 시뮬레이터로도 못 메운다 — 저수준만 흉내내므로 [SportService]를 아예
 * 답하지 않는다([G1Link] 참조).
 */
class G1Adapter(
    private val link: G1Link,
    private val identity: AdapterIdentity,
    private val fsm: FsmProfile,

    /**
     * 과열 판정 임계. **로봇이 주는 값이 아니라 우리가 정하는 값이다** —
     * G1은 결함을 보고하지 않고 SDK 예제가 클라이언트에서 온도로 판정한다.
     */
    private val overheatCelsius: Int = DEFAULT_OVERHEAT_CELSIUS,

    /** 이보다 큰 관절 각속도는 "아직 움직인다"로 본다. 역시 우리 값이다. */
    private val stillnessThreshold: Double = DEFAULT_STILLNESS,
) {

    private var task: RunningTask? = null
    private var issued = 0

    /** 지금 든 태스크의 상태. 아무것도 안 들었으면 `UNSPECIFIED`. */
    val state: TaskState
        get() = task?.state ?: TaskState.TASK_STATE_UNSPECIFIED

    /**
     * 태스크를 받는다.
     *
     * **순서가 뜻을 갖는다.** 신원 → 스킬 → 파라미터 → 남쪽 가용성 → 점유
     * 순으로 보며, 남쪽을 뒤에 두는 것은 앞의 것들이 설정 실수이고 그것은
     * 환경 사실보다 먼저 말해 줘야 하기 때문이다. 환경 탓을 먼저 하면 잘못
     * 배선된 어댑터가 시뮬레이터 탓으로 읽힌다.
     */
    fun accept(skillType: String, parameters: Map<String, Double>, startedAt: Instant): Acceptance {
        if (!identity.complete) {
            return Acceptance.Refused(
                Refusal.IDENTITY_UNSET,
                "기체 신원이 비어 있다 — G1은 신원을 말하지 않으므로 설정으로만 온다",
            )
        }
        if (skillType != SKILL) {
            return Acceptance.Refused(
                Refusal.UNSUPPORTED_SKILL,
                "이 어댑터가 드는 스킬은 '$SKILL' 하나다: '$skillType'",
            )
        }

        val missing = REQUIRED.filterNot { it in parameters }
        if (missing.isNotEmpty()) {
            return Acceptance.Refused(
                Refusal.PARAMETER_MISSING,
                "계약이 필수로 둔 파라미터가 없다: $missing",
            )
        }

        val sport = link.sport
            ?: return Acceptance.Refused(
                Refusal.NO_SPORT_SERVICE,
                "고수준 서비스가 없다 — 시뮬레이터이거나 ai_sport 가 안 떠 있다. " +
                    "받아 놓고 아무것도 안 하는 것이 가장 나쁘므로 거절한다",
            )

        task?.takeIf { !it.state.terminal }?.let {
            return Acceptance.Refused(Refusal.ALREADY_RUNNING, "이미 도는 태스크가 있다: ${it.id}")
        }

        val duration = parameters.getValue(P_DURATION)
        val outcome = sport.setVelocity(
            vx = parameters.getValue(P_FORWARD),
            vy = parameters.getValue(P_LATERAL),
            omega = parameters.getValue(P_YAW),
            durationSeconds = duration,
        )
        outcome.exceptionOrNull()?.let {
            return Acceptance.Refused(Refusal.LINK_ERROR, "SetVelocity 가 실패했다: ${it.message}")
        }

        // **결정적 식별자다.** 난수를 쓰면 같은 입력이 같은 기록을 남기지
        // 않는다 — §12.1의 결정성 규율과 §15.21이 세션 id를 카운터로 둔 것이
        // 같은 이유다.
        issued += 1
        val id = "${identity.robotId}-$issued"
        task = RunningTask(id, startedAt, duration, TaskState.TASK_STATE_RUNNING)
        return Acceptance.Accepted(id)
    }

    /**
     * 시간이 지났으면 성공으로 정착시킨다.
     *
     * **`sport` 서비스에 완료 통지가 없다.** 진행률을 내는 메서드도, 끝났다고
     * 알려 주는 것도 없어서(조사 문서) 어댑터가 시계로 판정한다.
     * `SetVelocity`가 받은 `duration`이 그 근거이며, 로봇이 스스로 멈추는 것을
     * 전제한다. 안 멈추면 [faults]가 `TERMINAL_STATE_VIOLATED`로 잡는다 —
     * 둘이 한 쌍이며 한쪽만 두면 "끝났다고 적었는데 계속 걷는" 상태가 조용히
     * 성공으로 남는다.
     */
    fun poll(now: Instant): TaskState {
        val current = task ?: return TaskState.TASK_STATE_UNSPECIFIED
        if (current.state != TaskState.TASK_STATE_RUNNING) return current.state

        val elapsed = (now.toEpochMilli() - current.startedAt.toEpochMilli()) / MILLIS_PER_SECOND
        if (elapsed >= current.durationSeconds) {
            current.state = TaskState.TASK_STATE_SUCCEEDED
        }
        return current.state
    }

    /**
     * 취소한다.
     *
     * **벤더에게 취소 프리미티브가 없다.** `StopMove()`가 실은
     * `SetVelocity(0, 0, 0)`이며 그것이 가진 전부다. 그래서 프로파일의
     * `cancel_support: YES`는 로봇의 선언이 아니라 **어댑터가 만든 것**이고
     * 출처 문서가 그렇게 적고 있다.
     *
     * `CANCELLING`에 머물지 않는다. 그 상태가 계약에 있는 이유는 *"휴머노이드는
     * 들고 있던 것을 내려놔야 하므로 즉시 중단이 물리적으로 불가능하다"* 인데
     * (`task.proto`) `move_relative`는 아무것도 들고 있지 않다. 무언가를 드는
     * 스킬이 이 어댑터에 들어오는 날 이 한 걸음이 실제 구간이 된다.
     */
    fun cancel(): Applied {
        val current = task ?: return Applied.Refused(Refusal.NO_TASK, "조작할 태스크가 없다")

        if (current.state.terminal) {
            return Applied.Refused(
                Refusal.TERMINAL_LATCHED,
                "${current.id} 은 이미 ${current.state} 다 — 종착은 되돌아가지 않는다(§4.4)",
            )
        }

        val sport = link.sport
            ?: return Applied.Refused(Refusal.NO_SPORT_SERVICE, "고수준 서비스가 없다")

        current.state = TaskState.TASK_STATE_CANCELLING
        val outcome = sport.setVelocity(0.0, 0.0, 0.0, 0.0)
        outcome.exceptionOrNull()?.let {
            // **되돌리지 않는다.** 멈추라고 시켰는데 실패한 것이므로 로봇이
            // 아직 움직이고 있을 수 있다. `RUNNING`으로 돌려 놓으면 그 사실이
            // 사라지고, `CANCELLED`로 넘기면 거짓말이 된다. 계약이 그 자리를
            // 위해 `CANCELLED_RECOVERY_FAILED`를 따로 두었다.
            current.state = TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED
            return Applied.Refused(Refusal.LINK_ERROR, "StopMove 가 실패했다: ${it.message}")
        }

        current.state = TaskState.TASK_STATE_CANCELLED
        return Applied.Ok
    }

    /**
     * 일시정지 — **언제나 거절한다.**
     *
     * 만들 수단이 없다. `SetFsmId(damp)`로 힘을 빼면 자세가 무너지므로
     * "멈췄다가 이어서 한다"가 아니고, 속도를 0으로 두는 것은 취소와 구별되지
     * 않는다. 프로파일의 `pause_support: NO`가 그 사실이며 여기가 그것을
     * 집행한다 — 선언과 거동이 갈리면 선언이 거짓말이 된다.
     */
    fun pause(): Applied = Applied.Refused(
        Refusal.NO_VENDOR_PRIMITIVE,
        "G1에 일시정지 프리미티브가 없다. damp(${fsm.damp}) 는 자세를 무너뜨리므로 재개가 아니다",
    )

    /**
     * 지금 관측되는 결함.
     *
     * 벤더 확장 접두사(`X_<VENDOR>_`)를 쓰는 것이 과열이다 — 계약의 코어
     * 여덟에 없고, 없는 것이 맞다. `TERMINAL_STATE_VIOLATED`는 코어이며
     * 어댑터만 발행한다(프로파일에 쓰면 게이트 3번이 막는다).
     */
    fun faults(): FaultObservation {
        val low = link.lowLevel.latestState()
            ?: return FaultObservation.NotObservable(
                "rt/lowstate 를 한 번도 못 받았다 — 결함 없음이 아니라 모른다",
            )

        val faults = mutableListOf<Fault>()

        val hottest = low.motorTemperaturesCelsius.maxOrNull()
        if (hottest != null && hottest >= overheatCelsius) {
            faults += Fault.newBuilder()
                .setErrorType(OVERHEAT)
                .setCanContinueCurrentTask(false)
                .setCanAcceptNewTask(false)
                .setErrorHint("모터 온도가 ${hottest}도다. 식을 때까지 기다린 뒤 재시도하십시오.")
                .build()
        }

        // **§4.4의 래치.** 종착했다고 우리가 적었는데 관절이 아직 돈다면 둘
        // 중 하나가 틀렸고, 어느 쪽이든 소비자가 알아야 한다.
        val current = task
        if (current != null && current.state.terminal &&
            low.motorVelocities.any { abs(it) > stillnessThreshold }
        ) {
            faults += Fault.newBuilder()
                .setErrorType(TERMINAL_VIOLATED)
                .setCanContinueCurrentTask(false)
                .setCanAcceptNewTask(false)
                .setErrorHint(
                    "${current.id} 을 ${current.state} 로 적었는데 관절이 아직 돈다. " +
                        "damp(${fsm.damp}) 로 세우고 원인을 확인하십시오.",
                )
                .build()
        }

        return FaultObservation.Observed(faults)
    }

    private class RunningTask(
        val id: String,
        val startedAt: Instant,
        val durationSeconds: Double,
        var state: TaskState,
    )

    private companion object {

        /** 이 어댑터가 드는 유일한 스킬. `skill_catalog.proto`의 `MoveRelativeV1`. */
        const val SKILL = "move_relative"

        const val P_FORWARD = "forward_speed"
        const val P_LATERAL = "lateral_speed"
        const val P_YAW = "yaw_rate"
        const val P_DURATION = "duration"

        /** 계약이 필수로 둔 넷. 하나라도 빠지면 어댑터가 값을 지어내게 된다. */
        val REQUIRED = listOf(P_FORWARD, P_LATERAL, P_YAW, P_DURATION)

        /** 벤더 확장(§4.6의 `X_<VENDOR>_` 규칙). 코어 여덟에 과열이 없고 그것이 맞다. */
        const val OVERHEAT = "X_UNITREE_MOTOR_OVERHEAT"

        const val TERMINAL_VIOLATED = "TERMINAL_STATE_VIOLATED"

        /**
         * 조사에서 벤더가 임계를 선언하지 않는 것을 확인했다. 이 값은 우리
         * 것이며 보수적으로 잡는다 — 틀리는 방향이 "일찍 멈춘다"여야 한다.
         */
        const val DEFAULT_OVERHEAT_CELSIUS = 80

        /** rad/s. 센서 잡음보다 크고 실제 보행보다 작은 자리. */
        const val DEFAULT_STILLNESS = 0.05

        const val MILLIS_PER_SECOND = 1000.0
    }
}

/** §4.4의 종착 넷. 나가는 화살표가 없는 상태들이다. */
private val TaskState.terminal: Boolean
    get() = this == TaskState.TASK_STATE_SUCCEEDED ||
        this == TaskState.TASK_STATE_FAILED ||
        this == TaskState.TASK_STATE_CANCELLED ||
        this == TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED
