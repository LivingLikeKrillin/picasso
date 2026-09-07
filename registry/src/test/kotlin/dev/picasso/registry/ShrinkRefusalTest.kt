package dev.picasso.registry

import dev.picasso.registry.adapter.AdapterService
import dev.picasso.registry.adapter.RegisterOutcome
import dev.picasso.registry.binding.ActivateOutcome
import dev.picasso.registry.binding.BindOutcome
import dev.picasso.registry.binding.BindingService
import dev.picasso.registry.ledger.LedgerService
import dev.picasso.registry.plan.ChangePlanService
import dev.picasso.registry.plan.CheckType
import dev.picasso.registry.plan.CreateOutcome
import dev.picasso.registry.plan.ExecuteOutcome
import dev.picasso.registry.plan.Intent
import dev.picasso.registry.plan.Preconditions
import dev.picasso.registry.plan.StepKind
import dev.picasso.registry.revision.RevisionService
import dev.picasso.registry.revision.SkillTypeSync
import dev.picasso.registry.revision.SubmitOutcome
import dev.picasso.registry.store.Db
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 완료 기준 19 — **소비자가 남아 있거나 진행 중 태스크가 있으면
 * `REMOVE_CAPABILITY` 계획의 제거 단계가 거부된다. 둘 다 0이 된 뒤에야
 * 열린다.**
 *
 * §12.2가 이것을 이 프로젝트의 두 주장 중 하나로 꼽았다 — 11번이 "소스
 * 변경 0"을 **CI 실패 조건**으로 바꾸고, 19번이 "축소 거부"를 **조작 거부
 * 조건**으로 바꾼다.
 *
 * ## 셋이 한 시나리오여야 한다
 *
 * 따로 보면 **"언제나 거부한다"가 앞의 둘을 통과한다.** 막히는 것과 열리는
 * 것을 같은 흐름에서 봐야 거부가 판정이지 습관이 아니라는 것이 관측된다.
 */
class ShrinkRefusalTest {

    /**
     * 바인딩된 기체가 살아 있다고 보고한다.
     *
     * **이것이 없으면 §9.3의 두 조회가 `NOT_OBSERVABLE`로 막힌다** — 그리고
     * 그것이 옳다. 바인딩만 되고 한 번도 보고한 적 없는 기체는 지금 그
     * 능력을 돌리고 있는지 알 수 없고, 모르는 것을 0으로 세면 아직 쓰는
     * 능력의 제거가 열린다.
     */
    private fun reportLive(robotId: String = "r1") {
        dev.picasso.registry.ingest.LivenessService(db).record(
            dev.picasso.contracts.v1.MessageHeader.newBuilder()
                .setRobotId(robotId).setCapabilityEpoch(1).build(),
            dev.picasso.contracts.v1.ConnectionState.CONNECTION_STATE_ONLINE,
            null,
        )
    }

    private lateinit var db: Db
    private lateinit var ledger: LedgerService
    private lateinit var revisions: RevisionService
    private lateinit var bindings: BindingService
    private lateinit var plans: ChangePlanService
    private var adapterVersionId: Long = 0

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        ledger = LedgerService(db)
        revisions = RevisionService(db, Fixtures.validator())
        bindings = BindingService(db)
        plans = ChangePlanService(db, Preconditions(db, ledger), bindings)
        SkillTypeSync(db).sync(Fixtures.descriptor(), CONTRACT_SEMVER, "sync")

