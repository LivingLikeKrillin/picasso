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
 * 결과 미확정(`IN_DOUBT`)과 해소 순서 — 보고서 13장, 17장 3·9번. 계획 단계 6.
 *
 * 요청은 하류에 닿았고 답만 잃었다. 미들웨어는 **다시 보내지 않는다** — 물리 작업이 둘이 될 수 있다. 대신 13.2 의 순서로
 * 푼다: ① 같은 참조로 기존 실행 조회(계약은 같은 핸들, 플릿은 같은 운반) → ② 설비 관측(잠정 완료) → ③ 운영자.
 * 조회가 있는 하류에서는 ①에서 풀리고 재실행은 0회다. 없는 하류에서는 운영자에게 가고, 재요청은 사람이 명시적으로 낸다(13.3).
 *
 * 로봇 쪽은 미믹(계약 ④ — 같은 `(task_id, revision)` 재전송이 같은 핸들), 플릿 쪽은 `AmrFleetMimic`(참조 멱등) 위에서 돌고,
 * 응답 유실은 그 사이의 선을 흉내내는 [LossyRobotPort]·[LossyFleet] 이 만든다.
 */
class InDoubtTest {

    // ── 로봇(계약 ④) — 조회가 있다

    private fun rackOrder() = JobOrder(
        jobOrderId = "SEQ-206",
        workMasterId = PrepareSequencedRack.WORK_MASTER,
        version = 1,
        requiredEvidence = Evidence.E2,
        equipmentRequirements = listOf(
            EquipmentRequirement(SLOT, EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to PART)),
            EquipmentRequirement(BIN, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to PART)),
        ),
    )

    private class RobotWorld(drop: Int) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to Path.of("..", "profile", "fixtures", "minimal.json").normalize()))
        val cell = CellMimic(now = { harness.clock.now() })
        val port = LossyRobotPort(ClientRobotPort(harness.client()), dropStartResponses = drop)
        val mw = Middleware(port, cell, now = { harness.clock.now() })

        fun tasks() = harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build()).tasksList

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

    private fun Middleware.Execution.unit() = units.single()
    private fun Middleware.Execution.settled() = physicalState.isSettled && active == null

    @Test
    fun `답을 잃으면 IN_DOUBT 이고, 같은 요청으로 조회해 이어서 추적한다 — 재실행 0회`() {
        RobotWorld(drop = 1).use { w ->
            w.cell.program(SLOT, PART)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder(), ROBOT)).execution
            w.mw.pump()

            // 요청은 로봇에 닿았고(태스크가 있다) 답만 없다 — 미확정이고 상류에 그렇게 드러낸다.
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)
            assertEquals(PhysicalState.IN_DOUBT, exec.physicalState)
            assertEquals(1, w.tasks().size)
            val doubt = w.mw.pending().single()
            assertEquals(listOf(SLOT), doubt.inDoubtUnits)
            assertTrue(doubt.autoResolvesInDoubt, "계약 ④는 같은 요청 재전송이 조회다")

            // 다음 펌프 — 같은 (task_id, revision) 으로 다시 묻는다. 같은 핸들, 새 태스크 없음, 이어서 추적.
            w.drive { exec.settled() }
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(1, w.tasks().size, "IN_DOUBT 해소가 물리 작업을 다시 만들었다")
            assertEquals(2, w.port.starts, "조회는 한 번이면 된다")
            assertEquals(1, exec.unit().lookups)
            assertTrue(exec.unit().note!!.contains("resolved by client-reference lookup"), exec.unit().note)
            // 처음부터 되짚었으므로(WatchTask 0) 중간 전이가 유실되지 않았다 — 종착까지 도달해 E2 로 닫혔다(17장 9번).
            assertEquals(Evidence.E2, exec.unit().reached)
        }
    }

    @Test
    fun `조회가 계속 안 되면 운영자다 — 그래도 재실행은 없다`() {
        RobotWorld(drop = 10).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder(), ROBOT)).execution
            w.drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }

            assertEquals(UnitState.OPERATOR_HOLD, exec.unit().state)
            assertEquals(1, w.tasks().size, "조회 시도가 물리 작업을 만들었다")
            assertEquals(3, exec.unit().lookups, "상한만큼 묻고 멈춘다")
            assertTrue(exec.unit().note!!.contains("lookup unanswered 3 time(s)"), exec.unit().note)
        }
    }

    // ── 플릿 — 조회가 있는 플릿과 없는 플릿

    private fun deliverOrder() = JobOrder(
        jobOrderId = "WT-790",
        workMasterId = DeliverContainer.WORK_MASTER,
        version = 1,
        requiredEvidence = Evidence.E2,
        equipmentRequirements = listOf(
            EquipmentRequirement(SOURCE, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_CONTAINER to CONTAINER)),
            EquipmentRequirement(DEST, EquipmentUse.DESTINATION),
        ),
    )

    private class FleetWorld(drop: Int, lookup: ExecutionLookup) : AutoCloseable {
        val harness = Harness(mapOf("idle-robot" to Path.of("..", "profile", "fixtures", "minimal.json").normalize()))
        val fleet = AmrFleetMimic(now = { harness.clock.now() })
        val cell = CellMimic(now = { harness.clock.now() }, live = { loc -> fleet.containersAt[loc]?.let { it to fleet.placedAt[loc] } })
        val mw = Middleware(ClientRobotPort(harness.client()), cell, LossyFleet(fleet, drop, lookup), now = { harness.clock.now() })

        init {
            fleet.containersAt[SOURCE] = CONTAINER
        }

        fun drive(rounds: Int = 12, until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(Duration.ofSeconds(10))
                fleet.tick()
            }
            error("조건에 못 미쳤다: 세계=${fleet.containersAt}")
        }

        override fun close() = harness.close()
    }

    @Test
    fun `참조로 찾아 주는 플릿이면 같은 운반을 이어서 추적한다`() {
        FleetWorld(drop = 1, lookup = ExecutionLookup.CLIENT_REFERENCE).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(deliverOrder(), ROBOT)).execution
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)
            assertEquals(1, w.fleet.dispatches)

            w.drive { exec.settled() }
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(1, w.fleet.dispatches, "같은 참조가 새 운반을 만들었다")
            assertEquals(Evidence.E2, exec.unit().reached)
        }
    }

    @Test
    fun `조회가 없는 플릿에서 용기가 도착해 있으면 잠정 완료로 운영자에게 — 확인하면 E2 완료`() {
        FleetWorld(drop = 1, lookup = ExecutionLookup.NONE).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(deliverOrder(), ROBOT)).execution
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)
            assertEquals(false, w.mw.pending().single().autoResolvesInDoubt, "이 하류에서는 IN_DOUBT 가 자동으로 안 풀린다 — 상류가 알아야 한다")

            // 플릿은 사실 접수했고 운반을 마친다. 우리는 그것을 물을 수 없고 — 설비가 본다(12.3 셋째 행).
            w.drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }
            assertEquals(CONTAINER, w.fleet.containersAt[DEST])
            assertEquals(1, w.fleet.dispatches, "조회가 없는데 다시 보냈다 — 물리 작업이 둘이 될 뻔했다")
            assertEquals(Verification.MATCHED, exec.unit().verification)
            assertTrue(exec.unit().note!!.contains("provisional done"), exec.unit().note)
            assertTrue(exec.unit().note!!.contains("no client-reference lookup"), exec.unit().note)

            assertTrue(w.mw.resolve(exec.executionId, CONTAINER, OperatorDecision.CONFIRM_DONE))
            w.drive { exec.settled() }
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(Evidence.E2, exec.unit().reached)
            assertEquals(1, w.fleet.dispatches)
        }
    }

    @Test
    fun `조회가 없고 설비도 말이 없으면 운영자에게 — 재요청은 사람이 명시적으로 낸다`() {
        FleetWorld(drop = 1, lookup = ExecutionLookup.NONE).use { w ->
            w.cell.silence(DEST)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(deliverOrder(), ROBOT)).execution
            w.drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }

            assertEquals(1, w.fleet.dispatches, "자동 재실행이 있었다")
            assertTrue(exec.unit().note!!.contains("no automatic re-execution"), exec.unit().note)
            assertEquals(Verification.NOT_REQUESTED, exec.unit().verification)

            // 사람이 미실행을 확인했다고 치고 재요청 — 새 참조다. 이것만이 명령 재시도다(13.3).
            w.cell.program(DEST, CONTAINER) // 이번에는 설비가 본다
            assertTrue(w.mw.resolve(exec.executionId, CONTAINER, OperatorDecision.REWORK))
            w.drive { exec.settled() }
            assertEquals(2, w.fleet.dispatches)
            assertTrue(exec.unit().taskId.endsWith("@r1"), exec.unit().taskId)
        }
    }

    private companion object {
        const val ROBOT = "hum-02"
        const val SLOT = "RACK-206.S01"
        const val BIN = "SEQ-IN-02.BIN-A"
        const val PART = "ENGINE-COVER-A"

        const val CONTAINER = "HU-1050"
        const val SOURCE = "OUT-08"
        const val DEST = "SEQ-IN-03"
    }
}
