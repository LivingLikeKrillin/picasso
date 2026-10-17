package dev.picasso.middleware

import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
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
 * **다시 지은 실행에 붙기 전에 건 취소**(§15.231). [Middleware.resume] 은 실행을 짓되 도는 단위에 붙지 않는다 — 붙는 것은 첫 pump 다.
 * 그 사이에 [Middleware.cancel] 이 오면 기체가 하는 태스크에 취소를 보내고 그 종료를 본 뒤 정착해야 한다. 붙지 않은 채 정착하면 기체가
 * 그 태스크를 계속 하는 동안 실행이 기체를 내준다.
 *
 * 나간 명령은 기체 쪽(미믹의 태스크 표)과 다시 뜬 인스턴스의 포트([Wire])에서 센다. 다시 뜬 인스턴스는 새 포트를 쓴다 — 앞 인스턴스의
 * 포트가 들던 갱신 목록을 물려받지 않는다.
 *
 * 기체는 4족 픽스처 `quadruped-b`, 업무는 점검 순회(지점마다 이동 뒤 점검)다. `inspect` 는 취소를 받는다.
 */
class ResumeCancelTest {

    /** 나간 `StartTask`·`CancelTask` 를 세고 스냅숏을 못 읽게 할 수 있는 포트. */
    private class Wire(private val delegate: RobotPort) : RobotPort by delegate {
        val starts = mutableListOf<String>()
        val cancels = mutableListOf<String>()
        var blind = false

        override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse {
            starts += taskId
            return delegate.start(robotId, taskId, revision, skillType, parameters)
        }

        override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse {
            cancels += handle.taskId
            return delegate.cancel(robotId, handle)
        }

        override fun snapshot(robotId: String): RobotSnapshot? = if (blind) null else delegate.snapshot(robotId)

        override fun replay(robotId: String, from: Long): Replay? = if (blind) null else delegate.replay(robotId, from)
    }

