package dev.picasso.registry

import dev.picasso.registry.adapter.AdapterService
import dev.picasso.registry.adapter.RegisterOutcome
import dev.picasso.registry.binding.ActivateOutcome
import dev.picasso.registry.binding.BindOutcome
import dev.picasso.registry.binding.BindingService
import dev.picasso.registry.binding.SiteNameRegistration
import dev.picasso.registry.binding.SiteNameStatus
import dev.picasso.registry.diag.DiagnosticsService
import dev.picasso.registry.revision.RevisionService
import dev.picasso.registry.revision.SkillTypeSync
import dev.picasso.registry.revision.SubmitOutcome
import dev.picasso.registry.store.Db
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **§15.68을 닫는 시험** — 바인딩된 기체가 사이트의 이름들을 아는가(ADR 35).
 *
 * ## 이 표가 왜 없었나
 *
 * ADR 35의 결정 3(`registry`가 그 사실을 상태로 갖는다)이 한나절 열려 있었다.
 * 미룬 이유가 *"무엇을 등록했다고 적을 것인가"* 였다 — 기종마다 집합이 다르므로
 * (Spot은 장소, Digit은 장소와 물체와 놓을 곳, G1은 없음) 불리언 하나로는
 * *"등록했다"* 가 기종마다 다른 것을 뜻하게 된다.
 *
 * **답은 유도였다.** 계약이 `is_site_reference`로 어느 파라미터가 사이트
 * 이름인지 말하고, 프로파일이 어느 스킬을 드는지 말한다. 둘을 곱하면 그
 * 바인딩이 알아야 하는 이름들이 나오고, **손으로 적는 목록이 없다.**
 *
 * 그래서 이 파일에서 가장 중요한 시험이
 * [`등록 대상이 프로파일을 따라 바뀐다`]이다 — 목록이 어디에도 안 적혀 있다는
 * 것을 그것이 붙든다.
 */
class SiteNameRegistrationTest {

    private lateinit var db: Db
    private lateinit var bindings: BindingService
    private lateinit var siteNames: SiteNameRegistration
    private var adapterVersionId: Long = 0
    private val clock: Instant = Instant.parse("2026-09-08T09:00:00Z")

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        bindings = BindingService(db)
        siteNames = SiteNameRegistration(db) { clock }
        SkillTypeSync(db).sync(Fixtures.descriptor(), CONTRACT_SEMVER, "sync")

