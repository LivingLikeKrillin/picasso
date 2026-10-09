package dev.picasso.middleware

import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.WatchTaskResponse
import dev.picasso.middleware.mission.Activation
import dev.picasso.middleware.mission.DefinedCapability
import dev.picasso.middleware.mission.InMemoryMissionCatalog
import dev.picasso.middleware.mission.MissionFixtures
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * **재기동 뒤 다시 짓기**(§15.213). 담는 쪽이 자기 기록으로 넘긴 작업 지시를 [Middleware.resume] 이 같은 정체성으로 다시 짓는다.
 *
 * 기체는 재기동 전 인스턴스가 낸 태스크를 들고 있는 더블이다. 같은 태스크 id 로 `StartTask` 가 오면 새 태스크를 만들지 않고
 * 기존 핸들을 돌려준다(계약의 멱등). 그래서 [Robot.created] 가 0 이면 다시 지은 실행이 새 명령을 내지 않은 것이다.
 * 미믹을 안 쓰는 것은 재작업 접미사가 붙은 태스크를 재기동 전 상태로 기체에 바로 세우기 위해서다.
 */
class ResumeTest {

    // ── 계획

    @Test
    fun `넘겨받은 임무 버전으로 계획하고 카탈로그의 활성 버전을 읽지 않는다`() {
        val catalog = InMemoryMissionCatalog(now = { T0 })
        assertIs<Activation.Activated>(catalog.activate(MissionFixtures.PREPARE_SEQUENCED_RACK, MissionFixtures.SIGNALS, FloorOwnership.None, MissionFixtures.SITE_SKILLS))
        assertIs<Activation.Activated>(catalog.activate(MissionFixtures.withArrivalWait(), MissionFixtures.SIGNALS, FloorOwnership.None, MissionFixtures.SITE_SKILLS))
        assertEquals(2, catalog.active(PrepareSequencedRack.WORK_MASTER)!!.missionVersion, "시험의 전제: 활성 버전이 2 다")

        World(catalog).use { w ->
            val exec = w.resume(mission = version(1))

            assertEquals(1, exec.missionVersion, "넘겨받은 버전이 아니라 카탈로그의 활성 버전으로 계획했다")
            assertEquals(listOf(SLOT), exec.units.map { it.unitId }, "버전 2 의 대기 단위가 들어왔다")
        }
    }

    @Test
    fun `배정 관문을 걸지 않고 조치 탐색 기록을 남기지 않는다`() {
        // 대조: 출발 자리가 비었으므로 같은 작업 지시를 submit 하면 관문이 출발 결품으로 거부하고 탐색 기록을 남긴다.
        World().use { w ->
            w.cell.empty(BIN)
            assertIs<Middleware.Submission.Rejected>(w.mw.submit(order(), ROBOT))
            assertTrue(w.mw.remedySearches().isNotEmpty(), "시험의 전제: 관문이 이 작업 지시를 거부하고 기록한다")
        }

        // 재기동 뒤에는 자재를 이미 집어 갔으므로 출발 자리가 비어 있다. 다시 짓기는 관문을 다시 걸지 않는다.
        World().use { w ->
            w.cell.empty(BIN)
            w.robot.hold("$ORDER#$SLOT", TaskState.TASK_STATE_RUNNING)
            val outcome = w.mw.resume(order(), ROBOT, ActiveMission(PrepareSequencedRack(), null))

            assertIs<Middleware.Submission.Accepted>(outcome, "관문을 다시 걸었다: $outcome")
            assertEquals(emptyList(), w.mw.remedySearches(), "다시 짓기가 조치 탐색 기록을 남겼다")
        }
    }

