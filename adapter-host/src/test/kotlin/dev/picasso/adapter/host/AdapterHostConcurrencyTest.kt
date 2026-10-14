package dev.picasso.adapter.host

import dev.picasso.adapter.core.Acceptance
import dev.picasso.adapter.core.Applied
import dev.picasso.adapter.core.FaultObservation
import dev.picasso.adapter.core.HoldObservation
import dev.picasso.adapter.core.ProgressObservation
import dev.picasso.adapter.core.RobotAdapter
import dev.picasso.adapter.core.SiteNames
import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.EventServiceGrpc
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.GetKnownSiteNamesRequest
import dev.picasso.contracts.v1.GetSnapshotRequest
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.ReplayEventsRequest
import dev.picasso.contracts.v1.SkillServiceGrpc
import dev.picasso.contracts.v1.StartTaskRequest
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskServiceGrpc
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.wire.RequestHeaders
import dev.picasso.profile.ProfileDocument
import dev.picasso.uplink.Publication
import dev.picasso.uplink.Publisher
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.ServerBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **실 포트의 호스트는 여러 스레드에서 불린다.** 다른 시험은 in-process 전송의 `directExecutor` 로 한 스레드에서만
 * 돌아 이 축을 한 번도 안 지났다. 여기서는 netty 서버의 기본 실행기(RPC 마다 gRPC 스레드)와 배치의 펌프 스레드가
 * 같은 기체의 기록(태스크 장부·도는 태스크·재생 버퍼·결함 표)을 동시에 만진다 — 런처가 실제로 세우는 모양이다.
 *
 * 시간에 기대는 시험이라 **창을 넓히는 쪽**으로 짠다: 접수는 남쪽 왕복만큼 늦고(실물 어댑터의 `accept` 는 벤더
 * 호출이다), 출발은 걸쇠로 맞추고, 같은 판을 여러 번 돈다.
 */
class AdapterHostConcurrencyTest {

    /**
     * 남쪽이 느린 어댑터. **이 어댑터 자신은 스레드에 안전하다** — 깨지는 것이 호스트인지 시험 대역인지 가르려고.
     * 결함은 부를 때마다 났다 사라졌다 한다: 펌프 한 번이 결함 사건 하나를 정확히 낸다.
     */
    private class SlowAdapter(private val acceptMillis: Long) : RobotAdapter {
        val accepts = AtomicInteger()
        private val faultCalls = AtomicLong()
        @Volatile private var current: TaskState = TaskState.TASK_STATE_UNSPECIFIED

        override val state: TaskState get() = current
        override fun accept(taskId: String, skillType: String, parameters: Map<String, Any>, startedAt: Instant): Acceptance {
            accepts.incrementAndGet()
            Thread.sleep(acceptMillis)
            current = TaskState.TASK_STATE_RUNNING
            return Acceptance.Accepted(taskId)
        }
        override fun poll(now: Instant): TaskState = current
        override fun pause(): Applied = Applied.Ok
        override fun resume(parameters: Map<String, Any>): Applied = Applied.Ok
        override fun retry(parameters: Map<String, Any>): Applied = Applied.Ok
        override fun cancel(): Applied = Applied.Ok
        override fun update(taskId: String, skillType: String, parameters: Map<String, Any>, at: Instant): Applied = Applied.Ok
        override fun hold(): HoldObservation = HoldObservation.Empty
        override fun faults(): FaultObservation =
            if (faultCalls.getAndIncrement() % 2 == 0L) FaultObservation.Observed(listOf(FAULT)) else FaultObservation.Observed(emptyList())
        override fun failure(): Fault? = null
        override fun result(): String? = null
        override fun knownSiteNames(): SiteNames = SiteNames.Unsupported
        override fun progress(): ProgressObservation = ProgressObservation.NotObservable("시험")
    }

