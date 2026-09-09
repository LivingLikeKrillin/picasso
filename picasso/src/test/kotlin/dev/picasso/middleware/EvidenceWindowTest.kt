package dev.picasso.middleware

import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import dev.picasso.mimic.control.v1.ForceFaultRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 근거 결합의 시간창 δ 와 불일치 표(보고서 12장) — 계획 단계 3.
 *
 * `physically_done := robot_report(FINISHED, t_r) ∧ plc_signal(present, tag, t_p) ∧ t_p ∈ [t_r − δ_before, t_r + δ_after]`
 *
 * 12.3 의 네 행 중 이 층이 지금 답하는 셋을 단언한다 — 로봇 완료·설비 없음(`UNVERIFIED`),
 * 로봇 실패·설비 있음(운영자 보류), 태그 불일치(`FAILED` + 위치 기록). 무응답·설비 있음은
 * `IN_DOUBT`(단계 6)의 것이다. 그리고 12.2 가 왜 PLC 쪽 래치 비트를 요구하라고 했는지 —
 * 폴링이 짧은 펄스를 놓치는 것 — 를 그대로 재현한다.
 *
 * 시퀀싱 셀 하나(슬롯 하나)로 돌린다. 슬롯이 하나인 것은 시간창을 보기 위해서이지 시나리오가
 * 바뀐 것이 아니다.
 */
class EvidenceWindowTest {

