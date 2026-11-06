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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * **출발을 잠금 밖으로 보낸다**(§15.237). 포트가 [RobotPort.issue] 로 출발을 받으면 미들웨어는 그 결과를 기다리지 않고 라운드를 끝내고,
 * 다음 라운드에 결과를 받는다. 그동안 걸린 취소와 개정은 결과 뒤로 미룬다 — 같은 기체에 명령 둘을 함께 내지 않는다.
 *
 * 기체는 4족 픽스처 `quadruped-b`, 업무는 점검 순회다. 송신은 [IssuingRobotPort] 가 손으로 돌리는 실행기([Manual])에서 돌린다.
 */
class OffLockStartTest {

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

    /** 하위로 나간 `StartTask`·`CancelTask` 를 세고, [lost] 만큼 다음 `StartTask` 를 하위에 닿게 한 뒤 답을 잃는다. */
    private class Wire(private val delegate: RobotPort) : RobotPort by delegate {
        var lost = 0
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
            cancels += handle.taskId
            return delegate.cancel(robotId, handle)
        }
    }

    private class World : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to Path.of("..", "profile", "profiles", "quadruped-b.json").normalize()))
        val wire = Wire(ClientRobotPort(harness.client()))
        val manual = Manual()
        val port = IssuingRobotPort(wire, now = { harness.clock.now() }, executor = manual)
        val mw = Middleware(port, now = { harness.clock.now() })

        fun tasks() = harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build()).tasksList
        fun state(taskId: String) = tasks().firstOrNull { it.taskId == taskId }?.taskState

        fun submit(version: Int): Middleware.Execution = assertIs<Middleware.Submission.Accepted>(mw.submit(order(version), ROBOT)).execution

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
            error("조건에 못 미쳤다: tasks=${tasks().map { it.taskId to it.taskState }}")
        }

        override fun close() {
            port.close()
            harness.close()
        }
    }

    @Test
    fun `잠금 밖 출발은 결과를 기다리지 않고 다음 라운드에 받는다`() {
        World().use { w ->
            val exec = w.submit(1)
            val first = exec.units.first()
            w.mw.pump()

            assertEquals(UnitState.PENDING, first.state, "출발의 결과를 기다렸다")
            assertEquals(emptyList(), w.wire.delivered, "라운드 안에서 하위로 보냈다")
            assertSame(first, exec.active, "보내는 중인 단위를 도는 단위로 잡지 않았다")
            assertNotNull(exec.inFlight)

            w.manual.run()
            w.mw.pump()
            assertEquals(UnitState.RUNNING, first.state, "다음 라운드에 결과를 받지 않았다")
            assertNull(exec.inFlight)
            assertEquals(listOf(taskOf(first.unitId) to 1), w.wire.delivered, "출발을 두 번 보냈다")
            assertEquals(emptyList(), w.mw.incidents())
        }
    }

    @Test
    fun `보내는 중인 출발은 판단 기록에 낸 것으로 남는다`() {
        World().use { w ->
            val exec = w.submit(1)
            val first = exec.units.first()
            w.mw.pump()
            assertNotNull(exec.inFlight, "시험의 전제: 첫 출발이 보내는 중이다")

            val record = w.mw.judgmentRecord(exec.executionId)!!.units.single { it.unitId == first.unitId }
            assertEquals(taskOf(first.unitId), record.taskId, "보내는 중인 출발을 내지 않은 것으로 적었다 — 재기동 뒤 같은 태스크를 다시 낸다")
            assertNotNull(record.requestedAt)
        }
    }

    @Test
    fun `보내는 중에 걸린 취소는 접수를 본 뒤 그 태스크에 보낸다`() {
        World().use { w ->
            val exec = w.submit(1)
            val first = exec.units.first()
            w.mw.pump()
            assertTrue(w.mw.cancel(exec.executionId))
            assertEquals(emptyList(), w.wire.cancels, "보내는 중에 취소를 보냈다")
            assertTrue(exec.cancelRequested)

            w.manual.run()
            w.mw.pump()
            assertEquals(listOf(taskOf(first.unitId)), w.wire.cancels, "접수를 본 뒤 취소를 보내지 않았다")
            w.drive { !exec.physicalState.pumps }
            assertEquals(emptyList(), w.wire.delivered.filter { it.first == taskOf(INSPECT) }, "취소한 실행이 다음 단위를 냈다")
        }
    }

    @Test
    fun `개정 갱신을 보내는 중에 걸린 취소는 그 결과 뒤에 보낸다`() {
        World().use { w ->
            val exec = w.submit(1)
            val first = exec.units.first()
            w.drive { first.state == UnitState.RUNNING }
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(2), ROBOT))
            assertEquals(InFlight.Kind.UPDATE, exec.inFlight?.kind, "시험의 전제: 갱신이 보내는 중이다")
            assertNotNull(exec.handle)

            assertTrue(w.mw.cancel(exec.executionId))
            assertEquals(emptyList(), w.wire.cancels, "갱신을 보내는 중에 같은 기체에 취소를 함께 냈다")
            w.manual.run()
            w.mw.pump()
            assertEquals(listOf(taskOf(first.unitId)), w.wire.cancels, "갱신의 결과 뒤에 취소를 보내지 않았다")
        }
    }

    @Test
    fun `보내는 중에 온 개정은 단위를 바꾸지 않고 접수 뒤 갱신으로 보낸다`() {
        World().use { w ->
            val exec = w.submit(1)
            val first = exec.units.first()
            w.mw.pump()
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(2), ROBOT))

            assertSame(first, exec.units.first(), "보내는 중인 출발의 단위를 바꿨다 — 결과가 받을 단위를 잃는다")
            assertEquals(2, first.revision)
            assertEquals(1, exec.deferredUpdate, "보내는 중인 리비전을 미룬 갱신으로 두지 않았다")
            assertEquals(emptyList(), w.wire.delivered, "시험의 전제: 아직 아무것도 보내지 않았다")

            w.drive { w.wire.delivered.size >= 2 && exec.inFlight == null }
            assertEquals(listOf(1, 2), w.wire.delivered.filter { it.first == taskOf(first.unitId) }.map { it.second }, "접수 뒤 새 리비전을 갱신으로 보내지 않았다")
            assertNull(exec.deferredUpdate)
            assertEquals(UnitState.RUNNING, first.state)
        }
    }

    @Test
    fun `답 없는 출발의 요청 시각은 실제로 보낸 시각이다`() {
        World().use { w ->
            w.wire.lost = 1
            val exec = w.submit(1)
            val first = exec.units.first()
            w.mw.pump()
            w.harness.advance(Duration.ofSeconds(10))
            val sentAt = w.harness.clock.now()
            w.manual.run()
            w.harness.advance(Duration.ofSeconds(10))
            w.mw.pump()

            assertEquals(UnitState.IN_DOUBT, first.state, "시험의 전제: 답을 잃었다")
            assertEquals(sentAt, first.requestedAt, "건 시각을 요청 시각으로 남겼다 — 미확정 유예가 송신 대기만큼 당겨진다")
        }
    }

    private companion object {
        const val ROBOT = "quad-01"
        const val ORDER = "PATROL-9"
        const val INSPECT = "PUMP-01"

        fun taskOf(unitId: String) = "$ORDER#$unitId"

        fun order(version: Int) = JobOrder(
            jobOrderId = ORDER,
            workMasterId = InspectAsset.WORK_MASTER,
            version = version,
            requiredEvidence = Evidence.E0,
            parameters = mapOf("shift" to "night"),
            equipmentRequirements = listOf("PUMP-ROOM" to "PUMP-01", "SWITCHGEAR" to "PANEL-3").map { (location, target) ->
                EquipmentRequirement(target, EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to location, EquipmentUse.PROP_ITEM to "thermal"))
            },
        )
    }
}
