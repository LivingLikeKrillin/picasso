package dev.picasso.middleware

import dev.picasso.capability.RemedyStep
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.ParameterDeclaration
import dev.picasso.contracts.v1.SkillDeclaration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 자동 승인이 실을 값은 **어디서 오는가**(ADR 44).
 *
 * 출처가 둘뿐이다 — 계약이 `is_object_reference` 로 표시한 칸은 **관측**에서, 나머지 필수 칸은 사람의
 * **선언**에서. 지어내는 갈래는 없고, 못 채우면 거절이고 사람에게 남는다.
 *
 * **여기가 순수 판정의 자리다.** [RemedyValues] 가 미들웨어도 포트도 시계도 안 보므로, 실물에서 아직
 * 못 만드는 관측(기체가 «든 채» 라면서 이름을 안 주는 경우 — §15.173)도 여기서는 세울 수 있다.
 */
class RemedyValuesTest {

    @Test
    fun `대상은 관측에서 오고 나머지 필수 칸은 선언에서 온다`() {
        val filled = assertIs<RemedyValues.Resolution.Filled>(
            RemedyValues.resolve(listOf(STEP), DECLARED, entitlement(DROP), holding("COVER-7")),
        )
        assertEquals(
            mapOf(OBJECT to "COVER-7", DESTINATION to "RACK-204.S01"),
            filled.parameters.single(),
        )
    }

    @Test
    fun `든 채라면서 이름을 안 주면 채우지 않는다`() {
        // ★**«든 채다» 와 «무엇을 들었다» 는 다른 사실이다.** 앞의 것만 있을 때 뒤의 것을 지어내면
        //   자동 승인이 무엇을 집는지 모르는 채 나간다. 실물 기체가 이렇게 답할 수 있다(§15.173).
        assertEquals(
            ApprovalRefusal.OBJECT_NOT_OBSERVED,
            refusal(RemedyValues.resolve(listOf(STEP), DECLARED, entitlement(DROP), holding(""))),
        )
    }

    @Test
    fun `빈손이거나 관측이 없으면 채우지 않는다`() {
        val empty = HoldState.newBuilder().setKind(HoldKind.HOLD_KIND_EMPTY).build()
        assertEquals(
            ApprovalRefusal.OBJECT_NOT_OBSERVED,
            refusal(RemedyValues.resolve(listOf(STEP), DECLARED, entitlement(DROP), empty)),
        )
        assertEquals(
            ApprovalRefusal.OBJECT_NOT_OBSERVED,
            refusal(RemedyValues.resolve(listOf(STEP), DECLARED, entitlement(DROP), observed = null)),
        )
    }

    @Test
    fun `선언은 대상을 좁히기만 하고 덮어쓰지 못한다`() {
        // ★★덮어쓸 수 있으면 선언이 사실을 이긴다. 그 순간 자동 승인이 엉뚱한 것을 집고, 그 사고는
        //   선언을 적은 사람의 의도와도 무관하다.
        val narrowed = entitlement(DROP + (OBJECT to "COVER-7"))
        assertIs<RemedyValues.Resolution.Filled>(
            RemedyValues.resolve(listOf(STEP), DECLARED, narrowed, holding("COVER-7")),
        )
        assertEquals(
            ApprovalRefusal.DECLARED_CONTRADICTS_OBSERVED,
            refusal(RemedyValues.resolve(listOf(STEP), DECLARED, narrowed, holding("다른-물건"))),
        )
    }

    @Test
    fun `필수 칸이 선언에 없으면 채우지 않는다`() {
        assertEquals(
            ApprovalRefusal.VALUE_NOT_DECLARED,
            refusal(RemedyValues.resolve(listOf(STEP), DECLARED, entitlement(emptyMap()), holding("COVER-7"))),
        )
    }

