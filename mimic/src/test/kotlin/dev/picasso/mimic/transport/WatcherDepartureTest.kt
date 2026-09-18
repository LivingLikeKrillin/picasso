package dev.picasso.mimic.transport

import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.StartTaskRequest
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.WatchTaskRequest
import dev.picasso.contracts.v1.WatchTaskResponse
import dev.picasso.mimic.engine.TaskMachineFixtures
import dev.picasso.mimic.engine.VirtualClock
import io.grpc.stub.ClientCallStreamObserver
import io.grpc.stub.ClientResponseObserver
import io.grpc.stub.StreamObserver
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **떠난 소비자가 세계를 멈추지 않는다.**
 *
 * 취소된 `WatchTask` 스트림에 밀면 gRPC 가 `CANCELLED` 로 던진다. 그 예외는 `settleAll` 을 통째로
 * 끊으므로 **한 소비자가 떠났다는 사실이 다른 기체의 시간까지 멈춘다** — 결함이 실패가 아니라 정지로
 * 나타나는 모양이고, 이 저장소가 `MimicServer.advance` 를 셋을 묶은 한 함수로 만든 이유와 같은 자리다.
 *
 * 시험에서는 안 보였다. 기다리던 것이 오면 곧 끝나므로 그 다음 전진이 없었다. 오래 도는 구동기
 * (`ScenarioHost`)에서 드러났다.
 */
class WatcherDepartureTest {

    private val clock = VirtualClock(Instant.parse("2026-09-06T00:00:00Z"))
    private val fixture = GrpcFixture(
        mapOf(LEAVER to TaskMachineFixtures.document(), STAYER to TaskMachineFixtures.document()),
        clock,
    )

    @AfterTest fun close() = fixture.close()

    private fun start(robotId: String, taskId: String) {
        fixture.tasks.startTask(
            StartTaskRequest.newBuilder()
                .setHeader(GrpcFixture.requestHeader(robotId))
                .setTaskId(taskId).setRevision(1).setSkillType("navigate_to")
                .addParameters(ParameterValue.newBuilder().setKey("location").setStringValue("dock-3").build())
                .build(),
        )
    }

    private fun watch(robotId: String, taskId: String, observer: StreamObserver<WatchTaskResponse>) {
        fixture.tasksAsync.watchTask(
            WatchTaskRequest.newBuilder()
                .setHeader(GrpcFixture.requestHeader(robotId))
                .setHandle(TaskHandle.newBuilder().setTaskId(taskId).build())
                .setFromUpdateIndex(0)
                .build(),
            observer,
        )
    }

    /** 열자마자 떠나는 소비자. 취소 손잡이를 잡으려면 이 인터페이스여야 한다. */
    private class Leaving : ClientResponseObserver<WatchTaskRequest, WatchTaskResponse> {
        private lateinit var call: ClientCallStreamObserver<WatchTaskRequest>
        override fun beforeStart(requestStream: ClientCallStreamObserver<WatchTaskRequest>) {
            call = requestStream
        }
        override fun onNext(value: WatchTaskResponse) = Unit
        override fun onError(t: Throwable) = Unit
        override fun onCompleted() = Unit
        fun leave() = call.cancel("소비자가 떠났다", null)
    }

    private class Staying : StreamObserver<WatchTaskResponse> {
        val received = mutableListOf<WatchTaskResponse>()
        override fun onNext(value: WatchTaskResponse) { received += value }
        override fun onError(t: Throwable) = throw t
        override fun onCompleted() = Unit
    }

    @Test
    fun `떠난 소비자가 다른 기체의 시간을 멈추지 않는다`() {
        // directExecutor() 라 취소도 밀어내기도 호출 스레드에서 동기로 일어난다.
        start(LEAVER, "t-leaver")
        start(STAYER, "t-stayer")

        val leaving = Leaving()
        watch(LEAVER, "t-leaver", leaving)
        leaving.leave()

        val staying = Staying()
        watch(STAYER, "t-stayer", staying)
        val backlog = staying.received.size
        assertTrue(backlog >= 1, "밀린 것을 안 줬다 — 이 시험의 전제가 비었다")

        // ★**여기가 앞 판이 던지던 자리다.** 떠난 쪽에 밀다 CANCELLED 가 나면 남은 기체는
        //   전진 자체를 못 받고, 그 정지는 로그도 예외도 없이 «아무 일도 안 일어난다» 로 보인다.
        fixture.server.advance(Duration.ofSeconds(20))

        assertTrue(
            staying.received.size > backlog,
            "떠난 소비자 때문에 남은 기체의 갱신이 끊겼다: ${staying.received.size}",
        )
    }

    private companion object {
        const val LEAVER = "r-leaver"
        const val STAYER = "r-stayer"
    }
}