    /** 받은 차례 그대로 적는다. 발행 순서를 묻는 시험이므로 적는 것 자체는 한 줄로 세운다. */
    private class OrderedPublisher : Publisher {
        val received: MutableList<Publication> = Collections.synchronizedList(mutableListOf())
        override fun publish(publication: Publication) { received += publication }
        fun snapshot(): List<Publication> = synchronized(received) { received.toList() }
    }

    private class World(acceptMillis: Long = 0) : AutoCloseable {
        val adapter = SlowAdapter(acceptMillis)
        val published = OrderedPublisher()
        val robot = HostedRobot(ROBOT, ProfileDocument.parse("test", Files.readString(PROFILE)).getOrThrow(), adapter, publisher = published)
        // **실 포트 · 기본 실행기** — 런처의 `ServerBuilder.forPort` 와 같은 모양. directExecutor 를 쓰면 이 시험이 무의미해진다.
        val host = AdapterHost(robot, ServerBuilder.forPort(0)).start()
        val channel: ManagedChannel = ManagedChannelBuilder.forAddress("127.0.0.1", host.port).usePlaintext().build()
        val tasks: TaskServiceGrpc.TaskServiceBlockingStub = TaskServiceGrpc.newBlockingStub(channel)
        val events: EventServiceGrpc.EventServiceBlockingStub = EventServiceGrpc.newBlockingStub(channel)
        val skills: SkillServiceGrpc.SkillServiceBlockingStub = SkillServiceGrpc.newBlockingStub(channel)

        fun header(schema: String) = RequestHeaders.build(schema, ROBOT, "test-client")

        fun start(taskId: String): StartTaskResponse = tasks.withDeadlineAfter(10, TimeUnit.SECONDS).startTask(
            StartTaskRequest.newBuilder().setHeader(header("picasso.v1.StartTaskRequest"))
                .setTaskId(taskId).setRevision(1).setRobotId(ROBOT).setSkillType("pick_place")
                .addAllParameters(PICK.map { (k, v) -> ParameterValue.newBuilder().setKey(k).setStringValue(v).build() })
                .build(),
        )

        override fun close() {
            channel.shutdownNow()
            host.shutdown()
        }
    }

    @Test
    fun `동시에 들어온 StartTask 는 하나만 받고 남쪽에도 한 번만 시킨다`() {
        // 실물은 배타적 제어 모델이다(§4.9). 둘이 다 «도는 태스크 없음» 을 보고 지나가면 로봇에 명령이 두 번 나간다 —
        // 응답 둘이 다 핸들이고 소비자 둘이 다 자기가 로봇을 쥐었다고 믿는다.
        repeat(ROUNDS) { round ->
            World(acceptMillis = 30).use { w ->
                val gate = CountDownLatch(1)
                val answers = Collections.synchronizedList(mutableListOf<Result<StartTaskResponse>>())
                val callers = (1..CALLERS).map { i ->
                    thread(name = "caller-$i") {
                        gate.await()
                        answers += runCatching { w.start("T-$round-$i") }
                    }
                }
                gate.countDown()
                callers.forEach { it.join(15_000) }

                val failed = answers.filter { it.isFailure }.map { it.exceptionOrNull() }
                assertTrue(failed.isEmpty(), "판 $round: RPC 가 깨졌다: $failed")
                val accepted = answers.mapNotNull { it.getOrNull() }.filter { it.hasHandle() }
                assertEquals(1, accepted.size, "판 $round: 배타 검사를 둘 이상이 지났다 — ${accepted.map { it.handle.taskId }}")
                assertEquals(1, w.adapter.accepts.get(), "판 $round: 남쪽에 접수가 ${w.adapter.accepts.get()} 번 나갔다")
                assertEquals(1, w.robot.tasks.size, "판 $round: 장부에 ${w.robot.tasks.map { it.taskId }}")
            }
        }
    }

