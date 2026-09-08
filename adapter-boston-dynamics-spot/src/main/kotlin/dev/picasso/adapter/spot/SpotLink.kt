package dev.picasso.adapter.spot

import dev.picasso.adapter.core.VendorSurface
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
 * **층이 둘이 아니라 셋이다.** 위 표에 없는 [GraphLayer]가 시키지 않고 묻기만
 * 하며, 사이트 이름이 거기 산다. 그 층을 세는 데 한 번 실패해서
 * `navigate_to`의 대상 해석이 틀린 채로 있었다 — 전말은 [GraphLayer]에 있다.
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
    @get:VendorSurface("bosdyn.api.RobotCommandService")
    val command: CommandLayer?

    /** 미션 계층(`MissionService`). `navigate_to`가 여기로 간다. */
    @get:VendorSurface("bosdyn.api.mission.MissionService")
    val mission: MissionLayer?

    /**
     * 지도 계층(`GraphNavService`). **사이트 이름이 여기 산다.**
     *
     * 미션 계층과 따로 두는 것은 하는 일이 다르기 때문이다 — 미션은 *시키는*
     * 표면이고 이것은 *묻는* 표면이다. 그리고 이 층이 없으면 `navigate_to`도
     * 못 든다: 갈 곳의 이름을 id 로 옮길 데가 없다.
     */
    @get:VendorSurface("bosdyn.api.graph_nav.GraphNavService")
    val graph: GraphLayer?
}

/**
 * 지도 계층 — **이 어댑터가 한 번 크게 틀렸던 자리다.**
 *
 * ## 무엇이 틀렸었나
 *
 * 처음 판은 계약의 `location` 을 `NavigateToRequest.destination_waypoint_id`
 * 로 **그대로** 넘기고, 주석에 *"옮기는 표가 없는 것이 요점"* 이라고 적었다.
 * 벤더 원문을 읽으니 그 둘은 같은 것이 아니다.
 *
 * | | 벤더 필드 | 누가 정하나 |
 * |---|---|---|
 * | `Waypoint.id` | *"Unique across all maps"* | **로봇이 생성한다** |
 * | `Waypoint.annotations.name` | *"Human-friendly name … For example, `Kitchen Fridge`"* | **사람이 붙인다** |
 *
 * 항법이 받는 것은 앞의 것이고 사이트가 저작하는 것은 뒤의 것이다. 그대로
 * 넘기면 **상위 시스템이 Spot 이 생성한 id 를 알고 있어야 하고**, 그것은
 * 소비자가 기종별 식별자를 들지 않는다는 A-1 을 깨는 것이다.
 *
 * ## 그런데 이 해석이 ADR 34 를 어기지 않나
 *
 * 아니다. **표를 갖는 것과 로봇의 표에 묻는 것은 다르다.** 이 계층은 매번
 * 로봇에게 그래프를 받아 그 안의 이름을 읽는다 — 어댑터는 아무것도 저장하지
 * 않고, 이름과 id 의 대응을 정한 것도 어댑터가 아니라 지도를 녹화한 사이트다.
 * 결속의 주인은 여전히 로봇이며, 그것이 ADR 35 가 말한 그 모양이다.
 *
 * **Digit 을 고쳤을 때와 같은 실수였다** — 제3자 요약이 아니라 벤더 원문을
 * 먼저 읽었어야 했다(§15.65).
 */
interface GraphLayer {

    /**
     * `DownloadGraph` → `Graph{waypoints}`.
     *
     * 그래프 전체를 받는 것이 무거워 보이지만 **더 좁은 질의가 없다** —
     * 이름으로 웨이포인트를 찾는 RPC 는 SDK 에 없고, 이름은 그래프 안에만
     * 있다. 좁히려면 어댑터가 캐시를 들어야 하고, 그러면 사이트가 지도를
     * 다시 올렸을 때 **어댑터가 옛 이름으로 로봇을 몬다.**
     */
    @VendorSurface(
        "bosdyn.api.graph_nav.GraphNavService.DownloadGraph",
        "bosdyn.api.graph_nav.Graph.waypoints",
    )
    fun downloadGraph(): Result<List<GraphWaypoint>>
}

/**
 * `Waypoint` 에서 **우리가 쓰는 둘만** 옮긴 것.
 *
 * 둘을 한 필드로 접지 않는 것이 이 데이터 클래스의 전부다. 접으면 위의
 * 오독이 그대로 돌아온다.
 */