    @Test
    fun `넘겨받은 정의의 WorkMaster 가 작업 지시와 다르면 거부하고 실행을 만들지 않는다`() {
        World().use { w ->
            // 스냅숏도 못 읽는다. WorkMaster 대조가 먼저이므로 «나중에 다시» 로 읽히는 사유가 나오면 안 된다.
            w.robot.unreadable = true
            val outcome = w.mw.resume(order(), ROBOT, ActiveMission(Interleaved(), null))

            val rejected = assertIs<Middleware.Submission.Rejected>(outcome)
            assertTrue("WorkMaster" in rejected.reason, rejected.reason)
            assertTrue(!rejected.reason.startsWith(Middleware.RESUME_SNAPSHOT_UNREADABLE), "스냅숏을 WorkMaster 대조보다 먼저 읽었다: ${rejected.reason}")
            assertEquals(emptyList(), w.mw.executions())
        }
    }

    @Test
    fun `요구 근거 등급이 정의의 최고 등급을 넘으면 거부하고 실행을 만들지 않는다`() {
        World().use { w ->
            w.robot.hold("$ORDER#$SLOT", TaskState.TASK_STATE_RUNNING)
            val outcome = w.mw.resume(order(required = Evidence.E3), ROBOT, ActiveMission(PrepareSequencedRack(), null))

            val rejected = assertIs<Middleware.Submission.Rejected>(outcome, "근거 등급 검사를 안 거쳤다: $outcome")
            assertTrue("E3" in rejected.reason, rejected.reason)
            assertEquals(emptyList(), w.mw.executions())
        }
    }

    // ── 재작업 횟수

    @Test
    fun `태스크 id 접미사의 가장 큰 재작업 횟수를 맞추고 첫 pump 가 그 태스크의 기존 핸들에 붙는다`() {
        World().use { w ->
            // 재기동 전 인스턴스가 두 번 재작업했다. 넣는 순서를 섞어 «마지막으로 본 것» 과 «가장 큰 것» 이 갈리게 한다.
            w.robot.hold("$ORDER#$SLOT", TaskState.TASK_STATE_FAILED)
            w.robot.hold("$ORDER#$SLOT@r2", TaskState.TASK_STATE_RUNNING)
            w.robot.hold("$ORDER#$SLOT@r1", TaskState.TASK_STATE_FAILED)
            val exec = w.resume()

            assertEquals(2, exec.units.single().attempt, "재작업 횟수를 태스크 id 접미사의 가장 큰 값으로 맞추지 않았다")
            val line = exec.eventTrail.single { it.kind == Middleware.RESUME_ATTEMPT }
            assertTrue("$SLOT: attempt=2" in line.detail && "$ORDER#$SLOT@r2" in line.detail, line.detail)

            w.mw.pump()
            assertEquals(listOf("$ORDER#$SLOT@r2"), w.robot.starts, "첫 pump 가 재작업 태스크가 아닌 id 로 갔다")
            assertEquals(0, w.robot.created, "기존 핸들에 붙지 않고 새 태스크를 만들었다")
            assertEquals(UnitState.RUNNING, exec.units.single().state)
        }
    }

    @Test
    fun `두 자리 재작업 횟수도 수로 견주어 접미사에서 읽는다`() {
        World().use { w ->
            // 글자로 견주면 @r9 가 @r12 보다 크다. @r013 은 startUnit 이 만들 수 없는 id 라 이 단위의 것이 아니다.
            w.robot.hold("$ORDER#$SLOT@r12", TaskState.TASK_STATE_RUNNING)
            w.robot.hold("$ORDER#$SLOT@r9", TaskState.TASK_STATE_FAILED)
            w.robot.hold("$ORDER#$SLOT@r013", TaskState.TASK_STATE_FAILED)
            val exec = w.resume()

            assertEquals(12, exec.units.single().attempt)
            w.mw.pump()
            assertEquals(listOf("$ORDER#$SLOT@r12"), w.robot.starts)
        }
    }

