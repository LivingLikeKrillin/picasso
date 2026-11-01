package dev.picasso.middleware

import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import dev.picasso.mimic.control.v1.ForceFaultRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 재작업한 단위는 **다음 라운드 전에 리비전이 와도 새 시도로 남는다.**
 *
 * 리비전은 `PENDING` 단위를 새 계획의 단위로 바꾼다. 새 계획의 단위는 시도 0 이라, 그대로 바꾸면 재작업이 올린 시도가 사라지고
 * 다음 출발이 첫 시도의 태스크 id 를 다시 쓴다.
 */
class ReworkRevisionTest {

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

        /** 로봇은 실패라는데 설비에는 있는 단위 하나 — 운영자 보류에 선다. */
        fun held(): Middleware.Execution {
            val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(order(1), ROBOT)).execution
            drive { tasks().any { it.taskState == "RUNNING" } }
            cell.program(SLOT, PART)
            harness.oracle.forceFault(
                ForceFaultRequest.newBuilder().setRobotId(ROBOT).setErrorType("SKILL_EXECUTION_FAILED")
                    .setTaskId(exec.units.single().taskId).build(),
            )
            drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }
            return exec
        }

        override fun close() = harness.close()
    }

    @Test
    fun `재작업 직후 온 리비전도 새 시도로 낸다`() {
        World().use { w ->
            val exec = w.held()
            val firstTask = exec.units.single().taskId
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, SLOT, OperatorDecision.REWORK, OPERATOR))

            w.mw.submit(order(2), ROBOT)
            val unit = exec.units.single()
            assertEquals(1, unit.attempt, "리비전이 재작업의 시도를 지웠다")
            assertEquals(2, unit.revision)

            w.drive { w.tasks().size == 2 }
            assertTrue(unit.taskId != firstTask && unit.taskId.endsWith("@r1"), unit.taskId)
            assertEquals(listOf(firstTask, unit.taskId), w.tasks().map { it.taskId }, "새 시도가 새 태스크로 나가지 않았다")
        }
    }

    @Test
    fun `재작업 직후 온 리비전 뒤에도 앞 시도에서 쥔 단위는 재할당하지 않는다`() {
        World().use { w ->
            val exec = w.held()
            exec.units.single().everHeld = true
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, SLOT, OperatorDecision.REWORK, OPERATOR))

            w.mw.submit(order(2), ROBOT)
            assertEquals(2, exec.units.single().revision, "시험의 전제: 리비전이 단위를 새 계획으로 바꿨다")

            val kept = assertIs<Reassignment.Kept>(w.mw.reassign(exec.executionId, listOf(OTHER)))
            assertTrue("자재를 다룬 단위가 있다" in kept.reason, kept.reason)
        }
    }

    private companion object {
        fun order(version: Int) = JobOrder(
            jobOrderId = "SEQ-247",
            workMasterId = PrepareSequencedRack.WORK_MASTER,
            version = version,
            requiredEvidence = Evidence.E2,
            equipmentRequirements = listOf(
                EquipmentRequirement(SLOT, EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to PART)),
                EquipmentRequirement(BIN, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to PART)),
            ),
        )

        val OPERATOR = Approver("op-1", ApproverKind.PERSON)

        const val ROBOT = "hum-02"
        const val OTHER = "hum-03"
        const val SLOT = "RACK-247.S01"
        const val BIN = "SEQ-IN-47.BIN-A"
        const val PART = "ENGINE-COVER-A"
    }
}
