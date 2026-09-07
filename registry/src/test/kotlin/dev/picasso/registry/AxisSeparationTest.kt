package dev.picasso.registry

import dev.picasso.registry.binding.ActivateOutcome
import dev.picasso.registry.binding.BindOutcome
import dev.picasso.registry.binding.BindingService
import dev.picasso.registry.revision.RevisionService
import dev.picasso.registry.revision.SkillTypeSync
import dev.picasso.registry.revision.SubmitOutcome
import dev.picasso.registry.store.Db
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 완료 기준 17 — **어댑터 버전만 올린 바인딩 전환에서 프로파일 개정판이
 * 그대로임이 관측된다.** 계약 semver 불만족 조합은 바인딩이 거부된다.
 *
 * ## 나눴다는 것은 한쪽만 움직일 때만 관측된다
 *
 * §9.1이 네 축(계약·프로파일·어댑터·바인딩)을 나눈 것이 이 프로젝트의 주장
 * 중 하나다. 그런데 **어댑터 버전을 올렸는데 프로파일 개정판이 따라 바뀌면
 * 축이 하나인 것이고, 표만 넷으로 그린 것이다.**
 *
 * 그래서 중심 시험은 **같은 시나리오 안에서 한 축만 움직이고 나머지를 읽는
 * 것**이다.
 */
class AxisSeparationTest {

