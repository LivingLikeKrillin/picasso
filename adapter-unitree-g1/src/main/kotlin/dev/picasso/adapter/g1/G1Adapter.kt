package dev.picasso.adapter.g1

import dev.picasso.adapter.core.Acceptance
import dev.picasso.adapter.core.AdapterIdentity
import dev.picasso.adapter.core.Applied
import dev.picasso.adapter.core.FaultObservation
import dev.picasso.adapter.core.HoldObservation
import dev.picasso.adapter.core.ProgressObservation
import dev.picasso.adapter.core.Refusal
import dev.picasso.adapter.core.RobotAdapter
import dev.picasso.adapter.core.SiteNames
import dev.picasso.contracts.v1.FailureClass
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.wire.isTerminal
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
) : RobotAdapter {

    private var task: RunningTask? = null

    /** 지금 든 태스크의 상태. 아무것도 안 들었으면 `UNSPECIFIED`. */
    override val state: TaskState
        get() = task?.state ?: TaskState.TASK_STATE_UNSPECIFIED

    /**
     * 태스크를 받는다.
     *
     * **순서가 뜻을 갖는다.** 신원 → 스킬 → 파라미터 → 남쪽 가용성 → 점유
     * 순으로 보며, 남쪽을 뒤에 두는 것은 앞의 것들이 설정 실수이고 그것은
     * 환경 사실보다 먼저 말해 줘야 하기 때문이다. 환경 탓을 먼저 하면 잘못
     * 배선된 어댑터가 시뮬레이터 탓으로 읽힌다.
     */
    override fun accept(taskId: String, skillType: String, parameters: Map<String, Any>, startedAt: Instant): Acceptance {
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
                Refusal.VENDOR_SURFACE_ABSENT,
                "고수준 서비스가 없다 — 시뮬레이터이거나 ai_sport 가 안 떠 있다. " +
                    "받아 놓고 아무것도 안 하는 것이 가장 나쁘므로 거절한다",
            )

        task?.takeIf { !it.state.isTerminal }?.let {
            return Acceptance.Refused(Refusal.ALREADY_RUNNING, "이미 도는 태스크가 있다: ${it.id}")
        }

        // 계약의 파라미터는 타입이 선언돼 있고(NUMBER) 호스트가 그것을 검사하지만, 수가 아닌 값이 오면 지어내지 않고 거절한다.
        val numbers = REQUIRED.associateWith { (parameters[it] as? Number)?.toDouble() }
        numbers.filterValues { it == null }.keys.takeIf { it.isNotEmpty() }?.let {
            return Acceptance.Refused(Refusal.PARAMETER_MISSING, "수가 아닌 파라미터: $it")
        }
        val duration = numbers.getValue(P_DURATION)!!
        val outcome = sport.setVelocity(
            vx = numbers.getValue(P_FORWARD)!!,
            vy = numbers.getValue(P_LATERAL)!!,
            omega = numbers.getValue(P_YAW)!!,
            durationSeconds = duration,
        )
        outcome.exceptionOrNull()?.let {
            // **전송 실패와 로봇의 거절은 다르다.** 코드가 왔으면 로봇이 답한 것이고 그 뜻을
            // 정준 분류로 옮긴다 — 이 기종은 태스크가 시계로 성공하므로 코드가 나올 자리가 여기뿐이다.
            if (it is UnitreeApiException) return vendorRejected(it)
            return Acceptance.Refused(Refusal.LINK_ERROR, "SetVelocity 가 실패했다: ${it.message}")
        }

        // 계약의 task_id 가 곧 이 어댑터의 식별자다 — 정체성 열(15.1)의 하류 작업 ID 를 새로 만들지 않는다.
        task = RunningTask(taskId, startedAt, duration, TaskState.TASK_STATE_RUNNING)
        return Acceptance.Accepted(taskId)
    }

    /**
     * 벤더 에러 코드 → 정준 분류(미들웨어 중앙 설계 §1.4).
     *
     * | 코드 | 분류 |
     * |---|---|
     * | `LOCO_ERR_INVALID_FSM_ID`·`LOCOSTATE_NOT_AVAILABLE`·`ARM_ACTION_ERR_INVALID_FSM_ID`·`G1_AGV_ERR_NOT_INIT`·`ARM_ACTION_ERR_HOLDING` | `PRECONDITION_FAILED` — 로봇이 그 명령을 받을 상태가 아니다 |
     * | 그 밖(잘못된 id·SDK·통신·실행 실패)과 이름 없는 정수 | `UNCLASSIFIED` — 뜻을 지어내지 않는다 |
     */
    private fun vendorRejected(e: UnitreeApiException): Acceptance.Refused {
        val failureClass = when (e.error) {
            UnitreeError.UT_ROBOT_LOCO_ERR_INVALID_FSM_ID,
            UnitreeError.UT_ROBOT_LOCO_ERR_LOCOSTATE_NOT_AVAILABLE,
            UnitreeError.UT_ROBOT_ARM_ACTION_ERR_INVALID_FSM_ID,
            UnitreeError.UT_ROBOT_ARM_ACTION_ERR_HOLDING,
            UnitreeError.UT_ROBOT_G1_AGV_ERR_NOT_INIT -> FailureClass.FAILURE_CLASS_PRECONDITION_FAILED
            else -> FailureClass.FAILURE_CLASS_UNCLASSIFIED
        }
        return Acceptance.Refused(
            Refusal.VENDOR_REJECTED,
            "SetVelocity 를 로봇이 거절했다: ${e.message}",
            failureClass = failureClass,
            vendorDetail = "${e.error?.name ?: "unnamed"} code=${e.rawCode}",
        )
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
     *
     * **반대쪽 구멍은 `SportModeState_`가 막는다**(2026-09-09). 시계는 로봇이
     * 실제로 그 모드에 있었는지를 모르므로, 명령이 받아들여지고 아무 일도
     * 안 일어난 경우에도 시간이 지나면 성공이 된다. [faults]의
     * `X_UNITREE_FSM_UNEXPECTED`가 그 자리이며 **여기서 막지는 않는다** —
     * 판정을 바꾸면 어느 FSM 에서 속도가 듣는지를 우리가 안다고 주장하게 된다.
     */
    override fun poll(now: Instant): TaskState {
        val current = task ?: return TaskState.TASK_STATE_UNSPECIFIED
        if (current.state != TaskState.TASK_STATE_RUNNING) return current.state

        val elapsed = (now.toEpochMilli() - current.startedAt.toEpochMilli()) / MILLIS_PER_SECOND
        if (elapsed >= current.durationSeconds) {
            current.state = TaskState.TASK_STATE_SUCCEEDED
        }
        return current.state
    }

    /**
     * **도는 태스크의 갱신**(§4.4) — 여기서는 **지시값 하나다.**
     *
     * `SetVelocity(vx, vy, omega, duration)` 은 지시값이므로 새 값을 보내면 그것이 지금의 지시다. §4.4 가 갱신을
     * `Halt` → `Reset` → `Start` 로 적은 것은 미션·액션 층의 모양이고, 지시값 층에서는 그 셋이 한 칸으로 접힌다
     * (Spot 의 명령 계층과 같다, §15.109). 그래서 반만 적용되는 자리가 없다 — 거절되면 앞 지시가 그대로 산다.
     *
     * **[cancel] 이 이미 이 사실 위에 서 있었다**: 이 어댑터의 취소가 `SetVelocity(0,0,0)` 이다. 지시값을 갈아
     * 멈추는 것이 되면, 갈아 다르게 가는 것도 된다.
     */
    override fun update(taskId: String, skillType: String, parameters: Map<String, Any>, at: Instant): Applied {
        val current = task ?: return Applied.Refused(Refusal.NO_TASK, "조작할 태스크가 없다")
        if (current.state.isTerminal) {
            return Applied.Refused(Refusal.TERMINAL_LATCHED, "${current.id} 은 이미 ${current.state} 다")
        }
        val sport = link.sport ?: return Applied.Refused(Refusal.VENDOR_SURFACE_ABSENT, "고수준 서비스가 없다")

        val numbers = REQUIRED.associateWith { (parameters[it] as? Number)?.toDouble() }
        numbers.filterValues { it == null }.keys.takeIf { it.isNotEmpty() }?.let {
            return Applied.Refused(Refusal.PARAMETER_MISSING, "수가 아닌 파라미터: $it")
        }
        val duration = numbers.getValue(P_DURATION)!!
        val outcome = sport.setVelocity(
            vx = numbers.getValue(P_FORWARD)!!,
            vy = numbers.getValue(P_LATERAL)!!,
            omega = numbers.getValue(P_YAW)!!,
            durationSeconds = duration,
        )
        outcome.exceptionOrNull()?.let {
            // **[Applied.Refused] 에는 정준 분류 칸이 없다** — 조작의 거절은 계약에서 코드와 사정 문자열로만
            // 나가므로(호스트가 그것만 옮긴다) 로봇이 준 코드를 사정에 붙인다. 접수 쪽 거절과 다른 점이다.
            val refused = if (it is UnitreeApiException) vendorRejected(it) else null
            return Applied.Refused(
                refused?.reason ?: Refusal.LINK_ERROR,
                refused?.detail ?: "SetVelocity 가 실패했다: ${it.message}",
            )
        }
        current.startedAt = at
        current.durationSeconds = duration
        return Applied.Ok
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
    override fun cancel(): Applied {
        val current = task ?: return Applied.Refused(Refusal.NO_TASK, "조작할 태스크가 없다")

        if (current.state.isTerminal) {
            return Applied.Refused(
                Refusal.TERMINAL_LATCHED,
                "${current.id} 은 이미 ${current.state} 다 — 종착은 되돌아가지 않는다(§4.4)",
            )
        }

        val sport = link.sport
            ?: return Applied.Refused(Refusal.VENDOR_SURFACE_ABSENT, "고수준 서비스가 없다")

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
    override fun pause(): Applied = Applied.Refused(
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
    override fun faults(): FaultObservation {
        val low = link.lowLevel.latestState()
            ?: return FaultObservation.NotObservable(
                "rt/lowstate 를 한 번도 못 받았다 — 결함 없음이 아니라 모른다",
            )

        val faults = mutableListOf<Fault>()

        val hottest = low.motorTemperaturesCelsius.maxOrNull()
        if (hottest != null && hottest >= overheatCelsius) {
            faults += Fault.newBuilder()
                .setErrorType(OVERHEAT)
                // 과열은 기체 결함이다 — 판정 주체는 우리이지만(임계값) 분류의 뜻은 벤더 종료 조건과 같다.
                .setFailureClass(FailureClass.FAILURE_CLASS_HARDWARE_FAULT)
                .setVendorDetail("MotorState_.temperature=$hottest")
                .setCanContinueCurrentTask(false)
                .setCanAcceptNewTask(false)
                .setErrorHint("모터 온도가 ${hottest}도다. 식을 때까지 기다린 뒤 재시도하십시오.")
                .build()
        }

        // **우리가 기대한 모드가 아니다.** 태스크가 도는 중인데 로봇의 FSM 이
        // 배포자가 선언한 `start` 가 아니면, `SetVelocity` 는 받아들여졌는데
        // 로봇은 damp·sit·squat 같은 데 있는 것이다. 그 조합에서 가장 나쁜
        // 결과가 **아무것도 안 하면서 시계로 성공이 되는 것**이고, 그것이
        // 이 어댑터가 성공을 시계로 적기 때문에 특히 조용하다.
        //
        // **막지 않는다.** 어느 FSM 에서 속도 명령이 듣는지를 벤더가 열거해
        // 주지 않으므로 *"못 움직인다"* 고 단정할 근거가 없다. 우리가 아는
        // 것은 **기대와 관측이 어긋났다**는 사실뿐이고, 그것만 낸다.
        //
        // **이 관측이 저수준 상태 뒤에 갇혀 있다.** 위의 이른 반환이 `lowstate`
        // 를 못 받으면 통째로 `NotObservable` 을 내므로, 운동 상태만 오는
        // 대상에서는 이 결함이 안 보인다. 채널 둘이 독립인데 관측 가능성을
        // 하나로 접은 것이며, 고치려면 [FaultObservation] 이 부분 관측을
        // 표현할 수 있어야 한다 — 지금은 둘 중 하나다.
        val running = task
        val mode = link.sportMode?.latestSportMode()
        if (running != null && running.state == TaskState.TASK_STATE_RUNNING &&
            mode != null && mode.fsmId != fsm.start
        ) {
            faults += Fault.newBuilder()
                .setErrorType(FSM_UNEXPECTED)
                // 명령은 받아들여졌는데 로봇이 그 모드에 없다 — 시작 조건 미충족이다.
                .setFailureClass(FailureClass.FAILURE_CLASS_PRECONDITION_FAILED)
                .setVendorDetail("SportModeState_.fsm_id=${mode.fsmId} expected=${fsm.start}")
                .setCanContinueCurrentTask(false)
                .setCanAcceptNewTask(false)
                .setErrorHint(
                    "${running.id} 이 도는데 로봇 FSM 은 ${mode.fsmId} 다(기대 ${fsm.start}). " +
                        "명령은 받아들여졌으나 로봇이 그 모드에 없다 — 시계로 성공이 적히기 전에 확인하십시오.",
                )
                .build()
        }

        // **§4.4의 래치.** 종착했다고 우리가 적었는데 관절이 아직 돈다면 둘
        // 중 하나가 틀렸고, 어느 쪽이든 소비자가 알아야 한다.
        val current = task
        if (current != null && current.state.isTerminal &&
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
        /** **갱신이 다시 센다** — 이 기종은 태스크가 시계로 종착하므로 새 지시는 새 시각에서 시작한다. */
        var startedAt: Instant,
        var durationSeconds: Double,
        var state: TaskState,
    )

    /**
     * 잔여 물리 상태(§4.4) — **볼 수 없다.**
     *
     * 벤더가 파지 판정을 주지 않는다. 161 심볼에 "쥐고 있다"는 없고, 있는 것은
     * `HandState_.press_sensor_state` — 원시 압력값이다. 문턱을 우리가 정해
     * 불리언으로 만들면 그것은 로봇의 답처럼 보이는 우리의 짐작이다(§15.65).
     * 이 어댑터는 드는 스킬이 `move_relative` 하나라 태스크가 무언가를 쥐게
     * 만들지도 않지만, 그것이 빈손이라는 뜻은 아니다 — 사람이 쥐여 줬을 수 있다.
     * 그래서 취소는 `CANCELLED`로 적히고 이 답이 "모른다"를 함께 나른다.
     */
    override fun hold(): HoldObservation = HoldObservation.NotObservable(
        "벤더가 파지 판정을 주지 않는다 — HandState_.press_sensor_state 는 원시 압력값이다",
    )

    /**
     * 진행률 — **셀 것이 없다.**
     *
     * 심볼 161 개 전수에 태스크의 진척을 말하는 것이 없다. 있는 퍼센트는 `AgvBmsState_.battery_percentage`
     * 하나이고 그것은 배터리이지 일이 아니다. 시간으로 나누면 그 숫자는 우리가 지은 것이다(§15.108).
     */
    override fun progress(): ProgressObservation = ProgressObservation.NotObservable(
        "벤더 표면에 태스크 진척이 없다 — 퍼센트는 AgvBmsState_.battery_percentage 뿐이고 그것은 배터리다",
    )

    /**
     * 이 기체가 아는 사이트 이름(계약의 `GetKnownSiteNames`).
     *
     * **물어볼 데가 없다.** `sport` 서비스는 `SetFsmId`·`GetFsmId`·
     * `SetVelocity` 를 주고, 저수준 채널은 관절과 IMU 를 준다. 세계 모델도
     * 지도도 이름 붙은 무엇도 없다 — 그래서 이 기체는 `navigate_to` 를
     * 아예 안 들며(`common-set-exemptions.json`), 등록할 자리도 없다.
     *
     * ## 왜 빈 목록이 아니라 [SiteNames.Unsupported] 인가
     *
     * 빈 목록은 *"등록할 수 있는데 아직 하나도 안 했다"* 는 뜻이고, 그러면
     * 운영자에게 **없는 자리에 등록하라고 요구하게 된다.** 로봇이 답할 수
     * 없는 요구를 화면에 띄우면 그 화면 전체를 안 믿게 된다.
     *
     * 링크를 보지 않는 유일한 답이다 — [G1Link.sport]가 떠 있든 아니든
     * 이름을 둘 자리는 생기지 않으므로, **환경이 아니라 기종의 사실이다.**
     */
    override fun knownSiteNames(): SiteNames = SiteNames.Unsupported

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

        /**
         * 태스크가 도는데 FSM 이 기대한 모드가 아니다.
         *
         * 코어 여덟에 없어서 벤더 확장이다. **`TERMINAL_STATE_VIOLATED` 와
         * 짝이지만 반대편이다** — 저쪽은 *끝났다고 적었는데 아직 움직인다*,
         * 이쪽은 *돈다고 적었는데 그 모드가 아니다*.
         */
        const val FSM_UNEXPECTED = "X_UNITREE_FSM_UNEXPECTED"

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

// **종착 판정은 계약이 갖는다**(`contracts` 의 `TaskStates`). 여기서 다시
// 적었던 것이 그 목록의 **세 번째 사본**이었고, `mimic` 이 상태를 하나 더하는
// 날 이 어댑터만 옛 목록으로 판정하게 된다. 계약 모듈이 프로젝트 내 의존 0
// 이라 누구나 쓸 수 있고, 그래서 두 번째로 적을 이유가 없었다.
