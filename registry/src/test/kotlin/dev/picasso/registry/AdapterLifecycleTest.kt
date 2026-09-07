package dev.picasso.registry

import dev.picasso.registry.adapter.AdapterService
import dev.picasso.registry.adapter.ConformanceStatus
import dev.picasso.registry.adapter.RegisterOutcome
import dev.picasso.registry.binding.ActivateOutcome
import dev.picasso.registry.binding.BindOutcome
import dev.picasso.registry.binding.BindingService
import dev.picasso.registry.catalog.CatalogService
import dev.picasso.registry.revision.RevisionService
import dev.picasso.registry.revision.SkillTypeSync
import dev.picasso.registry.revision.SubmitOutcome
import dev.picasso.registry.store.Db
import dev.picasso.registry.testing.TestRequestService
import java.time.Duration
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 완료 기준 16 — **§9.7의 여섯 단계를 통과해 새 기종이 카탈로그에 오르고,
 * `conformance_status=UNTESTED`가 표시된다.**
 *
 * ## 여섯 단계를 한 시험에서 걷는다
 *
 * 단계별로 따로 보면 "각 조각이 돈다"만 증명되고 **이어져 있는가**는 안
 * 본다. 운영자가 겪는 것은 조각이 아니라 그 순서다.
 *
 * ④(적합성)는 구현하지 않는다 — 실물 어댑터를 돌리는 것은 C-3이고 비목표다
 * (§9.7이 그렇게 적었다). **상태는 만들고 그 값이 카탈로그에 보이는지 본다.**
 */
class AdapterLifecycleTest {

