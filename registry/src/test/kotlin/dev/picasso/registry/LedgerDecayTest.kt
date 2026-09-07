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
 * 완료 기준 18의 나머지 절반 — **30일 미갱신 시 `active=false`가 되되
 * 삭제되지 않는다.**
 *
 * ## 삭제와 비활성은 다른 사실이다
 *
 * *"계절성 소비자를 지워버리면 원장이 거짓말을 한다"*(§9.2). 지운 소비자는
 * 돌아왔을 때 **새 소비자로 보이고**, 그때 `first_seen`이 오늘이 되어
 * "이 능력은 최근에 쓰기 시작했다"는 틀린 그림이 남는다.
 *
 * ## 경계를 양쪽에서 본다
 *
 * 29일·30일·31일. 한쪽만 보면 **언제나 비활성화하는 구현**과 **아무것도
 * 안 하는 구현**이 각각 절반씩 통과한다.
 */
class LedgerDecayTest {

    private lateinit var db: Db
    private lateinit var ledger: LedgerService
    private var clock: Instant = Instant.parse("2026-09-07T00:00:00Z")

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        ledger = LedgerService(db) { clock }
    }

    private fun rowsOf(skill: String) = ledger.dependents(skill)

    // ── 경계

    @Test
    fun `29일이면 살아 있다`() {
        ledger.observe("a", "line-a", listOf("pick_place@^1.0"))
        clock = clock.plus(Duration.ofDays(29))

        assertEquals(0, ledger.decay(), "29일인데 내려갔다")
        assertEquals(1, ledger.activeConsumerCount("pick_place"))
    }

    @Test
    fun `31일이면 내려간다`() {
        ledger.observe("a", "line-a", listOf("pick_place@^1.0"))
        clock = clock.plus(Duration.ofDays(31))

        assertEquals(1, ledger.decay(), "31일인데 안 내려갔다")
        assertEquals(0, ledger.activeConsumerCount("pick_place"))
    }

    @Test
    fun `정확히 30일이면 아직 살아 있다`() {
        // 경계를 **어느 쪽에 두는지 못 박는다.** 안 박으면 구현이 바뀔 때
        // 아무도 모르게 하루가 이동한다.
        ledger.observe("a", "line-a", listOf("pick_place@^1.0"))
        clock = clock.plus(Duration.ofDays(30))
        assertEquals(0, ledger.decay(), "경계일에 내려갔다")
    }

    // ── 삭제하지 않는다

    @Test
    fun `내려가도 행은 남는다`() {
        ledger.observe("a", "line-a", listOf("pick_place@^1.0"))
        clock = clock.plus(Duration.ofDays(31))
        ledger.decay()

        assertEquals(
            1,
            PostgresSupport.queryOne("SELECT count(*) FROM consumer_requirement") { it.getInt(1) },
            "**행이 사라졌다** — 돌아온 소비자가 새 소비자로 보인다",
        )
        val answer = rowsOf("pick_place")
        assertEquals(emptyList(), answer.active)
        assertEquals(1, answer.dormant.size, "비활성 행이 진단에 안 보인다")
    }

    @Test
    fun `first_seen은 그대로다`() {
        // 되살아났을 때 `first_seen`이 오늘이 되면 "이 능력은 최근에
        // 쓰기 시작했다"는 틀린 그림이 남는다.
        ledger.observe("a", "line-a", listOf("pick_place@^1.0"))
        val first = PostgresSupport.queryOne(
            "SELECT first_seen FROM consumer_requirement",
        ) { it.getTimestamp(1) }

        clock = clock.plus(Duration.ofDays(31))
        ledger.decay()
        ledger.observe("a", "line-a", listOf("pick_place@^1.0"))

        assertEquals(
            first,
            PostgresSupport.queryOne("SELECT first_seen FROM consumer_requirement") {
                it.getTimestamp(1)
            },
            "되살아나면서 처음 본 때가 오늘로 바뀌었다",
        )
    }

    @Test
    fun `다시 관측되면 되살아난다`() {
        // **비활성은 사형이 아니라 조용함의 표시다.** 되살아나지 않으면
        // 계절성 소비자가 돌아와도 축소 판정이 그를 못 본다.
        ledger.observe("a", "line-a", listOf("pick_place@^1.0"))
        clock = clock.plus(Duration.ofDays(31))
        ledger.decay()
        assertEquals(0, ledger.activeConsumerCount("pick_place"))

        ledger.observe("a", "line-a", listOf("pick_place@^1.0"))
        assertEquals(1, ledger.activeConsumerCount("pick_place"), "돌아왔는데 안 살아난다")
    }

    // ── 무엇이 감쇠하는가

    @Test
    fun `DECLARED도 감쇠한다`() {
        // **등록을 영구 면제로 두면** 한 번 등록한 소비자가 영원히 축소를
        // 막고, §9.3의 진입 조건이 관측이 아니라 서류가 된다.
        ledger.declare("MES-A", ConsumerKind.UPSTREAM_SYSTEM, "line-a", "MES", listOf("pick_place@^1.0"))
        clock = clock.plus(Duration.ofDays(31))

        assertEquals(1, ledger.decay(), "등록한 요구가 안 내려간다")
        assertEquals(0, ledger.activeConsumerCount("pick_place"))
    }

    @Test
    fun `조용한 것만 내려간다`() {
        // 언제나 전부 내려보내는 구현을 막는다.
        ledger.observe("조용한", "line-a", listOf("pick_place@^1.0"))
        clock = clock.plus(Duration.ofDays(31))
        ledger.observe("살아있는", "line-a", listOf("pick_place@^1.0"))

        assertEquals(1, ledger.decay())
        assertEquals(
            listOf("살아있는"), rowsOf("pick_place").active.map { it.consumerId },
        )
        assertEquals(listOf("조용한"), rowsOf("pick_place").dormant.map { it.consumerId })
    }

    @Test
    fun `두 번 돌려도 같은 행을 다시 세지 않는다`() {
        // 감쇠가 멱등이어야 잡 실행 횟수가 화면의 숫자를 바꾸지 않는다.
        ledger.observe("a", "line-a", listOf("pick_place@^1.0"))
        clock = clock.plus(Duration.ofDays(31))

        assertEquals(1, ledger.decay())
        assertEquals(0, ledger.decay(), "이미 내려간 것을 또 셌다")
    }

    // ── 진단 5번

    @Test
    fun `진단 5번이 산 것과 조용한 것을 갈라 낸다`() {
        // **0이 된 이유를 운영자가 갈라 봐야 한다** — "아무도 안 쓴다"와
        // "다들 조용하다"는 다른 사실이고, 후자는 돌아올 수 있다는 뜻이다.
        ledger.declare("MES-A", ConsumerKind.UPSTREAM_SYSTEM, "line-a", "MES", listOf("pick_place@^1.0"))
        clock = clock.plus(Duration.ofDays(31))
        ledger.observe("WCS-B", "line-a", listOf("pick_place@^1.2"))
        ledger.decay()

        val answer = ledger.dependents("pick_place")
        assertEquals("pick_place", answer.skillTypeName)
        assertEquals(listOf("WCS-B"), answer.active.map { it.consumerId })
        assertEquals(listOf("MES-A"), answer.dormant.map { it.consumerId })
        assertEquals(RequirementSource.OBSERVED, answer.active.single().source)
        assertEquals("^1.2", answer.active.single().versionRange)
    }

    @Test
    fun `아무도 안 쓰는 능력은 빈 답이다`() {
        assertEquals(emptyList(), ledger.dependents("inspect").active)
        assertEquals(emptyList(), ledger.dependents("inspect").dormant)
        assertEquals(0, ledger.activeConsumerCount("inspect"))
    }

    @Test
    fun `다른 능력의 소비자가 섞이지 않는다`() {
        ledger.observe("a", "line-a", listOf("pick_place@^1.0"))
        ledger.observe("b", "line-a", listOf("navigate_to@^1.0"))

        assertEquals(listOf("a"), ledger.dependents("pick_place").active.map { it.consumerId })
        assertEquals(listOf("b"), ledger.dependents("navigate_to").active.map { it.consumerId })
        assertTrue(ledger.dependents("inspect").active.isEmpty())
    }
}
