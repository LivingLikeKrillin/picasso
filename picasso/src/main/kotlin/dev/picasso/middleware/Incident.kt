package dev.picasso.middleware

import dev.picasso.contracts.v1.HoldKind
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
    /**
     * 판단 경로(설계안 §7.2 셋째) — **어느 신호가 어떤 값이어서 그렇게 판단했는가.** 분류만 내면 아무도
     * 배우지 않는다. [expectedHold] 가 효과에서 유도한 기대이고 [observedHold] 가 그때의 관측이며,
     * 둘이 어긋난 결과가 [effectMismatch] 다. 근거를 함께 내는 이유는 신뢰가 아니라 **학습**이다.
     */
    val expectedHold: HoldKind?,
    val observedHold: HoldKind,
    /**
     * 효과-관측 어긋남(설계안 §5) — 번들이 **새로 계산하는 유일한 사실**이다. 나머지는 전부 옮겨 싣는 것이다.
     * 관측이 없거나 볼 수 없으면 `null` 이고, 그때 운영자가 받는 것은 여전히 «모른다» 다(§5.2).
     */
    val effectMismatch: String?,
    /** 그때 무슨 모델이었나. 모델이 바뀐 뒤에 사건을 읽으면 이것 없이는 오독한다. */
    val profileRevision: Int,
    val contractSemver: String,
    /**
     * 사람이 이 사건을 읽고 남긴 판정(설계안 §7.2). 읽기 전에는 널이다.
     *
     * 해시에서 빠진다 — 같은 사건이 나중의 검토 때문에 다른 사건이 되지는 않는다.
     */
    val review: IncidentReview? = null,
) {
    /**
     * 같은 시드와 가상 시계면 같은 값이 나온다(설계안 §4.4).
     *
     * **해시에서 빼는 것은 둘뿐이다 — [wallClockAt] 과 [review].** 설계안은 식별자도 빼라고 적었는데,
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
            appendLine(effectMismatch.orEmpty())
            appendLine(expectedHold?.name.orEmpty())
            appendLine(observedHold.name)
            evidenceWindow.forEach { appendLine("${it.sequence}|${it.occurredAt}|${it.kind}|${it.local}|${it.detail}") }
            appendLine(profileRevision.toString())
            appendLine(contractSemver)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/** 사람이 자동 진단에 동의했는가 이의를 달았는가(설계안 §7.2 둘째). */
enum class ReviewVerdict { AGREED, DISPUTED }

/**
 * 교대 종료에 사람이 사건을 읽고 남긴 것.
 *
 * **실시간이 아니라 사후에 넣는다.** 라인을 멈추지 않으니 고무도장이 될 유인이 적고, 그래서 이 기록이
 * 지표로 쓸 값이 된다. 원인 지목은 가설이고 정답은 정비 실적과 재발 여부로 나중에 나온다 — 되먹이지
 * 않으면 정답 라벨 없는 자동 진단이 영원히 검증되지 않는다(설계안 §7.2 첫째).
 */
data class IncidentReview(
    val verdict: ReviewVerdict,
    /** 사람이 적은 원인. 이의라면 사람 쪽 판단이 여기 들어간다. */
    val cause: String,
    /** 읽은 시각 — 현장 대조용이므로 실 시계다. */
    val at: Instant,
)

/**
 * 검토가 실제로 일어나는가, 그리고 자동 진단이 맞는가(설계안 §7.2 둘째).
 *
 * **[disputeRate] 만 보면 안 된다.** 아무도 안 읽으면 이의가 0 이고, 그것은 완벽해진 것이 아니라 아무도
 * 안 읽는 것이다. 그래서 읽은 것이 없으면 이의율은 `null` 이다 — 0 을 돌려주면 그 자리에서 «완벽하다» 로
 * 읽힌다. 검토율과 함께 보라는 규율을 타입이 든다.
 */
data class ReviewMetrics(val total: Int, val reviewed: Int, val disputed: Int) {
    val reviewRate: Double? = if (total == 0) null else reviewed.toDouble() / total
    val disputeRate: Double? = if (reviewed == 0) null else disputed.toDouble() / reviewed
}

/**
 * 같은 조치가 거듭 승인된 사실(설계안 §7.3).
 *
 * **임시 대안이 매끄럽게 작동할수록 근본 원인을 고칠 압력이 사라진다.** 이것은 계열이 알려진 실패
 * 양식이다 — 응급 조치가 표준 작업으로 굳는 것, 자동화가 수작업 부담을 가려 근본 수정을 미루는 것.
 * 그래서 반복 자체를 지표로 올린다.
 */
data class RepeatedRemedy(val robotId: String, val steps: List<String>, val approvals: Int)
