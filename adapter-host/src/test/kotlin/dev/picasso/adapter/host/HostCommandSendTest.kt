package dev.picasso.adapter.host

import dev.picasso.adapter.core.Acceptance
import dev.picasso.adapter.core.Applied
import dev.picasso.adapter.core.FaultObservation
import dev.picasso.adapter.core.HoldObservation
import dev.picasso.adapter.core.RobotAdapter
import dev.picasso.adapter.core.SiteNames
import dev.picasso.contracts.v1.EventServiceGrpc
import dev.picasso.contracts.v1.GetKnownSiteNamesRequest
import dev.picasso.contracts.v1.GetSnapshotRequest
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.SkillServiceGrpc
import dev.picasso.contracts.v1.StartTaskRequest
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskServiceGrpc
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.wire.RequestHeaders
import dev.picasso.profile.ProfileDocument
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 명령은 호스트 잠금 밖에서 사우스바운드에 보낸다(§15.269).
 *
 * 플릿이 명령에 답하지 않는 동안 그 명령은 링크 잠금만 쥐고 선다. 링크를 안 쓰는 RPC 는 그것을 기다리지 않아야 하고, 보내는 자리가
 * 배타 검사를 대신해야 한다 — 같은 접수는 합류하고 다른 접수는 거부되며 남쪽에는 한 번만 나간다.
 */
class HostCommandSendTest {

    /** [Gate] 를 든 멤버는 그 문이 열릴 때까지 선다. `poll` 은 [next] 가 있으면 그 상태로 옮긴다. */
    private class GatedAdapter : RobotAdapter {
        @Volatile var current: TaskState = TaskState.TASK_STATE_UNSPECIFIED
        @Volatile var next: TaskState? = null
        val accepts = AtomicInteger()
        val accept = Gate()
        val update = Gate()
        val siteNames = Gate()

        override val state: TaskState get() = current
        override fun accept(taskId: String, skillType: String, parameters: Map<String, Any>, startedAt: Instant): Acceptance {
            accepts.incrementAndGet()
            accept.pass()
            current = TaskState.TASK_STATE_RUNNING
            return Acceptance.Accepted(taskId)
        }
        override fun update(taskId: String, skillType: String, parameters: Map<String, Any>, at: Instant): Applied {
            update.pass()
            return Applied.Ok
        }
        override fun poll(now: Instant): TaskState {
            next?.let { current = it }
            return current
        }
        override fun pause(): Applied = Applied.Ok
        override fun cancel(): Applied {
            current = TaskState.TASK_STATE_CANCELLED
            return Applied.Ok
        }
        override fun hold(): HoldObservation = HoldObservation.Empty
        override fun faults(): FaultObservation = FaultObservation.Observed(emptyList())
        override fun knownSiteNames(): SiteNames {
            siteNames.pass()
            return SiteNames.Unsupported
        }
    }

    /** 닫혀 있으면 지나는 쪽을 [release] 까지 세운다. */
    private class Gate {
        val closed = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        fun pass() {
            if (!closed.get()) return
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        }

        fun open() = release.countDown()
    }

    private class World : AutoCloseable {
        val adapter = GatedAdapter()
        val robot = HostedRobot(ROBOT, ProfileDocument.parse("test", Files.readString(PROFILE)).getOrThrow(), adapter)

        // 실 포트, 펌프 스레드가 있는 배치의 모양.
        val host = AdapterHost(robot, ServerBuilder.forPort(0), pumpOnRead = false).start()
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

        fun snapshot() = events.withDeadlineAfter(2, TimeUnit.SECONDS)
            .getSnapshot(GetSnapshotRequest.newBuilder().setHeader(header("picasso.v1.GetSnapshotRequest")).setRobotId(ROBOT).build())

        fun siteNames() = skills.withDeadlineAfter(10, TimeUnit.SECONDS)
            .getKnownSiteNames(GetKnownSiteNamesRequest.newBuilder().setHeader(header("picasso.v1.GetKnownSiteNamesRequest")).build())

