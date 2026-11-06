package dev.picasso.middleware

import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **보내지 않은 명령은 미확정이 아니다**(§15.237). 포트가 하위로 보내지 않았다고 답하면([CommandNotSent]) 미들웨어는 그 명령을
 * 보내지 않은 것으로 읽는다. 첫 출발은 단위를 `PENDING` 에 두어 다음 라운드에 게이트를 다시 지나 내고, 개정 갱신은 미뤄 다음 라운드에
 * 보내며, 미확정 조회는 조회 횟수를 쓰지 않는다. 그 밖의 예외는 지금처럼 나갔는지 모르는 것이다.
 *
 * 기체는 4족 픽스처 `quadruped-b`, 업무는 점검 순회다. 보내지 않음과 답 잃음은 계약 클라이언트 바로 위의 [Wire] 가 만든다.
 */
class CommandNotSentTest {

    /** [unsent] 만큼 다음 `StartTask` 를 하위로 보내지 않고 던지고, [lost] 만큼 다음 `StartTask` 를 하위에 닿게 한 뒤 답을 잃는다. */
    private class Wire(private val delegate: RobotPort) : RobotPort by delegate {
        var unsent = 0
        var lost = 0

        /** 하위로 나간 `StartTask` 의 (태스크 id, 리비전). */
        val delivered = mutableListOf<Pair<String, Int>>()

        override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse {
            if (unsent > 0) {
                unsent -= 1
                throw CommandNotSent("시험: 하위로 보내지 않았다")
            }
            delivered += taskId to revision
            val response = delegate.start(robotId, taskId, revision, skillType, parameters)
            if (lost > 0) {
                lost -= 1
                throw IllegalStateException("response lost after the request reached the robot")
            }
            return response
        }
    }

    /** [blind] 면 스냅숏을 못 읽는 기체다 — 미확정 태스크가 스냅숏에 안 보여 조회(`StartTask` 재전송)로만 풀린다. */
    private class World(blind: Boolean = false) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to Path.of("..", "profile", "profiles", "quadruped-b.json").normalize()))
        val wire = Wire(ClientRobotPort(harness.client()).let { if (blind) FaultBlindRobotPort(it) else it })
        val mw = Middleware(wire, now = { harness.clock.now() })

        fun tasks() = harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build()).tasksList
        fun state(taskId: String) = tasks().firstOrNull { it.taskId == taskId }?.taskState

        fun submit(version: Int): Middleware.Execution = assertIs<Middleware.Submission.Accepted>(mw.submit(order(version), ROBOT)).execution

        /** 한 라운드 펌프하고 시계를 민다. */
        fun round() {
            mw.pump()
            harness.advance(Duration.ofSeconds(10))
            Thread.sleep(30)
        }

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

        override fun close() = harness.close()
    }

    private fun Middleware.Execution.unit(unitId: String) = units.single { it.unitId == unitId }

    @Test
    fun `포트가 출발을 보내지 않았다고 하면 미확정으로 두지 않고 다음 라운드에 다시 낸다`() {
        World().use { w ->
            w.wire.unsent = 2
            val exec = w.submit(1)
            val first = exec.units.first()
            w.mw.pump()
            w.mw.pump()

            assertEquals(UnitState.PENDING, first.state, "보내지 않은 출발을 미확정으로 읽었다")
            assertEquals(Middleware.START_NOT_SENT, first.note, "보내지 않은 사정을 안 남겼거나 라운드마다 덧붙였다")
            assertNull(exec.active, "보내지 않은 단위가 도는 단위로 남았다")
            assertTrue(exec.physicalState != PhysicalState.IN_DOUBT, "보내지 않은 출발이 실행을 미확정으로 드러냈다")
            assertTrue(w.mw.pending().none { it.inDoubtUnits.isNotEmpty() }, "보내지 않은 출발을 상류에 미확정으로 알렸다")
            assertEquals(emptyList(), w.tasks(), "시험의 전제: 하위에 아무것도 닿지 않았다")

            w.drive { w.state(taskOf(first.unitId)) != null }
            assertEquals(listOf(taskOf(first.unitId) to 1), w.wire.delivered.filter { it.first == taskOf(first.unitId) })
            assertEquals(0, first.lookups, "다시 낸 출발이 미확정 조회로 나갔다")
            assertEquals(emptyList(), w.mw.incidents())
        }
    }

    @Test
    fun `보내지 않은 출발은 판단 기록에 낸 것으로 남지 않는다`() {
        World().use { w ->
            w.wire.unsent = 1
            val exec = w.submit(1)
            val first = exec.units.first()
            w.mw.pump()
            assertEquals(UnitState.PENDING, first.state, "시험의 전제: 첫 출발을 보내지 않았다")

            val record = w.mw.judgmentRecord(exec.executionId)!!.units.single { it.unitId == first.unitId }
            assertEquals("", record.taskId, "보내지 않은 시도의 태스크 id 를 낸 것으로 적었다")
            assertNull(record.requestedAt, "보내지 않은 시각을 요청 시각으로 적었다 — 재기동 뒤 미확정 유예의 기준이 된다")
        }
    }

    @Test
    fun `포트가 개정 갱신을 보내지 않았다고 하면 미확정으로 두지 않고 다음 라운드에 보낸다`() {
        World().use { w ->
            val exec = w.submit(1)
            w.drive { w.state(taskOf(INSPECT)) == "RUNNING" }
            w.wire.unsent = 1
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(2), ROBOT))

            assertEquals(UnitState.RUNNING, exec.unit(INSPECT).state, "보내지 않은 갱신을 미확정으로 읽었다")
            assertEquals(1, exec.deferredUpdate, "보내지 않은 갱신을 미루지 않았다")
            assertTrue(exec.unit(INSPECT).note.orEmpty().contains(Middleware.UPDATE_NOT_SENT), "보내지 않은 갱신의 사정을 안 남겼다: ${exec.unit(INSPECT).note}")
            assertEquals(listOf(taskOf(INSPECT) to 1), w.wire.delivered.filter { it.first == taskOf(INSPECT) }, "시험의 전제: 갱신은 나가지 않았다")

            w.mw.pump()
            assertEquals(listOf(1, 2), w.wire.delivered.filter { it.first == taskOf(INSPECT) }.map { it.second }, "미룬 갱신이 다음 라운드에 나가지 않았다")
            assertNull(exec.deferredUpdate)
            assertEquals(UnitState.RUNNING, exec.unit(INSPECT).state)
        }
    }

    @Test
    fun `미확정 조회가 나가지 않으면 조회 횟수를 쓰지 않고 유예가 지나도 조회를 기다린다`() {
        World(blind = true).use { w ->
            w.wire.lost = 1
            val exec = w.submit(1)
            val first = exec.units.first()
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, first.state, "시험의 전제: 첫 출발의 답을 잃었다")

            w.wire.unsent = 1000
            repeat(8) { w.round() } // 유예 60초를 넘긴다
            assertEquals(UnitState.IN_DOUBT, first.state, "한 번도 못 물은 채 유예가 다 갔다고 넘어갔다: ${first.note}")
            assertEquals(0, first.lookups, "나가지 않은 조회를 조회 횟수로 셌다")

            w.wire.unsent = 0
            w.drive { first.state != UnitState.IN_DOUBT }
            assertEquals(1, first.lookups, first.note)
            assertTrue(first.note!!.contains("resolved by client-reference lookup"), first.note)
            assertEquals(1, w.tasks().count { it.taskId == taskOf(first.unitId) }, "조회가 물리 작업을 다시 만들었다")
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
