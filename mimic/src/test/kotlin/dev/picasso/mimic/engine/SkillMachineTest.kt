package dev.picasso.mimic.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillMachineTest {

    /**
     * §4.2 전이표를 그대로 옮긴 것. **시험이 표이고 구현이 그것을 따른다.**
     * 여기 없는 (상태, 명령) 조합은 전부 불법이다.
     */
    private val legal: Map<Pair<SkillState, SkillCommand>, SkillState> = mapOf(
        (SkillState.READY to SkillCommand.START) to SkillState.RUNNING,
        (SkillState.RUNNING to SkillCommand.SUSPEND) to SkillState.SUSPENDED,
        (SkillState.SUSPENDED to SkillCommand.RESUME) to SkillState.RUNNING,
        (SkillState.RUNNING to SkillCommand.HALT) to SkillState.HALTED,
        (SkillState.SUSPENDED to SkillCommand.HALT) to SkillState.HALTED,
        (SkillState.RUNNING to SkillCommand.COMPLETE) to SkillState.READY,
        (SkillState.HALTED to SkillCommand.RESET) to SkillState.READY,
    )

    @Test
    fun `전이표가 비어 있지 않다`() {
        // 아래 시험은 legal이 비면 "전부 거절"만 확인하고 통과한다.
        assertEquals(7, legal.size)
    }

    @Test
    fun `전이표를 통째로 확인한다`() {
        // 대표 사례를 고르면 나머지가 어떻게 구현됐든 아무도 모른다.
        // 4 × 6 = 24 조합 전부를 본다.
        SkillState.entries.forEach { from ->
            SkillCommand.entries.forEach { command ->
                val expected = legal[from to command]
                val machine = SkillMachine(from)
                val result = machine.apply(command)

                if (expected == null) {
                    assertTrue(
                        result is SkillTransition.Rejected,
                        "$from + $command 가 통과했다 — §4.2 표에 없는 조합이다",
                    )
                    assertEquals(from, machine.state, "거절했는데 상태가 바뀌었다: $from + $command")
                } else {
                    assertTrue(
                        result is SkillTransition.Moved,
                        "$from + $command 를 거절했다 — §4.2 표에 있는 조합이다",
                    )
                    assertEquals(expected, machine.state, "$from + $command")
                }
            }
        }
    }

    @Test
    fun `초기 상태는 READY다`() {
        assertEquals(SkillState.READY, SkillMachine().state)
    }

    @Test
    fun `거절은 무엇이 왜 불법인지 말한다`() {
        val result = SkillMachine(SkillState.HALTED).apply(SkillCommand.START)
        result as SkillTransition.Rejected
        assertTrue(result.reason.contains("HALTED"))
        assertTrue(result.reason.contains("START"))
    }

    @Test
    fun `HALTED는 Reset 없이 다시 실행할 수 없다`() {
        // §4.2 — 이것이 HALTED의 정의다.
        val machine = SkillMachine(SkillState.HALTED)
        assertTrue(machine.apply(SkillCommand.START) is SkillTransition.Rejected)
        assertTrue(machine.apply(SkillCommand.RESET) is SkillTransition.Moved)
        assertTrue(machine.apply(SkillCommand.START) is SkillTransition.Moved)
    }

    @Test
    fun `Start가 파라미터를 싣는다`() {
        // §4.2 — 파라미터와 실행 요청을 단일 RPC로 묶는다.
        val machine = SkillMachine()
        machine.apply(SkillCommand.START, mapOf("grip_force" to "10"))
        assertEquals(mapOf("grip_force" to "10"), machine.parameters)
    }
}
