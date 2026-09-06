package dev.picasso.mimic.control

import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.mimic.RobotInstance
import dev.picasso.mimic.control.v1.AdvanceClockRequest
import dev.picasso.mimic.control.v1.ClockMode
import dev.picasso.mimic.control.v1.ControlServiceGrpc
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import dev.picasso.mimic.control.v1.SetClockModeRequest
import dev.picasso.mimic.engine.RealClock
import dev.picasso.mimic.engine.TaskMachineFixtures
import dev.picasso.mimic.engine.VirtualClock
import dev.picasso.mimic.transport.MimicServer
import dev.picasso.mimic.transport.RecordingPublisher
import dev.picasso.mimic.transport.RobotRegistry
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ControlServerTest {

    private val clock = VirtualClock(Instant.parse("2026-09-06T00:00:00Z"))
    private val publisher = RecordingPublisher()

    private val registry = RobotRegistry(
        listOf(
            RobotInstance("r1", TaskMachineFixtures.document(), clock, publisher = publisher),
        ),
    )

    private val name: String = InProcessServerBuilder.generateName()

    private val mimic = MimicServer(
        registry, InProcessServerBuilder.forName("$name-mimic").directExecutor(),
    ).start()

    private val control = ControlServer(
        registry, mimic, InProcessServerBuilder.forName(name).directExecutor(),
    ).start()

    private val channel: ManagedChannel =
        InProcessChannelBuilder.forName(name).directExecutor().build()

    private val stub = ControlServiceGrpc.newBlockingStub(channel)

    @AfterTest
    fun close() {
        channel.shutdownNow()
        control.shutdown()
        mimic.shutdown()
    }

    private fun location(value: String = "dock-3") =
        ParameterValue.newBuilder().setKey("location").setStringValue(value).build()

    private fun dump() = stub.dumpInternalState(
        DumpInternalStateRequest.newBuilder().setRobotId("r1").build(),
    )

    // ── 시계

    @Test
    fun `AdvanceClock이 MimicServer_advance로 내려온다`() {
        // 제어 채널이 자기 전진 경로를 따로 만들면 시험과 운영이 다른 코드로
        // 시간을 흘리게 되고, 열린 스트림에 미는 것을 빠뜨리면 결함이
        // **정지**로 나타난다. 시계만 움직이고 tick이 안 돌면 여기서 걸린다.
        registry.byId("r1")!!.instance.tasks.start("t1", 1, "navigate_to", listOf(location()))

        stub.advanceClock(AdvanceClockRequest.newBuilder().setDurationMillis(60_000).build())
        stub.advanceClock(AdvanceClockRequest.newBuilder().setDurationMillis(60_000).build())

        assertEquals("SUCCEEDED", dump().tasksList.single().taskState, "전진만 하고 정착을 안 했다")
    }

    @Test
    fun `전진 뒤의 시각을 돌려준다`() {
        val response = stub.advanceClock(
            AdvanceClockRequest.newBuilder().setDurationMillis(90_000).build(),
        )
        assertEquals("2026-09-06T00:01:30Z", response.now)
    }

    @Test
    fun `되감기를 거절한다`() {
        val error = assertFailsWith<StatusRuntimeException> {
            stub.advanceClock(AdvanceClockRequest.newBuilder().setDurationMillis(-1).build())
        }
        assertEquals(Status.Code.INVALID_ARGUMENT, error.status.code)
    }

    @Test
    fun `실시간 시계는 전진시킬 수 없다`() {
        // 조용히 무시하면 호출자가 시간이 흐른 줄 알고 통과한다.
        val real = RobotRegistry(
            listOf(RobotInstance("r1", TaskMachineFixtures.document(), RealClock())),
        )
        val serverName = InProcessServerBuilder.generateName()
        val realMimic = MimicServer(
            real, InProcessServerBuilder.forName("$serverName-m").directExecutor(),
        ).start()
        val realControl = ControlServer(
            real, realMimic, InProcessServerBuilder.forName(serverName).directExecutor(),
        ).start()
        val realChannel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
        try {
            val error = assertFailsWith<StatusRuntimeException> {
                ControlServiceGrpc.newBlockingStub(realChannel)
                    .advanceClock(AdvanceClockRequest.newBuilder().setDurationMillis(1).build())
            }
            assertEquals(Status.Code.FAILED_PRECONDITION, error.status.code)
        } finally {
            realChannel.shutdownNow(); realControl.shutdown(); realMimic.shutdown()
        }
    }

    @Test
    fun `기동한 모드와 다른 모드를 요구하면 알린다`() {
        // 조용히 받아들이면 호출자가 가상 시계를 쓰는 줄 알고 AdvanceClock을
        // 부르다 엉뚱한 자리에서 예외를 만난다.
        assertEquals(
            ClockMode.CLOCK_MODE_VIRTUAL,
            stub.setClockMode(
                SetClockModeRequest.newBuilder().setMode(ClockMode.CLOCK_MODE_VIRTUAL).build(),
            ).mode,
        )
        val error = assertFailsWith<StatusRuntimeException> {
            stub.setClockMode(
                SetClockModeRequest.newBuilder().setMode(ClockMode.CLOCK_MODE_REAL).build(),
            )
        }
        assertEquals(Status.Code.FAILED_PRECONDITION, error.status.code)
    }

    // ── 오라클 (§12.2의 A-2)

    @Test
    fun `덤프가 엔진의 어휘로 말한다`() {
        // **계약 표면의 투영이면 안 된다.** GetSnapshot이 쓰는 코드를 거치면
        // 투영을 투영과 비교하는 순환이 되어, 투영이 틀려도 양쪽이 똑같이
        // 틀린다. 계약 enum의 이름(TASK_STATE_RUNNING)이 아니라 엔진의
        // 이름(RUNNING)이 나와야 한다.
        val instance = registry.byId("r1")!!.instance
        instance.tasks.start("t1", 1, "navigate_to", listOf(location()))
        instance.tasks.tick()

        val task = dump().tasksList.single()
        assertEquals("RUNNING", task.taskState)
        assertEquals("RUNNING", task.skillState)
        assertTrue(
            !task.taskState.startsWith("TASK_STATE_"),
            "계약 enum의 이름이 나왔다 — 덤프가 투영이다",
        )
    }

    @Test
    fun `덤프가 계약 표면에 없는 것을 담는다`() {
        // 진행률과 로그 크기는 GetSnapshot에 없다. 없으면 이 오라클이
        // 스냅샷과 같은 것만 보게 되고 순환을 못 벗어난다.
        val instance = registry.byId("r1")!!.instance
        instance.tasks.start("t1", 1, "navigate_to", listOf(location()))
        instance.tasks.tick()
        stub.advanceClock(AdvanceClockRequest.newBuilder().setDurationMillis(10_000).build())

        val task = dump().tasksList.single()
        assertTrue(task.progress > 0.0 && task.progress < 1.0, "진행률: ${task.progress}")
        assertTrue(task.logSize >= 2, "로그 크기: ${task.logSize}")
    }

    @Test
    fun `덤프의 next_sequence가 발행 축과 맞는다`() {
        val instance = registry.byId("r1")!!.instance
        instance.tasks.start("t1", 1, "navigate_to", listOf(location()))
        instance.tasks.tick()

        assertEquals(publisher.publications.size.toLong(), dump().nextSequence)
        assertEquals(instance.sessionId, dump().sessionId)
    }

    @Test
    fun `모르는 기체는 NOT_FOUND다`() {
        val error = assertFailsWith<StatusRuntimeException> {
            stub.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId("r9").build())
        }
        assertEquals(Status.Code.NOT_FOUND, error.status.code)
    }

    // ── 바인딩 (§6.3)

    @Test
    fun `제어 채널이 루프백에만 바인딩한다`() {
        // **소켓을 걸어 보지 않는다.** 거절과 필터링을 구별할 수 없고, 필터링은
        // 실패가 아니라 **정지**로 나타난다. 바인딩된 주소를 본다.
        val loopback = ControlServer(registry, mimic, ControlServer.loopback(0)).start()
        try {
            val bound = loopback.boundAddresses
            assertTrue(bound.isNotEmpty(), "바인딩된 주소가 없다 — 시험이 아무것도 확인 안 한다")
            bound.forEach {
                assertTrue(
                    it.address.isLoopbackAddress,
                    "루프백이 아닌 주소에 열렸다: ${it.address} — 프로덕션 소비자가 목에 손댈 수 있다",
                )
            }
            assertTrue(loopback.port > 0)
        } finally {
            loopback.shutdown()
        }
    }
}
