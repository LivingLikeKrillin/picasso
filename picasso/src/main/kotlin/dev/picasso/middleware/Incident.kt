package dev.picasso.middleware

import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import java.security.MessageDigest
import java.time.Instant

/**
 * 결함 하나를 **정준 분류와 벤더 원문을 함께** 적은 것(§15.177).
 *
 * 앞 판은 분류 이름만 실었다. 그러면 읽는 쪽이 «왜 그 분류가 됐는지» 를 말할 재료가 없어 **분류를
 * 되풀이하는 것 말고 할 수 있는 것이 없다.** 계약이 칸을 갖고 있고 이 층도 읽는데 번들만 버리고 있었다.
 *
 * **분류는 분기의 입력이고 원문은 진단의 재료다.** 둘을 한 자리에 두는 것이 이 층의 본업인 번역을
 * 읽는 쪽이 따라갈 수 있게 한다 — «이 벤더 코드가 이 분류로 왔다» 를 어댑터 매핑표에 대고 짚을 수 있다.
 */
data class FaultDetail(
    /** 미들웨어가 매긴 정준 분류(접두사 없이). 분류가 없으면 `UNCLASSIFIED` 다. */
    val failureClass: String,
    /** 어댑터가 낸 오류 유형. 벤더 이름공간이면 `X_` 로 시작한다. */
    val errorType: String,
    /** 벤더 원문 — 코드·메시지·상태 이름. **비어 있을 수 있다**(벤더가 없는 경우). */
    val vendorDetail: String,
    /** 사람이 취할 조치. 사람 개입 등급을 실무에서 쓸모 있게 만드는 값이다. */
    val errorHint: String,
    /**
     * 스킬 수준 결함이면 `KEY_SKILL_ID`·`KEY_TASK_ID` 가 들어온다. 로봇 수준이면 **빈 목록**이다 —
     * 그 구분이 «이동은 되는데 조작만 안 된다» 를 표현하는 자리다(§4.6).
     *
     * **맵으로 접지 않는다.** 같은 열쇠가 둘 올 수 있고, 접으면 그중 하나가 조용히 사라진다.
     */
    val references: List<FaultReference>,
    val canContinueCurrentTask: Boolean,
    val canAcceptNewTask: Boolean,
    /** 결함의 수명 종류 — `KIND_UNTIL_CLEARED` · `KIND_UNTIL_NEW_TASK` · `KIND_UNTIL`. */
    val activeUntilKind: String,
    /** 수명이 절대 시각이면 그 시각. 아니면 빈 문자열이다. */
    val activeUntilTime: String,
)

/** 결함이 지목한 대상 하나. 계약 `Reference` 를 옮겨 싣는다. */
data class FaultReference(val key: String, val value: String)

/**
 * **몇 걸음 중 어디서 깨졌나**(§15.177).
 *
 * 앞 판은 `unitId` 하나만 실어 앞뒤가 없었다. 단위 이름만으로는 그것이 첫 걸음인지 마지막 걸음인지
 * 알 수 없고, 그러면 «거의 다 끝났는데 마지막에 깨졌다» 와 «시작하자마자 깨졌다» 가 같은 모양이 된다.
 *
 * 총수는 [plan] 의 크기다. 따로 싣지 않는다 — 같은 사실을 두 칸에 두면 갈릴 자리가 생긴다.
 */
