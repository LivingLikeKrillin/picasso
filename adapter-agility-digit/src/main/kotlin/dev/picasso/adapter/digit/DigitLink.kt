package dev.picasso.adapter.digit

import dev.picasso.adapter.core.VendorSurface

/**
 * 남쪽 경계 — Digit이 실제로 말하는 것.
 *
 * ## 이 파일은 한 번 크게 틀렸다
 *
 * 처음 판은 원시 JSON을 쓰는 **제3자 래퍼 코드**에서 파생했고, 그 래퍼가 벤더
 * API의 **부분집합**이라 없는 것을 여럿 만들어 냈다 — *"지속시간이 없다"*,
 * *"취소 프리미티브가 없다"*, *"목적지가 좌표뿐이다"*. 셋 다 틀렸다.
 *
 * 벤더 SDK의 메시지 정의(`agility/messages/json.py`, 릴리스 `2021.06.01`)를
 * 전수로 읽고 다시 썼다. **근거 등급을 §15.65에 적어 두고도 그 위에 "없다"를
 * 얹은 것이 실수였다** — 등급이 낮으면 `NO`가 아니라 `UNKNOWN`이어야 했다.
 *
 * ## 이 기종이 셋 중 가장 많이 준다
 *
 * | | G1 | Spot | Digit |
 * |---|---|---|---|
 * | 대상 지시 | 없음 | 웨이포인트 id(항법만) | **[ObjectSelector]** — 이름·속성·태그·계층 질의 |
 * | 시맨틱 집기 | 없음 | 픽셀·3D점 | **`action-pick{object}`** |
 * | 시맨틱 놓기 | 없음 | **요청 자리 없음** | **`action-place{reference_frame}`** |
 * | 지속시간 | 필드 | 필드(`end_time`) | **합성**(`action-duration`) |
 * | 취소 | 덮어쓰기 | `StopMission` | `remove-action` |
 * | 세계 모델 등록 | 없음 | 지도 녹화 | **`add-object`·`add-landmarks`·`set-floorplan-map`** |
 *
 * 마지막 줄이 [ADR 35](../../../../../../../docs/adr/0035-site-names-live-in-the-robot.md)의
 * 가장 강한 증거다 — *"사이트 이름은 로봇 안에 산다"*.
 *
 * ## 남는 한계
 *
 * SDK 릴리스가 2021년판이다. **"있다"는 확실하고 "없다"는 그 시점 기준이다.**
 * 그리고 여전히 실물에 붙여 보지 못했다(§9.7 ④·C-3).
 */
interface DigitLink {

    /**
     * `change-action-command` 권한을 쥐고 있는가.
     *
     * **전 시스템에서 한 클라이언트만 갖는다.** 빼앗기면 로봇이 즉시
     * `action-idle`로 리셋된다 — Spot의 리스 거절보다 결과가 세다. 거기서는
     * 요청이 거절될 뿐이지만 여기서는 **하던 일이 사라진다.**
     */
    @get:VendorSurface(
        "request-privilege",
        "privileges",
        "privileges.privileges",
        "privilege-info.has",
        "privilege.change-action-command",
    )
    val privilege: PrivilegeState

    /**
     * `["action-duration", {action: ["action-move", {velocity}], duration}]`.
     *
     * **지속시간이 필드가 아니라 합성이다.** `action-move` 자체에는 없고
     * `action-duration`이 아무 액션이나 감싸며, **로봇이 그것을 집행한다.**
     * 그래서 어댑터가 시계를 들 필요가 없고 실패 방향도 다른 둘과 같다 —
     * 어댑터가 죽어도 로봇이 선다.
     */
    @VendorSurface(
        "action-duration",
        "action-duration.action",
        "action-duration.duration",
        "action-move",
        "action-move.velocity",
    )
    fun moveFor(yawRate: Double, forward: Double, lateral: Double, durationSeconds: Double): Result<ActionRef>

