package dev.picasso.adapter.host

import dev.picasso.adapter.core.Acceptance
import dev.picasso.adapter.core.Applied
import dev.picasso.adapter.core.FaultObservation
import dev.picasso.adapter.core.HoldObservation
import dev.picasso.adapter.core.Refusal
import dev.picasso.adapter.core.RobotAdapter
import dev.picasso.adapter.core.SiteNames
import dev.picasso.adapter.core.classifiedFault
import dev.picasso.contracts.v1.CancelTaskRequest
import dev.picasso.contracts.v1.EventServiceGrpc
import dev.picasso.contracts.v1.FailureClass
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.GetCapabilitiesRequest
import dev.picasso.contracts.v1.GetKnownSiteNamesRequest
import dev.picasso.contracts.v1.GetSnapshotRequest
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.PauseTaskRequest
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.ReplayEventsRequest
import dev.picasso.contracts.v1.SkillServiceGrpc
import dev.picasso.contracts.v1.StartTaskRequest
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskServiceGrpc
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.WatchTaskRequest
import dev.picasso.contracts.v1.WatchTaskResponse
import dev.picasso.contracts.wire.RequestHeaders
import dev.picasso.profile.ProfileDocument
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 계약 뒤에 선 어댑터 — 소비자가 보는 것은 미믹과 같은 모양이어야 한다. 가짜 어댑터로 계약 면을 하나씩 본다:
 * 접수와 로그, `WatchTask` 의 되짚기와 밀기, 조작 넷의 거절 어휘, 스냅샷과 재생, 축출.
 */
class AdapterHostTest {

    /** 각본대로 답하는 어댑터. 기종이 없다 — 이 시험이 보는 것은 호스트다. */
    private class ScriptedAdapter : RobotAdapter {
        var accepted = mutableListOf<Triple<String, Map<String, Any>, Instant>>()
        var refuseWith: Acceptance.Refused? = null
        var next: TaskState = TaskState.TASK_STATE_RUNNING
        var pauseAnswer: Applied = Applied.Refused(Refusal.NO_VENDOR_PRIMITIVE, "없다")
        var cancelAnswer: Applied = Applied.Ok
        var holding: HoldObservation = HoldObservation.Empty
        var observedFaults: FaultObservation = FaultObservation.Observed(emptyList())
        var failureToReport: Fault? = null
        var resultToReport: String? = null
        var siteNames: SiteNames = SiteNames.Known(listOf("dock-3", "bay-7"))
        private var current: TaskState = TaskState.TASK_STATE_UNSPECIFIED

        override val state: TaskState get() = current
        override fun accept(taskId: String, skillType: String, parameters: Map<String, Any>, startedAt: Instant): Acceptance {
            refuseWith?.let { return it }
            accepted += Triple(skillType, parameters, startedAt)
            current = TaskState.TASK_STATE_RUNNING
            return Acceptance.Accepted(taskId)
        }
        override fun poll(now: Instant): TaskState { current = next; return current }
        override fun pause(): Applied = pauseAnswer.also { if (it == Applied.Ok) current = TaskState.TASK_STATE_PAUSED }
        override fun cancel(): Applied = cancelAnswer.also { if (it == Applied.Ok) { current = TaskState.TASK_STATE_CANCELLING; next = TaskState.TASK_STATE_CANCELLED } }
        override fun hold(): HoldObservation = holding
        override fun faults(): FaultObservation = observedFaults
        override fun failure(): Fault? = failureToReport
        override fun result(): String? = resultToReport
        override fun knownSiteNames(): SiteNames = siteNames
    }

    private class World(profileJson: String = Files.readString(PROFILE)) : AutoCloseable {
        var now: Instant = Instant.parse("2026-09-10T00:00:00Z")
        val adapter = ScriptedAdapter()
        val robot = HostedRobot(ROBOT, ProfileDocument.parse("test", profileJson).getOrThrow(), adapter) { now }
        private val name = InProcessServerBuilder.generateName()
        val host = AdapterHost(robot, InProcessServerBuilder.forName(name).directExecutor()).start()
        val channel: ManagedChannel = InProcessChannelBuilder.forName(name).directExecutor().build()
        val tasks: TaskServiceGrpc.TaskServiceBlockingStub = TaskServiceGrpc.newBlockingStub(channel)
        val skills: SkillServiceGrpc.SkillServiceBlockingStub = SkillServiceGrpc.newBlockingStub(channel)
        val events: EventServiceGrpc.EventServiceBlockingStub = EventServiceGrpc.newBlockingStub(channel)

