package dev.picasso.capability

import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.Precondition
import dev.picasso.contracts.v1.PreconditionSubject
import dev.picasso.contracts.v1.SkillDeclaration

/** 선언된 조건 하나가 관측과 맞지 않았다. */
data class PreconditionViolation(
    val subject: PreconditionSubject,
    val required: HoldKind,
    val observed: HoldKind,
    /** 사람이 읽는다. 관측 불가면 그 이유가 들어간다. */
    val detail: String,
)

/**
 * 접수 전 사전 조건 평가(설계안 §3). 순수 함수다 — 입력은 선언과 관측, 출력은 위반 목록.
 *
 * **미믹과 어댑터 호스트가 같은 함수를 부른다.** `Negotiator` 가 여기 있는 이유와 같다(§15.100) —
 * 파라미터 범위 검사가 미믹에만 있어 호스트가 그것을 안 보는 갈림을 되풀이하지 않는다.
 *
 * **관측 불가는 통과가 아니다.** 조건이 선언돼 있는데 발신자가 파지 상태를 못 보면 접수하지
 * 않는다 — 관측 경로가 죽었을 때 정상을 답하면 고장난 기체가 정상으로 보인다. 그래서 파지를
 * 관측하지 못하는 기종은 조건을 선언하면 안 된다(설계안 §3.2).
 *
 * 조건이 없으면 어떤 관측이든 통과다 — 선언하지 않은 조건은 제약 없음이다(설계안 §6).
 */
object PreconditionCheck {

    /**
     * 모르는 주어(새 계약이 더한 값 — 옛 쪽에서는 `UNRECOGNIZED`)를 어떻게 볼 것인가.
     *
     * **발신자는 [REFUSE].** 자기가 선언한 조건이라 모를 리 없고, 모른다면 통과시킬 근거가 없다.
     * **소비자는 [DEFER].** 모르는 조건을 지어내지 않는다 — 판정은 발신자가 접수 때 한다. 여기서 거절하면
     * 마이너 호환(값 추가)이 소비자 쪽에서 깨진다(리뷰 C5).
     */
    enum class Unknown { REFUSE, DEFER }

    fun check(
        skill: SkillDeclaration,
        hold: HoldState,
        unknown: Unknown = Unknown.REFUSE,
    ): List<PreconditionViolation> =
        skill.preconditionsList.mapNotNull { precondition ->
            when (precondition.subject) {
                PreconditionSubject.PRECONDITION_SUBJECT_HOLD -> holdViolation(precondition, hold)
                else -> when (unknown) {
                    // 평가할 수 없는 주어는 통과가 아니다 — 모르는 조건을 만족했다고 말할 수 없다.
                    Unknown.REFUSE -> violation(precondition, hold.kind, "평가할 수 없는 주어다: ${precondition.subject.name}")
                    Unknown.DEFER -> null
                }
            }
        }

    private fun holdViolation(precondition: Precondition, hold: HoldState): PreconditionViolation? {
        val observed = hold.kind
        return when {
            observed == precondition.requires -> null
            observed == HoldKind.HOLD_KIND_NOT_OBSERVABLE ->
                violation(precondition, observed, "파지 상태를 볼 수 없다 — 빈손이 아니다: ${hold.reason}")
            observed == HoldKind.HOLD_KIND_UNSPECIFIED ->
                violation(precondition, observed, "발신자가 파지 상태를 말하지 않았다")
            else -> violation(
                precondition, observed,
                "요구=${precondition.requires.name.removePrefix("HOLD_KIND_")} " +
                    "관측=${observed.name.removePrefix("HOLD_KIND_")}",
            )
        }
    }

    private fun violation(precondition: Precondition, observed: HoldKind, detail: String) =
        PreconditionViolation(precondition.subject, precondition.requires, observed, detail)

    /**
     * 거절의 `detail`. **여기서만 만든다** — 미믹과 호스트가 각자 문장을 지으면 같은 위반에 다른 말이 나가고,
     * 그것을 `HostParityTest` 가 잡는다. 어긴 것을 전부 싣는다(협상과 같은 이유).
     */
    fun rejectionDetail(violations: List<PreconditionViolation>): String =
        violations.joinToString("; ") { "${subjectName(it)}: ${it.detail}" }

    /** 거절의 `KEY_PRECONDITION_SUBJECT` 참조 값들 — 어느 조건인지 응답만 보고 알 수 있어야 한다. */
    fun subjects(violations: List<PreconditionViolation>): List<String> =
        violations.map(::subjectName).distinct()

    private fun subjectName(violation: PreconditionViolation): String =
        violation.subject.name.removePrefix("PRECONDITION_SUBJECT_")
}
