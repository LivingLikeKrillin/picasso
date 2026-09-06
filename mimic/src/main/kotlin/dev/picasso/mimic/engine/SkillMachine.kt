package dev.picasso.mimic.engine

/** §4.2. OPC UA Skill 모델을 언어 중립 계약으로 옮긴 넷. */
enum class SkillState { READY, RUNNING, SUSPENDED, HALTED }

/**
 * §4.2의 전이.
 *
 * `RESET`은 **엔진 내부 전이**이며 RPC로 노출하지 않는다(ADR 8) — 외부에 두면
 * "누가 언제 리셋하는가"라는 정책이 계약에 들어오는데 그 주인은 미션 계층이고
 * 비목표다.
 */
enum class SkillCommand { START, SUSPEND, RESUME, HALT, COMPLETE, RESET }

sealed interface SkillTransition {
    data class Moved(val from: SkillState, val to: SkillState) : SkillTransition
    data class Rejected(val reason: String) : SkillTransition
}

/**
 * §4.2의 스킬 상태머신.
 *
 * **표에 없는 조합은 전부 거절한다.** `else` 가지를 쓰지 않으므로 상태나
 * 명령이 늘면 컴파일이 깨진다 — 그것이 §12.1의 "상태머신 망라성"이다.
 */
class SkillMachine(initial: SkillState = SkillState.READY) {

    var state: SkillState = initial
        private set

    /** 현재 실행 중인 파라미터. 갱신이 이것을 교체한다(§4.4). */
    var parameters: Map<String, String> = emptyMap()
        private set

    fun apply(command: SkillCommand, parameters: Map<String, String> = emptyMap()): SkillTransition {
        val next: SkillState? = when (command) {
            SkillCommand.START -> if (state == SkillState.READY) SkillState.RUNNING else null
            SkillCommand.SUSPEND -> if (state == SkillState.RUNNING) SkillState.SUSPENDED else null
            SkillCommand.RESUME -> if (state == SkillState.SUSPENDED) SkillState.RUNNING else null
            SkillCommand.HALT -> when (state) {
                SkillState.RUNNING, SkillState.SUSPENDED -> SkillState.HALTED
                SkillState.READY, SkillState.HALTED -> null
            }
            SkillCommand.COMPLETE -> if (state == SkillState.RUNNING) SkillState.READY else null
            SkillCommand.RESET -> if (state == SkillState.HALTED) SkillState.READY else null
        }

        if (next == null) {
            return SkillTransition.Rejected(
                "INVALID_TRANSITION: $state 에서 $command 는 §4.2 표에 없다",
            )
        }

        val from = state
        state = next
        if (command == SkillCommand.START) this.parameters = parameters
        return SkillTransition.Moved(from, next)
    }
}
