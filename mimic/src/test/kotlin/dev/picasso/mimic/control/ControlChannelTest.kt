package dev.picasso.mimic.control

import dev.picasso.contracts.v1.StartTaskRequest
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskServiceGrpc
import dev.picasso.contracts.v1.WatchTaskRequest
import dev.picasso.contracts.v1.WatchTaskResponse
import dev.picasso.mimic.RobotInstance
import dev.picasso.mimic.control.v1.ControlServiceGrpc
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import dev.picasso.mimic.control.v1.ForceFaultRequest
import dev.picasso.mimic.control.v1.SetSeedRequest
import dev.picasso.mimic.control.v1.SetSingleStepRequest
import dev.picasso.mimic.control.v1.StepRequest
import dev.picasso.mimic.engine.TaskMachineFixtures
import dev.picasso.mimic.engine.TaskRuntime
import dev.picasso.mimic.engine.TaskState
import dev.picasso.mimic.engine.VirtualClock
import dev.picasso.mimic.transport.MimicServer
import dev.picasso.uplink.RecordingPublisher
import dev.picasso.mimic.transport.GrpcFixture
import dev.picasso.mimic.transport.RobotRegistry
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * §10.5의 제어 채널 — `SetSeed`·`ForceFault`·`SetSingleStep`·`Step`.
 *
 * **오라클은 `DumpInternalState`다.** `GetSnapshot`을 보면 계약 표면의 투영을
 * 투영과 비교하는 순환이 되고, 투영이 틀려도 양쪽이 똑같이 틀린다.
 */
class ControlChannelTest {

    private val clock = VirtualClock(Instant.parse("2026-09-06T00:00:00Z"))
    private val publisher = RecordingPublisher()

