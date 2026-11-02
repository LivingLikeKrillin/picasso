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
 * **개정이 걸린 취소를 지나가는 자리**(§15.255). 취소가 걸린 실행에도 새 버전은 붙는다 — 도는 로봇 단위에는 계약의 갱신이 나가고, 그
 * 응답이 두 갈래로 취소와 만난다.
 *
 * - 갱신이 닿기 전에 태스크가 취소로 끝났으면 그 종료는 옛 리비전의 **지연 이벤트**로 온다([Middleware.settleLate]). 건 취소의 종료면
 *   요청된 취소로 종결하고, 아니면 요청 없는 취소라 운영자 보류에 둔다.
 * - 답을 못 받은(미확정) 단위에 핸들 없이 취소가 걸렸으면 갱신이 핸들을 돌려줄 때 비로소 붙는다. 그때 걸린 취소를 보낸다 — 안 보내면
 *   기체는 그 태스크를 끝까지 한다.
 *
 * 기체는 4족 픽스처 `quadruped-b`, 업무는 점검 순회다. `inspect` 는 취소를 받는다. 나간 명령은 포트([Wire])에서 센다.
 */
class CancelUnderRevisionTest {

    /** 나간 `StartTask`·`CancelTask` 를 세고, [dropStartOf] 의 첫 `StartTask` 응답을 하위에 닿은 뒤 잃는 포트. */
    private class Wire(private val delegate: RobotPort, private var dropStartOf: String? = null) : RobotPort by delegate {
        val starts = mutableListOf<String>()
        val cancels = mutableListOf<String>()

        override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse {
            starts += taskId
            val response = delegate.start(robotId, taskId, revision, skillType, parameters)
            if (taskId == dropStartOf) {
                dropStartOf = null
                throw IllegalStateException("response lost after the request reached the robot")
            }
            return response
        }

        override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse {
            cancels += handle.taskId
            return delegate.cancel(robotId, handle)
        }
    }

