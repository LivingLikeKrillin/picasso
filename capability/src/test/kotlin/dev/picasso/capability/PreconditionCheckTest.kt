package dev.picasso.capability

import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.Precondition
import dev.picasso.contracts.v1.PreconditionSubject
import dev.picasso.contracts.v1.SkillDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 설계안 §3 — 접수 전 사전 조건 평가. 미믹과 호스트가 같은 함수를 부른다.
 */
class PreconditionCheckTest {

    private val requiresEmpty: Precondition = Precondition.newBuilder()
        .setSubject(PreconditionSubject.PRECONDITION_SUBJECT_HOLD)
        .setRequires(HoldKind.HOLD_KIND_EMPTY)
        .build()

    private fun skill(vararg preconditions: Precondition): SkillDeclaration =
        SkillDeclaration.newBuilder().setSkillType("navigate_to").setMajor(1)
            .addAllPreconditions(preconditions.toList()).build()

    private fun hold(kind: HoldKind, reason: String = ""): HoldState =
        HoldState.newBuilder().setKind(kind).setReason(reason).build()

    @Test
    fun `요구가 EMPTY 인데 HOLDING 이면 위반이다`() {
        val violations = PreconditionCheck.check(skill(requiresEmpty), hold(HoldKind.HOLD_KIND_HOLDING))
        assertEquals(1, violations.size)
        assertEquals(PreconditionSubject.PRECONDITION_SUBJECT_HOLD, violations[0].subject)
        assertEquals(HoldKind.HOLD_KIND_EMPTY, violations[0].required)
        assertEquals(HoldKind.HOLD_KIND_HOLDING, violations[0].observed)
    }

    @Test
    fun `관측 불가는 통과가 아니다`() {
        // 빈손이 아니다 — 못 봤을 뿐이다. 조건이 있는데 못 봤으면 접수하지 않는다(설계안 §3.2).
        val violations = PreconditionCheck.check(
            skill(requiresEmpty), hold(HoldKind.HOLD_KIND_NOT_OBSERVABLE, "벤더가 파지 판정을 안 준다"),
        )
        assertEquals(1, violations.size)
        assertEquals(HoldKind.HOLD_KIND_NOT_OBSERVABLE, violations[0].observed)
        assertTrue("벤더가 파지 판정을 안 준다" in violations[0].detail, violations[0].detail)
    }

    @Test
    fun `말하지 않은 관측도 통과가 아니다`() {
        // UNSPECIFIED 는 이 필드가 생기기 전의 발신자다(task.proto). 조건을 평가할 근거가 없다.
        val violations = PreconditionCheck.check(skill(requiresEmpty), hold(HoldKind.HOLD_KIND_UNSPECIFIED))
        assertEquals(1, violations.size)
    }

    @Test
    fun `요구를 만족하면 통과다`() {
        assertEquals(emptyList(), PreconditionCheck.check(skill(requiresEmpty), hold(HoldKind.HOLD_KIND_EMPTY)))
    }

    @Test
    fun `조건이 없으면 어떤 관측이든 통과다`() {
        // 선언하지 않은 조건은 제약 없음이다(설계안 §6) — 관측 불가여도 막지 않는다.
        for (kind in HoldKind.values().filter { it != HoldKind.UNRECOGNIZED }) {
            assertEquals(emptyList(), PreconditionCheck.check(skill(), hold(kind)), kind.name)
        }
    }

    @Test
    fun `모르는 주어는 발신자에겐 위반이고 소비자에겐 유보다`() {
        // 리뷰 C5 — 새 계약이 주어를 더하면 옛 소비자는 UNRECOGNIZED 로 읽는다. 발신자는 자기가 선언한 것이라
        // 모를 리 없으니 fail-closed 가 맞고, 소비자는 모르는 조건을 지어내지 않는다 — 발신자에게 맡긴다(마이너 호환).
        val alien = Precondition.newBuilder().setSubjectValue(99).setRequires(HoldKind.HOLD_KIND_EMPTY).build()
        assertEquals(1, PreconditionCheck.check(skill(alien), hold(HoldKind.HOLD_KIND_EMPTY)).size, "발신자는 모르는 주어를 통과시키면 안 된다")
        assertEquals(
            emptyList(),
            PreconditionCheck.check(skill(alien), hold(HoldKind.HOLD_KIND_EMPTY), PreconditionCheck.Unknown.DEFER),
            "소비자는 모르는 주어를 유보해야 한다",
        )
        // 아는 주어는 유보 정책과 무관하게 평가된다.
        assertEquals(1, PreconditionCheck.check(skill(requiresEmpty), hold(HoldKind.HOLD_KIND_HOLDING), PreconditionCheck.Unknown.DEFER).size)
    }
}