    @Test
    fun `펌프 스레드와 RPC 가 겹쳐도 발행 순번은 빈틈없이 차례대로 오르고 결함은 났다 사라졌다를 번갈아 적는다`() {
        World().use { w ->
            val stop = System.nanoTime() + RUN_NANOS
            val broken = Collections.synchronizedList(mutableListOf<Throwable>())

            // 배치의 스케줄러 자리 — 런처의 펌프 스레드가 부르는 그것(`AdapterHost.pump`)을 쉬지 않고 부른다.
            val pump = thread(name = "pump") {
                while (System.nanoTime() < stop) runCatching { w.host.pump() }.onFailure { broken += it }
            }
            // 소비자 자리 — 서비스 셋을 다 두드린다. RPC 마다 펌프를 먼저 지나고, 재생 버퍼와 결함 표를 읽는다.
            val callers = (1..CALLERS).map { i ->
                thread(name = "caller-$i") {
                    while (System.nanoTime() < stop) {
                        runCatching {
                            w.events.withDeadlineAfter(10, TimeUnit.SECONDS)
                                .getSnapshot(GetSnapshotRequest.newBuilder().setHeader(w.header("picasso.v1.GetSnapshotRequest")).setRobotId(ROBOT).build())
                            w.events.withDeadlineAfter(10, TimeUnit.SECONDS)
                                .replayEvents(ReplayEventsRequest.newBuilder().setHeader(w.header("picasso.v1.ReplayEventsRequest")).setFromSequence(w.robot.sequence / 2).build())
                                .forEachRemaining { }
                            w.skills.withDeadlineAfter(10, TimeUnit.SECONDS)
                                .getKnownSiteNames(GetKnownSiteNamesRequest.newBuilder().setHeader(w.header("picasso.v1.GetKnownSiteNamesRequest")).build())
                        }.onFailure { broken += it }
                    }
                }
            }
            pump.join(RUN_NANOS / 1_000_000 + 15_000)
            callers.forEach { it.join(RUN_NANOS / 1_000_000 + 15_000) }

            assertTrue(broken.isEmpty(), "펌프나 RPC 가 깨졌다(${broken.size} 건): ${broken.take(3)}")

            val out = w.published.snapshot()
            // 세 스트림(상태·이벤트·연결)이 순번 축 하나를 같이 쓴다(§5.5). 낸 차례가 곧 번호 차례여야 하고 빈 번호가 없어야 한다.
            val sequences = out.map { it.sequence }
            assertEquals(sequences.indices.map { it.toLong() }, sequences, "발행 순번이 차례가 아니거나 빈다: 처음 어긋난 곳 ${firstBreak(sequences)}")

            // 결함 표는 펌프마다 바뀐다(났다·사라졌다). 같은 쪽이 두 번 연달아 적히면 두 펌프가 같은 옛 표에 대고 비교한 것이다.
            val cleared = out.mapNotNull { (it.message as? Event)?.takeIf(Event::hasFaultEvent)?.faultEvent?.cleared }
            assertTrue(cleared.size > 10, "결함 사건이 거의 안 났다 — 시험이 안 돈 것이다: ${cleared.size}")
            val repeated = cleared.zipWithNext().indexOfFirst { (a, b) -> a == b }
            assertEquals(-1, repeated, "결함 사건이 번갈지 않는다(${cleared.size} 건 중 $repeated 번째)")
        }
    }

    private fun firstBreak(sequences: List<Long>): String {
        val at = sequences.indices.firstOrNull { sequences[it] != it.toLong() } ?: return "없음"
        return "#$at: ${sequences.subList(maxOf(0, at - 2), minOf(sequences.size, at + 3))}"
    }

    private companion object {
        const val ROBOT = "fake-01"
        const val ROUNDS = 5
        const val CALLERS = 8
        const val RUN_NANOS = 1_500_000_000L
        val PROFILE: Path = Path.of("..", "profile", "fixtures", "minimal.json").normalize()
        val PICK = mapOf("object_id" to "SEQ-IN-02.BIN-A", "destination" to "RACK-204.S01")
        val FAULT: Fault = Fault.newBuilder().setErrorType("SKILL_EXECUTION_FAILED").build()
    }
}