    private lateinit var db: Db
    private lateinit var revisions: RevisionService
    private lateinit var bindings: BindingService
    private lateinit var adapters: AdapterService
    private lateinit var requests: TestRequestService
    private lateinit var catalog: CatalogService
    private var clock: Instant = Instant.parse("2026-09-07T00:00:00Z")

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        revisions = RevisionService(db, Fixtures.validator())
        bindings = BindingService(db)
        adapters = AdapterService(db)
        requests = TestRequestService(db, now = { clock })
        catalog = CatalogService(db)
        SkillTypeSync(db).sync(Fixtures.descriptor(), CONTRACT_SEMVER, "sync")
    }

    private fun robot(id: String = "r1") = db.transaction { c ->
        c.prepareStatement(
            "INSERT INTO robot (robot_id, site_id, serial_number) VALUES (?, 'line-a', ?)",
        ).use { it.setString(1, id); it.setString(2, "sn-$id"); it.executeUpdate() }
        id
    }

    // ── 여섯 단계

    @Test
    fun `여섯 단계를 걸어 새 기종이 카탈로그에 오른다`() {
        // ① 어댑터 등록
        val adapterId = adapters.registerAdapter("acme", "drv", "op")
        val registered = adapters.registerVersion(adapterId, "1.0.0", CONTRACT_SEMVER, "op")
        assertTrue(registered is RegisterOutcome.Registered, "$registered")

        // ② 프로파일 제출 → 검증
        val stored = revisions.submit(Fixtures.good(), "op")
        assertTrue(stored is SubmitOutcome.Stored, "$stored")
        assertEquals("VALIDATED", statusOf(stored.profileRevisionId), "사유: ${stored.reasons}")

        // ③ 계약 시험 — 레지스트리는 요청을 **적재만** 하고 하네스가 집어간다
        val requestId = requests.request(stored.profileRevisionId, "op")
        val claimed = requests.claim("harness-1")
        assertEquals(requestId, claimed?.requestId, "요청을 못 집어갔다")
        assertTrue(
            claimed!!.documentJson.contains("\"vendor\""),
            "집어간 요청에 문서가 안 실렸다 — 하네스가 무엇으로 mimic을 띄우나",
        )
        BindingService.SUITE_NAMES.forEach {
            bindings.recordTestRun(stored.profileRevisionId, it, "PASS", "harness-1")
        }
        assertEquals("TESTED", statusOf(stored.profileRevisionId))

        // ④ 적합성 — **구현하지 않는다.** 기본값이 UNTESTED로 남는다.
        assertEquals(
            "UNTESTED", conformanceOf(registered.adapterVersionId),
            "적합성을 안 돌렸는데 UNTESTED가 아니다",
        )

        // ⑤ 바인딩
        val activated = bindings.activate(stored.profileRevisionId, "op")
        assertTrue(activated is ActivateOutcome.Activated, "$activated")
        val bound = bindings.bind(robot(), registered.adapterVersionId, stored.profileRevisionId, "op")
        assertTrue(bound is BindOutcome.Bound, "$bound")

        // ⑥ 카탈로그 반영
        val entry = catalog.entries().single()
        assertEquals("r1", entry.robotId)
        assertEquals("fixture", entry.vendor)
        assertEquals("minimal", entry.model)
        assertEquals(1, entry.profileRevision)
        assertEquals("1.0.0", entry.adapterVersion)
        assertEquals(
            "UNTESTED", entry.conformanceStatus,
            "**시험 안 된 어댑터가 조용히 돈다** — 운영자가 그 사실을 모른다",
        )
        assertEquals(listOf("navigate_to", "pick_place"), entry.skills)
    }

    @Test
    fun `바인딩 전에는 카탈로그가 비어 있다`() {
        // 위 시험의 전제. 언제나 뭔가 들어 있으면 "올랐다"가 아무 뜻도 없다.
        adapters.registerAdapter("acme", "drv", "op")
        revisions.submit(Fixtures.good(), "op")
        assertEquals(emptyList(), catalog.entries(), "바인딩 전인데 카탈로그에 있다")
    }

    @Test
    fun `적합성 결과를 기입하면 카탈로그가 따라온다`() {
        val entry = walkToBinding()
        adapters.recordConformance(entry, ConformanceStatus.PASSED, "qa")
        assertEquals("PASSED", catalog.entries().single().conformanceStatus)
    }

    @Test
    fun `해제하면 카탈로그에서 빠진다`() {
        // **활성 바인딩만 본다.** 이력을 섞으면 "지금 무엇으로 도는가"에
        // 답이 여럿이 된다.
        walkToBinding()
        db.transaction { c ->
            c.createStatement().use { it.execute("UPDATE robot_binding SET unbound_at = now()") }
        }
        assertEquals(emptyList(), catalog.entries(), "해제된 바인딩이 카탈로그에 남았다")
    }

    @Test
    fun `사이트로 좁힐 수 있다`() {
        walkToBinding()
        assertEquals(1, catalog.entries("line-a").size)
        assertEquals(emptyList(), catalog.entries("line-z"), "다른 사이트의 것이 섞였다")
    }

    // ── 어댑터 등록

    @Test
    fun `계약 semver가 형식이 아니면 등록을 거부한다`() {
        // 바인딩까지 미루면 **아무 기체에도 못 붙는 어댑터 버전**이 남고,
        // 운영자는 바인딩을 시도해야 그것을 안다.
        val adapterId = adapters.registerAdapter("acme", "drv", "op")
        val refused = adapters.registerVersion(adapterId, "1.0.0", "not-a-semver", "op")
        assertTrue(refused is RegisterOutcome.Rejected, "$refused")
        assertEquals(
            0,
            PostgresSupport.queryOne("SELECT count(*) FROM adapter_version") { it.getInt(1) },
            "거부해 놓고 저장했다",
        )
    }

    @Test
    fun `같은 버전을 두 번 등록하면 거부한다`() {
        val adapterId = adapters.registerAdapter("acme", "drv", "op")
        assertTrue(adapters.registerVersion(adapterId, "1.0.0", CONTRACT_SEMVER, "op") is RegisterOutcome.Registered)
        assertTrue(adapters.registerVersion(adapterId, "1.0.0", CONTRACT_SEMVER, "op") is RegisterOutcome.Rejected)
    }

    // ── 시험 요청의 클레임

    @Test
    fun `집어간 요청은 다른 하네스가 못 집는다`() {
        val stored = revisions.submit(Fixtures.good(), "op") as SubmitOutcome.Stored
        requests.request(stored.profileRevisionId, "op")

        assertTrue(requests.claim("harness-1") != null)
        assertNull(requests.claim("harness-2"), "이미 잡힌 것을 둘째가 집었다")
    }

    @Test
    fun `클레임이 만료되면 다른 하네스가 집는다`() {
        // **만료가 없으면 하네스가 죽었을 때 요청이 영구히 잡힌다** — 그
        // 개정판은 영영 TESTED가 못 되고 활성화도 못 한다.
        val stored = revisions.submit(Fixtures.good(), "op") as SubmitOutcome.Stored
        val requestId = requests.request(stored.profileRevisionId, "op")

        requests.claim("harness-1")
        assertEquals("harness-1", requests.claimedBy(requestId))

        clock = clock.plus(Duration.ofMinutes(16))
        assertEquals(requestId, requests.claim("harness-2")?.requestId, "만료됐는데 못 집는다")
        assertEquals("harness-2", requests.claimedBy(requestId))
    }

    @Test
    fun `만료 전에는 안 넘어간다`() {
        // 위 시험의 짝. 언제나 넘어가면 클레임이 아무 뜻도 없다.
        val stored = revisions.submit(Fixtures.good(), "op") as SubmitOutcome.Stored
        requests.request(stored.profileRevisionId, "op")
        requests.claim("harness-1")

        clock = clock.plus(Duration.ofMinutes(14))
        assertNull(requests.claim("harness-2"), "만료 전인데 넘어갔다")
    }

    @Test
    fun `요청이 없으면 아무것도 안 집는다`() {
        assertNull(requests.claim("harness-1"))
    }

    // ── 준비물

    private fun walkToBinding(): Long {
        val adapterId = adapters.registerAdapter("acme", "drv", "op")
        val version = adapters.registerVersion(adapterId, "1.0.0", CONTRACT_SEMVER, "op")
            as RegisterOutcome.Registered
        val stored = revisions.submit(Fixtures.good(), "op") as SubmitOutcome.Stored
        BindingService.SUITE_NAMES.forEach {
            bindings.recordTestRun(stored.profileRevisionId, it, "PASS", "harness")
        }
        bindings.activate(stored.profileRevisionId, "op")
        bindings.bind(robot(), version.adapterVersionId, stored.profileRevisionId, "op")
        return version.adapterVersionId
    }

    private fun statusOf(id: Long): String = PostgresSupport.queryOne(
        "SELECT status FROM profile_revision WHERE profile_revision_id = $id",
    ) { it.getString(1) }

    private fun conformanceOf(id: Long): String = PostgresSupport.queryOne(
        "SELECT conformance_status FROM adapter_version WHERE adapter_version_id = $id",
    ) { it.getString(1) }

    private companion object {
        val CONTRACT_SEMVER: String = dev.picasso.contracts.wire.ContractIdentity.semver
    }
}
