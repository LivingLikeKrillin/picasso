package dev.picasso.mimic.engine

import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.profile.ProfileDocument
import dev.picasso.mimic.profile.CapabilityProjection
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 엔진이 보고한 것을 순서대로 모은다. */
class RecordingListener : EngineListener {

    val reports = mutableListOf<String>()

    override fun onSkillTransition(taskId: String, skillType: String, from: SkillState, to: SkillState) {
        reports += "skill($taskId,$skillType): $from->$to"
    }

    override fun onTaskTransition(
        taskId: String,
        skillType: String,
        from: TaskState?,
        to: TaskState,
        revision: Int,
        attempt: Int,
    ) {
        reports += "task($taskId,$skillType): ${from?.name ?: "NEW"}->$to r$revision a$attempt"
    }
}

/**
 * §4.7의 이벤트 넷 중 엔진이 아는 둘.
 *
 * **로그에서 되짚어 만들지 않는다는 것이 이 시험의 요점이다.** 스킬 전이는
 * 태스크 로그에 아예 없으므로, 되짚기로는 §4.7의 `SKILL_TRANSITION`을 만들
 * 수 없거나 만들면 그것이 두 번째 진실이 된다.
 */
class EngineListenerTest {

    private val listener = RecordingListener()
    private val clock = VirtualClock(Instant.EPOCH)

    private fun host(document: ProfileDocument = TaskMachineFixtures.document()) =
        TaskHost(CapabilityProjection.of(document), document, clock, listener)

    private fun location(value: String = "dock-3") =
        ParameterValue.newBuilder().setKey("location").setStringValue(value).build()

    @Test
    fun `정상 경로의 전이를 순서대로 전부 보고한다`() {
        // 스킬 전이가 태스크 전이 **뒤에** 온다 — RUNNING으로 간 다음에
        // 스킬을 START하기 때문이다. 순서를 뭉개면 소비자가 세운 상태가
        // 중간 시점에 내부 상태와 어긋난다.
        val tasks = host()
        tasks.start("t1", 1, "navigate_to", listOf(location()))
        tasks.tick()
        clock.advance(Duration.ofSeconds(20))
        tasks.tick()

        assertEquals(
            listOf(
                "task(t1,navigate_to): NEW->ACCEPTED r1 a0",
                "task(t1,navigate_to): ACCEPTED->RUNNING r1 a0",
                "skill(t1,navigate_to): READY->RUNNING",
                "skill(t1,navigate_to): RUNNING->READY",
                "task(t1,navigate_to): RUNNING->SUCCEEDED r1 a0",
            ),
            listener.reports,
        )
    }

    @Test
    fun `접수도 전이로 보고한다`() {
        // 안 알리면 접수만 되고 아직 tick을 안 받은 태스크가 재구성에서
        // 통째로 사라진다. `from`이 없는 것이 접수의 표시다.
        host().start("t1", 1, "navigate_to", listOf(location()))
        assertEquals(listOf("task(t1,navigate_to): NEW->ACCEPTED r1 a0"), listener.reports)
    }

    @Test
    fun `거절된 전이는 보고하지 않는다`() {
        val tasks = host()
        tasks.start("t1", 1, "navigate_to", listOf(location()))
        tasks.tick()
        val before = listener.reports.size

        // RUNNING에서 RESUME은 §4.4 표에 없다.
        val rejected = tasks.find("t1")!!.machine.apply(TaskCommand.RESUME)
        assertTrue(rejected is TaskTransition.Rejected)
        assertEquals(before, listener.reports.size, "안 일어난 일을 보고했다")
    }

    @Test
    fun `거절된 스킬 명령도 보고하지 않는다`() {
        // 태스크는 움직였는데 스킬이 거절하는 경우가 있다 — 예를 들어
        // 이미 READY인 스킬에 RESET을 보내는 경로.
        val tasks = host()
        tasks.start("t1", 1, "navigate_to", listOf(location()))
        tasks.tick()
        clock.advance(Duration.ofSeconds(20))
        tasks.tick()
        val after = listener.reports.size

        // 종착이라 아무 명령도 안 통한다.
        tasks.find("t1")!!.machine.apply(TaskCommand.CANCEL)
        assertEquals(after, listener.reports.size)
    }

    @Test
    fun `여덟 개의 스킬 명령 자리를 전부 지난다`() {
        // TaskMachine이 스킬에 명령을 보내는 자리가 여덟이다. 하나라도
        // 직접 apply를 부르면 그 전이만 조용히 사라진다 — 취소·재시도
        // 경로가 각각 다른 자리를 쓴다.
        val tasks = host()
        tasks.start("t1", 1, "navigate_to", listOf(location()))
        tasks.tick()
        val machine = tasks.find("t1")!!.machine

        machine.apply(TaskCommand.PAUSE)
        machine.apply(TaskCommand.RESUME)
        machine.onSkillHalted(Resolution.SELF_RETRIABLE)
        machine.apply(TaskCommand.RETRY)
        machine.update(2, listOf(location("dock-9")))
        machine.apply(TaskCommand.CANCEL)
        tasks.tick()

        val skills = listener.reports.filter { it.startsWith("skill") }
        // SUSPEND·RESUME·HALT·RESET·START(재시도)·HALT·RESET·START(갱신) ...
        assertTrue(skills.size >= 8, "스킬 전이가 너무 적다:\n${listener.reports.joinToString("\n")}")
        assertTrue(
            skills.any { "RUNNING->SUSPENDED" in it } && skills.any { "SUSPENDED->RUNNING" in it },
            "일시정지·재개가 안 보고됐다",
        )
        assertTrue(skills.any { "->HALTED" in it }, "정지가 안 보고됐다")
        assertTrue(skills.any { "HALTED->READY" in it }, "리셋이 안 보고됐다")
    }

