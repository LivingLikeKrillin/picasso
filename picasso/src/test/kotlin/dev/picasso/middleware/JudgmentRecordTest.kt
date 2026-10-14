package dev.picasso.middleware

import dev.picasso.capability.Remedy
import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.WatchTaskResponse
import dev.picasso.harness.Harness
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **판단 기록으로 다시 짓기**(§15.213). 앞 인스턴스가 낸 판단 기록을 담는 쪽이 영속했다가 재기동 뒤 [Middleware.resume] 에
 * 넘기면, 다시 지은 실행은 그 판정과 판단이 사라진 적 없는 것처럼 이어 간다.
 *
 * 재기동은 **미들웨어를 새로 세우는 것**이다. 기체와 셀은 그대로 두고 같은 더블 위에 두 번째 [Middleware] 를 띄운다. 기록은
 * 언제나 인코딩을 한 번 지나 넘긴다([persisted]) — 담는 쪽이 저장소에서 읽어 오는 모양이다.
 *
 * 시험마다 **기록 없이 다시 지은 대조**를 함께 둔다. 기록 없이도 같은 답이 나오면 그 시험은 기록이 무엇을 옮기는지 말하지 못한다.
 */
class JudgmentRecordTest {

    // ── 운영자 판단

    @Test
    fun `운영자가 확인한 단위는 다시 지은 뒤 다시 보류에 서지 않는다`() {
        World().use { w ->
            val exec = w.submit(order(required = Evidence.E2))
            w.old.pump()
            w.robot.set("$ORDER#s1", TaskState.TASK_STATE_FAILED)
            w.cell.program(slot(1), PART)
            w.old.pump()
            assertEquals(UnitState.OPERATOR_HOLD, exec.units.single().state, "시험의 전제: 하류는 실패, 설비에는 있다")
            assertEquals(ResolveOutcome.Resolved, w.old.resolve(exec.executionId, "s1", OperatorDecision.CONFIRM_DONE, OPERATOR))
            val record = persisted(w.old.judgmentRecord(exec.executionId))

            val again = w.restart()
            val resumed = again.resumeWith(order(required = Evidence.E2), record)
            again.mw.pump()
            again.mw.pump()
            val unit = resumed.units.single()
            assertEquals(UnitState.DONE, unit.state, "확인한 단위가 다시 판정됐다: ${unit.note}")
            assertEquals(Evidence.E2, unit.reached)
            assertEquals(PhysicalState.PHYSICALLY_DONE, resumed.physicalState)
            assertEquals(emptyList(), again.mw.incidents(), "확인한 단위의 사건을 다시 열었다")
            val decision = assertNotNull(again.mw.judgmentRecord(resumed.executionId)!!.units.single().decision, "판단을 다시 내보내지 않는다")
            assertEquals(OperatorDecision.CONFIRM_DONE to OPERATOR, decision.decision to decision.approver)

            // 대조: 기록 없이 다시 지으면 같은 실패와 설비 근거로 다시 보류에 선다.
            val bare = w.restart()
            val rebuilt = bare.resumeWith(order(required = Evidence.E2), null)
            bare.mw.pump()
            bare.mw.pump()
            assertEquals(UnitState.OPERATOR_HOLD, rebuilt.units.single().state)
        }
    }

    @Test
    fun `재작업 판단이 서고 내기 전에 재기동해도 새 시도로 나가고 다시 묻지 않는다`() {
        World().use { w ->
            val exec = w.submit(order(required = Evidence.E2))
            w.old.pump()
            w.robot.set("$ORDER#s1", TaskState.TASK_STATE_FAILED)
            w.cell.program(slot(1), PART)
            w.old.pump()
            w.old.resolve(exec.executionId, "s1", OperatorDecision.REWORK, OPERATOR)
            val record = persisted(w.old.judgmentRecord(exec.executionId))
            val created = w.robot.created

            val again = w.restart()
            val resumed = again.resumeWith(order(required = Evidence.E2), record)
            assertEquals(1, resumed.units.single().attempt)
            again.mw.pump()
            assertEquals("$ORDER#s1@r1", w.robot.starts.last(), "재작업 판단이 내기 전에 사라졌다")
            assertEquals(created + 1, w.robot.created)
            assertEquals(UnitState.RUNNING, resumed.units.single().state)
            val decision = assertNotNull(again.mw.judgmentRecord(resumed.executionId)!!.units.single().decision)
            assertEquals(OperatorDecision.REWORK to 0, decision.decision to decision.attempt)
        }
    }

