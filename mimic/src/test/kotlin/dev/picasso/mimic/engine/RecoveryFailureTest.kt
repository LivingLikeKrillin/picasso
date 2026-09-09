package dev.picasso.mimic.engine

import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.Lifetime
import dev.picasso.profile.projection.CapabilityProjection
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 완료 기준 8b — 복구 실패는 `CANCELLED_RECOVERY_FAILED`이고 **로봇 수준
 * 결함을 동반한다**.
 *
 * **엔진이 그 결함을 낸다.** 시험이 `ForceFault`로 로봇 결함을 넣고 그것이
 * 서 있는지 보면 자기가 넣은 것을 자기가 확인하는 것이라 "동반한다"가 통째로
 * 공허해진다. 그래서 여기서 넣는 결함(`LOCALIZATION_LOST`)과 엔진이 내는
 * 결함(`INTERNAL_ERROR`)이 **다른 것**임을 매번 확인한다.
 */
class RecoveryFailureTest {

    private val document = TaskMachineFixtures.document()
    private val listener = OrderedFaultListener()

    private fun host(clock: Clock = VirtualClock(Instant.EPOCH)) = TaskHost(
        CapabilityProjection.of(document), document, clock,
        listener, FaultRegistry(clock), Seeded(0),
    )

    /** `navigate_to` 하나를 `CANCELLING`까지 민다. */
    private fun cancelling(tasks: TaskHost, taskId: String = "t1"): TaskRuntime {
        tasks.start(taskId, 1, "navigate_to", listOf(TaskMachineFixtures.param("location", "d")))
        tasks.tick()
        val task = tasks.find(taskId)!!
        TaskMachineFixtures.driveTo(task, TaskState.CANCELLING)
        check(task.machine.state == TaskState.CANCELLING) { "취소를 못 걸었다" }
        return task
    }

    private fun faultsOf(tasks: TaskHost): List<Fault> =
        listener.faults.filterNot { it.errorType == INJECTED }

    // ── 동반하는가

    @Test
    fun `복구 실패가 로봇 수준 결함을 동반한다`() {
        val tasks = host()
        val task = cancelling(tasks)

        val outcome = tasks.forceFault(INJECTED, "t1")
        assertEquals(
            TaskState.CANCELLED_RECOVERY_FAILED,
            (outcome as ForceOutcome.Raised).taskState,
            "전제가 무너졌다 — 복구 실패가 안 됐다",
        )
        assertEquals(TaskState.CANCELLED_RECOVERY_FAILED, task.machine.state)

        // **주입한 것과 다른 결함이어야 한다.**
        val accompanying = faultsOf(tasks).single()
        assertEquals("INTERNAL_ERROR", accompanying.errorType)
    }

    @Test
    fun `그 결함이 로봇 수준이다`() {
        // §4.6 — references 가 등급을 말한다. 되돌리기에 실패한 것은 기체이지
        // 스킬이 아니다.
        val tasks = host()
        cancelling(tasks)
        tasks.forceFault(INJECTED, "t1")

        assertEquals(emptyList(), faultsOf(tasks).single().referencesList)
    }

    @Test
    fun `그 결함의 두 불리언이 모두 false다`() {
        // §4.4 — "로봇이 물건을 든 채 멈춰 있다"가 CANCELLED 와 이것을 나눈
        // 이유다. `can_accept_new_task` 가 참이면 그 구분이 사라진다.
        val tasks = host()
        cancelling(tasks)
        tasks.forceFault(INJECTED, "t1")

        val fault = faultsOf(tasks).single()
        assertEquals(false, fault.canContinueCurrentTask)
        assertEquals(false, fault.canAcceptNewTask)
    }

    @Test
    fun `그 결함의 수명이 UNTIL_CLEARED다`() {
        // `UNTIL_NEW_TASK` 는 틀렸다 — 새 태스크를 받는다고 로봇이 물건을
        // 내려놓지 않는다. 사람이 치워야 풀린다.
        val tasks = host()
        cancelling(tasks)
        tasks.forceFault(INJECTED, "t1")

        assertEquals(
            Lifetime.Kind.KIND_UNTIL_CLEARED,
            faultsOf(tasks).single().activeUntil.kind,
        )
    }

    @Test
    fun `그 결함이 조치를 담는다`() {
        // `can_accept_new_task=false` 인 결함에 조치가 없으면 운영자가 할 수
        // 있는 것이 없다(§4.6).
        val tasks = host()
        cancelling(tasks)
        tasks.forceFault(INJECTED, "t1")

        assertTrue(faultsOf(tasks).single().errorHint.isNotBlank(), "조치가 비어 있다")
    }

    // ── 안 동반해야 할 때

    @Test
    fun `복구 성공에는 그 결함이 없다`() {
        // **언제나 붙이는 구현을 잡는다.** 이것이 없으면 위 시험 전부가
        // "결함이 하나 있다"만 말한다.
        val clock = VirtualClock(Instant.EPOCH)
        val tasks = host(clock)
        val task = cancelling(tasks)

        tasks.tick() // CANCELLING → CANCELLED
        assertEquals(TaskState.CANCELLED, task.machine.state, "복구가 안 끝났다")
        assertEquals(emptyList(), faultsOf(tasks), "복구 성공인데 결함이 섰다")
    }

