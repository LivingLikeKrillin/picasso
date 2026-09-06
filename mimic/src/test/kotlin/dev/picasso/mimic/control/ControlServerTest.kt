package dev.picasso.mimic.control

import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.Lifetime
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.Reference
import dev.picasso.mimic.RobotInstance
import dev.picasso.mimic.control.v1.AdvanceClockRequest
import dev.picasso.mimic.control.v1.ClockMode
import dev.picasso.mimic.control.v1.ControlServiceGrpc
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import dev.picasso.mimic.control.v1.SetClockModeRequest
import dev.picasso.mimic.engine.FailureDraw
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

    // ── 결함 오라클 (완료 기준 8b·8c·8d가 이것 위에 선다)

    private fun fault(
        errorType: String,
        skillId: String = "",
        taskId: String = "",
        hint: String = "",
        kind: Lifetime.Kind = Lifetime.Kind.KIND_UNTIL_CLEARED,
        until: String = "",
    ): Fault = Fault.newBuilder()
        .setErrorType(errorType)
        .setCanContinueCurrentTask(false)
        .setCanAcceptNewTask(true)
        .setErrorHint(hint)
        .setActiveUntil(Lifetime.newBuilder().setKind(kind).setUntil(until))
        .also { builder ->
            if (skillId.isNotEmpty()) {
                builder.addReferences(
                    Reference.newBuilder().setKey(Reference.Key.KEY_SKILL_ID).setValue(skillId),
                )
            }
            if (taskId.isNotEmpty()) {
                builder.addReferences(
                    Reference.newBuilder().setKey(Reference.Key.KEY_TASK_ID).setValue(taskId),
                )
            }
        }
        .build()

    @Test
    fun `결함이 없으면 목록이 빈다`() {
        // 아래 시험들의 전제다. 덤프가 언제나 무언가를 실으면 "실렸다"가
        // 아무것도 뜻하지 않는다.
        assertEquals(emptyList(), dump().faultsList)
    }

    @Test
    fun `덤프가 결함을 스칼라로 편다`() {
        // **`Fault`를 `Fault`와 비교하지 않는다.** 투영이 필드를 빠뜨리면
        // 양쪽이 똑같이 빠뜨려 초록이다. 하나씩 펴 두면 그 자리가 벌어진다.
        registry.byId("r1")!!.instance.faults.raise(
            fault(
                "PAYLOAD_LOST",
                skillId = "pick_place",
                taskId = "t1",
                hint = "떨어뜨린 대상을 회수하십시오.",
                kind = Lifetime.Kind.KIND_UNTIL_TIMESTAMP,
                until = "2026-09-06T00:05:00Z",
            ),
        )

        val flat = dump().faultsList.single()
        assertEquals("PAYLOAD_LOST", flat.errorType)
        assertEquals(false, flat.canContinueCurrentTask)
        assertEquals(true, flat.canAcceptNewTask)
        assertEquals("KIND_UNTIL_TIMESTAMP", flat.lifetimeKind)
        assertEquals("2026-09-06T00:05:00Z", flat.lifetimeUntil)
        assertEquals("pick_place", flat.skillId)
        assertEquals("t1", flat.taskId)
        assertEquals("떨어뜨린 대상을 회수하십시오.", flat.errorHint)
    }

    @Test
    fun `로봇 수준은 skill_id가 빈 것으로 갈린다`() {
        // §4.6 — 등급을 별도 컬렉션으로 나누지 않는다. `references`가 말한다.
        val faults = registry.byId("r1")!!.instance.faults
        faults.raise(fault("SKILL_EXECUTION_FAILED", skillId = "pick_place", taskId = "t1"))
        faults.raise(fault("LOCALIZATION_LOST"))

        val flat = dump().faultsList.associateBy { it.errorType }
        assertEquals(2, flat.size)
        assertEquals("pick_place", flat.getValue("SKILL_EXECUTION_FAILED").skillId)
        assertEquals("", flat.getValue("LOCALIZATION_LOST").skillId)
        assertEquals("", flat.getValue("LOCALIZATION_LOST").taskId)
    }

    @Test
    fun `덤프의 결함 순서가 발생 순서다`() {
        // 소비자가 이벤트로 본 순서와 오라클의 순서가 다르면 재구성한 목록과
        // 대조할 수 없다 — 완료 기준 2의 비교가 거기서 어긋난다.
        val faults = registry.byId("r1")!!.instance.faults
        listOf("LOCALIZATION_LOST", "PAYLOAD_LOST", "INTERNAL_ERROR").forEach {
            faults.raise(fault(it))
        }
        assertEquals(
            listOf("LOCALIZATION_LOST", "PAYLOAD_LOST", "INTERNAL_ERROR"),
            dump().faultsList.map { it.errorType },
        )
    }

    @Test
    fun `덤프가 계약의 Fault를 빠짐없이 편다`() {
        // **계약이 자라면 여기가 빨개져야 한다.** 안 그러면 새 필드가 조용히
        // 오라클 밖으로 떨어지고, 그 필드를 잃어버리는 투영은 영영 안 잡힌다.
        assertEquals(
            listOf(
                "error_type",
                "can_continue_current_task",
                "can_accept_new_task",
                "references",
                "error_hint",
                "active_until",
            ),
            Fault.getDescriptor().fields.map { it.name },
            "계약의 Fault가 바뀌었다 — InternalFault와 flatten()을 함께 고쳐라",
        )

        // `references`는 키가 다섯인데 둘만 편다. 엔진이 그 둘만 붙이기
        // 때문이며, 그 사실 자체를 못박는다.
        assertEquals(
            setOf(Reference.Key.KEY_SKILL_ID, Reference.Key.KEY_TASK_ID),
            FailureDraw
                .faultOf(TaskMachineFixtures.document().failureModes.first(), "pick_place", "t1")
                .referencesList.map { it.key }.toSet(),
            "엔진이 다른 키를 붙이기 시작했다 — flatten()이 그것을 잃는다",
        )
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