        fun header(schema: String) = RequestHeaders.build(schema, ROBOT, "test-client")

        fun start(taskId: String = "T-1", revision: Int = 1, skill: String = "pick_place", params: Map<String, String> = pick): StartTaskResponse =
            tasks.startTask(
                StartTaskRequest.newBuilder().setHeader(header("picasso.v1.StartTaskRequest"))
                    .setTaskId(taskId).setRevision(revision).setRobotId(ROBOT).setSkillType(skill)
                    .addAllParameters(params.map { (k, v) -> ParameterValue.newBuilder().setKey(k).setStringValue(v).build() })
                    .build(),
            )

        fun watch(handle: TaskHandle, from: Long = 0): List<WatchTaskResponse> =
            tasks.watchTask(WatchTaskRequest.newBuilder().setHeader(header("picasso.v1.WatchTaskRequest")).setHandle(handle).setFromUpdateIndex(from).build()).asSequence().toList()

        override fun close() { channel.shutdownNow(); host.shutdown() }

        companion object {
            val pick = mapOf("object_id" to "SEQ-IN-02.BIN-A", "destination" to "RACK-204.S01")
        }
    }

    @Test
    fun `접수하면 ACCEPTED 와 RUNNING 이 로그에 적히고 WatchTask 가 되짚어 준다`() {
        World().use { w ->
            val response = w.start()
            assertTrue(response.hasHandle(), response.rejection.toString())
            assertEquals(ROBOT, response.header.robotId)
            assertEquals(listOf("pick_place"), w.adapter.accepted.map { it.first })
            assertEquals("SEQ-IN-02.BIN-A", w.adapter.accepted.single().second["object_id"])

            w.adapter.next = TaskState.TASK_STATE_SUCCEEDED
            w.adapter.resultToReport = "evidence://ref-1"
            val updates = w.watch(response.handle)
            assertEquals(listOf(TaskState.TASK_STATE_ACCEPTED, TaskState.TASK_STATE_RUNNING, TaskState.TASK_STATE_SUCCEEDED), updates.map { it.state })
            assertEquals(listOf(0L, 1L, 2L), updates.map { it.header.updateIndex })
            assertEquals("evidence://ref-1", updates.last().partialResult, "어댑터의 결과 참조가 partial_result 로 올라간다")
            assertEquals(1.0, updates.last().progress)
            assertEquals(HoldKind.HOLD_KIND_EMPTY, updates.last().hold.kind)
        }
    }

    @Test
    fun `실패 종착에는 어댑터의 정준 분류가 fault 로 실린다`() {
        World().use { w ->
            val handle = w.start().handle
            w.adapter.failureToReport = classifiedFault("X_TEST_GRASP", FailureClass.FAILURE_CLASS_GRASP_FAILED, "vendor=code-7", "다시 잡으십시오")
            w.adapter.next = TaskState.TASK_STATE_FAILED
            val last = w.watch(handle).last()
            assertEquals(TaskState.TASK_STATE_FAILED, last.state)
            assertEquals(FailureClass.FAILURE_CLASS_GRASP_FAILED, last.fault.failureClass)
            assertEquals("vendor=code-7", last.fault.vendorDetail)
        }
    }

    @Test
    fun `같은 요청은 같은 핸들이고, 도는 태스크의 갱신은 되는 척하지 않는다`() {
        World().use { w ->
            val first = w.start().handle
            val again = w.start()
            assertEquals(first, again.handle)
            assertEquals(1, w.adapter.accepted.size, "재전송이 로봇에 두 번 갔다")
            assertEquals(2, w.robot.task("T-1")!!.updates.size, "멱등 재수신이 로그를 늘렸다")

            val bumped = w.start(revision = 2)
            assertEquals(RejectionCode.REJECTION_CODE_INVALID_TRANSITION, bumped.rejection.code)
            assertTrue(bumped.rejection.detail.contains("갱신하지 않는다"), bumped.rejection.detail)
            assertEquals(RejectionCode.REJECTION_CODE_OUTDATED_REVISION, w.start(revision = 0).rejection.code)
        }
    }

