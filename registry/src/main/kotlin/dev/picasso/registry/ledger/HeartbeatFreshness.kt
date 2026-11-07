package dev.picasso.registry.ledger

import java.time.Duration

/**
 * 관측선이 살아 있다고 인정하는 기본 최대 침묵(§15.41). 원장 질의([RegistryLedgerQuery])와 관측 가능성([RobotObservability]), 그리고
 * 진단의 생존 창(`DiagnosticsService.LIVENESS_WINDOW`)이 **이 한 자리를 쓴다** — 자리마다 따로 두면 한쪽만 바뀌어, 진단이 «보고 중»
 * 이라 적은 기체 때문에 축소가 막힌다.
 *
 * 24시간인 이유는 가동 중인 라인이 하루 한 번도 협상하지 않거나 태스크 하나 안 움직이는 일은 없기 때문이다. 기종·사이트마다 다른 값은
 * 아직 받지 않는다 — 각 생성자의 `freshness` 로만 바꾼다.
 */
val HEARTBEAT_FRESHNESS: Duration = Duration.ofHours(24)
