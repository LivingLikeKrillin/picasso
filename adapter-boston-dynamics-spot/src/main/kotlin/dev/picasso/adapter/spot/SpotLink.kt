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
     * 이 기체에 **팔이 붙어 있는가.**
     *
     * ## 벤더가 존재를 말해 준다
     *
     * `RobotState.manipulator_state` 의 주석이 *"only populated if an arm is
     * attached to the robot"* 다. **필드가 채워졌는지가 곧 신호**이며, 그것을
     * 읽는 것은 남쪽 구현의 일이다.
     *
     * `HardwareConfiguration.skeleton.links[].name` 으로도 셀 수 있지만 그쪽은
     * **어느 링크가 팔인지 우리가 정해야 한다** — 벤더가 `has_arm` 을 안 판다
     * (`has_audio_visual_system` 은 파는데도). 그래서 앞의 것을 쓴다.
     *
     * ## 왜 필요한가 — 그리고 왜 처음 든 이유는 틀렸었다
     *
     * 처음에는 *"팔 없는 기체에 팔 명령을 보내면 조용히 아무 일도 안 일어난다"*
     * 로 적었다. **그 문장은 `BodyAssistForManipulation` 하나에만 붙어 있고**
     * (proto 152 개 전체에서 팔 부재를 말하는 유일한 자리다), 명령 일반에는
     * `RobotCommandResponse.Status.STATUS_UNSUPPORTED` *"The robot does not
     * understand this command"* 가 있다. 한 문장을 표면 전체로 넓힌 것이었다.
     *
     * 진짜 이유는 **결속이 어긋난 것을 보이게 하는 것**이다. 어댑터가
     * `spot-arm` 프로파일에 묶여 있는데 기체에 팔이 없으면 그 아래의 선언이
     * 전부 거짓 전제 위에 선다. 막지는 않는다 — `move_relative` 와
     * `navigate_to` 는 팔 없이도 돈다(§9.7 ④·§15.47과 같은 판단).
     */
    @VendorSurface(
        "bosdyn.api.RobotStateService.GetRobotState",
        "bosdyn.api.RobotState.manipulator_state",
    )
    fun armAttached(): Result<Boolean>

    /**
     * 그리퍼가 **무언가를 쥐고 있는가.**
     *
     * 벤더가 판정을 준다 — `ManipulatorState.is_gripper_holding_item`. 세 기종
     * 중 유일하게 파지를 불리언으로 말하는 표면이다(G1은 원시 압력값, Digit은
     * 발행하지 않는다). `manipulator_state` 자체가 비어 있으면(팔 없음) `null`.
     *
     * §4.4의 잔여 물리 상태(`HoldState`)의 근거이며, 멈춘 뒤의 종착을
     * `CANCELLED`로 적을지 `CANCELLED_RECOVERY_FAILED`로 적을지가 여기 걸린다.
     */
    @VendorSurface(
        "bosdyn.api.RobotStateService.GetRobotState",
        "bosdyn.api.RobotState.manipulator_state",
        "bosdyn.api.ManipulatorState.is_gripper_holding_item",
    )
    fun gripperHoldingItem(): Result<Boolean?>

    /**
     * 지도 계층(`GraphNavService`). **사이트 이름이 여기 산다.**
     *
     * 미션 계층과 따로 두는 것은 하는 일이 다르기 때문이다 — 미션은 *시키는*
     * 표면이고 이것은 *묻는* 표면이다. 그리고 이 층이 없으면 `navigate_to`도
     * 못 든다: 갈 곳의 이름을 id 로 옮길 데가 없다.
     */
    @get:VendorSurface("bosdyn.api.graph_nav.GraphNavService")
    val graph: GraphLayer?

    /**
     * 기체가 지금 안고 있는 **행동 결함**의 원인들.
     *
     * `RobotState.behavior_fault_state.faults[].cause` — 벤더가 넘어짐(`CAUSE_FALL`)·
     * 하드웨어(`CAUSE_HARDWARE`)·리스 만료(`CAUSE_LEASE_TIMEOUT`)를 **원인으로** 가른다.
     * 정준 분류의 `ROBOT_FELL`·`HARDWARE_FAULT` 가 이 기종에서 나오는 자리는 여기뿐이다 —
     * 미션 상태도 항법 피드백도 넘어졌다고는 말하지 않는다.
     */
    @VendorSurface(
        "bosdyn.api.RobotStateService.GetRobotState",
        "bosdyn.api.RobotState.behavior_fault_state",
        "bosdyn.api.BehaviorFaultState.faults",
        "bosdyn.api.BehaviorFault.cause",
    )
    fun behaviorFaults(): Result<List<BehaviorFaultCause>>

    /**
     * 세계 모델(`WorldObjectService`). **대상의 이름이 여기 산다** — 사이트가 `MutateWorldObjects` 로 등록한 것이며
     * (ADR 35), Digit 의 객체 모델과 구조가 같다(§15.76). `inspect` 의 `target` 을 여기서 찾는다. 널이면 대상을 찾을
     * 데가 없어 `inspect` 만 죽는다 — `navigate_to` 는 지도 계층, `move_relative` 는 명령 계층이라 산다.
     */
    @get:VendorSurface("bosdyn.api.WorldObjectService")
    val world: WorldLayer?

    /**
     * 취득 계층(`DataAcquisitionService`). `inspect` 가 여기로 간다 — 보고서 7장이 적은 ①경로의 C 수준 자리
     * (`request_id` · `STATUS_COMPLETE` · `data_saved[]`). 생명주기가 온전하다: `AcquireData`(수락) · `GetStatus`(폴) ·
     * `CancelAcquisition`(취소) · `GetServiceInfo`(능력). 일시정지는 없다.
     */
    @get:VendorSurface("bosdyn.api.DataAcquisitionService")
    val acquisition: AcquisitionLayer?
}

