package dev.picasso.middleware

import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **취소를 잠금 밖으로 보낸다**(§15.237). 포트가 [RobotPort.issueCancel] 로 취소를 받으면 미들웨어는 그 답을 기다리지 않고, 다음 라운드
 * 머리에서 받는다. 받기 전까지 그 실행의 라운드는 진행하지 않는다 — 단위의 종료와 중단 보고가 취소의 답에 달렸다.
 *
 * 기체는 4족 픽스처 `quadruped-b`, 업무는 점검 순회다. 송신은 [IssuingRobotPort] 가 손으로 돌리는 실행기([Manual])에서 돌린다.
 */
class OffLockCancelTest {

    /** 넘겨받은 송신을 쌓아 두었다가 [run] 이 돌린다. */
    private class Manual : Executor {
        val queued = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            queued += command
        }

        fun run() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }

    /**
     * 하위로 나간 `StartTask`·`CancelTask` 를 센다. [lost] 만큼 다음 `StartTask` 를 하위에 닿게 한 뒤 답을 잃고, [cancelFailures] 만큼
     * 다음 `CancelTask` 를 하위에 닿지 않게 하고 던진다.
     */
    private class Wire(private val delegate: RobotPort) : RobotPort by delegate {
        var lost = 0
        var cancelFailures = 0
        val delivered = mutableListOf<Pair<String, Int>>()
        val cancels = mutableListOf<String>()

        override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse {
            delivered += taskId to revision
            val response = delegate.start(robotId, taskId, revision, skillType, parameters)
            if (lost > 0) {
                lost -= 1
                throw IllegalStateException("response lost after the request reached the robot")
            }
            return response
        }

        override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse {
            if (cancelFailures > 0) {
                cancelFailures -= 1
                throw IllegalStateException("시험: 취소가 하위에 닿지 않았다")
            }
            cancels += handle.taskId
            return delegate.cancel(robotId, handle)
        }
    }

    /** [blind] 면 스냅숏을 못 읽는 기체다 — 미확정 태스크에 스냅숏으로 붙지 못한다. */
    private class World(blind: Boolean = false) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to Path.of("..", "profile", "profiles", "quadruped-b.json").normalize()))
        val wire = Wire(ClientRobotPort(harness.client()).let { if (blind) FaultBlindRobotPort(it) else it })
        val manual = Manual()
        val port = IssuingRobotPort(wire, now = { harness.clock.now() }, executor = manual)
        val mw = Middleware(port, now = { harness.clock.now() })

        fun state(taskId: String) = harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build())
            .tasksList.firstOrNull { it.taskId == taskId }?.taskState

        fun submit(): Middleware.Execution = assertIs<Middleware.Submission.Accepted>(mw.submit(order(), ROBOT)).execution

        /** 한 라운드 펌프하고, 쌓인 송신을 보내고, 시계를 민다. */
        fun round() {
            mw.pump()
            manual.run()
            harness.advance(Duration.ofSeconds(10))
            Thread.sleep(30)
        }

        fun drive(rounds: Int = 60, until: () -> Boolean) {
            repeat(rounds) {
                round()
                if (until()) return
            }
            error("조건에 못 미쳤다")
        }

        override fun close() {
            port.close()
            harness.close()
        }
    }

    @Test
    fun `잠금 밖 취소는 답을 기다리지 않고 다음 라운드에 받는다`() {
        World().use { w ->
            val exec = w.submit()
            val first = exec.units.first()
            w.drive { first.state == UnitState.RUNNING }

            assertTrue(w.mw.cancel(exec.executionId))
            assertEquals(emptyList(), w.wire.cancels, "잠금 아래에서 취소를 보냈다")
            assertEquals(CancelFlight.Site.SEND, exec.cancelInFlight?.site)
            w.mw.pump()
            assertNotNull(exec.cancelInFlight, "보내지 않은 취소의 답을 받았다")

            w.manual.run()
            assertEquals(listOf(taskOf(first.unitId)), w.wire.cancels)
            w.mw.pump()
            assertNull(exec.cancelInFlight, "다음 라운드에 답을 받지 않았다")
            w.drive { !exec.physicalState.pumps }
            assertEquals(1, w.wire.cancels.size, "취소를 두 번 보냈다")
            assertEquals(emptyList(), w.wire.delivered.filter { it.first == taskOf(INSPECT) }, "취소한 실행이 다음 단위를 냈다")
        }
    }

    @Test
    fun `닿지 못한 잠금 밖 취소는 다음 라운드에 다시 보낸다`() {
        World().use { w ->
            val exec = w.submit()
            val first = exec.units.first()
            w.drive { first.state == UnitState.RUNNING }
            w.wire.cancelFailures = 1

            assertTrue(w.mw.cancel(exec.executionId))
            w.manual.run()
            w.mw.pump()
            assertTrue(exec.cancelUnsent, "닿지 못한 취소를 닿은 것으로 읽었다")
            assertTrue(exec.eventTrail.any { it.kind == Middleware.CANCEL_UNSENT })
            assertNotNull(exec.cancelInFlight, "닿지 못한 취소를 다시 걸지 않았다")

            w.manual.run()
            w.mw.pump()
            assertFalse(exec.cancelUnsent, "다시 보낸 취소가 닿았는데 표시를 내리지 않았다")
            assertTrue(exec.eventTrail.any { it.kind == Middleware.CANCEL_RESENT })
            assertEquals(listOf(taskOf(first.unitId)), w.wire.cancels)
        }
    }

    @Test
    fun `다시 보내는 취소가 나가는 중에 끝난 단위는 그 답을 받은 뒤 중단을 적는다`() {
        World().use { w ->
            val exec = w.submit()
            val first = exec.units.first()
            w.drive { first.state == UnitState.RUNNING }
            w.wire.cancelFailures = 1
            assertTrue(w.mw.cancel(exec.executionId))
            w.manual.run()
            repeat(30) {
                if (w.state(taskOf(first.unitId)) == "SUCCEEDED") return@repeat
                w.harness.advance(Duration.ofSeconds(10))
                Thread.sleep(30)
            }
            assertEquals("SUCCEEDED", w.state(taskOf(first.unitId)), "시험의 전제: 닿지 못한 취소 뒤 태스크가 끝났다")

            w.mw.pump()
            assertTrue(exec.physicalState.pumps, "다시 보낸 취소의 답을 받기 전에 중단을 적었다")
            w.manual.run()
            w.drive { !exec.physicalState.pumps }
            val report = assertNotNull(w.mw.lastCancel(exec.executionId))
            assertTrue(report.refusal != Middleware.CANCEL_NOT_SENT, "닿은 취소를 닿지 못했다고 적었다: ${report.refusal}")
        }
    }

    /**
     * 잠금 밖 취소가 남은 채 실행이 끝나는 길은 운영자 판단이다(보류 중 다시 보내기가 보내는 중에 운영자가 단위를 판단한다). 여기서는 실행의
     * 상태를 직접 끝내 그 뒤만 본다.
     */
    @Test
    fun `끝난 실행에 남은 취소는 이빅션을 막고 다음 라운드에 차선을 놓는다`() {
        World().use { w ->
            val exec = w.submit()
            val first = exec.units.first()
            w.drive { first.state == UnitState.RUNNING }
            assertTrue(w.mw.cancel(exec.executionId))
            exec.physicalState = PhysicalState.ABORTED

            val refused = assertIs<ForgetOutcome.Refused>(w.mw.forget(exec.executionId))
            assertTrue("취소" in refused.reason, refused.reason)

            w.manual.run()
            w.mw.pump()
            assertNull(exec.cancelInFlight, "끝난 실행의 취소 결과를 받지 않았다 — 그 기체의 차선이 풀리지 않는다")
            assertEquals(Issue.Pending, w.port.issue(ROBOT, "probe", 1, "INSPECT", emptyMap()), "끝난 실행의 취소가 차선을 쥐고 있다")
        }
    }

    @Test
    fun `키로 만든 핸들의 잠금 밖 취소는 답을 받은 뒤 미확정 단위를 붙인다`() {
        World(blind = true).use { w ->
            w.wire.lost = 1
            val exec = w.submit()
            val first = exec.units.first()
            w.mw.pump()
            w.manual.run()
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, first.state, "시험의 전제: 첫 출발의 답을 잃었다")

            assertTrue(w.mw.cancel(exec.executionId))
            w.mw.pump()
            assertEquals(CancelFlight.Site.KEY, exec.cancelInFlight?.site, "키로 만든 핸들의 취소를 잠금 밖으로 걸지 않았다")
            assertEquals(UnitState.IN_DOUBT, first.state, "답을 받기 전에 단위를 정했다")

            w.manual.run()
            w.mw.pump()
            assertNull(exec.cancelInFlight)
            assertTrue("resolved by keyed cancel" in first.note.orEmpty(), "답을 받고도 키 취소로 붙지 않았다: ${first.note}")
            assertEquals(listOf(taskOf(first.unitId)), w.wire.cancels)
        }
    }

    @Test
    fun `유예가 지난 미확정 단위도 키로 만든 핸들의 취소가 보내는 중이면 운영자 보류에 두지 않는다`() {
        World(blind = true).use { w ->
            w.wire.lost = 1
            val exec = w.submit()
            val first = exec.units.first()
            w.mw.pump()
            w.manual.run()
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, first.state, "시험의 전제: 첫 출발의 답을 잃었다")
            w.harness.advance(Duration.ofSeconds(70))
            assertEquals("RUNNING", w.state(taskOf(first.unitId)), "시험의 전제: 기체의 태스크가 아직 돈다")

            assertTrue(w.mw.cancel(exec.executionId))
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, first.state, "취소의 답을 받기 전에 유예가 지났다고 운영자 보류에 뒀다 — 답이 와도 붙을 자리가 없다")

            w.manual.run()
            w.mw.pump()
            assertTrue("resolved by keyed cancel" in first.note.orEmpty(), "답을 받고도 키 취소로 붙지 않았다: ${first.note}")
        }
    }

    private companion object {
        const val ROBOT = "quad-01"
        const val ORDER = "PATROL-9"
        const val INSPECT = "PUMP-01"

        fun taskOf(unitId: String) = "$ORDER#$unitId"

        fun order() = JobOrder(
            jobOrderId = ORDER,
            workMasterId = InspectAsset.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E0,
            parameters = mapOf("shift" to "night"),
            equipmentRequirements = listOf("PUMP-ROOM" to "PUMP-01", "SWITCHGEAR" to "PANEL-3").map { (location, target) ->
                EquipmentRequirement(target, EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to location, EquipmentUse.PROP_ITEM to "thermal"))
            },
        )
    }
}
