package dev.picasso.middleware

import dev.picasso.harness.Harness
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **다시 지은 실행에 붙기 전에 건 취소의 플릿 쪽**(§15.254). 다시 짓기는 기체의 스냅숏만 읽고 플릿은 읽지 않는다 — 재기동 전에 맡긴
 * 운반은 다시 지은 실행에 붙지 않은 채 플릿에서 돈다. 그 사이 취소가 걸려 있으면 그 운반을 찾아 멈춘 뒤 정착해야 한다. 붙지 않은 채
 * «지울 것 없음» 으로 정착하면 운반이 도는 동안 실행이 끝난다.
 *
 * 앞 인스턴스의 취소는 플릿에 닿지 않았다([FlakyCancelFleet]). 건 취소는 판단 기록이 들고 온다(§15.253). 두 인스턴스가 같은 플릿
 * ([AmrFleetMimic])을 본다. 업무는 용기 공급(운반 전체를 플릿에 맡기는 단위 하나)이다.
 */
class ResumeFleetCancelTest {

    private class World : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to Path.of("..", "profile", "fixtures", "minimal.json").normalize()))
        val fleet = AmrFleetMimic(now = { harness.clock.now() })
        val cell = CellMimic(now = { harness.clock.now() }, live = { loc -> fleet.containersAt[loc]?.let { it to fleet.placedAt[loc] } })

        /** 앞 인스턴스 — 취소가 플릿에 닿지 않는다. */
        val mw = Middleware(ClientRobotPort(harness.client()), cell, FlakyCancelFleet(fleet), now = { harness.clock.now() })

        init {
            fleet.containersAt[SOURCE] = CONTAINER
        }

        /** 다시 뜬 인스턴스 — 같은 플릿, 닿는 취소. */
        fun restart(port: AmrFleetPort = fleet) = Middleware(ClientRobotPort(harness.client()), cell, port, now = { harness.clock.now() })

        fun drive(mw: Middleware, rounds: Int = 12, until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(Duration.ofSeconds(10))
                fleet.tick()
            }
            error("조건에 못 미쳤다: 세계=${fleet.containersAt}")
        }

        /** 운반을 맡긴 뒤 취소를 걸고 그 직후의 판단 기록을 받아 둔다 — 취소는 플릿에 닿지 않았고 앞 인스턴스는 여기서 죽었다. */
        fun cancelUnreachedThenDie(): JudgmentRecord {
            val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(order(), ROBOT)).execution
            drive(mw) { exec.units.single().state == UnitState.RUNNING }
            assertTrue(mw.cancel(exec.executionId))
            assertEquals(TransportState.ACCEPTED, fleet.status(TRANSPORT).state, "시험의 전제: 취소가 플릿에 닿지 않았다")
            return assertNotNull(mw.judgmentRecord(exec.executionId)).also { assertTrue(it.cancelRequested, "시험의 전제: 기록이 건 취소를 실었다") }
        }

        override fun close() = harness.close()
    }

    private fun Middleware.rebuild(record: JudgmentRecord): Middleware.Execution =
        assertIs<Middleware.Submission.Accepted>(resume(order(), ROBOT, ActiveMission(DeliverContainer(), null), record)).execution

    @Test
    fun `판단 기록이 건 취소를 들고 오면 맡긴 운반을 새로 맡기지 않고 찾아 멈춘 뒤 중단으로 종결한다`() {
        World().use { w ->
            val record = w.cancelUnreachedThenDie()
            val again = w.restart()
            val rebuilt = again.rebuild(record)
            w.drive(again) { !rebuilt.physicalState.pumps }

            assertEquals(TransportState.CANCELLED, w.fleet.status(TRANSPORT).state, "플릿의 운반이 멈추지 않았다 — 운반이 도는 동안 실행이 끝났다")
            assertEquals(1, w.fleet.dispatches, "멈추려고 붙는 자리에서 운반을 새로 맡겼다")
            assertEquals(PhysicalState.ABORTED, rebuilt.physicalState)
            assertEquals(UnitState.ABORTED, rebuilt.units.single().state, "플릿의 취소 종료로 단위를 판정하지 않았다")
            assertEquals(CONTAINER, assertNotNull(again.lastCancel(rebuilt.executionId)).inProgressUnit)
            assertEquals(emptyList(), again.incidents(), "요청한 취소를 요청 없는 취소로 읽었다")
            assertTrue(rebuilt.eventTrail.any { it.kind == Middleware.CANCEL_ATTACHED }, "붙은 사실을 자취에 남기지 않았다: ${rebuilt.eventTrail}")
        }
    }

    @Test
    fun `판단 기록이 건 취소를 들고 와도 맡기지 않은 운반은 찾기만 하고 새로 맡기지 않은 채 중단으로 종결한다`() {
        World().use { w ->
            // 맡기기 전에 취소가 걸렸고 앞 인스턴스는 여기서 죽었다.
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            assertTrue(w.mw.cancel(exec.executionId))
            val record = assertNotNull(w.mw.judgmentRecord(exec.executionId))

            val again = w.restart()
            val rebuilt = again.rebuild(record)
            w.drive(again) { !rebuilt.physicalState.pumps }

            assertEquals(0, w.fleet.dispatches, "찾으려다 운반을 맡겼다")
            assertEquals(PhysicalState.ABORTED, rebuilt.physicalState)
            assertEquals("nothing_to_clean", assertNotNull(again.lastCancel(rebuilt.executionId)).cleanup)
        }
    }

    @Test
    fun `조회가 없는 플릿에 맡긴 운반은 건 취소를 들고 다시 지으면 운영자 보류에 두고 새로 맡기지 않는다`() {
        World().use { w ->
            val record = w.cancelUnreachedThenDie()
            val again = w.restart(LossyFleet(w.fleet, dropDispatchResponses = 0, executionLookup = ExecutionLookup.NONE))
            val rebuilt = again.rebuild(record)
            w.drive(again) { rebuilt.physicalState == PhysicalState.OPERATOR_HOLD }

            val unit = rebuilt.units.single()
            assertEquals(UnitState.OPERATOR_HOLD, unit.state, "도는지 볼 길이 없는 운반을 지울 것 없음으로 접었다")
            assertEquals(1, w.fleet.dispatches, "조회가 없는 플릿에 운반을 다시 맡겼다")
            assertEquals(1, again.incidents().size, "보류에 인시던트가 안 열렸다")
            assertTrue(
                rebuilt.eventTrail.any { it.kind == Middleware.CANCEL_UNSENT && Middleware.LOOKUP_UNSENT_CAUSE in it.detail },
                "닿지 못한 까닭을 자취에 남기지 않았다: ${rebuilt.eventTrail}",
            )
        }
    }

    @Test
    fun `조회가 없는 플릿이라도 판단 기록이 맡겼다고 적지 않은 운반 단위는 보류에 두지 않고 중단으로 종결한다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            assertTrue(w.mw.cancel(exec.executionId))
            val record = assertNotNull(w.mw.judgmentRecord(exec.executionId))
            assertEquals(null, record.units.single().requestedAt, "시험의 전제: 기록이 맡겼다고 적지 않았다")

            val again = w.restart(LossyFleet(w.fleet, dropDispatchResponses = 0, executionLookup = ExecutionLookup.NONE))
            val rebuilt = again.rebuild(record)
            w.drive(again) { !rebuilt.physicalState.pumps || rebuilt.physicalState == PhysicalState.OPERATOR_HOLD }

            assertEquals(PhysicalState.ABORTED, rebuilt.physicalState, "맡긴 적 없는 운반을 볼 길이 없다고 보류에 세웠다")
            assertEquals(0, w.fleet.dispatches)
            assertEquals(emptyList(), again.incidents())
        }
    }

    @Test
    fun `플릿 찾기가 미확정 유예 동안 답하지 않으면 건 취소를 운영자 보류에 두고 새로 맡기지 않는다`() {
        World().use { w ->
            val record = w.cancelUnreachedThenDie()
            val again = w.restart(object : AmrFleetPort by w.fleet {
                override fun find(reference: String): TransportHandle? = throw IllegalStateException("fleet unreachable")
            })
            val rebuilt = again.rebuild(record)
            w.drive(again) { rebuilt.physicalState == PhysicalState.OPERATOR_HOLD }

            assertEquals(UnitState.OPERATOR_HOLD, rebuilt.units.single().state, "유예가 지나도 기한 없이 기다렸다")
            assertEquals(1, w.fleet.dispatches, "찾지 못한 운반을 다시 맡겼다")
            assertEquals(1, again.incidents().size, "보류에 인시던트가 안 열렸다")
            assertEquals(1, rebuilt.eventTrail.count { it.kind == Middleware.CANCEL_UNATTACHED_WAIT }, "기다리는 까닭을 한 번 남기지 않았다: ${rebuilt.eventTrail}")
            assertTrue(
                rebuilt.eventTrail.any { it.kind == Middleware.CANCEL_UNSENT && Middleware.UNATTACHED_UNSENT_CAUSE in it.detail },
                "닿지 못한 까닭을 자취에 남기지 않았다: ${rebuilt.eventTrail}",
            )
        }
    }

    private companion object {
        const val ROBOT = "idle-robot"
        const val CONTAINER = "HU-1042"
        const val SOURCE = "OUT-07"
        const val DEST = "SEQ-IN-02"
        val TRANSPORT = TransportHandle("tr-1")

        fun order() = JobOrder(
            jobOrderId = "WT-781",
            workMasterId = DeliverContainer.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E2,
            equipmentRequirements = listOf(
                EquipmentRequirement(SOURCE, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_CONTAINER to CONTAINER)),
                EquipmentRequirement(DEST, EquipmentUse.DESTINATION),
            ),
        )
    }
}