    @Test
    fun `접미사 없는 태스크만 있으면 재작업 횟수는 0 이고 태스크가 없는 로봇 단위는 자취에 안 남는다`() {
        World().use { w ->
            w.robot.hold("$ORDER#$SLOT", TaskState.TASK_STATE_RUNNING)
            val exec = w.resume(order = order(slots = listOf(SLOT, SLOT_2)))

            assertEquals(listOf(0, 0), exec.units.map { it.attempt })
            assertEquals(listOf(SLOT), exec.eventTrail.filter { it.kind == Middleware.RESUME_ATTEMPT }.map { it.detail.substringBefore(':') })

            w.mw.pump()
            assertEquals(listOf("$ORDER#$SLOT"), w.robot.starts)
            assertEquals(0, w.robot.created)
        }
    }

    @Test
    fun `다른 단위 id 가 앞머리인 단위의 태스크를 제 것으로 읽지 않는다`() {
        World().use { w ->
            // ${SLOT}0 의 태스크 id 는 SLOT 의 태스크 id 로 시작한다.
            w.robot.hold("$ORDER#$SLOT", TaskState.TASK_STATE_SUCCEEDED)
            w.robot.hold("$ORDER#${SLOT}0@r3", TaskState.TASK_STATE_RUNNING)
            val exec = w.resume(order = order(slots = listOf(SLOT, "${SLOT}0")))

            assertEquals(listOf(0, 3), exec.units.map { it.attempt })
        }
    }

    // ── 앞선 로봇 단위

    @Test
    fun `마지막 로봇 태스크 앞에서 성공한 로봇 단위는 설비 근거를 다시 묻지 않고 E0 으로 닫고 마지막 단위는 보통대로 확인한다`() {
        World().use { w ->
            // 첫 슬롯은 이전 인스턴스에서 끝났고 지금 설비는 다른 부품을 본다. 다시 확인하면 불일치로 실패한다.
            w.robot.hold("$ORDER#$SLOT", TaskState.TASK_STATE_SUCCEEDED)
            w.robot.hold("$ORDER#$SLOT_2", TaskState.TASK_STATE_RUNNING)
            w.cell.program(SLOT, "ENGINE-COVER-X")
            val exec = w.resume(order = order(required = Evidence.E2, slots = listOf(SLOT, SLOT_2)))

            val line = exec.eventTrail.single { it.kind == Middleware.RESUME_PRIOR_ROBOT }
            assertTrue(line.detail.startsWith("$SLOT:") && SLOT_2 in line.detail, line.detail)

            w.mw.pump()
            w.mw.pump()
            val first = exec.units.single { it.unitId == SLOT }
            assertEquals(UnitState.DONE, first.state, "앞선 로봇 단위의 완료에 설비 근거를 다시 물었다: ${first.note}")
            assertEquals(Evidence.E0, first.reached)
            assertEquals(Verification.NOT_REQUESTED, first.verification)
            assertTrue(exec.eventTrail.none { it.kind == "CELL_SIGNAL" && it.detail.startsWith("$SLOT:") }, "앞선 로봇 단위의 자리를 설비에 물었다")
            assertEquals(UnitState.RUNNING, exec.units.single { it.unitId == SLOT_2 }.state)

            // 마지막 로봇 단위는 보통대로 설비로 확인한다.
            w.cell.program(SLOT_2, PART)
            w.robot.hold("$ORDER#$SLOT_2", TaskState.TASK_STATE_SUCCEEDED)
            w.mw.pump()
            val second = exec.units.single { it.unitId == SLOT_2 }
            assertEquals(UnitState.DONE, second.state)
            assertEquals(Evidence.E2, second.reached, "마지막 로봇 단위까지 설비 확인을 건너뛰었다")
            assertEquals(Verification.MATCHED, second.verification)
            assertEquals(Evidence.E0, w.mw.responses().last().reachedEvidence)
        }
    }

