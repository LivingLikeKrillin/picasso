package dev.picasso.middleware

import dev.picasso.client.PicassoClient
import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.EventServiceGrpc
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.FaultObservability
import dev.picasso.contracts.v1.GetSnapshotRequest
import dev.picasso.contracts.v1.GetSnapshotResponse
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskSnapshot
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.WatchTaskResponse
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 결함을 못 보는 기체의 스냅숏(§15.263, 계약 0.10.0 `FaultObservability`). 스냅숏은 읽히되 결함만 모른다 — 태스크·연결로 하는 일
 * (연결 끊김 판정)은 그대로 하고, 결함으로 하는 일(막는 관문, 수용 놓기)은 스냅숏을 못 읽은 라운드와 같이 다룬다.
 *
 * 에뮬레이터는 늘 결함을 보므로(`UNSPECIFIED`) 결함을 감추는 포트로 본다. 계약 포트가 그 값을 읽는지는 시험의 서버로 본다.
 */
class FaultBlindSnapshotTest {

    @Test
    fun `계약 포트는 NOT_OBSERVABLE 스냅숏을 결함 미관측으로 읽고 태스크는 그대로 든다`() {
        val events = object : EventServiceGrpc.EventServiceImplBase() {
            override fun getSnapshot(request: GetSnapshotRequest, observer: StreamObserver<GetSnapshotResponse>) {
                val response = GetSnapshotResponse.newBuilder()
                    .setSequence(3)
                    .addTasks(TaskSnapshot.newBuilder().setTaskId("t-1").setState(TaskState.TASK_STATE_RUNNING).setRevision(1))
                    .setConnectionState(ConnectionState.CONNECTION_STATE_ONLINE)
                if (request.robotId == A) {
                    response.setFaultObservability(FaultObservability.FAULT_OBSERVABILITY_NOT_OBSERVABLE)
                } else {
                    response.addFaults(FAULT)
                }
                observer.onNext(response.build())
                observer.onCompleted()
            }
        }
        val name = InProcessServerBuilder.generateName()
        val server = InProcessServerBuilder.forName(name).addService(events).build().start()
        val channel = InProcessChannelBuilder.forName(name).build()
        try {
            val port = ClientRobotPort(PicassoClient(channel, "line-controller"), now = { T0 })

            val blind = assertNotNull(port.snapshot(A), "결함을 못 본 스냅숏을 못 읽은 것으로 접었다")
            assertFalse(blind.faultsObservable, "NOT_OBSERVABLE 을 결함을 본 것으로 읽었다")
            assertTrue(blind.faults.isEmpty())
            assertEquals(mapOf("t-1" to TaskState.TASK_STATE_RUNNING), blind.tasks)

            // 옛 발신자는 이 칸을 안 싣는다(UNSPECIFIED) — 결함을 본 것이다.
            val seen = assertNotNull(port.snapshot(B))
            assertTrue(seen.faultsObservable, "UNSPECIFIED 를 결함을 못 본 것으로 읽었다")
            assertEquals(listOf(FAULT.errorType), seen.faults.map { it.errorType })
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `결함을 못 보는 기체도 연결이 끊기면 LINK_BROKEN 이다`() {
        World().use { w ->
            w.robots.blind += A
            val exec = w.submit()
            w.mw.pump()
            assertEquals(listOf("$ORDER#u1"), w.robots.started, "시험의 전제: 결함을 못 봐도 첫 단위가 나간다")

            w.robots.connection[A] = ConnectionState.CONNECTION_STATE_OFFLINE
            w.mw.pump()
            assertTrue(exec.eventTrail.any { it.kind == "LINK_BROKEN" }, "결함을 못 본 스냅숏의 연결을 안 읽었다: ${exec.eventTrail.map { it.kind }}")
            assertEquals(PhysicalState.IN_DOUBT, exec.physicalState)
        }
    }

    @Test
    fun `결함을 못 본다는 스냅숏은 막고 있던 결함을 걷지 않는다`() {
        World().use { w ->
            w.robots.faults[A] = listOf(FAULT)
            val exec = w.submit()
            w.mw.pump()
            assertEquals(PhysicalState.OPERATOR_HOLD, exec.physicalState, "시험의 전제: 결함이 첫 단위를 막는다")

            w.robots.blind += A
            w.mw.pump()
            assertEquals(PhysicalState.OPERATOR_HOLD, exec.physicalState, "결함을 못 본 스냅숏을 결함 없음으로 읽어 막음을 걷었다")
            assertEquals(listOf(FAULT.errorType), exec.blockedBy.map { it.errorType })
            assertTrue(w.robots.started.isEmpty(), "막힌 기체에 단위가 나갔다: ${w.robots.started}")
        }
    }

    @Test
    fun `결함을 못 본다는 스냅숏으로는 수용을 놓지 않는다`() {
        World().use { w ->
            w.robots.faults[A] = listOf(FAULT)
            val exec = w.submit()
            w.mw.pump()
            assertTrue(w.mw.release(exec.executionId))
            w.mw.pump()
            assertEquals(setOf(KEY), exec.acknowledgedFaults, "시험의 전제: 운영자가 결함을 수용했다")

            w.robots.blind += A
            w.mw.pump()
            assertEquals(setOf(KEY), exec.acknowledgedFaults, "결함을 못 본 스냅숏으로 수용을 놓았다")
            assertTrue(exec.eventTrail.none { it.kind == Middleware.ACKNOWLEDGED_FAULT_CLEARED }, "놓았다는 기록이 남았다")
        }
    }

    /** 기체마다 결함·연결·태스크를 손으로 세우는 포트. [blind] 의 기체는 결함을 비우고 못 봤다고 싣는다. */
    private class Robots(private val now: () -> Instant) : RobotPort {
        val faults = mutableMapOf<String, List<Fault>>()
        val connection = mutableMapOf<String, ConnectionState>()
        val blind = mutableSetOf<String>()
        val started = mutableListOf<String>()
        private val tasks = mutableMapOf<String, LinkedHashMap<String, TaskState>>()

        override fun capabilities(robotId: String): Capability? = null

        override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse {
            if (tasks.getOrPut(robotId) { linkedMapOf() }.putIfAbsent(taskId, TaskState.TASK_STATE_RUNNING) == null) started += taskId
            return StartTaskResponse.newBuilder()
                .setHandle(TaskHandle.newBuilder().setTaskId(taskId).setRevision(revision).setRobotId(robotId))
                .build()
        }

        override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> {
            val state = tasks[robotId]?.get(handle.taskId) ?: return emptyList()
            return listOf(
                WatchTaskResponse.newBuilder()
                    .setHeader(MessageHeader.newBuilder().setStateAsOf(now().toString()))
                    .setState(state)
                    .setRevision(handle.revision)
                    .setHold(HoldState.newBuilder().setKind(HoldKind.HOLD_KIND_EMPTY))
                    .build(),
            )
        }

        override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse = CancelTaskResponse.getDefaultInstance()

        override fun snapshot(robotId: String): RobotSnapshot {
            val seen = robotId !in blind
            return RobotSnapshot(
                sequence = 0,
                faults = if (seen) faults[robotId].orEmpty() else emptyList(),
                connection = connection[robotId] ?: ConnectionState.CONNECTION_STATE_ONLINE,
                tasks = tasks[robotId].orEmpty().toMap(),
                faultsObservable = seen,
            )
        }

        override fun replay(robotId: String, from: Long): Replay = Replay.Events(emptyList())
    }

    /** 단위 둘, 둘 다 실행의 기체. */
    private class Two : LogicalCapability {
        override val workMasterId: String = WORK_MASTER
        override val maxEvidence: Evidence = Evidence.E2

        override fun plan(order: JobOrder): List<ExecutionUnit> = listOf(unit("u1", "Z1/a"), unit("u2", "Z2/a"))
    }

    private class World : AutoCloseable {
        val robots = Robots { T0 }
        val mw = Middleware(robots, capabilities = listOf(Two()), now = { T0 })

        fun submit(): Middleware.Execution = (mw.submit(JobOrder(ORDER, WORK_MASTER, 1), A) as Middleware.Submission.Accepted).execution

        override fun close() = Unit
    }

    private companion object {
        const val A = "amr-a"
        const val B = "amr-b"
        const val ORDER = "BLIND-263"
        const val WORK_MASTER = "Two"
        const val KEY = "ARM_FAULT[]"
        val T0: Instant = Instant.parse("2026-11-04T00:00:00Z")
        val FAULT: Fault = Fault.newBuilder().setErrorType("ARM_FAULT").setCanAcceptNewTask(false).build()

        fun unit(id: String, to: String) = ExecutionUnit(
            unitId = id,
            route = Route.ROBOT,
            skillType = "navigate_to",
            parameters = emptyMap(),
            expectedIdentity = null,
            source = null,
            destination = to,
        )
    }
}