/** 세계 모델 — 묻기만 한다. 대상의 이름과 id 의 대응을 정한 것은 어댑터가 아니라 등록한 사이트다. */
interface WorldLayer {

    /** `ListWorldObjects` → `WorldObject{id, name}`. 이름은 선택 필드라 비어 있을 수 있고 그런 객체는 사이트가 지은 것이 아니다. */
    @VendorSurface(
        "bosdyn.api.WorldObjectService.ListWorldObjects",
        "bosdyn.api.ListWorldObjectResponse.world_objects",
        "bosdyn.api.WorldObject.id",
        "bosdyn.api.WorldObject.name",
    )
    fun listObjects(): Result<List<WorldObjectRef>>
}

/** `WorldObject` 에서 우리가 쓰는 둘. 항법의 `GraphWaypoint` 와 같은 이유로 id 와 이름을 한 필드로 접지 않는다. */
data class WorldObjectRef(
    @field:VendorSurface("bosdyn.api.WorldObject.id")
    val id: Int,

    @field:VendorSurface("bosdyn.api.WorldObject.name")
    val name: String,
)

/**
 * 취득 계층.
 *
 * **대상을 겨냥하는 자리가 없다** — `AcquisitionRequestList` 는 *어느 센서로 찍을지*를 받지 *무엇을 볼지*를 안 받는다
 * (`distance/spot-arm.json`). 그래서 어댑터는 로봇이 광고하는 영상 원천 **전부**로 찍고, 대상의 이름은
 * `CaptureActionId.action_name` 에 — 사이트가 이름을 정해 넣을 수 있는 유일한 자리(§15.75) — 묶는다. 그 자리에 섰을 때
 * 카메라가 대상을 본다는 것은 **환경 전제**다(`environment-preconditions.md` B).
 */
interface AcquisitionLayer {

    /** `GetServiceInfo` → 영상 원천들. 취득 요청은 이 이름들로 짠다. */
    @VendorSurface(
        "bosdyn.api.DataAcquisitionService.GetServiceInfo",
        "bosdyn.api.GetServiceInfoResponse.capabilities",
        "bosdyn.api.AcquisitionCapabilityList.image_sources",
        "bosdyn.api.ImageAcquisitionCapability.service_name",
        "bosdyn.api.ImageAcquisitionCapability.image_source_names",
    )
    fun imageSources(): Result<List<ImageSourceRef>>