data class GraphWaypoint(

    /** `Waypoint.id`. 로봇이 생성했고 `destination_waypoint_id` 가 받는다. */
    @field:VendorSurface("bosdyn.api.graph_nav.Waypoint.id")
    val id: String,

    /**
     * `Waypoint.annotations.name`. 사람이 붙였고 계약의 `location` 이 이것이다.
     *
     * **비어 있을 수 있다.** 지도를 녹화하면서 이름을 안 붙인 웨이포인트가
     * 그렇고, 그것은 사이트 이름이 아니므로 답에서 뺀다 — 빈 문자열을
     * 이름으로 세면 개수가 부풀고 확인이 통과한다.
     */
    @field:VendorSurface(
        "bosdyn.api.graph_nav.Waypoint.annotations",
        "bosdyn.api.graph_nav.Waypoint.Annotations.name",
    )
    val annotationName: String,
)

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
    @VendorSurface(
        "bosdyn.api.SE2VelocityCommand.Request",
        "bosdyn.api.SE2VelocityCommand.Request.velocity",
        "bosdyn.api.SE2VelocityCommand.Request.end_time",
        "bosdyn.api.SE2VelocityCommand.Request.se2_frame_name",
        "bosdyn.api.SE2VelocityCommand.Request.slew_rate_limit",
    )
    fun se2Velocity(vx: Double, vy: Double, omega: Double, endTime: Instant): LeaseResult

    /** `StopCommand`. 인자도 피드백도 없다 — 그래서 멈췄는지는 알 수 없다. */
    @VendorSurface("bosdyn.api.StopCommand.Request")
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
     * **이것은 계약의 `location`이 아니다** — 로봇이 생성한 id 이고, 사람이
     * 붙인 이름은 [GraphWaypoint.annotationName]에 따로 있다. 어댑터가
     * [GraphLayer]에 물어 옮긴 뒤에 여기로 온다.
     *
     * 처음 판은 `location`을 그대로 여기 넣었다. 그 오독의 전말은
     * [GraphLayer]에 적혀 있다.
     */
    @VendorSurface(
        "bosdyn.api.mission.MissionService.LoadMission",
        "bosdyn.api.mission.BosdynNavigateTo",
        "bosdyn.api.graph_nav.NavigateToRequest.destination_waypoint_id",
    )
    fun loadNavigateTo(waypointId: String): LeaseResult

    /** `PlayMission`. */
    @VendorSurface("bosdyn.api.mission.MissionService.PlayMission")
    fun play(): LeaseResult

    /** `PauseMission`. **명령 계층에는 대응이 없다.** */
    @VendorSurface("bosdyn.api.mission.MissionService.PauseMission")
    fun pause(): LeaseResult

    /** `StopMission`. 계약의 취소가 여기로 간다. */
    @VendorSurface("bosdyn.api.mission.MissionService.StopMission")
    fun stop(): LeaseResult

    /** `GetState`. 아직 아무것도 안 올렸으면 널이다. */
    @VendorSurface(
        "bosdyn.api.mission.MissionService.GetState",
        "bosdyn.api.mission.GetStateResponse.state",
    )
    fun state(): MissionState?
}

/**
 * `GetStateResponse`에서 우리가 쓰는 것만 옮긴 것.
 *
 * [question]이 `AnswerQuestion`의 자리다 — **계약의 `NEEDS_INTERVENTION`에
 * 해당하는 것을 가진 유일한 실물이다.**
 */
data class MissionState(
    @field:VendorSurface("bosdyn.api.mission.State.status")
    val status: MissionStatus,

    /**
     * 미션이 사람에게 묻고 있는 것. 없으면 널.
     *
     * 벤더는 `Prompt` 노드와 `AnswerQuestion` RPC로 이것을 모델링한다. 우리
     * 계약에는 질문을 **되돌려 답하는** 경로가 없어서(§4.4의 `RetryTask`뿐)
     * 어댑터는 이것을 `NEEDS_INTERVENTION` + `error_hint`로만 옮긴다.
     * 답은 사람이 Spot 쪽에서 한다.
     */
    @field:VendorSurface(
        "bosdyn.api.mission.State.questions",
        "bosdyn.api.mission.Question.text",
        "bosdyn.api.mission.MissionService.AnswerQuestion",
    )
    val question: String? = null,
)

/**
 * `bosdyn.api.mission.State.Status`.
 *
 * **이름이 한 번 틀려 있었다.** 앞 판은 `GetStateResponse.Status` 라고 적었는데
 * 그런 것은 없다 — 응답은 `GetStateResponse.state` 를 싣고 상태는 그 안의
 * `State.status` 다. [VendorSurface] 검사를 붙이면서 드러났다.
 *
 * **그리고 값 하나가 빠져 있었다.** 벤더에 `STATUS_UNKNOWN` 이 있다. 빠뜨리면
 * 그 값이 왔을 때 남쪽 구현이 무언가를 지어내야 하고, 지어낸 것은 로봇이
 * 판정한 것처럼 보인다 — Digit 의 `ActionStatus` 가 같은 이유로 모르는 값을
 * 안 접는다.
 */
enum class MissionStatus {
    @VendorSurface("bosdyn.api.mission.State.Status.STATUS_UNKNOWN")
    UNKNOWN,

    @VendorSurface("bosdyn.api.mission.State.Status.STATUS_NONE")
    NONE,

    @VendorSurface("bosdyn.api.mission.State.Status.STATUS_RUNNING")
    RUNNING,

    @VendorSurface("bosdyn.api.mission.State.Status.STATUS_PAUSED")
    PAUSED,

    @VendorSurface("bosdyn.api.mission.State.Status.STATUS_SUCCESS")
    SUCCESS,

    @VendorSurface("bosdyn.api.mission.State.Status.STATUS_FAILURE")
    FAILURE,

    @VendorSurface("bosdyn.api.mission.State.Status.STATUS_STOPPED")
    STOPPED,

    @VendorSurface("bosdyn.api.mission.State.Status.STATUS_ERROR")
    ERROR,
}

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
    @VendorSurface("bosdyn.api.LeaseUseResult.Status.STATUS_INVALID_LEASE")
    STATUS_INVALID_LEASE,

    @VendorSurface("bosdyn.api.LeaseUseResult.Status.STATUS_OLDER")
    STATUS_OLDER,

    @VendorSurface("bosdyn.api.LeaseUseResult.Status.STATUS_REVOKED")
    STATUS_REVOKED,

    @VendorSurface("bosdyn.api.LeaseUseResult.Status.STATUS_UNMANAGED")
    STATUS_UNMANAGED,

    @VendorSurface("bosdyn.api.LeaseUseResult.Status.STATUS_WRONG_EPOCH")
    STATUS_WRONG_EPOCH,
}
