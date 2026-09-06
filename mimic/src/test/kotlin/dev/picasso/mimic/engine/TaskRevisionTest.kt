package dev.picasso.mimic.engine

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §4.4의 두 표 — 수신한 `revision` 4케이스와 갱신을 받은 상태 10케이스. */
class TaskRevisionTest {

    @Test
    fun `수신한 revision의 네 케이스를 통째로 확인한다`() {
        // "동일"과 "낮음"을 같은 거절로 접으면, 상태 메시지를 못 받아
        // 재전송한 클라이언트가 OUTDATED_REVISION을 받는다.
        val machine = TaskMachineFixtures.at(TaskState.RUNNING, revision = 3)

        assertEquals(
            UpdateOutcome.Idempotent(TaskState.RUNNING),
            machine.update(3, listOf(TaskMachineFixtures.param("a", "b"))),
            "같은 revision은 멱등이어야 한다",
        )
        assertEquals(emptyList(), machine.parameters, "멱등 재수신이 파라미터를 덮어썼다")

        assertEquals(UpdateOutcome.Outdated, machine.update(2, listOf(TaskMachineFixtures.param("a", "b"))))
        assertEquals(3, machine.revision, "거절했는데 revision이 바뀌었다")

        assertEquals(
            UpdateOutcome.RestartedSkill(TaskState.RUNNING),
            machine.update(4, listOf(TaskMachineFixtures.param("a", "b"))),
        )
        assertEquals(4, machine.revision)
        assertEquals(listOf(TaskMachineFixtures.param("a", "b")), machine.parameters)
    }

    @Test
    fun `갱신을 받은 상태별 처리를 통째로 확인한다`() {
        // §4.4의 표. 열 상태 전부를 본다.
        val expected: Map<TaskState, UpdateOutcome> = mapOf(
            TaskState.ACCEPTED to UpdateOutcome.ParametersOnly(TaskState.ACCEPTED),
            TaskState.RUNNING to UpdateOutcome.RestartedSkill(TaskState.RUNNING),
            TaskState.PAUSED to UpdateOutcome.ParametersOnly(TaskState.PAUSED),
            TaskState.RETRIABLE to UpdateOutcome.ParametersOnly(TaskState.RETRIABLE),
            // 스펙 보정 — 개입한 사람이 파라미터를 고쳐 넣는 경로다.
            TaskState.NEEDS_INTERVENTION to
                UpdateOutcome.ParametersOnly(TaskState.NEEDS_INTERVENTION),
            TaskState.CANCELLING to UpdateOutcome.Rejected,
            // 종착 넷은 전부 거절
        )
        assertEquals(6, expected.size, "표가 비면 '전부 거절'만 확인하고 통과한다")

        TaskState.entries.forEach { from ->
            val outcome = TaskMachineFixtures.at(from).update(2, listOf(TaskMachineFixtures.param("a", "b")))
            assertEquals(
                expected[from] ?: UpdateOutcome.Rejected,
                outcome,
                "$from 에서 갱신 처리가 §4.4 표와 다르다",
            )
        }
    }

    @Test
    fun `RUNNING 갱신은 스킬을 Halt Reset Start 시킨다`() {
        // 이름만 RestartedSkill이면 아무 뜻이 없다. 실제로 다시 시작했는가.
        val machine = TaskMachineFixtures.at(TaskState.RUNNING)
        machine.update(revision = 2, parameters = listOf(TaskMachineFixtures.param("grip_force", "10")))

        assertEquals(TaskState.RUNNING, machine.state, "태스크는 RUNNING을 유지해야 한다")
        assertEquals(
            SkillState.RUNNING, machine.skillMachine!!.state,
            "새 파라미터로 다시 Start하지 않았다",
        )
        assertEquals(listOf(TaskMachineFixtures.param("grip_force", "10")), machine.skillMachine!!.parameters)
    }

    @Test
    fun `갱신은 진행률과 attempt를 되돌린다`() {
        // 초안은 attempt와 revision만 단언했고 **진행률은 이름에만 있었다** —
        // restart() 한 줄을 지워도 초록이었다(리뷰 실측). 단조 비감소는
        // (revision, attempt) 두 축의 불변식인데 한 축만 지켜졌다.
        val clock = VirtualClock(Instant.EPOCH)
        val machine = TaskMachineFixtures.at(TaskState.RUNNING, clock = clock)

        clock.advance(Duration.ofSeconds(30))
        assertTrue(machine.progress() > 0.5, "전제가 무너졌다: ${machine.progress()}")

        machine.update(revision = 2, parameters = emptyList())
        assertEquals(0, machine.attempt)
        assertEquals(2, machine.revision)
        assertEquals(0.0, machine.progress(), "revision이 올랐는데 진행률이 이어졌다")
    }