    @Test
    fun `프로파일이 선언하지 않은 스킬과 빠진 필수 파라미터는 계약의 어휘로 거절된다`() {
        World().use { w ->
            assertEquals(RejectionCode.REJECTION_CODE_SKILL_ABSENT, w.start(skill = "fly").rejection.code)
            assertEquals(RejectionCode.REJECTION_CODE_REQUIRED_OPTIONAL_MISSING, w.start(params = mapOf("object_id" to "x")).rejection.code)
            assertTrue(w.adapter.accepted.isEmpty(), "거절될 요청이 로봇에 갔다")
        }
    }

    @Test
    fun `어댑터의 거절이 계약의 코드로 옮겨진다 — 모르는 이름은 PARAMETER_INVALID, 표면 부재는 CAPABILITY_WITHDRAWN`() {
        World().use { w ->
            w.adapter.refuseWith = Acceptance.Refused(Refusal.SITE_NAME_UNKNOWN, "no such name")
            assertEquals(RejectionCode.REJECTION_CODE_PARAMETER_INVALID, w.start().rejection.code)
            w.adapter.refuseWith = Acceptance.Refused(Refusal.VENDOR_SURFACE_ABSENT, "no mission layer")
            assertEquals(RejectionCode.REJECTION_CODE_CAPABILITY_WITHDRAWN, w.start().rejection.code)
            w.adapter.refuseWith = Acceptance.Refused(Refusal.LINK_ERROR, "socket")
            val e = assertFailsWith<StatusRuntimeException> { w.start() }
            assertEquals(Status.Code.UNAVAILABLE, e.status.code)
        }
    }

    @Test
    fun `취소는 어댑터가 없다 하면 CANCEL_UNSUPPORTED 이고, 되면 CANCELLING 뒤 CANCELLED 다`() {
        World().use { w ->
            val handle = w.start().handle
            w.adapter.cancelAnswer = Applied.Refused(Refusal.NO_VENDOR_PRIMITIVE, "취소 없음")
            val refused = w.tasks.cancelTask(CancelTaskRequest.newBuilder().setHeader(w.header("picasso.v1.CancelTaskRequest")).setHandle(handle).build())
            assertEquals(RejectionCode.REJECTION_CODE_CANCEL_UNSUPPORTED, refused.rejection.code)

            w.adapter.cancelAnswer = Applied.Ok
            val ok = w.tasks.cancelTask(CancelTaskRequest.newBuilder().setHeader(w.header("picasso.v1.CancelTaskRequest")).setHandle(handle).build())
            assertEquals(TaskState.TASK_STATE_CANCELLING, ok.state)
            assertEquals(TaskState.TASK_STATE_CANCELLED, w.watch(handle).last().state)

            val paused = w.tasks.pauseTask(PauseTaskRequest.newBuilder().setHeader(w.header("picasso.v1.PauseTaskRequest")).setHandle(handle).build())
            assertEquals(RejectionCode.REJECTION_CODE_INVALID_TRANSITION, paused.rejection.code, "종착은 래치된다")
        }
    }