    @Test
    fun `마지막 로봇 태스크 앞의 로봇 단위가 실패로 다시 관측되면 보통의 실패 경로를 탄다`() {
        World().use { w ->
            w.robot.hold("$ORDER#$SLOT", TaskState.TASK_STATE_FAILED)
            w.robot.hold("$ORDER#$SLOT_2", TaskState.TASK_STATE_RUNNING)
            val exec = w.resume(order = order(slots = listOf(SLOT, SLOT_2)))

            w.mw.pump()
            w.mw.pump()
            val first = exec.units.single { it.unitId == SLOT }
            assertEquals(UnitState.FAILED, first.state, "앞선 로봇 단위의 실패를 완료로 닫았다")
            assertTrue(w.mw.incidents().any { it.unitId == SLOT }, "실패한 앞선 로봇 단위의 인시던트가 없다")
        }
    }

    // ── 앞선 설비 대기

    @Test
    fun `마지막 로봇 태스크보다 앞선 설비 대기는 근거 등급 E0 인 채 끝난 것으로 두고 뒤 대기는 그대로 둔다`() {
        World().use { w ->
            // w1 · p1 · w2 · p2 · w3 에서 p1 은 끝났고 p2 가 돈다. 그러면 w1 과 w2 는 이미 통과했다.
            w.robot.hold("$ORDER#p1", TaskState.TASK_STATE_SUCCEEDED)
            w.robot.hold("$ORDER#p2", TaskState.TASK_STATE_RUNNING)
            val exec = w.resume(mission = ActiveMission(Interleaved(), null), order = order(workMaster = Interleaved.WORK_MASTER))

            val byId = exec.units.associateBy { it.unitId }
            assertEquals(UnitState.DONE, byId.getValue("w1").state)
            assertEquals(UnitState.DONE, byId.getValue("w2").state, "마지막이 아닌 첫 로봇 태스크까지만 통과로 뒀다")
            assertEquals(UnitState.PENDING, byId.getValue("w3").state, "마지막 로봇 태스크 뒤의 대기까지 통과로 뒀다")
            assertEquals(listOf(Evidence.E0, Evidence.E0), listOf(byId.getValue("w1").reached, byId.getValue("w2").reached))
            assertEquals(listOf(UnitState.PENDING, UnitState.PENDING), listOf(byId.getValue("p1").state, byId.getValue("p2").state))

            // 흔적은 실행 자취에 남고 단위 메모에는 없다. 메모는 작업 응답의 미완 단위 값으로 나간다.
            val passed = exec.eventTrail.filter { it.kind == Middleware.RESUME_SIGNAL_PASSED }
            assertEquals(listOf("w1", "w2"), passed.map { it.detail.substringBefore(':') })
            assertTrue(passed.all { "p2" in it.detail && "E0" in it.detail }, passed.joinToString { it.detail })
            assertEquals(listOf<String?>(null, null, null, null, null), exec.units.map { it.note }, "다시 짓기의 흔적을 단위 메모에 적었다")
        }
    }

    @Test
    fun `태스크가 앞 로봇 단위에만 있으면 그 뒤 설비 대기는 다시 기다린다`() {
        World().use { w ->
            w.robot.hold("$ORDER#p1", TaskState.TASK_STATE_RUNNING)
            val exec = w.resume(mission = ActiveMission(Interleaved(), null), order = order(workMaster = Interleaved.WORK_MASTER))

            assertEquals(
                listOf(UnitState.DONE, UnitState.PENDING, UnitState.PENDING, UnitState.PENDING, UnitState.PENDING),
                exec.units.map { it.state },
            )
        }
    }

    @Test
    fun `기체에 태스크가 없으면 설비 대기를 통과로 두지 않는다`() {
        World().use { w ->
            val exec = w.resume(mission = ActiveMission(Interleaved(), null), order = order(workMaster = Interleaved.WORK_MASTER))

            assertTrue(exec.units.all { it.state == UnitState.PENDING })
            assertTrue(exec.eventTrail.none { it.kind == Middleware.RESUME_SIGNAL_PASSED })
        }
    }

