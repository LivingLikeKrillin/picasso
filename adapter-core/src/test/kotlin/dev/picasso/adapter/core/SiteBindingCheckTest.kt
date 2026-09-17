package dev.picasso.adapter.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 자리 이름이 **지금 판에서 유효한가**(§15.155).
 *
 * 축이 둘이다 — 사이트 지도와 기체 개체의 캘리브레이션. 지도가 그대로여도 개체가 바뀌면 같은 이름이
 * 다른 자세를 뜻하므로, 지도만 보면 그 경우가 통과한다.
 *
 * 거절은 넷으로 가른다 — 옛 지도 · 옛 캘리브레이션 · 정본이 모르는 이름 · 못 물어봤다. 운영자가 할 일이
 * 넷 다 다르므로(지도 재촬영 · 재티칭 · 등록 · 정본 수리) 접으면 무엇을 해야 할지 알 수 없다.
 */
class SiteBindingCheckTest {

    private class Source(
        private val map: ActiveRevision = ActiveRevision.NotConfigured,
        private val calibration: ActiveRevision = ActiveRevision.NotConfigured,
        private val table: Map<String, SiteBinding> = emptyMap(),
    ) : SiteBindingSource {
        override fun activeMap(): ActiveRevision = map
        override fun activeCalibration(): ActiveRevision = calibration
        override fun binding(name: String): SiteBinding? = table[name]
    }

    private fun bound(name: String, map: String, calibration: String = "cal-1") =
        name to SiteBinding(name, "site-registry", map, calibration)

    // ── 검사하지 않는 경우

    @Test
    fun `정본을 안 붙였으면 판을 묻지 않는다`() {
        // 0 을 고르는 것도 결정이다 — 정본 없는 배치를 통째로 멈추면 이 검사가 곧 꺼진다.
        assertNull(SiteBindingCheck.refusalFor(listOf("dock-3"), SiteBindingSource.None))
    }

    @Test
    fun `자리 이름을 안 나르면 묻지 않는다`() {
        // 이름이 없으면 판정할 것도 없다. 정본이 죽어 있어도 마찬가지다.
        assertNull(SiteBindingCheck.refusalFor(emptyList(), Source(map = ActiveRevision.Unavailable("정본이 죽었다"))))
    }

    @Test
    fun `축 하나만 붙은 정본은 그 축만 본다`() {
        // 캘리브레이션 판을 내놓는 정본과 지도 판을 내놓는 정본이 같으리라는 보장이 없다. 지도만 아는
        // 배치에서 캘리브레이션 축은 «없다» 이고 그것이 정직한 답이다.
        val source = Source(map = ActiveRevision.Known("map-7"), table = mapOf(bound("dock-3", "map-7", "cal-9")))
        assertNull(SiteBindingCheck.refusalFor(listOf("dock-3"), source))
    }

    // ── 지도 축

    @Test
    fun `활성 판과 같으면 통과한다`() {
        val source = Source(ActiveRevision.Known("map-7"), ActiveRevision.Known("cal-1"), mapOf(bound("dock-3", "map-7")))
        assertNull(SiteBindingCheck.refusalFor(listOf("dock-3"), source))
    }

    @Test
    fun `옛 지도 판에서 배운 자리는 막는다`() {
        val source = Source(map = ActiveRevision.Known("map-8"), table = mapOf(bound("dock-3", "map-7")))
        assertEquals(Refusal.SITE_BINDING_STALE_MAP, SiteBindingCheck.refusalFor(listOf("dock-3"), source))

        val detail = SiteBindingCheck.detailFor(Refusal.SITE_BINDING_STALE_MAP, listOf("dock-3"), source)
        assertTrue("map-7" in detail && "map-8" in detail, "두 판을 다 적어야 재등록할 것을 안다: $detail")
    }

    // ── 캘리브레이션 축

    @Test
    fun `지도가 같아도 개체가 바뀌면 막는다`() {
        // **이 축이 없으면 통과하던 경우다.** 기체를 교체하고 세계 모델을 복원해도 티칭 기준이 달라
        // 같은 이름이 다른 자세를 뜻한다.
        val source = Source(
            map = ActiveRevision.Known("map-7"),
            calibration = ActiveRevision.Known("cal-2"),
            table = mapOf(bound("dock-3", "map-7", "cal-1")),
        )
        assertEquals(Refusal.SITE_BINDING_STALE_CALIBRATION, SiteBindingCheck.refusalFor(listOf("dock-3"), source))
    }

