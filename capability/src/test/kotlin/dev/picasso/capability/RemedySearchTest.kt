package dev.picasso.capability

import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.Precondition
import dev.picasso.contracts.v1.PreconditionSubject
import dev.picasso.contracts.v1.SkillDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 대안 탐색(`INCIDENT_AND_REMEDY_PROPOSAL.md` §6) — 깨진 전제를 충족시키는 다른 능력 조합을 계산한다.
 *
 * **계획 시점 사슬 검사의 역방향이다.** 사슬 검사가 "이 순서로 보내면 깨지는가" 를 묻고, 여기는
 * "참으로 만들려면 무엇을 해야 하나" 를 묻는다. 호출자는 아직 없다 — 규칙을 먼저 고정하고 경로를
 * 붙이는 순서다(설계안 §9). 순서를 바꾸면 탐색의 오류와 경로의 오류가 섞인다.
 */
class RemedySearchTest {

    private fun requires(kind: HoldKind): Precondition = Precondition.newBuilder()
        .setSubject(PreconditionSubject.PRECONDITION_SUBJECT_HOLD)
        .setRequires(kind)
        .build()

    private fun skill(type: String, vararg preconditions: Precondition): SkillDeclaration =
        SkillDeclaration.newBuilder().setSkillType(type).setMajor(1)
            .addAllPreconditions(preconditions.toList()).build()

    private fun hold(kind: HoldKind): HoldState = HoldState.newBuilder().setKind(kind).build()

    /** 든 채로는 못 걷는 기체. 참고 프로파일 `humanoid-a` 가 선언한 것과 같은 모양이다. */
    private val navigate = skill("navigate_to", requires(HoldKind.HOLD_KIND_EMPTY))
    private val pickPlace = skill("pick_place")
    private val inspect = skill("inspect")

    @Test
    fun `이미 보낼 수 있으면 걸음이 없다`() {
        val found = assertIs<Remedy.Found>(
            RemedySearch.search(navigate, listOf(navigate, pickPlace), hold(HoldKind.HOLD_KIND_EMPTY)),
        )
        assertEquals(emptyList(), found.steps, "할 일이 없는데 조치를 제안했다")
    }

    @Test
    fun `든 채로 못 걸을 때 놓는 스킬 한 걸음을 제안한다`() {
        // `pick_place` 는 쥐고 놓으므로 끝난 뒤 빈손이다 — 그 효과가 `navigate_to` 의 전제를 참으로 만든다.
        val found = assertIs<Remedy.Found>(
            RemedySearch.search(navigate, listOf(navigate, pickPlace, inspect), hold(HoldKind.HOLD_KIND_HOLDING)),
        )
        assertEquals(listOf("pick_place"), found.steps.map { it.skillType })
        assertEquals(HoldKind.HOLD_KIND_EMPTY, found.steps.single().expectedHold)
    }

    @Test
    fun `걸음마다 전제와 기대 효과와 실패 시 도달 상태를 싣는다`() {
        // 이것이 없으면 승인은 곧 의례가 된다(§6.3). 특히 실패 시 상태 — 쥐는 스킬이 실패하면 든 채다.
        val step = assertIs<Remedy.Found>(
            RemedySearch.search(navigate, listOf(navigate, pickPlace), hold(HoldKind.HOLD_KIND_HOLDING)),
        ).steps.single()

        assertEquals(emptyList(), step.requires, "pick_place 는 조건을 선언하지 않는다")
        assertEquals(HoldKind.HOLD_KIND_EMPTY, step.expectedHold)
        assertEquals(HoldKind.HOLD_KIND_HOLDING, step.onFailureHold, "쥐는 스킬이 실패했는데 빈손이라고 했다")
    }

    @Test
    fun `선언에 없는 스킬은 제안하지 않는다`() {
        // 이 기체가 못 하는 일을 제안하면 승인해도 실행되지 않는다. 효과를 아는 스킬이라도 마찬가지다.
        val none = assertIs<Remedy.None>(
            RemedySearch.search(navigate, listOf(navigate, inspect), hold(HoldKind.HOLD_KIND_HOLDING)),
        )
        assertEquals(Remedy.None.Cause.NO_CAPABILITY, none.cause)
    }

    @Test
    fun `깊이 상한을 넘으면 대안 없음이고 사유가 상한이다`() {
        // 셋을 넘는 조치 열은 운영자가 승인 시점에 검토할 수 없다. 검토되지 않는 제안은 자동 실행이다(§6.2).
        val none = assertIs<Remedy.None>(
            RemedySearch.search(navigate, listOf(navigate, pickPlace), hold(HoldKind.HOLD_KIND_HOLDING), maxDepth = 0),
        )
        assertEquals(Remedy.None.Cause.DEPTH_LIMIT, none.cause, "상한에 걸린 것을 «없다» 로 답했다")
        assertTrue(none.unmet.isNotEmpty(), "어느 조건이 안 채워졌는지 없이 «없다» 만 냈다")
    }

    @Test
    fun `못 채운 조건을 사유로 싣는다`() {
        // 빈 목록으로 접으면 "찾지 못했다" 와 "없다" 가 구별되지 않는다(§6.3).
        val none = assertIs<Remedy.None>(
            RemedySearch.search(navigate, listOf(navigate, inspect), hold(HoldKind.HOLD_KIND_HOLDING)),
        )
        assertEquals(1, none.unmet.size)
        assertEquals(PreconditionSubject.PRECONDITION_SUBJECT_HOLD, none.unmet.single().subject)
        assertEquals(HoldKind.HOLD_KIND_EMPTY, none.unmet.single().required)
        assertEquals(HoldKind.HOLD_KIND_HOLDING, none.unmet.single().observed)
    }

    @Test
    fun `관측 불가에서는 대안을 계산하지 않는다`() {
        // 효과는 파지를 바꾸지 **관측 가능성**을 바꾸지 못한다. 못 보는 것을 조치로 덮으면 그것이
        // 바로 «선언으로 현실을 단정하는» 일이다(§5.2 와 같은 이유).
        val none = assertIs<Remedy.None>(
            RemedySearch.search(navigate, listOf(navigate, pickPlace, inspect), hold(HoldKind.HOLD_KIND_NOT_OBSERVABLE)),
        )
        assertEquals(Remedy.None.Cause.NO_CAPABILITY, none.cause)
        assertEquals(HoldKind.HOLD_KIND_NOT_OBSERVABLE, none.unmet.single().observed)
    }

    @Test
    fun `자기 전제가 깨진 스킬은 걸음으로 쓰지 않는다`() {
        // 적용 가능성은 기존 조건 검사를 그대로 쓴다(§6.2). 지금 보낼 수 없는 스킬을 딛고 가는 계획은
        // 계획이 아니다. 조건을 안 보면 이 기체는 `pick_place` 한 걸음으로 답이 나온다 — 그 답이
        // 나오면 검사를 건너뛴 것이다.
        val gated = skill("pick_place", requires(HoldKind.HOLD_KIND_EMPTY))
        val none = assertIs<Remedy.None>(
            RemedySearch.search(navigate, listOf(navigate, gated), hold(HoldKind.HOLD_KIND_HOLDING)),
        )
        assertEquals(Remedy.None.Cause.NO_CAPABILITY, none.cause, "전제가 깨진 스킬을 딛고 계획을 세웠다")
    }
}