        db.transaction { c ->
            c.prepareStatement(
                "INSERT INTO robot (robot_id, site_id, serial_number) VALUES ('r1','line-a','sn')",
            ).use { it.executeUpdate() }
        }
        val adapters = AdapterService(db)
        val adapterId = adapters.registerAdapter("acme", "drv", "op")
        adapterVersionId = (
            adapters.registerVersion(adapterId, "1.0.0", CONTRACT_SEMVER, "op")
                as RegisterOutcome.Registered
            ).adapterVersionId
    }

    private fun activate(revision: Int = 1): Long {
        val stored = revisions.submit(Fixtures.good(revision = revision), "op")
        assertTrue(stored is SubmitOutcome.Stored, "$stored")
        BindingService.SUITE_NAMES.forEach {
            bindings.recordTestRun(stored.profileRevisionId, it, "PASS", "harness")
        }
        assertTrue(bindings.activate(stored.profileRevisionId, "op") is ActivateOutcome.Activated)
        return stored.profileRevisionId
    }

    /**
     * **후계는 `TESTED`까지만 만든다. 활성화는 `APPLY`가 한다**(§9.5).
     *
     * 미리 활성화하면 옛 개정판이 `SUPERSEDED`가 되고, `deprecated_after`는
     * 그 옛 개정판에 붙어 있으므로 `DEPRECATION_PUBLISHED`가 도로 거짓이
     * 된다 — 예고를 해 놓고도 제거가 영원히 안 열린다. **처음 이 시험을
     * 쓸 때 그렇게 짰다가 걸렸고, 그것이 이 순서가 강제되는 이유다.**
     */
    private fun preparedSuccessor(revision: Int = 2): Long {
        val stored = revisions.submit(Fixtures.good(revision = revision), "op")
        assertTrue(stored is SubmitOutcome.Stored, "$stored")
        BindingService.SUITE_NAMES.forEach {
            bindings.recordTestRun(stored.profileRevisionId, it, "PASS", "harness")
        }
        return stored.profileRevisionId
    }

    private fun announce(revisionId: Long) = PostgresSupport.execute(
        "UPDATE profile_skill SET deprecated_after = now() + interval '30 days' " +
            "WHERE profile_revision_id = $revisionId AND skill_type_id = " +
            "(SELECT skill_type_id FROM skill_type WHERE name = 'pick_place')",
    )

    private fun inflight(revisionId: Long, id: String = "t1") = db.transaction { c ->
        c.prepareStatement(
            """
            INSERT INTO task (task_id, robot_id, profile_revision_id, skill_type_id,
                              revision, state, terminal)
            SELECT ?, 'r1', ?, skill_type_id, 1, 'RUNNING', false
            FROM skill_type WHERE name = 'pick_place'
            """.trimIndent(),
        ).use { it.setString(1, id); it.setLong(2, revisionId); it.executeUpdate() }
    }

    private fun drain(id: String = "t1") =
        PostgresSupport.execute(
            "UPDATE task SET terminal = true, state = 'SUCCEEDED' WHERE task_id = '$id'",
        )

    private fun plan(): Long {
        val outcome = plans.create(
            Intent.REMOVE_CAPABILITY,
            mapOf("skill" to "pick_place", "major" to "1"),
            "line-a", "op",
        )
        assertTrue(outcome is CreateOutcome.Created, "$outcome")
        return outcome.planId
    }

    /** `APPLY`는 넷째 단계다(§9.5의 표). */
    private val applyStep = 4

    // ── 완료 기준 19의 한 시나리오

    @Test
    fun `소비자와 태스크가 빠진 뒤에야 제거가 열린다`() {
        val old = activate(revision = 1)
        assertTrue(bindings.bind("r1", adapterVersionId, old, "op") is BindOutcome.Bound)
        reportLive()
        announce(old)
        ledger.observe("WCS-B", "line-a", listOf("pick_place@^1.2"))
        inflight(old)
        val planId = plan()

        // ① 둘 다 남아 있다 — 거부되고, **둘 다** 사유에 나온다.
        val both = plans.execute(planId, applyStep, "op") as ExecuteOutcome.Refused
        assertEquals(
            setOf(CheckType.NO_ACTIVE_CONSUMERS, CheckType.NO_INFLIGHT_TASKS),
            both.blocking.map { it.type }.toSet(),
            "차단 사유가 둘 다 안 나온다: ${both.detail}",
        )

        // ② 태스크만 빠졌다 — 아직 거부다.
        drain()
        val consumersOnly = plans.execute(planId, applyStep, "op") as ExecuteOutcome.Refused
        assertEquals(
            listOf(CheckType.NO_ACTIVE_CONSUMERS),
            consumersOnly.blocking.map { it.type },
            "태스크가 빠졌는데 그것이 아직 막는다고 한다",
        )

        // ③ 소비자만 빠졌다면? — 반대쪽도 본다. 태스크를 되살린다.
        inflight(old, id = "t2")
        PostgresSupport.execute("UPDATE consumer_requirement SET active = false")
        val tasksOnly = plans.execute(planId, applyStep, "op") as ExecuteOutcome.Refused
        assertEquals(
            listOf(CheckType.NO_INFLIGHT_TASKS),
            tasksOnly.blocking.map { it.type },
        )

        // ④ 둘 다 0 — **이제 열린다.**
        drain("t2")
        val successor = preparedSuccessor()
        val executed = plans.execute(planId, applyStep, "op", successorRevisionId = successor)
        assertTrue(
            executed is ExecuteOutcome.Executed,
            "둘 다 0인데 아직 거부한다 — 거부가 판정이 아니라 습관이다: $executed",
        )
        assertEquals("APPLIED", statusOf(planId))
        assertEquals(
            "ACTIVE", revisionStatus(successor),
            "APPLY가 후계 개정판을 활성화하지 않았다",
        )
    }

    // ── 재평가

    @Test
    fun `충족을 확인한 뒤 새 소비자가 오면 실행이 거부된다`() {
        // *"충족을 확인한 순간"과 "실행한 순간" 사이의 창*(§9.5). 이것이
        // 없으면 그 창이 사고가 된다.
        val old = activate(revision = 1)
        assertTrue(bindings.bind("r1", adapterVersionId, old, "op") is BindOutcome.Bound)
        reportLive()
        announce(old)
        val planId = plan()

        // 앞 단계들이 전부 충족을 본다.
        assertTrue(plans.execute(planId, 1, "op") is ExecuteOutcome.Executed)
        assertTrue(plans.execute(planId, 2, "op") is ExecuteOutcome.Executed)
        assertTrue(plans.execute(planId, 3, "op") is ExecuteOutcome.Executed)
        assertTrue(
            plans.plans().single().steps.take(3).all { step ->
                step.checks.all { it.satisfied }
            },
            "앞 단계가 충족으로 안 보인다",
        )

        // 그 뒤 새 소비자가 협상에 성공한다.
        ledger.observe("늦게-온-소비자", "line-a", listOf("pick_place@^1.2"))

        val successor = preparedSuccessor()
        val refused = plans.execute(planId, applyStep, "op", successorRevisionId = successor)
        assertTrue(
            refused is ExecuteOutcome.Refused,
            "**캐시를 믿고 실행했다** — 확인과 실행 사이의 창이 사고가 된다",
        )
        assertEquals(listOf(CheckType.NO_ACTIVE_CONSUMERS), refused.blocking.map { it.type })
        assertEquals("DRAFT", statusOf(planId), "거부했는데 계획이 APPLIED가 됐다")
    }

    @Test
    fun `거부하면 화면의 캐시도 함께 내려간다`() {
        // 화면이 "충족"이라 적힌 채 실행만 거부되면 운영자는 시스템이
        // 고장 났다고 읽고, 그다음에 우회를 찾는다.
        val old = activate(revision = 1)
        assertTrue(bindings.bind("r1", adapterVersionId, old, "op") is BindOutcome.Bound)
        reportLive()
        announce(old)
        val planId = plan()
        assertTrue(plans.execute(planId, 2, "op") is ExecuteOutcome.Executed)
        assertEquals(true, satisfiedFlag(planId, 2))

        ledger.observe("늦게-온-소비자", "line-a", listOf("pick_place@^1.2"))
        assertTrue(plans.execute(planId, 2, "op") is ExecuteOutcome.Refused)
        assertEquals(false, satisfiedFlag(planId, 2), "거부했는데 캐시가 충족으로 남았다")
    }

    // ── 진단 6번

    @Test
    fun `진단 6번이 지금 재평가한 값을 낸다`() {
        // **캐시를 내면 화면이 "열렸다"고 하는데 실행은 거부되는 상태가
        // 생기고, 그때 운영자는 화면을 믿는다.**
        val old = activate(revision = 1)
        assertTrue(bindings.bind("r1", adapterVersionId, old, "op") is BindOutcome.Bound)
        reportLive()
        announce(old)
        val planId = plan()
        assertTrue(plans.execute(planId, 2, "op") is ExecuteOutcome.Executed)

        // DB의 캐시는 참으로 남아 있다.
        assertEquals(true, satisfiedFlag(planId, 2))

        // 그 뒤 상태가 바뀌면 화면이 **바로** 불충족을 보여야 한다.
        ledger.observe("늦게-온-소비자", "line-a", listOf("pick_place@^1.2"))

        val view = plans.plans().single()
        assertEquals(planId, view.planId)
        assertEquals(Intent.REMOVE_CAPABILITY, view.intent)
        assertEquals(
            listOf(
                StepKind.ANNOUNCE, StepKind.OBSERVE_MIGRATION, StepKind.DRAIN,
                StepKind.APPLY,
                // 축소는 APPLY 로 안 끝난다 — 어댑터까지 내려갔는지 확인해야
                // 카탈로그에서만 사라진 중간 상태를 안 남긴다.
                StepKind.VERIFY_WITHDRAWAL,
            ),
            view.steps.map { it.kind },
            "시스템이 §9.5의 단계를 안 만들었다",
        )
        val observe = view.steps.single { it.kind == StepKind.OBSERVE_MIGRATION }
        assertTrue(
            observe.checks.none { it.satisfied },
            "**캐시를 그대로 냈다** — 화면이 열렸다고 하는데 실행은 거부된다",
        )
        assertTrue(observe.executed, "실행된 사실은 남아야 한다")
    }

    @Test
    fun `끝난 계획은 기본으로 안 보인다`() {
        val old = activate(revision = 1)
        assertTrue(bindings.bind("r1", adapterVersionId, old, "op") is BindOutcome.Bound)
        reportLive()
        val planId = plan()
        plans.abandon(planId, "op")

        assertEquals(emptyList(), plans.plans(), "버린 계획이 진행 중으로 보인다")
        assertEquals(1, plans.plans(includeFinished = true).size)
    }

    // ── 경합

    @Test
    fun `같은 대상의 둘째 계획은 기존 것을 가리킨다`() {
        // 둘이면 두 운영자가 서로 모른 채 같은 능력을 지운다 — 각자
        // 자기 계획의 전제 조건만 보면서.
        val first = plan()
        val second = plans.create(
            Intent.REMOVE_CAPABILITY,
            mapOf("major" to "1", "skill" to "pick_place"),
            "line-a", "다른-운영자",
        )
        assertTrue(second is CreateOutcome.AlreadyLive, "$second")
        assertEquals(
            first, second.planId,
            "**기존 계획을 안 가리킨다** — 운영자가 대상을 조금 바꿔 둘째를 만든다",
        )
    }

    @Test
    fun `버린 뒤에는 다시 만들 수 있다`() {
        val first = plan()
        plans.abandon(first, "op")
        assertTrue(plans.create(
            Intent.REMOVE_CAPABILITY,
            mapOf("skill" to "pick_place", "major" to "1"),
            "line-a", "op",
        ) is CreateOutcome.Created)
    }

    @Test
    fun `다른 사이트는 각자 계획을 갖는다`() {
        plan()
        assertTrue(plans.create(
            Intent.REMOVE_CAPABILITY,
            mapOf("skill" to "pick_place", "major" to "1"),
            "line-b", "op",
        ) is CreateOutcome.Created)
    }

    @Test
    fun `다른 능력은 각자 계획을 갖는다`() {
        plan()
        assertTrue(plans.create(
            Intent.REMOVE_CAPABILITY,
            mapOf("skill" to "navigate_to", "major" to "1"),
            "line-a", "op",
        ) is CreateOutcome.Created)
    }

    // ── 준비물

    private fun statusOf(planId: Long): String = PostgresSupport.queryOne(
        "SELECT status FROM change_plan WHERE plan_id = $planId",
    ) { it.getString(1) }

    private fun revisionStatus(id: Long): String = PostgresSupport.queryOne(
        "SELECT status FROM profile_revision WHERE profile_revision_id = $id",
    ) { it.getString(1) }

    private fun satisfiedFlag(planId: Long, seq: Int): Boolean = PostgresSupport.queryOne(
        "SELECT satisfied FROM change_plan_step WHERE plan_id = $planId AND seq = $seq",
    ) { it.getBoolean(1) }

    private companion object {
        val CONTRACT_SEMVER: String = dev.picasso.contracts.wire.ContractIdentity.semver
    }
}
