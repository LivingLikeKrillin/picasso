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
}