    // ── 감수한 결함

    @Test
    fun `감수한 결함은 다시 지은 뒤에도 감수한 채라 운영자를 다시 부르지 않는다`() {
        World().use { w ->
            w.robot.faults = listOf(Fault.newBuilder().setErrorType("LOW_BATTERY").setCanAcceptNewTask(false).build())
            val exec = w.submit(order(slots = 2))
            w.old.pump()
            assertEquals(PhysicalState.OPERATOR_HOLD, exec.physicalState, "시험의 전제: 결함이 첫 단위를 막는다")
            assertTrue(w.old.release(exec.executionId))
            w.old.pump()
            assertEquals(UnitState.RUNNING, exec.units.first().state)
            val record = persisted(w.old.judgmentRecord(exec.executionId))

            val again = w.restart()
            val resumed = again.resumeWith(order(slots = 2), record)
            again.mw.pump()
            assertEquals(emptyList(), resumed.blockedBy, "감수한 결함이 다시 막았다")
            assertEquals(UnitState.RUNNING, resumed.units.first().state)
            assertEquals(PhysicalState.RUNNING, resumed.physicalState)

            val bare = w.restart()
            val rebuilt = bare.resumeWith(order(slots = 2), null)
            bare.mw.pump()
            assertEquals(listOf("LOW_BATTERY"), rebuilt.blockedBy.map { it.errorType })
        }
    }

    // ── 근거 윈도우와 기한

    @Test
    fun `근거 윈도우의 요청 시각이 넘어와 처음 요청 뒤의 설비 신호를 잠정 근거로 본다`() {
        World(lookup = ExecutionLookup.NONE).use { w ->
            w.robot.lost += "$ORDER#s1"
            val exec = w.submit(order())
            w.old.pump()
            assertEquals(UnitState.IN_DOUBT, exec.units.single().state, "시험의 전제: 첫 요청의 답을 잃었다")
            val record = persisted(w.old.judgmentRecord(exec.executionId))
            assertEquals(T0, record.units.single().requestedAt)

            // 재기동 사이에 설비가 부품을 봤다. 처음 요청 뒤의 일이므로 이 요청의 근거다.
            w.t = T0.plusSeconds(50)
            w.cell.programAt(slot(1), PART, T0.plusSeconds(5))
            val again = w.restart()
            val resumed = again.resumeWith(order(), record)
            again.mw.pump()
            again.mw.pump()
            val unit = resumed.units.single()
            assertEquals(UnitState.OPERATOR_HOLD, unit.state, "처음 요청 시각을 잃어 신호를 옛 것으로 읽었다: ${unit.note}")
            assertEquals(Verification.MATCHED, unit.verification)
            assertEquals(T0, unit.requestedAt)

            // 대조: 요청 시각이 재기동 시각이면 그 신호는 창 앞이라 유예가 끝날 때까지 기다린다.
            val bare = w.restart()
            val rebuilt = bare.resumeWith(order(), null)
            bare.mw.pump()
            bare.mw.pump()
            assertEquals(UnitState.IN_DOUBT, rebuilt.units.single().state)
        }
    }

