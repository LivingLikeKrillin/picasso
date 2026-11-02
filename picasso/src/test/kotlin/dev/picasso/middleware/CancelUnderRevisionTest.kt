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
 * **개정과 취소가 만나는 자리**(§15.255·§15.256). 개정은 도는 로봇 단위에 계약의 갱신을 보낸다. 취소와는 두 순서로 만난다.
 *
 * - **취소가 먼저 걸렸으면** 도는 단위와 미확정 단위는 개정이 바꾸지 않는다 — 갱신을 보내지 않고 리비전과 기대도 그대로 둔다.
 *   멈추라는 말 뒤에 새 버전을 보내면 기체가 그 버전으로 다시 움직일 수 있고, 원 요청이 안 닿은 미확정 단위면 갱신이 새 태스크로
 *   접수된다.
 * - **개정이 먼저 왔으면** 기체에서 도는 것은 옛 리비전이고, 그 태스크의 취소 종료는 옛 리비전의 지연 이벤트로 온다
 *   ([Middleware.settleLate]). 건 취소의 종료면 요청된 취소로 종결하고, 아니면 요청 없는 취소라 운영자 보류에 둔다.
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

        /** 작업 허가 — 닫으면 새 발행(갱신 포함)이 미뤄진다. 취소는 막지 않는다. */
        var permitted = true
        val mw = Middleware(wire, now = { harness.clock.now() }, permits = WorkPermitPort {
            if (!permitted) {
                emptyList()
            } else {
                listOf(
                    PermitReading(
                        "cell-door", "s-1", setOf(ROBOT),
                        listOf(PermitScope(setOf(ROBOT), WorkPermit.PERMITTED, null, null, harness.clock.now(), Duration.ofDays(1), "s-1")),
                    ),
                )
            }
        })

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

        /** 같은 실행에 새 버전을 낸다. */
        fun revise() {
            assertIs<Middleware.Submission.Accepted>(mw.submit(order(2), ROBOT))
        }

        override fun close() = harness.close()
    }

    private fun Middleware.Execution.unit(unitId: String) = units.single { it.unitId == unitId }

    // ── 취소가 먼저 걸린 개정

    @Test
    fun `건 취소가 걸린 채 개정이 오면 도는 단위에 갱신을 보내지 않고 그 리비전 그대로 중단으로 종결한다`() {
        World().use { w ->
            val exec = w.runUntilInspecting()
            assertTrue(w.mw.cancel(exec.executionId))
            w.revise()

            assertEquals(1, w.wire.starts.count { it == taskOf(INSPECT) }, "취소한 태스크에 갱신을 보냈다 — 기체가 새 버전으로 다시 움직일 수 있다")
            assertEquals(1, exec.unit(INSPECT).revision, "보내지 않은 갱신의 리비전으로 단위를 올렸다")
            assertEquals(2, exec.version, "시험의 전제: 개정은 받았다")

            w.drive { !exec.physicalState.pumps }

            assertEquals("CANCELLED", w.state(taskOf(INSPECT)))
            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertEquals(UnitState.ABORTED, exec.unit(INSPECT).state)
            assertEquals(emptyList(), exec.lateEvents, "보내지 않은 갱신이 지연 이벤트를 만들었다")
            assertEquals(INSPECT, assertNotNull(w.mw.lastCancel(exec.executionId)).inProgressUnit)
            assertEquals(emptyList(), w.mw.incidents())
        }
    }

    @Test
    fun `핸들 없이 취소가 걸린 미확정 단위에 개정이 오면 갱신을 보내지 않고 같은 리비전의 태스크에 붙어 중단으로 종결한다`() {
        World(dropStartOf = taskOf(INSPECT)).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(1), ROBOT)).execution
            w.drive { exec.unit(INSPECT).state == UnitState.IN_DOUBT }
            assertTrue(w.mw.cancel(exec.executionId))
            assertEquals(emptyList(), w.wire.cancels, "시험의 전제: 핸들이 없어 취소가 안 나갔다")

            w.revise()
            assertEquals(1, w.wire.starts.count { it == taskOf(INSPECT) }, "취소가 걸린 미확정 단위에 갱신을 보냈다 — 원 요청이 안 닿았으면 새 태스크가 나간다")
            assertEquals(1, exec.unit(INSPECT).revision, "기체에서 도는 리비전과 단위의 리비전이 어긋났다")

            w.drive { !exec.physicalState.pumps }

            assertEquals(listOf(taskOf(INSPECT)), w.wire.cancels, "같은 리비전의 태스크에 붙어 취소를 보내지 않았다")
            assertEquals("CANCELLED", w.state(taskOf(INSPECT)), "기체가 멈추지 않았다")
            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertEquals(UnitState.ABORTED, exec.unit(INSPECT).state)
            assertEquals(emptyList(), w.mw.incidents())
        }
    }

    // ── 개정이 먼저 온 뒤의 취소 종료(지연 이벤트)

    @Test
    fun `미룬 갱신 뒤 건 취소가 옛 리비전의 태스크를 끝냈으면 그 지연 이벤트를 요청된 취소로 읽어 중단으로 종결한다`() {
        World().use { w ->
            val exec = w.runUntilInspecting()
            // 허가가 닫혀 갱신이 미뤄진다 — 단위는 새 리비전이고 기체에서는 옛 리비전이 돈다.
            w.permitted = false
            w.revise()
            assertEquals(2, exec.unit(INSPECT).revision)
            assertEquals(1, w.wire.starts.count { it == taskOf(INSPECT) }, "시험의 전제: 갱신이 미뤄졌다")
            assertTrue(w.mw.cancel(exec.executionId))
            w.cancelDownstreamWithoutReading(taskOf(INSPECT))

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
            assertTrue(exec.unit(INSPECT).note!!.contains("already terminal under revision 1"), "시험의 전제: 갱신이 끝난 태스크에 닿았다 — ${exec.unit(INSPECT).note}")

            w.drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }

            assertEquals(UnitState.OPERATOR_HOLD, exec.unit(INSPECT).state, "요청 없는 취소의 지연 종료를 중단으로 접었다")
            assertEquals(listOf("TASK_STATE_CANCELLED"), exec.lateEvents.map { it.downstreamState })
            assertEquals(1, w.mw.incidents().size, "보류에 인시던트가 안 열렸다")
            assertEquals(emptyList(), w.wire.cancels, "걸지 않은 취소를 보냈다")
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
