package dev.picasso.middleware

import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 버전 변경 중 지연 이벤트 — 보고서 15.1, 17장 6번. 계획 단계 6.
 *
 * *"v17 완료 이벤트가 v18 시작 후 도착 → v18 에 반영되지 않고 지연 이벤트로 보존."* 계약 쪽에서는 이벤트가 `revision` 을
 * 싣고 있어 소비자가 가를 수 있다고만 적혀 있었다(`scenarios.md` §4.5, §7 후보 ④). 그 규칙이 사는 자리는 계약이 아니라
 * **실행 층**이고, 여기가 그것이다.
 *
 * 만드는 법: 슬롯 하나를 v17 로 돌려 미믹에서 종착시키되 미들웨어는 아직 안 읽는다. 그 사이 v18 이 온다 — 미들웨어는 그
 * 슬롯을 도는 중으로 알고 계약의 갱신을 보내는데 이미 종착한 태스크라 거절된다(`INVALID_TRANSITION`). 그 뒤 읽으면 v17 의
 * 완료가 v18 아래로 도착한다.
 */
class LateEventTest {

    private fun order(version: Int, material: String) = JobOrder(
        jobOrderId = "SEQ-207",
        workMasterId = PrepareSequencedRack.WORK_MASTER,
        version = version,
        requiredEvidence = Evidence.E2,
        equipmentRequirements = listOf(
            EquipmentRequirement(SLOT, EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to material)),
            EquipmentRequirement("SEQ-IN-02.BIN-A", EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to A)),
            EquipmentRequirement("SEQ-IN-02.BIN-B", EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to B)),
        ),
    )

    private class World : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to Path.of("..", "profile", "fixtures", "minimal.json").normalize()))
        val cell = CellMimic(now = { harness.clock.now() })
        val mw = Middleware(ClientRobotPort(harness.client()), cell, now = { harness.clock.now() })

        fun tasks() = harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build()).tasksList

        /** 미믹만 민다 — 미들웨어는 읽지 않는다. 그 사이가 지연 이벤트가 생기는 자리다. */
        fun settleDownstreamWithoutReading() {
            repeat(30) {
                if (tasks().any { it.taskState == "SUCCEEDED" }) return
                harness.advance(Duration.ofSeconds(30))
                Thread.sleep(30)
            }
            error("미믹이 종착하지 않았다: ${tasks().map { it.taskId to it.taskState }}")
        }

        fun drive(rounds: Int = 40, until: () -> Boolean) {
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

    private fun Middleware.Execution.unit() = units.single()
    private fun Middleware.Execution.settled() = physicalState.isSettled && active == null

    /** v17 을 미믹에서 끝내 놓고 v18 을 낸다. 돌아오는 것은 v18 아래의 실행이다. */
    private fun World.lateCompletion(v18Material: String): Middleware.Execution {
        val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(order(V17, A), ROBOT)).execution
        mw.pump() // 시작만 — 태스크가 생겼다
        assertEquals(UnitState.RUNNING, exec.unit().state)
        settleDownstreamWithoutReading()
        cell.program(SLOT, A) // 로봇이 v17 대로 A 를 놓았다 — 설비는 그것을 본다

        assertIs<Middleware.Submission.Accepted>(mw.submit(order(V18, v18Material), ROBOT))
        assertEquals(V18, exec.unit().revision)
        assertEquals(UnitState.RUNNING, exec.unit().state, "갱신 거절을 실패로 적었다 — 물리 결과를 잃는다")
        assertTrue(exec.unit().note!!.contains("already terminal under revision 17"), exec.unit().note)
        return exec
    }

    @Test
    fun `v17 의 완료는 v18 을 닫지 않는다 — 지연 이벤트로 보존되고, 설비가 v18 의 기대에 대고 본다`() {
        World().use { w ->
            val exec = w.lateCompletion(v18Material = B) // v18 은 그 슬롯에 B 를 원한다
            w.drive { exec.settled() }

            assertEquals(listOf(LateEvent(SLOT, V17, V18, "TASK_STATE_SUCCEEDED", exec.lateEvents.single().occurredAt)), exec.lateEvents)
            assertEquals(UnitState.FAILED, exec.unit().state, "v17 의 완료가 v18 의 완료로 적혔다")
            assertEquals(Verification.MISMATCH, exec.unit().verification)
            assertEquals(Middleware.MISMATCH, exec.unit().failureClass)
            assertTrue(exec.unit().note!!.contains("late event: revision 17 TASK_STATE_SUCCEEDED arrived under revision 18"), exec.unit().note)
            assertEquals(1, w.tasks().size, "지연 이벤트가 재실행을 만들었다")
            val response = w.mw.pending().last()
            assertEquals(mapOf(SLOT to Middleware.MISMATCH), response.incompleteUnits)
            assertTrue(response.operatorRequired)
        }
    }

    @Test
    fun `v18 이 같은 것을 원하면 설비가 그것을 확인하고 E2 로 닫는다 — 그래도 지연 이벤트는 남는다`() {
        World().use { w ->
            val exec = w.lateCompletion(v18Material = A)
            w.drive { exec.settled() }

            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(Evidence.E2, exec.unit().reached)
            assertEquals(1, exec.lateEvents.size)
            assertEquals(V17, exec.lateEvents.single().revision)
        }
    }

    @Test
    fun `요구 등급이 E0 라도 옛 버전의 보고만으로는 못 닫는다 — 설비가 말이 없으면 UNVERIFIED`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(V17, A).copy(requiredEvidence = Evidence.E0), ROBOT)).execution
            w.mw.pump()
            w.settleDownstreamWithoutReading()
            w.cell.silence(SLOT)
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(V18, A).copy(requiredEvidence = Evidence.E0), ROBOT))
            w.drive { exec.settled() }

            assertEquals(UnitState.UNVERIFIED, exec.unit().state)
            assertEquals(PhysicalState.UNVERIFIED, exec.physicalState)
            assertEquals(1, exec.lateEvents.size)
        }
    }

    private companion object {
        const val ROBOT = "hum-02"
        const val SLOT = "RACK-207.S01"
        const val A = "ENGINE-COVER-A"
        const val B = "ENGINE-COVER-B"
        const val V17 = 17
        const val V18 = 18
    }
}