    private lateinit var db: Db
    private lateinit var revisions: RevisionService
    private lateinit var bindings: BindingService

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        revisions = RevisionService(db, Fixtures.validator())
        bindings = BindingService(db)
        SkillTypeSync(db).sync(Fixtures.descriptor(), CONTRACT_SEMVER, "sync")
    }

    // ── 준비물

    private fun submitActive(revision: Int): Long {
        val stored = revisions.submit(Fixtures.good(revision = revision), "op")
        assertTrue(stored is SubmitOutcome.Stored, "$stored")
        BindingService.SUITE_NAMES.forEach {
            bindings.recordTestRun(stored.profileRevisionId, it, "PASS", "harness")
        }
        val activated = bindings.activate(stored.profileRevisionId, "op")
        assertTrue(activated is ActivateOutcome.Activated, "$activated")
        return stored.profileRevisionId
    }

    private fun adapterVersion(version: String, semver: String = CONTRACT_SEMVER): Long =
        db.transaction { c ->
            c.createStatement().use {
                it.execute(
                    "INSERT INTO adapter (vendor, name) VALUES ('acme', 'drv') " +
                        "ON CONFLICT (vendor, name) DO NOTHING",
                )
            }
            c.prepareStatement(
                "INSERT INTO adapter_version " +
                    "(adapter_id, version, contract_semver, registered_by) " +
                    "SELECT adapter_id, ?, ?, 'op' FROM adapter WHERE vendor = 'acme' " +
                    "RETURNING adapter_version_id",
            ).use { s ->
                s.setString(1, version); s.setString(2, semver)
                s.executeQuery().use { rs -> check(rs.next()); rs.getLong(1) }
            }
        }

    private fun robot(id: String = "r1"): String = db.transaction { c ->
        c.prepareStatement(
            "INSERT INTO robot (robot_id, site_id, serial_number) VALUES (?, 'line-a', ?)",
        ).use { it.setString(1, id); it.setString(2, "sn-$id"); it.executeUpdate() }
        id
    }

    // ── 축이 따로 논다

    @Test
    fun `어댑터 버전만 올리면 개정판이 그대로다`() {
        // **완료 기준 17의 중심이다.**
        val revision = submitActive(1)
        val v1 = adapterVersion("1.0.0")
        val v2 = adapterVersion("2.0.0")
        val robot = robot()

        bindings.bind(robot, v1, revision, "op")
        val before = bindings.activeBinding(robot)!!

        val rebound = bindings.bind(robot, v2, revision, "op", reason = "어댑터 버전만 올린다")
        assertTrue(rebound is BindOutcome.Bound, "$rebound")

        val after = bindings.activeBinding(robot)!!
        assertNotEquals(before.second, after.second, "어댑터 버전이 안 바뀌었다 — 전제가 무너졌다")
        assertEquals(
            before.third, after.third,
            "어댑터 버전만 올렸는데 프로파일 개정판이 따라 바뀌었다 — 축이 하나다",
        )
    }

    @Test
    fun `개정판만 올리면 어댑터 버전이 그대로다`() {
        // 반대 방향도 본다. 한 방향만 보면 "언제나 앞의 값을 유지한다"가
        // 통과한다.
        // **순서가 중요하다.** 개정판 2를 활성화하면 1이 `SUPERSEDED`가
        // 되므로(§8.4 ③), 1로 바인딩하는 것은 그 **전에** 해야 한다. 그리고
        // 그 바인딩은 승격 뒤에도 살아 있어야 한다 — 활성화는 바인딩을
        // 건드리지 않는다(§8.2).
        val r1 = submitActive(1)
        val v1 = adapterVersion("1.0.0")
        val robot = robot()
        bindings.bind(robot, v1, r1, "op")
        val before = bindings.activeBinding(robot)!!

        val r2 = submitActive(2)
        assertEquals(
            before, bindings.activeBinding(robot),
            "활성화가 바인딩을 건드렸다 — 축이 하나다",
        )

        bindings.bind(robot, v1, r2, "op", reason = "개정판만 올린다")
        val after = bindings.activeBinding(robot)!!

        assertNotEquals(before.third, after.third, "개정판이 안 바뀌었다 — 전제가 무너졌다")
        assertEquals(before.second, after.second, "개정판만 올렸는데 어댑터 버전이 바뀌었다")
    }

    // ── 합법성

    @Test
    fun `계약 semver가 낮으면 거부한다`() {
        // 어댑터가 모르는 스킬을 프로파일이 선언한 것이고, 그 로봇에 그
        // 스킬로 태스크를 걸면 어댑터가 이해하지 못한다.
        val revision = submitActive(1)
        val old = adapterVersion("0.9.0", semver = "0.0.1")
        val robot = robot()

        val refused = bindings.bind(robot, old, revision, "op")
        assertTrue(refused is BindOutcome.Refused, "낮은 계약으로 바인딩됐다: $refused")
        assertTrue(refused.detail.contains("0.0.1"), "무엇이 낮은지 안 알려준다: ${refused.detail}")
    }

    @Test
    fun `거부해도 옛 바인딩이 살아 있다`() {
        // 거부가 상태를 바꾸면 실패한 전환이 기체를 무바인딩으로 만든다.
        val revision = submitActive(1)
        val good = adapterVersion("1.0.0")
        val bad = adapterVersion("0.9.0", semver = "0.0.1")
        val robot = robot()

        bindings.bind(robot, good, revision, "op")
        val before = bindings.activeBinding(robot)!!

        bindings.bind(robot, bad, revision, "op")
        assertEquals(before, bindings.activeBinding(robot), "거부가 바인딩을 건드렸다")
    }

    @Test
    fun `충분히 높은 계약은 받는다`() {
        // 위 시험의 짝. 언제나 거부하는 구현이면 그것도 통과한다.
        val revision = submitActive(1)
        val high = adapterVersion("9.9.9", semver = "99.0.0")
        val bound = bindings.bind(robot(), high, revision, "op")
        assertTrue(bound is BindOutcome.Bound, "$bound")
    }

    @Test
    fun `활성 개정판이 아니면 바인딩을 거부한다`() {
        val stored = revisions.submit(Fixtures.good(), "op")
        assertTrue(stored is SubmitOutcome.Stored)
        val refused = bindings.bind(robot(), adapterVersion("1.0.0"), stored.profileRevisionId, "op")
        assertTrue(refused is BindOutcome.Refused, "VALIDATED인데 바인딩됐다: $refused")
    }

    // ── 해제는 삭제가 아니다

    @Test
    fun `해제는 unbound_at이고 이력이 남는다`() {
        val revision = submitActive(1)
        val robot = robot()
        bindings.bind(robot, adapterVersion("1.0.0"), revision, "op")
        bindings.bind(robot, adapterVersion("2.0.0"), revision, "op")

        val rows = PostgresSupport.queryOne("SELECT count(*) FROM robot_binding") { it.getInt(1) }
        assertEquals(2, rows, "옛 바인딩을 지웠다 — 진단 1번이 이력에 답할 수 없다")

        val active = PostgresSupport.queryOne(
            "SELECT count(*) FROM robot_binding WHERE unbound_at IS NULL",
        ) { it.getInt(1) }
        assertEquals(1, active, "활성 바인딩이 하나가 아니다")
    }

    // ── 활성화의 승인 조건 (§8.4 ③)

    @Test
    fun `세 스위트가 다 PASS라야 활성화된다`() {
        // **하나만 빠뜨려도 안 된다.** 표로 돈다 — 하나만 보면 나머지 둘이
        // 침묵한다.
        BindingService.SUITE_NAMES.forEach { missing ->
            PostgresSupport.reset()
            SkillTypeSync(db).sync(Fixtures.descriptor(), CONTRACT_SEMVER, "sync")
            val stored = revisions.submit(Fixtures.good(), "op") as SubmitOutcome.Stored

            BindingService.SUITE_NAMES.filterNot { it == missing }.forEach {
                bindings.recordTestRun(stored.profileRevisionId, it, "PASS", "harness")
            }
            val refused = bindings.activate(stored.profileRevisionId, "op")
            assertTrue(refused is ActivateOutcome.Refused, "$missing 없이 활성화됐다: $refused")
        }
    }

    @Test
    fun `최신 실행만 본다`() {
        // 옛 FAIL이 뒤의 PASS를 덮으면 한 번 실패한 개정판을 영영 못 살린다.
        val stored = revisions.submit(Fixtures.good(), "op") as SubmitOutcome.Stored
        bindings.recordTestRun(stored.profileRevisionId, "CONTRACT", "FAIL", "harness")
        BindingService.SUITE_NAMES.forEach {
            bindings.recordTestRun(stored.profileRevisionId, it, "PASS", "harness")
        }
        assertTrue(
            bindings.activate(stored.profileRevisionId, "op") is ActivateOutcome.Activated,
            "옛 FAIL이 뒤의 PASS를 덮었다",
        )
    }

    @Test
    fun `마지막이 FAIL이면 활성화되지 않는다`() {
        // 위 시험의 짝. "최신만 본다"가 "아무거나 하나 PASS면 된다"가 되면 안 된다.
        val stored = revisions.submit(Fixtures.good(), "op") as SubmitOutcome.Stored
        BindingService.SUITE_NAMES.forEach {
            bindings.recordTestRun(stored.profileRevisionId, it, "PASS", "harness")
        }
        bindings.recordTestRun(stored.profileRevisionId, "DETERMINISM", "FAIL", "harness")
        assertTrue(
            bindings.activate(stored.profileRevisionId, "op") is ActivateOutcome.Refused,
            "마지막이 FAIL인데 활성화됐다",
        )
    }

    @Test
    fun `DRAFT는 스위트가 다 PASS여도 활성화되지 않는다`() {
        // **상태 검사가 없으면 검증에 실패한 개정판이 활성화된다.**
        // 스위트 검사만으로는 못 막는다 — 시험 결과는 넣을 수 있으니까.
        // 실측으로 상태 검사를 없애는 주입이 안 잡혔다: 그 경로를 지나는
        // 시험이 없었다.
        val stored = revisions.submit(Fixtures.badErrorType(), "op") as SubmitOutcome.Stored
        assertEquals("DRAFT", statusOf(stored.profileRevisionId), "전제가 무너졌다")

        BindingService.SUITE_NAMES.forEach {
            bindings.recordTestRun(stored.profileRevisionId, it, "PASS", "harness")
        }
        assertEquals(
            "DRAFT", statusOf(stored.profileRevisionId),
            "검증에 실패한 개정판이 시험 결과만으로 TESTED가 됐다",
        )

        val refused = bindings.activate(stored.profileRevisionId, "op")
        assertTrue(refused is ActivateOutcome.Refused, "DRAFT가 활성화됐다: $refused")
    }

    @Test
    fun `계약 semver가 형식이 아니면 바인딩이 막힌다`() {
        // 파싱 실패를 조용히 0으로 접으면 **아무 조합이나 통과한다.**
        // `SemverTest`가 파싱 자체를 보고, 여기서는 그것이 바인딩까지
        // 닿는지 본다.
        val revision = submitActive(1)
        val broken = adapterVersion("x", semver = "not-a-semver")
        val outcome = runCatching { bindings.bind(robot(), broken, revision, "op") }

        assertTrue(
            outcome.isFailure || outcome.getOrNull() is BindOutcome.Refused,
            "형식이 아닌 semver로 바인딩됐다: ${outcome.getOrNull()}",
        )
    }

    @Test
    fun `다시 동기화해도 introduced_in_semver는 안 바뀐다`() {
        // **처음 본 때의 값이다.** 덮으면 옛 어댑터가 이미 아는 스킬이
        // 갑자기 "더 새로운 계약이 필요한" 것으로 보여 멀쩡한 바인딩이
        // 거부된다. 실측으로 덮어쓰는 주입이 안 잡혔다.
        PostgresSupport.reset()
        SkillTypeSync(db).sync(Fixtures.descriptor(), "0.1.0", "sync")
        val first = PostgresSupport.queryOne(
            "SELECT introduced_in_semver FROM skill_type ORDER BY name LIMIT 1",
        ) { it.getString(1) }
        assertEquals("0.1.0", first, "전제가 무너졌다")

        SkillTypeSync(db).sync(Fixtures.descriptor(), "9.9.9", "sync")
        assertEquals(
            "0.1.0",
            PostgresSupport.queryOne(
                "SELECT introduced_in_semver FROM skill_type ORDER BY name LIMIT 1",
            ) { it.getString(1) },
            "다시 동기화가 최초 semver를 덮었다",
        )
        // 그렇다고 아무것도 안 바뀌면 동기화가 일을 안 하는 것이다.
        assertEquals(
            "9.9.9",
            PostgresSupport.queryOne(
                "SELECT contract_revision FROM skill_type ORDER BY name LIMIT 1",
            ) { it.getString(1) },
            "동기화가 아무것도 안 했다",
        )
    }

    @Test
    fun `활성화하면 옛것은 SUPERSEDED다`() {
        val r1 = submitActive(1)
        val r2 = submitActive(2)

        assertEquals("SUPERSEDED", statusOf(r1), "옛 개정판이 안 내려갔다")
        assertEquals("ACTIVE", statusOf(r2))
    }

    @Test
    fun `롤백은 같은 경로다`() {
        // §8.4 ⑥ — 이전 개정판 재활성화이며 별도 경로가 아니다.
        val r1 = submitActive(1)
        val r2 = submitActive(2)

        val back = bindings.activate(r1, "op")
        assertTrue(back is ActivateOutcome.Activated, "롤백이 거부됐다: $back")
        assertEquals("ACTIVE", statusOf(r1))
        assertEquals("SUPERSEDED", statusOf(r2))
    }

    private fun statusOf(id: Long): String = PostgresSupport.queryOne(
        "SELECT status FROM profile_revision WHERE profile_revision_id = $id",
    ) { it.getString(1) }

    private companion object {
        /** 계약이 스스로 말하는 semver. 리터럴로 적으면 계약이 올라가도 조용하다. */
        val CONTRACT_SEMVER: String = dev.picasso.contracts.wire.ContractIdentity.semver
    }
}
