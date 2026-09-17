package dev.picasso.middleware

import dev.picasso.harness.Harness
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 셀 자리의 점유(§15.153) — **저 자리가 지금 쓰이는가.**
 *
 * 축이 파지 하나뿐일 때는 두 주문이 같은 슬롯을 목적지로 삼아도 둘 다 접수됐고, 출발 자리가 비어 있어도
 * 하달한 뒤에야 실패했다. 점유는 계약의 사전 조건이 될 수 없으므로(그 주어는 로봇 상태이고 관측자가
 * 어댑터여야 한다) 이 층의 관문이 든다.
 */
class OccupancyTest {

    // ── 판정의 순수한 부분

    @Test
    fun `설비가 말이 없으면 판정하지 않는다`() {
        // 없는 관측을 위반으로 세면 신호가 죽은 셀이 통째로 선다.
        assertEquals(SourceCheck.Silent, CellOccupancy.sourceCheck(null, "ENGINE-COVER-A"))
    }

    @Test
    fun `비었으면 결품이다`() {
        assertEquals(
            SourceCheck.Missing(null),
            CellOccupancy.sourceCheck(SlotSignal(occupied = false, identity = null), "ENGINE-COVER-A"),
        )
    }

    @Test
    fun `다른 것이 있으면 그 신원을 적는다`() {
        // 무엇이 있는지 적어야 운영자가 «비었다» 와 «잘못 채웠다» 를 구별한다.
        assertEquals(
            SourceCheck.Missing("ENGINE-COVER-B"),
            CellOccupancy.sourceCheck(SlotSignal(occupied = true, identity = "ENGINE-COVER-B"), "ENGINE-COVER-A"),
        )
    }

    @Test
    fun `요구가 없으면 판정하지 않는다`() {
        // 자재를 지정하지 않은 단위에 «다른 것이 있다» 를 말할 근거가 없다.
        assertEquals(SourceCheck.Ready, CellOccupancy.sourceCheck(SlotSignal(occupied = true, identity = "X"), null))
    }

    @Test
    fun `있다는 것만 말한 신호는 통과다`() {
        // 있다는 것은 관측이고 무엇인지는 아니다. 모르는 것을 «다르다» 로 단정하지 않는다.
        assertEquals(
            SourceCheck.Ready,
            CellOccupancy.sourceCheck(SlotSignal(occupied = true, identity = null), "ENGINE-COVER-A"),
        )
    }

    @Test
    fun `셀이 답하지 않으면 모른다가 흐른다`() {
        // 빈 목록으로 접으면 «그 자재를 든 자리가 하나도 없다» 로 읽혀 운영자가 재고를 의심한다.
        assertNull(CellOccupancy.alternatives(null, emptySet(), exclude = "BIN-A"))
        assertEquals(emptyList(), CellOccupancy.alternatives(emptyList(), emptySet(), exclude = "BIN-A"))
    }

    @Test
    fun `이미 잡힌 자리와 자기 자리는 제시하지 않는다`() {
        // 제시하자마자 관문에 걸릴 자리를 내면 운영자가 같은 거절을 두 번 받는다.
        assertEquals(
            listOf("BIN-C"),
            CellOccupancy.alternatives(listOf("BIN-A", "BIN-B", "BIN-C"), setOf("BIN-B"), exclude = "BIN-A"),
        )
    }

    // ── 접수 관문

