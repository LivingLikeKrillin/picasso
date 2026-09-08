package dev.picasso.adapter.core

/**
 * 이 자리가 짚는 **벤더 원문의 이름들**.
 *
 * ## 왜 생겼나
 *
 * 남쪽 포트는 *"벤더에게 이런 메시지가 이런 필드로 있다"* 는 주장이고, 그
 * 주장이 지금까지 **KDoc 산문**이었다. 사람이 문서를 읽고 옮겼고, 틀려도
 * 아무것도 빨개지지 않았다.
 *
 * 2026-09-08 에 그 층에서 사고가 둘 났다.
 *
 * | 사고 | 무엇이 틀렸나 |
 * |---|---|
 * | Digit 측정이 통째로 뒤집힘 | 제3자 래퍼를 원문으로 삼아 없는 것을 만들어 냈다 |
 * | Spot `navigate_to` | `Waypoint.id`(로봇 생성)와 `annotations.name`(사람이 붙임)을 접었다 |
 *
 * 둘 다 **읽고 옮기는 단계**에서 났다. 이 애너테이션은 그 옮김을 데이터로
 * 만들어, 벤더 매니페스트에 없는 이름을 짚으면 시험이 빨개지게 한다.
 *
 * ## 무엇을 보증하고 무엇을 안 하나
 *
 * **이름이 있다는 것만 본다.** 그 메시지를 보냈을 때 로봇이 무엇을 하는지는
 * 전혀 안 본다 — 그것이 §9.7 ④·C-3 이고 여전히 열려 있다. 오독의 층을
 * 닫는 것이지 거동의 층을 닫는 것이 아니다.
 *
 * ## 이름의 모양
 *
 * 벤더마다 다르고, **벤더의 표기를 그대로 쓴다**(§15.56과 같은 이유 —
 * 우리 어휘로 옮기면 다음 조사에서 한 줄씩 맞대 보지 못한다).
 *
 * - Spot: proto 정규화 이름 `bosdyn.api.graph_nav.Waypoint.Annotations.name`
 * - Digit: JSON 메시지 이름과 필드 `action-goto`, `object-selector.name`
 * - G1: API 상수 `ROBOT_API_ID_LOCO_SET_VELOCITY`
 */
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.FIELD,
)
@Retention(AnnotationRetention.RUNTIME)
annotation class VendorSurface(vararg val symbols: String)
