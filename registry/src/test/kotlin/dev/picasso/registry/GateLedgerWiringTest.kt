package dev.picasso.registry

import dev.picasso.gate.CheckResult
import dev.picasso.gate.GateRunner
import dev.picasso.gate.checks.Check06Vocabulary
import dev.picasso.gate.input.GateInput
import dev.picasso.gate.input.LedgerQuery
import dev.picasso.profile.ProfileDocument
import dev.picasso.profile.ProfileKey
import dev.picasso.registry.binding.ActivateOutcome
import dev.picasso.registry.binding.BindingService
import dev.picasso.registry.ledger.LedgerService
import dev.picasso.registry.ledger.RegistryLedgerQuery
import dev.picasso.registry.revision.RevisionService
import dev.picasso.registry.revision.RevisionStatus
import dev.picasso.registry.revision.RevisionValidator
import dev.picasso.registry.revision.SkillTypeSync
import dev.picasso.registry.revision.SubmitOutcome
import dev.picasso.registry.store.Db
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **이 저장소의 두 주장이 한 검사 안에서 만난다.**
 *
 * 11번은 *"계약을 어기는 변경은 소스 변경 0으로 CI에서 막힌다"*이고, 19번은
 * *"소비자가 남아 있으면 축소가 거부된다"*이다. 3a까지 검사 6번은 축소를
 * **분류만** 했다 — 원장이 없었으니 *"§9.3의 두 조회를 하지 못했다"*로 끝났다.
 *
 * 여기서 확인하는 것은 하나다: **같은 문서, 같은 검사, 다른 원장이면 다른
 * 판정인가.** 아니라면 [RegistryLedgerQuery]는 배선된 척만 하는 것이고,
 * 그 사실은 원장이 비는 날까지 아무도 모른다.
 *
 * ## 왜 게이트가 아니라 레지스트리에서 보는가
 *
 * §11.1이 두 호출자가 같은 답을 내야 한다고 못박았다. 게이트 시험은
 * `gate` 모듈에 이미 있고 가짜 원장으로 돈다. 여기서 볼 것은 **진짜 원장을
 * 문 레지스트리가 개정판 제출을 실제로 거부하는가**다 — 그것이 완료 기준
 * 19가 말한 "조작 거부 조건"이다.
 */
class GateLedgerWiringTest {

