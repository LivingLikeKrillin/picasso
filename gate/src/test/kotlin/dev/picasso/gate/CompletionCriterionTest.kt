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
}