    private class World : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to PRECOND))
        val cell = CellMimic(now = { harness.clock.now() })
        val mw = Middleware(ClientRobotPort(harness.client()), cell, now = { harness.clock.now() })

        fun drive(rounds: Int = 200, step: Duration = Duration.ofSeconds(1), until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(step)
                Thread.sleep(40)
                mw.pump()
                if (until()) return
            }
            error("조건에 못 미쳤다")
        }

        override fun close() = harness.close()
    }

    @Test
    fun `같은 자리를 둘이 잡으면 뒤엣것을 막는다`() {
        // 축이 없을 때는 둘 다 접수됐다. 같은 슬롯에 둘을 놓는 주문이 물리적으로 나가는 자리다.
        World().use { w ->
            w.cell.program(RACK, MATERIAL)
            w.cell.program(BIN_A, MATERIAL)
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(rack("SEQ-1", BIN_A), ROBOT))

            val second = assertIs<Middleware.Submission.Rejected>(w.mw.submit(rack("SEQ-2", BIN_A), ROBOT))
            assertTrue(RACK in second.reason, second.reason)
            assertTrue("SEQ-1" in second.reason, "누가 잡고 있는지 적어야 운영자가 기다릴지 고친다: ${second.reason}")
            assertEquals(1, w.mw.executions().size, "막았는데 실행이 생겼다")
        }
    }

    @Test
    fun `종착한 실행은 자리를 놓는다`() {
        // 끝난 주문이 자리를 영원히 물고 있으면 그 자리는 다시 못 쓴다.
        World().use { w ->
            w.cell.program(RACK, MATERIAL)
            w.cell.program(BIN_A, MATERIAL)
            val first = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rack("SEQ-1", BIN_A), ROBOT)).execution
            w.drive { first.physicalState.isSettled }

            assertIs<Middleware.Submission.Accepted>(w.mw.submit(rack("SEQ-2", BIN_A), ROBOT))
        }
    }

    @Test
    fun `완료가 아닌 채로 종착해도 자리를 놓는다`() {
        // ★단위가 `DONE` 이 아닌 채 실행이 끝나는 경우다 — 실패한 주문이 자리를 영원히 물고 있으면
        //   그 자리는 사람이 손대기 전까지 못 쓴다. 단위만 보는 필터로는 이 경우를 못 놓는다.
        World().use { w ->
            w.cell.program(RACK, MATERIAL)
            val first = assertIs<Middleware.Submission.Accepted>(w.mw.submit(sourceless("SEQ-0"), ROBOT)).execution
            w.drive { first.physicalState.isSettled }
            assertTrue(first.units.none { it.state == UnitState.DONE }, "이 시험이 보려는 경우가 아니다")

            w.cell.program(BIN_A, MATERIAL)
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(rack("SEQ-1", BIN_A), ROBOT))
        }
    }

    @Test
    fun `갈 자리는 점유가 아니다`() {
        // ★`destination` 은 두 뜻을 진다 — «놓을 자리» 와 «갈 자리». 목적지만 보면 같은 설비를
        //   두 번 살피는 주문이 서로를 막는다. 점유는 **아는 것을 놓는 자리**뿐이다.
        World().use { w ->
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol("PATROL-A"), ROBOT))
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol("PATROL-B"), ROBOT))
        }
    }

    @Test
    fun `출발 자리가 비었으면 보내지 않는다`() {
        World().use { w ->
            w.cell.program(RACK, MATERIAL)
            w.cell.empty(BIN_A)

            val rejected = assertIs<Middleware.Submission.Rejected>(w.mw.submit(rack("SEQ-1", BIN_A), ROBOT))
            assertTrue(BIN_A in rejected.reason, rejected.reason)
            assertTrue("비었다" in rejected.reason, rejected.reason)
            assertEquals(emptyList(), w.mw.executions(), "결품인데 실행이 생겼다")
        }
    }

    @Test
    fun `설비가 말이 없으면 보낸다`() {
        // 신호가 없는 셀에서 라인이 통째로 서면 이 관문은 곧 꺼진다.
        World().use { w ->
            w.cell.program(RACK, MATERIAL)
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(rack("SEQ-1", BIN_A), ROBOT))
        }
    }

    @Test
    fun `다른 자재가 놓여 있으면 그 신원을 적는다`() {
        World().use { w ->
            w.cell.program(RACK, MATERIAL)
            w.cell.program(BIN_A, "ENGINE-COVER-B")

            val rejected = assertIs<Middleware.Submission.Rejected>(w.mw.submit(rack("SEQ-1", BIN_A), ROBOT))
            assertTrue("ENGINE-COVER-B" in rejected.reason, rejected.reason)
        }
    }

    @Test
    fun `그 자재를 든 다른 자리를 제시한다`() {
        // 자리 이름만 낸다 — 어느 자리를 쓸지는 주문을 고치는 쪽의 결정이고 이 층은 고르지 않는다.
        World().use { w ->
            w.cell.program(RACK, MATERIAL)
            w.cell.empty(BIN_A)
            w.cell.program(BIN_B, MATERIAL)

            val rejected = assertIs<Middleware.Submission.Rejected>(w.mw.submit(rack("SEQ-1", BIN_A), ROBOT))
            assertEquals(listOf(BIN_B), rejected.alternativeLocations)
        }
    }

    @Test
    fun `든 자리가 없으면 없다고 답한다`() {
        // 빈 목록은 답이다 — «못 물어봤다»(널)와 구별된다.
        World().use { w ->
            w.cell.program(RACK, MATERIAL)
            w.cell.empty(BIN_A)

            val rejected = assertIs<Middleware.Submission.Rejected>(w.mw.submit(rack("SEQ-1", BIN_A), ROBOT))
            assertEquals(emptyList(), rejected.alternativeLocations)
        }
    }

    companion object {
        const val ROBOT = "hum-02"
        const val MATERIAL = "ENGINE-COVER-A"
        const val RACK = "RACK-204.S01"
        const val BIN_A = "SEQ-IN-02.BIN-A"
        const val BIN_B = "SEQ-IN-02.BIN-B"

        private val PRECOND: Path = Path.of("..", "profile", "fixtures", "precondition.json").normalize()

        /** 한 자재를 한 자리에서 집어 선반에 놓는 주문. */
        private fun rack(jobOrderId: String, from: String) = JobOrder(
            jobOrderId = jobOrderId,
            workMasterId = PrepareSequencedRack.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E2,
            materialRequirements = listOf(MaterialRequirement(MATERIAL, 1)),
            equipmentRequirements = listOf(
                EquipmentRequirement(RACK, EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to MATERIAL)),
                EquipmentRequirement(from, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to MATERIAL)),
            ),
        )

        /** 제시 자리가 없는 주문 — 계획에서 `NO_SOURCE` 로 실패하고 실행은 완료 아닌 채로 종착한다. */
        private fun sourceless(jobOrderId: String) = JobOrder(
            jobOrderId = jobOrderId,
            workMasterId = PrepareSequencedRack.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E2,
            materialRequirements = listOf(MaterialRequirement(MATERIAL, 1)),
            equipmentRequirements = listOf(
                EquipmentRequirement(RACK, EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to MATERIAL)),
            ),
        )

        /** 같은 설비를 살피는 점검 순회 — 자리를 채우지 않는다. */
        private fun patrol(jobOrderId: String) = JobOrder(
            jobOrderId = jobOrderId,
            workMasterId = InspectAsset.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E0,
            equipmentRequirements = listOf(
                EquipmentRequirement(
                    "PUMP-01",
                    EquipmentUse.INSPECTION_TARGET,
                    mapOf(EquipmentUse.PROP_LOCATION to "PUMP-ROOM-1"),
                ),
            ),
        )
    }
}
