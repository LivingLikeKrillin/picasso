package dev.picasso.registry

import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.registry.binding.DiscoveredRobot
import dev.picasso.registry.binding.RobotOrigin
import dev.picasso.registry.binding.RobotRegistration
import dev.picasso.registry.binding.RetirementOutcome
import dev.picasso.registry.binding.RobotRegistrationOutcome
import dev.picasso.registry.binding.RobotStatus
import dev.picasso.registry.ingest.LivenessOutcome
import dev.picasso.registry.ingest.LivenessService
import dev.picasso.registry.store.Db
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    // ── 나가는 문(퇴역)

    @Test
    fun `퇴역시켜도 행은 남고 목록에서만 빠진다`() {
        declare("r-old")
        report("r-old")
        assertEquals(1, robots.list("line-a").size)

        val outcome = assertIs<RetirementOutcome.Retired>(robots.retire("r-old", "폐기", actor = "operator-1"))
        assertFalse(outcome.alreadyWas)

        // **지운 것이 아니다.** 상태를 물으면 답이 있고, 이력을 켜면 목록에도 있다 — 태스크 관측과 감사 로그가
        // 이 기체에 매달려 있으므로 행이 사라지면 지난달 무엇이 돌았는지 못 묻는다.
        assertEquals(RobotStatus.RETIRED, robots.statusOf("r-old"))
        assertEquals(emptyList(), robots.list("line-a").map { it.robotId }, "퇴역한 기체가 현역 목록에 남았다")
        val history = robots.list("line-a", includeRetired = true)
        assertEquals(listOf("r-old"), history.map { it.robotId })
        assertEquals("폐기", history.single().retiredReason)
        assertEquals("operator-1", history.single().retiredBy)
    }

    @Test
    fun `퇴역이 다른 상태를 덮지만 마지막 보고는 안 지운다`() {
        declare("r-old")
        report("r-old")
        robots.retire("r-old", "옮김", actor = "operator-1")

        val row = robots.list("line-a", includeRetired = true).single()
        assertEquals(RobotStatus.RETIRED, row.status, "퇴역이 CONFIRMED 에 가려지면 목록에서 안 빠진 것과 같다")
        assertNotNull(row.lastReportedAt, "마지막 보고를 지우면 '내렸는데 아직 있다' 를 못 본다")
    }

    @Test
    fun `퇴역한 뒤에도 보고가 오면 그 어긋남이 목록에 뜬다`() {
        declare("r-old")
        robots.retire("r-old", "폐기", actor = "operator-1")
        // ★**원장에서 내렸다고 현장에서 사라지지 않는다.** 이것이 이 목록에서 가장 알아야 할 사실이다.
        report("r-old")

        val row = robots.list("line-a", includeRetired = true).single()
        assertTrue(row.reportingAfterRetirement, "퇴역 뒤의 보고를 운영자가 시각 둘로 눈대중하게 두면 안 본다")
    }

    @Test
    fun `퇴역한 기체는 발견으로 되살아나지 않는다`() {
        val discovered = robots.discover("line-a", listOf(DiscoveredRobot("r-fleet", "sn-1")))
        assertEquals(listOf("r-fleet"), discovered.recorded)
        robots.retire("r-fleet", "플릿에서 뺐다", actor = "operator-1")

        // 어댑터는 계속 돈다. 그 발견이 운영자의 판단을 덮으면 **매번 되살아난다.**
        val again = robots.discover("line-a", listOf(DiscoveredRobot("r-fleet", "sn-1")))
        assertEquals(emptyList(), again.recorded, "발견이 퇴역을 덮었다 — 운영자가 내린 판단이 현장 프로세스에 진다")
        assertTrue("퇴역" in (again.refused["r-fleet"] ?: ""), "사유가 퇴역이라고 말해야 한다: ${again.refused}")
        assertEquals(RobotStatus.RETIRED, robots.statusOf("r-fleet"))
    }

    @Test
    fun `퇴역한 기체는 선언으로도 안 되살아난다`() {
        declare("r-old")
        robots.retire("r-old", "폐기", actor = "operator-1")

        // **복귀는 등록이 아니다.** 같은 화면에서 다시 적어 되살아나면 그 사건이 첫 등록과 구별되지 않는다.
        val outcome = assertIs<RobotRegistrationOutcome.RetiredAlready>(declare("r-old"))
        assertTrue("퇴역" in outcome.detail)
    }

    @Test
    fun `복귀는 퇴역을 지우고 기체가 답한 사실은 남긴다`() {
        declare("r-old")
        report("r-old")
        robots.retire("r-old", "폐기", actor = "operator-1")

        val outcome = assertIs<RetirementOutcome.Reinstated>(robots.reinstate("r-old", actor = "operator-2"))
        assertTrue(outcome.wasRetired)
        // 복귀 뒤의 상태는 **원장이 정한다** — 이 기체는 답한 적이 있으므로 CONFIRMED 다.
        assertEquals(RobotStatus.CONFIRMED, robots.statusOf("r-old"))
        assertEquals(listOf("r-old"), robots.list("line-a").map { it.robotId })
        assertNull(robots.list("line-a").single().retiredAt)
    }

    @Test
    fun `두 번 퇴역시켜도 첫 사유와 시각을 안 덮는다`() {
        declare("r-old")
        robots.retire("r-old", "첫 사유", actor = "operator-1")
        val first = robots.list("line-a", includeRetired = true).single().retiredAt

        val again = assertIs<RetirementOutcome.Retired>(robots.retire("r-old", "둘째 사유", actor = "operator-2"))
        assertTrue(again.alreadyWas)
        val row = robots.list("line-a", includeRetired = true).single()
        // **덮으면 "언제 떠났나" 의 답이 마지막으로 누른 버튼의 시각이 된다.**
        assertEquals("첫 사유", row.retiredReason)
        assertEquals(first, row.retiredAt)
        assertEquals("operator-1", row.retiredBy)
    }

    @Test
    fun `모르는 기체와 사유 없는 퇴역을 안 접는다`() {
        // 앞은 운영자가 잘못 친 것이고 뒤는 본문이 틀린 것이다 — 하나로 접으면 무엇을 고칠지 모른다.
        assertIs<RetirementOutcome.Unknown>(robots.retire("없는-기체", "폐기", actor = "operator-1"))
        declare("r-old")
        assertIs<RetirementOutcome.Rejected>(robots.retire("r-old", "  ", actor = "operator-1"))
        assertEquals(RobotStatus.CLAIMED, robots.statusOf("r-old"), "거절했는데 퇴역이 됐다")
    }

    @Test
    fun `퇴역 안 한 기체를 복귀시키면 아무것도 안 바뀐다`() {
        declare("r-old")
        val outcome = assertIs<RetirementOutcome.Reinstated>(robots.reinstate("r-old", actor = "operator-1"))
        assertFalse(outcome.wasRetired, "안 바뀐 것을 바뀐 것처럼 답하면 화면이 거짓말한다")
        assertEquals(RobotStatus.CLAIMED, robots.statusOf("r-old"))
    }
}
