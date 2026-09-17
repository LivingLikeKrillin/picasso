package dev.picasso.middleware

import dev.picasso.capability.PreconditionViolation
import dev.picasso.capability.Remedy
import dev.picasso.capability.RemedyStep
import java.time.Instant

/**
 * 한 번의 대안 탐색이 무엇을 답했는가.
 *
 * ## 세 「없음」을 접지 않는다
 *
 * 밖에서 보면 «제안이 없다» 하나가 셋을 덮는다 — **이 기체로는 안 된다**([None] 의 `NO_CAPABILITY`),
 * **더 찾아보면 있을 수 있다**([None] 의 `DEPTH_LIMIT`), **사람이 먼저 진단하라**([Withheld]). 셋은 다음
 * 행동이 다르다. 접으면 운영자가 의도적 비자동화를 고장으로 읽고, 상한에 걸린 것을 «없다» 로 읽는다
 * (설계안 §6.3 · §7.2 넷째).
 *
 * ## 가려 둔 것의 내용은 여기로 새지 않는다
 *
 * [Withheld] 가 걸음을 싣지 않는 것은 누락이 아니라 성질이다. 이 대장은 밖이 읽는 조회면이므로, 여기에
 * 걸음을 실으면 가려 둔 제안을 조회로 읽을 수 있고 그러면 가리는 일 자체가 무의미해진다.
 */
sealed interface RemedyOutcome {

    /** 여기까지 하면 그 스킬을 보낼 수 있다. */
    data class Found(val steps: List<RemedyStep>) : RemedyOutcome

    /** 못 찾았다. [cause] 가 «없다» 와 «덜 봤다» 를 가르고, [unmet] 이 끝내 못 채운 조건이다. */
    data class None(val cause: Remedy.None.Cause, val unmet: List<PreconditionViolation>) : RemedyOutcome

    /** 찾았으나 가렸다. **걸음을 싣지 않는다.** */
    data object Withheld : RemedyOutcome
}

/**
 * 탐색 한 번. **새 사실을 만들지 않는다** — 이미 계산된 답을 그때의 두 시각과 함께 옮겨 실은 것이다(ADR 40).
 *
 * ## 열쇠가 사건이 아니라 (기체, 주문)인 이유
 *
 * 탐색은 **접수 관문 안**에서 돌고, 관문이 막으면 실행이 만들어지지 않는다. 실행이 없으면 실행 식별자도
 * 근거 창도 막는 결함도 없으므로 **거절은 사건 번들이 될 수 없다** — 그런 번들을 열면 창이 빈 것이
 * «창이 완전한데 아무 일도 없었다» 로 읽히고, 검토율의 분모도 거절 수만큼 늘어난다. 그래서 대장을 따로 둔다.
 *
 * 같은 (기체, 주문)이 거듭 거절될 수 있으므로 **덮어쓰지 않고 쌓는다.** 지금 서 있는 제안이 무엇인지는
 * 여전히 [Middleware.proposal] 과 [Middleware.withheldProposal] 이 답한다 — 이 대장은 **그때 무엇을
 * 답했는가** 의 기록이지 지금 상태가 아니다.
 */
data class RemedySearchRecord(
    /** `search-N`. **세는 수다** — 같은 시드면 같은 값이고, 조회 커서로 쓴다. 뜯어 읽지 않는다. */
    val searchId: String,
    val robotId: String,
    val jobOrderId: String,
    /** 가상 시계. 재현의 기준이다. */
    val at: Instant,
    /** 실 시계. 현장 대조의 기준이다. */
    val wallClockAt: Instant,
    val outcome: RemedyOutcome,
)
