package dev.picasso.gate

import kotlin.test.Test
import kotlin.test.assertEquals

class CompletionCriterionTest {

    @Test
    fun `주장의 자리가 마흔일곱이다`() {
        // **세어서 적은 것을 다시 센다.** 자리가 늘거나 줄면 이 수가 먼저 빨개지고,
        // 그때 스펙 §2 를 다시 읽어야 한다.
        val docs = ClaimSurface.documents()
        assertEquals(47, docs.size, docs.joinToString("\n") { ClaimSurface.relative(it) })
    }

    @Test
    fun `도장이 있는 문서는 해시가 본문과 같다`() {
        val stale = ClaimSurface.documents().mapNotNull { doc ->
            val stamp = ClaimSurface.stamp(doc) ?: return@mapNotNull null
            val actual = ClaimSurface.expectedSha(doc)
            if (stamp.sha == actual) null
            else ClaimSurface.relative(doc) + " — 도장 " + stamp.sha + " · 본문 " + actual
        }
        assertEquals(
            emptyList(), stale,
            "본문이 도장 뒤에 바뀌었다. 주장이 바뀌었는지 보고 도장을 갱신하라: python tools/stamp.py <파일>",
        )
    }

    @Test
    fun `한계 대장의 id 가 유일하다`() {
        // ★**id 를 두 줄이 나눠 쓰면 그것을 댄 도장이 어느 한계를 가리키는지 알 수 없다.**
        // 실측(2026-09-10): §15.106 이 두 줄이었다.
        // ★칸을 뽑는 규칙을 여기서 다시 짜지 않는다 — `LimitsLedger.rows` 하나를 쓴다(§15.115).
        val cells = LimitsLedger.rows(Repo.read("docs/limits.md")).map { it.second }
        val dupes = cells.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.toList()
        assertEquals(emptyList(), dupes, "한 id 를 두 줄이 쓴다 — 도장이 어느 줄을 가리키는지 알 수 없다")
    }

    @Test
    fun `도장이 든 열림 id 가 한계 대장에 실재한다`() {
        val known = LimitsLedger.allIds()
        val ghosts = ClaimSurface.documents().flatMap { doc ->
            (ClaimSurface.stamp(doc)?.openIds ?: emptyList())
                .filterNot { it in known }
                .map { ClaimSurface.relative(doc) + " → " + it }
        }
        assertEquals(emptyList(), ghosts, "limits.md 에 없는 id 를 열림 근거로 댔다 — 대지 못하면 열림이 아니다")
    }

    @Test
    fun `열림 표기 수가 한계 대장이 적은 수와 같다`() {
        // ★**강등의 값.** 받쳐진 주장을 열림으로 내리면 이 수가 늘고, 늘리려면 limits.md 를 고쳐야 한다.
        // 그 편집이 diff 에 보이는 것까지가 기계의 몫이다(스펙 §4).
        val counted = ClaimSurface.documents().sumOf { ClaimSurface.stamp(it)?.openIds?.size ?: 0 }
        val claimed = Regex("""밖을 향한 문서가 드는 열림은 \*\*(\d+)\*\* 개다""")
            .find(Repo.read("docs/limits.md"))?.groupValues?.get(1)?.toInt()
            ?: error("limits.md 가 열림 표기 수를 안 적었다")
        assertEquals(claimed, counted, "도장이 든 열림 표기가 limits.md 가 적은 수와 다르다")
    }
}