    @Test
    fun `통과로 둔 설비 대기 뒤 첫 pump 가 로봇 단위를 시작하고 작업 응답의 도달 근거 등급이 E0 이다`() {
        World().use { w ->
            // 버전 2 는 랙 도착 대기 뒤 슬롯 하나. 대기 신호는 지금 없다. 다시 판정하면 대기가 다시 선다.
            w.robot.hold("$ORDER#$SLOT", TaskState.TASK_STATE_RUNNING)
            val exec = w.resume(mission = version(2), order = order(required = Evidence.E2))
            assertEquals(UnitState.DONE, exec.units.first { it.route == Route.SIGNAL }.state)

            w.mw.pump()
            assertEquals(listOf("$ORDER#$SLOT"), w.robot.starts, "설비 대기를 다시 기다리고 로봇 단위를 시작하지 않았다")
            assertEquals(0, w.robot.created)

            w.cell.program(SLOT, PART)
            w.robot.hold("$ORDER#$SLOT", TaskState.TASK_STATE_SUCCEEDED)
            w.mw.pump()

            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(Evidence.E2, exec.units.single { it.unitId == SLOT }.reached, "시험의 전제: 로봇 단위는 설비로 확인됐다")
            val response = w.mw.responses().last()
            assertEquals(listOf(MissionFixtures.WAIT_NODE, SLOT), response.completedUnits)
            assertEquals(Evidence.E0, response.reachedEvidence, "관측하지 않은 대기가 도달 근거 등급을 끌어내리지 않았다")
        }
    }

    // ── 거부와 멱등

    @Test
    fun `스냅숏을 못 읽으면 거부하고 실행을 만들지 않는다`() {
        World().use { w ->
            w.robot.hold("$ORDER#$SLOT@r1", TaskState.TASK_STATE_RUNNING)
            w.robot.unreadable = true
            val outcome = w.mw.resume(order(), ROBOT, ActiveMission(PrepareSequencedRack(), null))

            val rejected = assertIs<Middleware.Submission.Rejected>(outcome, "스냅숏 없이 지었다: $outcome")
            assertTrue(rejected.reason.startsWith(Middleware.RESUME_SNAPSHOT_UNREADABLE), rejected.reason)
            assertTrue("스냅숏" in rejected.reason && ROBOT in rejected.reason, rejected.reason)
            assertEquals(emptyList(), w.mw.executions())
            assertEquals(emptyList(), w.mw.remedySearches())

            w.mw.pump()
            assertEquals(emptyList(), w.robot.starts, "만들지 않은 실행이 명령을 냈다")
        }
    }

    @Test
    fun `같은 작업 지시 id 를 두 번 다시 지으면 둘째는 멱등이고 실행이 하나다`() {
        World().use { w ->
            w.robot.hold("$ORDER#$SLOT", TaskState.TASK_STATE_RUNNING)
            val first = w.resume()
            val again = w.mw.resume(order(), ROBOT, ActiveMission(PrepareSequencedRack(), null))

            assertSame(first, assertIs<Middleware.Submission.Idempotent>(again, "같은 작업 지시를 새 실행으로 다시 지었다: $again").execution)
            assertEquals(1, w.mw.executions().size)
        }
    }

    @Test
    fun `이미 선 실행이 있으면 리비전 경로로 가서 새 버전을 그 실행에 붙인다`() {
        World().use { w ->
            val first = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.robot.unreadable = true // 리비전 경로는 스냅숏을 안 읽는다.
            val revised = w.mw.resume(order(version = 2), ROBOT, ActiveMission(PrepareSequencedRack(), null))

            assertSame(first, assertIs<Middleware.Submission.Accepted>(revised, "$revised").execution)
            assertEquals(2, first.version)
            assertEquals(1, w.mw.executions().size)
        }
    }

    // ── 더블

    private class World(catalog: InMemoryMissionCatalog? = null) : AutoCloseable {
        var t: Instant = T0
        val robot = Robot { t }
        val cell = CellMimic(now = { t })
        val mw = Middleware(robot, cell, now = { t }, missions = catalog)