    private lateinit var db: Db
    private lateinit var ledger: LedgerService
    private lateinit var bindings: BindingService

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        ledger = LedgerService(db)
        bindings = BindingService(db)
        SkillTypeSync(db).sync(Fixtures.descriptor(), CONTRACT_SEMVER, "sync")
        PostgresSupport.execute(
            "INSERT INTO robot (robot_id, site_id, serial_number) VALUES ('r1','line-a','sn')",
        )
        activateBaseline()
    }

    // ── 레지스트리의 판정이 원장을 따라 바뀐다

    @Test
    fun `소비자가 남아 있으면 축소 개정판이 거부된다`() {
        aliveLedger()
        ledger.observe("line-controller", "line-a", listOf("navigate_to@^1.0"))

        val stored = submitShrunk()

        assertEquals(RevisionStatus.DRAFT, stored.status, "거부된 개정판은 DRAFT에 머문다: $stored")
        assertTrue(
            stored.reasons.any { "축소가 거부됐다" in it && "active 소비자 1" in it },
            "축소 거부가 사유로 안 남았다: ${stored.reasons}",
        )
    }

    @Test
    fun `진행 중 태스크가 남아 있으면 축소 개정판이 거부된다`() {
        aliveLedger()
        task("t2", "navigate_to", terminal = false)

        val stored = submitShrunk()

        assertEquals(RevisionStatus.DRAFT, stored.status, "$stored")
        assertTrue(
            stored.reasons.any { "비종착 태스크 1" in it },
            "드레인 미완이 사유로 안 남았다: ${stored.reasons}",
        )
    }

    @Test
    fun `둘 다 0으로 관측되면 같은 문서가 통과한다`() {
        // **같은 문서다.** 다른 것은 원장뿐이다 — 이 대비가 없으면
        // "언제나 거부"가 앞의 두 시험을 통과한다.
        aliveLedger()
        ledger.observe("old-consumer", "line-a", listOf("navigate_to@^1.0"))
        PostgresSupport.execute(
            "UPDATE consumer_requirement SET last_seen = now() - interval '40 days' " +
                "WHERE consumer_id = 'old-consumer'",
        )
        ledger.decay()

        val stored = submitShrunk()

        assertEquals(RevisionStatus.VALIDATED, stored.status, "사유: ${stored.reasons}")
    }

    // ── 검사 6번이 실제로 무엇을 말했는가

    @Test
    fun `원장이 관측하면 승인 소견에 관측 시각이 실린다`() {
        aliveLedger()

        val said = findings(RegistryLedgerQuery(db))

        assertTrue(
            said.any { "축소가 승인됐다" in it && "관측 시각" in it },
            "관측 사실이 산출물로 안 남았다: $said",
        )
    }

    @Test
    fun `원장이 비면 승인이 아니라 관측 실패로 남는다`() {
        // **여기가 접히면 안 되는 자리다.** 빈 원장은 0을 돌려주지만
        // 그 0은 "쓰는 사람이 없다"가 아니라 "모른다"다.
        val said = findings(RegistryLedgerQuery(db))

        assertTrue(
            said.any { "원장이 관측하지 못했다" in it },
            "빈 원장이 승인으로 접혔다: $said",
        )
        assertTrue(said.none { "축소가 승인됐다" in it }, said.toString())
    }

    @Test
    fun `원장이 아예 없으면 조회를 못 했다고 남는다`() {
        val said = findings(null)

        assertTrue(said.any { "원장이 없어" in it }, said.toString())
    }

    // ── 씨앗

    /**
     * 두 관측선을 **살려 둔다.** 하나라도 죽어 있으면 검사 6번은 개수를
     * 보기 전에 "관측 못 했다"로 끝나고, 그러면 거부 시험이 거부가 아니라
     * 관측 실패를 보게 된다 — 초록인데 아무것도 안 본 시험이 된다.
     */
    private fun aliveLedger() {
        ledger.observe("line-controller", "line-a", listOf("pick_place@^1.2"))
        task("t1", "pick_place", terminal = true)
    }

    private fun activateBaseline() {
        val stored = revisions(null).submit(Fixtures.good(), "op")
        assertTrue(stored is SubmitOutcome.Stored, "$stored")
        BindingService.SUITE_NAMES.forEach {
            bindings.recordTestRun(stored.profileRevisionId, it, "PASS", "harness")
        }
        assertTrue(bindings.activate(stored.profileRevisionId, "op") is ActivateOutcome.Activated)
    }

    private fun submitShrunk(): SubmitOutcome.Stored {
        val stored = revisions(RegistryLedgerQuery(db)).submit(Fixtures.shrunk(revision = 2), "op")
        assertTrue(stored is SubmitOutcome.Stored, "$stored")
        return stored
    }

    private fun revisions(query: LedgerQuery?) = RevisionService(
        db,
        RevisionValidator(Fixtures.schema(), Fixtures.descriptor(), ledger = query),
    )

    /** 검사 6번 하나만 돌려 **문장 자체**를 본다. 판정만 보면 사유가 안 보인다. */
    private fun findings(query: LedgerQuery?): List<String> {
        val head = ProfileDocument.parse("head", Fixtures.shrunk(revision = 2)).getOrThrow()
        val input = GateInput(
            profiles = listOf(head),
            schemaJson = Fixtures.schema(),
            descriptor = Fixtures.descriptor(),
            baseline = mapOf(ProfileKey(head.vendor, head.model) to Fixtures.good()),
            registry = query,
        )
        return GateRunner(listOf(Check06Vocabulary())).run(input).results.flatMap {
            when (it) {
                is CheckResult.Passed -> it.findings
                is CheckResult.Failed -> it.findings
                else -> emptyList()
            }
        }.map { it.message }
    }

    private fun task(id: String, skill: String, terminal: Boolean) = PostgresSupport.execute(
        """
        INSERT INTO task (task_id, robot_id, profile_revision_id, skill_type_id,
                          revision, state, terminal)
        SELECT '$id', 'r1', (SELECT min(profile_revision_id) FROM profile_revision),
               s.skill_type_id, 1,
               '${if (terminal) "SUCCEEDED" else "RUNNING"}', $terminal
        FROM skill_type s WHERE s.name = '$skill' AND s.major = 1
        """.trimIndent(),
    )

    private companion object {
        val CONTRACT_SEMVER: String = dev.picasso.contracts.wire.ContractIdentity.semver
    }
}
