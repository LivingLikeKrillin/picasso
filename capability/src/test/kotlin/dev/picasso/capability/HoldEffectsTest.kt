package dev.picasso.capability

import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
