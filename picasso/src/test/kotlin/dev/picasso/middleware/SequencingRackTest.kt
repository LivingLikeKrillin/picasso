package dev.picasso.middleware

import dev.picasso.contracts.v1.HoldKind
import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 시나리오 ② — 부품 시퀀싱을 **미들웨어 층**에서 돌린다(보고서 6장, `docs/scenarios.md` §4).
 *
 * `SequencingCellTest`(harness)가 계약 ④에서 답하는 것을 봤다면, 여기는 그 위 —
 * 상류(예상 소비자 = 이 시험)가 JobOrder 를 넣고, 미들웨어가 슬롯마다 원자
 * 태스크로 조합해 mimic 에 돌리고, 셀 검증 장치(PLC/WCS Mimic)의 신호와 결합해
 * 근거 등급을 매기고, JobResponse 를 아웃박스에 낸다. 보고서 6장 상황표 넷과
 * 17장의 1·2·4 를 이 층에서 단언한다.
 *
 * 상류 Mock 은 없다 — 이 시험이 MES 의 역할이다(ADR 38 결정 4).
 */
class SequencingRackTest {

    private fun order(
        version: Int = V17,
        required: Evidence = Evidence.E2,
        slots: Map<String, String> = mapOf("S01" to "A", "S02" to "B", "S03" to "A", "S04" to "C"),
        sources: Map<String, String> = mapOf("A" to "SEQ-IN-02.BIN-A", "B" to "SEQ-IN-02.BIN-B", "C" to "SEQ-IN-02.BIN-C"),
    ) = JobOrder(
        jobOrderId = "SEQ-204",
        workMasterId = PrepareSequencedRack.WORK_MASTER,
        version = version,
        requiredEvidence = required,
        materialRequirements = slots.values.groupingBy { it }.eachCount().map { (m, n) -> MaterialRequirement("ENGINE-COVER-$m", n) },
        equipmentRequirements =
        slots.map { (slot, m) -> EquipmentRequirement("RACK-204.$slot", "destination", mapOf("material" to "ENGINE-COVER-$m")) } +
            sources.map { (m, bin) -> EquipmentRequirement(bin, "source", mapOf("material" to "ENGINE-COVER-$m")) },
    )

    /** 완벽한 세계 — 로봇이 놓은 대로 셀 장치가 본다. 시험이 그 세계를 프로그램한다. */
    private fun CellMimic.perfect(order: JobOrder) {
        order.equipmentRequirements.filter { it.equipmentUse == "destination" }
            .forEach { program(it.id, it.properties["material"]) }
    }

