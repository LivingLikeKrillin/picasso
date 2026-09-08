package dev.picasso.adapter.spot

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
 * Boston Dynamics Spot 어댑터 — 두 스킬을 **서로 다른 벤더 계층**에 올린다.
 *
 * ## G1 어댑터와 다른 점이 곧 측정 결과다
 *
 * G1 어댑터는 하는 일의 대부분이 *없는 것을 만드는 일*이었다. 여기서는 벤더가
 * 주는 것이 훨씬 많고, **그래서 무엇이 여전히 없는지가 선명해진다.**
 *
 * | | G1 | Spot |
 * |---|---|---|
 * | 태스크 생명주기 | 없음(명령뿐) | `MissionService`가 거의 그대로 |
 * | 일시정지 | 수단 없음 | `PauseMission` — **단, 미션 계층 스킬만** |
 * | 취소 | `SetVelocity(0,0,0)`로 만든 것 | `StopMission`·`StopCommand` |
 * | `NEEDS_INTERVENTION` | 없음 | `AnswerQuestion`·`Prompt` |
 * | 제어 권한 상실 | 못 채움 | `LeaseUseResult.STATUS_REVOKED` |
 * | 신원 | 질의 없음 | `GetRobotId` (아직 안 읽는다) |
 * | **대상 시맨틱** | **없음** | **없음** |
 *
 * 마지막 줄이 이 어댑터가 재러 온 것이다. `navigate_to`가 되는 이유는 GraphNav이
 * 이미 시맨틱 결속을 해 두고 `destination_waypoint_id`로 노출하기 때문이고,
 * `pick_place`·`inspect`가 안 되는 이유는 그 층이 없어서 픽셀과 3D 점과 카메라
 * 이름이 그대로 올라오기 때문이다. 자세한 것은 `profile/distance/spot-arm.json`.
 */
