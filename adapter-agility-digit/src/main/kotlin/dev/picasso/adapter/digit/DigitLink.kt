package dev.picasso.adapter.digit

/**
 * 남쪽 경계 — Digit이 실제로 말하는 것.
 *
 * ## 세 기종 중 계층이 가운데다
 *
 * | 기종 | 층 | 무엇이 있나 |
 * |---|---|---|
 * | G1 | 없음 | 명령뿐. 태스크 개념이 없다 |
 * | **Digit** | **명령 + 상태 스트림 + 합성** | 액션마다 [ActionStatus]가 오고 `action-sequential`·`action-concurrent`로 묶인다. **그러나 일시정지·취소·재시작이 없다** |
 * | Spot | 미션 | `MissionService`가 생명주기를 그대로 준다 |
 *
 * **표본 둘로는 이 가운데가 안 보였다.** 상태 스트림과 합성은 있는데 생명주기
 * 조작이 없는 상태가 따로 있다는 것이 셋째에서 드러났다.
 *
 * ## 근거 등급이 다른 둘보다 낮다
 *
 * Spot은 벤더의 공개 proto 원문을 읽었다. 여기는 그러지 못했다 — 벤더 문서가
 * 닿지 않고 공식 SDK가 공개 저장소에 없어, **원시 JSON을 그대로 쓰는 제3자
 * 코드**(`json-v1-agility` 서브프로토콜)와 2026-09-05 조사를 썼다. 그래서
 * 여기 없는 것 중 일부는 *"공개된 것에서 못 찾았다"* 이며, 그 구분을
 * `profile/distance/agility-digit.json`이 진다.
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
     * `["action-move", {velocity: {rpyxyz: [0,0,yaw,vx,vy,0]}, mobility-parameters}]`.
     *
     * **지속시간을 안 받는다.** 같은 어휘의 `action-stand`에는 `duration`이
     * 있으므로 개념이 없는 것이 아니라 이 액션에 없다. 보내면 다른 액션이
     * 덮을 때까지 계속 걷는다 — 정지 시점을 [DigitAdapter]가 소유하는 이유이며,
     * 그래서 이 기종만 **실패 방향이 반대**다.
     */
    fun move(yawRate: Double, forward: Double, lateral: Double): Result<Unit>

    /**
     * `["action-stand", {base-pose, duration}]`.
     *
     * 계약의 취소와 어댑터의 정지가 둘 다 이것으로 나간다. **벤더가 준 취소
     * 프리미티브가 아니라 다른 액션으로 덮어쓰는 것**이며, 프로파일의
     * `cancel_support: YES`가 어댑터의 것인 이유다.
     */
    fun stand(): Result<Unit>

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
     * 결함 어휘가 없다. 어댑터가 분류를 **포기하는 것**이 정직한 처리이며,
     * 지어낸 분류는 로봇이 판정한 것처럼 보인다.
     */
    fun error(): String?
}

/** `["privileges", {privileges: [{has}]}]`가 말하는 것. */
enum class PrivilegeState { HELD, LOST }

/**
 * `action-status-changed`의 `status`.
 *
 * 확인한 값이 둘이다. **모르는 값이 오면 [ActionStatus]로 접지 않는다** —
 * 어댑터가 널로 받고 "모른다"로 다룬다.
 */
enum class ActionStatus { RUNNING, SUCCESS }