    @Test
    fun `스냅샷은 결함과 태스크와 ONLINE 을 싣고, 못 봤으면 UNAVAILABLE 이다`() {
        World().use { w ->
            val handle = w.start().handle
            val fault = classifiedFault("LOCALIZATION_LOST", FailureClass.FAILURE_CLASS_LOCALIZATION_LOST, "", "재측위", canAcceptNewTask = false)
            w.adapter.observedFaults = FaultObservation.Observed(listOf(fault))
            val snapshot = w.events.getSnapshot(GetSnapshotRequest.newBuilder().setHeader(w.header("picasso.v1.GetSnapshotRequest")).setRobotId(ROBOT).build())
            assertEquals(listOf("LOCALIZATION_LOST"), snapshot.faultsList.map { it.errorType })
            assertEquals(handle.taskId, snapshot.tasksList.single().taskId)
            assertEquals(dev.picasso.contracts.v1.ConnectionState.CONNECTION_STATE_ONLINE, snapshot.connectionState)

            val replay = w.events.replayEvents(ReplayEventsRequest.newBuilder().setHeader(w.header("picasso.v1.ReplayEventsRequest")).setRobotId(ROBOT).setFromSequence(0).build()).asSequence().toList()
            val kinds = replay.map { if (it.event.hasTaskTransition()) "T:${it.event.taskTransition.to.name}" else "F:${it.event.faultEvent.fault.errorType}" }
            assertEquals(listOf("T:TASK_STATE_ACCEPTED", "T:TASK_STATE_RUNNING", "F:LOCALIZATION_LOST"), kinds)
            assertEquals(replay.map { it.event.header.sequence }, replay.map { it.event.header.sequence }.sorted())

            w.adapter.observedFaults = FaultObservation.NotObservable("lowstate 없음")
            val e = assertFailsWith<StatusRuntimeException> {
                w.events.getSnapshot(GetSnapshotRequest.newBuilder().setHeader(w.header("picasso.v1.GetSnapshotRequest")).setRobotId(ROBOT).build())
            }
            assertEquals(Status.Code.UNAVAILABLE, e.status.code)
        }
    }

    @Test
    fun `재생 버퍼를 벗어나면 SEQUENCE_EVICTED 다`() {
        val small = Files.readString(PROFILE).replace("\"replay_buffer_size\": 256", "\"replay_buffer_size\": 2")
        World(small).use { w ->
            val handle = w.start().handle // ACCEPTED, RUNNING → 둘
            w.adapter.next = TaskState.TASK_STATE_SUCCEEDED
            w.watch(handle) // SUCCEEDED → 셋째. 버퍼 2 라 0 번이 버려진다
            val evicted = w.events.replayEvents(ReplayEventsRequest.newBuilder().setHeader(w.header("picasso.v1.ReplayEventsRequest")).setRobotId(ROBOT).setFromSequence(0).build()).asSequence().toList()
            assertEquals(RejectionCode.REJECTION_CODE_SEQUENCE_EVICTED, evicted.single().rejection.code)
            val fine = w.events.replayEvents(ReplayEventsRequest.newBuilder().setHeader(w.header("picasso.v1.ReplayEventsRequest")).setRobotId(ROBOT).setFromSequence(1).build()).asSequence().toList()
            assertEquals(2, fine.size)
        }
    }

    @Test
    fun `능력은 프로파일의 투영이고, 아는 이름 셋 답은 계약대로 갈린다`() {
        World().use { w ->
            val capability = w.skills.getCapabilities(GetCapabilitiesRequest.newBuilder().setHeader(w.header("picasso.v1.GetCapabilitiesRequest")).setRobotId(ROBOT).build()).capability
            assertEquals(w.robot.document.vendor, capability.vendor)
            assertTrue(capability.skillsList.any { it.skillType == "pick_place" })

            val req = GetKnownSiteNamesRequest.newBuilder().setHeader(w.header("picasso.v1.GetKnownSiteNamesRequest")).setRobotId(ROBOT).build()
            assertEquals(listOf("dock-3", "bay-7"), w.skills.getKnownSiteNames(req).namesList)
            w.adapter.siteNames = SiteNames.Unsupported
            assertTrue(w.skills.getKnownSiteNames(req).unsupported)
            w.adapter.siteNames = SiteNames.Unavailable("graph 못 받음")
            assertEquals(Status.Code.UNAVAILABLE, assertFailsWith<StatusRuntimeException> { w.skills.getKnownSiteNames(req) }.status.code)
        }
    }

    @Test
    fun `다른 기체를 지목하면 라우팅 실패이지 거절이 아니다`() {
        World().use { w ->
            val e = assertFailsWith<StatusRuntimeException> {
                w.tasks.startTask(StartTaskRequest.newBuilder().setHeader(RequestHeaders.build("picasso.v1.StartTaskRequest", "someone-else", "c")).setTaskId("T").setRevision(1).setSkillType("pick_place").build())
            }
            assertEquals(Status.Code.NOT_FOUND, e.status.code)
        }
    }

    private companion object {
        const val ROBOT = "fake-01"
        val PROFILE: Path = Path.of("..", "profile", "fixtures", "minimal.json").normalize()
    }
}