    @Test
    fun `평범한 실패에는 그 결함이 없다`() {
        // 복구가 아닌 halt 에도 붙이면 §4.4가 나눈 두 상황이 하나가 된다.
        val clock = VirtualClock(Instant.EPOCH)
        val tasks = host(clock)
        tasks.start("t1", 1, "navigate_to", listOf(TaskMachineFixtures.param("location", "d")))
        tasks.tick()

        tasks.forceFault(INJECTED, "t1")
        assertEquals(TaskState.NEEDS_INTERVENTION, tasks.find("t1")!!.machine.state)
        assertEquals(emptyList(), faultsOf(tasks), "복구 실패가 아닌데 결함이 섰다")
    }

    // ── 전파 규칙 3

    @Test
    fun `전파 규칙 3이 resolution을 보지 않는다`() {
        // §4.5 — 취소는 이미 결정된 것이고 남은 질문은 "되돌리는 데
        // 성공했는가"뿐이다. 셋을 다 넣어도 종착이 같아야 한다.
        val byResolution = document.failureModes
            .filter { it.skillType == null }
            .associate { it.errorType to it.resolution }
        assertTrue(
            byResolution.values.toSet().size >= 2,
            "픽스처의 로봇 수준 모드가 한 가지 resolution 뿐이다: $byResolution",
        )

        byResolution.keys.forEach { errorType ->
            val local = TaskHost(
                CapabilityProjection.of(document), document, VirtualClock(Instant.EPOCH),
                EngineListener.NONE, FaultRegistry(VirtualClock(Instant.EPOCH)), Seeded(0),
            )
            local.start("t1", 1, "navigate_to", listOf(TaskMachineFixtures.param("location", "d")))
            local.tick()
            TaskMachineFixtures.driveTo(local.find("t1")!!, TaskState.CANCELLING)

            local.forceFault(errorType, "t1")
            assertEquals(
                TaskState.CANCELLED_RECOVERY_FAILED,
                local.find("t1")!!.machine.state,
                "$errorType(${byResolution[errorType]})",
            )
        }
    }

    // ── 동일성과 순서

    @Test
    fun `태스크 둘이 복구 실패해도 결함은 하나다`() {
        // 로봇 수준이므로 기체당 하나다(§4.6). 태스크마다 세우면 소비자의
        // 목록이 부풀고 해소 하나로 안 지워지는 유령이 남는다.
        val tasks = host()
        cancelling(tasks, "t1")
        cancelling(tasks, "t2")

        tasks.forceFault(INJECTED, "t1")
        tasks.forceFault(INJECTED, "t2")

        assertEquals(
            TaskState.CANCELLED_RECOVERY_FAILED, tasks.find("t2")!!.machine.state,
            "두 번째 태스크가 안 갔다",
        )
        assertEquals(1, faultsOf(tasks).size, "결함이 태스크마다 섰다")
    }

    @Test
    fun `종착보다 로봇 결함이 나중에 나간다`() {
        // 복구 실패의 **원인**은 이미 나간 그 결함이고, 이것은 **결과**다.
        // 종착보다 먼저 내면 소비자가 아직 실패하지도 않은 복구에 대한
        // 결함을 본다.
        val tasks = host()
        cancelling(tasks)
        listener.events.clear()

        tasks.forceFault(INJECTED, "t1")

        val cause = listener.events.indexOf("fault $INJECTED")
        val effect = listener.events.indexOf("task CANCELLING->CANCELLED_RECOVERY_FAILED")
        val accompanying = listener.events.indexOf("fault INTERNAL_ERROR")
        assertTrue(cause >= 0 && effect >= 0 && accompanying >= 0, "${listener.events}")
        assertTrue(cause < effect, "원인이 종착보다 늦다: ${listener.events}")
        assertTrue(effect < accompanying, "동반 결함이 종착보다 이르다: ${listener.events}")
    }

    // ── 구조

    @Test
    fun `halt를 부르는 곳이 하나다`() {
        // **뒤처리를 호출자마다 두면 나중에 생기는 halt 경로가 결함 없이
        // 복구 실패를 만든다.** 그때 "동반한다"는 경로에 따라 참이 되고,
        // 거동 시험은 그것을 못 본다 — 지나지 않는 경로이기 때문이다.
        // 그래서 소스를 본다.
        val source = Files.readString(
            Path.of("src", "main", "kotlin", "dev", "picasso", "mimic", "engine", "TaskHost.kt"),
        )
        assertEquals(
            1, Regex("machine\\.onSkillHalted\\(").findAll(source).count(),
            "onSkillHalted 를 부르는 곳이 하나가 아니다 — 뒤처리가 그만큼 새 나간다",
        )
    }

    private companion object {
        /** 복구 실패를 **일으키는** 결함. 엔진이 내는 것과 달라야 한다. */
        const val INJECTED = "LOCALIZATION_LOST"
    }
}

/** 결함과 전이를 한 목록에 순서대로 모은다. */
private class OrderedFaultListener : EngineListener {

    val events = mutableListOf<String>()
    val faults = mutableListOf<Fault>()

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
        if (cleared) {
            events += "clear ${fault.errorType}"
            faults.removeIf { it.errorType == fault.errorType }
            return
        }
        events += "fault ${fault.errorType}"
        faults += fault
    }
}
