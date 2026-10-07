package dev.picasso.registry

import dev.picasso.registry.adapter.AdapterService
import dev.picasso.registry.adapter.RegisterOutcome
import dev.picasso.registry.binding.Activation
import dev.picasso.registry.binding.Binding
import dev.picasso.registry.binding.BindingService
import dev.picasso.registry.binding.SiteNameRegistration
import dev.picasso.registry.binding.SiteNameStatus
import dev.picasso.registry.revision.BootSync
import dev.picasso.registry.revision.RevisionListing
import dev.picasso.registry.revision.RevisionService
import dev.picasso.registry.revision.RevisionStatus
import dev.picasso.registry.revision.SkillTypeCatalog
import dev.picasso.registry.revision.Submitted
import dev.picasso.registry.store.Db
import dev.picasso.registry.testing.TestRequestService
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 개정판·바인딩의 조작 문이 쓰는 결과 타입(picasso-ops P2·S1d 스펙 §6.2·§6.3). **재전송은 같은 답이고, 거절은 이유로
 * 갈린다.**
 *
 * 제출·활성화·바인딩이 같은 요청의 재전송에 멱등인지, 바인딩이 없는 기체·퇴역 기체·같은 조합을 막는지, 동시 요청이 500
 * 대신 차례로 처리되는지 본다. 카탈로그 기동 동기화도 여기서 본다.
 */
class RevisionOperationsTest {

