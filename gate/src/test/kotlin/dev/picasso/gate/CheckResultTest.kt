package dev.picasso.gate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CheckResultTest {

    @Test
    fun `건너뛴 결과는 무엇이 없어서 건너뛰었는지 담는다`() {
        val skipped = CheckResult.Skipped("4", setOf(Resource.CONTRACT_DESCRIPTOR), "디스크립터가 없다")
        assertEquals(setOf(Resource.CONTRACT_DESCRIPTOR), skipped.missing)
        assertTrue(skipped.reason.isNotBlank())
    }

    @Test
    fun `실패에는 오류 소견이 하나 이상 있어야 한다`() {
        assertFailsWith<IllegalArgumentException> {
            CheckResult.Failed("3", emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            // 경고만으로는 실패가 아니다.
            CheckResult.Failed("3", listOf(Finding("3", Severity.WARNING, "음")))
        }
    }

    @Test
    fun `통과도 경고를 담을 수 있다`() {
        val passed = CheckResult.Passed(
            checkId = "6",
            findings = listOf(Finding("6", Severity.WARNING, "축소로 분류됨")),
        )
        assertEquals(1, passed.findings.size)
    }

    @Test
    fun `통과에 오류 소견을 담을 수 없다`() {
        assertFailsWith<IllegalArgumentException> {
            CheckResult.Passed("6", listOf(Finding("6", Severity.ERROR, "나쁨")))
        }
    }

    @Test
    fun `부분 건너뜀을 표현한다`() {
        // 검사 6번: 분류는 돌았고 원장 조회만 못 했다.
        val passed = CheckResult.Passed("6", skippedParts = setOf(Resource.REGISTRY))
        assertEquals(setOf(Resource.REGISTRY), passed.skippedParts)
    }
}