data class StepPosition(
    /** 이 단위가 몇 번째인가. **1 부터.** 계획에 없으면 0 이다 — 없는 것을 1 로 적지 않는다. */
    val at: Int,
    /** 계획된 단위 이름 전부, 순서대로. */
    val plan: List<String>,
    /** 여기까지 끝난 단위 이름. [plan] 의 부분집합이다. */
    val completed: List<String>,
)

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
    /**
     * 어느 기체인가. **자기 선언의 3분의 1이다** — 없으면 읽는 쪽이 분류에서 기종을 역추론하게 되고,
     * 그 추론은 도메인 사실이 아니라 **어댑터 매핑 공백**을 읽는 것이라 매핑 한 줄이 늘면 조용히 틀린다
     * (§15.177). 식별자이지 기종 분기가 아니다 — 이 층은 여전히 기종을 모른다.
     */
    val robotId: String,
    val unitId: String,
    /** 가상 시계. 재현 판정의 기준이고 해시에 든다. */
    val at: Instant,
    /** 실 시계. 현장 대조의 기준이고 해시에서 빠진다. */
    val wallClockAt: Instant,
    /** 미들웨어가 이미 매긴 정준 분류. 번들이 다시 판단하지 않는다. */
    val failureClass: String?,
    /**
     * **[failureClass] 가 그 값이 된 근거** — 이 단위를 실패로 만든 결함의 원문(§15.177).
     *
     * 분류만 있으면 읽는 쪽은 분류를 되풀이하는 것 말고 할 수 있는 것이 없다. [blockedBy] 는
     * **다음 단위를 막는** 결함이라 다음 단위가 없으면 비므로, 이 단위 자신의 실패를 설명하는 자리는
     * 따로 있어야 한다.
     *
     * 하류가 결함 없이 실패를 알린 경우(설비 대조 어긋남 같은)에는 널이다 — 없는 원문을 지어내지 않는다.
     */
    val fault: FaultDetail? = null,
    /**
     * 실행 전체를 막는 결함 — 이 사건이 단위의 문제인지 기체의 문제인지 가른다.
     *
     * **분류 이름으로 접지 않는다**(§15.177). 상류 통보(`JobResponse`)는 분류만 내지만 그것은 계약의
     * 소비자가 분기할 값이고, 이 대장은 사람이 원인을 말할 재료다 — 성질이 다르므로 싣는 것도 다르다.
     */
    val blockedBy: List<FaultDetail>,
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
    /**
     * 상류가 **요구한** 근거 등급과 이 단위가 **실제로 닿은** 등급, 그리고 설비 대조의 결과.
     *
     * ★**«이 판정을 얼마나 믿어야 하나» 가 이 셋이다.** 등급은 순서가 곧 세기이므로(E0 로봇 자기 보고 ·
     * E1 플릿 확인 · E2 독립 설비 확인 · E3 업무 ack), 요구와 도달을 맞대면 «이 결론은 로봇 자기 보고
     * 하나에 기대고 있다» 를 말할 수 있다. 없으면 읽는 쪽은 그 문장을 쓸 수 없다.
     *
     * [verification] 은 설비가 무엇을 말했는가다 — 안 물었다(`NOT_REQUESTED`)와 물었는데 말이 없다
     * (`ABSENT`)와 다른 것을 봤다(`MISMATCH`)는 다음 행동이 다 다르다.
     */
    val requiredEvidence: Evidence,
    val reachedEvidence: Evidence,
    val verification: Verification,
    /** 몇 걸음 중 어디서 깨졌나. */
    val step: StepPosition,
    /** 그때 무슨 모델이었나. 모델이 바뀐 뒤에 사건을 읽으면 이것 없이는 오독한다. */
    val profileRevision: Int,
    val contractSemver: String,
    /**
     * 이 사건이 난 실행이 **승인된 조치로 시작됐다면** 그것을 누른 쪽. 아니면 널이다(ADR 43).
     *
     * 해시에 든다 — 다른 쪽이 승인한 같은 모양의 사건은 **다른 사건**이다. 누가 눌렀는지가 이 사건에
     * 대해 할 말을 바꾸기 때문이다.
     */
    val approvedBy: Approver? = null,
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
     * **새 칸은 전부 든다**(§15.177). 기체·근거 등급·대조 결과·걸음 위치·결함 원문은 봉인 시점에
     * 확정되고 같은 시드면 같은 값이며, 그중 어느 하나가 달라진 사건은 **다른 사건**이다. 특히 기체가
     * 그렇다 — 같은 모양의 실패라도 어느 기체에서 났는지가 할 말을 바꾼다.
     *
     * **해시에서 빼는 것은 둘뿐이다 — [wallClockAt] 과 [review].** 설계안은 식별자도 빼라고 적었는데,
     * 이 층의 식별자는 전부 세는 수에서 나오므로(`exec-N`·`incident-N`·`resp-N`) 같은 시드면 같은 값이다.
     * 뺄 이유가 없고, 넣으면 사건의 순서까지 고정된다. 이 목록이 곧 "무엇이 결정적인가" 의 답이다.
     */
    private fun canonicalFault(f: FaultDetail): String =
        "${f.failureClass}|${f.errorType}|${f.vendorDetail}|${f.errorHint}|" +
            f.references.joinToString(";") { "${it.key}=${it.value}" } +
            "|${f.canContinueCurrentTask}|${f.canAcceptNewTask}|${f.activeUntilKind}|${f.activeUntilTime}"

    fun digest(): String {
        val canonical = buildString {
            appendLine(incidentId)
            appendLine(jobOrderId)
            appendLine(executionId)
            appendLine(robotId)
            appendLine(unitId)
            appendLine(at.toString())
            appendLine(failureClass.orEmpty())
            appendLine(fault?.let { canonicalFault(it) }.orEmpty())
            blockedBy.forEach { appendLine(canonicalFault(it)) }
            appendLine("${residualHold.kind.name}|${residualHold.objectRef}|${residualHold.reason}")
            appendLine(unresolved.toString())
            appendLine(preconditionSubjects.joinToString(","))
            appendLine(windowTruncated.toString())
            appendLine(effectMismatch.orEmpty())
            appendLine(expectedHold?.name.orEmpty())
            appendLine(observedHold.name)
            evidenceWindow.forEach { appendLine("${it.sequence}|${it.occurredAt}|${it.kind}|${it.local}|${it.detail}") }
            appendLine("${requiredEvidence.name}|${reachedEvidence.name}|${verification.name}")
            appendLine("${step.at}|${step.plan.joinToString(",")}|${step.completed.joinToString(",")}")
            appendLine(profileRevision.toString())
            appendLine(contractSemver)
            appendLine(approvedBy?.let { "${it.id}|${it.kind.name}" }.orEmpty())
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
