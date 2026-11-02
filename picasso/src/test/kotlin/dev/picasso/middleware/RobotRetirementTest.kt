package dev.picasso.middleware

import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.harness.Harness
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **기체 퇴역 — 새 발행만 막고 도는 단위는 끊지 않는다**(§15.249).
 *
 * 퇴역은 원장의 운영자 판단이고 제어 명령이 아니다(`docs/commissioning.md` §5). 그래서 시험은 «무엇이 나갔고 무엇이 안 나갔나»
 * 를 기체에 나간 `StartTask`·`CancelTask` 로 센다 — 실행의 상태만 보면 안 나간 명령과 나갔다가 거절된 명령이 같아 보인다.
 *
 * 기체는 4족 픽스처 `quadruped-b` 셋, 업무는 점검 순회(지점마다 이동 뒤 점검)다. 퇴역의 출처는 [Ledger] 이고 원장의 퇴역 표를
 * 그대로 돌려준다.
 */
class RobotRetirementTest {

    /** 원장의 퇴역 표. [failing] 이면 원장에 못 닿는다. */
    private class Ledger : RetirementPort {
        val retired = mutableMapOf<String, String>()
        var failing = false

        override fun retired(): Map<String, String> {
            if (failing) throw IllegalStateException("원장에 못 닿았다")
            return retired.toMap()
        }
    }

    /** 기체에 나간 `StartTask`·`CancelTask` 를 센다 — 응답이 오든 말든, 요청이 나간 것만. */
    private class Wire(private val delegate: RobotPort) : RobotPort by delegate {
        val starts = mutableListOf<Pair<String, String>>()
        val cancels = mutableListOf<String>()

        override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse {
            starts += robotId to taskId
            return delegate.start(robotId, taskId, revision, skillType, parameters)
        }

        override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse {
            cancels += handle.taskId
            return delegate.cancel(robotId, handle)
        }
    }

    private class World(policy: ReassignPolicy = ReassignPolicy()) : AutoCloseable {
        val harness = Harness(mapOf(A to QUAD, B to QUAD, C to QUAD))
        val ledger = Ledger()
        val wire = Wire(ClientRobotPort(harness.client()))
        val mw = Middleware(wire, now = { harness.clock.now() }, reassignPolicy = policy, retirements = ledger)

        fun accept(id: String = ORDER, robot: String = A) =
            assertIs<Middleware.Submission.Accepted>(mw.submit(order(id), robot)).execution

        fun pumps(n: Int) = repeat(n) {
            mw.pump()
            harness.advance(Duration.ofSeconds(1))
        }

        fun drive(rounds: Int = 120, until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(Duration.ofSeconds(10))
                Thread.sleep(30)
                mw.pump()
                if (until()) return
            }
            error("조건에 못 미쳤다")
        }