    private lateinit var db: Db
    private lateinit var revisions: RevisionService
    private lateinit var bindings: BindingService
    private var build: Long = 0

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        SkillTypeCatalog(db).syncAtBoot(Fixtures.descriptor(), CONTRACT_SEMVER)
        revisions = RevisionService(db, Fixtures.validator())
        bindings = BindingService(db)
        build = version(CONTRACT_SEMVER)
    }

    private fun version(contractSemver: String, number: String = "1.0.0"): Long {
        val adapters = AdapterService(db)
        val adapterId = adapters.registerAdapter("acme", "drv-$number", "op")
        val v = adapters.registerVersion(adapterId, number, contractSemver, "op")
        assertIs<RegisterOutcome.Registered>(v)
        return v.adapterVersionId
    }

    private fun robot(id: String) =
        PostgresSupport.execute("INSERT INTO robot (robot_id, site_id, serial_number) VALUES ('$id','line-a','sn-$id')")

    private fun created(document: String = Fixtures.good()): Long =
        assertIs<Submitted.Created>(revisions.submitDocument(document, "engineer/kim")).profileRevisionId

    private fun passed(revision: Long) =
        BindingService.SUITE_NAMES.forEach { bindings.recordTestRun(revision, it, "PASS", "harness") }

    private fun active(document: String = Fixtures.good()): Long {
        val revision = created(document)
        passed(revision)
        assertIs<Activation.Activated>(bindings.activateRevision(revision, "engineer/kim"))
        return revision
    }

    private fun auditCount(operation: String): Int =
        PostgresSupport.queryOne("SELECT count(*) FROM audit_log WHERE operation = '$operation'") { it.getInt(1) }

    // ── 카탈로그

    @Test
    fun `기동 동기화 뒤 스킬 종류가 계약과 같고 감사 행위자는 registry 다`() {
        val names = SkillTypeCatalog(db).list().map { it.name }.toSet()
        val contract = dev.picasso.gate.model.ContractIndex.from(Fixtures.descriptor()).skillTypes().toSet()

        assertEquals(contract, names)
        assertEquals(
            listOf("registry"),
            PostgresSupport.queryAll("SELECT actor FROM audit_log WHERE operation = 'SKILL_TYPE_SYNC'") { it.getString(1) },
        )
        val keys = SkillTypeCatalog(db).list().associate { it.name to it.siteReferenceKeys }
        assertEquals(listOf("location"), keys["navigate_to"])
        assertEquals(listOf("destination"), keys["pick_place"])
    }

    @Test
    fun `기술자를 못 읽으면 기동을 거부하고 스키마가 없으면 건너뛴다`() {
        assertFailsWith<IllegalStateException> { SkillTypeCatalog(db).syncAtBoot(null, CONTRACT_SEMVER) }

        PostgresSupport.execute("DROP TABLE skill_type CASCADE")
        assertEquals(BootSync.NoSchema, SkillTypeCatalog(db).syncAtBoot(Fixtures.descriptor(), CONTRACT_SEMVER))
    }

    // ── 제출

    @Test
    fun `같은 문서의 재제출은 같은 개정판이고 감사는 한 번이다`() {
        val first = revisions.submitDocument(Fixtures.good(), "engineer/kim")
        val again = revisions.submitDocument(Fixtures.good(), "engineer/kim")

        assertIs<Submitted.Created>(first)
        assertEquals(RevisionStatus.VALIDATED, first.status, "${first.reasons}")
        assertEquals(Submitted.Existing(first.profileRevisionId, 1, RevisionStatus.VALIDATED, emptyList()), again)
        assertEquals(1, auditCount("PROFILE_REVISION_SUBMIT"))
    }

    @Test
    fun `같은 번호의 다른 문서와 낮은 번호는 단조 위반이고 읽을 수 없는 문서는 따로 갈린다`() {
        created(Fixtures.good(revision = 2))

        assertEquals(Submitted.NotMonotonic(2, 2), revisions.submitDocument(Fixtures.good(revision = 2).replace("\"seconds\": 20", "\"seconds\": 21"), "op"))
        assertEquals(Submitted.NotMonotonic(1, 2), revisions.submitDocument(Fixtures.good(revision = 1), "op"))
        assertIs<Submitted.Unreadable>(revisions.submitDocument("{not json", "op"))
    }

    @Test
    fun `검증에 실패한 문서도 저장되고 DRAFT 와 사유를 돌려준다`() {
        val outcome = assertIs<Submitted.Created>(revisions.submitDocument(Fixtures.badErrorType(), "op"))

        assertEquals(RevisionStatus.DRAFT, outcome.status)
        assertTrue(outcome.reasons.isNotEmpty())
        assertEquals(
            Submitted.Existing(outcome.profileRevisionId, 1, RevisionStatus.DRAFT, outcome.reasons),
            revisions.submitDocument(Fixtures.badErrorType(), "op"),
            "재제출이 사유를 잃었다",
        )
    }

    /**
     * 기종이 **이미 있는** 때를 본다. 기종의 첫 제출이면 기종 행의 `INSERT … ON CONFLICT DO NOTHING` 이 유일 색인에서
     * 둘째를 기다리게 해 잠금 없이도 차례가 지켜진다 — 그 경우만 보면 잠금을 지워도 초록이다(결함 주입으로 확인).
     */
    @Test
    fun `같은 문서가 동시에 두 번 와도 하나는 만들고 하나는 그것을 돌려준다`() {
        created(Fixtures.good(revision = 1))

        val outcomes = concurrently(2) { revisions.submitDocument(Fixtures.good(revision = 2), "op") }

        assertEquals(1, outcomes.count { it is Submitted.Created }, "$outcomes")
        assertEquals(1, outcomes.count { it is Submitted.Existing }, "$outcomes")
    }

    // ── 활성화

    @Test
    fun `활성화는 옛 활성을 내리고 다시 누르면 아무것도 바꾸지 않는다`() {
        val old = active()
        val next = created(Fixtures.good(revision = 2))
        passed(next)

        assertEquals(Activation.Activated(old), bindings.activateRevision(next, "op"))
        assertEquals(Activation.AlreadyActive, bindings.activateRevision(next, "op"))
        assertEquals(2, auditCount("PROFILE_REVISION_ACTIVATE"))
    }

    @Test
    fun `활성화 거절은 상태와 스위트별 최신 결과를 싣고 없는 개정판은 따로다`() {
        val revision = created()
        bindings.recordTestRun(revision, "CONTRACT", "PASS", "harness")
        bindings.recordTestRun(revision, "NEGATIVE", "FAIL", "harness")

        assertEquals(
            Activation.Refused(RevisionStatus.VALIDATED, mapOf("CONTRACT" to "PASS", "NEGATIVE" to "FAIL")),
            bindings.activateRevision(revision, "op"),
        )
        assertEquals(Activation.Unknown, bindings.activateRevision(999_999, "op"))
    }

    // ── 바인딩

    @Test
    fun `없는 기체·퇴역 기체·없는 개정판·없는 빌드를 그 순서로 가른다`() {
        val revision = active()
        assertEquals(Binding.UnknownRobot, bindings.bindRobot("ghost", 999_999, 999_999, "op"))

        robot("r1")
        PostgresSupport.execute("UPDATE robot SET retired_at = now(), retired_by = 'op', retired_reason = 'sold' WHERE robot_id = 'r1'")
        assertEquals(Binding.RobotRetired, bindings.bindRobot("r1", 999_999, 999_999, "op"))

        robot("r2")
        assertEquals(Binding.UnknownRevision, bindings.bindRobot("r2", 999_999, 999_999, "op"))
        assertEquals(Binding.UnknownBuild, bindings.bindRobot("r2", 999_999, revision, "op"))
    }

    @Test
    fun `활성이 아닌 개정판과 낮은 계약의 빌드를 거절한다`() {
        robot("r1")
        val validated = created()
        assertEquals(Binding.RevisionNotActive(RevisionStatus.VALIDATED), bindings.bindRobot("r1", build, validated, "op"))

        passed(validated)
        bindings.activateRevision(validated, "op")
        val old = version("0.0.1", number = "0.0.1")
        val refused = assertIs<Binding.ContractTooOld>(bindings.bindRobot("r1", old, validated, "op"))
        assertEquals("0.0.1", refused.contractSemver)
        assertTrue(refused.tooNew.isNotEmpty())
    }

    @Test
    fun `같은 조합을 다시 묶으면 같은 행이고 사이트 명칭 기록이 남는다`() {
        robot("r1")
        val revision = active()
        val first = assertIs<Binding.Bound>(bindings.bindRobot("r1", build, revision, "op"))
        SiteNameRegistration(db).record("r1", "engineer/kim")

        assertEquals(Binding.AlreadyBound(first.bindingId), bindings.bindRobot("r1", build, revision, "op"))
        assertTrue(SiteNameRegistration(db).statusOf("r1") != SiteNameStatus.UNREGISTERED, "재바인딩이 명칭 기록을 지웠다")
        assertEquals(1, auditCount("ROBOT_BIND"))
    }

    @Test
    fun `대체된 개정판의 같은 조합은 이미 됨이 아니라 활성 아님이다`() {
        robot("r1")
        val old = active()
        bindings.bindRobot("r1", build, old, "op")
        val next = created(Fixtures.good(revision = 2))
        passed(next)
        bindings.activateRevision(next, "op")

        assertEquals(Binding.RevisionNotActive(RevisionStatus.SUPERSEDED), bindings.bindRobot("r1", build, old, "op"))
        val moved = assertIs<Binding.Bound>(bindings.bindRobot("r1", build, next, "op"))
        assertTrue(moved.unbound != null, "옛 바인딩을 풀지 않았다")
    }

    @Test
    fun `같은 기체의 동시 첫 바인딩은 하나만 남고 500 이 나지 않는다`() {
        robot("r1")
        val revision = active()

        val outcomes = concurrently(2) { bindings.bindRobot("r1", build, revision, "op") }

        assertEquals(1, outcomes.count { it is Binding.Bound }, "$outcomes")
        assertEquals(1, outcomes.count { it is Binding.AlreadyBound }, "$outcomes")
        assertEquals(1, PostgresSupport.queryOne("SELECT count(*) FROM robot_binding WHERE unbound_at IS NULL") { it.getInt(1) })
    }

    // ── 목록

    @Test
    fun `목록은 스위트별 최신 결과와 최신 시험 요청을 함께 싣는다`() {
        val revision = created()
        bindings.recordTestRun(revision, "CONTRACT", "FAIL", "harness")
        bindings.recordTestRun(revision, "CONTRACT", "PASS", "harness")
        val requestId = TestRequestService(db).request(revision, "engineer/kim")

        val row = RevisionListing(db).list().single()

        assertEquals("fixture" to "minimal", row.vendor to row.model)
        assertEquals(RevisionStatus.VALIDATED, row.status)
        assertEquals(mapOf("CONTRACT" to "PASS"), row.suites.mapValues { it.value.result })
        assertEquals(requestId, row.latestRequest?.requestId)
        assertNull(row.latestRequest?.completedAt)
        assertEquals("engineer/kim", row.createdBy)
    }

    private fun <T> concurrently(n: Int, body: () -> T): List<T> {
        val pool = Executors.newFixedThreadPool(n)
        try {
            val barrier = CyclicBarrier(n)
            return (1..n).map { pool.submit(Callable { barrier.await(); body() }) }.map { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        val CONTRACT_SEMVER: String = dev.picasso.contracts.wire.ContractIdentity.semver
    }
}
