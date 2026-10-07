package dev.picasso.registry

import dev.picasso.registry.revision.RevisionService
import dev.picasso.registry.revision.SkillTypeSync
import dev.picasso.registry.revision.SubmitOutcome
import dev.picasso.registry.store.Db
import dev.picasso.registry.testing.ReportOutcome
import dev.picasso.registry.testing.SuiteRun
import dev.picasso.registry.testing.TestRequestService
import dev.picasso.registry.testing.TestRequested
import java.time.Duration
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 시험 요청의 요청·집기·보고(picasso-ops P2·S1d 스펙 §5.1). **실행기가 돌린 결과만, 그 실행기가 집은 요청에만 붙는다.**
 *
 * 요청이 멱등이고, 끝난 요청이 다시 집히지 않고, 보고가 실행 3행과 «끝남» 과 승격을 한 번에 남기는지 본다.
 * 시계는 시험이 쥔다 — 만료를 실시간으로 기다리면 시험이 15분 걸린다.
 */
class RevisionTestRequestTest {

    private lateinit var db: Db
    private lateinit var requests: TestRequestService
    private var clock: Instant = Instant.parse("2026-10-08T00:00:00Z")

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        requests = TestRequestService(db, now = { clock })
        SkillTypeSync(db).sync(Fixtures.descriptor(), CONTRACT_SEMVER, "sync")
    }

    private fun validated(): Long {
        val stored = RevisionService(db, Fixtures.validator()).submit(Fixtures.good(), "op") as SubmitOutcome.Stored
        assertEquals("VALIDATED", statusOf(stored.profileRevisionId), "사유: ${stored.reasons}")
        return stored.profileRevisionId
    }

    private fun runs(contract: String = "PASS", negative: String = "PASS", determinism: String = "PASS") = listOf(
        SuiteRun("CONTRACT", contract, """{"checks":3,"failures":[]}"""),
        SuiteRun("NEGATIVE", negative, """{"checks":5,"failures":[]}"""),
        SuiteRun("DETERMINISM", determinism, """{"checks":2,"failures":[]}"""),
    )

    @Test
    fun `요청은 멱등이고 감사는 처음 한 번만 남는다`() {
        val revision = validated()

        val first = requests.requestTest(revision, "engineer/kim")
        val second = requests.requestTest(revision, "engineer/kim")

        assertIs<TestRequested.Created>(first)
        assertEquals(TestRequested.Existing(first.requestId), second)
        assertEquals(1, auditCount("TEST_REQUEST"), "멱등 응답이 일어나지 않은 요청을 감사에 남겼다")
    }

    @Test
    fun `없는 개정판과 DRAFT·REVOKED 는 시험을 요청할 수 없다`() {
        assertEquals(TestRequested.UnknownRevision, requests.requestTest(999_999, "op"))

        val revision = validated()
        listOf("DRAFT", "REVOKED").forEach { status ->
            PostgresSupport.execute("UPDATE profile_revision SET status = '$status' WHERE profile_revision_id = $revision")
            assertEquals(TestRequested.NotTestable(status), requests.requestTest(revision, "op"))
        }
    }

    @Test
    fun `셋 다 PASS 면 TESTED 이고 실행 3행에 요청 id 와 상세가 남는다`() {
        val revision = validated()
        val requestId = (requests.requestTest(revision, "op") as TestRequested.Created).requestId
        val claimed = requests.claim("site-runner")!!

        assertEquals(ReportOutcome.Recorded("TESTED"), requests.report(requestId, "site-runner", claimed.claimedAt, runs()))

        val rows = PostgresSupport.queryAll(
            "SELECT suite, request_id, ran_by, detail->>'checks' FROM revision_test_run " +
                "WHERE profile_revision_id = $revision ORDER BY suite",
        ) { "${it.getString(1)}/${it.getLong(2)}/${it.getString(3)}/${it.getString(4)}" }
        assertEquals(
            listOf("CONTRACT/$requestId/site-runner/3", "DETERMINISM/$requestId/site-runner/2", "NEGATIVE/$requestId/site-runner/5"),
            rows,
        )
    }

    @Test
    fun `하나라도 FAIL 이면 VALIDATED 에 머문다`() {
        val revision = validated()
        val requestId = (requests.requestTest(revision, "op") as TestRequested.Created).requestId
        val claimed = requests.claim("site-runner")!!

        assertEquals(
            ReportOutcome.Recorded("VALIDATED"),
            requests.report(requestId, "site-runner", claimed.claimedAt, runs(negative = "FAIL")),
        )
    }

    @Test
    fun `끝난 요청은 만료가 지나도 다시 집히지 않는다`() {
        val revision = validated()
        val requestId = (requests.requestTest(revision, "op") as TestRequested.Created).requestId
        val claimed = requests.claim("site-runner")!!
        requests.report(requestId, "site-runner", claimed.claimedAt, runs())

        clock = clock.plus(Duration.ofMinutes(16))
        assertNull(requests.claim("site-runner"), "끝난 요청이 다시 집혔다 — 같은 개정판을 끝없이 다시 돈다")
    }

    @Test
    fun `끝난 뒤의 요청은 새 요청이다`() {
        val revision = validated()
        val first = (requests.requestTest(revision, "op") as TestRequested.Created).requestId
        requests.report(first, "site-runner", requests.claim("site-runner")!!.claimedAt, runs())

        val second = requests.requestTest(revision, "op")
        assertIs<TestRequested.Created>(second)
        assertTrue(second.requestId != first, "끝난 요청을 열린 요청으로 돌려줬다")
    }

    @Test
    fun `다른 실행기의 보고는 받지 않는다`() {
        val revision = validated()
        val requestId = (requests.requestTest(revision, "op") as TestRequested.Created).requestId
        val claimed = requests.claim("site-runner")!!

        assertEquals(ReportOutcome.NotClaimer, requests.report(requestId, "other-runner", claimed.claimedAt, runs()))
    }

    @Test
    fun `같은 이름으로 다시 집은 요청에 옛 집은 시각의 보고는 받지 않는다`() {
        // 실행기 이름은 같은 이름으로 다시 뜰 수 있다. 이름만 보면 죽은 실행기의 늦은 보고가 새 실행 결과로 섞인다.
        val revision = validated()
        val requestId = (requests.requestTest(revision, "op") as TestRequested.Created).requestId
        val stale = requests.claim("site-runner")!!

        clock = clock.plus(Duration.ofMinutes(16))
        val fresh = requests.claim("site-runner")!!

        assertEquals(ReportOutcome.NotClaimer, requests.report(requestId, "site-runner", stale.claimedAt, runs()))
        assertEquals(ReportOutcome.Recorded("TESTED"), requests.report(requestId, "site-runner", fresh.claimedAt, runs()))
    }

    @Test
    fun `만료가 지났어도 다시 집히기 전이면 보고를 받는다`() {
        val revision = validated()
        val requestId = (requests.requestTest(revision, "op") as TestRequested.Created).requestId
        val claimed = requests.claim("site-runner")!!

        clock = clock.plus(Duration.ofMinutes(16))
        assertEquals(ReportOutcome.Recorded("TESTED"), requests.report(requestId, "site-runner", claimed.claimedAt, runs()))
    }

    @Test
    fun `이미 끝난 요청과 없는 요청`() {
        val revision = validated()
        val requestId = (requests.requestTest(revision, "op") as TestRequested.Created).requestId
        val claimed = requests.claim("site-runner")!!
        requests.report(requestId, "site-runner", claimed.claimedAt, runs())

        assertEquals(ReportOutcome.AlreadyCompleted, requests.report(requestId, "site-runner", claimed.claimedAt, runs()))
        assertEquals(ReportOutcome.UnknownRequest, requests.report(999_999, "site-runner", claimed.claimedAt, runs()))
    }

    @Test
    fun `스위트가 빠지거나 겹치거나 모르는 결과면 받지 않는다`() {
        val revision = validated()
        val requestId = (requests.requestTest(revision, "op") as TestRequested.Created).requestId
        val at = requests.claim("site-runner")!!.claimedAt

        assertIs<ReportOutcome.BadResults>(requests.report(requestId, "site-runner", at, runs().drop(1)))
        assertIs<ReportOutcome.BadResults>(requests.report(requestId, "site-runner", at, runs() + runs().first()))
        assertIs<ReportOutcome.BadResults>(requests.report(requestId, "site-runner", at, runs(contract = "SKIPPED")))
        assertEquals(0, PostgresSupport.queryOne("SELECT count(*) FROM revision_test_run") { it.getInt(1) })
    }

    @Test
    fun `집은 시각은 DB 에 적힌 값 그대로다`() {
        // 메모리의 시각(나노초)을 내주면 DB(마이크로초)와 어긋나 모든 보고가 거절된다.
        clock = Instant.parse("2026-10-08T00:00:00.123456789Z")
        val revision = validated()
        val requestId = (requests.requestTest(revision, "op") as TestRequested.Created).requestId
        val claimed = requests.claim("site-runner")!!

        assertEquals(Instant.parse("2026-10-08T00:00:00.123457Z"), claimed.claimedAt)
        assertEquals(ReportOutcome.Recorded("TESTED"), requests.report(requestId, "site-runner", claimed.claimedAt, runs()))
    }

    private fun statusOf(id: Long): String = PostgresSupport.queryOne(
        "SELECT status FROM profile_revision WHERE profile_revision_id = $id",
    ) { it.getString(1) }

    private fun auditCount(operation: String): Int = PostgresSupport.queryOne(
        "SELECT count(*) FROM audit_log WHERE operation = '$operation'",
    ) { it.getInt(1) }

    private companion object {
        val CONTRACT_SEMVER: String = dev.picasso.contracts.wire.ContractIdentity.semver
    }
}