        fun resume(
            mission: ActiveMission = ActiveMission(PrepareSequencedRack(), null),
            order: JobOrder = order(),
        ): Middleware.Execution = assertIs<Middleware.Submission.Accepted>(mw.resume(order, ROBOT, mission)).execution

        override fun close() = Unit
    }

    /**
     * 재기동 전 인스턴스가 낸 태스크를 든 기체. 같은 태스크 id 가 다시 오면 기존 핸들을 돌려주고 [created] 를 올리지 않는다.
     * 갱신은 지금 상태 하나를 돌려준다.
     */
    private class Robot(private val now: () -> Instant) : RobotPort {
        private val tasks = linkedMapOf<String, TaskState>()
        val starts = mutableListOf<String>()
        var created = 0
        var unreadable = false

        fun hold(taskId: String, state: TaskState) {
            tasks[taskId] = state
        }

        override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse {
            starts += taskId
            if (taskId !in tasks) {
                tasks[taskId] = TaskState.TASK_STATE_ACCEPTED
                created += 1
            }
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

        override fun snapshot(robotId: String): RobotSnapshot? =
            if (unreadable) null else RobotSnapshot(0, emptyList(), ConnectionState.CONNECTION_STATE_ONLINE, tasks.toMap())

        override fun replay(robotId: String, from: Long): Replay = Replay.Events(emptyList())
    }

    /** 설비 대기와 로봇 단위가 번갈아 서는 정의(w1 · p1 · w2 · p2 · w3). 앞선 대기의 경계를 보기 위한 것이다. */
    private class Interleaved : LogicalCapability {
        override val workMasterId: String = WORK_MASTER
        override val maxEvidence: Evidence = Evidence.E2

        override fun plan(order: JobOrder): List<ExecutionUnit> =
            listOf(wait("w1"), robot("p1"), wait("w2"), robot("p2"), wait("w3"))

        private fun wait(id: String) = ExecutionUnit(
            unitId = id,
            route = Route.SIGNAL,
            skillType = WaitSpec.SKILL_TYPE,
            parameters = emptyMap(),
            expectedIdentity = null,
            source = null,
            destination = null,
            wait = WaitSpec("guard_closed", "true", Duration.ofSeconds(60), DeadlineOutcome.OPERATOR_HOLD),
        )

        private fun robot(id: String) = ExecutionUnit(
            unitId = id,
            route = Route.ROBOT,
            skillType = "navigate_to",
            parameters = emptyMap(),
            expectedIdentity = null,
            source = null,
            destination = null,
        )

        companion object {
            const val WORK_MASTER = "Interleaved"
        }
    }

    private companion object {
        const val ROBOT = "hum-02"
        const val ORDER = "SEQ-212"
        const val SLOT = "RACK-212.S01"
        const val SLOT_2 = "RACK-212.S02"
        const val BIN = "SEQ-IN-02.BIN-A"
        const val PART = "ENGINE-COVER-A"
        val T0: Instant = Instant.parse("2026-10-09T00:00:00Z")

        /** 데이터판 정의. 1 은 코드 `PrepareSequencedRack` 과 같은 모양, 2 는 그 앞에 랙 도착 대기를 둔 것이다. */
        fun version(n: Int): ActiveMission {
            val text = if (n == 1) MissionFixtures.PREPARE_SEQUENCED_RACK else MissionFixtures.withArrivalWait()
            return ActiveMission(DefinedCapability(MissionFixtures.parsed(text)), n)
        }

        fun order(
            version: Int = 1,
            required: Evidence = Evidence.E0,
            workMaster: String = PrepareSequencedRack.WORK_MASTER,
            slots: List<String> = listOf(SLOT),
        ) = JobOrder(
            jobOrderId = ORDER,
            workMasterId = workMaster,
            version = version,
            requiredEvidence = required,
            equipmentRequirements = slots.map { EquipmentRequirement(it, EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to PART)) } +
                EquipmentRequirement(BIN, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to PART)),
        )
    }
}
