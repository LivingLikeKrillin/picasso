package dev.picasso.uplink.report

import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.NegotiateRequest
import dev.picasso.contracts.v1.NegotiateResponse
import dev.picasso.contracts.v1.StateMessage
import dev.picasso.contracts.v1.TaskSnapshot
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 적재 큐(§15.210). 발행한 스레드는 넣기만 하고, 레지스트리로 보내는 것은 작업 스레드 하나다.
 */
class QueuedIngestTest {

    /** 받은 태스크 관측을 순서대로 적는다. [gate] 가 닫혀 있으면 첫 관측에서 선다. */
    private class Tasks : TaskObservations {
        val received: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val entered = CountDownLatch(1)
        var gate: CountDownLatch? = null
        var pause: (Int) -> Unit = {}

        override fun onState(message: StateMessage) = take("state:" + message.tasksList.single().taskId)
        override fun onEvent(event: Event) = take("event:" + event.header.robotId)

        private fun take(label: String) {
            entered.countDown()
            gate?.await(10, TimeUnit.SECONDS)
            pause(received.size)
            received += label
        }
    }

    private class Live : LivenessObservations {
        val received: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun onConnection(
            header: MessageHeader,
            state: ConnectionState,
            software: String?,
            siteNames: SiteNameSummary?,
        ) {
            received += header.robotId
        }
    }

    private class Failed : FailedObservations {
        val received: MutableList<Pair<String, String?>> = Collections.synchronizedList(mutableListOf())
        override fun onFailure(kind: String, json: String, reason: String?) {
            received += kind to reason
        }
    }

    /** 받은 핸드셰이크 보고의 사이트를 적는다. [gate] 가 닫혀 있으면 첫 보고에서 선다. */
    private class Handshakes : HandshakeReporter {
        val received: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val entered = CountDownLatch(1)
        var gate: CountDownLatch? = null

        override fun report(report: HandshakeReport) {
            entered.countDown()
            gate?.await(10, TimeUnit.SECONDS)
            received += report.site
        }
    }

    private val tasks = Tasks()
    private val live = Live()
    private val failed = Failed()
    private val handshakes = Handshakes()
    private val handshakeFailed = RecordingHandshakeReporter()
    private val opened = mutableListOf<QueuedIngest>()

    private fun ingest(capacity: Int = QueuedIngest.CAPACITY, nanoTime: () -> Long = System::nanoTime) =
        QueuedIngest(
            tasks, live, failed, capacity = capacity, nanoTime = nanoTime,
            handshakes = handshakes, handshakeOverflow = handshakeFailed,
        ).also { opened += it }

    @AfterTest
    fun release() {
        tasks.gate?.countDown()
        handshakes.gate?.countDown()
        opened.forEach { it.close() }
    }

    @Test
    fun `레지스트리로 보내는 동안 넣는 쪽은 기다리지 않는다`() {
        tasks.gate = CountDownLatch(1)
        val queue = ingest()

        queue.taskSink.onState(state("t1"))
        assertTrue(tasks.entered.await(5, TimeUnit.SECONDS), "작업 스레드가 꺼내지 않았다")

        // 보내는 쪽이 서 있는 동안에도 넣기는 바로 돌아온다.
        val started = System.nanoTime()
        queue.taskSink.onState(state("t2"))
        queue.livenessSink.onConnection(header("r1"), ConnectionState.CONNECTION_STATE_ONLINE, null)
        val waited = Duration.ofNanos(System.nanoTime() - started)
        assertTrue(waited < Duration.ofMillis(500), "넣는 쪽이 보내기를 기다렸다: $waited")

        tasks.gate!!.countDown()
        assertTrue(queue.drain(Duration.ofSeconds(10)))
        assertEquals(listOf("state:t1", "state:t2"), tasks.received)
        assertEquals(listOf("r1"), live.received)
    }

    @Test
    fun `관측은 넣은 순서대로 간다`() {
        // 보내는 시간이 들쭉날쭉해도 순서가 안 바뀐다 — 보내는 스레드가 하나다.
        tasks.pause = { if (it % 3 == 0) Thread.sleep(2) }
        val queue = ingest()

        val expected = (1..60).map { n ->
            if (n % 2 == 0) {
                queue.taskSink.onState(state("t$n")); "state:t$n"
            } else {
                queue.taskSink.onEvent(event("r$n")); "event:r$n"
            }
        }

        assertTrue(queue.drain(Duration.ofSeconds(10)))
        assertEquals(expected, tasks.received)
    }

    @Test
    fun `큐가 차면 태스크 관측은 폴백으로 넘어간다`() {
        tasks.gate = CountDownLatch(1)
        val queue = ingest(capacity = 2)
        queue.taskSink.onState(state("t1"))
        assertTrue(tasks.entered.await(5, TimeUnit.SECONDS))

        queue.taskSink.onState(state("t2"))
        queue.taskSink.onEvent(event("r1"))
        queue.taskSink.onState(state("t3"))

        assertEquals(listOf<Pair<String, String?>>("state" to QueuedIngest.QUEUE_FULL), failed.received, "넘친 관측을 잃었다")
        assertEquals(1, queue.overflowedTaskCount)
        tasks.gate!!.countDown()
        assertTrue(queue.drain(Duration.ofSeconds(10)))
        assertEquals(listOf("state:t1", "state:t2", "event:r1"), tasks.received)
    }