    @Test
    fun `설비 대기의 기한은 처음 기다리기 시작한 시각부터 이어 잰다`() {
        World().use { w ->
            val exec = w.submit(order(workMaster = WaitThenSlot.WORK_MASTER))
            w.old.pump()
            assertEquals(UnitState.RUNNING, exec.units.first().state, "시험의 전제: 대기가 시작했다")
            val record = persisted(w.old.judgmentRecord(exec.executionId))

            w.t = T0.plusSeconds(50)
            val again = w.restart()
            val resumed = again.resumeWith(order(workMaster = WaitThenSlot.WORK_MASTER), record, WaitThenSlot())
            val bare = w.restart()
            val rebuilt = bare.resumeWith(order(workMaster = WaitThenSlot.WORK_MASTER), null, WaitThenSlot())
            again.mw.pump()
            bare.mw.pump()

            w.t = T0.plusSeconds(61)
            again.mw.pump()
            bare.mw.pump()
            val wait = resumed.units.first()
            assertEquals(UnitState.OPERATOR_HOLD, wait.state, "기한을 재기동 시각부터 다시 쟀다")
            assertEquals(WaitSpec.SIGNAL_DEADLINE, wait.failureClass)
            assertEquals(UnitState.RUNNING, rebuilt.units.first().state, "대조: 기록 없이는 기한이 재기동 시각부터다")
        }
    }

    @Test
    fun `검증 대기 중이던 단위의 근거 기한은 처음 완료 시각에서 잰다`() {
        World().use { w ->
            val exec = w.submit(order(required = Evidence.E2))
            w.old.pump()
            w.t = T0.plusSeconds(5)
            w.robot.set("$ORDER#s1", TaskState.TASK_STATE_SUCCEEDED)
            w.old.pump()
            assertEquals(UnitState.VERIFYING, exec.units.single().state, "시험의 전제: 설비 신호를 기다린다")
            val record = persisted(w.old.judgmentRecord(exec.executionId))
            assertEquals(VerifyWindow(T0.plusSeconds(5), T0.plusSeconds(20)), record.units.single().verifyWindow)

            w.t = T0.plusSeconds(30)
            val again = w.restart()
            val resumed = again.resumeWith(order(required = Evidence.E2), record)
            val bare = w.restart()
            val rebuilt = bare.resumeWith(order(required = Evidence.E2), null)
            repeat(2) {
                again.mw.pump()
                bare.mw.pump()
            }
            assertEquals(UnitState.UNVERIFIED, resumed.units.single().state, "근거 기한을 다시 관측한 완료 시각에서 다시 정했다")
            assertEquals(T0.plusSeconds(20), resumed.units.single().evidenceDeadline)
            assertEquals(UnitState.VERIFYING, rebuilt.units.single().state, "대조: 기록 없이는 기한이 재기동 뒤의 관측에서 선다")

            // 기한은 그때의 뒤 폭으로 정한 값이다 — 다시 계산하지 않는다. 그때 뒤 폭이 35초였던 기록이면 기한은 그대로 T0+40 이다.
            val wider = record.copy(units = listOf(record.units.single().copy(verifyWindow = VerifyWindow(T0.plusSeconds(5), T0.plusSeconds(40)))))
            val third = w.restart()
            val kept = third.resumeWith(order(required = Evidence.E2), wider)
            repeat(2) { third.mw.pump() }
            assertEquals(T0.plusSeconds(40), kept.units.single().evidenceDeadline, "기록의 기한을 지금의 뒤 폭으로 다시 계산했다")
            assertEquals(UnitState.VERIFYING, kept.units.single().state)
        }
    }

    // ── 이전 근거 확인 결과