    @Test
    fun `재시도는 진행률과 attempt를 되돌린다`() {
        val clock = VirtualClock(Instant.EPOCH)
        val machine = TaskMachineFixtures.at(TaskState.RUNNING, clock = clock)

        clock.advance(Duration.ofSeconds(30))
        machine.onSkillHalted(Resolution.SELF_RETRIABLE)
        clock.advance(Duration.ofSeconds(10))

        machine.apply(TaskCommand.RETRY)
        assertEquals(1, machine.attempt)
        assertEquals(0.0, machine.progress(), "재시도했는데 진행률이 이어졌다")
    }
}

/** 진행률의 불변식(§4.4)과 소요시간의 출처(§7.2·§10.1). */
class ProgressTest {

    @Test
    fun `같은 구간 안에서 단조 비감소이며 실제로 오른다`() {
        // "단조 비감소"만 보면 **언제나 0을 반환하는 구현이 통과한다.**
        val clock = VirtualClock(Instant.EPOCH)
        val machine = TaskMachineFixtures.at(TaskState.RUNNING, clock = clock)

        val samples = buildList {
            repeat(10) {
                add(machine.progress())
                clock.advance(Duration.ofSeconds(5))
            }
        }

        assertTrue(samples.zipWithNext().all { (a, b) -> b >= a }, "단조 비감소가 깨졌다: $samples")
        assertTrue(samples.all { it in 0.0..1.0 }, "범위를 벗어났다: $samples")
        assertTrue(samples.last() > samples.first(), "시간이 흘렀는데 진행률이 그대로다")
    }

    @Test
    fun `소요시간을 넘겨도 1을 넘지 않는다`() {
        val clock = VirtualClock(Instant.EPOCH)
        val machine = TaskMachineFixtures.at(TaskState.RUNNING, clock = clock)
        clock.advance(Duration.ofSeconds(500))
        assertEquals(1.0, machine.progress())
    }

    @Test
    fun `소요시간은 프로파일에서 온다`() {
        // 기대값을 리터럴로 쓰지 않는다 — 프로파일을 고치면 따라 바뀌어야 한다.
        // 초안은 durationSeconds = 45.0이라는 픽스처 값 리터럴을 썼고,
        // 그러면 소요시간이 데이터가 아니라 코드가 된다(§10.1).
        val raw = TaskMachineFixtures.fixtureRaw
        val mutated = raw.replace("\"skill_type\": \"pick_place\", \"seconds\": 45", "\"skill_type\": \"pick_place\", \"seconds\": 90")
        check(mutated != raw) { "치환이 아무것도 바꾸지 못했다" }

        val clock = VirtualClock(Instant.EPOCH)
        val a = TaskMachineFixtures.forSkill("pick_place", clock = clock)
        val b = TaskMachineFixtures.forSkill(
            "pick_place",
            doc = TaskMachineFixtures.document(mutated),
            clock = clock,
        )
        a.apply(TaskCommand.START)
        b.apply(TaskCommand.START)
        clock.advance(Duration.ofSeconds(45))

        assertEquals(1.0, a.progress())
        assertTrue(b.progress() < 0.6, "소요시간을 고쳤는데 진행률이 그대로다 — 파생되지 않았다")
    }

    @Test
    fun `두 스킬의 소요시간이 서로 다르다`() {
        // pick_place 45초, navigate_to 20초. 하나만 보면 둘 다 같은 상수를
        // 쓰는 구현이 통과한다.
        val clock = VirtualClock(Instant.EPOCH)
        val pick = TaskMachineFixtures.forSkill("pick_place", clock = clock)
        val nav = TaskMachineFixtures.forSkill("navigate_to", clock = clock)
        pick.apply(TaskCommand.START)
        nav.apply(TaskCommand.START)

        clock.advance(Duration.ofSeconds(20))
        assertEquals(1.0, nav.progress())
        assertTrue(pick.progress() < 0.5, "두 스킬이 같은 소요시간을 쓴다")
    }
}
