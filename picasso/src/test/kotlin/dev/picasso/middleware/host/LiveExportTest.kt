package dev.picasso.middleware.host

import dev.picasso.middleware.LedgerExport
import dev.picasso.middleware.RemedyOutcome
import dev.picasso.middleware.RemedySearchRecord
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 창구가 내는 살아 있는 한 벌 — **읽는 쪽이 이것으로 승인을 거른다.**
 *
 * 여기서 지키는 것은 셋이다: 구동 식별자가 한 바퀴에 하나일 것, 대장이 안 늘면 다시 안 쓸 것,
 * 안내가 대는 수가 대장의 줄 수와 같을 것.
 */
class LiveExportTest {

    private fun record(n: Int) = RemedySearchRecord(
        searchId = "search-$n",
        robotId = "hum-02",
        jobOrderId = "PATROL-APPROVES",
        at = Instant.parse("2026-09-06T02:00:00Z").plusSeconds(n.toLong()),
        wallClockAt = Instant.parse("2026-09-22T07:00:00Z").plusSeconds(n.toLong()),
        outcome = RemedyOutcome.Withheld,
    )

    private fun manifest(dir: java.nio.file.Path) = Files.readString(dir.resolve(LedgerExport.MANIFEST))

    private fun runId(body: String) = Regex(""""runId":"([^"]+)"""").find(body)?.groupValues?.get(1)

    @Test
    fun `구동 식별자가 한 바퀴 내내 같다`() {
        // ★★읽는 쪽은 「같은 구동의 같은 줄에는 한 번만 부른다」로 승인을 거른다. 식별자가 내보낼
        //   때마다 바뀌면 **그 거름망이 통째로 죽는다** — 같은 한 벌을 다시 훑을 때마다 또 부른다.
        //   앞 판이 그랬고 30분 돌린 판에서 식별자가 `-10930` 까지 갔다.
        val dir = createTempDirectory("live")
        val live = LiveExport(dir, LedgerExport.newRunId(Instant.now()))

        assertTrue(live.snapshot(emptyList(), listOf(record(1)), NOW), "첫 한 벌을 안 썼다")
        val first = runId(manifest(dir))

        assertTrue(live.snapshot(emptyList(), listOf(record(1), record(2)), NOW), "대장이 늘었는데 안 썼다")
        val second = runId(manifest(dir))

        assertEquals(first, second, "같은 바퀴인데 구동 식별자가 바뀌었다")
        assertTrue(first != null && first.isNotBlank(), "구동 식별자가 비었다: $first")
    }

    @Test
    fun `대장이 안 늘면 다시 안 쓴다`() {
        // 안 늘었는데 다시 쓰면 읽는 쪽이 세 파일을 한 벌로 집을 때 파일마다 다른 순간을 볼 수 있다.
        val dir = createTempDirectory("live")
        val live = LiveExport(dir, LedgerExport.newRunId(Instant.now()))
        val searches = listOf(record(1))

        assertTrue(live.snapshot(emptyList(), searches, NOW), "첫 한 벌을 안 썼다")
        val before = manifest(dir)

        assertFalse(live.snapshot(emptyList(), searches, NOW.plusSeconds(60)), "안 늘었는데 다시 썼다")
        assertEquals(before, manifest(dir), "안 썼다면서 안내가 바뀌었다")

        // ★**늘면 다시 쓴다.** 건너뛰기가 «영영 안 쓴다» 가 되면 창구가 도는데 한 벌이 멈춘다.
        assertTrue(live.snapshot(emptyList(), searches + record(2), NOW), "늘었는데 안 썼다")
    }

    @Test
    fun `안내가 대는 수가 대장의 줄 수와 같다`() {
        // 안내가 마지막에 나타나는 것이 «다 나왔다» 는 신호다. 그 수가 줄 수와 다르면 읽는 쪽은
        // 파편을 한 벌로 읽는다.
        val dir = createTempDirectory("live")
        val live = LiveExport(dir, LedgerExport.newRunId(Instant.now()))
        val searches = listOf(record(1), record(2), record(3))

        live.snapshot(emptyList(), searches, NOW)

        val lines = Files.readString(dir.resolve(LedgerExport.REMEDY_SEARCHES)).trim().lines()
        assertEquals(searches.size, lines.size, "탐색 대장의 줄 수가 다르다")
        assertTrue(""""remedySearches":${searches.size}""" in manifest(dir), "안내가 댄 수가 다르다: ${manifest(dir)}")
        assertEquals("", Files.readString(dir.resolve(LedgerExport.INCIDENTS)), "빈 사건 대장이 안 비었다")
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-06T02:47:57Z")
    }
}