    @Test
    fun `이전 근거 확인 결과가 남아 다시 지은 뒤에도 요구 등급 E2 를 채운다`() {
        World().use { w ->
            val exec = w.submit(order(required = Evidence.E2, slots = 2))
            w.cell.program(slot(1), PART)
            w.old.pump()
            w.robot.set("$ORDER#s1", TaskState.TASK_STATE_SUCCEEDED)
            w.old.pump()
            assertEquals(Evidence.E2, exec.units.first().reached, "시험의 전제: 첫 슬롯은 설비로 확인됐다")
            assertEquals(UnitState.RUNNING, exec.units[1].state)
            val record = persisted(w.old.judgmentRecord(exec.executionId))

            val again = w.restart()
            val resumed = again.resumeWith(order(required = Evidence.E2, slots = 2), record)
            w.cell.program(slot(2), PART)
            again.mw.pump()
            w.robot.set("$ORDER#s2", TaskState.TASK_STATE_SUCCEEDED)
            again.mw.pump()

            val first = resumed.units.first()
            assertEquals(Evidence.E2 to Verification.MATCHED, first.reached to first.verification, "앞 인스턴스의 확인 결과를 잃었다")
            assertEquals(PhysicalState.PHYSICALLY_DONE, resumed.physicalState)
            assertEquals(Evidence.E2, again.mw.responses().last().reachedEvidence, "요구 등급을 채운 작업 응답이 E0 으로 나갔다")
            assertTrue(resumed.eventTrail.any { it.kind == Middleware.RESUME_RECORD && it.detail.startsWith("s1:") }, "옮긴 판정이 자취에 없다")
        }
    }

    @Test
    fun `미확인으로 정착한 단위는 다시 지은 뒤에도 미확인이고 다시 관측한 성공으로 닫지 않는다`() {
        World().use { w ->
            val exec = w.submit(order(required = Evidence.E2, slots = 2))
            w.old.pump()
            w.robot.set("$ORDER#s1", TaskState.TASK_STATE_SUCCEEDED)
            w.old.pump()
            w.t = T0.plusSeconds(16)
            w.old.pump()
            assertEquals(UnitState.UNVERIFIED, exec.units.first().state, "시험의 전제: 시간창 안에 신호가 없었다")
            assertEquals(UnitState.RUNNING, exec.units[1].state)
            val record = persisted(w.old.judgmentRecord(exec.executionId))

            val again = w.restart()
            val resumed = again.resumeWith(order(required = Evidence.E2, slots = 2), record)
            val bare = w.restart()
            val rebuilt = bare.resumeWith(order(required = Evidence.E2, slots = 2), null)
            repeat(3) {
                again.mw.pump()
                bare.mw.pump()
            }
            assertEquals(UnitState.UNVERIFIED, resumed.units.first().state, "미확인을 다시 판정했다")
            assertEquals(UnitState.DONE, rebuilt.units.first().state, "대조: 기록 없이는 앞선 단위를 다시 관측한 성공으로 닫는다")
        }
    }

    // ── 승인 소모

    @Test
    fun `승인 소모는 다시 지은 뒤에도 소모로 답하고 조치 열이 계획 앞에 다시 선다`() {
        Approvals().use { w ->
            val found = assertIs<Remedy.Found>(assertNotNull(w.rejectedWhileHolding().remedy))
            val place = mapOf(PrepareSequencedRack.P_OBJECT to "ENGINE-COVER-A", PrepareSequencedRack.P_DESTINATION to "RACK-204.S01")
            val approved = assertIs<Middleware.Submission.Accepted>(
                w.old.approveRemedy(ROBOT, PATROL, List(found.steps.size) { place }, OPERATOR),
            ).execution
            val record = persisted(w.old.judgmentRecord(approved.executionId))
            assertEquals(listOf("remedy-1-pick_place"), record.approval!!.steps.map { it.unitId })

            val again = w.restart()
            val resumed = assertIs<Middleware.Submission.Accepted>(
                again.resume(patrol(), ROBOT, ActiveMission(InspectAsset(), null), record),
            ).execution
            assertEquals("remedy-1-pick_place", resumed.units.first().unitId, "승인한 조치 열이 계획에서 사라졌다")
            assertEquals(place, resumed.units.first().parameters)
            assertEquals(OPERATOR, resumed.approvedBy)

            val refused = assertIs<ApprovalOutcome.Refused>(
                again.attemptApproval(ApprovalAttempt(OPERATOR, ROBOT, PATROL, listOf("pick_place"))),
            )
            assertEquals(ApprovalRefusal.CONSUMED, refused.refusal, refused.reason)
            assertEquals(OPERATOR, refused.consumed!!.approver)
            assertEquals(resumed.executionId, refused.consumed!!.executionId)
            assertIs<Middleware.Submission.Rejected>(again.approveRemedy(ROBOT, PATROL, listOf(place), OPERATOR), "소모된 승인이 다시 쓰였다")

            // 대조: 기록 없이 다시 지으면 조치 열이 없고 소모도 모른다.
            val bare = w.restart()
            val rebuilt = assertIs<Middleware.Submission.Accepted>(bare.resume(patrol(), ROBOT, ActiveMission(InspectAsset(), null))).execution
            assertTrue(rebuilt.units.none { it.unitId.startsWith("remedy-") })
            val none = assertIs<ApprovalOutcome.Refused>(bare.attemptApproval(ApprovalAttempt(OPERATOR, ROBOT, PATROL, listOf("pick_place"))))
            assertEquals(ApprovalRefusal.NO_PROPOSAL, none.refusal)
        }
    }