    /**
     * `["action-goto", {reference_frame: {name: …}, target: <원점>, position_tolerance}]`.
     *
     * **[name]이 그대로 [ObjectSelector]의 `name`으로 간다.** 옮기는 표가 없는
     * 것이 요점이며(ADR 34), 그 이름을 로봇이 알게 만드는 것은 `add-object`·
     * `set-floorplan-map`으로 하는 **사이트 작업**이다(ADR 35).
     */
    @VendorSurface(
        "action-goto",
        "action-goto.reference-frame",
        "action-goto.target",
        "action-goto.position-tolerance",
        "object-selector.name",
    )
    fun gotoNamed(name: String): Result<ActionRef>

    /**
     * `["action-sequential", {actions: [action-pick{object}, action-place{reference_frame}]}]`.
     *
     * 계약의 `pick_place` 하나를 벤더의 액션 **둘을 합성한 하나**로 옮긴다.
     * 태스크 단위를 계약이 정하고 합성 방법을 어댑터가 정하는 것이며, 벤더가
     * `action-sequential`을 그 용도로 두었다.
     */
    @VendorSurface(
        "action-sequential",
        "action-sequential.actions",
        "action-pick",
        "action-pick.object",
        "action-place",
        "action-place.reference-frame",
    )
    fun pickAndPlace(objectName: String, destinationName: String): Result<ActionRef>

    /**
     * `["remove-action", {reference_number}]`.
     *
     * **취소가 있다.** 처음에 없다고 적었던 것이 이 파일의 가장 큰 오독이었다.
     * 다만 매뉴얼이 *컨테이너에 대해서는 성공한 것처럼 보인다*고 적었으므로
     * 중첩 액션에서 미덥지 않다 — 그래서 지운 뒤에도 상태를 다시 본다.
     */
    @VendorSurface("remove-action", "remove-action.reference-number")
    fun removeAction(ref: ActionRef): Result<Unit>

    /**
     * `["notify-objects", {objects: {has_attributes: []}}]` 가 내는 **id 목록**.
     *
     * **이름이 아니라 id 가 온다.** 매뉴얼이 그것을 명시한다 —
     * *"This does not affect the ordering of the list of IDs returned by
     * notify-objects"*. 그리고 선택자를 통째로 비우면 아무것도 선택되지 않으므로
     * `has-attributes` 를 **빈 배열**로 준다: *"If the list of attributes is
     * empty, all objects will be selected."*
     *
     * `persistent` 는 끈다. 켜면 세계가 바뀔 때마다 계속 오는데, 우리는 지금
     * 이 순간의 목록만 필요하다.
     */
    @VendorSurface(
        "notify-objects",
        "notify-objects.objects",
        "notify-objects.persistent",
        "object-selector.has-attributes",
        "object-notification",
    )
    fun objectIds(): Result<List<Int>>

    /**
     * `["get-object", {object: {object_id}}]` 가 내는 그 객체의 `name`.
     *
     * **이름이 없을 수 있다** — 매뉴얼의 객체 속성에서 `name` 이
     * `optional < string >` 이다. 그런 객체는 사이트가 이름을 붙인 것이
     * 아니므로 답에서 뺀다.
     *
     * ## 왜 두 번 물어야 하나
     *
     * 매뉴얼이 그 흐름을 직접 적어 두었다 — *"use notify-objects … to narrow
     * down the set of candidate objects, then use get-object to inspect each
     * candidate."* **이름만 한 번에 받는 질의가 없다.** Spot 은 그래프 한 번,
     * 여기는 목록 한 번 + 객체 수만큼. 이 차이가 그대로 비용이 된다.
     */
    @VendorSurface(
        "get-object",
        "get-object.object",
        "object",
        "object-selector.object-id",
        "object-attributes.name",
    )
    fun objectName(objectId: Int): Result<String?>