        val adapters = AdapterService(db)
        val adapterId = adapters.registerAdapter("acme", "drv", "op")
        val version = adapters.registerVersion(adapterId, "1.0.0", CONTRACT_SEMVER, "op")
        assertTrue(version is RegisterOutcome.Registered, "$version")
        adapterVersionId = version.adapterVersionId
    }

    private fun activate(document: String): Long {
        val stored = RevisionService(db, Fixtures.validator()).submit(document, "op")
        assertTrue(stored is SubmitOutcome.Stored, "$stored")
        BindingService.SUITE_NAMES.forEach { bindings.recordTestRun(stored.profileRevisionId, it, "PASS", "harness") }
        assertTrue(bindings.activate(stored.profileRevisionId, "op") is ActivateOutcome.Activated)
        return stored.profileRevisionId
    }

    private fun bound(id: String, revisionId: Long) {
        PostgresSupport.execute(
            "INSERT INTO robot (robot_id, site_id, serial_number) VALUES ('$id','line-a','sn-$id')",
        )
        assertTrue(bindings.bind(id, adapterVersionId, revisionId, "op") is BindOutcome.Bound)
    }

    // ── 유도 (이 파일의 핵심)

    @Test
    fun `등록 대상이 프로파일을 따라 바뀐다`() {
        // **목록이 어디에도 안 적혀 있다는 것을 붙든다.**
        //
        // 픽스처는 `navigate_to`(location)와 `pick_place`(object_id·destination)
        // 를 든다. 스킬 하나를 빼면 등록 대상이 저절로 줄어야 하며, 그 사이에
        // 사람이 고치는 목록이 없어야 한다.
        bound("r-both", activate(Fixtures.good()))
        assertEquals(setOf("destination", "location", "object_id"), siteNames.required("r-both"))

        bound("r-fewer", activate(Fixtures.shrunk(revision = 2, skill = "navigate_to")))
        assertEquals(
            setOf("destination", "object_id"),
            siteNames.required("r-fewer"),
            "`navigate_to` 를 뺐는데 `location` 이 남았다 — 목록이 어딘가에 박혀 있다",
        )
    }

    @Test
    fun `시맨틱 스킬을 안 드는 기종은 등록할 것이 없다`() {
        // `move_relative` 만 드는 기종(실물로는 G1)이다. `NOT_REQUIRED` 와
        // `UNREGISTERED` 를 접으면 이런 기체가 영원히 "안 했다" 로 보이고,
        // 그러면 화면이 언제나 빨개서 아무도 안 본다 — §15.47이 `liveness` 를
        // 다섯으로 나눈 것과 같은 이유다.
        bound("r-move", activate(Fixtures.moveOnly()))

        assertEquals(emptySet(), siteNames.required("r-move"))
        assertEquals(SiteNameStatus.NOT_REQUIRED, siteNames.statusOf("r-move"))
    }

    @Test
    fun `계약이 시맨틱이 아니라고 한 파라미터는 안 센다`() {
        // 위 시험이 "STRING 이면 전부" 로 통과하는 것을 막는다. `pick_place` 의
        // `verify_grasp`(BOOL)·`grip_force`(NUMBER)는 물론이고 **`navigate_to`
        // 가 드는 것 중에도 사이트 이름이 아닌 것이 있으면 안 세야 한다.**
        bound("r-both", activate(Fixtures.good()))

        val required = siteNames.required("r-both")
        assertFalse("verify_grasp" in required)
        assertFalse("grip_force" in required)
        assertFalse("duration" in required)
    }

    // ── 상태

    @Test
    fun `기본이 미등록이고 그것이 요점이다`() {
        // §9.7 ④의 `UNTESTED`, §15.47의 `NEVER` 와 같은 판단이다 —
        // *"우리는 아직 안 했다가 화면에 보여야 정직하다."*
        bound("r-both", activate(Fixtures.good()))
        assertEquals(SiteNameStatus.UNREGISTERED, siteNames.statusOf("r-both"))
    }

    @Test
    fun `등록했다고 기록하면 상태가 바뀐다`() {
        bound("r-both", activate(Fixtures.good()))

        assertTrue(siteNames.record("r-both", "op"))
        assertEquals(SiteNameStatus.REGISTERED, siteNames.statusOf("r-both"))
    }

    @Test
    fun `등록할 것이 없는데 등록했다고 적지 못한다`() {
        // 적히면 그 기록이 나중에 "이 기종은 이름을 안다" 로 읽힌다.
        bound("r-move", activate(Fixtures.moveOnly()))

        assertFalse(siteNames.record("r-move", "op"))
        assertEquals(SiteNameStatus.NOT_REQUIRED, siteNames.statusOf("r-move"))
    }

    @Test
    fun `바인딩이 없으면 기록되지 않는다`() {
        PostgresSupport.execute(
            "INSERT INTO robot (robot_id, site_id, serial_number) VALUES ('r-none','line-a','sn-none')",
        )
        assertFalse(siteNames.record("r-none", "op"))
    }

    // ── 재바인딩 (주입이 처음에 못 잡은 자리)

    @Test
    fun `재바인딩하면 옛 프로파일의 이름이 안 남는다`() {
        // **주입 둘이 처음에 안 잡혔고 원인은 시험이었다** — 해제된 바인딩이
        // 있는 상황을 한 번도 안 만들었으므로 `unbound_at IS NULL` 을 지워도
        // 아무것도 안 바뀌었다.
        //
        // 카나리(§9.7 ⑤)가 정확히 이 모양이다. 이력이 남는 것이 의도인데
        // (진단 1번이 "어느 개정판이었는가"에 답해야 한다) 유도가 그 이력까지
        // 세면 **이미 안 쓰는 프로파일의 이름을 등록하라고 요구한다.**
        val first = activate(Fixtures.good())
        bound("r-rebound", first)
        assertEquals(setOf("destination", "location", "object_id"), siteNames.required("r-rebound"))

        // 개정판 2는 `navigate_to` 를 뺀다. 바인딩하면 앞의 것이 자동으로
        // 해제되고 이력으로 남는다.
        val second = activate(Fixtures.shrunk(revision = 2, skill = "navigate_to"))
        assertTrue(bindings.bind("r-rebound", adapterVersionId, second, "op") is BindOutcome.Bound)

        assertEquals(
            setOf("destination", "object_id"),
            siteNames.required("r-rebound"),
            "해제된 바인딩의 이름이 남았다 — 안 쓰는 프로파일의 등록을 요구하게 된다",
        )
    }

    @Test
    fun `기록이 활성 바인딩에만 붙는다`() {
        // 이력 행까지 표시되면 나중에 그 행을 보고 "그때도 등록돼 있었다" 로
        // 읽는다. 그것은 감사 단서가 아니라 지어낸 사실이다.
        val first = activate(Fixtures.good())
        bound("r-rebound", first)
        val second = activate(Fixtures.shrunk(revision = 2, skill = "navigate_to"))
        assertTrue(bindings.bind("r-rebound", adapterVersionId, second, "op") is BindOutcome.Bound)

        assertTrue(siteNames.record("r-rebound", "op"))

        val marked = db.transaction { c ->
            c.prepareStatement(
                "SELECT count(*) FROM robot_binding " +
                    "WHERE robot_id = 'r-rebound' AND site_names_registered_at IS NOT NULL",
            ).use { st -> st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 } }
        }
        assertEquals(1, marked, "기록이 이력 행에도 붙었다")
    }

    // ── 진단 1번에 보인다

    @Test
    fun `진단이 상태와 대상을 함께 낸다`() {
        // **막지 않고 보이게 한다**(ADR 35의 결정 3). 상태만 내고 대상을 안
        // 내면 운영자가 *무엇을* 등록해야 하는지 모르고, 그러면 이 표시가
        // 화면의 빨간 점 하나로만 남는다.
        bound("r-both", activate(Fixtures.good()))

        val row = DiagnosticsService(db).bindings().rows.single { it.robotId == "r-both" }
        assertEquals("UNREGISTERED", row.siteNames)
        assertEquals(listOf("destination", "location", "object_id"), row.siteNameKeys)
    }

    private companion object {
        val CONTRACT_SEMVER: String = dev.picasso.contracts.wire.ContractIdentity.semver
    }
}