    @Test
    fun `어느 축이 어긋났는지 사정이 가른다`() {
        // 접으면 운영자가 지도를 다시 찍을지 개체를 다시 티칭할지 고를 수 없다.
        val source = Source(
            map = ActiveRevision.Known("map-7"),
            calibration = ActiveRevision.Known("cal-2"),
            table = mapOf(bound("dock-3", "map-7", "cal-1")),
        )
        val detail = SiteBindingCheck.detailFor(Refusal.SITE_BINDING_STALE_CALIBRATION, listOf("dock-3"), source)
        assertTrue("캘리브레이션" in detail, detail)
        assertTrue("지도" !in detail, "멀쩡한 축이 어긋난 것처럼 실렸다: $detail")
    }

    @Test
    fun `둘 다 어긋나면 지도를 먼저 내고 사정에는 둘 다 적는다`() {
        // 지도를 다시 찍으면 티칭도 따라 하게 되므로 상류부터 고치게 한다. 다만 캘리브레이션이 어긋난
        // 사실을 감추면 지도만 고치고 같은 거절을 한 번 더 받는다.
        val source = Source(
            map = ActiveRevision.Known("map-8"),
            calibration = ActiveRevision.Known("cal-2"),
            table = mapOf(bound("dock-3", "map-7", "cal-1")),
        )
        assertEquals(Refusal.SITE_BINDING_STALE_MAP, SiteBindingCheck.refusalFor(listOf("dock-3"), source))

        val detail = SiteBindingCheck.detailFor(Refusal.SITE_BINDING_STALE_MAP, listOf("dock-3"), source)
        assertTrue("지도" in detail && "캘리브레이션" in detail, "어긋난 축 하나가 사정에서 빠졌다: $detail")
    }

    @Test
    fun `캘리브레이션 정본에 못 물어봐도 멈춘다`() {
        // 축이 늘어도 «모르면 멈춘다» 는 그대로다.
        val source = Source(
            map = ActiveRevision.Known("map-7"),
            calibration = ActiveRevision.Unavailable("개체 판 조회 실패"),
            table = mapOf(bound("dock-3", "map-7")),
        )
        assertEquals(Refusal.SITE_BINDING_UNVERIFIABLE, SiteBindingCheck.refusalFor(listOf("dock-3"), source))
        assertTrue(
            "캘리브레이션" in SiteBindingCheck.detailFor(Refusal.SITE_BINDING_UNVERIFIABLE, listOf("dock-3"), source),
            "어느 축을 못 물어봤는지 적어야 정본의 어디를 고칠지 안다",
        )
    }

    // ── 이름과 정본

    @Test
    fun `정본이 모르는 이름은 통과가 아니다`() {
        // **확인 못 한 것을 통과로 접지 않는다.** 기체가 우연히 풀 수도 있으나 이 층이 확인한 사실이 아니다.
        val source = Source(map = ActiveRevision.Known("map-7"))
        assertEquals(Refusal.SITE_BINDING_ABSENT, SiteBindingCheck.refusalFor(listOf("dock-3"), source))
    }

    @Test
    fun `정본에 못 물어보면 멈춘다`() {
        // 빈 답을 «판이 같다» 로 접으면 지도가 바뀐 뒤에도 명령이 계속 나가고 로그에는 성공이 남는다.
        val source = Source(map = ActiveRevision.Unavailable("정본 응답 없음"))
        assertEquals(Refusal.SITE_BINDING_UNVERIFIABLE, SiteBindingCheck.refusalFor(listOf("dock-3"), source))
        assertTrue("정본 응답 없음" in SiteBindingCheck.detailFor(Refusal.SITE_BINDING_UNVERIFIABLE, listOf("dock-3"), source))
    }

    @Test
    fun `이름이 여럿이면 하나만 어긋나도 막는다`() {
        // 한 단위가 자리 둘을 나르면(`pick_place` 의 대상과 목적지) 둘 다 이 판의 것이어야 한다.
        val source = Source(
            map = ActiveRevision.Known("map-7"),
            table = mapOf(bound("bin-a", "map-7"), bound("rack-1", "map-6")),
        )
        assertEquals(Refusal.SITE_BINDING_STALE_MAP, SiteBindingCheck.refusalFor(listOf("bin-a", "rack-1"), source))
    }

    @Test
    fun `어긋난 자리만 사정에 적는다`() {
        // 멀쩡한 이름까지 적으면 운영자가 어느 것을 재등록해야 하는지 못 고른다.
        val source = Source(
            map = ActiveRevision.Known("map-7"),
            table = mapOf(bound("bin-a", "map-7"), bound("rack-1", "map-6")),
        )
        val detail = SiteBindingCheck.detailFor(Refusal.SITE_BINDING_STALE_MAP, listOf("bin-a", "rack-1"), source)
        assertTrue("rack-1" in detail, detail)
        assertTrue("bin-a" !in detail, "멀쩡한 자리가 어긋난 것처럼 실렸다: $detail")
    }
}