    /**
     * 마지막으로 받은 `["action-status-changed", {status}]`.
     *
     * **이 값이 되돌아갈 수 있다.** 매뉴얼이 *"This status does not latch once
     * reached"* 라 적었고, 그래서 §4.4의 래치 불변식이 **여기서 처음 실전이다** —
     * G1에서는 관절 각속도로 추론했는데 여기서는 벤더가 그 전이를 직접 보낸다.
     */
    @VendorSurface("action-status-changed", "action-status-changed.status")
    fun status(): ActionStatus?

    /**
     * 마지막 상태 변화에 딸려 온 **사람이 읽는 자유 문자열**.
     *
     * 결함 어휘가 없다 — SDK 와 매뉴얼을 다 읽고도 그대로였다. 어댑터가 분류를
     * **포기하는 것**이 정직한 처리이며, 지어낸 분류는 로봇이 판정한 것처럼 보인다.
     *
     * ## 이 메서드는 한 번 이름이 틀려 있었다
     *
     * 앞 판은 `["error", {info}]` 를 짚었다. **그런 메시지는 SDK 의 빌더에도
     * 매뉴얼의 메시지 표제에도 없다** — 매뉴얼은 잘못된 요청에 *"an error
     * message"* 가 온다고 산문으로만 말하고 그 모양을 어디에도 적지 않았다.
     * [VendorSurface] 검사를 붙이면서 드러났고, **없는 이름을 짚느니 있는
     * 이름을 짚는다**: 문서화된 것은 `action-status-changed.info` 이며
     * 매뉴얼이 *"A human-readable explanation of the action's status"* 라 적었다.
     *
     * 일반 오류 봉투가 **있다는 것은 확실하고 모양은 모른다.** 그래서 모델링
     * 하지 않는다 — 지어낸 모양은 다음 사람에게 확인된 것으로 읽힌다.
     */
    @VendorSurface("action-status-changed", "action-status-changed.info")
    fun statusInfo(): String?
}

/** `remove-action`이 짚는 봉투의 `refnum`. */
@JvmInline
value class ActionRef(val referenceNumber: Int)

/** `["privileges", {privileges: [{has}]}]`가 말하는 것. */
enum class PrivilegeState { HELD, LOST }

/**
 * `action-status-changed`의 `status`(매뉴얼의 `enum action-status`).
 *
 * ## 값이 둘이 아니라 넷이었다
 *
 * 앞 판은 *"확인한 값이 둘"* 이라고 적고 `RUNNING`·`SUCCESS` 만 들었다.
 * 매뉴얼의 열거는 넷이며, **빠진 것 중 하나가 하는 일이 있었다** —
 * `failure` 가 없으니 어댑터가 로봇이 신고한 실패에 **도달할 수 없었다.**
 * 권한을 잃을 때만 `TASK_STATE_FAILED` 가 났고, 액션이 실패하면 그 태스크는
 * 영원히 `RUNNING` 이었다. [VendorSurface] 검사를 붙이면서 드러났다.
 *
 * 여전히 모르는 값이 오면 널로 받고 "모른다"로 다룬다.
 */
enum class ActionStatus {
    @VendorSurface("action-status.running")
    RUNNING,

    @VendorSurface("action-status.success")
    SUCCESS,

    /** *"the action is blocked from making progress towards its goal"*. */
    @VendorSurface("action-status.failure")
    FAILURE,

    /**
     * *"the action is not an active part of the current execution state"* —
     * 다른 액션으로 교체됐다.
     *
     * **값은 들되 상태를 옮기지 않는다.** 매뉴얼이 *교체될 때 새 액션과 옛
     * 액션 양쪽에 대해 메시지가 간다*고 적었는데, [status]는 마지막 것 하나만
     * 주고 **어느 액션의 것인지를 안 나른다.** 우리 것이 교체된 것과 남의
     * 것이 끝난 것을 못 가르므로, 종착으로 옮기면 멀쩡한 태스크를 죽인다.
     * 봉투의 `refnum` 을 남쪽이 나르면 풀린다.
     */
    @VendorSurface("action-status.inactive")
    INACTIVE,
}