    private class World : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to Path.of("..", "profile", "profiles", "quadruped-b.json").normalize()))
        val mw = Middleware(ClientRobotPort(harness.client()), now = { harness.clock.now() })

        fun tasks() = harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build()).tasksList
        fun state(taskId: String) = tasks().firstOrNull { it.taskId == taskId }?.taskState

        /** 다시 뜬 인스턴스 — 새 포트, 같은 기체. */
        fun restart(permits: WorkPermitPort? = null): Pair<Middleware, Wire> {
            val wire = Wire(ClientRobotPort(harness.client()))
            return Middleware(wire, now = { harness.clock.now() }, permits = permits) to wire
        }

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

        /** 점검 단위가 기체에서 돌 때까지 몰고 그때의 판단 기록을 받아 둔다 — 담는 쪽이 재기동 직전에 들고 있던 것. */
        fun runUntilInspecting(): JudgmentRecord {
            val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(order(), ROBOT)).execution
            drive { state(taskOf(INSPECT)) == "RUNNING" }
            return assertNotNull(mw.judgmentRecord(exec.executionId))
        }

        override fun close() = harness.close()
    }

    private fun Middleware.rebuild(record: JudgmentRecord): Middleware.Execution =
        assertIs<Middleware.Submission.Accepted>(resume(order(), ROBOT, ActiveMission(InspectAsset(), null), record)).execution

    private fun Middleware.Execution.unit(unitId: String) = units.single { it.unitId == unitId }

    // ── 기체가 아직 하는 태스크

    @Test
    fun `다시 지은 실행에 곧바로 취소를 걸면 첫 pump 가 정착하지 않고 기체의 태스크를 취소한 뒤 중단으로 정착한다`() {
        World().use { w ->
            val record = w.runUntilInspecting()
            val (again, wire) = w.restart()
            val rebuilt = again.rebuild(record)
            assertTrue(again.cancel(rebuilt.executionId))

            again.pump()
            assertEquals(
                PhysicalState.CANCELING, rebuilt.physicalState,
                "붙지 않은 채 정착했다 — 기체의 태스크는 ${w.state(taskOf(INSPECT))}",
            )
            assertEquals(listOf(taskOf(INSPECT)), wire.cancels, "기체가 하는 태스크에 취소가 안 나갔다")
            assertEquals(emptyList(), wire.starts, "취소하려고 붙으면서 StartTask 를 보냈다")

            w.drive(again) { !rebuilt.physicalState.pumps || rebuilt.unit(INSPECT).state != UnitState.RUNNING }
            assertEquals(emptyList(), again.incidents(), "요청한 취소를 요청 없는 취소로 읽었다")
            assertTrue(again.responses().none { it.physicalState == PhysicalState.OPERATOR_HOLD }, "요청한 취소에 운영자 보류가 나갔다: ${again.responses()}")
            assertEquals(PhysicalState.ABORTED, rebuilt.physicalState)
            val report = assertNotNull(again.lastCancel(rebuilt.executionId))
            assertEquals(INSPECT to "done", report.inProgressUnit to report.cleanup, "중단점이 기체에서 취소된 단위가 아니다: $report")
            assertEquals(UnitState.ABORTED, rebuilt.unit(INSPECT).state)
            assertEquals("CANCELLED", w.state(taskOf(INSPECT)), "기체의 태스크가 취소로 안 끝났다")
            assertEquals(2, w.tasks().size, "취소 뒤에 새 단위가 나갔다: ${w.tasks().map { it.taskId }}")
        }
    }

    @Test
    fun `작업 허가가 막아도 다시 지은 실행의 취소는 기체에 나간다`() {
        World().use { w ->
            val record = w.runUntilInspecting()
            // 원천이 이 기체를 덮지 않는다 — 모름(UNKNOWN)이고 발행 관문이 막는다.
            val (again, wire) = w.restart(permits = WorkPermitPort { emptyList() })
            val rebuilt = again.rebuild(record)
            again.pump()
            assertNotNull(rebuilt.permitBlock, "시험의 전제: 관문이 다시 붙는 StartTask 를 막는다")
            assertEquals(emptyList(), wire.starts, "시험의 전제: 막힌 동안 StartTask 가 안 나간다")

            assertTrue(again.cancel(rebuilt.executionId))
            again.pump()
            assertEquals(listOf(taskOf(INSPECT)), wire.cancels, "허가가 취소를 막았다")
            w.drive(again) { !rebuilt.physicalState.pumps }
            assertEquals(PhysicalState.ABORTED, rebuilt.physicalState)
            assertEquals("CANCELLED", w.state(taskOf(INSPECT)))
        }
    }

    // ── 이미 끝난 태스크

    @Test
    fun `기체의 태스크가 이미 취소로 끝났으면 새 태스크를 내지 않고 그 종료로 단위를 판정한다`() {
        World().use { w ->
            val record = w.runUntilInspecting()
            // 앞 인스턴스가 취소를 낸 뒤 죽었다. 기체는 그 사이 취소를 마쳤다.
            w.harness.client().cancel(ROBOT, TaskHandle.newBuilder().setTaskId(taskOf(INSPECT)).setRevision(1).setRobotId(ROBOT).build())
            repeat(60) {
                if (w.state(taskOf(INSPECT)) == "CANCELLED") return@repeat
                w.harness.advance(Duration.ofSeconds(10))
                Thread.sleep(30)
            }
            assertEquals("CANCELLED", w.state(taskOf(INSPECT)), "시험의 전제: 기체의 태스크가 취소로 끝났다")

            val (again, wire) = w.restart()
            val rebuilt = again.rebuild(record)
            assertTrue(again.cancel(rebuilt.executionId))
            w.drive(again) { !rebuilt.physicalState.pumps }

            assertEquals(emptyList(), wire.starts, "끝난 태스크에 붙으려고 StartTask 를 보냈다 — 하위가 끝난 태스크를 안 들면 새 태스크가 출발한다")
            assertEquals(emptyList(), wire.cancels, "끝난 태스크에 취소를 보냈다")
            assertEquals(2, w.tasks().size, "새 태스크가 출발했다: ${w.tasks().map { it.taskId }}")
            assertEquals(PhysicalState.ABORTED, rebuilt.physicalState)
            assertEquals(UnitState.ABORTED, rebuilt.unit(INSPECT).state, "기체의 취소 종료로 단위를 판정하지 않았다")
            assertEquals(INSPECT, assertNotNull(again.lastCancel(rebuilt.executionId)).inProgressUnit)
            assertEquals(emptyList(), again.incidents(), "요청한 취소를 요청 없는 취소로 읽었다")
        }
    }

    // ── 못 읽은 스냅숏

    @Test
    fun `스냅숏을 못 읽으면 정착하지 않고 기다렸다가 다시 읽히면 취소를 보낸다`() {
        World().use { w ->
            val record = w.runUntilInspecting()
            val (again, wire) = w.restart()
            val rebuilt = again.rebuild(record)
            wire.blind = true
            assertTrue(again.cancel(rebuilt.executionId))

            // 시계를 밀지 않는다 — 기체가 그 사이 태스크를 끝내면 붙을 것이 «이미 끝난 태스크» 로 바뀐다.
            repeat(3) { again.pump() }
            assertEquals(PhysicalState.CANCELING, rebuilt.physicalState, "기체를 못 본 채 정착했다")
            assertEquals("RUNNING", w.state(taskOf(INSPECT)), "시험의 전제: 기체는 그 태스크를 계속 한다")
            assertEquals(emptyList(), wire.starts + wire.cancels, "못 본 채 명령을 냈다")
            assertEquals(1, rebuilt.eventTrail.count { it.kind == Middleware.CANCEL_UNATTACHED_WAIT }, "기다리는 까닭을 한 번 남기지 않았다: ${rebuilt.eventTrail}")

            wire.blind = false
            again.pump()
            assertEquals(listOf(taskOf(INSPECT)), wire.cancels, "다시 읽힌 스냅숏으로 취소를 안 보냈다")
            w.drive(again) { !rebuilt.physicalState.pumps }
            assertEquals(PhysicalState.ABORTED, rebuilt.physicalState)
            assertEquals("CANCELLED", w.state(taskOf(INSPECT)))
        }
    }

    private companion object {
        const val ROBOT = "quad-01"
        const val ORDER = "PATROL-9"
        const val INSPECT = "PUMP-01"

        fun taskOf(unitId: String) = "$ORDER#$unitId"

        fun order() = JobOrder(
            jobOrderId = ORDER,
            workMasterId = InspectAsset.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E0,
            parameters = mapOf("shift" to "night"),
            equipmentRequirements = listOf("PUMP-ROOM" to "PUMP-01", "SWITCHGEAR" to "PANEL-3").map { (location, target) ->
                EquipmentRequirement(target, EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to location, EquipmentUse.PROP_ITEM to "thermal"))
            },
        )
    }
}
