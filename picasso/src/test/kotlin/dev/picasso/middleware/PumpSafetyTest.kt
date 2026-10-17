package dev.picasso.middleware

import dev.picasso.contracts.v1.FailureClass
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **구동이 다음 단위를 내기 전의 안전 셋** — 하위가 스스로 낸 취소, 막음·보류·미확정이 풀릴 때의 작업 응답, 관측 실패 동안의 막음.
 *
 * 셋 다 «다음 단위가 나가는가» 와 «상위가 마지막으로 본 작업 응답이 지금과 맞는가» 를 본다. 나간 것은 기체 쪽(미믹의 태스크 표)에서
 * 센다 — 실행의 상태만 보면 안 나간 명령과 나갔다가 끝난 명령이 같아 보인다.
 *
 * 기체는 4족 픽스처 `quadruped-b`, 업무는 점검 순회(지점마다 이동 뒤 점검)다. `inspect` 는 취소를 받는다.
 */
class PumpSafetyTest {

    /** 스냅숏에 결함 하나를 얹거나 스냅숏을 못 읽게 하는 포트. 미믹에는 결함을 걷는 조작이 없어 여기서 만든다. */
    private class FaultSwitch(private val delegate: RobotPort) : RobotPort by delegate {
        var fault: Fault? = null
        var blind = false

        override fun snapshot(robotId: String): RobotSnapshot? {
            if (blind) return null
            val snapshot = delegate.snapshot(robotId) ?: return null
            return fault?.let { snapshot.copy(faults = snapshot.faults + it) } ?: snapshot
        }

        override fun replay(robotId: String, from: Long): Replay? = if (blind) null else delegate.replay(robotId, from)
    }

    /** 다음 `StartTask` 의 답을 버린다 — 요청은 기체에 닿는다. [drop] 만큼. */
    private class DropNext(private val delegate: RobotPort) : RobotPort by delegate {
        var drop = 0

        override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse {
            val response = delegate.start(robotId, taskId, revision, skillType, parameters)
            if (drop > 0) {
                drop -= 1
                throw IllegalStateException("response lost after the request reached the robot")
            }
            return response
        }
    }

