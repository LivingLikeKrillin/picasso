package dev.picasso.adapter.orbit

import dev.picasso.uplink.report.DiscoveredRobotReport
import dev.picasso.uplink.report.DiscoveryAck
import dev.picasso.uplink.report.RobotDiscovery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 플릿에서 본 것을 **관측으로** 올린다(ADR 37 의 발견).
 *
 * 이 시험이 붙드는 것은 값이 아니라 **무엇을 올리지 않는가**다 — 일련번호를 지어내지 않고, 접속 정보를
 * 안 싣고, 못 물어본 것을 빈 목록으로 접지 않는다.
 */
class OrbitDiscoveryTest {

    private class RecordingSink : RobotDiscovery {
        val sent = mutableListOf<Pair<String, List<DiscoveredRobotReport>>>()
        override fun report(site: String, robots: List<DiscoveredRobotReport>): DiscoveryAck {
            sent += site to robots
            return DiscoveryAck(robots.map { it.robotId }, emptyMap())
        }
    }

    private class FakeFleet(val robots: Result<List<OrbitRobot>>) : FleetLayer {
        override fun robots(): Result<List<OrbitRobot>> = robots
    }

    private class FakeLink(override val fleet: FleetLayer?) : OrbitLink {
        override val missions: MissionLayer? = null
        override val dispatch: DispatchLayer? = null
        override val runs: RunLayer? = null
    }

    @Test
    fun `별명이 계약의 robot_id 가 되고 일련번호는 안 지어낸다`() {
        val sink = RecordingSink()
        val fleet = FakeFleet(
            Result.success(
                listOf(
                    OrbitRobot(hostname = "spot-a.line-a.local", nickname = "spot-a", robotIndex = 0, username = "admin"),
                    OrbitRobot(hostname = "10.0.0.7", nickname = "spot-b", robotIndex = 1, username = "admin"),
                ),
            ),
        )

        val ack = OrbitDiscovery(FakeLink(fleet), "line-a", sink).sweep().getOrThrow()
        assertEquals(listOf("spot-a", "spot-b"), ack.recorded)

        val (site, sent) = sink.sent.single()
        assertEquals("line-a", site, "사이트는 어댑터가 배포된 곳이다 — 플릿은 우리 site_id 를 모른다")
        // **주소가 아니라 별명이다.** 주소는 바뀌고 신원은 안 바뀐다(§15.78).
        assertEquals(listOf("spot-a", "spot-b"), sent.map { it.robotId })
        // **일련번호를 지어내지 않는다.** 플릿이 안 준다 — 주소를 그 자리에 넣으면 거짓말이다.
        assertTrue(sent.all { it.serialNumber == null })
        // 주소는 사람이 읽는 표시로만 남는다.
        assertEquals(listOf("spot-a@spot-a.line-a.local", "spot-b@10.0.0.7"), sent.map { it.displayName })
    }

    @Test
    fun `목록을 못 얻는 것과 기체가 없는 것은 다르다`() {
        val sink = RecordingSink()

        // 링크에 그 층이 없다 — 발견이 선언으로 내려앉는 사분면이다(ADR 37 결정 5).
        assertTrue(OrbitDiscovery(FakeLink(null), "line-a", sink).sweep().isFailure)
        // 물어봤는데 못 받았다.
        assertTrue(OrbitDiscovery(FakeLink(FakeFleet(Result.failure(IllegalStateException("502")))), "line-a", sink).sweep().isFailure)
        // **둘 다 아무것도 안 올린다.** 빈 목록을 올리면 원장은 "플릿에 기체가 없다" 로 읽는다.
        assertEquals(emptyList(), sink.sent)

        // 진짜로 비어 있으면 그것은 답이다 — 올린다.
        val empty = OrbitDiscovery(FakeLink(FakeFleet(Result.success(emptyList()))), "line-a", sink).sweep().getOrThrow()
        assertEquals(emptyList(), empty.recorded)
        assertEquals(1, sink.sent.size)
    }

    @Test
    fun `레지스트리 연계가 없으면 발견은 어댑터 안에서 끝난다`() {
        val fleet = FakeFleet(Result.success(listOf(OrbitRobot("h", "spot-a", 0, "admin"))))
        val ack = OrbitDiscovery(FakeLink(fleet), "line-a").sweep().getOrThrow()

        // §3.2 의 "없을 때" — 상대가 없어도 모듈이 돈다. 다만 **조용히 성공하지 않는다.**
        assertEquals(emptyList(), ack.recorded)
        assertTrue("레지스트리" in ack.refused.getValue("spot-a"), ack.refused.toString())
    }
}
