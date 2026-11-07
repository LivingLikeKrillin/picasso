package dev.picasso.middleware

/**
 * 이 층이 메모리에 드는 기록의 보존 상한(§15.166).
 *
 * 인시던트 번들, 탐색 기록, 서 있는 제안, 승인 소모 기록은 프로세스 안의 목록이고, 상한이 없으면 오래 도는 인스턴스에서 끝없이
 * 는다. **상한은 메모리를 묶는 것이지 보존 규약이 아니다** — 오래 둘 기록은 담는 쪽이 내보내기(§6)로 가져가고, 적재의 소유자가
 * 정해지면 보존 기간은 그쪽 규약이 정한다.
 *
 * 넘치면 **가장 오래된 것부터** 내린다. 단 아직 구동이 돌리는 실행([PhysicalState.pumps])의 인시던트 번들과 소모 기록은 내리지
 * 않는다 — 운영자가 그 실행을 다루는 동안 근거가 사라지면 안 된다. 그래서 상한은 그만큼 넘을 수 있다.
 *
 * 내린 기록은 되돌리지 않는다. 내린 제안을 승인하면 «제안이 없다» 로 거부되고(승인 없이 실행되는 길은 생기지 않는다), 내린
 * 소모 기록의 열쇠에 다시 온 승인도 같은 답을 받는다. 내린 수는 [RetentionEvictions] 로 센다.
 */
data class Retention(
    /** 인시던트 번들. 번들 하나가 근거 윈도우를 통째로 들어 가장 무거우므로 상한이 가장 낮다. */
    val incidents: Int = 2_000,
    /** 탐색 기록([RemedySearchRecord]). */
    val remedySearches: Int = 10_000,
    /** 서 있는 제안 — (기체, 주문)마다. 함께 든 가림 표시와 진단도 같이 내린다. */
    val proposals: Int = 10_000,
    /** 승인 소모 기록(ADR 46) — (기체, 주문)마다. 함께 든 진단도 같이 내린다. */
    val consumedApprovals: Int = 10_000,
) {
    init {
        require(incidents > 0 && remedySearches > 0 && proposals > 0 && consumedApprovals > 0) {
            "보존 상한은 양수여야 한다: $this"
        }
    }
}

/** 보존 상한으로 내린 기록의 수 — 인스턴스가 뜬 뒤로 센다(§15.166). 0 이 아니면 그만큼 조회에서 빠졌다. */
data class RetentionEvictions(
    val incidents: Long,
    val remedySearches: Long,
    val proposals: Long,
    val consumedApprovals: Long,
)