        /** 접수 하나를 플릿 앞에 세운 채 [block] 을 돈다. 그 접수의 답은 [first] 에 남는다. */
        fun <T> whileStartHeld(first: AtomicReference<Result<StartTaskResponse>>, block: () -> T): T {
            adapter.accept.closed.set(true)
            val sender = thread(name = "sender") { first.set(runCatching { start("T-1") }) }
            try {
                assertTrue(adapter.accept.entered.await(5, TimeUnit.SECONDS), "접수가 사우스바운드에 들지 않았다")
                return block()
            } finally {
                adapter.accept.open()
                sender.join(10_000)
            }
        }

        override fun close() {
            listOf(adapter.accept, adapter.update, adapter.siteNames).forEach { it.open() }
            channel.shutdownNow()
            host.shutdown()
        }
    }

    private fun robot(adapter: RobotAdapter) =
        HostedRobot(ROBOT, ProfileDocument.parse("test", Files.readString(PROFILE)).getOrThrow(), adapter)

    private fun timed(block: () -> Unit): Pair<Duration, Throwable?> {
        val started = System.nanoTime()
        val failure = runCatching(block).exceptionOrNull()
        return Duration.ofNanos(System.nanoTime() - started) to failure
    }

    @Test
    fun `명령이 남쪽에 서 있는 동안에도 스냅숏은 그 명령을 기다리지 않는다`() {
        World().use { w ->
            val first = AtomicReference<Result<StartTaskResponse>>()
            val (waited, failure) = w.whileStartHeld(first) { timed { w.snapshot() } }
            assertTrue(failure == null && waited < Duration.ofMillis(500), "스냅숏이 명령의 남쪽 송신을 기다렸다: $waited ($failure)")
            assertTrue(first.get().getOrThrow().hasHandle())
        }
    }

    @Test
    fun `보내는 중인 접수에 같은 태스크와 리비전이 오면 합류하고 남쪽에는 한 번만 보낸다`() {
        World().use { w ->
            val first = AtomicReference<Result<StartTaskResponse>>()
            val second = AtomicReference<Result<StartTaskResponse>>()
            w.whileStartHeld(first) {
                val joiner = thread(name = "joiner") { second.set(runCatching { w.start("T-1") }) }
                // 합류한 쪽은 앞 접수가 답할 때까지 답하지 않는다.
                joiner.join(300)
                assertEquals(null, second.get(), "합류하지 않고 먼저 답했다: ${second.get()}")
                w.adapter.accept.open()
                joiner.join(10_000)
            }
            val answers = listOf(first.get().getOrThrow(), second.get().getOrThrow())
            assertTrue(answers.all { it.hasHandle() && it.handle.taskId == "T-1" }, "두 답이 같은 핸들이 아니다: $answers")
            assertEquals(1, w.adapter.accepts.get(), "남쪽에 접수가 ${w.adapter.accepts.get()} 번 나갔다")
        }
    }

    @Test
    fun `보내는 중에 온 다른 태스크는 그 답을 기다리지 않고 거부한다`() {
        World().use { w ->
            val first = AtomicReference<Result<StartTaskResponse>>()
            var other: StartTaskResponse? = null
            val (waited, failure) = w.whileStartHeld(first) { timed { other = w.start("T-2") } }
            assertTrue(failure == null && waited < Duration.ofMillis(500), "다른 태스크가 보내는 중인 접수를 기다렸다: $waited ($failure)")
            assertEquals(RejectionCode.REJECTION_CODE_INVALID_TRANSITION, other?.rejection?.code, "거부가 아니다: $other")
            assertTrue(first.get().getOrThrow().hasHandle())
            assertEquals(1, w.adapter.accepts.get(), "남쪽에 접수가 ${w.adapter.accepts.get()} 번 나갔다")
        }
    }