        override fun close() = harness.close()
    }

    private fun Middleware.Execution.unit(unitId: String) = units.first { it.unitId == unitId }

    @Test
    fun `퇴역한 기체에는 첫 단위를 내지 않고 퇴역 사유와 풀릴 근거가 막음으로 보인다`() {
        World().use { w ->
            w.ledger.retired[A] = REASON
            val exec = w.accept()
            w.pumps(4)

            assertEquals(emptyList(), w.wire.starts, "퇴역한 기체에 StartTask 가 나갔다")
            // 실패가 아니다 — 기다린다.
            assertEquals(UnitState.PENDING, exec.unit(TRAVEL).state)
            assertEquals(emptyList(), w.mw.incidents(), "기다림이 인시던트로 섰다")

            val block = assertNotNull(exec.permitBlock, "막음이 안 보인다")
            assertEquals(WithheldIssue.START, block.withheld)
            assertEquals(listOf(A), block.robotIds)
            val reason = block.robots.single().reasons.single()
            assertEquals(PermitCause.RETIRED, reason.cause)
            assertEquals(REASON, reason.subject, "퇴역 사유가 안 실렸다")
            assertTrue("퇴역" in reason.resumeBasis, reason.resumeBasis)
        }
    }

    @Test
    fun `퇴역해도 도는 단위는 끊지 않고 다음 단위부터 막는다`() {
        World().use { w ->
            val exec = w.accept()
            w.mw.pump()
            assertEquals(listOf(A to "$ORDER#$TRAVEL"), w.wire.starts)

            w.ledger.retired[A] = REASON
            w.drive { exec.unit(TRAVEL).state == UnitState.DONE }
            w.pumps(3)

            assertEquals(emptyList(), w.wire.cancels, "퇴역이 도는 단위를 취소했다")
            assertEquals(listOf(A to "$ORDER#$TRAVEL"), w.wire.starts, "퇴역한 기체에 다음 단위가 나갔다")
            assertEquals(UnitState.PENDING, exec.unit(TARGET).state)
            val block = assertNotNull(exec.permitBlock)
            assertEquals(TARGET, block.unitId)
            assertEquals(PermitCause.RETIRED, block.robots.single().reasons.single().cause)
        }
    }

    @Test
    fun `퇴역을 취소하면 막힌 단위가 그때 나간다`() {
        World().use { w ->
            w.ledger.retired[A] = REASON
            val exec = w.accept()
            w.pumps(2)
            assertEquals(emptyList(), w.wire.starts)

            w.ledger.retired.remove(A)
            w.mw.pump()

            assertEquals(listOf(A to "$ORDER#$TRAVEL"), w.wire.starts)
            assertNull(exec.permitBlock, "퇴역을 취소했는데 막음이 남았다")
            assertEquals(1, exec.eventTrail.count { it.kind == Middleware.ISSUE_RESUMED })
        }
    }

    @Test
    fun `원장을 못 읽으면 퇴역으로 막지 않는다`() {
        World().use { w ->
            w.ledger.retired[A] = REASON
            w.ledger.failing = true
            val exec = w.accept()
            w.mw.pump()

            assertEquals(listOf(A to "$ORDER#$TRAVEL"), w.wire.starts, "원장을 못 읽은 것으로 현장을 세웠다")
            assertNull(exec.permitBlock)
        }
    }

    @Test
    fun `재할당은 퇴역한 후보를 거르고 다음 후보로 옮긴다`() {
        World(ReassignPolicy(margin = 0, minHold = Duration.ZERO)).use { w ->
            val exec = w.accept()
            w.accept("LOAD-1") // 이 일을 뺀 A 의 부담 = 1, B·C = 0
            w.ledger.retired[B] = REASON

            // 부담이 같으면 앞 후보가 이긴다 — 거르지 않으면 B 로 간다.
            val moved = assertIs<Reassignment.Moved>(w.mw.reassign(exec.executionId, listOf(B, C)))
            assertEquals(C, moved.to)

            val kept = assertIs<Reassignment.Kept>(w.mw.reassign(exec.executionId, listOf(B)))
            assertEquals(C, kept.on)
            assertTrue("후보가 없다" in kept.reason, kept.reason)
            assertEquals(C, exec.robotId)
        }
    }

    private companion object {
        const val A = "quad-01"
        const val B = "quad-02"
        const val C = "quad-03"
        const val ORDER = "PATROL-9"
        const val TARGET = "PUMP-01"
        const val TRAVEL = "$TARGET${InspectAsset.TRAVEL_SUFFIX}"
        const val REASON = "라인 3 철거"
        val QUAD: Path = Path.of("..", "profile", "profiles", "quadruped-b.json").normalize()

        fun order(id: String) = JobOrder(
            jobOrderId = id,
            workMasterId = InspectAsset.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E0,
            equipmentRequirements = listOf(
                EquipmentRequirement(
                    TARGET, EquipmentUse.INSPECTION_TARGET,
                    mapOf(EquipmentUse.PROP_LOCATION to "$TARGET-BAY", EquipmentUse.PROP_ITEM to "thermal"),
                ),
            ),
        )
    }
}
