package dev.picasso.capability

import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
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

    // ── 끝난 시점의 기대와 관측(설계안 §5, 중단 시점 기준)

    @Test
    fun `놓기까지 마쳤으면 기대는 효과대로다`() {
        assertEquals(HoldKind.HOLD_KIND_EMPTY, HoldEffects.expectedAtEnd("pick_place", everHeld = true, completed = true))
    }

    @Test
    fun `쥐었다가 놓지 못한 채 끝났으면 기대는 든 채다`() {
        // 종료 시점의 효과만 보면 «쥐고 실패했는데 빈손» 이 정상으로 읽힌다. 어디까지 갔는지가 기대를 정한다.
        assertEquals(HoldKind.HOLD_KIND_HOLDING, HoldEffects.expectedAtEnd("pick_place", everHeld = true, completed = false))
    }

    @Test
    fun `쥔 적이 없으면 기대할 것이 없다`() {
        assertNull(HoldEffects.expectedAtEnd("pick_place", everHeld = false, completed = false))
    }

    @Test
    fun `효과를 선언하지 않은 스킬은 기대할 것이 없다`() {
        for (skill in listOf("navigate_to", "inspect", "move_relative", "transport")) {
            assertNull(HoldEffects.expectedAtEnd(skill, everHeld = true, completed = true), skill)
            assertNull(HoldEffects.expectedAtEnd(skill, everHeld = true, completed = false), skill)
        }
    }

    @Test
    fun `기대와 관측이 어긋나면 그 모양을 말한다`() {
        assertEquals(HoldMismatch.PAYLOAD_LOST, HoldEffects.compare(HoldKind.HOLD_KIND_HOLDING, HoldKind.HOLD_KIND_EMPTY))
        assertEquals(HoldMismatch.INCOMPLETE_RELEASE, HoldEffects.compare(HoldKind.HOLD_KIND_EMPTY, HoldKind.HOLD_KIND_HOLDING))
        assertNull(HoldEffects.compare(HoldKind.HOLD_KIND_EMPTY, HoldKind.HOLD_KIND_EMPTY))
        assertNull(HoldEffects.compare(HoldKind.HOLD_KIND_HOLDING, HoldKind.HOLD_KIND_HOLDING))
    }

    @Test
    fun `관측이 없거나 볼 수 없으면 판정하지 않는다`() {
        // §5.2 — 이 줄이 뚫리면 나머지 규율이 무의미해진다. 선언을 근거로 현실을 단정하는 것이기 때문이다.
        //
        // ★**열거를 여기 다시 적지 않는다.** 앞 판은 `listOf(NOT_OBSERVABLE, UNSPECIFIED)` 로 돌아,
        //   구체 관측이 아닌 값이 하나 더 생기면 **초록인 채로 그 값을 한 번도 안 봤다**. 분류에서 파생하면
        //   새 값이 저절로 들어오고, 새 값이 구체 관측이면 이 시험이 아니라 `compare` 의 `when` 이 깨진다.
        val notObserved = HoldKind.values().filter { !it.isConcreteObservation && it != HoldKind.UNRECOGNIZED }
        assertTrue(notObserved.isNotEmpty(), "구체 관측이 아닌 값이 하나도 없다 — 이 시험이 빈 목록을 돈다")

        for (observed in notObserved) {
            assertNull(HoldEffects.compare(HoldKind.HOLD_KIND_HOLDING, observed), observed.name)
            assertNull(HoldEffects.compare(HoldKind.HOLD_KIND_EMPTY, observed), observed.name)
            // 기대 쪽이 볼 수 없는 값이어도 같다 — 한쪽만 막으면 반만 막힌다.
            assertNull(HoldEffects.compare(observed, HoldKind.HOLD_KIND_EMPTY), observed.name)
        }
    }

    @Test
    fun `구체 관측은 빈손과 파지 둘뿐이다`() {
        // 방벽이 무엇을 참으로 두고 있는지 고정한다. 부류가 바뀌면 이 줄이 먼저 빨개지고,
        // 그때 «바꾼 것이 맞는가» 를 묻게 된다 — 값이 늘 때의 컴파일 오류와 짝이다.
        assertEquals(
            listOf(HoldKind.HOLD_KIND_EMPTY, HoldKind.HOLD_KIND_HOLDING),
            HoldKind.values().filter { it.isConcreteObservation }.sortedBy { it.name },
        )
    }

}