    @Test
    fun `보내는 중인 명령의 태스크 전이는 펌프가 적지 않는다`() {
        val adapter = GatedAdapter()
        val robot = robot(adapter)
        val task = (robot.start("T-1", 1, "pick_place", PICK) as HostedRobot.StartOutcome.Accepted).task
        assertEquals(TaskState.TASK_STATE_RUNNING, task.last.state)

        adapter.update.closed.set(true)
        val updater = thread(name = "updater") { robot.start("T-1", 2, "pick_place", PICK) }
        assertTrue(adapter.update.entered.await(5, TimeUnit.SECONDS), "갱신이 사우스바운드에 들지 않았다")

        // 갱신이 링크를 놓은 뒤 확정하기 전에 펌프가 읽고 적는 차례다 — 확정은 이 잠금을 기다린다. 그 읽기의 답은 성공이다.
        adapter.next = TaskState.TASK_STATE_SUCCEEDED
        synchronized(robot.host) {
            adapter.update.open()
            val reading = robot.read(robot.plan())
            robot.apply(reading)
        }
        updater.join(10_000)
        robot.pump()

        assertEquals(
            listOf(TaskState.TASK_STATE_ACCEPTED, TaskState.TASK_STATE_RUNNING, TaskState.TASK_STATE_RUNNING, TaskState.TASK_STATE_SUCCEEDED),
            task.updates.map { it.state },
            "보내는 중에 펌프가 전이를 적어 갱신 한 줄이 종착 뒤에 붙었다",
        )
        assertEquals(2, task.updates[2].revision)
    }

    @Test
    fun `조작은 보내는 중인 명령의 링크가 아니라 그 자리를 기다린다`() {
        val adapter = GatedAdapter()
        val robot = robot(adapter)
        val task = (robot.start("T-1", 1, "pick_place", PICK) as HostedRobot.StartOutcome.Accepted).task

        adapter.update.closed.set(true)
        val updater = thread(name = "updater") { robot.start("T-1", 2, "pick_place", PICK) }
        assertTrue(adapter.update.entered.await(5, TimeUnit.SECONDS), "갱신이 사우스바운드에 들지 않았다")
        val canceller = thread(name = "canceller") { robot.manipulate("T-1") { robot.adapter.cancel() } }

        // 자리를 기다리면 미래를 기다리며 쉬고(WAITING), 링크를 기다리면 모니터 앞에 막힌다(BLOCKED).
        val deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
        while (canceller.state !in setOf(Thread.State.WAITING, Thread.State.BLOCKED) && System.nanoTime() < deadline) Thread.sleep(5)
        val waiting = canceller.state
        adapter.update.open()
        updater.join(10_000)
        canceller.join(10_000)

        assertEquals(Thread.State.WAITING, waiting, "조작이 보내는 자리를 지나 링크 앞에 섰다")
        assertEquals(
            listOf(TaskState.TASK_STATE_ACCEPTED, TaskState.TASK_STATE_RUNNING, TaskState.TASK_STATE_RUNNING, TaskState.TASK_STATE_CANCELLED),
            task.updates.map { it.state },
        )
    }

    @Test
    fun `사이트 이름을 묻는 동안에도 스냅숏은 그 물음을 기다리지 않는다`() {
        World().use { w ->
            w.adapter.siteNames.closed.set(true)
            val asker = thread(name = "asker") { runCatching { w.siteNames() } }
            try {
                assertTrue(w.adapter.siteNames.entered.await(5, TimeUnit.SECONDS), "사이트 이름 조회가 사우스바운드에 들지 않았다")
                val (waited, failure) = timed { w.snapshot() }
                assertTrue(failure == null && waited < Duration.ofMillis(500), "스냅숏이 사이트 이름 조회를 기다렸다: $waited ($failure)")
            } finally {
                w.adapter.siteNames.open()
                asker.join(10_000)
            }
        }
    }

    private companion object {
        const val ROBOT = "fake-01"
        val PROFILE: Path = Path.of("..", "profile", "fixtures", "minimal.json").normalize()
        val PICK = mapOf("object_id" to "SEQ-IN-02.BIN-A", "destination" to "RACK-204.S01")
    }
}