    private val registry = RobotRegistry(
        listOf(RobotInstance("r1", TaskMachineFixtures.document(), clock, publisher = publisher)),
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

    /**
     * 계약 표면. **단일 걸음은 여기서만 보인다** — 제어 채널의 RPC는 애초에
     * `tick()`을 돌리지 않으므로 그것만 두드려서는 아무것도 확인 못 한다.
     */
    private val contract: ManagedChannel =
        InProcessChannelBuilder.forName("$name-mimic").directExecutor().build()

    private val taskStub = TaskServiceGrpc.newBlockingStub(contract)

    private val taskAsync = TaskServiceGrpc.newStub(contract)

    /**
     * 열려 있는 `WatchTask` 스트림. **밀어내기를 보는 축은 이것뿐이다.**
     *
     * 실측으로 발행자(`RecordingPublisher`)를 세는 시험은 밀어내기를 통째로
     * 지워도 초록이었다 — 결함과 전이는 `EngineListener` → `EventStream`을
     * 타고 **밀어내기와 무관하게** MQTT 축으로 나가기 때문이다. `push`가
     * 먹이는 것은 열린 스트림이고, 그것을 안 보면 §4.7이 두 축이라고 한 것을
     * 하나로 착각한 시험이 된다.
     */
    private fun watch(taskId: String = "t1"): MutableList<WatchTaskResponse> {
        val received = mutableListOf<WatchTaskResponse>()
        taskAsync.watchTask(
            WatchTaskRequest.newBuilder()
                .setHeader(GrpcFixture.requestHeader("r1"))
                .setHandle(TaskHandle.newBuilder().setRobotId("r1").setTaskId(taskId))
                .setFromUpdateIndex(0)
                .build(),
            object : StreamObserver<WatchTaskResponse> {
                override fun onNext(value: WatchTaskResponse) { received += value }
                override fun onError(t: Throwable) = throw t
                override fun onCompleted() = Unit
            },
        )
        return received
    }

    @AfterTest
    fun close() {
        contract.shutdownNow()
        channel.shutdownNow()
        control.shutdown()
        mimic.shutdown()
    }

    /** 계약 표면의 RPC 하나. 진입이 정착을 부르는 그 경로다. */
    private fun startOverContract(taskId: String) {
        taskStub.startTask(
            StartTaskRequest.newBuilder()
                .setHeader(GrpcFixture.requestHeader("r1"))
                .setTaskId(taskId).setRevision(1).setSkillType("navigate_to")
                .addParameters(TaskMachineFixtures.param("location", "dock-3"))
                .build(),
        )
    }

    private val tasks get() = registry.byId("r1")!!.instance.tasks

    private fun dump() = stub.dumpInternalState(
        DumpInternalStateRequest.newBuilder().setRobotId("r1").build(),
    )

    private fun startNavigate(taskId: String = "t1"): TaskRuntime {
        tasks.start(taskId, 1, "navigate_to", listOf(TaskMachineFixtures.param("location", "dock-3")))
        return tasks.find(taskId)!!
    }

    private fun force(errorType: String, taskId: String = "t1") = stub.forceFault(
        ForceFaultRequest.newBuilder()
            .setRobotId("r1").setErrorType(errorType).setTaskId(taskId).build(),
    )

    private fun failsWith(code: Status.Code, block: () -> Unit): StatusRuntimeException {
        val error = assertFailsWith<StatusRuntimeException>(block = block)
        assertEquals(code, error.status.code, error.status.description)
        return error
    }

    // ── ForceFault: 무엇을 받는가

    @Test
    fun `선언 안 된 error_type을 거절한다`() {
        // §4.5 전파 규칙 1의 입력인 resolution 이 프로파일에서만 오므로,
        // 허용하면 태스크 종착 판정이 **미정의**가 된다.
        startNavigate()
        tasks.tick()
        val error = failsWith(Status.Code.FAILED_PRECONDITION) { force("PARAMETER_OUT_OF_RANGE") }
        assertTrue(
            error.status.description!!.contains("PARAMETER_OUT_OF_RANGE"),
            "무엇이 거절됐는지 안 알려준다: ${error.status.description}",
        )
        assertEquals(emptyList(), dump().faultsList, "거절해 놓고 결함을 세웠다")
    }

    @Test
    fun `어댑터 전용 둘은 ForceFault로 못 넣는다`() {
        // §10.5 — 이 둘은 프로파일이 선언하는 실패 모드가 아니라 어댑터가
        // 런타임에 발행하는 것이다. **스키마에서 읽는다** — 리터럴이면
        // 계약이 자라도 여기가 조용하다.
        val declarable = Regex("\"errorType\"[\\s\\S]{0,900}?\"enum\"\\s*:\\s*\\[([^\\]]+)\\]")
            .find(schemaText())
            ?.groupValues?.get(1)
            ?.let { body -> Regex("\"([A-Z_]+)\"").findAll(body).map { it.groupValues[1] }.toList() }
        assertEquals(6, declarable?.size, "스키마에서 코어 여섯을 못 읽었다: $declarable")

        val adapterOnly = listOf("TERMINAL_STATE_VIOLATED", "CONTROL_AUTHORITY_LOST")
        adapterOnly.forEach {
            assertTrue(it !in declarable!!, "$it 가 프로파일이 선언할 수 있는 값이 됐다")
        }

        startNavigate()
        tasks.tick()
        adapterOnly.forEach { errorType ->
            failsWith(Status.Code.FAILED_PRECONDITION) { force(errorType) }
        }
    }

    @Test
    fun `ForceFault를 받는 태스크 상태가 셋뿐이다`() {
        // **열 행을 전부 돈다.** 셋만 보면 종착에서 결함을 받아 래치가 깨지는
        // 구현이 초록이다(§4.4).
        val accepts = setOf(TaskState.RUNNING, TaskState.PAUSED, TaskState.CANCELLING)
        assertEquals(10, TaskState.entries.size, "상태가 열이 아니다")
        assertTrue(accepts.size < TaskState.entries.size, "표가 전부를 받으면 아무것도 안 본다")

        TaskState.entries.forEach { state ->
            val local = fresh()
            try {
                val tasks = local.registry.byId("r1")!!.instance.tasks
                tasks.start("t1", 1, "navigate_to", listOf(TaskMachineFixtures.param("location", "d")))
                val task = tasks.find("t1")!!
                if (state != TaskState.ACCEPTED) {
                    tasks.tick()
                    TaskMachineFixtures.driveTo(task, state)
                }
                check(task.machine.state == state) { "$state 로 몰지 못했다: ${task.machine.state}" }

                val request = ForceFaultRequest.newBuilder()
                    .setRobotId("r1").setErrorType("LOCALIZATION_LOST").setTaskId("t1").build()
                if (state in accepts) {
                    assertTrue(local.stub.forceFault(request).raised, "$state 에서 안 섰다")
                } else {
                    val error = assertFailsWith<StatusRuntimeException> {
                        local.stub.forceFault(request)
                    }
                    assertEquals(Status.Code.FAILED_PRECONDITION, error.status.code, "$state")
                    assertEquals(state, task.machine.state, "$state 에서 거절하고도 상태를 바꿨다")
                }
            } finally {
                local.close()
            }
        }
    }

    @Test
    fun `스킬 수준 모드는 task_id를 요구한다`() {
        // 등급은 **모드가** 정한다. task_id 없이 스킬 수준을 세우면 결함의
        // skill_id 가 어느 태스크의 것인지 말할 수 없다.
        failsWith(Status.Code.FAILED_PRECONDITION) { force("SKILL_EXECUTION_FAILED", taskId = "") }
    }

    @Test
    fun `모드가 지목한 스킬과 다른 태스크면 거절한다`() {
        // pick_place 모드를 navigate_to 태스크에 걸면 결함의 skill_id 와
        // 태스크의 스킬이 어긋난 것이 나간다.
        startNavigate()
        tasks.tick()
        failsWith(Status.Code.FAILED_PRECONDITION) { force("SKILL_EXECUTION_FAILED") }
    }

    @Test
    fun `모르는 태스크는 NOT_FOUND다`() {
        failsWith(Status.Code.NOT_FOUND) { force("LOCALIZATION_LOST", taskId = "nope") }
    }

    // ── ForceFault: 무엇을 하는가

    @Test
    fun `resolution 셋이 각각 다른 종착을 만든다`() {
        // §4.5 전파 규칙 1. 픽스처가 셋을 다 선언한다 — SELF_RETRIABLE은
        // pick_place 모드, 나머지 둘은 로봇 수준 모드다.
        val table = mapOf(
            "LOCALIZATION_LOST" to TaskState.NEEDS_INTERVENTION,
            "X_FIXTURE_SIMULATED_HARDWARE_FAULT" to TaskState.FAILED,
        )
        assertEquals(2, table.values.distinct().size, "종착이 안 갈리는 표다")

        table.forEach { (errorType, expected) ->
            val local = fresh()
            try {
                val tasks = local.registry.byId("r1")!!.instance.tasks
                tasks.start("t1", 1, "navigate_to", listOf(TaskMachineFixtures.param("location", "d")))
                tasks.tick()

                val response = local.stub.forceFault(
                    ForceFaultRequest.newBuilder()
                        .setRobotId("r1").setErrorType(errorType).setTaskId("t1").build(),
                )
                assertEquals(expected.name, response.taskState, errorType)
                assertEquals(expected, tasks.find("t1")!!.machine.state, errorType)
            } finally {
                local.close()
            }
        }
    }

    @Test
    fun `CANCELLING 중의 결함은 복구 실패다`() {
        // §4.5 전파 규칙 3 — resolution 을 보지 않는다. 취소는 이미 결정된
        // 것이고 남은 질문은 "되돌리는 데 성공했는가"뿐이다.
        val task = startNavigate()
        tasks.tick()
        TaskMachineFixtures.driveTo(task, TaskState.CANCELLING)

        // TERMINAL 인 모드를 넣어도 FAILED가 아니라 복구 실패로 간다.
        val response = force("X_FIXTURE_SIMULATED_HARDWARE_FAULT")
        assertEquals(TaskState.CANCELLED_RECOVERY_FAILED.name, response.taskState)
    }

    @Test
    fun `ForceFault가 정착시키지 않는다`() {
        // **여기서 tick이 돌면 CANCELLING 창이 같은 호출 안에서 닫힌다** —
        // 완료 기준 8b가 보려는 복구 실패를 만들 방법이 없어진다.
        // 접수만 된 두 번째 태스크가 ACCEPTED에 남는지로 본다.
        val task = startNavigate("t1")
        tasks.tick()
        TaskMachineFixtures.driveTo(task, TaskState.CANCELLING)
        startNavigate("t2")

        force("LOCALIZATION_LOST", taskId = "t1")

        val states = dump().tasksList.associate { it.taskId to it.taskState }
        assertEquals("CANCELLED_RECOVERY_FAILED", states["t1"])
        assertEquals("ACCEPTED", states["t2"], "ForceFault가 tick을 돌렸다")
    }

    @Test
    fun `ForceFault가 전이를 열린 스트림으로 민다`() {
        // 정착시키지 않는 것과 **아무것도 안 미는 것**은 다르다. 안 밀면
        // 열린 `WatchTask`가 그 전이를 통째로 놓치고 소비자는 그 자리를
        // 결손으로 읽는다.
        //
        // **발행자를 세면 안 된다** — 결함과 전이는 `EventStream`을 타고
        // 밀어내기와 무관하게 MQTT 축으로 나간다(실측: 밀어내기를 지워도
        // 발행자 수를 보는 시험이 초록이었다).
        startNavigate()
        tasks.tick()
        val seen = watch()
        val before = seen.size
        assertTrue(before >= 1, "스트림이 밀린 것을 안 줬다")

        force("LOCALIZATION_LOST")

        val added = seen.drop(before)
        assertTrue(added.isNotEmpty(), "전이가 열린 스트림에 안 갔다")
        assertEquals(
            dev.picasso.contracts.v1.TaskState.TASK_STATE_NEEDS_INTERVENTION,
            added.last().state,
            "종착이 안 갔다: ${added.map { it.state }}",
        )
    }

    @Test
    fun `task_id를 비우면 로봇 수준이다`() {
        // §4.6 — references 가 등급을 말한다. 진행 중 태스크가 있어도
        // 그것에 붙지 않는다.
        startNavigate()
        tasks.tick()

        val response = force("LOCALIZATION_LOST", taskId = "")
        assertTrue(response.raised)
        assertEquals("", response.taskState, "로봇 수준인데 태스크를 보냈다")

        val fault = dump().faultsList.single()
        assertEquals("", fault.skillId)
        assertEquals("", fault.taskId)
        assertEquals("RUNNING", dump().tasksList.single().taskState, "태스크를 건드렸다")
    }

    @Test
    fun `같은 결함을 두 번 세우면 두 번째는 새것이 아니다`() {
        // §4.6 — 내면 소비자의 목록이 부풀고 해소 하나로 안 지워지는 유령이
        // 남는다.
        startNavigate()
        tasks.tick()
        assertTrue(force("LOCALIZATION_LOST", taskId = "").raised)
        assertTrue(!force("LOCALIZATION_LOST", taskId = "").raised, "두 번째가 새 결함으로 잡혔다")
        assertEquals(1, dump().faultsList.size)
    }

    // ── SetSeed

    @Test
    fun `SetSeed가 추첨 스트림을 다시 심는다`() {
        // §12.1 — 시드가 결정성을 만든다는 주장을 흔들어 본다. 소요시간
        // 지터가 시드에서 나오므로 진행률로 되짚을 수 있다(§10.4 ②).
        fun durationAfter(seed: Long?): Double {
            val local = fresh()
            try {
                if (seed != null) {
                    local.stub.setSeed(SetSeedRequest.newBuilder().setRobotId("r1").setSeed(seed).build())
                }
                val tasks = local.registry.byId("r1")!!.instance.tasks
                tasks.start(
                    "t1", 1, "pick_place",
                    listOf(
                        TaskMachineFixtures.param("object_id", "b"),
                        TaskMachineFixtures.param("destination", "d"),
                    ),
                )
                tasks.tick()
                local.clock.advance(Duration.ofSeconds(10))
                return 10.0 / tasks.find("t1")!!.machine.progress()
            } finally {
                local.close()
            }
        }

        val base = durationAfter(null)
        assertEquals(base, durationAfter(0), "기본 시드가 0이 아니다 — 아래 비교가 뜻을 잃는다")
        assertTrue(base != durationAfter(99), "SetSeed가 추첨에 안 닿는다")
        assertEquals(durationAfter(99), durationAfter(99), "다시 심어도 결정적이지 않다")
    }

    @Test
    fun `SetSeed가 심은 값을 돌려준다`() {
        assertEquals(
            99L,
            stub.setSeed(SetSeedRequest.newBuilder().setRobotId("r1").setSeed(99).build()).seed,
        )
    }

    // ── 단일 걸음

    private fun singleStep(enabled: Boolean) = stub.setSingleStep(
        SetSingleStepRequest.newBuilder().setRobotId("r1").setEnabled(enabled).build(),
    )

    private fun step() = stub.step(StepRequest.newBuilder().setRobotId("r1").build())

    @Test
    fun `단일 걸음을 켜면 계약 표면의 RPC가 태스크를 안 민다`() {
        assertTrue(singleStep(true).enabled)
        startNavigate("t1")

        // **계약 표면을 두드린다.** 제어 채널만으로는 공허하다 — 그쪽은
        // 애초에 tick을 안 돌린다.
        startOverContract("t2")
        assertEquals(
            "ACCEPTED", dump().tasksList.first { it.taskId == "t1" }.taskState,
            "RPC 진입이 걸음을 훔쳤다",
        )

        mimic.settle()
        assertEquals(
            "ACCEPTED", dump().tasksList.first { it.taskId == "t1" }.taskState,
            "settle이 걸음을 훔쳤다",
        )
    }

    @Test
    fun `단일 걸음이 꺼져 있으면 계약 표면의 RPC가 민다`() {
        // 위 시험의 전제다. 애초에 아무 RPC도 안 밀면 "안 민다"가 자명하다.
        startNavigate("t1")
        startOverContract("t2")
        assertEquals("RUNNING", dump().tasksList.first { it.taskId == "t1" }.taskState)
    }

    @Test
    fun `덤프는 상태를 움직이지 않는다`() {
        // **오라클이 행위자가 되면 안 된다.** 관측이 관측 대상을 바꾸면
        // §12.2의 A-2 비교가 자기 자신을 좇는다.
        startNavigate()
        repeat(5) { assertEquals("ACCEPTED", dump().tasksList.single().taskState) }
    }

    @Test
    fun `Step이 한 칸씩 민다`() {
        singleStep(true)
        startNavigate()

        assertEquals(1, step().moved, "ACCEPTED → RUNNING 이 안 일어났다")
        assertEquals("RUNNING", dump().tasksList.single().taskState)

        // 시간이 안 흘렀으므로 다음 걸음은 아무것도 못 움직인다.
        assertEquals(0, step().moved, "시간이 안 흘렀는데 움직였다")

        clock.advance(Duration.ofSeconds(30))
        assertEquals(1, step().moved)
        assertEquals("SUCCEEDED", dump().tasksList.single().taskState)
    }

    @Test
    fun `단일 걸음을 끄면 다시 저절로 돈다`() {
        singleStep(true)
        startNavigate()
        assertEquals("ACCEPTED", dump().tasksList.single().taskState)

        assertTrue(!singleStep(false).enabled)
        startOverContract("t2")
        assertEquals(
            "RUNNING", dump().tasksList.first { it.taskId == "t1" }.taskState,
            "꺼도 안 돈다",
        )
    }

    @Test
    fun `단일 걸음 중에도 밀어내기는 멈추지 않는다`() {
        // 멈추면 열린 스트림이 이미 생긴 전이를 못 받고, 결함이 실패가 아니라
        // **정지**로 나타난다.
        //
        // **`Step`으로는 이것을 못 본다**(실측). `Step`은 스스로 밀어내므로
        // 정착 경로가 밀어내기까지 막아도 초록이었다. 봐야 하는 것은
        // **계약 RPC 진입의 정착**이 단일 걸음 중에도 미는가다.
        singleStep(true)
        startNavigate()
        val seen = watch()
        val before = seen.size

        // 엔진에서 직접 전이를 만든다 — 밀어내기 없이.
        tasks.tick()
        assertEquals(before, seen.size, "전제가 무너졌다 — 이미 밀렸다")
        assertEquals("RUNNING", dump().tasksList.single { it.taskId == "t1" }.taskState)

        // 계약 RPC 진입이 정착을 부른다. 틱은 안 돌아도 **밀기는 해야 한다**.
        startOverContract("t2")

        val added = seen.drop(before)
        assertTrue(added.isNotEmpty(), "단일 걸음이 밀어내기까지 막았다")
        assertEquals(
            dev.picasso.contracts.v1.TaskState.TASK_STATE_RUNNING,
            added.last().state,
        )
    }

    // ── 라우팅

    @Test
    fun `모르는 기체는 전부 NOT_FOUND다`() {
        // 하나만 보면 나머지가 조용히 아무것도 안 하고 통과한다.
        val calls = listOf<Pair<String, () -> Any>>(
            "SetSeed" to {
                stub.setSeed(SetSeedRequest.newBuilder().setRobotId("r9").setSeed(1).build())
            },
            "ForceFault" to {
                stub.forceFault(
                    ForceFaultRequest.newBuilder()
                        .setRobotId("r9").setErrorType("LOCALIZATION_LOST").build(),
                )
            },
            "SetSingleStep" to {
                stub.setSingleStep(
                    SetSingleStepRequest.newBuilder().setRobotId("r9").setEnabled(true).build(),
                )
            },
            "Step" to { stub.step(StepRequest.newBuilder().setRobotId("r9").build()) },
        )
        assertEquals(4, calls.size, "이 청크가 더한 RPC 넷을 다 안 본다")

        calls.forEach { (label, call) ->
            val error = assertFailsWith<StatusRuntimeException>(message = label) { call() }
            assertEquals(Status.Code.NOT_FOUND, error.status.code, label)
        }
    }

    // ── 시험용 두 번째 기체

    /** 상태마다 새 기체가 필요한 시험이 쓴다. */
    private class Fixture : AutoCloseable {
        val clock = VirtualClock(Instant.parse("2026-09-06T00:00:00Z"))
        val registry = RobotRegistry(
            listOf(RobotInstance("r1", TaskMachineFixtures.document(), clock)),
        )
        private val name = InProcessServerBuilder.generateName()
        private val mimic = MimicServer(
            registry, InProcessServerBuilder.forName("$name-mimic").directExecutor(),
        ).start()
        private val control = ControlServer(
            registry, mimic, InProcessServerBuilder.forName(name).directExecutor(),
        ).start()
        private val channel: ManagedChannel =
            InProcessChannelBuilder.forName(name).directExecutor().build()
        val stub: ControlServiceGrpc.ControlServiceBlockingStub =
            ControlServiceGrpc.newBlockingStub(channel)

        override fun close() {
            channel.shutdownNow(); control.shutdown(); mimic.shutdown()
        }
    }

    private fun fresh() = Fixture()

    private fun schemaText(): String = Files.readString(
        Path.of("..", "profile", "schema", "capability-profile.schema.json").normalize(),
    )
}
