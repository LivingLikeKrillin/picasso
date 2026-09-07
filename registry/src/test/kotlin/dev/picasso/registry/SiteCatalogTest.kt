package dev.picasso.registry

import dev.picasso.registry.adapter.AdapterService
import dev.picasso.registry.adapter.RegisterOutcome
import dev.picasso.registry.binding.ActivateOutcome
import dev.picasso.registry.binding.BindOutcome
import dev.picasso.registry.binding.BindingService
import dev.picasso.registry.catalog.SiteCapability
import dev.picasso.registry.catalog.SiteCatalog
import dev.picasso.registry.revision.RevisionService
import dev.picasso.registry.revision.SkillTypeSync
import dev.picasso.registry.revision.SubmitOutcome
import dev.picasso.registry.store.Db
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §9.6의 사이트 카탈로그 — **기체가 아니라 능력이 단위다.**
 *
 * 상위 시스템이 이것으로 라우팅을 정한다. 그래서 여기서 확인하는 것은
 * 집계가 맞는지가 아니라 **흔들리지 않는지**다: 세 대 중 한 대가 빠졌을 때
 * 능력이 사라지면 상위는 없는 장애에 반응하고, 그 반응은 라인을 세운다.
 */
class SiteCatalogTest {

    private lateinit var db: Db
    private lateinit var revisions: RevisionService
    private lateinit var bindings: BindingService
    private lateinit var catalog: SiteCatalog
    private var adapterVersionId: Long = 0

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        revisions = RevisionService(db, Fixtures.validator())
        bindings = BindingService(db)
        catalog = SiteCatalog(db)
        SkillTypeSync(db).sync(Fixtures.descriptor(), CONTRACT_SEMVER, "sync")

