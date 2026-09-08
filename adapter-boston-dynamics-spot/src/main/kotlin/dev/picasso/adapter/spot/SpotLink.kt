package dev.picasso.adapter.spot

import java.time.Instant

/**
 * 남쪽 경계 — Spot이 실제로 말하는 것.
 *
 * ## 이 분할도 우리 것이 아니라 벤더의 것이다
 *
 * Spot은 두 층으로 갈라져 있고, **우리 계약의 태스크 생명주기와 모양이 같은
 * 것은 아래층이 아니라 위층이다.**
 *
 * | 우리 계약 | Spot |
 * |---|---|
 * | 태스크 수락·실행 | `MissionService.PlayMission` |
 * | `PAUSED` | `PauseMission` |
 * | `CANCELLING`·`CANCELLED` | `StopMission` |
 * | `RetryTask` | `RestartMission` |
 * | `WatchTask` | `GetState` |
 * | `NEEDS_INTERVENTION` | `AnswerQuestion` + `Prompt` 노드 |
 *
 * 명령 계층(`basic_command.proto`)에는 이 중 어느 것도 없다 — `StopCommand`는
 * *"takes no additional arguments"* 이고 *"provides no feedback"* 이다.
 *
 * **이 층 구분이 G1의 거리도 설명한다.** G1은 미션 계층이 아예 없고 `sport`
 * 서비스가 명령만 준다. 그래서 거리는 스킬마다 다른 것이 아니라 **계층이
 * 어긋난 것**이다.
 *
 * ## 둘 다 널일 수 있고, 각자 다른 스킬을 죽인다
 *
 * G1에서는 표면 하나가 없으면 전부 죽었다. 여기서는 [command]만 있으면
 * `move_relative`는 돌고 `navigate_to`는 못 돈다. **표면이 스킬마다 다르다는
 * 것이 이 기종에서 처음 드러났다.**
 */
interface SpotLink {

    /** 명령 계층(`RobotCommandService`). `move_relative`가 여기로 간다. */
    val command: CommandLayer?

    /** 미션 계층(`MissionService`). `navigate_to`가 여기로 간다. */
    val mission: MissionLayer?
}

/**
 * 명령 계층.
 *
 * **메서드 이름과 인자를 SDK 그대로 둔다** — 우리 어휘로 옮기면 다음 조사에서
 * 한 줄씩 맞대 보지 못한다(§15.56).
 */
interface CommandLayer {

    /**
     * `SE2VelocityCommand{velocity, end_time, se2_frame_name, slew_rate_limit}`.
     *
     * **`end_time`이 절대 시각인 것이 계약과의 유일한 어긋남이다.** 계약은
     * `duration`(상대)을 싣는데 Spot은 언제 멈출지를 절대 시각으로 받는다.
     * 변환은 어댑터가 하며, 그래서 **시계 어긋남이 어댑터의 문제가 된다** —
     * 벤더도 그것을 알아서 `graph_nav`에 `clock_identifier`를 두었다.
     */
    fun se2Velocity(vx: Double, vy: Double, omega: Double, endTime: Instant): LeaseResult

    /** `StopCommand`. 인자도 피드백도 없다 — 그래서 멈췄는지는 알 수 없다. */
    fun stop(): LeaseResult
}

/**
 * 미션 계층.
 *
 * 태스크 하나를 미션 하나로 옮기는 결속은 **어댑터가 정한 것**이다. 벤더는
 * 미션을 여러 노드의 트리로 쓰라고 만들었고(`Sequence`·`Selector`·`Repeat`),
 * 우리가 노드 하나짜리 미션으로 쓰는 것은 계약의 태스크 단위에 맞추기 위해서다.
 */
interface MissionLayer {

    /**
     * `BosdynNavigateTo` 노드 하나짜리 미션을 올린다(`LoadMission`).
     *
     * [waypointId]가 `NavigateToRequest.destination_waypoint_id`로 간다.
     * **이것이 우리 `location`과 모양이 같은 유일한 벤더 값이다** — 항법이
     * 쉬워서가 아니라 GraphNav이 이미 시맨틱 결속을 해 두고 문자열 id로
     * 노출하기 때문이다. 그 결속(지도 녹화·`UploadGraph`)은 이 어댑터 밖의
     * 사이트 작업이며 계약에도 우리 코드에도 자리가 없다.
     */
    fun loadNavigateTo(waypointId: String): LeaseResult

    /** `PlayMission`. */
    fun play(): LeaseResult

    /** `PauseMission`. **명령 계층에는 대응이 없다.** */
    fun pause(): LeaseResult

    /** `StopMission`. 계약의 취소가 여기로 간다. */
    fun stop(): LeaseResult

    /** `GetState`. 아직 아무것도 안 올렸으면 널이다. */
    fun state(): MissionState?
}

/**
 * `GetStateResponse`에서 우리가 쓰는 것만 옮긴 것.
 *
 * [question]이 `AnswerQuestion`의 자리다 — **계약의 `NEEDS_INTERVENTION`에
 * 해당하는 것을 가진 유일한 실물이다.**
 */
data class MissionState(
    val status: MissionStatus,

    /**
     * 미션이 사람에게 묻고 있는 것. 없으면 널.
     *
     * 벤더는 `Prompt` 노드와 `AnswerQuestion` RPC로 이것을 모델링한다. 우리
     * 계약에는 질문을 **되돌려 답하는** 경로가 없어서(§4.4의 `RetryTask`뿐)
     * 어댑터는 이것을 `NEEDS_INTERVENTION` + `error_hint`로만 옮긴다.
     * 답은 사람이 Spot 쪽에서 한다.
     */
    val question: String? = null,
)

/** `GetStateResponse.Status`. */
enum class MissionStatus { NONE, RUNNING, PAUSED, SUCCESS, FAILURE, STOPPED, ERROR }

/**
 * 리스 판정을 실은 호출 결과.
 *
 * **Spot에서 처음으로 §4.9가 기계적 근거를 갖는다.** 모든 요청이 `lease`를
 * 싣고 응답이 `LeaseUseResult`를 돌려주므로, 권한을 빼앗긴 것이 예외가 아니라
 * **값으로** 온다. G1은 lease id가 있어도 인증이 없어 같은 것을 못 채운다.
 */
sealed interface LeaseResult {
    data object Ok : LeaseResult

    /** `LeaseUseResult.Status`가 `STATUS_OK`가 아니었다. */
    data class Rejected(val status: LeaseStatus) : LeaseResult

    /** 전송 자체가 실패했다. 리스와 무관하다. */
    data class Failed(val cause: Throwable) : LeaseResult
}

/** `lease.proto`의 `LeaseUseResult.Status`. 이름을 그대로 둔다. */
enum class LeaseStatus {
    STATUS_INVALID_LEASE,
    STATUS_OLDER,
    STATUS_REVOKED,
    STATUS_UNMANAGED,
    STATUS_WRONG_EPOCH,
}
