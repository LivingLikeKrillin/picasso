package dev.picasso.middleware

import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.WatchTaskResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 잠금 밖 선읽기(§15.267) — [PrefetchingRobotPort] 가 다 읽은 값을 한 번만, 실제 왕복 시각과 함께, 낡지 않았을 때만 주는가.
 *
 * 읽기는 손으로 돌리는 실행기([Manual])에서 돈다. 언제 읽고 언제 시계가 가는지를 시험이 정한다.
 */
class PrefetchingRobotPortTest {

    private var clock: Instant = Instant.parse("2026-11-05T00:00:00Z")

    /** 넘겨받은 읽기를 쌓아 두었다가 [run] 이 돌린다. */
    private class Manual : Executor {
        val queued = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            queued += command
        }

        fun run() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }

    /** 스냅숏과 재생만 답하는 하위. 스냅숏 하나에 [readTakes] 만큼 시계가 간다. */
    private inner class Robot : RobotPort {
        var session = "s1"
        var sequence = 10L
        var events = (10L..12L).toList()
        var readTakes: Duration = Duration.ofMillis(50)
        val replays = mutableListOf<Long>()
        val declared: Capability = Capability.newBuilder().setModel("m1").build()
        var capabilityReads = 0
        var capabilityFailure: RuntimeException? = null

        override fun capabilities(robotId: String): Capability? {
            capabilityReads += 1
            capabilityFailure?.let { throw it }
            return declared
        }

        override fun snapshot(robotId: String): RobotSnapshot? {
            clock = clock.plus(readTakes)
            return RobotSnapshot(sequence = sequence, faults = emptyList(), connection = ConnectionState.CONNECTION_STATE_ONLINE, tasks = emptyMap(), session = session)
        }

        override fun replay(robotId: String, from: Long): Replay? {
            replays += from
            return Replay.Events(events.filter { it >= from }.map { Event.newBuilder().setHeader(MessageHeader.newBuilder().setSequence(it)).build() })
        }

        override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse = error("not used")
        override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> = error("not used")
        override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse = error("not used")
    }

    private val robot = Robot()
    private val manual = Manual()
    private val port = PrefetchingRobotPort(robot, now = { clock }, maxAge = Duration.ofSeconds(2), executor = manual)

    private fun advance(millis: Long) {
        clock = clock.plusMillis(millis)
    }

    private fun Replay?.sequences(): List<Long> = assertIs<Replay.Events>(this).events.map { it.header.sequence }

    @Test
    fun `다 읽은 값은 한 번만 준다`() {
        assertEquals(PrefetchedRead.Unread, port.prefetched(R), "읽은 적이 없다")
        manual.run()
        assertIs<PrefetchedRead.Read>(port.prefetched(R))
        val again = port.prefetched(R)
        assertEquals(PrefetchedRead.Pending, again, "준 값을 다시 줬다 — 멎은 읽기가 새 관측으로 보인다")
    }

    @Test
    fun `받은 시각은 읽기가 실제로 받은 시각이다`() {
        port.prefetched(R)
        advance(100)
        val sent = clock
        manual.run()
        val received = clock
        advance(1_000)
        val read = assertIs<PrefetchedRead.Read>(port.prefetched(R))
        assertEquals(sent, read.sentAt)
        assertEquals(received, read.receivedAt, "받아 든 시각으로 찍었다")
    }

    @Test
    fun `받은 지 maxAge 넘은 값은 주지 않는다`() {
        port.prefetched(R)
        manual.run()
        advance(2_001)
        assertEquals(PrefetchedRead.Unread, port.prefetched(R), "낡은 값을 현재값으로 줬다")
    }

    @Test
    fun `읽는 중인데 마지막 읽기가 낡으면 못 물어봤다이다`() {
        port.prefetched(R)
        manual.run()
        assertIs<PrefetchedRead.Read>(port.prefetched(R)) // 다음 읽기가 걸리고 돌지 않는다
        advance(1_000)
        assertEquals(PrefetchedRead.Pending, port.prefetched(R), "읽기 간격을 관측 실패로 셌다")
        advance(1_500)
        assertEquals(PrefetchedRead.Unread, port.prefetched(R), "답하지 않는 기체의 낡은 값이 남았다")
    }

    @Test
    fun `재생은 미리 물은 번호 이후로 걸러 준다`() {
        port.prefetched(R); manual.run()
        port.prefetched(R) // 첫 스냅숏 — 다음 읽기는 그 번호(10)부터 재생을 묻는다
        manual.run()
        assertEquals(listOf(10L), robot.replays)
        assertIs<PrefetchedRead.Read>(port.prefetched(R))
        assertEquals(listOf(11L, 12L), port.replay(R, 11).sequences(), "미리 읽은 번호 앞의 이벤트를 다시 줬다")

        manual.run()
        assertIs<PrefetchedRead.Read>(port.prefetched(R))
        assertNull(port.replay(R, 9), "미리 읽은 번호보다 앞을 물었는데 모자란 재생을 줬다")
    }

    @Test
    fun `세션이 바뀌면 앞 세션의 번호로 재생을 묻지 않는다`() {
        port.prefetched(R); manual.run()
        port.prefetched(R)
        robot.session = "s2"
        robot.sequence = 3
        manual.run()
        assertTrue(robot.replays.isEmpty(), "앞 세션의 번호로 재생을 물었다: ${robot.replays}")
    }

    @Test
    fun `재개 경로의 스냅숏은 낡지 않은 값만 주고 없으면 읽기를 건다`() {
        assertNull(port.snapshot(R), "읽은 값이 없는데 지금 물었다")
        assertEquals(1, manual.queued.size)
        manual.run()
        assertNotNull(port.snapshot(R))
        advance(2_001)
        assertNull(port.snapshot(R), "낡은 값을 줬다")
        assertEquals(1, manual.queued.size)
    }

    @Test
    fun `답하지 않는 기체의 읽기가 다른 기체의 읽기를 늦추지 않는다`() {
        val held = CountDownLatch(1)
        val hanging = object : RobotPort by robot {
            override fun snapshot(robotId: String): RobotSnapshot? {
                if (robotId == R) held.await()
                return RobotSnapshot(sequence = 1, faults = emptyList(), connection = ConnectionState.CONNECTION_STATE_ONLINE, tasks = emptyMap())
            }
        }
        PrefetchingRobotPort(hanging, now = Instant::now).use { real ->
            try {
                real.prefetched(R)
                real.prefetched(OTHER)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var other: PrefetchedRead = PrefetchedRead.Unread
                while (other !is PrefetchedRead.Read && System.nanoTime() < deadline) {
                    Thread.sleep(10)
                    other = real.prefetched(OTHER)
                }
                assertIs<PrefetchedRead.Read>(other, "답하지 않는 기체가 다른 기체의 읽기를 붙들었다")
                assertTrue(real.prefetched(R) !is PrefetchedRead.Read)
            } finally {
                held.countDown()
            }
        }
    }

    @Test
    fun `기체의 선언은 지금 묻지 않고 스냅숏 읽기에 이어 받은 값을 준다`() {
        assertNull(port.capabilities(R), "읽은 적이 없는데 선언을 줬다")
        assertEquals(0, robot.capabilityReads, "부른 쪽 스레드에서 선언을 물었다")
        manual.run()
        assertEquals(robot.declared, port.capabilities(R))
        assertEquals(robot.declared, port.capabilities(R), "선언을 받아 가는 값처럼 한 번만 줬다")
        assertEquals(1, robot.capabilityReads, "읽기 한 번에 선언을 한 번보다 많이 물었다")
    }

    @Test
    fun `못 물어본 선언은 앞 선언을 덮지 않고 받은 지 maxAge 넘은 선언은 주지 않는다`() {
        port.capabilities(R)
        manual.run()
        robot.capabilityFailure = IllegalStateException("시험: 선언에 답 없음")
        port.prefetched(R)
        manual.run()
        assertEquals(robot.declared, port.capabilities(R), "못 물어본 선언이 앞 선언을 덮었다")

        advance(2_100)
        assertNull(port.capabilities(R), "받은 지 maxAge 넘은 선언을 줬다")
    }

    @Test
    fun `선언을 아직 못 받았으면 기한 안에서만 읽는 중이라고 답하고 못 물어본 선언은 기다리지 않는다`() {
        assertTrue(port.capabilitiesPending(R), "읽은 적이 없는데 읽는 중이 아니라고 했다")
        assertEquals(0, robot.capabilityReads, "부른 쪽 스레드에서 선언을 물었다")
        manual.run()
        assertFalse(port.capabilitiesPending(R), "선언을 받았는데 읽는 중이라고 했다")

        assertTrue(port.capabilitiesPending(OTHER))
        advance(2_100)
        assertFalse(port.capabilitiesPending(OTHER), "기한 넘게 읽는 중이라고 했다 — 출발이 끝없이 밀린다")

        robot.capabilityFailure = IllegalStateException("시험: 선언에 답 없음")
        assertTrue(port.capabilitiesPending(R), "낡은 선언을 읽는 중이 아니라고 했다")
        manual.run()
        assertFalse(port.capabilitiesPending(R), "물었으나 답이 없는 선언을 계속 기다렸다")
        assertNull(port.capabilities(R))
    }

    private companion object {
        const val R = "hum-01"
        const val OTHER = "hum-02"
    }
}
