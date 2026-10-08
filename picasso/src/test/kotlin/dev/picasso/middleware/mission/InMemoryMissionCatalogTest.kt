package dev.picasso.middleware.mission

import dev.picasso.middleware.DeliverContainer
import dev.picasso.middleware.FloorOwnership
import dev.picasso.middleware.Middleware
import dev.picasso.middleware.MissionCatalog
import dev.picasso.middleware.PrepareSequencedRack
import dev.picasso.middleware.RobotPort
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 메모리 카탈로그 — 활성화는 검증을 지나야 서고, 통과하면 다음 버전이, 거부되면 활성 버전 그대로다.
 */
class InMemoryMissionCatalogTest {

    private val at: Instant = Instant.parse("2026-10-08T09:00:00Z")

    private fun catalog() = InMemoryMissionCatalog(now = { at })

    private fun InMemoryMissionCatalog.activate(text: String) =
        activate(text, MissionFixtures.SIGNALS, FloorOwnership.None, MissionFixtures.SITE_SKILLS)

    @Test
    fun `활성화 전에는 코드 케이퍼빌리티가 버전 없이 답한다`() {
        val catalog = catalog()
        val active = catalog.active(PrepareSequencedRack.WORK_MASTER)!!
        assertIs<PrepareSequencedRack>(active.capability)
        assertNull(active.missionVersion)
        assertIs<DeliverContainer>(catalog.active(DeliverContainer.WORK_MASTER)!!.capability)
        assertNull(catalog.active("Unknown"))
    }

    @Test
    fun `통과하면 1 부터 오르는 버전이 활성이 되고 거부된 시도는 번호를 쓰지 않는다`() {
        val catalog = catalog()
        assertEquals(Activation.Activated(PrepareSequencedRack.WORK_MASTER, 1), catalog.activate(MissionFixtures.PREPARE_SEQUENCED_RACK))
        val v1 = catalog.active(PrepareSequencedRack.WORK_MASTER)!!
        assertEquals(1, v1.missionVersion)
        assertIs<DefinedCapability>(v1.capability)

        val refused = assertIs<Activation.Refused>(catalog.activate(MissionFixtures.withArrivalWait(signal = "rack_ready")))
        assertEquals(listOf(MissionRefusalKind.SIGNAL_NOT_IN_SPEC), refused.refusals.map { it.kind })
        assertSame(v1, catalog.active(PrepareSequencedRack.WORK_MASTER), "거부가 활성 버전을 바꿨다")

        assertEquals(Activation.Activated(PrepareSequencedRack.WORK_MASTER, 2), catalog.activate(MissionFixtures.withArrivalWait()))
        assertEquals(2, catalog.active(PrepareSequencedRack.WORK_MASTER)!!.missionVersion)
        // 다른 WorkMaster 는 손대지 않았다.
        assertNull(catalog.active(DeliverContainer.WORK_MASTER)!!.missionVersion)
    }

    @Test
    fun `읽을 수 없는 정의는 틀린 곳마다 같은 모양의 거부로 낸다`() {
        val catalog = catalog()
        val text = MissionFixtures.PREPARE_SEQUENCED_RACK.replace("\"maxEvidence\": \"E2\"", "\"maxEvidence\": \"E9\", \"owner\": \"x\"")
        val refused = assertIs<Activation.Refused>(catalog.activate(text))
        assertEquals(2, refused.refusals.size)
        refused.refusals.forEach {
            assertEquals(MissionRefusalKind.UNREADABLE, it.kind)
            assertNull(it.nodeId)
            assertEquals(at, it.checkedAt)
            assertEquals(RefusalOwner.ENGINEER, it.owner)
        }
        assertNull(catalog.active(PrepareSequencedRack.WORK_MASTER)!!.missionVersion, "읽을 수 없는 정의가 섰다")
    }

    @Test
    fun `활성화와 조회가 여러 스레드에서 겹쳐도 번호가 겹치거나 빠지지 않는다`() {
        val catalog = catalog()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val versions = java.util.Collections.synchronizedList(mutableListOf<Int>())
        repeat(40) {
            pool.submit {
                start.await()
                val result = catalog.activate(MissionFixtures.PREPARE_SEQUENCED_RACK)
                versions += (result as Activation.Activated).missionVersion
                catalog.active(PrepareSequencedRack.WORK_MASTER)
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals((1..40).toList(), versions.sorted())
        assertEquals(40, catalog.active(PrepareSequencedRack.WORK_MASTER)!!.missionVersion)
    }

    @Test
    fun `미들웨어에 케이퍼빌리티 목록과 카탈로그를 함께 주면 생성이 실패한다`() {
        // 한쪽이 조용히 무시되는 길을 두지 않는다.
        val robots = NO_ROBOT
        assertFailsWith<IllegalArgumentException> {
            Middleware(robots, capabilities = listOf(PrepareSequencedRack()), missions = catalog())
        }
        Middleware(robots, missions = catalog())
        Middleware(robots, capabilities = MissionCatalog.codeCapabilities())
        Middleware(robots)
    }

    private companion object {
        /** 생성만 보는 시험이라 부르면 안 된다. */
        val NO_ROBOT: RobotPort = java.lang.reflect.Proxy.newProxyInstance(
            RobotPort::class.java.classLoader,
            arrayOf(RobotPort::class.java),
        ) { _, method, _ -> error("생성 시험에서 로봇을 불렀다: ${method.name}") } as RobotPort
    }
}
