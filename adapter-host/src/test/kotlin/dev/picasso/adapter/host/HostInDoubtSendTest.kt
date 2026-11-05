package dev.picasso.adapter.host

import dev.picasso.adapter.core.Acceptance
import dev.picasso.adapter.core.Applied
import dev.picasso.adapter.core.FaultObservation
import dev.picasso.adapter.core.HoldObservation
import dev.picasso.adapter.core.Refusal
import dev.picasso.adapter.core.RobotAdapter
import dev.picasso.adapter.core.SiteNames
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.StartTaskRequest
import dev.picasso.contracts.v1.TaskServiceGrpc
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.wire.RequestHeaders
import dev.picasso.profile.ProfileDocument
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.ServerBuilder
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 나갔는지 모르는 접수는 다시 보내지 않는다(§15.220).
 *
 * 링크 오류는 보내지 못한 것이라 같은 요청이 다시 오면 다시 보낸다. 요청이 나간 뒤 답을 못 받은 것은 다르다 — 로봇이 받았을 수 있고,
 * 다시 보내면 같은 일을 두 번 시킨다.
 */
class HostInDoubtSendTest {

    /** 접수를 [reason] 으로 거부하는 어댑터. 접수를 부른 수는 [accepts] 다. */
    private class FailingAdapter(val reason: Refusal) : RobotAdapter {
        val accepts = AtomicInteger()

        override val state: TaskState get() = TaskState.TASK_STATE_UNSPECIFIED
        override fun accept(taskId: String, skillType: String, parameters: Map<String, Any>, startedAt: Instant): Acceptance {
            accepts.incrementAndGet()
            return Acceptance.Refused(reason, "응답 시간 초과")
        }
        override fun poll(now: Instant): TaskState = TaskState.TASK_STATE_UNSPECIFIED
        override fun pause(): Applied = Applied.Ok
        override fun cancel(): Applied = Applied.Ok
        override fun hold(): HoldObservation = HoldObservation.Empty
        override fun faults(): FaultObservation = FaultObservation.Observed(emptyList())
        override fun knownSiteNames(): SiteNames = SiteNames.Unsupported
    }

    private class World(reason: Refusal) : AutoCloseable {
        val adapter = FailingAdapter(reason)
        val robot = HostedRobot(ROBOT, ProfileDocument.parse("test", Files.readString(PROFILE)).getOrThrow(), adapter)
        val host = AdapterHost(robot, ServerBuilder.forPort(0), pumpOnRead = false).start()
        val channel: ManagedChannel = ManagedChannelBuilder.forAddress("127.0.0.1", host.port).usePlaintext().build()
        val tasks: TaskServiceGrpc.TaskServiceBlockingStub = TaskServiceGrpc.newBlockingStub(channel)

        /** 같은 접수를 보내고 그 gRPC 상태를 돌려준다. */
        fun start(): Status.Code = assertFailsWith<StatusRuntimeException> {
            tasks.withDeadlineAfter(10, TimeUnit.SECONDS).startTask(
                StartTaskRequest.newBuilder()
                    .setHeader(RequestHeaders.build("picasso.v1.StartTaskRequest", ROBOT, "test-client"))
                    .setTaskId("T-1").setRevision(1).setRobotId(ROBOT).setSkillType("pick_place")
                    .addAllParameters(PICK.map { (k, v) -> ParameterValue.newBuilder().setKey(k).setStringValue(v).build() })
                    .build(),
            )
        }.status.code

        override fun close() {
            channel.shutdownNow()
            host.shutdown()
        }
    }

    @Test
    fun `나갔는지 모르는 접수는 UNKNOWN 으로 답하고 같은 접수가 다시 와도 보내지 않는다`() {
        World(Refusal.LINK_IN_DOUBT).use { w ->
            assertEquals(Status.Code.UNKNOWN, w.start(), "나갔는지 모르는 접수를 다시 보내도 되는 답으로 접었다")
            assertEquals(Status.Code.UNKNOWN, w.start())
            assertEquals(1, w.adapter.accepts.get(), "나갔는지 모르는 접수를 남쪽에 ${w.adapter.accepts.get()} 번 보냈다")
            assertEquals(null, w.robot.task("T-1"), "받았는지 모르는 것을 장부에 적었다")
        }
    }

    @Test
    fun `보내지 못한 접수는 UNAVAILABLE 로 답하고 같은 접수가 다시 오면 다시 보낸다`() {
        World(Refusal.LINK_ERROR).use { w ->
            assertEquals(Status.Code.UNAVAILABLE, w.start())
            assertEquals(Status.Code.UNAVAILABLE, w.start())
            assertEquals(2, w.adapter.accepts.get(), "보내지 못한 접수를 다시 보내지 않았다")
        }
    }

    private companion object {
        const val ROBOT = "fake-01"
        val PROFILE: Path = Path.of("..", "profile", "fixtures", "minimal.json").normalize()
        val PICK = mapOf("object_id" to "SEQ-IN-02.BIN-A", "destination" to "RACK-204.S01")
    }
}
