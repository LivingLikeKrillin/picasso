package dev.picasso.registry

import dev.picasso.gate.input.LedgerAnswer
import dev.picasso.registry.ledger.ConsumerKind
import dev.picasso.registry.ledger.LedgerService
import dev.picasso.registry.ledger.RegistryLedgerQuery
import dev.picasso.registry.revision.RevisionService
import dev.picasso.registry.revision.SkillTypeSync
import dev.picasso.registry.revision.SubmitOutcome
import dev.picasso.registry.store.Db
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **"0"과 "모른다"가 다른 답인가.**
 *
 * 이 시험이 없으면 [RegistryLedgerQuery]는 `count(*)` 한 줄과 구별되지
 * 않는다. 그리고 그 한 줄은 브로커 구독이 끊긴 날 **정확히 0을 돌려주고**,
 * 검사 6번은 그것을 §9.3의 진입 조건 충족으로 읽는다.
 *
 * 그래서 **다섯 갈래를 다 짚는다** — 관측 있음/없음, 표가 빔, 오래됨,
 * 닿지 못함. 앞의 둘만 보면 "언제나 Observed"가 통과한다.
 */
class RegistryLedgerQueryTest {

    private lateinit var db: Db
    private lateinit var ledger: LedgerService
    private lateinit var revisions: RevisionService
    private lateinit var query: RegistryLedgerQuery

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        ledger = LedgerService(db)
        revisions = RevisionService(db, Fixtures.validator())
        query = RegistryLedgerQuery(db)
        SkillTypeSync(db).sync(Fixtures.descriptor(), CONTRACT_SEMVER, "sync")
    }

    // ── 소비자 조회

    @Test
    fun `관측선이 살아 있으면 소비자를 센다`() {
        ledger.observe("line-controller", "line-a", listOf("pick_place@^1.2"))

        val answer = query.activeConsumers("pick_place", 1)

        assertTrue(answer is LedgerAnswer.Observed, "$answer")
        assertEquals(1, answer.count)
    }

    @Test
    fun `표가 비면 0이 아니라 모른다다`() {
        // **이 시험이 이 클래스의 이유다.** 빈 표는 0을 돌려주고, 0은
        // 축소를 연다.
        val answer = query.activeConsumers("pick_place", 1)

        assertTrue(answer is LedgerAnswer.NotObservable, "$answer")
        assertTrue("구독이 없는" in answer.reason, answer.reason)
    }

    @Test
    fun `관측이 오래되면 모른다다`() {
        ledger.observe("line-controller", "line-a", listOf("pick_place@^1.2"))
        PostgresSupport.execute(
            "UPDATE consumer_requirement SET last_seen = now() - interval '48 hours'",
        )

        val answer = query.activeConsumers("pick_place", 1)

        assertTrue(answer is LedgerAnswer.NotObservable, "$answer")
        assertTrue("구독이 끊겼을" in answer.reason, answer.reason)
    }

    @Test
    fun `관측 시각은 지금이 아니라 마지막 관측이다`() {
        // 개수는 방금 셌지만 **그 개수를 믿을 근거는 마지막 관측 시각**이다.
        // 지금 시각을 실으면 하루 묵은 원장이 방금 관측한 것처럼 보이고,
        // 그 소견을 읽는 사람은 신선도를 판단할 방법을 잃는다.
        ledger.observe("line-controller", "line-a", listOf("pick_place@^1.2"))
        PostgresSupport.execute(
            "UPDATE consumer_requirement SET last_seen = now() - interval '2 hours'",
        )

        val answer = query.activeConsumers("pick_place", 1)

        assertTrue(answer is LedgerAnswer.Observed, "$answer")
        assertTrue(
            answer.asOf.isBefore(java.time.Instant.now().minus(Duration.ofMinutes(90))),
            "관측 시각이 지금으로 찍혔다: ${answer.asOf}",
        )
    }

    @Test
    fun `창 안이면 오래된 것이 아니다`() {
        // 경계가 한쪽으로만 시험되면 "언제나 오래됨"도 통과한다.
        ledger.observe("line-controller", "line-a", listOf("pick_place@^1.2"))
        PostgresSupport.execute(
            "UPDATE consumer_requirement SET last_seen = now() - interval '23 hours'",
        )

        assertTrue(query.activeConsumers("pick_place", 1) is LedgerAnswer.Observed)
    }

    @Test
    fun `원장에 닿지 못하면 모른다다`() {
        val unreachable = RegistryLedgerQuery(
            Db("jdbc:postgresql://127.0.0.1:1/none", "none", "none"),
        )

        val answer = unreachable.activeConsumers("pick_place", 1)

        assertTrue(answer is LedgerAnswer.NotObservable, "$answer")
        assertTrue("닿지 못했다" in answer.reason, answer.reason)
    }

    @Test
    fun `감쇠한 소비자는 안 세지만 관측선은 살아 있다`() {
        // **이것이 진짜 0이다.** 옮겨 간 소비자는 감쇠하고, 남은 소비자가
        // 관측선이 살아 있음을 증명한다.
        ledger.observe("old-consumer", "line-a", listOf("navigate_to@^1.0"))
        PostgresSupport.execute(
            "UPDATE consumer_requirement SET last_seen = now() - interval '40 days' " +
                "WHERE consumer_id = 'old-consumer'",
        )
        ledger.decay()
        ledger.observe("line-controller", "line-a", listOf("pick_place@^1.2"))

        val answer = query.activeConsumers("navigate_to", 1)

        assertTrue(answer is LedgerAnswer.Observed, "$answer")
        assertEquals(0, answer.count)
    }

    @Test
    fun `요구가 전부 비활성이어도 방금 관측했으면 0을 관측한 것이다`() {
        // **워터마크는 관측선을 재지 소비자를 재지 않는다.** 세려는 술어로
        // 증거를 걸러 내면 마지막 소비자가 떠난 순간 원장이 눈을 감는다.
        ledger.observe("line-controller", "line-a", listOf("pick_place@^1.2"))
        // 감쇠 기준을 넘기려고 1분만 물린다 — 워터마크는 창(24시간) 안이다.
        PostgresSupport.execute(
            "UPDATE consumer_requirement SET last_seen = now() - interval '1 minute'",
        )
        ledger.decay(Duration.ofSeconds(30))

        val answer = query.activeConsumers("pick_place", 1)

        assertTrue(answer is LedgerAnswer.Observed, "$answer")
        assertEquals(0, answer.count)
    }

    @Test
    fun `다른 major를 요구하는 소비자는 안 센다`() {
        ledger.observe("line-controller", "line-a", listOf("pick_place@^2.0"))

        val answer = query.activeConsumers("pick_place", 1)

        assertTrue(answer is LedgerAnswer.Observed, "$answer")
        assertEquals(0, answer.count)
    }

    @Test
    fun `읽을 수 없는 범위는 센다`() {
        // 파싱 실패는 "안 쓴다"의 증거가 아니다. 빼면 그 소비자만 모르는
        // 채로 능력이 사라진다.
        ledger.declare(
            "legacy", ConsumerKind.UPSTREAM_SYSTEM, "line-a", "옛 시스템",
            listOf("pick_place"),
        )

        val answer = query.activeConsumers("pick_place", 1)

        assertTrue(answer is LedgerAnswer.Observed, "$answer")
        assertEquals(1, answer.count)
    }

    @Test
    fun `같은 소비자가 등록과 관측 둘 다여도 한 명이다`() {
        ledger.declare(
            "line-controller", ConsumerKind.CLIENT, "line-a", "라인 제어기",
            listOf("pick_place@^1.2"),
        )
        ledger.observe("line-controller", "line-a", listOf("pick_place@^1.2"))

        val answer = query.activeConsumers("pick_place", 1)

        assertTrue(answer is LedgerAnswer.Observed, "$answer")
        assertEquals(1, answer.count)
    }

    // ── 드레인 조회

    @Test
    fun `태스크가 없으면 0이 아니라 모른다다`() {
        assertTrue(query.inflightTasks("pick_place", 1) is LedgerAnswer.NotObservable)
    }

    @Test
    fun `비종착 태스크를 센다`() {
        seedRobot()
        task("t1", "pick_place", terminal = false)

        val answer = query.inflightTasks("pick_place", 1)

        assertTrue(answer is LedgerAnswer.Observed, "$answer")
        assertEquals(1, answer.count)
    }

    @Test
    fun `종착한 태스크는 안 세지만 관측선은 살아 있다`() {
        seedRobot()
        task("t1", "pick_place", terminal = true)

        val answer = query.inflightTasks("pick_place", 1)

        assertTrue(answer is LedgerAnswer.Observed, "$answer")
        assertEquals(0, answer.count)
    }

    @Test
    fun `다른 스킬의 태스크는 안 센다`() {
        seedRobot()
        task("t1", "navigate_to", terminal = false)

        val answer = query.inflightTasks("pick_place", 1)

        assertTrue(answer is LedgerAnswer.Observed, "$answer")
        assertEquals(0, answer.count)
    }

    @Test
    fun `태스크 관측이 오래되면 모른다다`() {
        seedRobot()
        task("t1", "pick_place", terminal = false)
        PostgresSupport.execute("UPDATE task SET updated_at = now() - interval '48 hours'")

        assertTrue(query.inflightTasks("pick_place", 1) is LedgerAnswer.NotObservable)
    }

    @Test
    fun `창은 주입할 수 있다`() {
        // 24시간을 기다려야 시험할 수 있는 규칙은 결국 안 시험된다.
        ledger.observe("line-controller", "line-a", listOf("pick_place@^1.2"))
        PostgresSupport.execute(
            "UPDATE consumer_requirement SET last_seen = now() - interval '2 minutes'",
        )

        val tight = RegistryLedgerQuery(db, freshness = Duration.ofMinutes(1))

        assertTrue(tight.activeConsumers("pick_place", 1) is LedgerAnswer.NotObservable)
    }

    // ── 씨앗

    /**
     * 태스크는 개정판을 가리킨다(§8.4의 pinning). 그래서 드레인 시험은
     * 개정판 하나가 먼저 있어야 한다 — 없으면 FK가 막고, 그 실패는
     * "드레인을 못 센다"처럼 보인다.
     */
    private fun seedRobot(): Long {
        PostgresSupport.execute(
            "INSERT INTO robot (robot_id, site_id, serial_number) VALUES ('r1','line-a','sn')",
        )
        val stored = revisions.submit(Fixtures.good(), "op")
        assertTrue(stored is SubmitOutcome.Stored, "$stored")
        return stored.profileRevisionId
    }

    private fun task(id: String, skill: String, terminal: Boolean) = PostgresSupport.execute(
        """
        INSERT INTO task (task_id, robot_id, profile_revision_id, skill_type_id,
                          revision, state, terminal)
        SELECT '$id', 'r1', (SELECT max(profile_revision_id) FROM profile_revision),
               s.skill_type_id, 1,
               '${if (terminal) "SUCCEEDED" else "RUNNING"}', $terminal
        FROM skill_type s WHERE s.name = '$skill' AND s.major = 1
        """.trimIndent(),
    )

    private companion object {
        val CONTRACT_SEMVER: String = dev.picasso.contracts.wire.ContractIdentity.semver
    }
}