    @Test
    fun `재시도 전이가 올라간 attempt를 싣는다`() {
        // 뒤에 올리면 그 전이 이벤트가 **올리기 전** 값을 싣고, 소비자가
        // "재시도했는데 attempt가 그대로"인 상태를 세운다(§12.2의 8번).
        val tasks = host()
        tasks.start("t1", 1, "navigate_to", listOf(location()))
        tasks.tick()
        val machine = tasks.find("t1")!!.machine
        machine.onSkillHalted(Resolution.SELF_RETRIABLE)
        listener.reports.clear()

        machine.apply(TaskCommand.RETRY)

        val retry = listener.reports.first { it.startsWith("task") }
        assertTrue("a1" in retry, "재시도 전이가 옛 attempt를 실었다: $retry")
        assertEquals(1, machine.attempt)
    }

    @Test
    fun `거절된 재시도는 attempt를 올리지 않는다`() {
        // 전이 전에 올리므로 되돌리는 것을 잊으면 거절만 하고 값이 바뀐다.
        val tasks = host()
        tasks.start("t1", 1, "navigate_to", listOf(location()))
        tasks.tick()
        val machine = tasks.find("t1")!!.machine
        machine.apply(TaskCommand.RETRY) // RUNNING에서는 §4.4 표에 없다
        assertEquals(0, machine.attempt, "거절했는데 attempt가 올랐다")
    }

    @Test
    fun `갱신도 전이로 알린다`() {
        // §4.7에 "revision이 바뀌었다" 이벤트가 따로 없는데 갱신은 상태를
        // 안 바꾼다. 안 알리면 이벤트를 접는 소비자가 옛 revision을 영원히 든다.
        val tasks = host()
        tasks.start("t1", 1, "navigate_to", listOf(location()))
        tasks.tick()
        listener.reports.clear()

        tasks.find("t1")!!.machine.update(2, listOf(location("dock-9")))

        val task = listener.reports.filter { it.startsWith("task") }
        assertEquals(1, task.size, "${listener.reports}")
        assertTrue("RUNNING->RUNNING r2 a0" in task.single(), task.single())
    }

    @Test
    fun `멱등 재취소는 전이를 보고하지 않는다`() {
        // 같은 상태로의 이동이 응답으로는 Moved지만 일어난 일이 없다.
        // 갱신의 자기 전이와 달라야 한다 — 판별은 from==to가 아니라 경로다.
        val tasks = host()
        tasks.start("t1", 1, "navigate_to", listOf(location()))
        tasks.tick()
        val machine = tasks.find("t1")!!.machine
        machine.apply(TaskCommand.CANCEL)
        listener.reports.clear()

        machine.apply(TaskCommand.CANCEL)
        assertEquals(emptyList(), listener.reports)
    }

    @Test
    fun `원인이 결과보다 먼저 나간다`() {
        // 스킬이 HALTED가 되어서 태스크가 실패하는 것인데 태스크 전이를
        // 먼저 알리면, 이벤트를 접는 소비자가 잠시 §4.5 표에 없는 조합을 든다.
        val tasks = host()
        tasks.start("t1", 1, "navigate_to", listOf(location()))
        tasks.tick()
        listener.reports.clear()

        tasks.find("t1")!!.machine.onSkillHalted(Resolution.TERMINAL)

        val first = listener.reports.first()
        assertTrue(first.startsWith("skill"), "결과가 원인보다 먼저 나갔다: ${listener.reports}")
        assertTrue(listener.reports.last().contains("->FAILED"))
    }

    @Test
    fun `보고한 전이가 실제 상태와 이어진다`() {
        // **되짚기를 잡는 시험이다.** 보고된 from/to를 이어 붙인 결과가
        // 실제 최종 상태와 같아야 한다 — 하나라도 빠지거나 겹치면 끊긴다.
        val tasks = host()
        tasks.start("t1", 1, "navigate_to", listOf(location()))
        tasks.tick()
        clock.advance(Duration.ofSeconds(20))
        tasks.tick()

        var state = "NEW"
        listener.reports.filter { it.startsWith("task") }.forEach { line ->
            val (from, to) = line.substringAfter(": ").substringBefore(" r").split("->")
            assertEquals(state, from, "전이가 끊겼다: $line")
            state = to
        }
        assertEquals(tasks.find("t1")!!.machine.state.name, state)
    }

    @Test
    fun `기본 리스너는 아무것도 안 한다`() {
        // 엔진 시험이 리스너를 몰라도 돌아야 한다.
        val document = TaskMachineFixtures.document()
        val tasks = TaskHost(CapabilityProjection.of(document), document, clock)
        tasks.start("t1", 1, "navigate_to", listOf(location()))
        tasks.tick()
        assertEquals(TaskState.RUNNING, tasks.find("t1")!!.machine.state)
    }
}
