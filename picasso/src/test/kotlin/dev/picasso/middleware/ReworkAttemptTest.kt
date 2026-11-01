package dev.picasso.middleware

import dev.picasso.capability.HoldMismatch
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import dev.picasso.mimic.control.v1.ForceFaultRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 재작업이 내는 새 시도는 **앞 시도의 관측을 들고 가지 않는다**(§15.236).
 *
 * 운영자 보류에 선 단위의 관측 칸에는 앞 시도가 남긴 값이 있다. 미믹이 늘 채우지 않는 칸(파지, 정체, 진행률)은 시험이 직접 채워
 * 둔다 — 재작업이 그 값을 비우는가를 보는 것이지, 앞 시도가 그 값을 낸 경로를 보는 것이 아니다.
 */
class ReworkAttemptTest {

    private class World : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to Path.of("..", "profile", "fixtures", "minimal.json").normalize()))
        val cell = CellMimic(now = { harness.clock.now() })
        val mw = Middleware(ClientRobotPort(harness.client()), cell, now = { harness.clock.now() })

        fun tasks() = harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build()).tasksList

        fun drive(rounds: Int = 80, until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(Duration.ofSeconds(5))
                Thread.sleep(30)
                mw.pump()
                if (until()) return
            }
            error("조건에 못 미쳤다: tasks=${tasks().map { it.taskId to it.taskState }}")
        }

        fun forceFault(taskId: String) = harness.oracle.forceFault(
            ForceFaultRequest.newBuilder().setRobotId(ROBOT).setErrorType("SKILL_EXECUTION_FAILED").setTaskId(taskId).build(),
        )

        /** 로봇은 실패라는데 설비에는 있는 단위 하나 — 운영자 보류에 서고 사건이 열린다. */
        fun held(): Middleware.Execution {
            val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(order(), ROBOT)).execution
            drive { tasks().any { it.taskState == "RUNNING" } }
            cell.program(SLOT, PART)
            forceFault(exec.units.single().taskId)
            drive { exec.physicalState == PhysicalState.OPERATOR_HOLD && mw.incidents().isNotEmpty() }
            return exec
        }

        override fun close() = harness.close()
    }

    /** 미믹이 늘 채우지 않는 시도별 칸을 앞 시도가 남긴 것처럼 채운다. */
    private fun ExecutionUnit.markEarlierAttempt() {
        everHeld = true
        reached = Evidence.E1
        holdExpected = HoldKind.HOLD_KIND_HOLDING
        holdMismatch = HoldMismatch.PAYLOAD_LOST
        rechecks = 3
        progress = 0.6
        progressAt = requestedAt
        progressObservable = true
        progressStalled = true
        preconditionSubjects = listOf("$SLOT.occupied")
        result = "vision://earlier"
        evidenceAt = requestedAt
        evidenceDeadline = requestedAt
    }

    @Test
    fun `재작업은 새 시도의 관측 칸을 계획이 처음 지은 값으로 비운다`() {
        World().use { w ->
            val exec = w.held()
            val unit = exec.units.single()
            assertNotNull(unit.fault, "시험의 전제: 앞 시도의 결함이 남아 있다")
            assertNotNull(unit.downstreamDoneAt, "시험의 전제: 앞 시도의 하위 종료 시각이 남아 있다")
            unit.markEarlierAttempt()

            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, SLOT, OperatorDecision.REWORK, OPERATOR))

            assertEquals(1, unit.attempt)
            assertFalse(unit.everHeld, "앞 시도의 파지를 새 시도가 들고 갔다")
            assertNull(unit.holdExpected, "앞 시도의 파지 기대가 남았다")
            assertNull(unit.holdMismatch, "앞 시도의 어긋남이 남았다")
            assertNull(unit.fault, "앞 시도의 결함이 남았다")
            assertEquals(Evidence.E0, unit.reached, "앞 시도의 근거 등급이 남았다")
            assertNull(unit.downstreamDoneAt, "앞 시도의 하위 종료 시각이 남았다")
            assertNull(unit.evidenceDeadline, "앞 시도의 근거 기한이 남았다")
            assertNull(unit.evidenceAt, "앞 시도의 근거 시각이 남았다")
            assertEquals(0, unit.rechecks, "앞 시도의 재확인 횟수가 남았다")
            assertNull(unit.requestedAt, "앞 시도의 요청 시각이 남았다")
            assertEquals(0.0, unit.progress, "앞 시도의 진행률이 남았다")
            assertNull(unit.progressAt, "앞 시도의 진행률 시각이 남았다")
            assertNull(unit.progressObservable, "앞 시도의 진행률 관측 여부가 남았다")
            assertFalse(unit.progressStalled, "앞 시도의 정체 표시가 남았다")
            assertTrue(unit.preconditionSubjects.isEmpty(), "앞 시도의 사전 조건 주어가 남았다")
            assertNull(unit.result, "앞 시도의 결과 참조가 남았다")
        }
    }

    @Test
    fun `재작업한 시도의 사건 번들은 앞 시도의 어긋남과 결함을 싣지 않는다`() {
        World().use { w ->
            val exec = w.held()
            val unit = exec.units.single()
            unit.markEarlierAttempt()
            val first = w.mw.incidents().single()
            assertEquals("GRASP_FAILED", first.failureClass)

            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, SLOT, OperatorDecision.REWORK, OPERATOR))
            w.cell.program(SLOT, null) // 새 시도는 설비도 비어 그냥 실패한다
            w.drive { w.tasks().any { it.taskId == unit.taskId && it.taskState == "RUNNING" } }
            w.forceFault(unit.taskId)
            w.drive { exec.physicalState.isSettled && exec.active == null }

            val second = w.mw.incidents().last()
            assertTrue(second.incidentId != first.incidentId, "새 시도의 실패가 사건을 열지 않았다")
            assertNull(second.effectMismatch, "새 시도의 번들이 앞 시도의 어긋남을 실었다")
            assertTrue(second.preconditionSubjects.isEmpty(), "새 시도의 번들이 앞 시도의 사전 조건 주어를 실었다")
        }
    }

    @Test
    fun `앞 시도에서 쥔 것을 본 단위는 재작업 뒤에도 재할당하지 않는다`() {
        // 새 시도의 파지 기대는 비워도 반쯤 한 물리 작업은 남는다 — 앞 시도가 쥐었다가 어딘가에 둔 자재를 다른 기체가 이어받지 않는다.
        World().use { w ->
            val exec = w.held()
            val unit = exec.units.single()
            unit.everHeld = true

            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, SLOT, OperatorDecision.REWORK, OPERATOR))
            assertFalse(unit.everHeld)
            assertNull(exec.active, "시험의 전제: 재작업 직후 도는 단위가 없다")

            val kept = assertIs<Reassignment.Kept>(w.mw.reassign(exec.executionId, listOf(OTHER)))
            assertTrue("자재를 다룬 단위가 있다" in kept.reason, kept.reason)
            assertEquals(ROBOT, exec.robotId)
        }
    }

    private companion object {
        fun order() = JobOrder(
            jobOrderId = "SEQ-236",
            workMasterId = PrepareSequencedRack.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E2,
            equipmentRequirements = listOf(
                EquipmentRequirement(SLOT, EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to PART)),
                EquipmentRequirement(BIN, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to PART)),
            ),
        )

        val OPERATOR = Approver("op-1", ApproverKind.PERSON)

        const val ROBOT = "hum-02"
        const val OTHER = "hum-03"
        const val SLOT = "RACK-236.S01"
        const val BIN = "SEQ-IN-36.BIN-A"
        const val PART = "ENGINE-COVER-A"
    }
}