    // ── 기록과 스냅숏

    @Test
    fun `기록의 시도가 스냅숏보다 앞서면 그 단위의 판정을 옮기지 않는다`() {
        World().use { w ->
            val exec = w.submit(order(required = Evidence.E2))
            w.old.pump()
            w.robot.set("$ORDER#s1", TaskState.TASK_STATE_FAILED)
            w.cell.program(slot(1), PART)
            w.old.pump()
            // 보류일 때 받아 둔 기록. 그 뒤 재작업을 내 기체에 새 시도가 돈다.
            val stale = persisted(w.old.judgmentRecord(exec.executionId))
            w.old.resolve(exec.executionId, "s1", OperatorDecision.REWORK, OPERATOR)
            w.old.pump()
            assertEquals("$ORDER#s1@r1", exec.units.single().taskId)
            val created = w.robot.created

            val again = w.restart()
            val resumed = again.resumeWith(order(required = Evidence.E2), stale)
            again.mw.pump()
            val unit = resumed.units.single()
            assertEquals(UnitState.RUNNING, unit.state, "앞 시도의 보류를 도는 재작업에 붙였다")
            assertEquals("$ORDER#s1@r1", unit.taskId)
            assertEquals(created, w.robot.created)
            assertTrue(resumed.eventTrail.any { it.kind == Middleware.RESUME_RECORD_SUPERSEDED && it.detail.startsWith("s1:") })
        }
    }

    @Test
    fun `다른 작업 지시나 버전의 기록이면 거부하고 실행을 만들지 않는다`() {
        World().use { w ->
            val exec = w.submit(order())
            val record = persisted(w.old.judgmentRecord(exec.executionId))

            val again = w.restart()
            listOf(
                record.copy(jobOrderId = "OTHER"),
                record.copy(orderVersion = 2),
                record.copy(units = record.units + record.units.single().copy(unitId = "ghost")),
            ).forEach { wrong ->
                val rejected = assertIs<Middleware.Submission.Rejected>(again.mw.resume(order(), ROBOT, ActiveMission(Slots(), null), wrong))
                assertTrue(rejected.reason.startsWith(Middleware.RESUME_RECORD_MISMATCH), rejected.reason)
            }
            assertEquals(emptyList(), again.mw.executions())
            assertEquals(0, w.robot.snapshots, "기록을 대조하기 전에 스냅숏을 읽었다")
        }
    }

    @Test
    fun `다시 지은 뒤 내기 전에 받은 기록도 넘겨받은 요청 시각을 싣는다`() {
        World().use { w ->
            val exec = w.submit(order(workMaster = WaitThenSlot.WORK_MASTER))
            w.old.pump()
            val record = persisted(w.old.judgmentRecord(exec.executionId))

            val again = w.restart()
            val resumed = again.resumeWith(order(workMaster = WaitThenSlot.WORK_MASTER), record, WaitThenSlot())
            assertEquals(T0, again.mw.judgmentRecord(resumed.executionId)!!.units.first().requestedAt, "다시 지은 직후의 기록이 기한의 시작을 잃었다")
        }
    }

