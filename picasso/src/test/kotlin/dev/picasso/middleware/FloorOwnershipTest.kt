package dev.picasso.middleware

import dev.picasso.harness.Harness
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 소유자 없는 자원에는 명령을 내지 않는다(§15.154) — deny by default.
 *
 * 걷는 기체가 셀을 나가면 그 바닥의 소유자가 없고, 플릿은 자기 AMR 만 승인한다. 그러면 이 층이 내는
 * 명령이 **아무 관문도 통과하지 않고** 물리 세계로 나간다. 소유자를 만들거나 그 자원을 쓰지 않거나
 * 둘뿐이며, 무승인 통행을 허용하는 셋째는 없다.
 */
class FloorOwnershipTest {

    /** 자리마다 소유자를 시험이 정한다. 대장에 없는 자리는 선언 안 됨이다. */
    private class Ledger(private val rows: Map<String, FloorOwner>) : FloorOwnership {
        override fun ownerOf(location: String): FloorOwner = rows[location] ?: FloorOwner.NotDeclared
    }

    private class World(floors: FloorOwnership) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to PRECOND))
        val mw = Middleware(
            ClientRobotPort(harness.client()),
            CellMimic(now = { harness.clock.now() }),
            now = { harness.clock.now() },
            floors = floors,
        )

        override fun close() = harness.close()
    }

    @Test
    fun `소유자 없는 바닥으로는 안 보낸다`() {
        // 빈 칸이 무승인 경로다. 여기서 막지 않으면 그 명령은 아무의 승인도 없이 물리 세계로 나간다.
        World(Ledger(mapOf(CORRIDOR to FloorOwner.Unowned))).use { w ->
            val rejected = assertIs<Middleware.Submission.Rejected>(w.mw.submit(patrol("PATROL-1", CORRIDOR), ROBOT))
            assertTrue(CORRIDOR in rejected.reason, rejected.reason)
            assertTrue("소유자가 없다" in rejected.reason, rejected.reason)
            assertEquals(emptyList(), w.mw.executions(), "막았는데 실행이 생겼다")
        }
    }

    @Test
    fun `소유자가 있으면 보낸다`() {
        // 막는 것은 무소유이지 대장 자체가 아니다.
        World(Ledger(mapOf(CORRIDOR to FloorOwner.Declared("플릿")))).use { w ->
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol("PATROL-1", CORRIDOR), ROBOT))
        }
    }

    @Test
    fun `대장을 안 붙인 배치는 막지 않는다`() {
        // ★「자리는 아는데 주인이 없다」와 「대장 자체가 없다」는 뜻이 반대다. 접으면 대장 없는 현장이
        //   통째로 서고, 그러면 이 관문이 곧 꺼진다.
        World(FloorOwnership.None).use { w ->
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol("PATROL-1", CORRIDOR), ROBOT))
        }
    }

    @Test
    fun `대장에 없는 자리도 막지 않는다`() {
        // 대장이 있어도 그 자리를 안 적었으면 「선언 안 됨」이다 — 모르는 것을 무소유로 단정하지 않는다.
        World(Ledger(mapOf("다른-구역" to FloorOwner.Unowned))).use { w ->
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol("PATROL-1", CORRIDOR), ROBOT))
        }
    }

    @Test
    fun `한 단위만 무소유여도 주문 전체를 막는다`() {
        // 앞 단위가 나가고 뒤에서 막히면 기체가 도중에 선다. 접수 시점에 통째로 막는 것이 그것을 막는다.
        val rows = mapOf(SAFE to FloorOwner.Declared("플릿"), CORRIDOR to FloorOwner.Unowned)
        World(Ledger(rows)).use { w ->
            val rejected = assertIs<Middleware.Submission.Rejected>(
                w.mw.submit(twoStops("PATROL-1", SAFE, CORRIDOR), ROBOT),
            )
            assertTrue(CORRIDOR in rejected.reason, rejected.reason)
        }
    }

    // ── 순수 판정

    @Test
    fun `안 붙인 대장은 모든 자리를 선언 안 됨으로 답한다`() {
        assertEquals(FloorOwner.NotDeclared, FloorOwnership.None.ownerOf(CORRIDOR))
    }

    companion object {
        const val ROBOT = "hum-02"
        const val CORRIDOR = "CORRIDOR-7"
        const val SAFE = "PUMP-ROOM-1"

        private val PRECOND: Path = Path.of("..", "profile", "fixtures", "precondition.json").normalize()

        private fun patrol(jobOrderId: String, where: String) = JobOrder(
            jobOrderId = jobOrderId,
            workMasterId = InspectAsset.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E0,
            equipmentRequirements = listOf(
                EquipmentRequirement("PUMP-01", EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to where)),
            ),
        )

        private fun twoStops(jobOrderId: String, first: String, second: String) = JobOrder(
            jobOrderId = jobOrderId,
            workMasterId = InspectAsset.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E0,
            equipmentRequirements = listOf(
                EquipmentRequirement("PUMP-01", EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to first)),
                EquipmentRequirement("PUMP-02", EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to second)),
            ),
        )
    }
}