    @Test
    fun `큐가 차면 생존은 폴백으로 안 넘기고 버린다`() {
        // 생존은 시점이 곧 내용이라 되밀 수 없다(HttpLiveness 의 KDoc).
        tasks.gate = CountDownLatch(1)
        val queue = ingest(capacity = 1)
        queue.taskSink.onState(state("t1"))
        assertTrue(tasks.entered.await(5, TimeUnit.SECONDS))
        queue.taskSink.onState(state("t2"))

        queue.livenessSink.onConnection(header("r1"), ConnectionState.CONNECTION_STATE_ONLINE, null)

        assertEquals(1, queue.droppedLivenessCount)
        assertTrue(failed.received.isEmpty(), "${failed.received}")
        tasks.gate!!.countDown()
        assertTrue(queue.drain(Duration.ofSeconds(10)))
        assertTrue(live.received.isEmpty(), "${live.received}")
    }

    @Test
    fun `큐에 오래 머문 생존은 보내지 않는다`() {
        // 레지스트리는 받은 시각으로 적는다 — 늦게 간 생존은 그 시각에 살아 있었다는 거짓이 된다.
        var now = 0L
        tasks.gate = CountDownLatch(1)
        val queue = ingest(nanoTime = { now })
        queue.taskSink.onState(state("t1"))
        assertTrue(tasks.entered.await(5, TimeUnit.SECONDS))

        queue.livenessSink.onConnection(header("old"), ConnectionState.CONNECTION_STATE_ONLINE, null)
        now += QueuedIngest.LIVENESS_MAX_AGE.plusSeconds(1).toNanos()
        queue.livenessSink.onConnection(header("fresh"), ConnectionState.CONNECTION_STATE_ONLINE, null)
        tasks.gate!!.countDown()

        assertTrue(queue.drain(Duration.ofSeconds(10)))
        assertEquals(listOf("fresh"), live.received, "오래 머문 생존을 보냈다")
        assertEquals(1, queue.staleLivenessCount)
    }

    @Test
    fun `닫으면 남은 관측을 다 보내고 그 뒤에 온 것은 폴백으로 넘긴다`() {
        // 보내기가 닫는 쪽의 기다림(작업 스레드 join)보다 오래 걸린다 — 비우지 않고 닫으면 덜 간 채로 돌아온다.
        tasks.pause = { Thread.sleep(50) }
        val queue = ingest()
        repeat(10) { queue.taskSink.onState(state("t$it")) }

        queue.close()

        assertEquals((0 until 10).map { "state:t$it" }, tasks.received, "닫으면서 큐에 남은 것을 버렸다")
        queue.taskSink.onState(state("late"))
        assertEquals(listOf<Pair<String, String?>>("state" to QueuedIngest.QUEUE_FULL), failed.received)
    }

    @Test
    fun `핸드셰이크 보고는 넣기만 하고 레지스트리의 답을 기다리지 않는다`() {
        // 협상에 답하는 스레드는 호스트 잠금 아래에 있다(§15.266). 보고를 기다리면 그 호스트의 RPC 가 같이 선다.
        handshakes.gate = CountDownLatch(1)
        val queue = ingest()

        queue.handshakeSink.report(handshake("line-a"))
        assertTrue(handshakes.entered.await(5, TimeUnit.SECONDS), "작업 스레드가 꺼내지 않았다")

        val started = System.nanoTime()
        queue.handshakeSink.report(handshake("line-b"))
        val waited = Duration.ofNanos(System.nanoTime() - started)
        assertTrue(waited < Duration.ofMillis(500), "보고하는 쪽이 보내기를 기다렸다: $waited")

        handshakes.gate!!.countDown()
        assertTrue(queue.drain(Duration.ofSeconds(10)))
        assertEquals(listOf("line-a", "line-b"), handshakes.received)
        assertTrue(handshakeFailed.reports.isEmpty(), "${handshakeFailed.reports}")
    }

    @Test
    fun `큐가 차면 핸드셰이크 보고는 폴백으로 넘어간다`() {
        // 보고는 사실이라 늦게 가도 참이다 — 넘친 것을 버리면 원장이 그 소비자를 못 본 채 축소를 승인한다.
        handshakes.gate = CountDownLatch(1)
        val queue = ingest(capacity = 1)
        queue.handshakeSink.report(handshake("line-a"))
        assertTrue(handshakes.entered.await(5, TimeUnit.SECONDS))
        queue.handshakeSink.report(handshake("line-b"))

        queue.handshakeSink.report(handshake("line-c"))

        assertEquals(listOf("line-c"), handshakeFailed.reports.map { it.site }, "넘친 보고를 잃었다")
        assertEquals(1, queue.overflowedHandshakeCount)
        handshakes.gate!!.countDown()
        assertTrue(queue.drain(Duration.ofSeconds(10)))
        assertEquals(listOf("line-a", "line-b"), handshakes.received)
    }

    private fun handshake(site: String) =
        HandshakeReport(site, NegotiateRequest.getDefaultInstance(), NegotiateResponse.getDefaultInstance())

    private fun header(robot: String) = MessageHeader.newBuilder().setRobotId(robot).build()

    private fun state(task: String) = StateMessage.newBuilder()
        .setHeader(header("r1"))
        .addTasks(TaskSnapshot.newBuilder().setTaskId(task))
        .build()

    private fun event(robot: String) = Event.newBuilder().setHeader(header(robot)).build()
}