    /**
     * `AcquireData{action_id{action_name, group_name}, acquisition_requests{image_captures}}` → `request_id`.
     * 리스를 안 싣는다 — 취득은 움직임이 아니다(§15.76: `Lease` 는 34개 요청에만).
     */
    @VendorSurface(
        "bosdyn.api.DataAcquisitionService.AcquireData",
        "bosdyn.api.AcquireDataRequest.action_id",
        "bosdyn.api.CaptureActionId.action_name",
        "bosdyn.api.CaptureActionId.group_name",
        "bosdyn.api.AcquireDataRequest.acquisition_requests",
        "bosdyn.api.AcquisitionRequestList.image_captures",
        "bosdyn.api.AcquireDataResponse.status",
        "bosdyn.api.AcquireDataResponse.request_id",
    )
    fun acquire(actionName: String, groupName: String, captures: List<ImageSourceRef>): AcquireResult

    /** `GetStatus(request_id)` — 진행·완료·오류와 **저장된 것의 식별자**. 그 식별자가 계약의 결과 참조가 된다. */
    @VendorSurface(
        "bosdyn.api.DataAcquisitionService.GetStatus",
        "bosdyn.api.GetStatusRequest.request_id",
        "bosdyn.api.GetStatusResponse.status",
        "bosdyn.api.GetStatusResponse.data_saved",
        "bosdyn.api.GetStatusResponse.data_errors",
        "bosdyn.api.GetStatusResponse.service_errors",
    )
    fun status(requestId: Int): Result<AcquisitionStatus>

    /** `CancelAcquisition(request_id)`. **벤더가 준 취소다** — 명령 계층의 `StopCommand` 와 달리 답이 온다. */
    @VendorSurface(
        "bosdyn.api.DataAcquisitionService.CancelAcquisition",
        "bosdyn.api.CancelAcquisitionRequest.request_id",
        "bosdyn.api.CancelAcquisitionResponse.status",
    )
    fun cancel(requestId: Int): Result<CancelAcquisitionStatus>
}

/** `ImageSourceCapture` 의 둘 — 어느 영상 서비스의 어느 원천. */
data class ImageSourceRef(
    @field:VendorSurface("bosdyn.api.ImageSourceCapture.image_service")
    val service: String,

    @field:VendorSurface("bosdyn.api.ImageSourceCapture.image_source")
    val source: String,
)

/** `AcquireData` 의 답 — 우리가 만든 결과 어휘라 매니페스트 대조 목록에 없다(`LeaseResult` 와 같다). */
sealed interface AcquireResult {
    data class Accepted(val requestId: Int) : AcquireResult

    /** `AcquireDataResponse.Status` 가 `STATUS_OK` 가 아니었다. */
    data class Rejected(val status: AcquireStatus) : AcquireResult

    data class Failed(val cause: Throwable) : AcquireResult
}

/** `bosdyn.api.AcquireDataResponse.Status` 셋 전부. */
enum class AcquireStatus {
    @VendorSurface("bosdyn.api.AcquireDataResponse.Status.STATUS_UNKNOWN")
    STATUS_UNKNOWN,

    @VendorSurface("bosdyn.api.AcquireDataResponse.Status.STATUS_OK")
    STATUS_OK,

    @VendorSurface("bosdyn.api.AcquireDataResponse.Status.STATUS_UNKNOWN_CAPTURE_TYPE")
    STATUS_UNKNOWN_CAPTURE_TYPE,
}

/** `GetStatusResponse` 에서 우리가 쓰는 셋. */
data class AcquisitionStatus(
    @field:VendorSurface("bosdyn.api.GetStatusResponse.status")
    val state: AcquisitionState,

    /** 저장된 것들 — 계약의 결과 참조(`partial_result`)가 되는 자리다. */
    @field:VendorSurface("bosdyn.api.GetStatusResponse.data_saved")
    val saved: List<DataRef>,

    /** 오류 셋을 문장으로 편 것 — 벤더 원문은 `vendor_detail` 로 동반한다. */
    @field:VendorSurface("bosdyn.api.GetStatusResponse.data_errors", "bosdyn.api.GetStatusResponse.service_errors")
    val errors: List<String>,
)

/** `bosdyn.api.GetStatusResponse.Status` 열하나 전부. 이름을 그대로 둔다. */
enum class AcquisitionState {
    @VendorSurface("bosdyn.api.GetStatusResponse.Status.STATUS_UNKNOWN")
    STATUS_UNKNOWN,

    @VendorSurface("bosdyn.api.GetStatusResponse.Status.STATUS_ACQUIRING")
    STATUS_ACQUIRING,

