package dev.picasso.registry

import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.registry.binding.DiscoveredRobot
import dev.picasso.registry.binding.RobotOrigin
import dev.picasso.registry.binding.RobotRegistration
import dev.picasso.registry.binding.RobotRegistrationOutcome
import dev.picasso.registry.binding.RobotStatus
import dev.picasso.registry.ingest.LivenessOutcome
import dev.picasso.registry.ingest.LivenessService
import dev.picasso.registry.store.Db
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR 37 — **등록이 두 갈래이고, 그 갈래는 문이 정한다.**
 *
 * 이 파일에서 가장 중요한 시험은 [`다른 문으로 다시 들이지 않는다`] 와 [`사람의 말은 기체가 답해야 확인이 된다`] 둘이다.
 * 앞은 출처가 두 번째 진실이 되는 것을 막고, 뒤는 오타 난 `robot_id` 로 선언한 화면이 초록으로 보이는 것을 막는다
 * (ADR 35가 사이트 이름에서 세운 규율과 같다).
 */
class RobotRegistrationTest {

    private lateinit var db: Db
    private lateinit var robots: RobotRegistration
    private lateinit var liveness: LivenessService

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        robots = RobotRegistration(db)
        liveness = LivenessService(db)
    }

    private fun declare(id: String, site: String = "line-a", serial: String = "sn-$id", endpoint: String? = null) =
        robots.declare(id, site, serial, displayName = null, endpoint = endpoint, actor = "operator-1")

    private fun report(id: String) {
        val outcome = liveness.record(
            MessageHeader.newBuilder().setRobotId(id).build(),
            ConnectionState.CONNECTION_STATE_ONLINE,
            software = null,
        )
        assertTrue(outcome is LivenessOutcome.Recorded, "$outcome")
    }

    // ── 사람의 말과 기체의 답

    @Test
    fun `사람의 말은 기체가 답해야 확인이 된다`() {
        val outcome = assertIs<RobotRegistrationOutcome.Registered>(declare("r1"))
        assertEquals(RobotStatus.CLAIMED, outcome.status, "선언한 직후가 확인이면 오타 난 기체를 아무도 못 잡는다")
        assertEquals(RobotStatus.CLAIMED, robots.statusOf("r1"))

        report("r1")
        assertEquals(RobotStatus.CONFIRMED, robots.statusOf("r1"), "기체가 답했는데 여전히 사람의 말로 남았다")
    }

    @Test
    fun `발견된 기체에는 사람의 말 단계가 없다`() {
        // 어댑터가 플릿에서 본 것이므로 처음부터 관측이다 — 그래도 우리 계약으로 닿은 적은 없다.
        val outcome = robots.discover("line-a", listOf(DiscoveredRobot("r2", "sn-r2")))
        assertEquals(listOf("r2"), outcome.recorded)
        assertEquals(emptyMap(), outcome.refused)
        assertEquals(RobotStatus.DISCOVERED, robots.statusOf("r2"))

        report("r2")
        assertEquals(RobotStatus.CONFIRMED, robots.statusOf("r2"), "출처와 무관하게 기체가 답하면 확인이다")
    }

    @Test
    fun `없는 기체와 문 밖에서 들어온 기체는 다르다`() {
        assertNull(robots.statusOf("nobody"), "없는 기체와 출처 없는 기체를 접으면 안 된다")

        // 시험 픽스처가 SQL 로 넣은 행. **기본값으로 덮지 않는다** — 덮으면 진짜 선언과 구별되지 않는다.
        PostgresSupport.execute("INSERT INTO robot (robot_id, site_id, serial_number) VALUES ('r3','line-a','sn-r3')")
        assertEquals(RobotStatus.UNREGISTERED, robots.statusOf("r3"))

        // 문을 지나면 출처를 갖는다 — 비어 있던 행은 어느 문의 것도 아니었으므로 입양할 수 있다.
        assertIs<RobotRegistrationOutcome.Updated>(declare("r3"))
        assertEquals(RobotStatus.CLAIMED, robots.statusOf("r3"))
    }

    // ── 문이 곧 출처다

    @Test
    fun `다른 문으로 다시 들이지 않는다`() {
        // 선언한 기체를 어댑터가 발견해 올린다.
        declare("r1")
        val discovery = robots.discover("line-a", listOf(DiscoveredRobot("r1", "sn-r1")))
        assertEquals(emptyList(), discovery.recorded)
        assertTrue("DECLARED" in discovery.refused.getValue("r1"), discovery.refused.toString())
        // 출처는 그대로다 — 거절이 조용히 갱신으로 새면 "왜 여기 있는가" 에 답할 수 없다.
        assertEquals(RobotOrigin.DECLARED, originOf("r1"))

        // **반대 방향도 막는다.** 한쪽만 보면 문을 하나로 합쳐도 통과한다.
        robots.discover("line-a", listOf(DiscoveredRobot("r2", "sn-r2")))
        val wrong = assertIs<RobotRegistrationOutcome.WrongDoor>(declare("r2", serial = "sn-r2"))
        assertEquals(RobotOrigin.DISCOVERED, wrong.origin)
        assertEquals(RobotOrigin.DISCOVERED, originOf("r2"))
    }

    @Test
    fun `같은 문으로 다시 들이면 사실이 갱신된다`() {
        declare("r1", endpoint = "10.0.0.1:50051")
        val again = assertIs<RobotRegistrationOutcome.Updated>(
            robots.declare("r1", "line-a", "sn-r1", displayName = "픽 셀 A", endpoint = "10.0.0.2:50051", actor = "operator-2"),
        )
        assertEquals(RobotStatus.CLAIMED, again.status)
        assertEquals(
            listOf("픽 셀 A", "10.0.0.2:50051", "operator-2"),
            PostgresSupport.queryAll("SELECT display_name, endpoint, registered_by FROM robot WHERE robot_id = 'r1'") {
                listOf(it.getString(1), it.getString(2), it.getString(3))
            }.single(),
        )
    }

    // ── 접속 정보는 직결에서만 우리가 갖는다 (결정 4)

    @Test
    fun `발견된 기체의 접속 정보는 받지 않고, 나머지는 들인다`() {
        val outcome = robots.discover(
            "line-a",
            listOf(
                DiscoveredRobot("r1", "sn-r1", endpoint = "10.0.0.1:50051"),
                DiscoveredRobot("r2", "sn-r2"),
            ),
        )
        // **부분 성공이다.** 하나가 거절돼 목록 전체가 멈추면, 그 멈춤은 "플릿에서 사라졌다" 와 화면에서 구별되지 않는다.
        assertEquals(listOf("r2"), outcome.recorded)
        assertTrue("플릿이 갖는다" in outcome.refused.getValue("r1"), outcome.refused.toString())
        assertNull(robots.statusOf("r1"), "거절된 기체가 들어왔다")

        // 스키마도 같은 것을 막는다 — 서비스를 한 줄 고쳐 새는 것을 표가 잡는다.
        val violated = runCatching {
            PostgresSupport.execute(
                "INSERT INTO robot (robot_id, site_id, serial_number, origin, endpoint) " +
                    "VALUES ('r9','line-a','sn-r9','DISCOVERED','10.0.0.9:50051')",
            )
        }
        assertTrue(violated.isFailure, "발견된 기체가 접속 정보를 갖는 것을 표가 안 막았다")
    }

    @Test
    fun `선언은 접속 정보를 갖고, 없어도 된다`() {
        declare("r1", endpoint = "10.0.0.1:50051")
        declare("r2")
        assertEquals(
            listOf("10.0.0.1:50051", null),
            listOf("r1", "r2").map {
                PostgresSupport.queryOne("SELECT endpoint FROM robot WHERE robot_id = '$it'") { rs -> rs.getString(1) }
            },
        )
    }

    // ── 거절

    @Test
    fun `같은 사이트에 같은 일련번호는 둘일 수 없다`() {
        declare("r1", serial = "sn-x")
        val rejected = assertIs<RobotRegistrationOutcome.Rejected>(declare("r2", serial = "sn-x"))
        assertTrue("r1" in rejected.detail, rejected.detail)
    }

    @Test
    fun `빈 값은 거절한다`() {
        assertIs<RobotRegistrationOutcome.Rejected>(robots.declare("", "line-a", "sn", actor = "op"))
        assertIs<RobotRegistrationOutcome.Rejected>(robots.declare("r1", "", "sn", actor = "op"))
        assertIs<RobotRegistrationOutcome.Rejected>(robots.declare("r1", "line-a", "", actor = "op"))
        assertIs<RobotRegistrationOutcome.Rejected>(robots.declare("r1", "line-a", "sn", actor = ""))
    }

    // ── 감사와 진단

    @Test
    fun `두 문이 감사 로그에 다른 조작으로 남는다`() {
        declare("r1")
        robots.discover("line-a", listOf(DiscoveredRobot("r2", "sn-r2")))

        assertEquals(
            listOf("ROBOT_DECLARED|operator-1|r1", "ROBOT_DISCOVERED|adapter-discovery|r2"),
            PostgresSupport.queryAll(
                "SELECT operation, actor, subject FROM audit_log WHERE operation LIKE 'ROBOT_%' ORDER BY subject",
            ) { "${it.getString(1)}|${it.getString(2)}|${it.getString(3)}" },
        )
    }

    @Test
    fun `진단이 출처와 상태를 함께 낸다`() {
        declare("r1", endpoint = "10.0.0.1:50051")
        robots.discover("line-b", listOf(DiscoveredRobot("r2", "sn-r2")))
        report("r2")

        val rows = robots.list()
        assertEquals(listOf("r1", "r2"), rows.map { it.robotId })
        assertEquals(listOf(RobotOrigin.DECLARED, RobotOrigin.DISCOVERED), rows.map { it.origin })
        assertEquals(listOf(RobotStatus.CLAIMED, RobotStatus.CONFIRMED), rows.map { it.status })
        assertEquals("operator-1", rows.first().registeredBy)
        assertNull(rows.first().lastReportedAt, "답한 적 없는 기체에 보고 시각이 있다")

        assertEquals(listOf("r2"), robots.list(siteId = "line-b").map { it.robotId })
    }

    private fun originOf(robotId: String): RobotOrigin? =
        PostgresSupport.queryOne("SELECT origin FROM robot WHERE robot_id = '$robotId'") { it.getString(1) }
            ?.let(RobotOrigin::valueOf)
}
