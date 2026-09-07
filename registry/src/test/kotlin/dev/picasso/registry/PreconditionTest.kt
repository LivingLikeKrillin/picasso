package dev.picasso.registry

import dev.picasso.registry.adapter.AdapterService
import dev.picasso.registry.adapter.RegisterOutcome
import dev.picasso.registry.binding.ActivateOutcome
import dev.picasso.registry.binding.BindOutcome
import dev.picasso.registry.binding.BindingService
import dev.picasso.registry.ledger.ConsumerKind
import dev.picasso.registry.ledger.LedgerService
import dev.picasso.registry.plan.CheckType
import dev.picasso.registry.plan.PreconditionCheck
import dev.picasso.registry.plan.Preconditions
import dev.picasso.registry.revision.RevisionService
import dev.picasso.registry.revision.SkillTypeSync
import dev.picasso.registry.revision.SubmitOutcome
import dev.picasso.registry.store.Db
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §9.5의 전제 조건 다섯.
 *
 * ## 각 검사에 양성과 음성이 둘 다 있다
 *
 * **언제나 참을 돌려주는 구현은 양성만으로 통과한다** — 그리고 그것이
 * 정확히 완료 기준 19가 막으려는 결함이다. §9.3이 *"운영자가 판단하지
 * 않는다"*며 조회에 권한을 넘긴 만큼, **조회가 거짓말하면 막을 것이 없다.**
 *
 * 무너진 것이 안 보인다는 점이 특히 나쁘다 — 화면은 여전히 "충족"이라고
 * 적혀 있다.
 */
class PreconditionTest {

    private lateinit var db: Db
    private lateinit var ledger: LedgerService
    private lateinit var revisions: RevisionService
    private lateinit var bindings: BindingService
    private lateinit var adapters: AdapterService
    private lateinit var checks: Preconditions
    private var adapterVersionId: Long = 0

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        ledger = LedgerService(db)
        revisions = RevisionService(db, Fixtures.validator())
        bindings = BindingService(db)
        adapters = AdapterService(db)
        checks = Preconditions(db, ledger)
        SkillTypeSync(db).sync(Fixtures.descriptor(), CONTRACT_SEMVER, "sync")