    private fun order() = JobOrder(
        jobOrderId = "SEQ-205",
        workMasterId = PrepareSequencedRack.WORK_MASTER,
        version = 1,
        requiredEvidence = Evidence.E2,
        equipmentRequirements = listOf(
            EquipmentRequirement(SLOT, EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to PART)),
            EquipmentRequirement(BIN, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to PART)),
        ),
    )

    private class World : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to Path.of("..", "profile", "fixtures", "minimal.json").normalize()))
        val cell = CellMimic(now = { harness.clock.now() })
        val mw = Middleware(ClientRobotPort(harness.client()), cell, now = { harness.clock.now() })
        val now get() = harness.clock.now()

        fun tasks() = harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build()).tasksList

        /** 잘게 민다 — 시간창(뒤쪽 15초) 안에서 일어나는 일을 보려면 30초 걸음으로는 안 된다. */
        fun drive(rounds: Int = 80, step: Duration = Duration.ofSeconds(5), until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(step)
                Thread.sleep(30)
                mw.pump()
                if (until()) return
            }
            error("조건에 못 미쳤다: tasks=${tasks().map { it.taskId to it.taskState }}")
        }

        fun forceFault(errorType: String, taskId: String) = harness.oracle.forceFault(
            ForceFaultRequest.newBuilder().setRobotId(ROBOT).setErrorType(errorType).setTaskId(taskId).build(),
        ).taskState

        override fun close() = harness.close()
    }

    private fun Middleware.Execution.unit() = units.single()
    private fun Middleware.Execution.settled() = physicalState.isSettled && active == null

    // ── 12.3 첫째 행 — 로봇 완료 · 설비 없음

    @Test
    fun `보고보다 늦게 온 신호도 시간창 안이면 근거다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { exec.unit().state == UnitState.VERIFYING }

            // 로봇은 끝났고 설비는 아직 말이 없다 — 실행은 실패도 완료도 아니다.
            assertEquals(PhysicalState.RUNNING, exec.physicalState)
            val doneAt = assertNotNull(exec.unit().downstreamDoneAt)

            // 폴링 지연 5초 뒤에 신호가 읽힌다(시각 없는 PLC — 읽은 순간이 t_p).
            w.harness.advance(Duration.ofSeconds(5))
            w.cell.program(SLOT, PART)
            w.mw.pump()

            assertEquals(UnitState.DONE, exec.unit().state)
            assertEquals(Evidence.E2, exec.unit().reached)
            assertTrue(exec.unit().evidenceAt!!.isAfter(doneAt), "t_p 가 t_r 뒤인데도 근거로 채택돼야 한다")
            assertTrue(exec.unit().rechecks >= 2, "한 번만 묻고 판정했다")
        }
    }

    @Test
    fun `시간창이 닫힐 때까지 신호가 없으면 UNVERIFIED — 몇 번 물었는지 남긴다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { exec.settled() }

            assertEquals(UnitState.UNVERIFIED, exec.unit().state)
            assertEquals(Verification.ABSENT, exec.unit().verification)
            assertTrue(exec.unit().rechecks >= 2, "재확인 없이 바로 UNVERIFIED 로 갔다")
            assertTrue(exec.unit().note!!.contains("rechecks"), exec.unit().note)
            assertEquals(1, w.tasks().size, "UNVERIFIED 가 재실행을 만들었다")
            assertTrue(w.mw.pending().single().operatorRequired)
        }
    }

    @Test
    fun `짧은 펄스는 폴링이 놓친다 — 그래서 UNVERIFIED 다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { exec.unit().state == UnitState.VERIFYING }

            // 로봇이 끝난 1초 뒤 재석 센서가 1초 켜졌다 꺼진다. 폴링은 5초마다다 — 그 사이다.
            w.cell.pulse(SLOT, PART, at = w.now.plusSeconds(1), lasting = Duration.ofSeconds(1))
            w.drive { exec.settled() }

            assertEquals(UnitState.UNVERIFIED, exec.unit().state, "폴링 주기가 신호 지속 시간보다 길면 신호를 놓친다(12.2)")
        }
    }

    @Test
    fun `같은 펄스라도 PLC 쪽 래치 비트가 있으면 잡는다`() {
        World().use { w ->
            w.cell.latch(SLOT)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { exec.unit().state == UnitState.VERIFYING }

            val pulseAt = w.now.plusSeconds(1)
            w.cell.pulse(SLOT, PART, at = pulseAt, lasting = Duration.ofSeconds(1))
            w.drive { exec.settled() }

            assertEquals(UnitState.DONE, exec.unit().state)
            assertEquals(Evidence.E2, exec.unit().reached)
            assertEquals(pulseAt, exec.unit().evidenceAt, "래치가 남긴 것은 펄스의 시각이어야 한다")
        }
    }

    @Test
    fun `옛 신호는 이 완료의 근거가 아니다 — 창 앞쪽 밖이면 세지 않는다`() {
        World().use { w ->
            // 이전 부품이 그 슬롯에 오래전부터 있었다 — 로봇이 끝나기 훨씬 전의 신호.
            w.cell.programAt(SLOT, PART, at = w.now.minus(Duration.ofMinutes(10)))
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { exec.settled() }

            assertEquals(UnitState.UNVERIFIED, exec.unit().state, "옛 신호를 이번 완료의 근거로 썼다")
            assertTrue(exec.unit().note!!.contains("stale"), exec.unit().note)
        }
    }

    // ── 12.3 둘째 행 — 로봇 실패 · 설비 있음

    @Test
    fun `로봇은 실패라는데 설비에는 있으면 운영자 보류다 — 확인하면 완료`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { w.tasks().any { it.taskState == "RUNNING" } } // 미믹은 틱으로 돈다 — ACCEPTED 에서는 결함을 안 받는다

            w.cell.program(SLOT, PART) // 설비는 부품을 본다
            assertEquals("RETRIABLE", w.forceFault("SKILL_EXECUTION_FAILED", exec.unit().taskId))
            w.drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }

            assertEquals(UnitState.OPERATOR_HOLD, exec.unit().state)
            // 계약의 `fault.failure_class` — 프로파일이 그 모드를 GRASP_FAILED 로 선언했다. 상류에는 이것만 간다.
            assertEquals("GRASP_FAILED", exec.unit().failureClass)
            // 하류 상태 이름과 모드 이름은 note(로그)에만 동반한다.
            assertTrue(exec.unit().note!!.contains("downstream=TASK_STATE_RETRIABLE error_type=SKILL_EXECUTION_FAILED"), exec.unit().note)
            assertEquals(Verification.MATCHED, exec.unit().verification)
            val hold = w.mw.pending().single()
            assertEquals(PhysicalState.OPERATOR_HOLD, hold.physicalState)
            assertTrue(hold.operatorRequired)
            assertEquals(1, w.tasks().size, "운영자가 판단하기 전에 자동으로 다시 돌렸다")

            assertTrue(w.mw.resolve(exec.executionId, SLOT, OperatorDecision.CONFIRM_DONE))
            w.drive { exec.settled() }
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(Evidence.E2, exec.unit().reached)
            assertEquals(1, w.tasks().size)
        }
    }

    @Test
    fun `운영자가 재작업을 택하면 그 슬롯이 새 정체성으로 다시 돈다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { w.tasks().any { it.taskState == "RUNNING" } } // 미믹은 틱으로 돈다 — ACCEPTED 에서는 결함을 안 받는다
            w.cell.program(SLOT, PART)
            w.forceFault("SKILL_EXECUTION_FAILED", exec.unit().taskId)
            w.drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }
            val firstTask = exec.unit().taskId

            assertTrue(w.mw.resolve(exec.executionId, SLOT, OperatorDecision.REWORK))
            w.drive { exec.settled() }

            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(2, w.tasks().size, "재작업은 새 태스크다")
            assertTrue(exec.unit().taskId != firstTask && exec.unit().taskId.endsWith("@r1"), exec.unit().taskId)
            assertEquals(1, exec.unit().attempt)
        }
    }

    @Test
    fun `로봇은 실패이고 설비도 비어 있으면 그냥 실패다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { w.tasks().any { it.taskState == "RUNNING" } } // 미믹은 틱으로 돈다 — ACCEPTED 에서는 결함을 안 받는다
            w.forceFault("SKILL_EXECUTION_FAILED", exec.unit().taskId)
            w.drive { exec.settled() }

            assertEquals(UnitState.FAILED, exec.unit().state)
            assertEquals(PhysicalState.FAILED, exec.physicalState)
        }
    }

    private companion object {
        const val ROBOT = "hum-02"
        const val SLOT = "RACK-205.S01"
        const val BIN = "SEQ-IN-02.BIN-A"
        const val PART = "ENGINE-COVER-A"
    }
}
