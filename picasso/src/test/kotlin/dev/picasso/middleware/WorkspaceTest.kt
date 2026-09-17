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
 * 같은 작업 구역에서 두 기체가 동시에 일하지 않는다(§15.153).
 *
 * 두 기체의 작업 반경이 겹치면 그것도 셀 전용 자원의 경쟁이다. 이 저장소에 기하가 없으므로 공간을
 * **구역 이름**으로 다룬다 — 맞대 볼 토큰이라 어긋나게 만들 수 있고, 그래서 시뮬레이션에서 진짜로
 * 검증된다. 반경으로 지었다면 미믹에 기하가 없어 항상 통과하는 시험이 됐을 것이다.
 */
class WorkspaceTest {

    /** 자리마다 구역을 시험이 정한다. 대장에 없는 자리는 구역 밖이다. */
    private class Zones(private val rows: Map<String, String>) : Workspace {
        override fun zoneOf(location: String): String? = rows[location]
    }

    private class World(zones: Workspace) : AutoCloseable {
        val harness = Harness(mapOf(A to PRECOND, B to PRECOND))
        val mw = Middleware(
            ClientRobotPort(harness.client()),
            CellMimic(now = { harness.clock.now() }),
            now = { harness.clock.now() },
            workspace = zones,
        )

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
    fun `같은 구역의 두 기체는 동시에 통과하지 않는다`() {
        // 축이 없을 때는 둘 다 접수됐다. 반경이 겹치는 두 단위가 물리적으로 동시에 나가는 자리다.
        World(Zones(mapOf(PUMP to CELL_NORTH, VALVE to CELL_NORTH))).use { w ->
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol("PATROL-1", PUMP), A))

            val second = assertIs<Middleware.Submission.Rejected>(w.mw.submit(patrol("PATROL-2", VALVE), B))
            assertTrue(CELL_NORTH in second.reason, second.reason)
            assertTrue(A in second.reason, "누가 일하고 있는지 적어야 운영자가 기다릴지 고친다: ${second.reason}")
            assertEquals(1, w.mw.executions().size, "막았는데 실행이 생겼다")
        }
    }

    @Test
    fun `다른 구역이면 동시에 통과한다`() {
        // 막는 것은 겹침이지 동시성 자체가 아니다.
        World(Zones(mapOf(PUMP to CELL_NORTH, VALVE to CELL_SOUTH))).use { w ->
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol("PATROL-1", PUMP), A))
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol("PATROL-2", VALVE), B))
        }
    }

    @Test
    fun `같은 기체는 자기 구역에 안 걸린다`() {
        // ★한 기체가 두 자리에 동시에 있을 수 없고, 그 배타는 발신자가 든다. 여기서 또 막으면
        //   한 기체가 같은 구역에서 이어 일하는 것이 불가능해진다.
        World(Zones(mapOf(PUMP to CELL_NORTH, VALVE to CELL_NORTH))).use { w ->
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol("PATROL-1", PUMP), A))
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol("PATROL-2", VALVE), A))
        }
    }

    @Test
    fun `구역을 안 붙이면 막지 않는다`() {
        // 구역을 안 붙인 현장에서 라인이 통째로 서면 이 관문이 곧 꺼진다.
        World(Workspace.None).use { w ->
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol("PATROL-1", PUMP), A))
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol("PATROL-2", PUMP), B))
        }
    }

    @Test
    fun `대장에 없는 자리끼리는 서로를 막지 않는다`() {
        // ★모르는 자리를 «어느 구역도 아니다» 로 묶으면 **구역 밖 자리들이 서로 하나의 구역이 된다** —
        //   대장에 없는 두 자리가 서로를 막는다. 모름은 값이 아니라 없음이다.
        World(Zones(mapOf(PUMP to CELL_NORTH))).use { w ->
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol("PATROL-1", VALVE), A))
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol("PATROL-2", OTHER), B))
        }
    }

    @Test
    fun `대장에 없는 자리는 선언된 구역에 안 걸린다`() {
        World(Zones(mapOf(PUMP to CELL_NORTH))).use { w ->
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol("PATROL-1", PUMP), A))
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol("PATROL-2", VALVE), B))
        }
    }

    @Test
    fun `끝난 실행은 구역을 놓는다`() {
        // 끝난 일이 구역을 영원히 물고 있으면 그 구역은 다시 못 쓴다 — 자리 점유와 같은 규칙이다.
        World(Zones(mapOf(PUMP to CELL_NORTH, VALVE to CELL_NORTH))).use { w ->
            val first = assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol("PATROL-1", PUMP), A)).execution
            w.drive { first.physicalState.isSettled }

            assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol("PATROL-2", VALVE), B))
        }
    }

    @Test
    fun `갈 자리도 구역을 차지한다`() {
        // ★슬롯 점유는 «아는 것을 놓는 자리» 만 세지만 공간은 다르다 — 놓을 자리든 갈 자리든
        //   기체가 그 공간을 차지한다. 자원의 성질이 달라 세는 법도 다르다.
        //   점검 순회의 `navigate_to` 는 자재를 안 나르지만 구역은 차지한다.
        World(Zones(mapOf(PUMP to CELL_NORTH, VALVE to CELL_NORTH))).use { w ->
            val first = assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol("PATROL-1", PUMP), A)).execution
            assertTrue(first.units.all { it.expectedIdentity == null }, "이 시험이 보려는 경우가 아니다")

            assertIs<Middleware.Submission.Rejected>(w.mw.submit(patrol("PATROL-2", VALVE), B))
        }
    }

    // ── 순수 판정

    @Test
    fun `안 붙인 구역 대장은 모든 자리를 모른다고 답한다`() {
        assertNull(Workspace.None.zoneOf(PUMP))
    }

    companion object {
        const val A = "hum-02"
        const val B = "hum-03"
        const val PUMP = "PUMP-ROOM-1"
        const val VALVE = "VALVE-BAY-2"
        const val CELL_NORTH = "CELL-204.NORTH"
        const val CELL_SOUTH = "CELL-204.SOUTH"
        const val OTHER = "STAGING-9"

        private val PRECOND: Path = Path.of("..", "profile", "fixtures", "precondition.json").normalize()

        private fun patrol(jobOrderId: String, where: String) = JobOrder(
            jobOrderId = jobOrderId,
            workMasterId = InspectAsset.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E0,
            equipmentRequirements = listOf(
                EquipmentRequirement(
                    "ASSET-$where",
                    EquipmentUse.INSPECTION_TARGET,
                    mapOf(EquipmentUse.PROP_LOCATION to where),
                ),
            ),
        )
    }
}
