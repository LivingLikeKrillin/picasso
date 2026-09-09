package dev.picasso.harness

import dev.picasso.adapter.orbit.FleetLayer
import dev.picasso.adapter.orbit.OrbitDiscovery
import dev.picasso.adapter.orbit.OrbitLink
import dev.picasso.adapter.orbit.OrbitRobot
import dev.picasso.registry.PostgresSupport
import dev.picasso.registry.binding.DiscoveredRobot
import dev.picasso.registry.binding.RobotOrigin
import dev.picasso.registry.binding.RobotRegistration
import dev.picasso.registry.binding.RobotRegistrationOutcome
import dev.picasso.registry.binding.RobotStatus
import dev.picasso.registry.store.Db
import dev.picasso.uplink.report.DiscoveryAck
import dev.picasso.uplink.report.RobotDiscovery
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **ADR 37 의 발견 경로가 실증된다.**
 *
 * 그 ADR 이 미결로 남긴 것이 이것이었다 — *"발견 경로를 무엇으로 실증하는가. 어댑터의 남쪽에 플릿 흉내가
 * 필요한데 공용 도구가 없다(지금은 어댑터마다 자기 시험 안에 `Fake` 를 둔다). 미믹은 북쪽이라 그 자리가
 * 아니다."* 그리고 §15.101 이 문을 만들면서도 *"그 문으로 올리는 어댑터는 없다"* 고 적었다.
 *
 * 여기서 그 셋이 이어진다: 가짜 Orbit(남쪽) → `OrbitDiscovery` → 적재 문 → 원장. **관측으로 앉는다** —
 * 사람이 아무것도 적지 않았는데 기체가 원장에 있고, 상태가 `CLAIMED` 가 아니라 `DISCOVERED` 다.
 *
 * ## HTTP 는 여기서 안 지난다
 *
 * 미믹 쪽 적재 시험과 같은 이유로 등록 서비스를 직접 싱크로 붙인다. 와이어(경로·토큰·본문)는
 * `RobotDoorEndpointTest` 가 본다. 여기서 볼 것은 **출처가 관측인가** 하나다.
 */
class OrbitDiscoveryEndToEndTest {

    private lateinit var db: Db
    private lateinit var robots: RobotRegistration

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        robots = RobotRegistration(db)
    }

    // ── 가짜 Orbit. 벤더가 주는 모양 그대로다 — 일련번호가 없다.

    private class FakeFleet(private val robots: List<OrbitRobot>) : FleetLayer {
        override fun robots(): Result<List<OrbitRobot>> = Result.success(robots)
    }

    private class FakeLink(override val fleet: FleetLayer?) : OrbitLink {
        override val missions: dev.picasso.adapter.orbit.MissionLayer? = null
        override val dispatch: dev.picasso.adapter.orbit.DispatchLayer? = null
        override val runs: dev.picasso.adapter.orbit.RunLayer? = null
    }

    /** 적재 문. HTTP 대신 등록 서비스를 직접 부른다 — 문은 같다. */
    private fun door(): RobotDiscovery = RobotDiscovery { site, reported ->
        val outcome = robots.discover(
            site,
            reported.map { DiscoveredRobot(it.robotId, it.serialNumber, it.displayName) },
        )
        DiscoveryAck(outcome.recorded, outcome.refused)
    }

    private fun sweep(vararg found: OrbitRobot) =
        OrbitDiscovery(FakeLink(FakeFleet(found.toList())), SITE, door()).sweep().getOrThrow()

    @Test
    fun `플릿에서 본 기체가 사람의 말 없이 원장에 관측으로 앉는다`() {
        assertNull(robots.statusOf("spot-a"), "시작할 때 원장이 이 기체를 몰라야 한다")

        val ack = sweep(
            OrbitRobot(hostname = "spot-a.line-a.local", nickname = "spot-a", robotIndex = 0, username = "admin"),
            OrbitRobot(hostname = "10.0.0.7", nickname = "spot-b", robotIndex = 1, username = "admin"),
        )
        assertEquals(listOf("spot-a", "spot-b"), ack.recorded)
        assertEquals(emptyMap(), ack.refused)

        // **사람이 아무것도 안 적었는데 기체가 있다.** 그리고 그 상태가 CLAIMED 가 아니다 —
        // 어댑터가 플릿에서 본 것이라 처음부터 관측이다(ADR 37 의 근거 문단).
        assertEquals(RobotStatus.DISCOVERED, robots.statusOf("spot-a"))
        assertEquals(RobotStatus.DISCOVERED, robots.statusOf("spot-b"))

        val rows = robots.list(siteId = SITE)
        assertEquals(listOf(RobotOrigin.DISCOVERED, RobotOrigin.DISCOVERED), rows.map { it.origin })
        // **일련번호가 비어 있다.** 플릿이 안 준다(§15.103) — 주소를 그 자리에 넣지 않았다.
        assertTrue(rows.all { it.serialNumber == null })
        // **접속 정보도 비어 있다.** 플릿 경유면 그것은 플릿이 갖는다(ADR 37 결정 4).
        assertTrue(rows.all { it.endpoint == null })
        assertEquals(listOf("spot-a@spot-a.line-a.local", "spot-b@10.0.0.7"), rows.map { it.displayName })

        // 행위자가 사람이 아니다 — 그 문에는 신원이 없고, 있는 척하면 감사 로그가 거짓말한다.
        assertEquals(
            listOf("ROBOT_DISCOVERED|adapter-discovery", "ROBOT_DISCOVERED|adapter-discovery"),
            PostgresSupport.queryAll("SELECT operation, actor FROM audit_log ORDER BY subject") {
                "${it.getString(1)}|${it.getString(2)}"
            },
        )
    }

    @Test
    fun `다시 훑어도 같은 기체가 겹쳐 쌓이지 않는다`() {
        val first = OrbitRobot("spot-a.line-a.local", "spot-a", 0, "admin")
        sweep(first)
        // 주소가 바뀌어도 같은 기체다 — 신원이 별명이라 그렇다(§15.78 의 이름과 주소).
        sweep(OrbitRobot("10.0.0.9", "spot-a", 0, "admin"))

        assertEquals(1, PostgresSupport.queryOne("SELECT count(*) FROM robot") { it.getInt(1) })
        assertEquals("spot-a@10.0.0.9", robots.list().single().displayName, "주소가 바뀐 것은 반영돼야 한다")
        assertEquals(RobotStatus.DISCOVERED, robots.statusOf("spot-a"))
    }

    @Test
    fun `사람이 선언한 기체를 발견이 덮지 않는다`() {
        // 운영자가 먼저 적었다 — 일련번호까지 알고 있다.
        assertIs<RobotRegistrationOutcome.Registered>(
            robots.declare("spot-a", SITE, "sn-real", endpoint = "10.0.0.1:50051", actor = "operator-1"),
        )

        val ack = sweep(OrbitRobot("spot-a.line-a.local", "spot-a", 0, "admin"))

        // **출처는 두 번째 진실이 되지 않는다**(ADR 37 결정 3). 거절되고 사유가 온다.
        assertEquals(emptyList(), ack.recorded)
        assertTrue("DECLARED" in ack.refused.getValue("spot-a"), ack.refused.toString())

        val row = robots.list().single()
        assertEquals(RobotOrigin.DECLARED, row.origin)
        // 사람이 적은 것이 그대로다 — 발견이 일련번호를 지우거나 접속 정보를 날리지 않았다.
        assertEquals("sn-real", row.serialNumber)
        assertEquals("10.0.0.1:50051", row.endpoint)
    }

    private companion object {
        const val SITE = "line-a"
    }
}