    private class World(profile: Path) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to profile))
        val cell = CellMimic(now = { harness.clock.now() })
        val mw = Middleware(ClientRobotPort(harness.client()), cell, now = { harness.clock.now() })

        fun tasks() = harness.oracle.dumpInternalState(
            DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build(),
        ).tasksList

        /**
         * 시간을 밀고 미들웨어를 돌린다 — [until] 이 참이 될 때까지, 최대 [rounds] 번.
         * 팔로워가 비동기라 각 라운드에 잠깐 기다린다.
         */
        fun drive(rounds: Int = 40, step: Duration = Duration.ofSeconds(30), until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(step)
                Thread.sleep(40)
                mw.pump()
                if (until()) return
            }
            error("조건에 못 미쳤다: tasks=${tasks().map { it.taskId to it.taskState }}")
        }

        override fun close() = harness.close()
    }

    private fun world() = World(Path.of("..", "profile", "fixtures", "minimal.json").normalize())

    private fun Middleware.Execution.settled() = physicalState.isSettled && active == null

    // ── 정상

    @Test
    fun `슬롯 넷이 차례로 돌고, 셀 장치가 확인하면 E2 로 물리 완료다`() {
        world().use { w ->
            val order = order()
            w.cell.perfect(order)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order, ROBOT)).execution

            w.drive { exec.settled() }

            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(listOf("RACK-204.S01", "RACK-204.S02", "RACK-204.S03", "RACK-204.S04"), exec.completedUnits)
            assertTrue(exec.units.all { it.reached == Evidence.E2 && it.verification == Verification.MATCHED })
            // 슬롯 넷 = 원자 태스크 넷, 차례로.
            assertEquals(4, w.tasks().size)
            assertTrue(w.tasks().all { it.taskState == "SUCCEEDED" })

            // 결과 통보 — 아웃박스에 있고 아직 ack 전이다(두 축의 둘째).
            val response = w.mw.pending().single()
            assertEquals(PhysicalState.PHYSICALLY_DONE, response.physicalState)
            assertEquals(Evidence.E2, response.reachedEvidence)
            assertEquals(UpstreamAck.SENT_UNACKED, response.ack)
            assertEquals(UpstreamAck.SENT_UNACKED, exec.upstreamAck)

            assertTrue(w.mw.ack(response.jobResponseId))
            assertEquals(emptyList(), w.mw.pending(), "ack 뒤에도 아웃박스에 남아 있다")
            assertEquals(UpstreamAck.ACKED, exec.upstreamAck)
        }
    }

    @Test
    fun `자재 선언과 배정이 어긋난 주문은 받지 않는다`() {
        // 상류가 A형 둘이라 해 놓고 슬롯이 셋을 요구하면 그 주문은 자기 안에서 모순이다.
        // **재고 판단이 아니다** — 그것은 WMS 의 일이고, 여기서 보는 것은 주문의 정합성이다.
        val broken = order().let { o ->
            o.copy(materialRequirements = o.materialRequirements.map { m ->
                if (m.materialDefinitionId == "ENGINE-COVER-A") m.copy(quantity = 3) else m
            })
        }
        world().use { w ->
            val rejected = assertIs<Middleware.Submission.Rejected>(w.mw.submit(broken, ROBOT))
            assertTrue("선언" in rejected.reason && "배정" in rejected.reason, rejected.reason)
            // **받아 놓고 돌리면** 어긋남이 로봇이 실패한 뒤에야 보인다. 그때는 이미 움직인 뒤다.
            assertEquals(emptyList(), w.tasks(), "거절인데 로봇에 갔다")
        }
    }

    @Test
    fun `자재 선언이 없으면 검사하지 않는다 — 없는 것과 어긋나는 것은 다르다`() {
        world().use { w ->
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order().copy(materialRequirements = emptyList()), ROBOT))
        }
    }

    @Test
    fun `요구 등급이 E0 이면 셀 장치를 묻지 않는다`() {
        world().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(required = Evidence.E0), ROBOT)).execution
            w.drive { exec.settled() }

            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertTrue(exec.units.all { it.verification == Verification.NOT_REQUESTED && it.reached == Evidence.E0 })
            assertEquals(Evidence.E0, w.mw.pending().single().reachedEvidence)
        }
    }

    // ── 상황표 (보고서 6장)

    @Test
    fun `B형 슬롯에 A형이 감지되면 그 슬롯은 완료가 아니고 불일치로 보고된다`() {
        world().use { w ->
            val order = order()
            w.cell.perfect(order)
            w.cell.program("RACK-204.S02", "ENGINE-COVER-A") // 오인계
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order, ROBOT)).execution
            w.drive { exec.settled() }

            val s02 = exec.units.single { it.unitId == "RACK-204.S02" }
            assertEquals(UnitState.FAILED, s02.state)
            assertEquals(Verification.MISMATCH, s02.verification)
            assertEquals("observed=ENGINE-COVER-A at RACK-204.S02", s02.note, "잘못 놓인 것의 위치를 기록한다(12.3 넷째 행)")
            assertEquals(PhysicalState.PARTIAL, exec.physicalState, "다른 슬롯은 보존되고 실행은 부분 완료다")

            val response = w.mw.pending().single()
            assertEquals(mapOf("RACK-204.S02" to Middleware.MISMATCH), response.incompleteUnits)
            assertEquals(listOf("RACK-204.S01", "RACK-204.S03", "RACK-204.S04"), response.completedUnits)
            assertTrue(response.operatorRequired, "오인계 의심은 운영자 확인이다(보고서 12.3)")
        }
    }

    @Test
    fun `로봇은 끝났다는데 설비가 말이 없으면 UNVERIFIED 다 — 재작업이 아니라 운영자 확인`() {
        world().use { w ->
            val order = order()
            w.cell.perfect(order)
            w.cell.silence("RACK-204.S03")
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order, ROBOT)).execution
            w.drive { exec.settled() }

            val s03 = exec.units.single { it.unitId == "RACK-204.S03" }
            assertEquals(UnitState.UNVERIFIED, s03.state)
            assertEquals(Verification.ABSENT, s03.verification)
            assertEquals(PhysicalState.UNVERIFIED, exec.physicalState)
            assertEquals(4, w.tasks().size, "UNVERIFIED 가 재실행을 만들었다")

            val response = w.mw.pending().single()
            assertEquals(listOf("RACK-204.S03"), response.unverifiedUnits)
            assertTrue(response.operatorRequired)
        }
    }

    @Test
    fun `C형이 부족하면 그 슬롯만 못 시작하고 완료 슬롯은 보존된다 — 그리고 v18 이 채운다`() {
        world().use { w ->
            val v17 = order(sources = mapOf("A" to "SEQ-IN-02.BIN-A", "B" to "SEQ-IN-02.BIN-B")) // C 없음
            w.cell.perfect(v17)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(v17, ROBOT)).execution
            w.drive { exec.settled() }

            assertEquals(PhysicalState.PARTIAL, exec.physicalState)
            assertEquals(
                mapOf("RACK-204.S04" to PrepareSequencedRack.NO_SOURCE),
                w.mw.pending().single().incompleteUnits,
            )
            assertEquals(3, w.tasks().size, "못 시작할 슬롯에 태스크가 생겼다")

            // 같은 버전 재발행 — 멱등, 새 태스크 없음.
            assertIs<Middleware.Submission.Idempotent>(w.mw.submit(v17, ROBOT))
            assertIs<Middleware.Submission.Rejected>(w.mw.submit(v17.copy(version = V17 - 1), ROBOT))

            // v18 — C형 공급이 생겼다. 완료 슬롯 셋은 그대로, S04 만 새로 돈다.
            val v18 = order(version = V18)
            w.cell.perfect(v18)
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(v18, ROBOT))
            w.drive { exec.settled() }

            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(4, w.tasks().size, "완료 슬롯이 다시 돌았다")
            assertEquals(V18, exec.units.single { it.unitId == "RACK-204.S04" }.revision)
            assertEquals(V17, exec.units.single { it.unitId == "RACK-204.S01" }.revision, "완료 슬롯은 새 버전을 상속한다 — 다시 안 돈다")
            assertEquals(2, w.mw.responses().size, "부분 완료와 완료, 통보 둘")
        }
    }

    // ── 취소 (보고서 14, 17장 4번)

    private fun cancellableProfile(): Path {
        val raw = Files.readString(Path.of("..", "profile", "fixtures", "minimal.json").normalize())
        val needle = "\"cancel_support\": \"NO\""
        check(raw.split(needle).size == 2) { "픽스처에 cancel_support NO 가 하나가 아니다" }
        val copy = Files.createTempFile("picasso-mw-cancellable-", ".json")
        Files.writeString(copy, raw.replace(needle, "\"cancel_support\": \"YES\""))
        copy.toFile().deleteOnExit()
        return copy
    }

    @Test
    fun `S03 파지 중 취소 — 중단점과 잔여 상태가 응답에 있고 재작업은 자동으로 돌지 않는다`() {
        World(cancellableProfile()).use { w ->
            val order = order()
            w.cell.perfect(order)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order, ROBOT)).execution

            w.drive(step = Duration.ofSeconds(30)) { exec.completedUnits.size == 2 && exec.active?.unitId == "RACK-204.S03" }
            w.harness.advance(Duration.ofSeconds(5))
            Thread.sleep(40)
            w.mw.pump()
            assertEquals("RACK-204.S03", exec.active?.unitId)
            assertEquals(HoldKind.HOLD_KIND_HOLDING, exec.active!!.hold.kind, "전제 — S03 을 든 채여야 한다")

            assertTrue(w.mw.cancel(exec.executionId))
            w.drive(step = Duration.ofSeconds(1)) { exec.physicalState == PhysicalState.ABORTED }

            val report = assertNotNull(w.mw.lastCancel(exec.executionId))
            assertEquals(listOf("RACK-204.S01", "RACK-204.S02"), report.completedUnits)
            assertEquals("RACK-204.S03", report.inProgressUnit)
            assertEquals(listOf("RACK-204.S04"), report.notStartedUnits)
            assertEquals("done", report.cleanup)
            assertEquals(HoldKind.HOLD_KIND_EMPTY, report.residualHold.kind, "복구했다면서 든 채다")
            assertEquals(3, w.tasks().size, "S04 가 자동으로 돌았다 — 재작업은 자동이 아니다")

            val response = w.mw.pending().single()
            assertEquals(PhysicalState.ABORTED, response.physicalState)
            assertEquals(setOf("RACK-204.S03", "RACK-204.S04"), response.incompleteUnits.keys)
        }
    }

    @Test
    fun `단위 사이에서 취소하면 다음 단위를 시작하지 않는다`() {
        // 결함 주입이 찾은 구멍 — 취소를 걸었을 때 도는 단위가 없으면(접수 직후, 또는
        // 단위 사이) 아무것도 새로 시작하면 안 된다. 앞 판 시험은 도는 중의 취소만 봤다.
        World(cancellableProfile()).use { w ->
            val order = order()
            w.cell.perfect(order)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order, ROBOT)).execution

            assertTrue(w.mw.cancel(exec.executionId), "접수 직후의 취소가 거절됐다")
            w.mw.pump()

            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertEquals(0, w.tasks().size, "취소된 실행이 단위를 시작했다")
            val report = assertNotNull(w.mw.lastCancel(exec.executionId))
            assertEquals(emptyList(), report.completedUnits)
            assertEquals(null, report.inProgressUnit)
            assertEquals(4, report.notStartedUnits.size)
            assertEquals("nothing_to_clean", report.cleanup)
        }
    }

    private companion object {
        const val ROBOT = "hum-02"
        const val V17 = 17
        const val V18 = 18
    }
}