    // ── 더블

    /** 앞 인스턴스와, 같은 기체·셀 위에 다시 세우는 인스턴스. */
    private class World(lookup: ExecutionLookup = ExecutionLookup.CLIENT_REFERENCE) : AutoCloseable {
        var t: Instant = T0
        val robot = Robot({ t }, lookup)
        val cell = CellMimic(now = { t })
        val old = middleware()

        fun middleware() = Middleware(robot, cell, capabilities = listOf(Slots(), WaitThenSlot()), now = { t })

        fun submit(order: JobOrder): Middleware.Execution = assertIs<Middleware.Submission.Accepted>(old.submit(order, ROBOT)).execution

        fun restart() = Restarted(middleware())

        override fun close() = Unit
    }

    private class Restarted(val mw: Middleware) {
        fun resumeWith(order: JobOrder, record: JudgmentRecord?, capability: LogicalCapability = Slots()): Middleware.Execution =
            assertIs<Middleware.Submission.Accepted>(mw.resume(order, ROBOT, ActiveMission(capability, null), record)).execution
    }

    /** 승인 경로는 에뮬레이터 위에서 세운다 — 든 채인 기체와 그 거절이 있어야 제안이 선다(`RemedyApprovalTest` 와 같은 모양). */
    private class Approvals : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to PRECOND))
        val cell = CellMimic(now = { harness.clock.now() })
        val old = restart()

        fun restart() = Middleware(ClientRobotPort(harness.client()), cell, now = { harness.clock.now() })

        fun rejectedWhileHolding(): Middleware.Submission.Rejected {
            val rack = JobOrder(
                jobOrderId = "SEQ-204",
                workMasterId = PrepareSequencedRack.WORK_MASTER,
                version = 17,
                requiredEvidence = Evidence.E2,
                materialRequirements = listOf(MaterialRequirement("ENGINE-COVER-A", 1)),
                equipmentRequirements = listOf(
                    EquipmentRequirement("RACK-204.S01", "destination", mapOf("material" to "ENGINE-COVER-A")),
                    EquipmentRequirement("SEQ-IN-02.BIN-A", "source", mapOf("material" to "ENGINE-COVER-A")),
                ),
            )
            val exec = assertIs<Middleware.Submission.Accepted>(old.submit(rack, ROBOT)).execution
            repeat(80) {
                old.pump()
                if (exec.units.first().hold.kind == HoldKind.HOLD_KIND_HOLDING) return assertIs(old.submit(patrol(), ROBOT))
                harness.advance(Duration.ofSeconds(1))
                Thread.sleep(40)
            }
            error("기체가 든 채가 되지 않았다")
        }

        override fun close() = harness.close()
    }

    /**
     * 재기동 전 인스턴스가 낸 태스크를 든 기체. 같은 태스크 id 가 다시 오면 기존 핸들을 돌려주고 [created] 를 올리지 않는다.
     * [lost] 의 태스크는 받되 답을 잃는다(던진다). 갱신은 지금 상태 하나를 돌려주고 그 시각을 지금으로 찍는다.
     */
    private class Robot(private val now: () -> Instant, override val executionLookup: ExecutionLookup) : RobotPort {
        private val tasks = linkedMapOf<String, TaskState>()
        val starts = mutableListOf<String>()
        val lost = mutableSetOf<String>()
        var faults: List<Fault> = emptyList()
        var created = 0
        var snapshots = 0

        fun set(taskId: String, state: TaskState) {
            tasks[taskId] = state
        }

        override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse {
            starts += taskId
            if (taskId !in tasks) {
                tasks[taskId] = TaskState.TASK_STATE_RUNNING
                created += 1
            }
            if (taskId in lost) throw IllegalStateException("답을 잃었다")
            return StartTaskResponse.newBuilder()
                .setHandle(TaskHandle.newBuilder().setTaskId(taskId).setRevision(revision).setRobotId(robotId))
                .build()
        }

        override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> {
            val state = tasks[handle.taskId] ?: return emptyList()
            return listOf(
                WatchTaskResponse.newBuilder()
                    .setHeader(MessageHeader.newBuilder().setStateAsOf(now().toString()))
                    .setState(state)
                    .setRevision(handle.revision)
                    .build(),
            )
        }

        override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse = CancelTaskResponse.getDefaultInstance()

        override fun snapshot(robotId: String): RobotSnapshot {
            snapshots += 1
            return RobotSnapshot(0, faults, ConnectionState.CONNECTION_STATE_ONLINE, tasks.toMap())
        }

        override fun replay(robotId: String, from: Long): Replay = Replay.Events(emptyList())
    }

    /** 슬롯마다 로봇 단위 하나. 목적지에 부품을 놓으므로 요구가 E2 면 설비로 확인한다. */
    private class Slots : LogicalCapability {
        override val workMasterId: String = WORK_MASTER
        override val maxEvidence: Evidence = Evidence.E2
        override fun plan(order: JobOrder): List<ExecutionUnit> =
            (1..order.parameters.getValue("slots").toInt()).map { slotUnit(it) }

        companion object {
            const val WORK_MASTER = "Slots"
        }
    }

    /** 랙 도착 대기(기한 60초, 지나면 운영자) 뒤 슬롯 하나. */
    private class WaitThenSlot : LogicalCapability {
        override val workMasterId: String = WORK_MASTER
        override val maxEvidence: Evidence = Evidence.E2
        override fun plan(order: JobOrder): List<ExecutionUnit> = listOf(
            ExecutionUnit(
                unitId = "w",
                route = Route.SIGNAL,
                skillType = WaitSpec.SKILL_TYPE,
                parameters = emptyMap(),
                expectedIdentity = null,
                source = null,
                destination = null,
                wait = WaitSpec("rack_arrived", "true", Duration.ofSeconds(60), DeadlineOutcome.OPERATOR_HOLD),
            ),
            slotUnit(1),
        )

        companion object {
            const val WORK_MASTER = "WaitThenSlot"
        }
    }

    private companion object {
        const val ROBOT = "hum-02"
        const val ORDER = "REC-1"
        const val PATROL = "PATROL-1"
        const val PART = "ENGINE-COVER-A"
        val OPERATOR = Approver("op-1", ApproverKind.PERSON)
        val T0: Instant = Instant.parse("2026-10-14T00:00:00Z")
        val PRECOND: Path = Path.of("..", "profile", "fixtures", "precondition.json").normalize()

        fun slot(n: Int) = "RACK-1.S0$n"

        fun slotUnit(n: Int) = ExecutionUnit(
            unitId = "s$n",
            route = Route.ROBOT,
            skillType = "pick_place",
            parameters = emptyMap(),
            expectedIdentity = PART,
            source = null,
            destination = slot(n),
        )

        fun order(required: Evidence = Evidence.E0, slots: Int = 1, workMaster: String = Slots.WORK_MASTER) =
            JobOrder(ORDER, workMaster, 1, required, parameters = mapOf("slots" to "$slots"))

        fun patrol() = JobOrder(
            jobOrderId = PATROL,
            workMasterId = InspectAsset.WORK_MASTER,
            version = 1,
            equipmentRequirements = listOf(
                EquipmentRequirement("PUMP-01", EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to "PUMP-ROOM-1")),
            ),
        )

        /** 담는 쪽이 저장소에 적었다가 읽어 오는 모양 — 인코딩을 한 번 지난다. */
        fun persisted(record: JudgmentRecord?): JudgmentRecord =
            JudgmentRecordWire.decode(JudgmentRecordWire.encode(assertNotNull(record, "판단 기록이 없다")))
    }
}