    @Test
    fun `선택 칸은 선언에 없어도 된다`() {
        // 없는 것을 요구하면 선언이 계약의 선택 칸까지 전부 적어야 하고, 그러면 minor 가 오를 때마다
        // 선언이 낡는다.
        val withOptional = SkillDeclaration.newBuilder()
            .setSkillType(SKILL)
            .addParameters(parameter(OBJECT, optional = false))
            .addParameters(parameter(DESTINATION, optional = false))
            .addParameters(parameter("grip_force", optional = true))
            .build()
        val filled = assertIs<RemedyValues.Resolution.Filled>(
            RemedyValues.resolve(listOf(STEP), mapOf(SKILL to withOptional), entitlement(DROP), holding("COVER-7")),
        )
        assertEquals(setOf(OBJECT, DESTINATION), filled.parameters.single().keys)
    }

    @Test
    fun `둘째 걸음이 들 것의 이름은 아무도 모른다`() {
        // ★★관측은 **지금**의 사실이다. 첫 걸음이 놓으면 그 다음에 무엇을 들지는 선언에도 관측에도
        //   없고, 효과로 굴려 얻은 «빈손» 이 그것을 말해 준다. 굴리지 않으면 지금 든 것의 이름이
        //   둘째 걸음에까지 실려 나간다.
        val answer = RemedyValues.resolve(listOf(STEP, STEP), DECLARED, entitlement(DROP), holding("COVER-7"))
        assertEquals(ApprovalRefusal.OBJECT_NOT_OBSERVED, refusal(answer))
        val refused = assertIs<RemedyValues.Resolution.Refused>(answer)
        assertEquals(true, "조치 2" in refused.reason, refused.reason)
    }

    @Test
    fun `선언이 안 덮는 조치 유형은 채우지 않는다`() {
        val other = Entitlement(
            "narrator-1",
            listOf(DeclaredAction("다른_스킬", emptyMap())),
            setOf("hum-02"),
            FAR,
        )
        assertEquals(
            ApprovalRefusal.SKILL_OUT_OF_SCOPE,
            refusal(RemedyValues.resolve(listOf(STEP), DECLARED, other, holding("COVER-7"))),
        )
    }

    @Test
    fun `기체가 선언하지 않은 스킬은 채우지 않는다`() {
        assertEquals(
            ApprovalRefusal.CAPABILITY_UNKNOWN,
            refusal(RemedyValues.resolve(listOf(STEP), emptyMap(), entitlement(DROP), holding("COVER-7"))),
        )
    }

    private companion object {
        /** 계약이 `is_object_reference` 를 붙인 칸은 이것이다 — 시험이 따로 정하지 않는다. */
        const val SKILL = "pick_place"
        const val OBJECT = "object_id"
        const val DESTINATION = "destination"

        val FAR: Instant = Instant.parse("2099-01-01T00:00:00Z")
        val DROP = mapOf(DESTINATION to "RACK-204.S01")

        val STEP = RemedyStep(SKILL, emptyList(), HoldKind.HOLD_KIND_EMPTY, HoldKind.HOLD_KIND_HOLDING)

        fun parameter(key: String, optional: Boolean) =
            ParameterDeclaration.newBuilder().setKey(key).setOptional(optional).build()

        val DECLARED = mapOf(
            SKILL to SkillDeclaration.newBuilder()
                .setSkillType(SKILL)
                .addParameters(parameter(OBJECT, optional = false))
                .addParameters(parameter(DESTINATION, optional = false))
                .build(),
        )

        fun entitlement(parameters: Map<String, String>) = Entitlement(
            "narrator-1",
            listOf(DeclaredAction(SKILL, parameters)),
            setOf("hum-02"),
            FAR,
        )

        fun holding(objectRef: String): HoldState = HoldState.newBuilder()
            .setKind(HoldKind.HOLD_KIND_HOLDING)
            .setObjectRef(objectRef)
            .build()

        fun refusal(resolution: RemedyValues.Resolution): ApprovalRefusal =
            assertIs<RemedyValues.Resolution.Refused>(resolution).refusal
    }
}
