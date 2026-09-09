package dev.picasso.mimic.engine

import dev.picasso.contracts.v1.Fault
import dev.picasso.capability.CapabilityProjection
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 완료 기준 8c의 전수 축 — **래치 위반은 종착에서만 성립한다**.
 *
 * 하네스는 소비자가 보는 것을 보고(`TerminalViolationTest`), 여기서는
 * **상태 열 개를 통째로** 돈다. 표면으로는 여섯 비종착에 다 갈 수 없다 —
 * 갈 수 있는 것만 보면 나머지가 침묵한다.
 */
class TerminalLatchTest {

    private val document = TaskMachineFixtures.document()
    private val listener = CollectingListener()

    private fun host(clock: Clock = VirtualClock(Instant.EPOCH)) = TaskHost(
        CapabilityProjection.of(document), document, clock,
        listener, FaultRegistry(clock), Seeded(0),
    )

    /** `navigate_to` 하나를 [state]로 몬다. */
    private fun at(state: TaskState, tasks: TaskHost = host()): Pair<TaskHost, TaskRuntime> {
        tasks.start("t1", 1, "navigate_to", listOf(TaskMachineFixtures.param("location", "d")))
        val task = tasks.find("t1")!!
        if (state != TaskState.ACCEPTED) {
            tasks.tick()
            TaskMachineFixtures.driveTo(task, state)
        }
        check(task.machine.state == state) { "$state 로 못 몰았다: ${task.machine.state}" }
        return tasks to task
    }

    @Test
    fun `상태 열 개마다 위반이 성립하는지 통째로 확인한다`() {
        // **종착 넷만 받는다.** 하나만 보면 "언제나 받는다"와 "절대 안
        // 받는다"가 둘 다 통과한다.
        val terminal = TaskState.entries.filter { it.isTerminal }
        assertEquals(4, terminal.size, "종착이 넷이 아니다: $terminal")
        assertEquals(6, TaskState.entries.size - terminal.size, "비종착이 여섯이 아니다")

        TaskState.entries.forEach { state ->
            val (tasks, task) = at(state)
            val outcome = tasks.forceTerminalViolation("t1")

            if (state.isTerminal) {
                assertTrue(outcome is ViolationOutcome.Seen, "$state 에서 거절했다: $outcome")
                assertEquals(state, outcome.state, "$state")
                assertTrue(outcome.raised, "$state 에서 결함이 안 섰다")
                assertTrue(task.terminalViolationSeen, "$state 에서 표시가 안 섰다")
            } else {
                assertTrue(outcome is ViolationOutcome.Rejected, "$state 에서 받았다: $outcome")
                assertTrue(!task.terminalViolationSeen, "$state 에서 표시가 섰다")
            }
            // **어느 쪽이든 상태는 안 바뀐다.** 위반은 관측이지 전이가 아니다.
            assertEquals(state, task.machine.state, "$state 에서 상태가 바뀌었다")
        }
    }

    @Test
    fun `위반은 전이를 만들지 않는다`() {
        // 상태만 보면 "안 바뀌었다"가 참인데 **전이 이벤트는 나가는** 구현이
        // 통과한다 — 소비자는 종착 → 종착의 유령 전이를 본다.
        val (tasks, _) = at(TaskState.SUCCEEDED)
        listener.events.clear()

        tasks.forceTerminalViolation("t1")

        assertEquals(
            listOf("fault TERMINAL_STATE_VIOLATED"), listener.events,
            "전이가 함께 나갔다: ${listener.events}",
        )
    }

    @Test
    fun `종착 넷이 각각 위반을 만들 수 있다`() {
        // 위 표가 `SUCCEEDED` 하나로 통과하지 않게 한다 — 넷을 따로 센다.
        val seen = TaskState.entries.filter { it.isTerminal }.map { state ->
            val (tasks, _) = at(state)
            state to (tasks.forceTerminalViolation("t1") as ViolationOutcome.Seen).state
        }
        assertEquals(4, seen.size)
        assertEquals(seen.map { it.first }, seen.map { it.second })
    }

    @Test
    fun `모르는 태스크는 못 찾는다`() {
        val (tasks, _) = at(TaskState.SUCCEEDED)
        assertTrue(tasks.forceTerminalViolation("nope") is ViolationOutcome.NotFound)
    }
}

/** 결함과 전이를 순서대로 모은다. */
private class CollectingListener : EngineListener {

    val events = mutableListOf<String>()

    override fun onTaskTransition(
        taskId: String,
        skillType: String,
        from: TaskState?,
        to: TaskState,
        revision: Int,
        attempt: Int,
    ) {
        events += "task ${from?.name ?: "NEW"}->$to"
    }

    override fun onFault(fault: Fault, cleared: Boolean) {
        events += "${if (cleared) "clear" else "fault"} ${fault.errorType}"
    }
}
