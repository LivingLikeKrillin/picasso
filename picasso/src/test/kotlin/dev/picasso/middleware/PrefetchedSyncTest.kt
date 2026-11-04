package dev.picasso.middleware

import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 미들웨어가 잠금 밖에서 미리 읽은 값으로 동기화한다(§15.267) — 에뮬레이터 위의 [PrefetchingRobotPort].
 *
 * 펌프는 다 읽은 값만 받으므로 답하지 않는 기체가 있어도 기다리지 않는다. 관측 시각은 그 읽기가 받은 시각이고, 못 읽는 동안의
 * 끊김 판정은 동기 포트와 같다(§15.242).
 *
 * 하위 호출은 [OneAtATime] 이 하나씩 보낸다. 하네스의 in-process 전송은 직접 실행기이고 에뮬레이터 서버는 호출 전체를 잠금 하나 아래에서
 * 돌므로, 두 스레드가 같은 채널로 동시에 부르면 전송의 잠금과 서버의 잠금을 서로 반대 순서로 잡아 멎는다. 네트워크 채널에는 없는 제약이다.
 */
class PrefetchedSyncTest {

    private fun order() = JobOrder(
        jobOrderId = "SEQ-208",
        workMasterId = PrepareSequencedRack.WORK_MASTER,
        version = 1,
        requiredEvidence = Evidence.E0,
        equipmentRequirements = listOf(
            EquipmentRequirement(SLOT, EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to PART)),
            EquipmentRequirement(BIN, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to PART)),
        ),
    )

    /** [holding] 인 동안 스냅숏이 [held] 가 풀릴 때까지 답하지 않는 선. */
    private class Holding(private val delegate: RobotPort, val holding: AtomicBoolean, val held: CountDownLatch) : RobotPort by delegate {
        override fun snapshot(robotId: String): RobotSnapshot? {
            if (holding.get()) held.await()
            return delegate.snapshot(robotId)
        }
    }

    /** 하위 호출을 하나씩 보내는 선. [Holding] 의 기다림은 이 바깥에 있어 붙든 읽기가 다른 호출을 막지 않는다. */
    private class OneAtATime(private val delegate: RobotPort) : RobotPort {
        private val lock = Any()
        override val executionLookup get() = delegate.executionLookup
        override fun capabilities(robotId: String) = synchronized(lock) { delegate.capabilities(robotId) }
        override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>) =
            synchronized(lock) { delegate.start(robotId, taskId, revision, skillType, parameters) }
        override fun watch(robotId: String, handle: TaskHandle) = synchronized(lock) { delegate.watch(robotId, handle) }
        override fun release(robotId: String, handle: TaskHandle) = synchronized(lock) { delegate.release(robotId, handle) }
        override fun unfollowable(robotId: String, handle: TaskHandle) = synchronized(lock) { delegate.unfollowable(robotId, handle) }
        override fun cancel(robotId: String, handle: TaskHandle) = synchronized(lock) { delegate.cancel(robotId, handle) }
        override fun snapshot(robotId: String) = synchronized(lock) { delegate.snapshot(robotId) }
        override fun replay(robotId: String, from: Long) = synchronized(lock) { delegate.replay(robotId, from) }
    }

    private class World : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to Path.of("..", "profile", "fixtures", "minimal.json").normalize()))
        val holding = AtomicBoolean(false)
        private val held = CountDownLatch(1)
        val port = PrefetchingRobotPort(Holding(OneAtATime(ClientRobotPort(harness.client())), holding, held), now = { harness.clock.now() })
        val mw = Middleware(port, now = { harness.clock.now() })

        fun state(taskId: String) = harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build())
            .tasksList.firstOrNull { it.taskId == taskId }?.taskState

        /** 걸린 읽기가 끝나기를 기다린다. */
        fun settle() {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (port.reading(ROBOT) && System.nanoTime() < deadline) Thread.sleep(5)
            check(!port.reading(ROBOT)) { "읽기가 끝나지 않았다" }
        }

        fun drive(until: () -> Boolean) {
            repeat(60) {
                mw.pump(); settle()
                if (until()) return
                harness.advance(Duration.ofSeconds(10))
                Thread.sleep(30)
                mw.pump(); settle()
                if (until()) return
            }
            error("조건에 못 미쳤다")
        }

        override fun close() {
            held.countDown()
            port.close()
            harness.close()
        }
    }

    private val task = "SEQ-208#$SLOT"

    @Test
    fun `관측 시각은 미리 읽은 값을 받은 시각이다`() {
        World().use { w ->
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT))
            w.drive { w.state(task) == "RUNNING" }
            w.mw.pump(); w.settle()
            val readAt = w.harness.clock.now()

            w.harness.advance(Duration.ofSeconds(1))
            w.mw.pump()
            assertEquals(readAt, w.mw.view(ROBOT)!!.lastObservedAt, "받아 든 시각으로 찍었다 — 낡은 값이 방금 본 값이 된다")
        }
    }

    @Test
    fun `답하지 않는 기체가 있어도 pump 는 기다리지 않고 유예가 지나면 끊김이다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { w.state(task) == "RUNNING" }
            w.mw.pump(); w.settle()

            w.holding.set(true)
            repeat(7) {
                w.harness.advance(Duration.ofSeconds(5))
                assertTimeoutPreemptively(Duration.ofSeconds(1), { w.mw.pump() }, "pump 가 답하지 않는 기체의 읽기를 기다렸다")
            }
            assertEquals(false, w.mw.view(ROBOT)!!.observable, "답하지 않는 기체의 낡은 값을 현재값으로 뒀다")
            assertEquals(PhysicalState.IN_DOUBT, exec.physicalState)
            assertTrue(exec.eventTrail.any { it.kind == "LINK_BROKEN" && it.detail.startsWith("snapshot unreadable since") }, exec.eventTrail.toString())
        }
    }

    private companion object {
        const val ROBOT = "hum-02"
        const val SLOT = "RACK-208.S01"
        const val BIN = "SEQ-IN-02.BIN-A"
        const val PART = "ENGINE-COVER-A"
    }
}
