package dev.picasso.middleware

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **허용 범위는 라이브러리가 쥔다**(운영 관리 화면 설계 제안 §11 결정 2).
 *
 * 칸마다 하한과 상한에서 한 초 안팎을 잰다. 범위 밖이면 문장이 그 칸 이름으로 시작해야 한다. 쓰는 쪽(실행 호스트)이 그 문장을
 * «적용 안 한 버전과 이유» 로 그대로 보이기 때문이다.
 */
class SiteTimingsRangeTest {

    private val ok = SiteTimings.ofSeconds(1, 30, 15, 60, 300)

    /** 한 칸만 바꾼 값의 문제 목록. */
    private fun problems(field: String, seconds: Long): List<String> = when (field) {
        "evidenceWindowBefore" -> ok.copy(evidenceWindowBefore = Duration.ofSeconds(seconds))
        "evidenceWindowAfter" -> ok.copy(evidenceWindowAfter = Duration.ofSeconds(seconds))
        "inDoubtGrace" -> ok.copy(inDoubtGrace = Duration.ofSeconds(seconds))
        "stallWindow" -> ok.copy(stallWindow = Duration.ofSeconds(seconds))
        else -> error(field)
    }.problems()

    private fun assertBounds(field: String, range: LongRange) {
        assertEquals(emptyList(), problems(field, range.first), "$field 하한 ${range.first} 을 막았다")
        assertEquals(emptyList(), problems(field, range.last), "$field 상한 ${range.last} 을 막았다")
        listOf(range.first - 1, range.last + 1).forEach { outside ->
            val found = problems(field, outside)
            assertEquals(1, found.size, "$field = $outside 를 안 잡았다: $found")
            assertTrue(found.single().startsWith("$field:"), found.single())
        }
    }

    @Test
    fun `앞 폭의 하한과 상한 밖을 잡는다`() = assertBounds("evidenceWindowBefore", 5L..120L)

    @Test
    fun `뒤 폭의 하한과 상한 밖을 잡는다`() = assertBounds("evidenceWindowAfter", 5L..120L)

    @Test
    fun `IN_DOUBT 유예의 하한과 상한 밖을 잡는다`() = assertBounds("inDoubtGrace", 10L..600L)

    @Test
    fun `정체 유예의 하한과 상한 밖을 잡는다`() = assertBounds("stallWindow", 30L..3600L)

    @Test
    fun `범위 상수가 시험이 잰 범위와 같다`() {
        // 운영 서비스가 사본을 두고 통합 시험이 이 상수와 대조한다. 상수가 움직이면 그쪽도 움직여야 한다.
        assertEquals(5L..120L, SiteTimings.EVIDENCE_WINDOW_BEFORE_SECONDS)
        assertEquals(5L..120L, SiteTimings.EVIDENCE_WINDOW_AFTER_SECONDS)
        assertEquals(10L..600L, SiteTimings.IN_DOUBT_GRACE_SECONDS)
        assertEquals(30L..3600L, SiteTimings.STALL_WINDOW_SECONDS)
    }

    @Test
    fun `초 단위가 아닌 값과 1 보다 작은 버전을 잡는다`() {
        val fractional = ok.copy(inDoubtGrace = Duration.ofMillis(60_500)).problems()
        assertEquals(1, fractional.size, "$fractional")
        assertTrue(fractional.single().startsWith("inDoubtGrace:"), fractional.single())

        val unversioned = ok.copy(siteSettingsVersion = 0).problems()
        assertEquals(1, unversioned.size, "$unversioned")
        assertTrue(unversioned.single().startsWith("siteSettingsVersion:"), unversioned.single())
    }

    @Test
    fun `케이퍼빌리티 기본값이 허용 범위 안이다`() {
        // 현장 설정의 첫 버전은 이 기본값으로 채운다. 기본값이 범위 밖이면 첫 버전부터 적용되지 않는다.
        MissionCatalog.codeCapabilities().forEach { c ->
            val defaults = SiteTimings(1, c.evidenceWindow.before, c.evidenceWindow.after, c.inDoubtGrace, c.stallWindow)
            assertEquals(emptyList(), defaults.problems(), c.workMasterId)
        }
        assertEquals(emptyList(), ok.problems())
    }
}
