package dev.picasso.registry

import dev.picasso.registry.ledger.ConsumerKind
import dev.picasso.registry.ledger.LedgerService
import dev.picasso.registry.ledger.RequirementSource
import dev.picasso.registry.store.Db
import java.time.Duration
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 완료 기준 18 — **등록하지 않은 소비자도 협상 성공에서 `OBSERVED`로
 * 잡힌다. 30일 미갱신 시 `active=false`가 되되 삭제되지 않는다.**
 *
 * ## 왜 이것이 중요한가
 *
 * *"원장 없이 능력을 지우는 것은 '아무도 안 쓰겠지'이고, 원장이 있으면
 * '쓰는 사람 0명임을 관측했다'이다. 전자는 사고가 나고 후자는 안
 * 난다"*(§9.2).
 *
 * 그래서 이 시험들이 실제로 지키는 것은 **완료 기준 19의 축소 거부**다.
 * 원장이 조용히 비면 거기가 "0명"이라 답하고, 아직 쓰는 소비자가 살아
 * 있는 능력이 지워진다.
 */
class LedgerTest {

    private lateinit var db: Db
    private lateinit var ledger: LedgerService
    private var clock: Instant = Instant.parse("2026-09-07T00:00:00Z")

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        ledger = LedgerService(db) { clock }
    }

    private fun consumerRow(id: String): Triple<String, String, Boolean> =
        PostgresSupport.queryOne(
            "SELECT kind, display_name, registered FROM consumer WHERE consumer_id = '$id'",
        ) { Triple(it.getString(1), it.getString(2), it.getBoolean(3)) }

    // ── 등록

    @Test
    fun `등록하면 요구가 원장에 오른다`() {
        ledger.declare(
            "MES-A", ConsumerKind.UPSTREAM_SYSTEM, "line-a", "MES",
            listOf("pick_place@^1.2", "navigate_to@^1.0"),
        )

        val answer = ledger.dependents("pick_place")
        val row = answer.active.single()
        assertEquals("MES-A", row.consumerId)
        assertEquals(RequirementSource.DECLARED, row.source)
        assertEquals(
            "^1.2", row.versionRange,
            "요구 문자열이 원문 그대로 안 남았다 — 해석은 게이트가 한다",
        )
        assertEquals(1, ledger.activeConsumerCount("navigate_to"))
        assertEquals(0, ledger.activeConsumerCount("inspect"))
    }

    @Test
    fun `등록은 registered를 올린다`() {
        ledger.declare("MES-A", ConsumerKind.UPSTREAM_SYSTEM, "line-a", "MES", listOf("x@^1"))
        assertEquals(Triple("UPSTREAM_SYSTEM", "MES", true), consumerRow("MES-A"))
    }

    @Test
    fun `범위가 없는 요구도 잃지 않는다`() {
        // **파싱 실패로 적재를 포기하면 §9.3의 조회가 그 소비자를 0으로
        // 세고, 그 위에서 축소가 승인된다.** 덜 정확한 채 남는 편이 낫다.
        ledger.declare("MES-A", ConsumerKind.UPSTREAM_SYSTEM, "line-a", "MES", listOf("pick_place"))
        assertEquals(1, ledger.activeConsumerCount("pick_place"))
        assertEquals("", ledger.dependents("pick_place").active.single().versionRange)
    }

    // ── 관측

    @Test
    fun `등록 안 한 소비자도 관측으로 잡힌다`() {
        // **완료 기준 18의 앞 절반.** 이것이 없으면 원장은 "성실한
        // 소비자만" 담고, 축소 판정이 나머지를 못 본다.
        ledger.observe("WCS-B", "line-a", listOf("pick_place@^1.2"))

        assertEquals(1, ledger.activeConsumerCount("pick_place"))
        assertEquals(
            Triple("CLIENT", "WCS-B", false), consumerRow("WCS-B"),
            "자동 생성이 §8.3의 규정과 다르다",
        )
        assertEquals(
            RequirementSource.OBSERVED,
            ledger.dependents("pick_place").active.single().source,
        )
    }

    @Test
    fun `등록과 관측이 같은 소비자에 공존한다`() {
        // **PK에 `source`가 없으면 한 행이 다른 행을 덮고, 등록을 지웠을
        // 때 관측 기록까지 사라진다**(§8.3 결정 6).
        ledger.declare("MES-A", ConsumerKind.UPSTREAM_SYSTEM, "line-a", "MES", listOf("pick_place@^1.0"))
        ledger.observe("MES-A", "line-a", listOf("pick_place@^1.2"))

        val active = ledger.dependents("pick_place").active
        assertEquals(2, active.size, "두 source가 공존하지 않는다: $active")
        assertEquals(
            listOf(RequirementSource.DECLARED, RequirementSource.OBSERVED),
            active.map { it.source },
        )
        assertEquals(
            1, ledger.activeConsumerCount("pick_place"),
            "**소비자 하나를 둘로 센다** — 화면의 숫자가 실제 소비자 수와 달라진다",
        )
    }

    @Test
    fun `관측이 등록 정보를 덮어쓰지 않는다`() {
        // 덮어쓰면 사람이 적어 넣은 kind·display_name이 client_id로
        // 되돌아가고, 다음 화면에서 MES가 사라진 것처럼 보인다.
        ledger.declare("MES-A", ConsumerKind.UPSTREAM_SYSTEM, "line-a", "MES", listOf("x@^1"))
        ledger.observe("MES-A", "line-a", listOf("x@^1"))
        assertEquals(Triple("UPSTREAM_SYSTEM", "MES", true), consumerRow("MES-A"))
    }

    @Test
    fun `등록이 관측 뒤에 와도 된다`() {
        // 정상 경로다 — 관측이 먼저 자동 생성하고 나중에 등록한다.
        ledger.observe("WCS-B", "line-a", listOf("pick_place@^1.2"))
        ledger.declare("WCS-B", ConsumerKind.CLIENT, "line-a", "WCS", listOf("pick_place@^1.2"))
        assertEquals(Triple("CLIENT", "WCS", true), consumerRow("WCS-B"))
        assertEquals(2, ledger.dependents("pick_place").active.size)
    }

    @Test
    fun `다시 관측하면 행이 늘지 않고 시각만 오른다`() {
        // **행을 새로 만들면 30일 감쇠가 언제나 갓 만들어진 행을 보고
        // 아무것도 비활성화하지 않는다** — 원장이 "다 살아 있다"고만 답한다.
        ledger.observe("WCS-B", "line-a", listOf("pick_place@^1.2"))
        val first = ledger.dependents("pick_place").active.single().lastSeen

        clock = clock.plus(Duration.ofDays(1))
        ledger.observe("WCS-B", "line-a", listOf("pick_place@^1.2"))

        val rows = ledger.dependents("pick_place").active
        assertEquals(1, rows.size, "관측이 행을 늘렸다")
        assertTrue(rows.single().lastSeen.isAfter(first), "마지막으로 본 때가 안 올랐다")
    }

    @Test
    fun `여러 소비자가 같은 능력을 쓰면 각각 센다`() {
        ledger.observe("a", "line-a", listOf("pick_place@^1.0"))
        ledger.observe("b", "line-a", listOf("pick_place@^1.0"))
        ledger.declare("c", ConsumerKind.CLIENT, "line-a", "c", listOf("pick_place@^1.0"))
        assertEquals(3, ledger.activeConsumerCount("pick_place"))
    }
}
