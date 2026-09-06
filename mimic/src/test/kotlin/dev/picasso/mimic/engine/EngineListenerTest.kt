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
        from: TaskState,
        to: TaskState,
        revision: Int,
        attempt: Int,
    ) {
        reports += "task($taskId,$skillType): $from->$to r$revision a$attempt"
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
                "task(t1,navigate_to): ACCEPTED->RUNNING r1 a0",
                "skill(t1,navigate_to): READY->RUNNING",
                "task(t1,navigate_to): RUNNING->SUCCEEDED r1 a0",
                "skill(t1,navigate_to): RUNNING->READY",
            ),
            listener.reports,
        )
    }

    @Test
    fun `접수만으로는 아무것도 보고하지 않는다`() {
        // StartTask는 접수까지다. 전이가 없는데 보고하면 소비자가 유령을 본다.
        host().start("t1", 1, "navigate_to", listOf(location()))
        assertEquals(emptyList(), listener.reports)
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
    fun `보고한 전이가 실제 상태와 이어진다`() {
        // **되짚기를 잡는 시험이다.** 보고된 from/to를 이어 붙인 결과가
        // 실제 최종 상태와 같아야 한다 — 하나라도 빠지거나 겹치면 끊긴다.
        val tasks = host()
        tasks.start("t1", 1, "navigate_to", listOf(location()))
        tasks.tick()
        clock.advance(Duration.ofSeconds(20))
        tasks.tick()

        var state = TaskState.ACCEPTED
        listener.reports.filter { it.startsWith("task") }.forEach { line ->
            val (from, to) = line.substringAfter(": ").substringBefore(" r").split("->")
            assertEquals(state.name, from, "전이가 끊겼다: $line")
            state = TaskState.valueOf(to)
        }
        assertEquals(tasks.find("t1")!!.machine.state, state)
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
