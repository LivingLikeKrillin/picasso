package dev.picasso.middleware.mission

/**
 * 현장 신호 사양의 한 줄 — 설비 대기가 이름으로 참조하는 신호가 무엇인가(현장 데이터).
 *
 * 이름과 성질까지다. **어느 주소가 어느 신호인가의 매핑은 여기 없다** — 그것은 드라이버의 일이고, 이 계층은 이름으로
 * 읽는다(`CellSignals.signal`).
 *
 * @param location 그 신호를 내는 자리. 있으면 검증기의 자원 검사가 그 자리의 바닥 소유를 본다. 없으면 그 검사에서 빠진다.
 * @param safety 안전 신호인가. **안전 신호를 기다리는 대기는 거부한다** — 안전 기능은 이 소프트웨어 계약을 거치지
 *   않는다(ADR 32).
 */
data class SignalSpec(
    val name: String,
    val location: String? = null,
    val kind: SignalKind,
    val safety: Boolean = false,
)

/** 신호 값의 종류. [BOOLEAN] 이면 값은 `true`·`false` 둘이다. [TEXT] 는 어떤 문자열이든 된다. */
enum class SignalKind { BOOLEAN, TEXT }