    private class World(wrap: (RobotPort) -> RobotPort = { it }) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to Path.of("..", "profile", "profiles", "quadruped-b.json").normalize()))
        val port = wrap(ClientRobotPort(harness.client()))
        val mw = Middleware(port, now = { harness.clock.now() })

        fun tasks() = harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build()).tasksList
        fun state(taskId: String) = tasks().firstOrNull { it.taskId == taskId }?.taskState

        fun drive(mw: Middleware = this.mw, rounds: Int = 120, until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(Duration.ofSeconds(10))
                Thread.sleep(30)
                mw.pump()
                if (until()) return
            }
            error("조건에 못 미쳤다: tasks=${tasks().map { it.taskId to it.taskState }}")
        }

        fun rounds(n: Int, mw: Middleware = this.mw) = repeat(n) {
            mw.pump()
            harness.advance(Duration.ofSeconds(10))
            Thread.sleep(30)
        }

        override fun close() = harness.close()
    }

    private fun order(version: Int = 1) = JobOrder(
        jobOrderId = ORDER,
        workMasterId = InspectAsset.WORK_MASTER,
        version = version,
        requiredEvidence = Evidence.E0,
        parameters = mapOf("shift" to "night"),
        equipmentRequirements = listOf("PUMP-ROOM" to "PUMP-01", "SWITCHGEAR" to "PANEL-3").map { (location, target) ->
            EquipmentRequirement(target, EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to location, EquipmentUse.PROP_ITEM to "thermal"))
        },
    )

    private fun taskOf(unitId: String) = "$ORDER#$unitId"
    private fun Middleware.Execution.unit(unitId: String) = units.single { it.unitId == unitId }
    private fun Middleware.sentFor(exec: Middleware.Execution) = responses().filter { it.executionId == exec.executionId }

    // ── 하위가 스스로 낸 취소

    @Test
    fun `하위가 스스로 취소하면 다음 단위를 내지 않고 운영자 보류로 세우고 인시던트를 연다`() {
        // 현장 작업자가 기체 쪽에서 태스크를 취소했다. 사람이 세운 기체에 다음 단위가 나가면 안 된다.
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { w.state(taskOf(INSPECT)) == "RUNNING" }

            w.harness.client().cancel(ROBOT, assertNotNull(exec.handle))
            w.drive { exec.unit(INSPECT).state != UnitState.RUNNING }
            w.rounds(3)

            assertEquals(2, w.tasks().size, "하위가 스스로 취소했는데 다음 단위가 나갔다: ${w.tasks().map { it.taskId }}")
            assertEquals(UnitState.OPERATOR_HOLD, exec.unit(INSPECT).state)
            assertEquals(PhysicalState.OPERATOR_HOLD, exec.physicalState)
            val bundle = assertNotNull(w.mw.incidents().singleOrNull { it.unitId == INSPECT }, "하위의 취소에 인시던트가 안 열렸다: ${w.mw.incidents()}")
            assertTrue(bundle.unresolved, "보류인데 사건이 풀린 것으로 적혔다")
            val last = w.mw.sentFor(exec).last()
            assertEquals(PhysicalState.OPERATOR_HOLD to true, last.physicalState to last.operatorRequired)
        }
    }

    @Test
    fun `미들웨어가 취소를 건 뒤 다시 뜨면 하위의 취소를 요청 없는 취소로 읽어 다음 단위를 내지 않는다`() {
        // 취소 요청 표시는 판단 기록에 없다. 다시 지은 인스턴스는 하위의 취소를 누가 냈는지 모른다 — 모르면 진행하지 않는다.
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { w.state(taskOf(INSPECT)) == "RUNNING" }
            assertTrue(w.mw.cancel(exec.executionId))

            val again = Middleware(w.port, now = { w.harness.clock.now() })
            val rebuilt = assertIs<Middleware.Submission.Accepted>(again.resume(order(), ROBOT, ActiveMission(InspectAsset(), null))).execution
            w.drive(again) { rebuilt.unit(INSPECT).state != UnitState.PENDING && rebuilt.unit(INSPECT).state != UnitState.RUNNING }
            w.rounds(3, again)

            assertEquals(2, w.tasks().size, "다시 뜬 인스턴스가 취소된 단위 뒤의 단위를 냈다: ${w.tasks().map { it.taskId }}")
            assertEquals(UnitState.OPERATOR_HOLD, rebuilt.unit(INSPECT).state)
            assertEquals(PhysicalState.OPERATOR_HOLD, rebuilt.physicalState)
        }
    }

    // ── 풀릴 때도 알린다

    @Test
    fun `운영자가 재작업을 고르면 보류가 걷힌 작업 응답이 곧바로 하나 나가고 다음 라운드가 같은 응답을 또 내지 않는다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { w.state(taskOf(INSPECT)) == "RUNNING" }
            w.harness.client().cancel(ROBOT, assertNotNull(exec.handle))
            w.drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }
            val before = w.mw.sentFor(exec).size

            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, INSPECT, OperatorDecision.REWORK, OPERATOR))
            val sent = w.mw.sentFor(exec).drop(before)
            assertEquals(1, sent.size, "운영자 판단 뒤 작업 응답이 안 나갔다: $sent")
            assertEquals(PhysicalState.RUNNING to false, sent.single().physicalState to sent.single().operatorRequired)

            w.mw.pump()
            assertEquals(before + 1, w.mw.sentFor(exec).size, "바뀐 것 없는 라운드가 작업 응답을 또 냈다")
            w.drive { w.state("${taskOf(INSPECT)}@r1") == "RUNNING" }
        }
    }

    @Test
    fun `결함이 저절로 사라지면 막음이 걷힌 작업 응답이 하나 나가고 같은 결함이 다시 서면 다시 알린다`() {
        World(wrap = { FaultSwitch(it) }).use { w ->
            val switch = w.port as FaultSwitch
            switch.fault = ESTOP
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.rounds(2)
            assertEquals(0, w.tasks().size, "시험의 전제: 결함이 첫 단위를 막는다")
            val held = w.mw.sentFor(exec).last()
            assertEquals(PhysicalState.OPERATOR_HOLD to listOf("HARDWARE_FAULT"), held.physicalState to held.blockedBy)
            val before = w.mw.sentFor(exec).size

            switch.fault = null
            w.mw.pump()
            assertEquals(1, w.tasks().size, "결함이 사라졌는데 단위가 안 나갔다")
            val sent = w.mw.sentFor(exec).drop(before)
            assertEquals(1, sent.size, "막음이 걷혔는데 작업 응답이 안 나갔다: $sent")
            assertEquals(PhysicalState.RUNNING to emptyList(), sent.single().physicalState to sent.single().blockedBy)
            assertEquals(false, sent.single().operatorRequired)

            // 같은 결함이 다음 경계에서 다시 선다 — 다시 막고 다시 알려야 한다.
            switch.fault = ESTOP
            w.drive { exec.blockedBy.isNotEmpty() }
            val again = w.mw.sentFor(exec).last()
            assertEquals(PhysicalState.OPERATOR_HOLD to listOf("HARDWARE_FAULT"), again.physicalState to again.blockedBy, "같은 결함이 다시 섰는데 알리지 않았다")
        }
    }

    @Test
    fun `미확정이 조회로 풀리면 작업 응답이 곧바로 나간다`() {
        World(wrap = { DropNext(it) }).use { w ->
            (w.port as DropNext).drop = 1
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.mw.pump()
            assertEquals(PhysicalState.IN_DOUBT, w.mw.sentFor(exec).last().physicalState, "시험의 전제: 첫 단위가 미확정이다")
            val before = w.mw.sentFor(exec).size

            w.mw.pump()
            assertEquals(UnitState.RUNNING, exec.units.first().state, "시험의 전제: 조회가 미확정을 풀었다")
            val sent = w.mw.sentFor(exec).drop(before)
            assertEquals(1, sent.size, "미확정이 풀렸는데 작업 응답이 안 나갔다: $sent")
            assertEquals(PhysicalState.RUNNING to emptyList(), sent.single().physicalState to sent.single().inDoubtUnits)
        }
    }

    @Test
    fun `개정 갱신의 답을 못 받으면 미확정을 알리고 조회로 풀리면 다시 알린다`() {
        World(wrap = { DropNext(it) }).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            val travel = exec.units.first().unitId
            w.drive { w.state(taskOf(travel)) == "RUNNING" }
            val before = w.mw.sentFor(exec).size

            (w.port as DropNext).drop = 1
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(version = 2), ROBOT))
            assertEquals(UnitState.IN_DOUBT, exec.unit(travel).state, "시험의 전제: 갱신의 답을 잃어 미확정이다")
            val doubted = w.mw.sentFor(exec).drop(before)
            assertEquals(1, doubted.size, "개정 갱신이 미확정을 냈는데 알리지 않았다: $doubted")
            assertEquals(PhysicalState.IN_DOUBT to listOf(travel), doubted.single().physicalState to doubted.single().inDoubtUnits)
            assertEquals(2, doubted.single().version, "새 판의 응답이 아니다")

            w.mw.pump()
            assertEquals(UnitState.RUNNING, exec.unit(travel).state, "시험의 전제: 조회가 미확정을 풀었다")
            val cleared = w.mw.sentFor(exec).drop(before + 1)
            assertEquals(1, cleared.size, "미확정이 풀렸는데 작업 응답이 안 나갔다: $cleared")
            assertEquals(PhysicalState.RUNNING to emptyList(), cleared.single().physicalState to cleared.single().inDoubtUnits)
        }
    }

    // ── 관측 실패 동안의 막음

    @Test
    fun `스냅숏을 한 번 못 읽어도 이미 막던 결함은 계속 막는다`() {
        World(wrap = { FaultSwitch(it) }).use { w ->
            val switch = w.port as FaultSwitch
            switch.fault = ESTOP
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.rounds(2)
            assertTrue(exec.blockedBy.isNotEmpty(), "시험의 전제: 결함이 첫 단위를 막는다")
            val before = w.mw.sentFor(exec).size

            switch.blind = true
            w.rounds(3)
            assertEquals(0, w.tasks().size, "스냅숏을 못 읽은 라운드에 막던 결함을 잊고 단위를 냈다")
            assertEquals(PhysicalState.OPERATOR_HOLD, exec.physicalState)
            assertEquals(before, w.mw.sentFor(exec).size, "막음이 그대로인데 작업 응답이 나갔다")

            switch.blind = false
            switch.fault = null
            w.mw.pump()
            assertEquals(1, w.tasks().size, "다시 읽은 스냅숏에 결함이 없는데 단위가 안 나갔다")
        }
    }

    private companion object {
        const val ROBOT = "quad-01"
        const val ORDER = "PATROL-9"
        const val INSPECT = "PUMP-01"
        val OPERATOR = Approver("op-1", ApproverKind.PERSON)
        val ESTOP: Fault = Fault.newBuilder()
            .setErrorType("INTERNAL_ERROR")
            .setFailureClass(FailureClass.FAILURE_CLASS_HARDWARE_FAULT)
            .setCanAcceptNewTask(false)
            .build()
    }
}
