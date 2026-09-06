package dev.picasso.mimic.engine

import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.Support
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskMachineTest {

    // ── 상태 집합

    @Test
    fun `종착 넷과 비종착 여섯을 정확히 안다`() {
        // isTerminal이 전부 false여도 아래 래치 시험은 조용히 통과한다.
        assertEquals(
            setOf(
                TaskState.SUCCEEDED, TaskState.FAILED,
                TaskState.CANCELLED, TaskState.CANCELLED_RECOVERY_FAILED,
            ),
            TaskState.entries.filter { it.isTerminal }.toSet(),
        )
        assertEquals(6, TaskState.entries.count { !it.isTerminal })
    }

    // ── 전이표 (§4.4 + 스펙 보정)

    /** §4.4의 표. `CANCEL`은 비종착 여섯 전부에서 합법이다(스펙 보정). */
    private val legal: Map<Pair<TaskState, TaskCommand>, TaskState> = buildMap {
        put(TaskState.ACCEPTED to TaskCommand.START, TaskState.RUNNING)
        put(TaskState.RUNNING to TaskCommand.PAUSE, TaskState.PAUSED)
        put(TaskState.PAUSED to TaskCommand.RESUME, TaskState.RUNNING)
        TaskState.entries.filterNot { it.isTerminal }.forEach {
            put(it to TaskCommand.CANCEL, TaskState.CANCELLING)
        }
        put(TaskState.RETRIABLE to TaskCommand.RETRY, TaskState.RUNNING)
        put(TaskState.NEEDS_INTERVENTION to TaskCommand.RETRY, TaskState.RUNNING)
    }

    @Test
    fun `전이표가 비어 있지 않다`() {
        assertEquals(11, legal.size)
    }

    @Test
    fun `전이표를 통째로 확인한다`() {
        // 10 × 5 = 50 조합 전부. 대표 사례를 고르면 나머지가 침묵한다.
        TaskState.entries.forEach { from ->
            TaskCommand.entries.forEach { command ->
                val expected = legal[from to command]
                val machine = TaskMachineFixtures.at(from)
                val result = machine.apply(command)

                if (expected == null) {
                    assertTrue(
                        result is TaskTransition.Rejected,
                        "$from + $command 가 통과했다 — §4.4 표에 없는 조합이다",
                    )
                    assertEquals(from, machine.state, "거절했는데 상태가 바뀌었다: $from + $command")
                } else {
                    assertTrue(
                        result is TaskTransition.Moved,
                        "$from + $command 를 거절했다 — §4.4 표에 있는 조합이다: $result",
                    )
                    // CANCELLING에서 스킬이 없으면 복구가 즉시 끝나 CANCELLED가 된다.
                    val settled = if (from == TaskState.CANCELLING) TaskState.CANCELLING else expected
                    assertTrue(
                        machine.state == settled || machine.state == TaskState.CANCELLED,
                        "$from + $command → ${machine.state}, 기대 $settled",
                    )
                }
            }
        }
    }

    // ── 래치 — 이 청크의 유일한 불변식

    @Test
    fun `종착은 어떤 진입점으로도 깨지지 않는다`() {
        // 명령만 도는 시험은 onSkillHalted / onRecoveryComplete /
        // onSkillComplete / update 를 못 본다. 계획 초안이 그 상태로 초록이었고
        // 종착 4 × Resolution 3 = 12조합이 전부 종착을 떠났다(리뷰 실측).
        val entryPoints: List<Pair<String, (TaskMachine) -> Unit>> =
            TaskCommand.entries.map { c -> "apply($c)" to { m: TaskMachine -> m.apply(c); Unit } } +
                Resolution.entries.map { r ->
                    "onSkillHalted($r)" to { m: TaskMachine -> m.onSkillHalted(r) }
                } +
                listOf(
                    "onRecoveryComplete" to { m: TaskMachine -> m.onRecoveryComplete() },
                    "onSkillComplete" to { m: TaskMachine -> m.onSkillComplete() },
                    "update" to { m: TaskMachine -> m.update(99, emptyList()); Unit },
                )

        assertEquals(11, entryPoints.size, "진입점 목록이 줄었다")

        TaskState.entries.filter { it.isTerminal }.forEach { terminal ->
            entryPoints.forEach { (name, act) ->
                val machine = TaskMachineFixtures.at(terminal)
                act(machine)
                assertEquals(terminal, machine.state, "$terminal 에서 $name 이 래치를 깼다")
            }
        }
    }

    // ── §4.5 전파 규칙

    @Test
    fun `스킬 Halt의 태스크 판정은 Resolution이 정한다`() {
        // 전파 규칙 1. 셋 다 본다 — 하나만 보면 나머지가 침묵한다.
        mapOf(
            Resolution.SELF_RETRIABLE to TaskState.RETRIABLE,
            Resolution.NEEDS_INTERVENTION to TaskState.NEEDS_INTERVENTION,
            Resolution.TERMINAL to TaskState.FAILED,
        ).forEach { (resolution, expected) ->
            val machine = TaskMachineFixtures.at(TaskState.RUNNING)
            machine.onSkillHalted(resolution)
            assertEquals(expected, machine.state, "$resolution")
        }
    }

    @Test
    fun `CANCELLING 중의 Halt는 Resolution을 보지 않는다`() {
        // 전파 규칙 3 — 취소는 이미 결정된 것이고 남은 질문은
        // "되돌리는 데 성공했는가"뿐이다.
        Resolution.entries.forEach { resolution ->
            val machine = TaskMachineFixtures.at(TaskState.CANCELLING)
            machine.onSkillHalted(resolution)
            assertEquals(
                TaskState.CANCELLED_RECOVERY_FAILED, machine.state,
                "$resolution 에서 복구 실패가 아닌 것으로 갔다",
            )
        }
    }

    @Test
    fun `태스크 상태와 스킬 상태의 대응을 통째로 확인한다`() {
        // §4.5의 표. 두 상태머신을 만든 청크에서 둘의 관계가 시험되지 않으면
        // 무엇을 만든 것인지 알 수 없다.
        val expected = mapOf(
            TaskState.ACCEPTED to null,
            TaskState.RUNNING to SkillState.RUNNING,
            TaskState.PAUSED to SkillState.SUSPENDED,
            TaskState.RETRIABLE to SkillState.READY,
            TaskState.NEEDS_INTERVENTION to SkillState.READY,
            TaskState.SUCCEEDED to SkillState.READY,
            TaskState.FAILED to SkillState.READY,
            TaskState.CANCELLED to SkillState.READY,
            TaskState.CANCELLED_RECOVERY_FAILED to SkillState.READY,
        )
        // CANCELLING은 RUNNING·SUSPENDED 둘 다 합법이라 따로 본다.
        TaskState.entries.filter { it != TaskState.CANCELLING }.forEach { t ->
            assertEquals(expected.getValue(t), TaskMachineFixtures.at(t).skillMachine?.state, "$t")
        }
        assertTrue(
            TaskMachineFixtures.at(TaskState.CANCELLING).skillMachine?.state
                in setOf(SkillState.RUNNING, SkillState.SUSPENDED),
        )
    }

    // ── 취소와 재시도

    @Test
    fun `취소는 즉시가 아니고 복구를 동반한다`() {
        // §4.4 — CancelTask는 종착이 아니라 CANCELLING을 반환한다.
        val machine = TaskMachineFixtures.at(TaskState.RUNNING)
        machine.apply(TaskCommand.CANCEL)
        assertEquals(TaskState.CANCELLING, machine.state)

        machine.onRecoveryComplete()
        assertEquals(TaskState.CANCELLED, machine.state)
    }

    @Test
    fun `되돌릴 것이 없어도 CANCELLING을 지난다`() {
        // 스펙 보정 — RETRIABLE에서는 스킬이 이미 READY라 복구가 즉시 끝나지만
        // 관측 순서는 그래도 CANCELLING → 종착이다. 한 경로가 소비자에게
        // 거짓말하지 않는다.
        val machine = TaskMachineFixtures.at(TaskState.RETRIABLE)
        val result = machine.apply(TaskCommand.CANCEL)
        result as TaskTransition.Moved
        assertEquals(TaskState.CANCELLING, result.to)
        assertEquals(TaskState.CANCELLED, machine.state)
    }

    @Test
    fun `RetryTask는 RETRIABLE과 NEEDS_INTERVENTION에서만 합법이다`() {
        val allowed = setOf(TaskState.RETRIABLE, TaskState.NEEDS_INTERVENTION)
        TaskState.entries.forEach { from ->
            val machine = TaskMachineFixtures.at(from)
            val result = machine.apply(TaskCommand.RETRY)
            if (from in allowed) {
                assertTrue(result is TaskTransition.Moved, "$from 에서 재시도를 거절했다")
                assertEquals(TaskState.RUNNING, machine.state)
                assertEquals(1, machine.attempt, "재시도했는데 attempt가 안 올랐다")
            } else {
                assertTrue(result is TaskTransition.Rejected, "$from 에서 재시도가 통과했다")
            }
        }
    }

    // ── 완료 기준 7 — 취소·일시정지 불가

    @Test
    fun `pause_support가 NO면 PAUSE_UNSUPPORTED다`() {
        val machine = TaskMachineFixtures.withSupport(pause = Support.SUPPORT_NO)
        machine.apply(TaskCommand.START)
        val result = machine.apply(TaskCommand.PAUSE)
        result as TaskTransition.Rejected
        assertEquals(RejectionCode.REJECTION_CODE_PAUSE_UNSUPPORTED, result.code)
        assertEquals(TaskState.RUNNING, machine.state)
    }

    @Test
    fun `cancel_support가 NO면 CANCEL_UNSUPPORTED다`() {
        val machine = TaskMachineFixtures.withSupport(cancel = Support.SUPPORT_NO)
        machine.apply(TaskCommand.START)
        val result = machine.apply(TaskCommand.CANCEL)
        result as TaskTransition.Rejected
        assertEquals(RejectionCode.REJECTION_CODE_CANCEL_UNSUPPORTED, result.code)
    }

    @Test
    fun `UNKNOWN이면 시도가 허용된다`() {
        // §7.4 — UNKNOWN이면 시도가 허용되고 로봇이 거절할 수 있다.
        // 거절은 실패 주입이 만들 일이지 엔진이 미리 막을 일이 아니다.
        val machine = TaskMachineFixtures.withSupport(pause = Support.SUPPORT_UNKNOWN)
        machine.apply(TaskCommand.START)
        assertTrue(machine.apply(TaskCommand.PAUSE) is TaskTransition.Moved)
        assertEquals(TaskState.PAUSED, machine.state)
    }

    // ── 정상 경로 (완료 기준 6)

    @Test
    fun `정상 경로를 완주한다`() {
        // at()으로 만든 기체만 도는 시험은 실제 경로를 증명하지 않는다.
        val clock = VirtualClock(Instant.EPOCH)
        val machine = TaskMachineFixtures.forSkill("pick_place", clock = clock)

        assertEquals(TaskState.ACCEPTED, machine.state)
        assertEquals(0.0, machine.progress(), "시작 전인데 진행률이 있다")

        machine.apply(TaskCommand.START)
        assertEquals(TaskState.RUNNING, machine.state)
        assertEquals(SkillState.RUNNING, machine.skillMachine!!.state)

        clock.advance(Duration.ofSeconds(45))
        assertEquals(1.0, machine.progress())

        machine.onSkillComplete()
        assertEquals(TaskState.SUCCEEDED, machine.state)
        assertEquals(SkillState.READY, machine.skillMachine!!.state)
    }
}
