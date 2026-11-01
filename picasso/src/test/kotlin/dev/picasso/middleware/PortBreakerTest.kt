package dev.picasso.middleware

import dev.picasso.client.PicassoClient
import dev.picasso.contracts.v1.CancelTaskRequest
import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.EventServiceGrpc
import dev.picasso.contracts.v1.GetSnapshotRequest
import dev.picasso.contracts.v1.GetSnapshotResponse
import dev.picasso.contracts.v1.ReplayEventsRequest
import dev.picasso.contracts.v1.ReplayEventsResponse
import dev.picasso.contracts.v1.StartTaskRequest
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskServiceGrpc
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 답하지 않는 기체의 차단 창(§15.237) — [ClientRobotPort] 가 기한을 다 쓴 기체에 같은 기한을 라운드마다 되풀이해 쓰지 않는가.
 *
 * 미믹은 늘 답하므로 하위는 이 시험의 서버다. [Downstream.silent] 에 든 기체에는 답하지 않고(호출이 기한까지 걸린다),
 * [Downstream.refusing] 에 든 기체에는 바로 오류로 답한다. 기한은 1초다.
 */
class PortBreakerTest {

    private class Downstream {
        val silent: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val refusing = ConcurrentHashMap<String, Status>()
        val snapshots = ConcurrentHashMap<String, Int>()
        val cancels = ConcurrentHashMap<String, Int>()
        val others = ConcurrentHashMap<String, Int>()

        val events = object : EventServiceGrpc.EventServiceImplBase() {
            override fun getSnapshot(request: GetSnapshotRequest, observer: StreamObserver<GetSnapshotResponse>) {
                snapshots.merge(request.robotId, 1, Int::plus)
                answer(request.robotId, observer, GetSnapshotResponse.getDefaultInstance())
            }

            override fun replayEvents(request: ReplayEventsRequest, observer: StreamObserver<ReplayEventsResponse>) {
                others.merge(request.robotId, 1, Int::plus)
                if (request.robotId !in silent) observer.onCompleted()
            }
        }

        val tasks = object : TaskServiceGrpc.TaskServiceImplBase() {
            override fun cancelTask(request: CancelTaskRequest, observer: StreamObserver<CancelTaskResponse>) {
                cancels.merge(request.handle.robotId, 1, Int::plus)
                answer(request.handle.robotId, observer, CancelTaskResponse.getDefaultInstance())
            }

            override fun startTask(request: StartTaskRequest, observer: StreamObserver<StartTaskResponse>) {
                others.merge(request.robotId, 1, Int::plus)
                answer(request.robotId, observer, StartTaskResponse.getDefaultInstance())
            }
        }

        private fun <T> answer(robot: String, observer: StreamObserver<T>, value: T) {
            if (robot in silent) return // 답하지 않는다 — 호출은 기한까지 걸린다
            refusing[robot]?.let { observer.onError(it.asRuntimeException()); return }
            observer.onNext(value)
            observer.onCompleted()
        }
    }

    private val downstream = Downstream()
    private val name = InProcessServerBuilder.generateName()
    private val server: Server = InProcessServerBuilder.forName(name)
        .addService(downstream.events).addService(downstream.tasks).build().start()
    private val channel: ManagedChannel = InProcessChannelBuilder.forName(name).build()
    private var clock = Instant.parse("2026-11-01T00:00:00Z")
    private val port = ClientRobotPort(PicassoClient(channel, "line-controller", deadlineSeconds = 1), now = { clock })

    @AfterTest
    fun close() {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun handle(robot: String) = TaskHandle.newBuilder().setTaskId("t-1").setRevision(1).setRobotId(robot).build()

    private fun millis(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1_000_000
    }

    @Test
    fun `기한을 다 쓴 기체는 차단 창 동안 하위로 묻지 않고 바로 실패한다`() {
        downstream.silent += STUCK
        assertTrue(millis { assertNull(port.snapshot(STUCK)) } >= 900, "시험의 전제: 첫 호출은 기한까지 걸린다")
        assertEquals(clock.plus(port.backoff), port.backingOffUntil(STUCK))

        val spent = millis {
            assertNull(port.snapshot(STUCK))
            val e = assertFailsWith<StatusRuntimeException> { port.cancel(STUCK, handle(STUCK)) }
            assertEquals(Status.Code.UNAVAILABLE, e.status.code)
            assertFailsWith<StatusRuntimeException> { port.start(STUCK, "t-2", 1, "pick_place", emptyMap()) }
            assertNull(port.replay(STUCK, 0))
        }
        assertTrue(spent < 500, "차단 창 안인데 기한을 또 썼다: ${spent}ms")
        assertEquals(1, downstream.snapshots[STUCK], "차단 창 안인데 스냅숏을 하위로 보냈다")
        assertNull(downstream.cancels[STUCK], "차단 창 안인데 취소를 하위로 보냈다")
        assertNull(downstream.others[STUCK], "차단 창 안인데 출발이나 재생을 하위로 보냈다")
    }

    @Test
    fun `차단 창이 지나면 한 번 다시 묻고 답이 오면 창을 걷는다`() {
        downstream.silent += STUCK
        assertNull(port.snapshot(STUCK))
        downstream.silent -= STUCK

        clock = clock.plus(port.backoff).minusMillis(1)
        assertNull(port.snapshot(STUCK))
        assertEquals(1, downstream.snapshots[STUCK], "창이 지나기 전에 다시 물었다")

        clock = clock.plusMillis(1)
        assertNotNull(port.snapshot(STUCK), "창이 지났는데 안 물었다")
        assertEquals(2, downstream.snapshots[STUCK])
        assertNull(port.backingOffUntil(STUCK), "답이 왔는데 창이 남았다")
        assertNotNull(port.snapshot(STUCK))
        assertEquals(3, downstream.snapshots[STUCK])
    }

    @Test
    fun `한 기체의 차단 창은 다른 기체를 막지 않는다`() {
        downstream.silent += STUCK
        assertNull(port.snapshot(STUCK))

        assertNotNull(port.snapshot(OTHER))
        assertEquals(1, downstream.snapshots[OTHER])
        assertNull(port.backingOffUntil(OTHER))
    }

    @Test
    fun `바로 온 오류에는 차단 창을 걸지 않는다`() {
        downstream.refusing[STUCK] = Status.UNAVAILABLE.withDescription("기체의 결함을 지금 못 본다")
        assertNull(port.snapshot(STUCK))
        assertNull(port.backingOffUntil(STUCK), "빨리 온 UNAVAILABLE 에 창을 걸었다")
        assertNull(port.snapshot(STUCK))
        assertEquals(2, downstream.snapshots[STUCK])

        downstream.refusing[STUCK] = Status.NOT_FOUND
        repeat(2) { assertFailsWith<StatusRuntimeException> { port.cancel(STUCK, handle(STUCK)) } }
        assertEquals(2, downstream.cancels[STUCK], "하위가 답한 오류 뒤에 취소를 막았다")
    }

    private companion object {
        const val STUCK = "robot-stuck"
        const val OTHER = "robot-other"
    }
}
