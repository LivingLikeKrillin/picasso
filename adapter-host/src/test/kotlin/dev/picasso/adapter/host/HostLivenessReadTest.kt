package dev.picasso.adapter.host

import dev.picasso.adapter.core.Acceptance
import dev.picasso.adapter.core.Applied
import dev.picasso.adapter.core.FaultObservation
import dev.picasso.adapter.core.HoldObservation
import dev.picasso.adapter.core.RobotAdapter
import dev.picasso.adapter.core.SiteNames
import dev.picasso.contracts.v1.EventServiceGrpc
import dev.picasso.contracts.v1.GetSnapshotRequest
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.wire.RequestHeaders
import dev.picasso.profile.ProfileDocument
import dev.picasso.uplink.Publisher
import dev.picasso.uplink.report.RegistryLink
import dev.picasso.uplink.report.SiteNameSummary
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.ServerBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 생존 보고의 내용은 펌프가 호스트 잠금 밖에서 읽어 둔다(§15.271).
 *
 * 상태 발행은 호스트 잠금 아래에서 오고, 그 발행이 싣는 사이트 이름 요약은 사우스바운드 왕복이다. 발행할 때 어댑터에 물으면 링크가
 * 답하지 않는 동안 링크를 안 쓰는 RPC 까지 선다.
 */
class HostLivenessReadTest {

    /** [closed] 인 동안 `knownSiteNames` 가 [release] 를 기다리는 어댑터. 이름은 [names], 부른 수는 [asked] 다. */
    private class NamesAdapter : RobotAdapter {
        @Volatile var names: List<String> = listOf("DOCK-1", "DOCK-2")
        val asked = AtomicInteger()
        val closed = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override val state: TaskState get() = TaskState.TASK_STATE_UNSPECIFIED
        override fun accept(taskId: String, skillType: String, parameters: Map<String, Any>, startedAt: Instant): Acceptance =
            Acceptance.Accepted(taskId)
        override fun poll(now: Instant): TaskState = TaskState.TASK_STATE_UNSPECIFIED
        override fun pause(): Applied = Applied.Ok
        override fun cancel(): Applied = Applied.Ok
        override fun hold(): HoldObservation = HoldObservation.Empty
        override fun faults(): FaultObservation = FaultObservation.Observed(emptyList())
        override fun knownSiteNames(): SiteNames {
            asked.incrementAndGet()
            if (closed.get()) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            return SiteNames.Known(names)
        }
    }

    /**
     * 운영 배치의 모양 — 실 포트, 펌프 스레드가 있어 읽기 RPC 는 펌프를 안 지난다. 발행은 레지스트리 적재로 감싼다. 레지스트리
     * 주소는 아무도 안 듣는 포트다 — 생존 보고의 내용은 발행한 스레드에서 정해지고 보내는 것은 큐의 작업 스레드다.
     */
    private class World : AutoCloseable {
        val adapter = NamesAdapter()
        var now: Instant = Instant.parse("2026-11-06T00:00:00Z")
        val registry = RegistryLink.http("http://127.0.0.1:1", "test-token", "default", fallbackDir = null)
        private var hosted: HostedRobot? = null
        val robot = HostedRobot(
            ROBOT,
            ProfileDocument.parse("test", Files.readString(PROFILE)).getOrThrow(),
            adapter,
            publisher = HostUplink.wrap(registry, Publisher.NONE) { checkNotNull(hosted) },
        ) { now }.also { hosted = it }
        val host = AdapterHost(robot, ServerBuilder.forPort(0), pumpOnRead = false).start()
        val channel: ManagedChannel = ManagedChannelBuilder.forAddress("127.0.0.1", host.port).usePlaintext().build()
        val events: EventServiceGrpc.EventServiceBlockingStub = EventServiceGrpc.newBlockingStub(channel)

        fun snapshot() = events.withDeadlineAfter(2, TimeUnit.SECONDS).getSnapshot(
            GetSnapshotRequest.newBuilder()
                .setHeader(RequestHeaders.build("picasso.v1.GetSnapshotRequest", ROBOT, "test-client"))
                .setRobotId(ROBOT).build(),
        )

        override fun close() {
            adapter.release.countDown()
            channel.shutdownNow()
            host.shutdown()
            registry.close()
        }
    }

    @Test
    fun `상태 발행의 생존 보고가 사이트 이름을 묻는 동안에도 스냅숏은 그 물음을 기다리지 않는다`() {
        World().use { w ->
            w.snapshot() // 채널을 연다 — 첫 호출의 연결 수립을 재는 시간에 넣지 않는다.
            // 기동 뒤 첫 펌프가 상태 발행 차례다. 그 차례의 사이트 이름 조회를 붙든다.
            w.adapter.closed.set(true)
            val pump = thread(name = "pump") { runCatching { w.host.pump() } }
            try {
                assertTrue(w.adapter.entered.await(5, TimeUnit.SECONDS), "상태 발행 차례에 사이트 이름을 묻지 않았다")
                val started = System.nanoTime()
                val failure = runCatching { w.snapshot() }.exceptionOrNull()
                val waited = Duration.ofNanos(System.nanoTime() - started)
                assertTrue(failure == null && waited < Duration.ofMillis(500), "스냅숏이 사이트 이름 조회를 기다렸다: $waited ($failure)")
            } finally {
                w.adapter.release.countDown()
                pump.join(10_000)
            }
        }
    }

    @Test
    fun `기동 때 생존 보고의 내용을 읽어 두고 상태 발행 차례에만 다시 읽는다`() {
        World().use { w ->
            val two = SiteNameSummary(unsupported = false, count = 2)
            assertEquals(two, w.robot.liveness?.siteNames, "기동 때 생존 보고의 내용을 읽어 두지 않았다")
            assertEquals(1, w.adapter.asked.get())

            w.host.pump() // 첫 상태 발행 차례
            assertEquals(2, w.adapter.asked.get())

            w.adapter.names = listOf("DOCK-1", "DOCK-2", "DOCK-3")
            w.host.pump() // 같은 시각, 발행 차례가 아니다
            assertEquals(2, w.adapter.asked.get(), "상태 발행 차례가 아닌데 사이트 이름을 물었다")
            assertEquals(two, w.robot.liveness?.siteNames)

            w.now = w.now.plusSeconds(w.robot.document.publishIntervalMaxSeconds.toLong())
            w.host.pump() // 다음 발행 차례
            assertEquals(3, w.adapter.asked.get())
            assertEquals(SiteNameSummary(unsupported = false, count = 3), w.robot.liveness?.siteNames, "발행 차례에 다시 읽지 않았다")
        }
    }

    private companion object {
        const val ROBOT = "fake-01"
        val PROFILE: Path = Path.of("..", "profile", "fixtures", "minimal.json").normalize()
    }
}