        listOf("r1" to "line-a", "r2" to "line-a", "r3" to "line-b").forEach { (id, site) ->
            PostgresSupport.execute(
                "INSERT INTO robot (robot_id, site_id, serial_number) " +
                    "VALUES ('$id','$site','sn-$id')",
            )
        }
        val adapters = AdapterService(db)
        val adapterId = adapters.registerAdapter("acme", "drv", "op")
        adapterVersionId = (
            adapters.registerVersion(adapterId, "1.0.0", CONTRACT_SEMVER, "op")
                as RegisterOutcome.Registered
            ).adapterVersionId
    }

    @Test
    fun `능력 단위로 집계한다`() {
        val rev = activate()
        bind("r1", rev)
        bind("r2", rev)

        val capabilities = catalog.capabilities("line-a")

        assertEquals(
            listOf("navigate_to@1", "pick_place@1"),
            capabilities.map { "${it.skillType}@${it.major}" },
            "상위는 '3번 로봇'이 아니라 '이 공장에서 되는가'를 본다",
        )
        assertEquals(listOf(2, 2), capabilities.map { it.availableRobots })
    }

    @Test
    fun `한 대가 빠져도 능력은 남는다`() {
        val rev = activate()
        bind("r1", rev)
        bind("r2", rev)

        unbind("r2")

        val pickPlace = pickPlace("line-a")
        assertEquals(1, pickPlace?.availableRobots, "한 대가 빠졌다고 능력이 사라지면 안 된다")
    }

    @Test
    fun `마지막 한 대가 빠지면 능력이 사라진다`() {
        // 반대쪽이 없으면 "언제나 남긴다"가 앞 시험을 통과한다.
        val rev = activate()
        bind("r1", rev)

        unbind("r1")

        assertEquals(emptyList(), catalog.capabilities("line-a"))
    }

    @Test
    fun `다른 사이트의 기체는 안 센다`() {
        val rev = activate()
        bind("r1", rev)
        bind("r3", rev)

        assertEquals(1, pickPlace("line-a")?.availableRobots)
        assertEquals(1, pickPlace("line-b")?.availableRobots)
    }

    @Test
    fun `해제된 바인딩은 이력이라 안 센다`() {
        val rev = activate()
        bind("r1", rev)
        unbind("r1")
        bind("r1", rev)

        assertEquals(1, pickPlace("line-a")?.availableRobots, "이력까지 세면 한 대가 두 대가 된다")
    }

    // ── 폐기 예고: 두 축

    @Test
    fun `모든 제공 기체가 예고했을 때만 프로파일 축 값이 있다`() {
        val rev = activate()
        bind("r1", rev)
        bind("r2", rev)
        announceProfile(rev, days = 30)

        assertEquals(
            true,
            pickPlace("line-a")?.deprecatedAfter?.isAfter(java.time.Instant.now()),
            "예고가 안 실렸다",
        )
    }

    @Test
    fun `한 대라도 예고 없이 제공하면 폐기 예고가 없다`() {
        // 이른 쪽을 취하면 **아직 되는 능력을 안 된다고 알린다.**
        val announced = activate()
        val other = activate(model = "second")
        bind("r1", announced)
        bind("r2", other)
        announceProfile(announced, days = 30)

        assertNull(pickPlace("line-a")?.deprecatedAfter)
    }

    @Test
    fun `제공 기체마다 예고가 다르면 가장 늦은 것이 실린다`() {
        // **사이트가 능력을 잃는 시점은 마지막 제공자가 접는 날이다.**
        // 이른 쪽을 취하면 아직 두 대 중 한 대가 하는 일을 안 된다고 알린다.
        val early = activate()
        val late = activate(model = "second")
        bind("r1", early)
        bind("r2", late)
        announceProfile(early, days = 3)
        announceProfile(late, days = 30)

        val after = requireNotNull(pickPlace("line-a")?.deprecatedAfter)

        assertTrue(
            after.isAfter(java.time.Instant.now().plus(java.time.Duration.ofDays(20))),
            "가장 늦은 예고(30일)가 아니라 이른 것이 실렸다: $after",
        )
    }

    @Test
    fun `계약 축과 프로파일 축 중 이른 쪽이 실린다`() {
        val rev = activate()
        bind("r1", rev)
        announceProfile(rev, days = 30)
        announceContract(days = 7)

        val after = requireNotNull(pickPlace("line-a")?.deprecatedAfter)

        assertTrue(
            after.isBefore(java.time.Instant.now().plus(java.time.Duration.ofDays(8))),
            "계약 축(7일)이 프로파일 축(30일)보다 이른데 안 실렸다: $after",
        )
    }

    @Test
    fun `계약 축만 있어도 실린다`() {
        // 계약 축은 사이트와 무관하게 전부에 걸린다.
        val rev = activate()
        bind("r1", rev)
        announceContract(days = 7)

        assertTrue(pickPlace("line-a")?.deprecatedAfter != null)
    }

    @Test
    fun `프로파일 축이 더 이르면 그쪽이 실린다`() {
        val rev = activate()
        bind("r1", rev)
        announceProfile(rev, days = 3)
        announceContract(days = 90)

        val after = requireNotNull(pickPlace("line-a")?.deprecatedAfter)

        assertTrue(
            after.isBefore(java.time.Instant.now().plus(java.time.Duration.ofDays(4))),
            "이른 쪽이 프로파일 축인데 계약 축이 실렸다: $after",
        )
    }

    // ── 씨앗

    private fun activate(model: String? = null): Long {
        val stored = revisions.submit(Fixtures.good(model = model), "op")
        assertTrue(stored is SubmitOutcome.Stored, "$stored")
        BindingService.SUITE_NAMES.forEach {
            bindings.recordTestRun(stored.profileRevisionId, it, "PASS", "harness")
        }
        assertTrue(bindings.activate(stored.profileRevisionId, "op") is ActivateOutcome.Activated)
        return stored.profileRevisionId
    }

    private fun bind(robot: String, revisionId: Long) {
        val outcome = bindings.bind(robot, adapterVersionId, revisionId, "op")
        assertTrue(outcome is BindOutcome.Bound, "$outcome")
    }

    private fun unbind(robot: String) = PostgresSupport.execute(
        "UPDATE robot_binding SET unbound_at = now() " +
            "WHERE robot_id = '$robot' AND unbound_at IS NULL",
    )

    private fun announceProfile(revisionId: Long, days: Int) = PostgresSupport.execute(
        "UPDATE profile_skill SET deprecated_after = now() + interval '$days days' " +
            "WHERE profile_revision_id = $revisionId AND skill_type_id = " +
            "(SELECT skill_type_id FROM skill_type WHERE name = 'pick_place' AND major = 1)",
    )

    private fun announceContract(days: Int) = PostgresSupport.execute(
        "INSERT INTO skill_type_deprecation (skill_type_id, deprecated_after, announced_by) " +
            "SELECT skill_type_id, now() + interval '$days days', 'contract-owner' " +
            "FROM skill_type WHERE name = 'pick_place' AND major = 1",
    )

    private fun pickPlace(site: String): SiteCapability? =
        catalog.capabilities(site).firstOrNull { it.skillType == "pick_place" }

    private companion object {
        val CONTRACT_SEMVER: String = dev.picasso.contracts.wire.ContractIdentity.semver
    }
}
