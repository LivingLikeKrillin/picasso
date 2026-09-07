package dev.picasso.registry

import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.registry.ingest.LivenessOutcome
import dev.picasso.registry.ingest.LivenessService
import dev.picasso.registry.store.Db
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 기체가 살아 있다는 사실의 적재.
 *
 * **여기서 보는 것은 "적재가 되는가"가 아니라 "관측선이 거짓말을 안 하는가"다.**
 * 이 표는 §9.3의 두 조회 앞에 서고, 그것이 틀리면 아직 쓰는 능력의 제거가
 * 승인된다.
 *
 * 특히 셋을 붙든다.
 *
 *  - **시각의 주인은 registry다.** 발신자가 실은 것을 쓰면 §10.3의 VIRTUAL
 *    시계와 충돌해 하트비트가 임의로 낡거나 신선해 보인다.
 *  - **epoch는 뒤로 안 간다.** 늦게 온 옛 값이 새 값을 덮으면 축소 완료
 *    검증이 되돌아간다.
 *  - **연결 상태는 덮는다.** 최댓값이 아니다 — 지금 상태가 지금 상태다.
 */
class LivenessTest {

    private lateinit var db: Db
    private lateinit var liveness: LivenessService
    private var clock: Instant = Instant.parse("2026-09-08T09:00:00Z")

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        liveness = LivenessService(db) { clock }
        PostgresSupport.execute(
            "INSERT INTO robot (robot_id, site_id, serial_number) VALUES ('r1','line-a','sn1')",
        )
    }

    @Test
    fun `첫 보고가 행을 만든다`() {
        val outcome = liveness.record(header("r1"), ConnectionState.CONNECTION_STATE_ONLINE, "4.1.0")

        assertTrue(outcome is LivenessOutcome.Recorded, "$outcome")
        assertEquals(clock, reportedAt("r1"))
        assertEquals("CONNECTION_STATE_ONLINE", state("r1"))
        assertEquals("4.1.0", software("r1"))
    }

    @Test
    fun `시각은 발신자가 아니라 registry가 정한다`() {
        // **발신자가 하루 전을 싣는다.** VIRTUAL 시계에서 실제로 일어나는
        // 일이며(§10.3), 그 값을 믿으면 방금 온 보고가 하루 묵어 보인다.
        val stale = clock.minusSeconds(86_400).toString()
        liveness.record(header("r1", occurredAt = stale), ConnectionState.CONNECTION_STATE_ONLINE, null)

        assertEquals(clock, reportedAt("r1"), "발신자의 occurred_at 이 새어 들어왔다")
    }

    @Test
    fun `등록되지 않은 기체는 거절한다`() {
        // 오타 난 robot_id 가 관측선을 살아 있게 만들면 그것이 정확히 이
        // 표가 막으려던 것이다. FK 가 아니라 **사유가 있는 거절**로 낸다.
        val outcome = liveness.record(header("r-typo"), ConnectionState.CONNECTION_STATE_ONLINE, null)

        assertTrue(outcome is LivenessOutcome.Rejected, "$outcome")
        assertTrue("r-typo" in outcome.reason, "사유에 기체가 없다: ${outcome.reason}")
    }

    @Test
    fun `헤더에 robot_id가 없으면 거절한다`() {
        val outcome = liveness.record(header(""), ConnectionState.CONNECTION_STATE_ONLINE, null)

        assertTrue(outcome is LivenessOutcome.Rejected, "$outcome")
    }

    @Test
    fun `epoch는 최댓값을 유지한다`() {
        // **두 값이 달라야 upsert 가 무엇을 했는지 구분된다.**
        liveness.record(header("r1", epoch = 7), ConnectionState.CONNECTION_STATE_ONLINE, null)
        liveness.record(header("r1", epoch = 3), ConnectionState.CONNECTION_STATE_ONLINE, null)

        assertEquals(7L, epoch("r1"), "늦게 온 옛 epoch 가 새 값을 덮었다")
    }

    @Test
    fun `연결 상태는 마지막 보고로 덮는다`() {
        liveness.record(header("r1"), ConnectionState.CONNECTION_STATE_ONLINE, null)
        liveness.record(header("r1"), ConnectionState.CONNECTION_STATE_HIBERNATING, null)

        assertEquals("CONNECTION_STATE_HIBERNATING", state("r1"), "지금 상태가 지금 상태여야 한다")
    }

    @Test
    fun `못 읽는 보고가 이미 읽은 소프트웨어를 지우지 않는다`() {
        liveness.record(header("r1"), ConnectionState.CONNECTION_STATE_ONLINE, "4.1.0")
        liveness.record(header("r1"), ConnectionState.CONNECTION_STATE_ONLINE, null)

        assertEquals("4.1.0", software("r1"), "null 보고가 기존 값을 덮었다")
    }

    @Test
    fun `한 번도 못 읽었으면 빈 문자열이 아니라 NULL이다`() {
        liveness.record(header("r1"), ConnectionState.CONNECTION_STATE_ONLINE, null)

        assertNull(software("r1"), "못 읽는 기종이 '버전이 비어 있다'로 보인다")
    }

    // ── 헬퍼

    private fun header(
        robotId: String,
        epoch: Long = 1,
        occurredAt: String = clock.toString(),
    ): MessageHeader = MessageHeader.newBuilder()
        .setRobotId(robotId)
        .setCapabilityEpoch(epoch)
        .setOccurredAt(occurredAt)
        .build()

    private fun reportedAt(id: String): Instant =
        PostgresSupport.queryOne(
            "SELECT last_reported_at FROM robot_liveness WHERE robot_id = '$id'",
        ) { it.getTimestamp(1).toInstant() }

    private fun state(id: String): String =
        PostgresSupport.queryOne(
            "SELECT connection_state FROM robot_liveness WHERE robot_id = '$id'",
        ) { it.getString(1) }

    private fun epoch(id: String): Long =
        PostgresSupport.queryOne(
            "SELECT capability_epoch FROM robot_liveness WHERE robot_id = '$id'",
        ) { it.getLong(1) }

    private fun software(id: String): String? =
        PostgresSupport.queryOne(
            "SELECT robot_software FROM robot_liveness WHERE robot_id = '$id'",
        ) { it.getString(1) }
}