    @VendorSurface("bosdyn.api.GetStatusResponse.Status.STATUS_SAVING")
    STATUS_SAVING,

    @VendorSurface("bosdyn.api.GetStatusResponse.Status.STATUS_COMPLETE")
    STATUS_COMPLETE,

    @VendorSurface("bosdyn.api.GetStatusResponse.Status.STATUS_CANCEL_IN_PROGRESS")
    STATUS_CANCEL_IN_PROGRESS,

    @VendorSurface("bosdyn.api.GetStatusResponse.Status.STATUS_ACQUISITION_CANCELLED")
    STATUS_ACQUISITION_CANCELLED,

    @VendorSurface("bosdyn.api.GetStatusResponse.Status.STATUS_DATA_ERROR")
    STATUS_DATA_ERROR,

    @VendorSurface("bosdyn.api.GetStatusResponse.Status.STATUS_TIMEDOUT")
    STATUS_TIMEDOUT,

    @VendorSurface("bosdyn.api.GetStatusResponse.Status.STATUS_INTERNAL_ERROR")
    STATUS_INTERNAL_ERROR,

    @VendorSurface("bosdyn.api.GetStatusResponse.Status.STATUS_CANCEL_ACQUISITION_FAILED")
    STATUS_CANCEL_ACQUISITION_FAILED,

    @VendorSurface("bosdyn.api.GetStatusResponse.Status.STATUS_REQUEST_ID_DOES_NOT_EXIST")
    STATUS_REQUEST_ID_DOES_NOT_EXIST,
}

/** `bosdyn.api.CancelAcquisitionResponse.Status` 넷 전부. */
enum class CancelAcquisitionStatus {
    @VendorSurface("bosdyn.api.CancelAcquisitionResponse.Status.STATUS_UNKNOWN")
    STATUS_UNKNOWN,

    @VendorSurface("bosdyn.api.CancelAcquisitionResponse.Status.STATUS_OK")
    STATUS_OK,

    @VendorSurface("bosdyn.api.CancelAcquisitionResponse.Status.STATUS_FAILED_TO_CANCEL")
    STATUS_FAILED_TO_CANCEL,

    @VendorSurface("bosdyn.api.CancelAcquisitionResponse.Status.STATUS_REQUEST_ID_DOES_NOT_EXIST")
    STATUS_REQUEST_ID_DOES_NOT_EXIST,
}

/**
 * `DataIdentifier` — 저장된 취득물 하나의 이름. `action_id` 가 [CaptureActionId] 라 대상의 이름(`action_name`)과 태스크
 * (`group_name`)가 붙어 온다. **이것이 시나리오 ③의 "증거 자료 참조"다** — 저장소에서 이 id 로 조회된다.
 */
data class DataRef(
    @field:VendorSurface("bosdyn.api.DataIdentifier.action_id", "bosdyn.api.CaptureActionId.action_name")
    val actionName: String,

    @field:VendorSurface("bosdyn.api.DataIdentifier.action_id", "bosdyn.api.CaptureActionId.group_name")
    val groupName: String,

    @field:VendorSurface("bosdyn.api.DataIdentifier.channel")
    val channel: String,

    @field:VendorSurface("bosdyn.api.DataIdentifier.data_name")
    val dataName: String,

    @field:VendorSurface("bosdyn.api.DataIdentifier.id")
    val id: String,
)

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

    /**
     * `NavigationFeedback` → `NavigationFeedbackResponse.status`. **항법이 왜 멈췄는지를
     * 벤더가 열거로 말하는 자리다.**
     *
     * 미션 `State.status` 는 `FAILURE`/`ERROR` 까지만 말하고 이유를 안 싣는다 — `BosdynNavigateTo`
     * 노드가 부른 항법의 결과는 항법 서비스 쪽에 남는다. 그래서 미션이 실패라 하면 어댑터가
     * 여기에 한 번 더 묻고, 그 답으로 정준 분류(`ROUTE_BLOCKED`·`NO_ROUTE`·`LOCALIZATION_LOST`…)를
     * 정한다. 못 물으면 분류하지 않는다(`UNCLASSIFIED`).
     *
     * `command_id` 를 비워 보낸다 — 비우면 가장 최근 항법 명령의 것이라는 것이 벤더 proto
     * 주석의 진술이며, **실물에서 확인한 바 없다**(§9.7 ④·C-3). 아직 항법 명령이 없었으면 널.
     */
    @VendorSurface(
        "bosdyn.api.graph_nav.GraphNavService.NavigationFeedback",
        "bosdyn.api.graph_nav.NavigationFeedbackRequest.command_id",
        "bosdyn.api.graph_nav.NavigationFeedbackResponse.status",
    )
    fun navigationFeedback(): Result<NavigationStatus?>
}