        db.transaction { c ->
            c.prepareStatement(
                "INSERT INTO robot (robot_id, site_id, serial_number) VALUES ('r1','line-a','sn')",
            ).use { it.executeUpdate() }
        }
        val adapterId = adapters.registerAdapter("acme", "drv", "op")
        adapterVersionId = (
            adapters.registerVersion(adapterId, "1.0.0", CONTRACT_SEMVER, "op")
                as RegisterOutcome.Registered
            ).adapterVersionId
    }

    private fun activate(documentJson: String = Fixtures.good()): Long {
        val stored = revisions.submit(documentJson, "op")
        assertTrue(stored is SubmitOutcome.Stored, "$stored")
        BindingService.SUITE_NAMES.forEach {
            bindings.recordTestRun(stored.profileRevisionId, it, "PASS", "harness")
        }
        assertTrue(bindings.activate(stored.profileRevisionId, "op") is ActivateOutcome.Activated)
        return stored.profileRevisionId
    }

    private fun bind(revisionId: Long) {
        assertTrue(bindings.bind("r1", adapterVersionId, revisionId, "op") is BindOutcome.Bound)
    }

    private fun check(type: CheckType, vararg params: Pair<String, String>) =
        checks.evaluate(PreconditionCheck(type, params.toMap()))

    private fun task(skill: String, terminal: Boolean, revisionId: Long, id: String = "t1") =
        db.transaction { c ->
            c.prepareStatement(
                """
                INSERT INTO task (task_id, robot_id, profile_revision_id, skill_type_id,
                                  revision, state, terminal)
                SELECT ?, 'r1', ?, skill_type_id, 1, ?, ?
                FROM skill_type WHERE name = ?
                """.trimIndent(),
            ).use {
                it.setString(1, id); it.setLong(2, revisionId)
                it.setString(3, if (terminal) "SUCCEEDED" else "RUNNING")
                it.setBoolean(4, terminal); it.setString(5, skill)
                assertEquals(1, it.executeUpdate(), "태스크가 안 들어갔다 — 스킬 이름이 틀렸나")
            }
        }

    // ── NO_ACTIVE_CONSUMERS

    @Test
    fun `소비자가 없으면 충족이다`() {
        assertTrue(check(CheckType.NO_ACTIVE_CONSUMERS, "skill" to "pick_place").satisfied)
    }

    @Test
    fun `소비자가 있으면 불충족이고 몇인지 말한다`() {
        ledger.observe("a", "line-a", listOf("pick_place@^1.0"))
        ledger.observe("b", "line-a", listOf("pick_place@^1.0"))

        val outcome = check(CheckType.NO_ACTIVE_CONSUMERS, "skill" to "pick_place")
        assertTrue(!outcome.satisfied)
        assertTrue("2" in outcome.detail, "몇 명인지 안 말한다: ${outcome.detail}")
    }

    @Test
    fun `등록만 한 소비자도 막는다`() {
        // `source`를 구분하지 않는다(§8.3 결정 6). DECLARED만 보고 넘기면
        // 관측된 소비자가 통째로 안 보인다 — 그 반대도 마찬가지다.
        ledger.declare("MES", ConsumerKind.UPSTREAM_SYSTEM, "line-a", "MES", listOf("pick_place@^1.0"))
        assertTrue(!check(CheckType.NO_ACTIVE_CONSUMERS, "skill" to "pick_place").satisfied)
    }

    @Test
    fun `다른 능력의 소비자는 안 막는다`() {
        ledger.observe("a", "line-a", listOf("navigate_to@^1.0"))
        assertTrue(check(CheckType.NO_ACTIVE_CONSUMERS, "skill" to "pick_place").satisfied)
    }

    // ── NO_INFLIGHT_TASKS

    @Test
    fun `진행 중 태스크가 없으면 충족이다`() {
        activate().also { task("pick_place", terminal = true, revisionId = it) }
        assertTrue(check(CheckType.NO_INFLIGHT_TASKS, "skill" to "pick_place").satisfied)
    }

    @Test
    fun `진행 중 태스크가 있으면 불충족이다`() {
        activate().also { task("pick_place", terminal = false, revisionId = it) }

        val outcome = check(CheckType.NO_INFLIGHT_TASKS, "skill" to "pick_place")
        assertTrue(!outcome.satisfied)
        assertTrue("1" in outcome.detail, outcome.detail)
    }

    @Test
    fun `드레인은 스킬 단위다`() {
        // **`task.skill_type_id`가 있는 이유가 이것이다**(§8.3). 없으면
        // 로봇 전체가 비기를 기다리게 되고, 바쁜 라인은 영원히 안 빈다.
        val rev = activate()
        task("navigate_to", terminal = false, revisionId = rev)
        assertTrue(check(CheckType.NO_INFLIGHT_TASKS, "skill" to "pick_place").satisfied)
        assertTrue(!check(CheckType.NO_INFLIGHT_TASKS, "skill" to "navigate_to").satisfied)
    }

    // ── DEPRECATION_PUBLISHED

    @Test
    fun `예고가 없으면 불충족이다`() {
        activate()
        assertTrue(!check(CheckType.DEPRECATION_PUBLISHED, "skill" to "pick_place").satisfied)
    }

    @Test
    fun `예고가 있으면 충족이다`() {
        val rev = activate()
        PostgresSupport.execute(
            "UPDATE profile_skill SET deprecated_after = now() + interval '30 days' " +
                "WHERE profile_revision_id = $rev AND skill_type_id = " +
                "(SELECT skill_type_id FROM skill_type WHERE name = 'pick_place')",
        )
        assertTrue(check(CheckType.DEPRECATION_PUBLISHED, "skill" to "pick_place").satisfied)
    }

    @Test
    fun `활성이 아닌 개정판의 예고는 안 센다`() {
        // 예고는 **지금 도는 것**에 붙어야 소비자에게 보인다. 옛 개정판에
        // 적힌 예고를 세면 "예고했다"가 아무도 못 본 예고가 된다.
        val old = activate(Fixtures.good(revision = 1))
        PostgresSupport.execute(
            "UPDATE profile_skill SET deprecated_after = now() WHERE profile_revision_id = $old",
        )
        activate(Fixtures.good(revision = 2))
        assertTrue(!check(CheckType.DEPRECATION_PUBLISHED, "skill" to "pick_place").satisfied)
    }

    @Test
    fun `계약 축 예고만 있어도 충족이다`() {
        // §9.3은 폐기 시각을 **두 축 중 이른 쪽**으로 규정한다. 프로파일 축만
        // 보면 계약 전체를 접는 예고가 없는 것이 되고, 그 예고를 낸 사람은
        // 제거가 왜 안 열리는지 알 방법이 없다.
        activate()
        announceContract("pick_place")
        assertTrue(check(CheckType.DEPRECATION_PUBLISHED, "skill" to "pick_place").satisfied)
    }

    @Test
    fun `다른 스킬의 계약 축 예고는 안 센다`() {
        // 축을 하나 더 보는 것과 아무거나 세는 것은 다르다.
        activate()
        announceContract("navigate_to")
        assertTrue(!check(CheckType.DEPRECATION_PUBLISHED, "skill" to "pick_place").satisfied)
    }

    private fun announceContract(skill: String) = PostgresSupport.execute(
        "INSERT INTO skill_type_deprecation (skill_type_id, deprecated_after, announced_by) " +
            "SELECT skill_type_id, now() + interval '30 days', 'contract-owner' " +
            "FROM skill_type WHERE name = '" + skill + "' AND major = 1",
    )

    // ── NO_ACTIVE_BINDINGS

    @Test
    fun `붙은 기체가 없으면 충족이다`() {
        assertTrue(
            check(
                CheckType.NO_ACTIVE_BINDINGS,
                "adapter_version_id" to "$adapterVersionId",
            ).satisfied,
        )
    }

    @Test
    fun `붙은 기체가 있으면 불충족이다`() {
        bind(activate())
        val outcome = check(
            CheckType.NO_ACTIVE_BINDINGS,
            "adapter_version_id" to "$adapterVersionId",
        )
        assertTrue(!outcome.satisfied)
        assertTrue("1" in outcome.detail, outcome.detail)
    }

    @Test
    fun `해제된 바인딩은 안 센다`() {
        // 세면 **한 번이라도 쓴 적 있는 개정판은 영원히 폐기할 수 없다.**
        bind(activate())
        PostgresSupport.execute("UPDATE robot_binding SET unbound_at = now()")
        assertTrue(
            check(CheckType.NO_ACTIVE_BINDINGS, "adapter_version_id" to "$adapterVersionId")
                .satisfied,
        )
    }

    @Test
    fun `개정판으로도 물을 수 있다`() {
        val rev = activate()
        bind(rev)
        assertTrue(!check(CheckType.NO_ACTIVE_BINDINGS, "profile_revision_id" to "$rev").satisfied)
    }

    @Test
    fun `둘 다 주거나 안 주면 거부한다`() {
        // 애매한 질의를 조용히 한쪽으로 해석하면 **묻지 않은 것에 답한
        // 결과로 축소가 열린다.**
        val both = assertFailsWith<IllegalArgumentException> {
            check(
                CheckType.NO_ACTIVE_BINDINGS,
                "adapter_version_id" to "1", "profile_revision_id" to "1",
            )
        }
        // **문장까지 본다.** `single()`이 던지는 "List has more than one element"도
        // IllegalArgumentException이라, 타입만 단언하면 "정확히 하나"라는 규칙이
        // 사라져도 시험이 통과한다 — 3b-2 주입에서 실제로 그랬다.
        assertTrue("정확히 하나" in (both.message ?: ""), "사유가 규칙을 안 말한다: ${both.message}")
        assertFailsWith<IllegalArgumentException> { check(CheckType.NO_ACTIVE_BINDINGS) }
    }

    // ── SUCCESSOR_ACTIVE

    @Test
    fun `후계가 활성이고 붙어 있으면 충족이다`() {
        val successor = activate()
        bind(successor)
        assertTrue(
            check(CheckType.SUCCESSOR_ACTIVE, "profile_revision_id" to "$successor").satisfied,
        )
    }

    @Test
    fun `후계가 활성이어도 아무도 안 붙었으면 불충족이다`() {
        // **`ACTIVE`만 보면 안 된다.** 활성화는 바인딩 전환이 아니고
        // (§8.4 ③), 아무 기체도 안 붙은 후계를 인정하면 **옛 것을 지운 뒤
        // 아무도 그 능력을 못 쓰는 순간**이 생긴다.
        val successor = activate()
        val outcome = check(CheckType.SUCCESSOR_ACTIVE, "profile_revision_id" to "$successor")
        assertTrue(!outcome.satisfied)
        assertTrue("붙은 기체가 없다" in outcome.detail, outcome.detail)
    }

    @Test
    fun `후계가 활성이 아니면 불충족이다`() {
        val stored = revisions.submit(Fixtures.good(), "op") as SubmitOutcome.Stored
        val outcome = check(
            CheckType.SUCCESSOR_ACTIVE,
            "profile_revision_id" to "${stored.profileRevisionId}",
        )
        assertTrue(!outcome.satisfied)
        assertTrue("VALIDATED" in outcome.detail, outcome.detail)
    }

    @Test
    fun `없는 후계는 불충족이다`() {
        // 없는 것을 조용히 통과시키면 **오타 하나가 축소를 연다.**
        val outcome = check(CheckType.SUCCESSOR_ACTIVE, "profile_revision_id" to "999999")
        assertTrue(!outcome.satisfied)
        assertTrue("없다" in outcome.detail, outcome.detail)
    }

    // ── 인자

    @Test
    fun `필요한 인자가 없으면 던진다`() {
        // **조용히 빈 문자열로 조회하면 언제나 0이 나오고 언제나 충족이다.**
        assertFailsWith<IllegalArgumentException> { check(CheckType.NO_ACTIVE_CONSUMERS) }
        assertFailsWith<IllegalArgumentException> { check(CheckType.NO_INFLIGHT_TASKS) }
        assertFailsWith<IllegalArgumentException> { check(CheckType.DEPRECATION_PUBLISHED) }
        assertFailsWith<IllegalArgumentException> { check(CheckType.SUCCESSOR_ACTIVE) }
    }

    private companion object {
        val CONTRACT_SEMVER: String = dev.picasso.contracts.wire.ContractIdentity.semver
    }

    // ── 축소 완료 검증 (CAPABILITY_WITHDRAWN)

    /** 그 기체가 지금 보고하는 epoch. */
    private fun report(robotId: String, epoch: Long) {
        PostgresSupport.execute(
            "INSERT INTO robot_liveness " +
                "(robot_id, last_reported_at, connection_state, capability_epoch) " +
                "VALUES ('$robotId', now(), 'CONNECTION_STATE_ONLINE', $epoch) " +
                "ON CONFLICT (robot_id) DO UPDATE SET " +
                "last_reported_at = now(), capability_epoch = $epoch",
        )
    }

    private fun baseline(planId: Long, robotId: String, epoch: Long) {
        PostgresSupport.execute(
            "INSERT INTO withdrawal_baseline (change_plan_id, robot_id, epoch_at_apply) " +
                "VALUES ($planId, '$robotId', $epoch)",
        )
    }

    private fun withdrawn(planId: Long) = checks.evaluate(
        PreconditionCheck(
            CheckType.CAPABILITY_WITHDRAWN,
            mapOf("skill" to "pick_place", "plan" to planId.toString()),
        ),
    )

    private fun plan(): Long = PostgresSupport.queryOne(
        "INSERT INTO change_plan (intent, target, target_key, site, created_by) " +
            "VALUES ('REMOVE_CAPABILITY', '{}'::jsonb, 'k', 'line-a', 'op') RETURNING plan_id",
    ) { it.getLong(1) }

    @Test
    fun `baseline이 없으면 충족이 아니다`() {
        // 없는 것을 "전부 반영됨"으로 읽으면 APPLY 하기도 전에 완료로 넘어간다.
        report("r1", 5)
        val outcome = withdrawn(plan())

        assertFalse(outcome.satisfied, outcome.detail)
        assertTrue("baseline" in outcome.detail, outcome.detail)
    }

    @Test
    fun `APPLY 직후에는 아직 안 내려갔다`() {
        val planId = plan()
        report("r1", 5)
        baseline(planId, "r1", 5)

        val outcome = withdrawn(planId)

        assertFalse(outcome.satisfied, "epoch 가 그대로인데 반영됐다고 한다: ${outcome.detail}")
        assertTrue("r1" in outcome.detail, outcome.detail)
    }

    @Test
    fun `전 기체가 새 epoch로 보고해야 충족이다`() {
        val planId = plan()
        PostgresSupport.execute(
            "INSERT INTO robot (robot_id, site_id, serial_number) VALUES ('r2','line-a','sn2')",
        )
        report("r1", 5); baseline(planId, "r1", 5)
        report("r2", 5); baseline(planId, "r2", 5)

        // 하나만 올린다 — **둘 다 올라야 충족이다.**
        report("r1", 6)
        val partial = withdrawn(planId)
        assertFalse(partial.satisfied, partial.detail)
        assertTrue("r2" in partial.detail, partial.detail)

        report("r2", 6)
        val full = withdrawn(planId)
        assertTrue(full.satisfied, full.detail)
    }

    @Test
    fun `기체 사이에 epoch를 비교하지 않는다`() {
        // §8.2가 채번자를 발신자로 정했으므로 r1 의 7과 r2 의 7은 비교할 수
        // 없다. **같은 기체의 baseline 대비로만** 본다 — 전역 임계를 쓰는
        // 구현은 여기서 잡힌다.
        val planId = plan()
        PostgresSupport.execute(
            "INSERT INTO robot (robot_id, site_id, serial_number) VALUES ('r2','line-a','sn2')",
        )
        report("r1", 100); baseline(planId, "r1", 99)
        report("r2", 3); baseline(planId, "r2", 2)

        val outcome = withdrawn(planId)

        assertTrue(outcome.satisfied, "둘 다 자기 baseline 을 넘었는데 막혔다: ${outcome.detail}")
    }
}
