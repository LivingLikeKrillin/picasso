package dev.picasso.registry

import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.ProfileRef
import dev.picasso.contracts.v1.Reference
import dev.picasso.contracts.v1.Rejection
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.registry.observe.EpochCause
import dev.picasso.registry.observe.ObservationService
import dev.picasso.registry.observe.Recorded
import dev.picasso.registry.store.Db
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 관측 적재 — 진단 3번과 4번이 서는 바닥.
 *
 * **적재가 판정하지 않는다는 것을 시험이 지킨다.** 어떤 거절 코드도 특별
 * 취급하지 않고, 어떤 헤더도 고쳐 적지 않는다. 여기서 판정을 시작하면
 * 화면이 로봇과 다른 말을 하는 날이 온다.
 */
class ObservationTest {

    private lateinit var db: Db
    private lateinit var observations: ObservationService

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        observations = ObservationService(db)
        robot("r1")
        robot("r2")
    }

    private fun robot(id: String) = db.transaction { c ->
        c.prepareStatement(
            "INSERT INTO robot (robot_id, site_id, serial_number) VALUES (?, 'line-a', ?)",
        ).use { it.setString(1, id); it.setString(2, "sn-$id"); it.executeUpdate() }
    }

    private fun header(
        robotId: String = "r1",
        epoch: Long = 1,
        profileId: String = "fixture/minimal",
        revision: Int = 1,
    ): MessageHeader = MessageHeader.newBuilder()
        .setRobotId(robotId)
        .setCapabilityEpoch(epoch)
        .setProfileRef(
            ProfileRef.newBuilder().setProfileId(profileId).setRevision(revision),
        )
        .build()

    private fun rows(): Int =
        PostgresSupport.queryOne("SELECT count(*) FROM capability_epoch_log") { it.getInt(1) }

    // ── 접기

    @Test
    fun `같은 사실을 두 번 적으면 한 줄이다`() {
        // 폴링과 발행이 같은 사실을 반복해 준다. 그대로 쌓으면 진단 3번의
        // 이력이 안 바뀐 줄로 가득 차고 **사유가 묻힌다.**
        assertEquals(Recorded.INSERTED, observations.recordHeader(header()))
        assertEquals(Recorded.FOLDED, observations.recordHeader(header()))
        assertEquals(1, rows())
    }

    @Test
    fun `접혀도 시각은 갱신된다`() {
        // 접는 것과 버리는 것은 다르다. 마지막으로 그 상태를 본 시각이
        // 사라지면 "언제부터 조용한가"에 답할 수 없다.
        //
        // **시각을 한 시간 뒤로 밀어 놓고 접는다.** 두 번 그냥 적고
        // `!second.before(first)`로 보면 **같음이 허용되어** 갱신을 아예
        // 안 하는 구현이 통과한다(실측 — 그 결함이 안 잡혔다). 그렇다고
        // `after`로 바꾸면 두 트랜잭션의 `now()`가 같은 눈금에 떨어질 때
        // 흔들린다. 한 시간은 어떤 시계 해상도보다 크므로 결정적이다.
        observations.recordHeader(header())
        PostgresSupport.execute(
            "UPDATE capability_epoch_log SET occurred_at = occurred_at - interval '1 hour'",
        )
        val stale = PostgresSupport.queryOne(
            "SELECT occurred_at FROM capability_epoch_log",
        ) { it.getTimestamp(1) }

        assertEquals(Recorded.FOLDED, observations.recordHeader(header()), "접히지 않았다")
        val folded = PostgresSupport.queryOne(
            "SELECT occurred_at FROM capability_epoch_log",
        ) { it.getTimestamp(1) }

        assertTrue(
            folded.after(stale),
            "접었는데 시각이 그대로다 — 마지막으로 본 때를 잃었다: $stale → $folded",
        )
    }

    @Test
    fun `세대가 다르면 새 줄이다`() {
        observations.recordHeader(header(epoch = 1))
        assertEquals(Recorded.INSERTED, observations.recordHeader(header(epoch = 2)))
        assertEquals(2, rows())
    }

    @Test
    fun `세대가 같아도 개정판이 다르면 새 줄이다`() {
        // **카나리 전환의 관측 지점이다.** 세대만 보고 접으면 완료 기준
        // 20이 볼 것이 사라진다 — 개정판이 바뀌었는데 이력에 아무 흔적이
        // 없게 된다.
        observations.recordHeader(header(epoch = 7, revision = 1))
        assertEquals(
            Recorded.INSERTED,
            observations.recordHeader(header(epoch = 7, revision = 2)),
            "같은 세대에서 개정판만 바뀐 것이 접혔다",
        )
        assertEquals(2, rows())
    }

    @Test
    fun `사유가 다르면 새 줄이다`() {
        observations.recordHeader(header(), EpochCause.BINDING_CHANGED)
        assertEquals(
            Recorded.INSERTED,
            observations.recordHeader(header(), EpochCause.RUNTIME_DEGRADED),
            "사유가 달라졌는데 접혔다 — 진단 3번이 답할 것이 사유다",
        )
    }

    @Test
    fun `기체가 다르면 새 줄이다`() {
        observations.recordHeader(header(robotId = "r1"))
        assertEquals(Recorded.INSERTED, observations.recordHeader(header(robotId = "r2")))
        assertEquals(2, rows())
    }

    @Test
    fun `사유 넷이 각각 적힌다`() {
        // 망라. 하나라도 못 쓰면 그 사건은 이력에서 통째로 사라진다.
        EpochCause.entries.forEachIndexed { i, cause ->
            assertEquals(
                Recorded.INSERTED,
                observations.recordHeader(header(epoch = i.toLong()), cause),
                "$cause 를 못 적었다",
            )
        }
        assertEquals(EpochCause.entries.size, rows())
    }

    // ── 거절

    @Test
    fun `거절 다섯 코드가 각각 적힌다`() {
        // **어떤 코드도 특별 취급하지 않는다.** 하나를 접거나 다르게 다루면
        // 그 거절만 화면에서 사라지고, 그것이 하필 운영자가 찾던 것이 된다.
        val five = listOf(
            RejectionCode.REJECTION_CODE_MAJOR_MISMATCH,
            RejectionCode.REJECTION_CODE_SKILL_ABSENT,
            RejectionCode.REJECTION_CODE_REQUIRED_OPTIONAL_MISSING,
            RejectionCode.REJECTION_CODE_LIMIT_EXCEEDED,
            RejectionCode.REJECTION_CODE_IDENTITY_MISMATCH,
        )
        five.forEach { code ->
            observations.recordRejection(
                "r1", "line-controller", listOf("pick_place@^1.2"),
                Rejection.newBuilder().setCode(code).setDetail("사유").build(),
            )
        }

        val stored = PostgresSupport.queryAll(
            "SELECT reason_code FROM handshake_rejection ORDER BY rejection_id",
        ) { it.getString(1) }
        assertEquals(five.map { it.name }, stored)
    }

    @Test
    fun `요구 문자열이 원문 그대로 남는다`() {
        observations.recordRejection(
            "r1", "line-controller", listOf("pick_place@^1.2", "navigate_to@^2.0"),
            Rejection.newBuilder()
                .setCode(RejectionCode.REJECTION_CODE_SKILL_ABSENT)
                .setDetail("선언하지 않은 스킬이다")
                .build(),
        )
        val requirement = PostgresSupport.queryOne(
            "SELECT requirement::text FROM handshake_rejection",
        ) { it.getString(1) }
        assertTrue("pick_place@^1.2" in requirement, requirement)
        assertTrue("navigate_to@^2.0" in requirement, requirement)
    }

    @Test
    fun `따옴표가 든 사유가 적재를 깨뜨리지 않는다`() {
        // 거절 사유에는 `"`가 흔하다(`요구='pick_place@^1.2'`). 이스케이프를
        // 빠뜨리면 그 거절 하나가 적재를 통째로 깨뜨리고, 하필 그것이
        // 운영자가 찾던 거절이다.
        val nasty = """요구="pick_place@^1.2", 로봇=[1.0]\n둘째 줄"""
        observations.recordRejection(
            "r1", """client"with"quotes""", listOf(nasty),
            Rejection.newBuilder()
                .setCode(RejectionCode.REJECTION_CODE_LIMIT_EXCEEDED)
                .setDetail(nasty)
                .build(),
        )
        val detail = PostgresSupport.queryOne(
            "SELECT detail->>'detail' FROM handshake_rejection",
        ) { it.getString(1) }
        assertEquals(nasty, detail, "사유가 왜곡돼 저장됐다")
    }

    @Test
    fun `참조가 함께 남는다`() {
        // 어느 스킬 때문에 거절당했는지가 없으면 진단 4번이 "무엇을
        // 고쳐야 하나"에 답하지 못한다.
        observations.recordRejection(
            "r1", "line-controller", listOf("pick_place@^9.0"),
            Rejection.newBuilder()
                .setCode(RejectionCode.REJECTION_CODE_MAJOR_MISMATCH)
                .setDetail("major가 다르다")
                .addReferences(
                    Reference.newBuilder()
                        .setKey(Reference.Key.KEY_SKILL_ID)
                        .setValue("pick_place"),
                )
                .setCapabilityEpoch(4)
                .build(),
        )
        val detail = PostgresSupport.queryOne(
            "SELECT detail::text FROM handshake_rejection",
        ) { it.getString(1) }
        assertTrue("pick_place" in detail, detail)
        assertTrue("\"capability_epoch\": 4" in detail || "\"capability_epoch\":4" in detail, detail)
    }

    @Test
    fun `등록 안 된 기체의 거절도 적힌다`() {
        // FK를 걸면 이 줄이 **적재에 실패해 조용히 사라진다.** 그런데
        // 등록 안 된 기체가 협상을 거절당하고 있다는 것 자체가 운영자가
        // 알아야 할 사실이다.
        observations.recordRejection(
            "등록된-적-없는-기체", "line-controller", listOf("pick_place@^1.0"),
            Rejection.newBuilder()
                .setCode(RejectionCode.REJECTION_CODE_SKILL_ABSENT)
                .setDetail("사유").build(),
        )
        assertEquals(
            1,
            PostgresSupport.queryOne("SELECT count(*) FROM handshake_rejection") { it.getInt(1) },
        )
    }
}
