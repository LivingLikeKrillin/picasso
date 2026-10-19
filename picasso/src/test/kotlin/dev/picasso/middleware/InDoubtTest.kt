package dev.picasso.middleware

import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import java.nio.file.Files
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

    /** [blind] 면 스냅숏을 못 읽는 기체다 — 미확정 태스크가 스냅숏에 안 보여 조회(`StartTask` 재전송)로만 풀 수 있다. */
    private class RobotWorld(
        drop: Int,
        profile: Path = Path.of("..", "profile", "fixtures", "minimal.json").normalize(),
        blind: Boolean = false,
    ) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to profile))
        val cell = CellMimic(now = { harness.clock.now() })
        val port = LossyRobotPort(ClientRobotPort(harness.client()).let { if (blind) FaultBlindRobotPort(it) else it }, dropStartResponses = drop)
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
        RobotWorld(drop = 1, blind = true).use { w -> // 스냅숏이 안 열려야 조회가 푼다. 열리면 붙는다(아래 시험)
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
    fun `조회가 유예 안에서 계속 안 되면 유예가 지난 뒤 운영자다 — 그래도 재실행은 없다`() {
        RobotWorld(drop = 1000, blind = true).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder(), ROBOT)).execution
            val start = w.harness.clock.now()
            w.drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }

            assertEquals(UnitState.OPERATOR_HOLD, exec.unit().state)
            assertEquals(1, w.tasks().size, "조회 시도가 물리 작업을 만들었다")
            assertTrue(exec.unit().lookups > 3, "횟수가 아니라 유예 안의 시간으로 물어야 한다: ${exec.unit().lookups}")
            assertTrue(!w.harness.clock.now().isBefore(start.plus(exec.capability.inDoubtGrace)), "유예가 지나기 전에 운영자로 넘어갔다")
            assertTrue(exec.unit().note!!.contains("lookup unanswered"), exec.unit().note)
        }
    }

    @Test
    fun `1초 안의 짧은 끊김 뒤 링크가 돌아오면 미확정 유예 안에서 풀린다`() {
        // 출발의 응답과 조회 셋이 한꺼번에 사라진다(가상 시계는 안 밀렸다). 횟수로 재면 여기서 예산이 바닥나고, 시간으로 재면 유예가 남아 있다.
        RobotWorld(drop = 5, blind = true).use { w ->
            w.cell.program(SLOT, PART)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder(), ROBOT)).execution
            repeat(4) { w.mw.pump() }
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)
            assertEquals(2, w.port.starts, "시험의 전제: 출발과 첫 조회의 응답을 잃었고 간격 안의 라운드는 묻지 않았다")

            w.harness.advance(Duration.ofSeconds(1))
            w.drive { exec.settled() }
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(1, w.tasks().size, "조회가 물리 작업을 다시 만들었다")
            assertEquals(Evidence.E2, exec.unit().reached)
        }
    }

    @Test
    fun `미확정 조회는 간격 안에서 다시 묻지 않고 간격이 지나면 묻는다`() {
        RobotWorld(drop = 1000, blind = true).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder(), ROBOT)).execution
            w.mw.pump()
            w.mw.pump() // 첫 조회는 간격 없이 바로 나간다
            assertEquals(2, w.port.starts)

            repeat(5) { w.mw.pump() }
            assertEquals(2, w.port.starts, "간격 안인데 다시 물었다")
            w.harness.advance(Middleware.LOOKUP_INTERVAL.minusMillis(1))
            w.mw.pump()
            assertEquals(2, w.port.starts, "간격이 지나기 전에 다시 물었다")

            w.harness.advance(Duration.ofMillis(1))
            w.mw.pump()
            assertEquals(3, w.port.starts, "간격이 지났는데 안 물었다")
            assertEquals(2, exec.unit().lookups)
        }
    }

    @Test
    fun `스냅숏에 보이는 미확정 태스크에는 StartTask 없이 붙는다`() {
        RobotWorld(drop = 1).use { w ->
            w.cell.program(SLOT, PART)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder(), ROBOT)).execution
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)

            w.mw.pump()
            assertEquals(UnitState.RUNNING, exec.unit().state)
            assertEquals(1, w.port.starts, "스냅숏으로 붙을 수 있는데 StartTask 를 다시 보냈다")
            assertEquals(0, exec.unit().lookups)
            assertTrue(exec.unit().note!!.contains("snapshot"), exec.unit().note)
            assertEquals(exec.unit().taskId, exec.handle!!.taskId)

            w.drive { exec.settled() }
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(1, w.tasks().size)
            assertEquals(Evidence.E2, exec.unit().reached, "붙은 태스크를 처음부터 되짚어 종료까지 봐야 한다")
        }
    }

    /** 픽스처의 `pick_place` 는 취소 `NO` 다. 취소가 닿는 경로를 보려면 `YES` 인 사본이 필요하다(SequencingRackTest 와 같은 수법). */
    private fun cancellableProfile(): Path {
        val raw = Files.readString(Path.of("..", "profile", "fixtures", "minimal.json").normalize())
        val needle = "\"cancel_support\": \"NO\""
        check(raw.split(needle).size == 2) { "픽스처에 cancel_support NO 가 하나가 아니다" }
        val copy = Files.createTempFile("picasso-doubt-cancellable-", ".json")
        Files.writeString(copy, raw.replace(needle, "\"cancel_support\": \"YES\""))
        copy.toFile().deleteOnExit()
        return copy
    }

    @Test
    fun `미확정 중의 취소는 핸들이 생기는 순간 하류에 닿는다 — 재실행 없이 중단으로 끝난다`() {
        RobotWorld(drop = 1, profile = cancellableProfile()).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder(), ROBOT)).execution
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)

            // 답을 못 받은 채로 취소가 온다 — 보낼 핸들이 없다. 요청만 적어 둔다.
            assertTrue(w.mw.cancel(exec.executionId))
            assertEquals(PhysicalState.CANCELING, exec.physicalState)

            w.drive { exec.settled() }
            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertEquals(1, w.tasks().size, "취소 경로가 물리 작업을 다시 만들었다")
            val report = w.mw.lastCancel(exec.executionId)!!
            assertEquals(null, report.refusal)
            assertEquals(SLOT, report.inProgressUnit)
            assertTrue(w.tasks().single().taskState.startsWith("CANCELLED"), w.tasks().single().taskState)
        }
    }

    @Test
    fun `미확정 중에 건 취소는 조회가 안 돼도 기체 태스크를 멈춘다`() {
        // 조회(StartTask 재전송)는 전부 응답을 잃는다. 스냅숏에 태스크가 보이면 그 핸들로 취소가 나가야 한다.
        RobotWorld(drop = 10, profile = cancellableProfile()).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder(), ROBOT)).execution
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)
            assertTrue(w.mw.cancel(exec.executionId))

            w.drive { exec.settled() }
            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertEquals(1, w.tasks().size)
            assertTrue(w.tasks().single().taskState.startsWith("CANCELLED"), "취소가 기체 태스크에 안 닿았다: ${w.tasks().single().taskState}")
            assertEquals(null, w.mw.lastCancel(exec.executionId)!!.refusal)
        }
    }

    @Test
    fun `스냅숏도 조회도 안 되면 취소가 걸린 실행을 중단으로 닫지 않고 운영자 보류에 둔다`() {
        // 취소가 갈 핸들이 없다. 중단(motionStopped)으로 닫으면 기체가 그 태스크를 하는 동안 실행이 기체를 내준다.
        RobotWorld(drop = 1000, profile = cancellableProfile(), blind = true).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder(), ROBOT)).execution
            w.mw.pump()
            assertTrue(w.mw.cancel(exec.executionId))

            w.drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }
            repeat(3) { w.mw.pump() }

            assertEquals(PhysicalState.OPERATOR_HOLD, exec.physicalState, "취소가 닿지 않았는데 실행이 닫혔다")
            assertEquals(UnitState.OPERATOR_HOLD, exec.unit().state)
            assertEquals(null, w.mw.lastCancel(exec.executionId), "닿지 않은 취소에 취소 응답이 나갔다")
            assertTrue(exec.physicalState.occupiesRobot, "실행이 기체를 내줬다")
            assertTrue(exec.eventTrail.any { it.kind == Middleware.CANCEL_UNSENT }, "닿지 못한 취소가 트레이스에 없다")
            assertEquals(true, w.mw.pending().last().operatorRequired)
        }
    }

    @Test
    fun `운영자 보류에 둔 취소는 운영자가 판단하면 닿지 못했다는 사유를 싣고 끝난다`() {
        RobotWorld(drop = 1000, profile = cancellableProfile(), blind = true).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder(), ROBOT)).execution
            w.mw.pump()
            assertTrue(w.mw.cancel(exec.executionId))
            w.drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }

            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, SLOT, OperatorDecision.CONFIRM_DONE, OPERATOR))
            w.drive { exec.settled() }

            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            val report = w.mw.lastCancel(exec.executionId)!!
            assertEquals(Middleware.CANCEL_NOT_SENT, report.refusal)
            assertEquals(listOf(SLOT), report.completedUnits, "운영자가 확인한 단위가 완료로 실려야 한다")
        }
    }

    @Test
    fun `미확정 중의 취소를 하류가 거절하면 그 단위는 끝까지 가고 — 거절과 멈춘 경계가 응답에 있다`() {
        RobotWorld(drop = 1).use { w -> // 픽스처의 pick_place 는 취소 NO
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder().copy(requiredEvidence = Evidence.E0), ROBOT)).execution
            w.mw.pump()
            assertTrue(w.mw.cancel(exec.executionId))

            w.drive { exec.settled() }
            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            val report = w.mw.lastCancel(exec.executionId)!!
            assertEquals("REJECTION_CODE_CANCEL_UNSUPPORTED", report.refusal)
            assertEquals(null, report.inProgressUnit, "중단된 단위는 없다 — 끝까지 갔다")
            assertEquals(SLOT, report.stoppedAfter)
            assertEquals(listOf(SLOT), report.completedUnits)
            assertEquals("SUCCEEDED", w.tasks().single().taskState)
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

            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, CONTAINER, OperatorDecision.CONFIRM_DONE, OPERATOR))
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
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, CONTAINER, OperatorDecision.REWORK, OPERATOR))
            w.drive { exec.settled() }
            assertEquals(2, w.fleet.dispatches)
            assertTrue(exec.unit().taskId.endsWith("@r1"), exec.unit().taskId)
        }
    }

    private companion object {
        /** 판단하는 사람 — 운영자 판단은 사람만 낸다(ADR 47). */
        val OPERATOR = Approver("op-1", ApproverKind.PERSON)

        const val ROBOT = "hum-02"
        const val SLOT = "RACK-206.S01"
        const val BIN = "SEQ-IN-02.BIN-A"
        const val PART = "ENGINE-COVER-A"

        const val CONTAINER = "HU-1050"
        const val SOURCE = "OUT-08"
        const val DEST = "SEQ-IN-03"
    }
}
