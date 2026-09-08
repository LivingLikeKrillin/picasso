package dev.picasso.adapter.digit

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
    val privilege: PrivilegeState

    /**
     * `["action-duration", {action: ["action-move", {velocity}], duration}]`.
     *
     * **지속시간이 필드가 아니라 합성이다.** `action-move` 자체에는 없고
     * `action-duration`이 아무 액션이나 감싸며, **로봇이 그것을 집행한다.**
     * 그래서 어댑터가 시계를 들 필요가 없고 실패 방향도 다른 둘과 같다 —
     * 어댑터가 죽어도 로봇이 선다.
     */
    fun moveFor(yawRate: Double, forward: Double, lateral: Double, durationSeconds: Double): Result<ActionRef>

    /**
     * `["action-goto", {reference_frame: {name: …}, target: <원점>, position_tolerance}]`.
     *
     * **[name]이 그대로 [ObjectSelector]의 `name`으로 간다.** 옮기는 표가 없는
     * 것이 요점이며(ADR 34), 그 이름을 로봇이 알게 만드는 것은 `add-object`·
     * `set-floorplan-map`으로 하는 **사이트 작업**이다(ADR 35).
     */
    fun gotoNamed(name: String): Result<ActionRef>

    /**
     * `["action-sequential", {actions: [action-pick{object}, action-place{reference_frame}]}]`.
     *
     * 계약의 `pick_place` 하나를 벤더의 액션 **둘을 합성한 하나**로 옮긴다.
     * 태스크 단위를 계약이 정하고 합성 방법을 어댑터가 정하는 것이며, 벤더가
     * `action-sequential`을 그 용도로 두었다.
     */
    fun pickAndPlace(objectName: String, destinationName: String): Result<ActionRef>

    /**
     * `["remove-action", {reference_number}]`.
     *
     * **취소가 있다.** 처음에 없다고 적었던 것이 이 파일의 가장 큰 오독이었다.
     * 다만 매뉴얼이 *컨테이너에 대해서는 성공한 것처럼 보인다*고 적었으므로
     * 중첩 액션에서 미덥지 않다 — 그래서 지운 뒤에도 상태를 다시 본다.
     */
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
    fun objectName(objectId: Int): Result<String?>

    /**
     * 마지막으로 받은 `["action-status-changed", {status}]`.
     *
     * **이 값이 되돌아갈 수 있다.** 매뉴얼이 *"This status does not latch once
     * reached"* 라 적었고, 그래서 §4.4의 래치 불변식이 **여기서 처음 실전이다** —
     * G1에서는 관절 각속도로 추론했는데 여기서는 벤더가 그 전이를 직접 보낸다.
     */
    fun status(): ActionStatus?

    /**
     * 마지막으로 받은 `["error", {info}]`의 **사람이 읽는 자유 문자열**.
     *
     * 결함 어휘가 없다 — 이것은 SDK 전수를 읽고도 그대로였다. 어댑터가 분류를
     * **포기하는 것**이 정직한 처리이며, 지어낸 분류는 로봇이 판정한 것처럼 보인다.
     */
    fun error(): String?
}

/** `remove-action`이 짚는 봉투의 `refnum`. */
@JvmInline
value class ActionRef(val referenceNumber: Int)

/** `["privileges", {privileges: [{has}]}]`가 말하는 것. */
enum class PrivilegeState { HELD, LOST }

/**
 * `action-status-changed`의 `status`.
 *
 * 확인한 값이 둘이다. **모르는 값이 오면 여기로 접지 않는다** — 어댑터가
 * 널로 받고 "모른다"로 다룬다.
 */
enum class ActionStatus { RUNNING, SUCCESS }