class SpotAdapter(
    private val link: SpotLink,
    private val identity: AdapterIdentity,
) {

    private var task: RunningTask? = null
    private var issued = 0
    private var authorityLost: LeaseStatus? = null

    /** 지금 든 태스크의 상태. 아무것도 안 들었으면 `UNSPECIFIED`. */
    val state: TaskState
        get() = task?.state ?: TaskState.TASK_STATE_UNSPECIFIED

    /**
     * 태스크를 받는다.
     *
     * **스킬마다 다른 계층으로 간다.** `move_relative`는 명령 계층,
     * `navigate_to`는 미션 계층이다. 그래서 미션 서비스가 없는 기체에서도
     * `move_relative`는 돌고 `navigate_to`만 거절된다 — G1처럼 전부 죽지 않는다.
     */
    fun accept(skillType: String, parameters: Map<String, Any>, startedAt: Instant): Acceptance {
        if (!identity.complete) {
            return Acceptance.Refused(Refusal.IDENTITY_UNSET, "기체 신원이 비어 있다")
        }

        task?.takeIf { !it.state.isTerminal }?.let {
            return Acceptance.Refused(Refusal.ALREADY_RUNNING, "이미 도는 태스크가 있다: ${it.id}")
        }

        return when (skillType) {
            MOVE -> acceptMove(parameters, startedAt)
            NAVIGATE -> acceptNavigate(parameters, startedAt)
            else -> Acceptance.Refused(
                Refusal.UNSUPPORTED_SKILL,
                "이 어댑터가 드는 스킬은 '$MOVE'·'$NAVIGATE' 둘이다: '$skillType'",
            )
        }
    }

    private fun acceptMove(parameters: Map<String, Any>, startedAt: Instant): Acceptance {
        val missing = MOVE_PARAMS.filterNot { it in parameters }
        if (missing.isNotEmpty()) {
            return Acceptance.Refused(Refusal.PARAMETER_MISSING, "필수 파라미터가 없다: $missing")
        }

        val command = link.command
            ?: return Acceptance.Refused(
                Refusal.VENDOR_SURFACE_ABSENT,
                "명령 계층이 없다 — 받아 놓고 아무것도 안 하는 것이 가장 나쁘므로 거절한다",
            )

        val duration = number(parameters, P_DURATION)
            ?: return Acceptance.Refused(Refusal.PARAMETER_MISSING, "$P_DURATION 이 수가 아니다")

        // **여기가 계약과 벤더가 어긋나는 유일한 자리다.** 계약은 상대 시간,
        // Spot은 절대 시각. 시계가 어긋나면 로봇이 일찍 서거나 안 선다.
        val endTime = startedAt.plusMillis((duration * MILLIS_PER_SECOND).toLong())

        return start(
            command.se2Velocity(
                vx = number(parameters, P_FORWARD) ?: 0.0,
                vy = number(parameters, P_LATERAL) ?: 0.0,
                omega = number(parameters, P_YAW) ?: 0.0,
                endTime = endTime,
            ),
            startedAt,
            Layer.COMMAND,
            duration,
        )
    }

    private fun acceptNavigate(parameters: Map<String, Any>, startedAt: Instant): Acceptance {
        val location = parameters[P_LOCATION] as? String
            ?: return Acceptance.Refused(Refusal.PARAMETER_MISSING, "필수 파라미터가 없다: [$P_LOCATION]")

        val mission = link.mission
            ?: return Acceptance.Refused(
                Refusal.VENDOR_SURFACE_ABSENT,
                "미션 계층이 없다. **명령 계층만으로는 이 스킬을 못 든다** — " +
                    "`move_relative` 는 여전히 받는다",
            )

        // **`location` 이 그대로 웨이포인트 id 로 간다.** 옮기는 표가 없는 것이
        // 요점이며, 그 표를 우리가 갖게 되는 순간 시맨틱 결속의 주인이 바뀐다.
        loaded(mission.loadNavigateTo(location))?.let { return it }
        return start(mission.play(), startedAt, Layer.MISSION, durationSeconds = null)
    }

    /** 미션 적재 실패를 거절로 옮긴다. 성공이면 널. */
    private fun loaded(result: LeaseResult): Acceptance.Refused? = when (result) {
        is LeaseResult.Ok -> null
        is LeaseResult.Rejected -> {
            authorityLost = result.status
            Acceptance.Refused(Refusal.CONTROL_AUTHORITY_LOST, "미션 적재가 리스로 거절됐다: ${result.status}")
        }
        is LeaseResult.Failed ->
            Acceptance.Refused(Refusal.LINK_ERROR, "미션 적재가 실패했다: ${result.cause.message}")
    }

    private fun start(
        result: LeaseResult,
        startedAt: Instant,
        layer: Layer,
        durationSeconds: Double?,
    ): Acceptance = when (result) {
        is LeaseResult.Rejected -> {
            authorityLost = result.status
            Acceptance.Refused(Refusal.CONTROL_AUTHORITY_LOST, "리스가 거절됐다: ${result.status}")
        }

        is LeaseResult.Failed ->
            Acceptance.Refused(Refusal.LINK_ERROR, "남쪽 호출이 실패했다: ${result.cause.message}")

        is LeaseResult.Ok -> {
            issued += 1
            val id = "${identity.robotId}-$issued"
            task = RunningTask(id, startedAt, layer, durationSeconds, TaskState.TASK_STATE_RUNNING)
            Acceptance.Accepted(id)
        }
    }

    /**
     * 상태를 갱신한다.
     *
     * **두 계층이 다른 방식으로 답한다.** 미션 계층은 `GetState`가 상태를
     * 말해 주므로 그것을 옮기면 되고, 명령 계층은 `StopCommand`조차
     * *"provides no feedback"* 이라 **시계로 판정할 수밖에 없다** — G1과 같다.
     */
    fun poll(now: Instant): TaskState {
        val current = task ?: return TaskState.TASK_STATE_UNSPECIFIED
        if (current.state.isTerminal) return current.state

        when (current.layer) {
            Layer.COMMAND -> {
                val elapsed = (now.toEpochMilli() - current.startedAt.toEpochMilli()) / MILLIS_PER_SECOND
                if (elapsed >= (current.durationSeconds ?: 0.0)) {
                    current.state = TaskState.TASK_STATE_SUCCEEDED
                }
            }

            Layer.MISSION -> {
                val reported = link.mission?.state()
                current.question = reported?.question
                current.state = when {
                    // **질문이 상태보다 세다.** 미션은 물어보는 동안에도
                    // RUNNING 이라 답하는데, 사람이 와야 진행되는 것은
                    // 계약에서 RUNNING 이 아니다(§4.4의 NEEDS_INTERVENTION).
                    reported?.question != null -> TaskState.TASK_STATE_NEEDS_INTERVENTION
                    reported == null -> current.state
                    else -> when (reported.status) {
                        MissionStatus.SUCCESS -> TaskState.TASK_STATE_SUCCEEDED
                        MissionStatus.FAILURE, MissionStatus.ERROR -> TaskState.TASK_STATE_FAILED
                        MissionStatus.STOPPED -> TaskState.TASK_STATE_CANCELLED
                        MissionStatus.PAUSED -> TaskState.TASK_STATE_PAUSED
                        MissionStatus.RUNNING -> TaskState.TASK_STATE_RUNNING
                        MissionStatus.NONE -> current.state
                    }
                }
            }
        }
        return current.state
    }

    /**
     * 일시정지.
     *
     * **스킬마다 답이 다르다.** 미션 계층 태스크는 `PauseMission`이 있어 되고,
     * 명령 계층 태스크는 수단이 없어 안 된다. 프로파일이 `pause_support`를
     * 스킬 단위로 든 것(§7.2)이 **한 로봇 안에서** 값을 하는 첫 사례이며,
     * 여기가 그 선언을 집행한다.
     */
    fun pause(): Applied {
        val current = task ?: return Applied.Refused(Refusal.NO_TASK, "조작할 태스크가 없다")
        if (current.state.isTerminal) {
            return Applied.Refused(Refusal.TERMINAL_LATCHED, "${current.id} 은 이미 ${current.state} 다")
        }
        if (current.layer == Layer.COMMAND) {
            return Applied.Refused(
                Refusal.NO_VENDOR_PRIMITIVE,
                "명령 계층에는 일시정지가 없다. StopCommand 는 멈추기이지 재개가 아니다",
            )
        }

        val mission = link.mission
            ?: return Applied.Refused(Refusal.VENDOR_SURFACE_ABSENT, "미션 계층이 없다")

        return applied(mission.pause()) { current.state = TaskState.TASK_STATE_PAUSED }
    }

    /**
     * 취소.
     *
     * 두 계층 모두 수단이 있다 — 미션은 `StopMission`, 명령은 `StopCommand`.
     * **다만 명령 쪽은 피드백이 없어 멈췄는지 알 수 없다.** 그래서 성공을
     * 적되 그것이 관측이 아니라 가정이라는 것을 여기 남긴다.
     */
    fun cancel(): Applied {
        val current = task ?: return Applied.Refused(Refusal.NO_TASK, "조작할 태스크가 없다")
        if (current.state.isTerminal) {
            return Applied.Refused(
                Refusal.TERMINAL_LATCHED,
                "${current.id} 은 이미 ${current.state} 다 — 종착은 되돌아가지 않는다(§4.4)",
            )
        }

        current.state = TaskState.TASK_STATE_CANCELLING
        val result = when (current.layer) {
            Layer.MISSION -> link.mission?.stop()
            Layer.COMMAND -> link.command?.stop()
        } ?: return Applied.Refused(Refusal.VENDOR_SURFACE_ABSENT, "멈출 표면이 사라졌다")

        return applied(result, onReject = {
            // 멈추라고 시켰는데 권한이 없다. 로봇이 아직 움직이고 있을 수 있다.
            current.state = TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED
        }) { current.state = TaskState.TASK_STATE_CANCELLED }
    }

    private fun applied(
        result: LeaseResult,
        onReject: () -> Unit = {},
        onOk: () -> Unit,
    ): Applied = when (result) {
        is LeaseResult.Ok -> {
            onOk()
            Applied.Ok
        }

        is LeaseResult.Rejected -> {
            authorityLost = result.status
            onReject()
            Applied.Refused(Refusal.CONTROL_AUTHORITY_LOST, "리스가 거절됐다: ${result.status}")
        }

        is LeaseResult.Failed -> {
            onReject()
            Applied.Refused(Refusal.LINK_ERROR, "남쪽 호출이 실패했다: ${result.cause.message}")
        }
    }

    /**
     * 지금 관측되는 결함.
     *
     * **`CONTROL_AUTHORITY_LOST`를 기계적으로 채우는 첫 어댑터다** —
     * `LeaseUseResult.Status`가 값으로 오므로 추측이 필요 없다(§4.9).
     * G1에서는 이 결함을 낼 근거 자체가 없었다.
     */
    fun faults(): FaultObservation {
        val faults = mutableListOf<Fault>()

        authorityLost?.let {
            faults += Fault.newBuilder()
                .setErrorType("CONTROL_AUTHORITY_LOST")
                .setCanContinueCurrentTask(false)
                .setCanAcceptNewTask(false)
                .setErrorHint("리스를 잃었습니다($it). 다른 클라이언트가 기체를 쥐었는지 확인하십시오.")
                .build()
        }

        task?.question?.let {
            faults += Fault.newBuilder()
                .setErrorType("X_BOSTONDYNAMICS_MISSION_QUESTION")
                .setCanContinueCurrentTask(false)
                .setCanAcceptNewTask(false)
                .setErrorHint("미션이 사람에게 묻고 있습니다: $it")
                .build()
        }

        return FaultObservation.Observed(faults)
    }

    /** 태스크가 올라탄 벤더 계층. **어느 층이냐가 조작의 답을 바꾼다.** */
    private enum class Layer { COMMAND, MISSION }

    private class RunningTask(
        val id: String,
        val startedAt: Instant,
        val layer: Layer,
        val durationSeconds: Double?,
        var state: TaskState,
        var question: String? = null,
    )

    private companion object {
        const val MOVE = "move_relative"
        const val NAVIGATE = "navigate_to"

        const val P_FORWARD = "forward_speed"
        const val P_LATERAL = "lateral_speed"
        const val P_YAW = "yaw_rate"
        const val P_DURATION = "duration"
        const val P_LOCATION = "location"

        val MOVE_PARAMS = listOf(P_FORWARD, P_LATERAL, P_YAW, P_DURATION)

        const val MILLIS_PER_SECOND = 1000.0

        fun number(parameters: Map<String, Any>, key: String): Double? =
            (parameters[key] as? Number)?.toDouble()
    }
}
