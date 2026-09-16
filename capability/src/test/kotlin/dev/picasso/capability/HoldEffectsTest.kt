package dev.picasso.capability

import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.SkillCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 설계안 §1.1 — 카탈로그의 `grasps_object` · `releases_object` 가 말하는 잔여 물리 상태의 변화. */
class HoldEffectsTest {

    private val holding: HoldState = HoldState.newBuilder().setKind(HoldKind.HOLD_KIND_HOLDING).setObjectRef("tote-7").build()
    private val empty: HoldState = HoldState.newBuilder().setKind(HoldKind.HOLD_KIND_EMPTY).build()

    @Test
    fun `pick_place 는 쥐고 놓는다 — 끝난 뒤 빈손이다`() {
        assertTrue(HoldEffects.grasps("pick_place"))
        assertTrue(HoldEffects.releases("pick_place"))
        assertEquals(HoldKind.HOLD_KIND_EMPTY, HoldEffects.after("pick_place", holding).kind)
        assertEquals(HoldKind.HOLD_KIND_EMPTY, HoldEffects.after("pick_place", empty).kind)
    }

    @Test
    fun `참조만 하는 스킬은 파지를 바꾸지 않는다`() {
        for (skill in listOf("navigate_to", "inspect", "move_relative")) {
            assertEquals(holding, HoldEffects.after(skill, holding), skill)
            assertEquals(empty, HoldEffects.after(skill, empty), skill)
        }
    }

    @Test
    fun `카탈로그에 없는 스킬은 파지를 바꾸지 않는다`() {
        // 플릿 계약의 `transport` 처럼 카탈로그 밖의 단위 — 모르는 것을 바꿨다고 말하지 않는다.
        assertEquals(holding, HoldEffects.after("transport", holding))
    }

    // ── 효과와 관측의 어긋남(설계안 §5.1)

    @Test
    fun `놓고 끝나야 하는데 들고 있으면 미완료 파지다`() {
        assertEquals(HoldMismatch.INCOMPLETE_RELEASE, HoldEffects.mismatch("pick_place", HoldKind.HOLD_KIND_HOLDING))
    }

    @Test
    fun `들고 끝나야 하는데 빈손이면 적재 유실이다`() {
        // 표 첫째 줄. 카탈로그에 쥐고 놓지 않는 스킬이 없어 스킬 이름으로는 도달할 수 없으므로
        // 비교 자체를 겨냥한다 — 닿지 않는 가지는 규칙이 아니라 주석이다.
        assertEquals(HoldMismatch.PAYLOAD_LOST, HoldEffects.compare(HoldKind.HOLD_KIND_HOLDING, HoldKind.HOLD_KIND_EMPTY))
        assertEquals(HoldMismatch.INCOMPLETE_RELEASE, HoldEffects.compare(HoldKind.HOLD_KIND_EMPTY, HoldKind.HOLD_KIND_HOLDING))
        assertNull(HoldEffects.compare(HoldKind.HOLD_KIND_EMPTY, HoldKind.HOLD_KIND_EMPTY))
    }

    @Test
    fun `쥐고 놓지 않는 스킬이 카탈로그에 생기면 알린다`() {
        // 표 첫째 줄이 스킬로 도달 가능해지는 날을 고정한다. 그날 이 시험이 빨개지고, 그것이
        // «첫째 줄을 겨냥한 종단 시험을 함께 쓰라» 는 뜻이다.
        val graspOnly = SkillCatalog.getDescriptor().messageTypes
            .mapNotNull { it.options.getExtension(SkillCatalog.skillTypeName).takeIf { n -> !n.isNullOrEmpty() } }
            .filter { HoldEffects.grasps(it) && !HoldEffects.releases(it) }
        assertEquals(emptyList(), graspOnly, "쥐고 놓지 않는 스킬이 생겼다 — §5.1 표 첫째 줄의 종단 시험이 필요하다")
    }

    @Test
    fun `효과와 관측이 맞으면 어긋남이 아니다`() {
        assertNull(HoldEffects.mismatch("pick_place", HoldKind.HOLD_KIND_EMPTY))
    }

    @Test
    fun `관측이 없거나 볼 수 없으면 판정하지 않는다`() {
        // §5.2 — 이 절의 핵심. 효과는 관측이 있을 때만 미결을 줄인다. 선언으로 현실을 단정하면
        // 관측 경로가 죽었을 때 고장난 기체가 멀쩡해 보인다.
        assertNull(HoldEffects.mismatch("pick_place", HoldKind.HOLD_KIND_NOT_OBSERVABLE), "관측 불가를 판정했다")
        assertNull(HoldEffects.mismatch("pick_place", HoldKind.HOLD_KIND_UNSPECIFIED), "침묵을 판정했다")
    }

    @Test
    fun `효과를 선언하지 않은 스킬은 어긋날 것이 없다`() {
        for (skill in listOf("navigate_to", "inspect", "move_relative", "transport")) {
            assertNull(HoldEffects.mismatch(skill, HoldKind.HOLD_KIND_HOLDING), skill)
            assertNull(HoldEffects.mismatch(skill, HoldKind.HOLD_KIND_EMPTY), skill)
        }
    }

}
