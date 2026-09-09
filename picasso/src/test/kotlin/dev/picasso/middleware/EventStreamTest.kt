package dev.picasso.middleware

import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import dev.picasso.mimic.control.v1.ForceFaultRequest
import dev.picasso.mimic.control.v1.SetConnectionRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 이벤트 스트림 소비 — 계약 §4.8 의 소비자 패턴(스냅샷으로 세우고 재생으로 이어 붙이고, 벗어나면 다시 세운다)을
 * 실행 층이 든다. 보고서 17장 9번(단절 후 재동기화)과 7장의 *실행 추적·이벤트 전달·감사 기록*.
 *
 * 앞 판은 다음 단위 앞에서 스냅샷 한 번만 물었다(§15.94). 이제 펌프마다 스냅샷(현재값)과 재생(그 사이의 사실)을
 * 읽어 — 결함 현재값으로 문을 세우고, 연결이 끊기면 도는 단위를 미확정으로 두며, 이벤트를 실행의 자취에 남긴다.
 */
class EventStreamTest {

    private fun order() = JobOrder(
        jobOrderId = "SEQ-208",
        workMasterId = PrepareSequencedRack.WORK_MASTER,
        version = 1,
        requiredEvidence = Evidence.E0,
        equipmentRequirements = listOf(
            EquipmentRequirement(SLOT, EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to PART)),
            EquipmentRequirement(BIN, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to PART)),
        ),
    )

    private class World(port: (RobotPort) -> RobotPort = { it }) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to Path.of("..", "profile", "fixtures", "minimal.json").normalize()))
        val mw = Middleware(port(ClientRobotPort(harness.client())), now = { harness.clock.now() })

        fun tasks() = harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build()).tasksList
        fun state(taskId: String) = tasks().firstOrNull { it.taskId == taskId }?.taskState

        fun setConnection(state: ConnectionState) = harness.oracle.setConnection(
            SetConnectionRequest.newBuilder().setRobotId(ROBOT).setState(state.name).build(),
        )

        fun forceFault(errorType: String, taskId: String) = harness.oracle.forceFault(
            ForceFaultRequest.newBuilder().setRobotId(ROBOT).setErrorType(errorType).setTaskId(taskId).build(),
        ).taskState

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
    private val task = "SEQ-208#$SLOT"

    @Test
    fun `전이와 결함이 실행의 자취에 남는다 — 발행 열의 번호와 시각 그대로`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { w.state(task) == "RUNNING" }
            w.forceFault("SKILL_EXECUTION_FAILED", task) // 스킬 수준, can_accept_new_task=true — 문은 안 선다
            w.drive { exec.settled() }

            val kinds = exec.eventTrail.map { it.kind }
            assertEquals("RESYNC", kinds.first(), "첫 관측은 스냅샷으로 세운다")
            assertTrue(kinds.contains("FAULT_RAISED"), kinds.toString())
            assertTrue(exec.eventTrail.any { it.kind == "TASK_TRANSITION" && it.detail.contains("$task TASK_STATE_ACCEPTED->TASK_STATE_RUNNING") }, exec.eventTrail.toString())
            assertTrue(exec.eventTrail.any { it.kind == "TASK_TRANSITION" && it.detail.contains("->TASK_STATE_RETRIABLE") }, exec.eventTrail.toString())
            val raised = exec.eventTrail.single { it.kind == "FAULT_RAISED" }
            assertTrue(raised.detail.startsWith("SKILL_EXECUTION_FAILED class=GRASP_FAILED"), raised.detail)
            assertTrue(raised.occurredAt.isNotBlank(), "발행 열의 시각을 다시 찍지 않고 그대로 든다")
            // 번호는 단조 증가 — 재생을 이어 붙인 것이지 다시 세운 것이 아니다.
            val contractSeqs = exec.eventTrail.filter { it.kind != "RESYNC" }.map { it.sequence }
            assertEquals(contractSeqs.sorted(), contractSeqs)
            assertEquals(contractSeqs.distinct(), contractSeqs, "같은 이벤트를 두 번 자취에 남겼다 — 커서가 안 나아갔다")
            val view = w.mw.view(ROBOT)!!
            assertEquals(1, view.resyncs)
            assertTrue(view.eventsSeen >= contractSeqs.size.toLong())
        }
    }

    @Test
    fun `연결이 끊기면 도는 단위는 미확정이고, 돌아오면 스냅샷으로 다시 세워 이어 간다 — 재실행 없음`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { w.state(task) == "RUNNING" }

            w.setConnection(ConnectionState.CONNECTION_STATE_CONNECTION_BROKEN)
            w.mw.pump()
            assertEquals(PhysicalState.IN_DOUBT, exec.physicalState)
            assertEquals(UnitState.RUNNING, exec.unit().state, "단위는 도는 중이다 — 미확정인 것은 실행의 결과다")
            val told = w.mw.pending().last()
            assertEquals(PhysicalState.IN_DOUBT, told.physicalState)
            assertEquals("CONNECTION_STATE_CONNECTION_BROKEN", told.connection)
            assertTrue(exec.eventTrail.any { it.kind == "LINK_BROKEN" })

            // 끊긴 동안 시간이 간다 — 여전히 미확정이고 아무것도 다시 보내지 않는다.
            w.harness.advance(Duration.ofSeconds(10)); w.mw.pump()
            assertEquals(PhysicalState.IN_DOUBT, exec.physicalState)
            assertEquals(1, w.tasks().size)

            w.setConnection(ConnectionState.CONNECTION_STATE_ONLINE)
            w.mw.pump()
            assertTrue(exec.eventTrail.any { it.kind == "LINK_RESTORED" && it.detail.contains("snapshot says $task=") }, exec.eventTrail.toString())
            assertTrue(exec.physicalState == PhysicalState.RUNNING || exec.physicalState.isSettled)
            assertEquals("CONNECTION_STATE_ONLINE", w.mw.pending().last().connection)

            w.drive { exec.settled() }
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(1, w.tasks().size, "단절이 재실행을 만들었다")
        }
    }

    @Test
    fun `절전은 단절이 아니다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { w.state(task) == "RUNNING" }
            w.setConnection(ConnectionState.CONNECTION_STATE_HIBERNATING)
            w.mw.pump()
            assertEquals(PhysicalState.RUNNING, exec.physicalState)
            assertTrue(exec.eventTrail.none { it.kind == "LINK_BROKEN" })
        }
    }

    @Test
    fun `재생 버퍼를 벗어나면 스냅샷부터 다시 세운다`() {
        World(port = { EvictingRobotPort(it) }).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { exec.settled() }

            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            val view = w.mw.view(ROBOT)!!
            assertEquals(2, view.resyncs, "첫 세움 + 축출 뒤 한 번")
            assertTrue(exec.eventTrail.any { it.kind == "RESYNC" && it.detail.contains("replay evicted") }, exec.eventTrail.toString())
            // 다시 세운 뒤에도 이어 붙는다 — 그 뒤의 전이가 자취에 있다.
            assertTrue(exec.eventTrail.any { it.kind == "TASK_TRANSITION" && it.detail.contains("->TASK_STATE_SUCCEEDED") }, exec.eventTrail.toString())
        }
    }

    @Test
    fun `기체를 못 보면 문도 자취도 없이 간다 — 그러나 못 봤다는 것은 남는다`() {
        World(port = { FaultBlindRobotPort(it) }).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { exec.settled() }
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(false, w.mw.view(ROBOT)!!.observable)
            assertTrue(exec.eventTrail.isEmpty())
            assertTrue(exec.unit().note!!.contains("not observable"), exec.unit().note)
            assertEquals("CONNECTION_STATE_UNSPECIFIED", w.mw.pending().last().connection, "못 봤으면 ONLINE 이라고 하지 않는다")
        }
    }

    private companion object {
        const val ROBOT = "hum-02"
        const val SLOT = "RACK-208.S01"
        const val BIN = "SEQ-IN-02.BIN-A"
        const val PART = "ENGINE-COVER-A"
    }
}
