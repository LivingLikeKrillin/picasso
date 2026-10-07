package dev.picasso.harness.revision

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 실행기의 폴링과 보고(picasso-ops P2·S1d 스펙 §5.1·§5.2). **registry 가 어떻게 답하든 실행기가 멈추지 않고, 같은
 * 결과를 두 번 반영하지 않는다.**
 *
 * registry 대신 답을 정해 둔 창구를 쓴다. 실제 registry 와의 왕복은 [RevisionRunnerEndToEndTest] 가 본다.
 */
class RevisionTestRunnerTest {

    private val schema: Path = Path.of("..", "profile", "schema", "capability-profile.schema.json").normalize()
    private val humanoid: String = Files.readString(Path.of("..", "profile", "profiles", "humanoid-a.json").normalize())
    private val logs = mutableListOf<String>()

    private class ScriptedDesk(
        private val claims: ArrayDeque<() -> ClaimedTest?>,
        private val replies: ArrayDeque<() -> ReportReply>,
    ) : TestDesk {
        val reports = mutableListOf<Triple<Long, String, List<SuiteOutcome>>>()

        override fun claim(worker: String): ClaimedTest? = claims.removeFirstOrNull()?.invoke()

        override fun report(requestId: Long, worker: String, claimedAt: String, outcomes: List<SuiteOutcome>): ReportReply {
            reports += Triple(requestId, "$worker@$claimedAt", outcomes)
            return replies.removeFirst().invoke()
        }
    }

    private fun claimed() = ClaimedTest(7, 3, "2026-10-08T00:00:00.123457Z", humanoid)

    private fun runner(desk: TestDesk) = RevisionTestRunner(desk, RevisionSuites(schema), "site-runner") { logs += it }

    @Test
    fun `집은 요청의 시험 셋을 집은 실행기 이름과 집은 시각으로 보고한다`() {
        val desk = ScriptedDesk(ArrayDeque(listOf({ claimed() })), ArrayDeque(listOf({ ReportReply.Recorded("TESTED") })))

        assertTrue(runner(desk).pollOnce())

        val (requestId, claimer, outcomes) = desk.reports.single()
        assertEquals(7, requestId)
        assertEquals("site-runner@2026-10-08T00:00:00.123457Z", claimer)
        assertEquals(listOf("CONTRACT", "NEGATIVE", "DETERMINISM"), outcomes.map { it.suite.name })
        assertTrue(outcomes.all { it.passed }, outcomes.joinToString { it.detailJson() })
    }

    @Test
    fun `집을 것이 없으면 보고하지 않는다`() {
        val desk = ScriptedDesk(ArrayDeque(listOf({ null })), ArrayDeque())

        assertFalse(runner(desk).pollOnce())
        assertTrue(desk.reports.isEmpty())
    }

    @Test
    fun `집기에 실패하면 다음 폴링을 기다린다`() {
        val desk = ScriptedDesk(ArrayDeque(listOf({ throw DeskUnavailable(503, "잠시") }, { throw DeskUnavailable(401, "토큰") })), ArrayDeque())
        val runner = runner(desk)

        assertFalse(runner.pollOnce())
        assertFalse(runner.pollOnce())
        assertTrue(logs.any { "적재 토큰" in it }, "401 을 토큰 설정 오류로 남기지 않았다: $logs")
    }

    @Test
    fun `보고의 응답을 못 받으면 다시 보내고, 다시 보낸 보고가 이미 끝남이면 거기서 멈춘다`() {
        val desk = ScriptedDesk(
            ArrayDeque(listOf({ claimed() })),
            ArrayDeque(listOf({ throw DeskUnavailable(0, "끊김") }, { ReportReply.Completed })),
        )

        runner(desk).pollOnce()

        assertEquals(2, desk.reports.size)
        assertTrue(logs.any { "앞 보고가 반영됐다" in it }, "$logs")
    }

    @Test
    fun `보고를 최대 3번 다시 보내고 포기한다`() {
        val desk = ScriptedDesk(
            ArrayDeque(listOf({ claimed() })),
            ArrayDeque(List(10) { { throw DeskUnavailable(503, "잠시") } }),
        )

        runner(desk).pollOnce()

        assertEquals(1 + RevisionTestRunner.REPORT_RETRIES, desk.reports.size)
        assertTrue(logs.any { "포기" in it }, "$logs")
    }

    @Test
    fun `이 실행기가 집은 것이 아니라는 답에는 다시 보내지 않는다`() {
        val desk = ScriptedDesk(ArrayDeque(listOf({ claimed() })), ArrayDeque(listOf({ ReportReply.NotClaimer })))

        runner(desk).pollOnce()

        assertEquals(1, desk.reports.size)
    }

    @Test
    fun `적재에서 거절되는 문서도 FAIL 셋으로 보고한다`() {
        // 보고하지 않으면 같은 요청이 15분마다 다시 집히고, 화면에는 끝내 FAIL 이 안 보인다(스펙 §5.2).
        val desk = ScriptedDesk(
            ArrayDeque(listOf({ claimed().copy(document = """{"vendor":"x"}""") })),
            ArrayDeque(listOf({ ReportReply.Recorded("VALIDATED") })),
        )

        runner(desk).pollOnce()

        val outcomes = desk.reports.single().third
        assertTrue(outcomes.none { it.passed })
        assertTrue(outcomes.all { it.failures.single().check == "LOAD" })
    }
}
