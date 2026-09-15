package dev.picasso.middleware

import dev.picasso.contracts.v1.HoldState
import java.security.MessageDigest
import java.time.Instant

/**
 * 하나의 실패를, 사람이 **원인을 말할 수 있는 최소 단위**로 묶은 것(설계안 §4).
 *
 * **새 사실을 만들지 않는다.** 실패 분류도 차단 결함도 잔여 파지도 이미 각자의 자리에서 답한다.
 * 번들이 하는 일은 그 사실들을 한 시점의 한 사건으로 묶고, 그때의 근거 창을 붙이는 것이다 —
 * 운영자가 "이 단위가" 와 "이 기체가" 와 "그때 무슨 일이 있었나" 를 한 화면에서 잇게.
 *
 * 읽기 전용이다. 이 층의 거동은 번들이 있든 없든 같다.
 */
data class IncidentBundle(
    val incidentId: String,
    val jobOrderId: String,
    val executionId: String,
    val unitId: String,
    /** 가상 시계. 재현 판정의 기준이고 해시에 든다. */
    val at: Instant,
    /** 실 시계. 현장 대조의 기준이고 해시에서 빠진다. */
    val wallClockAt: Instant,
    /** 미들웨어가 이미 매긴 정준 분류. 번들이 다시 판단하지 않는다. */
    val failureClass: String?,
    /** 실행 전체를 막는 결함 — 이 사건이 단위의 문제인지 기체의 문제인지 가른다. */
    val blockedBy: List<String>,
    /** 마지막 관측. **빈손과 침묵을 접지 않는다**(§15.145). */
    val residualHold: HoldState,
    /** 판정을 내렸는가 보류했는가. */
    val unresolved: Boolean,
    /** 위반된 사전 조건의 주어. 조건 검사의 출력을 옮긴 것이고 다시 계산하지 않는다. */
    val preconditionSubjects: List<String>,
    /** 유효 시간창 안의 설비 신호와 로봇 이벤트. 원인 후보를 좁히는 실질 재료다. */
    val evidenceWindow: List<ObservedEvent>,
    /** 창 밖이라 버린 관측이 있는가. 조용히 자르면 읽는 사람이 창을 완전한 것으로 오해한다. */
    val windowTruncated: Boolean,
    /** 그때 무슨 모델이었나. 모델이 바뀐 뒤에 사건을 읽으면 이것 없이는 오독한다. */
    val profileRevision: Int,
    val contractSemver: String,
    /**
     * 사후 대조 고리(설계안 §7) — 원인 지목은 가설이고 정답은 정비 실적과 재발 여부로 나중에 나온다.
     * 되먹이지 않으면 정답 라벨 없는 자동 진단이 영영 검증되지 않는다. 지금은 사람이 [Middleware.confirmIncident]
     * 로 적는 자리만 있다. 해시에서 빠진다 — 같은 사건이 나중의 확인 때문에 다른 사건이 되지는 않는다.
     */
    val postHocCause: String? = null,
) {
    /**
     * 같은 시드와 가상 시계면 같은 값이 나온다(설계안 §4.4).
     *
     * **해시에서 빼는 것은 둘뿐이다 — [wallClockAt] 과 [postHocCause].** 설계안은 식별자도 빼라고 적었는데,
     * 이 층의 식별자는 전부 세는 수에서 나오므로(`exec-N`·`incident-N`·`resp-N`) 같은 시드면 같은 값이다.
     * 뺄 이유가 없고, 넣으면 사건의 순서까지 고정된다. 이 목록이 곧 "무엇이 결정적인가" 의 답이다.
     */
    fun digest(): String {
        val canonical = buildString {
            appendLine(incidentId)
            appendLine(jobOrderId)
            appendLine(executionId)
            appendLine(unitId)
            appendLine(at.toString())
            appendLine(failureClass.orEmpty())
            appendLine(blockedBy.joinToString(","))
            appendLine("${residualHold.kind.name}|${residualHold.objectRef}|${residualHold.reason}")
            appendLine(unresolved.toString())
            appendLine(preconditionSubjects.joinToString(","))
            appendLine(windowTruncated.toString())
            evidenceWindow.forEach { appendLine("${it.sequence}|${it.occurredAt}|${it.kind}|${it.local}|${it.detail}") }
            appendLine(profileRevision.toString())
            appendLine(contractSemver)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