    private class World(dropStartOf: String? = null) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to Path.of("..", "profile", "profiles", "quadruped-b.json").normalize()))
        val wire = Wire(ClientRobotPort(harness.client()), dropStartOf)
        val mw = Middleware(wire, now = { harness.clock.now() })

        fun tasks() = harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build()).tasksList
        fun state(taskId: String) = tasks().firstOrNull { it.taskId == taskId }?.taskState

        fun drive(rounds: Int = 60, until: () -> Boolean) {
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

        /** 미믹만 민다 — 미들웨어는 읽지 않는다. 그 사이가 지연 이벤트가 생기는 자리다. */
        fun cancelDownstreamWithoutReading(taskId: String) {
            repeat(30) {
                if (state(taskId) == "CANCELLED") return
                harness.advance(Duration.ofSeconds(10))
                Thread.sleep(30)
            }
            error("기체가 취소로 끝내지 않았다: ${tasks().map { it.taskId to it.taskState }}")
        }

        /** 점검 단위가 기체에서 돌 때까지 몬다. */
        fun runUntilInspecting(): Middleware.Execution {
            val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(order(1), ROBOT)).execution
            drive { state(taskOf(INSPECT)) == "RUNNING" }
            return exec
        }

        /** 같은 실행에 새 버전을 낸다 — 점검 단위는 도는 중이라 계약의 갱신을 탄다. */
        fun revise() {
            assertIs<Middleware.Submission.Accepted>(mw.submit(order(2), ROBOT))
        }

        override fun close() = harness.close()
    }

    private fun Middleware.Execution.unit(unitId: String) = units.single { it.unitId == unitId }

    // ── 지연 이벤트로 오는 취소 종료

    @Test
    fun `건 취소가 갱신보다 먼저 태스크를 끝냈으면 그 지연 이벤트를 요청된 취소로 읽어 중단으로 종결한다`() {
        World().use { w ->
            val exec = w.runUntilInspecting()
            assertTrue(w.mw.cancel(exec.executionId))
            w.cancelDownstreamWithoutReading(taskOf(INSPECT))
            w.revise()
            assertEquals(2, exec.unit(INSPECT).revision)
            assertTrue(exec.unit(INSPECT).note!!.contains("already terminal under revision 1"), "시험의 전제: 갱신이 끝난 태스크에 닿았다 — ${exec.unit(INSPECT).note}")

            w.drive { !exec.physicalState.pumps }

            assertEquals(PhysicalState.ABORTED, exec.physicalState, "요청한 취소의 지연 종료로 종결하지 않았다")
            assertEquals(UnitState.ABORTED, exec.unit(INSPECT).state)
            assertEquals(listOf("TASK_STATE_CANCELLED"), exec.lateEvents.map { it.downstreamState }, "취소 종료를 지연 이벤트로 남기지 않았다")
            assertEquals(INSPECT, assertNotNull(w.mw.lastCancel(exec.executionId)).inProgressUnit)
            assertEquals(emptyList(), w.mw.incidents(), "요청한 취소를 요청 없는 취소로 읽었다")
        }
    }

    @Test
    fun `요청하지 않은 취소 종료가 갱신 뒤 지연 이벤트로 오면 운영자 보류에 두고 인시던트를 연다`() {
        World().use { w ->
            val exec = w.runUntilInspecting()
            // 이 계층 밖에서 기체의 태스크가 취소됐다 — 미들웨어는 그것을 아직 안 읽었다.
            w.harness.client().cancel(ROBOT, TaskHandle.newBuilder().setTaskId(taskOf(INSPECT)).setRevision(1).setRobotId(ROBOT).build())
            w.cancelDownstreamWithoutReading(taskOf(INSPECT))
            w.revise()

            w.drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }

            assertEquals(UnitState.OPERATOR_HOLD, exec.unit(INSPECT).state, "요청 없는 취소의 지연 종료를 중단으로 접었다")
            assertEquals(listOf("TASK_STATE_CANCELLED"), exec.lateEvents.map { it.downstreamState })
            assertEquals(1, w.mw.incidents().size, "보류에 인시던트가 안 열렸다")
            assertEquals(emptyList(), w.wire.cancels, "걸지 않은 취소를 보냈다")
        }
    }

    // ── 갱신이 붙인 미확정 단위

    @Test
    fun `핸들 없이 취소가 걸린 미확정 단위가 갱신으로 붙으면 그 핸들로 취소를 보내고 중단으로 종결한다`() {
        World(dropStartOf = taskOf(INSPECT)).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(1), ROBOT)).execution
            w.drive { exec.unit(INSPECT).state == UnitState.IN_DOUBT }
            assertTrue(w.mw.cancel(exec.executionId))
            assertEquals(emptyList(), w.wire.cancels, "시험의 전제: 핸들이 없어 취소가 안 나갔다")

            w.revise()
            assertEquals(UnitState.RUNNING, exec.unit(INSPECT).state, "시험의 전제: 갱신이 핸들을 돌려줘 붙었다")
            assertEquals(PhysicalState.CANCELING, exec.physicalState)
            assertEquals(listOf(taskOf(INSPECT)), w.wire.cancels, "붙은 핸들로 걸린 취소를 안 보냈다 — 기체는 그 태스크를 끝까지 한다")

            w.drive { !exec.physicalState.pumps }

            assertEquals("CANCELLED", w.state(taskOf(INSPECT)), "기체가 멈추지 않았다")
            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertEquals(UnitState.ABORTED, exec.unit(INSPECT).state)
            assertEquals(INSPECT, assertNotNull(w.mw.lastCancel(exec.executionId)).inProgressUnit)
            assertEquals(emptyList(), w.mw.incidents())
        }
    }

    private companion object {
        const val ROBOT = "quad-01"
        const val ORDER = "PATROL-9"
        const val INSPECT = "PUMP-01"

        fun taskOf(unitId: String) = "$ORDER#$unitId"

        fun order(version: Int) = JobOrder(
            jobOrderId = ORDER,
            workMasterId = InspectAsset.WORK_MASTER,
            version = version,
            requiredEvidence = Evidence.E0,
            parameters = mapOf("shift" to "night"),
            equipmentRequirements = listOf("PUMP-ROOM" to "PUMP-01", "SWITCHGEAR" to "PANEL-3").map { (location, target) ->
                EquipmentRequirement(target, EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to location, EquipmentUse.PROP_ITEM to "thermal"))
            },
        )
    }
}