/**
 * `bosdyn.api.graph_nav.NavigationFeedbackResponse.Status` 열넷 전부. 이름을 그대로 둔다.
 *
 * 정준 분류로 옮기는 표는 [SpotAdapter] 에 있다 — 여기는 벤더가 말하는 것만 있다.
 */
enum class NavigationStatus {
    @VendorSurface("bosdyn.api.graph_nav.NavigationFeedbackResponse.Status.STATUS_UNKNOWN")
    STATUS_UNKNOWN,

    @VendorSurface("bosdyn.api.graph_nav.NavigationFeedbackResponse.Status.STATUS_FOLLOWING_ROUTE")
    STATUS_FOLLOWING_ROUTE,

    @VendorSurface("bosdyn.api.graph_nav.NavigationFeedbackResponse.Status.STATUS_REACHED_GOAL")
    STATUS_REACHED_GOAL,

    @VendorSurface("bosdyn.api.graph_nav.NavigationFeedbackResponse.Status.STATUS_NO_ROUTE")
    STATUS_NO_ROUTE,

    @VendorSurface("bosdyn.api.graph_nav.NavigationFeedbackResponse.Status.STATUS_NO_LOCALIZATION")
    STATUS_NO_LOCALIZATION,

    @VendorSurface("bosdyn.api.graph_nav.NavigationFeedbackResponse.Status.STATUS_LOST")
    STATUS_LOST,

    @VendorSurface("bosdyn.api.graph_nav.NavigationFeedbackResponse.Status.STATUS_STUCK")
    STATUS_STUCK,

    @VendorSurface("bosdyn.api.graph_nav.NavigationFeedbackResponse.Status.STATUS_COMMAND_TIMED_OUT")
    STATUS_COMMAND_TIMED_OUT,

    @VendorSurface("bosdyn.api.graph_nav.NavigationFeedbackResponse.Status.STATUS_ROBOT_IMPAIRED")
    STATUS_ROBOT_IMPAIRED,

    @VendorSurface("bosdyn.api.graph_nav.NavigationFeedbackResponse.Status.STATUS_CONSTRAINT_FAULT")
    STATUS_CONSTRAINT_FAULT,

    @VendorSurface("bosdyn.api.graph_nav.NavigationFeedbackResponse.Status.STATUS_COMMAND_OVERRIDDEN")
    STATUS_COMMAND_OVERRIDDEN,

    @VendorSurface("bosdyn.api.graph_nav.NavigationFeedbackResponse.Status.STATUS_NOT_LOCALIZED_TO_ROUTE")
    STATUS_NOT_LOCALIZED_TO_ROUTE,

    @VendorSurface("bosdyn.api.graph_nav.NavigationFeedbackResponse.Status.STATUS_LEASE_ERROR")
    STATUS_LEASE_ERROR,

    @VendorSurface("bosdyn.api.graph_nav.NavigationFeedbackResponse.Status.STATUS_AREA_CALLBACK_ERROR")
    STATUS_AREA_CALLBACK_ERROR,
}

/** `bosdyn.api.BehaviorFault.Cause` 넷 전부. 이름을 그대로 둔다. */
enum class BehaviorFaultCause {
    @VendorSurface("bosdyn.api.BehaviorFault.Cause.CAUSE_UNKNOWN")
    CAUSE_UNKNOWN,

    @VendorSurface("bosdyn.api.BehaviorFault.Cause.CAUSE_FALL")
    CAUSE_FALL,

    @VendorSurface("bosdyn.api.BehaviorFault.Cause.CAUSE_HARDWARE")
    CAUSE_HARDWARE,

    @VendorSurface("bosdyn.api.BehaviorFault.Cause.CAUSE_LEASE_TIMEOUT")
    CAUSE_LEASE_TIMEOUT,
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
