package dev.picasso.adapter.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 자리 이름이 **지금 판에서 유효한가**(§15.155).
 *
 * 세 가지를 갈라 답한다 — 옛 판에서 배웠다 · 정본이 모르는 이름이다 · 정본에 못 물어봤다. 운영자가 할 일이
 * 셋 다 다르므로(재등록 · 등록 · 정본 수리) 접으면 무엇을 해야 할지 알 수 없다.
 */
class SiteBindingCheckTest {

    private class Source(
        private val active: ActiveMap,
        private val table: Map<String, SiteBinding> = emptyMap(),
    ) : SiteBindingSource {
        override fun activeMap(): ActiveMap = active
        override fun binding(name: String): SiteBinding? = table[name]
    }

    private fun bound(name: String, version: String) = name to SiteBinding(name, "site-registry", version)

    @Test
    fun `정본을 안 붙였으면 판을 묻지 않는다`() {
        // 0 을 고르는 것도 결정이다 — 정본 없는 배치를 통째로 멈추면 이 검사가 곧 꺼진다.
        assertNull(SiteBindingCheck.refusalFor(listOf("dock-3"), SiteBindingSource.None))
    }

    @Test
    fun `자리 이름을 안 나르면 묻지 않는다`() {
        // 이름이 없으면 판정할 것도 없다. 정본이 죽어 있어도 마찬가지다.
        assertNull(SiteBindingCheck.refusalFor(emptyList(), Source(ActiveMap.Unavailable("정본이 죽었다"))))
    }

    @Test
    fun `활성 판과 같으면 통과한다`() {
        val source = Source(ActiveMap.Known("map-7"), mapOf(bound("dock-3", "map-7")))
        assertNull(SiteBindingCheck.refusalFor(listOf("dock-3"), source))
    }

    @Test
    fun `옛 판에서 배운 자리는 막는다`() {
        val source = Source(ActiveMap.Known("map-8"), mapOf(bound("dock-3", "map-7")))
        assertEquals(Refusal.SITE_BINDING_STALE, SiteBindingCheck.refusalFor(listOf("dock-3"), source))

        val detail = SiteBindingCheck.detailFor(Refusal.SITE_BINDING_STALE, listOf("dock-3"), source)
        assertTrue("map-7" in detail && "map-8" in detail, "두 판을 다 적어야 재등록할 것을 안다: $detail")
    }

    @Test
    fun `정본이 모르는 이름은 통과가 아니다`() {
        // **확인 못 한 것을 통과로 접지 않는다.** 기체가 우연히 풀 수도 있지만 그것은 이 층이 확인한 사실이 아니다.
        val source = Source(ActiveMap.Known("map-7"))
        assertEquals(Refusal.SITE_BINDING_ABSENT, SiteBindingCheck.refusalFor(listOf("dock-3"), source))
    }

    @Test
    fun `정본에 못 물어보면 멈춘다`() {
        // 빈 답을 «판이 같다» 로 접으면 지도가 바뀐 뒤에도 명령이 계속 나가고 로그에는 성공이 남는다.
        val source = Source(ActiveMap.Unavailable("정본 응답 없음"))
        assertEquals(Refusal.SITE_BINDING_UNVERIFIABLE, SiteBindingCheck.refusalFor(listOf("dock-3"), source))
        assertTrue("정본 응답 없음" in SiteBindingCheck.detailFor(Refusal.SITE_BINDING_UNVERIFIABLE, listOf("dock-3"), source))
    }

    @Test
    fun `이름이 여럿이면 하나만 어긋나도 막는다`() {
        // 한 단위가 자리 둘을 나르면(`pick_place` 의 대상과 목적지) 둘 다 이 판의 것이어야 한다.
        val source = Source(ActiveMap.Known("map-7"), mapOf(bound("bin-a", "map-7"), bound("rack-1", "map-6")))
        assertEquals(Refusal.SITE_BINDING_STALE, SiteBindingCheck.refusalFor(listOf("bin-a", "rack-1"), source))
    }

    @Test
    fun `어긋난 자리만 사정에 적는다`() {
        // 멀쩡한 이름까지 적으면 운영자가 어느 것을 재등록해야 하는지 못 고른다.
        val source = Source(ActiveMap.Known("map-7"), mapOf(bound("bin-a", "map-7"), bound("rack-1", "map-6")))
        val detail = SiteBindingCheck.detailFor(Refusal.SITE_BINDING_STALE, listOf("bin-a", "rack-1"), source)
        assertTrue("rack-1" in detail, detail)
        assertTrue("bin-a" !in detail, "멀쩡한 자리가 어긋난 것처럼 실렸다: $detail")
    }
}
