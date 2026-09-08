package dev.picasso.adapter.digit

import dev.picasso.adapter.core.Acceptance
import dev.picasso.adapter.core.AdapterIdentity
import dev.picasso.adapter.core.Applied
import dev.picasso.adapter.core.FaultObservation
import dev.picasso.adapter.core.Refusal
import dev.picasso.adapter.core.SiteNames
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.wire.isTerminal
import java.time.Instant

/**
 * Agility Digit 어댑터 — **계약의 네 스킬 중 셋을 든다.**
 *
 * 조사한 셋 중 가장 많이 드는 기종이고, 그 이유가 하나다: **대상을 이름으로
 * 지시할 수 있다.** `ObjectSelector{name}`이 `action-goto`의 참조 프레임이자
 * `action-pick`의 대상이며, 그 이름을 로봇이 알게 만드는 것은 `add-object`로
 * 하는 사이트 작업이다(ADR 35).
 *
 * | 계약 스킬 | 벤더 자리 |
 * |---|---|
 * | `move_relative` | `action-duration{action-move{velocity}, duration}` |
 * | `navigate_to` | `action-goto{reference_frame: {name}}` |
 * | `pick_place` | `action-sequential[action-pick{object}, action-place{reference_frame}]` |
 * | `inspect` | **없다** — 관측 취득 액션이 어휘에 없다 |
 *
 * ## 앞 판이 틀렸던 자리
 *
 * 첫 판은 **정지 워치독**을 들고 있었다 — `action-move`에 지속시간이 없다고
 * 읽어 어댑터가 시계로 멈춰야 한다고 봤기 때문이다. `action-duration`이 그
 * 일을 하며 **로봇이 집행한다.** 워치독은 통째로 사라졌고 이 기종의 실패
 * 방향은 다른 둘과 **같다.** 근거를 벤더 문서가 아니라 제3자 래퍼에서 뽑은
 * 대가였다.
 *
 * 남은 것은 진짜다 — **래치**(종착이 안 래치되는 유일한 기종), **결함 분류
 * 포기**(자유 문자열뿐), **권한 상실**(로봇이 `action-idle`로 리셋된다).
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
     * 태스크를 받는다.
     *
     * 권한 검사가 스킬·파라미터 **뒤**에 온다 — 앞의 것들은 설정 실수이고
     * 권한은 환경 사실이다(G1·Spot 어댑터와 같은 순서).
     */
    fun accept(skillType: String, parameters: Map<String, Any>, startedAt: Instant): Acceptance {
        if (!identity.complete) {
            return Acceptance.Refused(Refusal.IDENTITY_UNSET, "기체 신원이 비어 있다")
        }

        val required = REQUIRED[skillType]
            ?: return Acceptance.Refused(
                Refusal.UNSUPPORTED_SKILL,
                "이 어댑터가 드는 스킬은 ${REQUIRED.keys} 셋이다: '$skillType'",
            )

        val missing = required.filterNot { it in parameters }
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

        val sent = when (skillType) {
            MOVE -> link.moveFor(
                yawRate = number(parameters, P_YAW),
                forward = number(parameters, P_FORWARD),
                lateral = number(parameters, P_LATERAL),
                durationSeconds = number(parameters, P_DURATION),
            )

            // **이름이 그대로 간다.** 옮기는 표가 없는 것이 ADR 34의 결정이고,
            // 그 이름을 로봇이 알게 만드는 것은 ADR 35의 사이트 작업이다.
            NAVIGATE -> link.gotoNamed(text(parameters, P_LOCATION))

            else -> link.pickAndPlace(
                objectName = text(parameters, P_OBJECT),
                destinationName = text(parameters, P_DESTINATION),
            )
        }

        val ref = sent.getOrElse {
            return Acceptance.Refused(Refusal.LINK_ERROR, "액션 전송이 실패했다: ${it.message}")
        }

        issued += 1
        val id = "${identity.robotId}-$issued"
        task = RunningTask(id, ref, startedAt, TaskState.TASK_STATE_RUNNING)
        return Acceptance.Accepted(id)
    }

    /**
     * 상태를 읽어 옮긴다.
     *
     * **관측일 뿐 조작이 아니다.** 앞 판에서는 여기가 정지를 보내는 자리였는데
     * `action-duration`이 그 일을 하므로 어댑터는 보기만 한다.
     *
     * 종착 뒤에도 계속 본다 — **`success`가 `running`으로 되돌아갈 수 있는
     * 유일한 기종**이고 그것이 §4.4의 래치 위반이다.
     */
    fun poll(now: Instant): TaskState {
        val current = task ?: return TaskState.TASK_STATE_UNSPECIFIED
        current.lastPolledAt = now

        // 권한 상실은 종착 여부보다 앞선다 — 로봇이 action-idle 로 리셋되므로
        // 하던 일이 사라진다.
        if (link.privilege != PrivilegeState.HELD && !current.state.isTerminal) {
            current.state = TaskState.TASK_STATE_FAILED
            return current.state
        }

        if (current.state.isTerminal) {
            if (link.status() == ActionStatus.RUNNING) latchViolated = true
            return current.state
        }

        // **`FAILURE` 가 여기 없어서 로봇이 신고한 실패에 도달할 수 없었다.**
        // 권한 상실만 태스크를 죽였고, 액션이 막히면 영원히 RUNNING 이었다.
        // `INACTIVE` 는 일부러 안 옮긴다 — 어느 액션의 것인지 모른다([ActionStatus]).
        when (link.status()) {
            ActionStatus.SUCCESS -> current.state = TaskState.TASK_STATE_SUCCEEDED
            ActionStatus.FAILURE -> current.state = TaskState.TASK_STATE_FAILED
            ActionStatus.RUNNING, ActionStatus.INACTIVE, null -> Unit
        }
        return current.state
    }

    /**
     * 취소 — `remove-action`.
     *
     * **벤더가 준 프리미티브다.** 앞 판은 이것이 없다고 보고 `action-stand`로
     * 덮어썼는데, 제3자 래퍼가 `remove-action`을 안 써서 생긴 오독이었다.
     *
     * 지운 **뒤에도 상태를 다시 본다.** 매뉴얼이 *컨테이너에 대해서는 성공한
     * 것처럼 보인다*고 적었으므로 응답만으로 멈췄다고 적을 수 없다 —
     * `pick_place`가 바로 그 컨테이너(`action-sequential`)다.
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
            return Applied.Refused(Refusal.CONTROL_AUTHORITY_LOST, "권한이 없어 지울 수도 없다")
        }

        current.state = TaskState.TASK_STATE_CANCELLING
        link.removeAction(current.ref).exceptionOrNull()?.let {
            current.state = TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED
            return Applied.Refused(Refusal.LINK_ERROR, "remove-action 이 실패했다: ${it.message}")
        }

        if (link.status() == ActionStatus.RUNNING) {
            // 지웠다는데 아직 돈다 — 매뉴얼이 예고한 컨테이너 거동이다.
            current.state = TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED
            return Applied.Refused(
                Refusal.LINK_ERROR,
                "remove-action 이 성공했다는데 액션이 아직 running 이다(컨테이너 거동)",
            )
        }

        current.state = TaskState.TASK_STATE_CANCELLED
        return Applied.Ok
    }

    /**
     * 일시정지 — 만들 수단이 없다. 언제나 거절한다.
     *
     * **이 `NO`는 근거가 있다.** SDK 메시지 정의를 전수로 읽었고 일시정지에
     * 해당하는 것이 없다. 앞 판의 `cancel_support: NO`가 근거 없이 적혔던 것과
     * 대비된다 — 같은 `NO`라도 값어치가 다르다.
     */
    fun pause(): Applied = Applied.Refused(
        Refusal.NO_VENDOR_PRIMITIVE,
        "SDK 메시지 전수에 일시정지가 없다. remove-action 은 지우기이지 재개가 아니다",
    )

    /**
     * 지금 관측되는 결함.
     *
     * **분류를 포기하는 것이 여기서는 정직한 처리다.** 벤더가 주는 실패 정보가
     * 사람이 읽는 자유 문자열 하나뿐이며 **이것은 SDK 전수를 읽고도 그대로였다.**
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

        link.statusInfo()?.takeIf { it.isNotBlank() }?.let {
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
        val ref: ActionRef,
        var lastPolledAt: Instant,
        var state: TaskState,
    )

    /**
     * 이 기체가 아는 사이트 이름(계약의 `GetKnownSiteNames`).
     *
     * ## 어디에 묻는가
     *
     * 세계 모델이다. **셋 중 유일하게 두 번 물어야 한다** — `notify-objects`
     * 가 id 목록을 주고, 이름은 객체마다 `get-object` 로 따로 받는다. 매뉴얼이
     * 그 흐름을 그대로 적어 두었고, 이름만 받는 질의는 없다.
     *
     * ## 하나라도 못 물으면 통째로 못 답한다
     *
     * 객체 하나의 `get-object` 가 실패했을 때 나머지로 답하면 **개수가 실제보다
     * 작게 올라가고**, 원장은 그것을 *"등록이 어긋났다"*(`CONTRADICTED`)로
     * 읽는다 — 사실은 우리가 다 못 물어본 것이다. **부분 답이 거짓 경보를
     * 만든다.** 그래서 [SiteNames.Unavailable] 로 통째로 접는다.
     *
     * ## 이름이 없는 객체는 뺀다
     *
     * `name` 이 선택 필드다. 이름 없는 객체는 사이트가 저작한 것이 아니고,
     * 로봇이 스스로 인지해 만든 것들이 여기 대부분이다 — 세면 개수가 부풀어
     * 확인이 통과한다.
     */
    fun knownSiteNames(): SiteNames {
        val ids = link.objectIds().getOrElse {
            return SiteNames.Unavailable("객체 목록을 못 받았다: ${it.message}")
        }

        val names = mutableListOf<String>()
        for (id in ids) {
            val name = link.objectName(id).getOrElse {
                return SiteNames.Unavailable("객체 $id 를 못 읽었다: ${it.message}")
            }
            if (!name.isNullOrBlank()) names += name
        }

        return SiteNames.Known(names.distinct().sorted())
    }

    private companion object {
        const val MOVE = "move_relative"
        const val NAVIGATE = "navigate_to"
        const val PICK_PLACE = "pick_place"

        const val P_FORWARD = "forward_speed"
        const val P_LATERAL = "lateral_speed"
        const val P_YAW = "yaw_rate"
        const val P_DURATION = "duration"
        const val P_LOCATION = "location"
        const val P_OBJECT = "object_id"
        const val P_DESTINATION = "destination"

        /** 계약이 스킬마다 필수로 둔 것. **이 표가 곧 이 어댑터가 드는 목록이다.** */
        val REQUIRED: Map<String, List<String>> = mapOf(
            MOVE to listOf(P_FORWARD, P_LATERAL, P_YAW, P_DURATION),
            NAVIGATE to listOf(P_LOCATION),
            PICK_PLACE to listOf(P_OBJECT, P_DESTINATION),
        )

        /**
         * 벤더 확장(§4.6의 `X_<VENDOR>_` 규칙).
         *
         * **분류하지 못했다는 뜻의 이름이다.** 코어 여덟 중 하나로 접으면
         * 우리가 지어낸 분류가 로봇의 판정으로 읽힌다.
         */
        const val UNCLASSIFIED = "X_AGILITYROBOTICS_UNCLASSIFIED"

        fun number(parameters: Map<String, Any>, key: String): Double =
            (parameters[key] as? Number)?.toDouble() ?: 0.0

        fun text(parameters: Map<String, Any>, key: String): String =
            parameters[key] as? String ?: ""
    }
}
