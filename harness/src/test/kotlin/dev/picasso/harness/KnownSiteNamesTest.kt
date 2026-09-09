package dev.picasso.harness

import dev.picasso.mimic.control.v1.SetKnownSiteNamesRequest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR 35 의 확인 질의를 **클라이언트가 든다.** 계약에는 `GetKnownSiteNames` 가 있었고(§15.68) 어댑터 셋이 답을
 * 들었는데(§15.73) 이 저장소의 소비자(`client`)에는 그 호출이 없었다 — §15.87 이 미결로 적은 그것이다.
 *
 * 셋을 가른다: 아는 이름이 있다 / 하나도 등록 안 됐다(빈 목록, 사실이다) / 이름을 둘 자리가 없다(`unsupported`).
 * 그리고 `protocol_limits.max_array_length` 에 잘렸는지를 `total_count` 로 안다 — 세지 않으면 큰 지도가 조용히 잘린다.
 */
class KnownSiteNamesTest {

    private val profile = Path.of("..", "profile", "fixtures", "minimal.json").normalize()

    private fun Harness.setKnown(vararg names: String, unsupported: Boolean = false) = oracle.setKnownSiteNames(
        SetKnownSiteNamesRequest.newBuilder().setRobotId(ROBOT).addAllKnown(names.toList()).setUnsupported(unsupported).build(),
    )

    @Test
    fun `아는 이름을 답한다 — 전체 개수와 함께`() {
        Harness(mapOf(ROBOT to profile)).use { harness ->
            harness.setKnown("RACK-204.S01", "SEQ-IN-02.BIN-A")
            val answer = harness.client().knownSiteNames(ROBOT)
            assertEquals(listOf("RACK-204.S01", "SEQ-IN-02.BIN-A"), answer.namesList.sorted())
            assertEquals(2, answer.totalCount)
            assertFalse(answer.unsupported)
        }
    }

    @Test
    fun `하나도 등록 안 된 것은 사실이고, 둘 자리가 없는 것은 다른 답이다`() {
        Harness(mapOf(ROBOT to profile)).use { harness ->
            harness.setKnown()
            val none = harness.client().knownSiteNames(ROBOT)
            assertTrue(none.namesList.isEmpty())
            assertFalse(none.unsupported, "0개와 못 함을 접었다")

            harness.setKnown(unsupported = true)
            val cannot = harness.client().knownSiteNames(ROBOT)
            assertTrue(cannot.unsupported)
            assertTrue(cannot.namesList.isEmpty())
        }
    }

    @Test
    fun `목록이 한도에 잘리면 전체 개수가 그것을 말한다`() {
        Harness(mapOf(ROBOT to profile)).use { harness ->
            val many = (1..40).map { "WP-%02d".format(it) }
            harness.setKnown(*many.toTypedArray())
            val answer = harness.client().knownSiteNames(ROBOT)
            assertEquals(40, answer.totalCount)
            assertTrue(answer.namesList.size <= 40)
            if (answer.namesList.size < 40) {
                assertTrue(answer.totalCount > answer.namesList.size, "잘렸는데 전체 개수가 그것을 안 말한다")
            }
        }
    }

    private companion object {
        const val ROBOT = "hum-02"
    }
}
