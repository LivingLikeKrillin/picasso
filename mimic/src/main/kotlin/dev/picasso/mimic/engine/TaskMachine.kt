package dev.picasso.mimic.engine

import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.SkillDeclaration
import dev.picasso.contracts.v1.Support

/** §4.4. 종착 넷과 비종착 여섯. */
enum class TaskState(val isTerminal: Boolean) {
    ACCEPTED(false),
    RUNNING(false),
    PAUSED(false),
    CANCELLING(false),
    RETRIABLE(false),
    NEEDS_INTERVENTION(false),
    SUCCEEDED(true),
    FAILED(true),
    CANCELLED(true),
    CANCELLED_RECOVERY_FAILED(true),
}

/** §4.4의 RPC 다섯이 그대로 명령이다. */
enum class TaskCommand { START, PAUSE, RESUME, CANCEL, RETRY }

/**
 * §4.3·§4.5. **프로파일의 실패 모드가 선언한다** — 어댑터가 정하지 않는다.
 * 이것이 전파 규칙 1의 입력이다.
 */
enum class Resolution { SELF_RETRIABLE, NEEDS_INTERVENTION, TERMINAL }

sealed interface TaskTransition {
    data class Moved(val from: TaskState, val to: TaskState) : TaskTransition
    data class Rejected(val code: RejectionCode, val reason: String) : TaskTransition
}

/** §4.4의 멱등성 4케이스와 갱신 6케이스의 결과. */
sealed interface UpdateOutcome {
    /** 현재와 같은 revision. 상태 메시지를 못 받아 재전송한 경우다. */
    data class Idempotent(val state: TaskState) : UpdateOutcome
    data class ParametersOnly(val state: TaskState) : UpdateOutcome

    /** `RUNNING`에서만. 스킬을 `Halt` → `Reset` → 새 파라미터로 `Start`한다. */
    data class RestartedSkill(val state: TaskState) : UpdateOutcome
    data object Outdated : UpdateOutcome
    data object Rejected : UpdateOutcome
}

/**
 * §4.4의 태스크 상태머신과 §4.5의 전파 규칙.
 *
 * **종착은 래치된다 — 이것이 계약의 불변식이다.** 실물 중에 이를 지키지 않는
 * 것이 있고(Digit의 `action-status`는 매뉴얼이 래치하지 않는다고 명시한다),
 * 그런 로봇에서는 어댑터가 래치 책임을 진다.
 *
 * 그래서 **상태 쓰기가 [transitionTo] 한 곳뿐이다.** 진입점이 명령 다섯 말고도
 * [onSkillHalted]·[onRecoveryComplete]·[onSkillComplete]·[update] 넷이 더
 * 있는데, 래치를 명령 경로에만 걸면 나머지 넷이 통째로 뚫린다 — 계획 초안이
 * 정확히 그 상태였고 종착 4 × Resolution 3 = 12조합이 전부 종착을 떠났다.
 *
 * @param skill 프로파일이 선언한 스킬. `pause_support`·`cancel_support`가
 *   완료 기준 7의 판정 입력이며, 엔진 밖에서 판단하면 §10.1이 깨진다.
 */
class TaskMachine(
    private val skill: SkillDeclaration,
    private val clock: Clock,
    durationSeconds: Double,
    initial: TaskState = TaskState.ACCEPTED,
    initialRevision: Int = 1,
    initialParameters: List<ParameterValue> = emptyList(),
    /** 이벤트에 실린다(§4.7). 시험용 기체는 비워 둔다. */
    private val taskId: String = "",
    private val listener: EngineListener = EngineListener.NONE,
) {
    var state: TaskState = initial
        private set

    var revision: Int = initialRevision
        private set

    var attempt: Int = 0
        private set

    var parameters: List<ParameterValue> = initialParameters
        private set

    /** §4.5 — 태스크 상태마다 스킬 상태가 정해진다. `ACCEPTED`에서는 없다. */
    var skillMachine: SkillMachine? = null
        private set

    private val progress = Progress(clock, durationSeconds)

    fun progress(): Double = if (skillMachine == null) 0.0 else progress.value()

    // ── 명령

    fun apply(command: TaskCommand): TaskTransition {
        // 지원 여부가 표보다 먼저다(§4.4의 응답 규정).
        // UNKNOWN은 통과시킨다 — §7.4가 "시도가 허용되고 로봇이 거절할 수
        // 있다"고 하므로 거절은 실패 주입이 만들 일이지 엔진이 미리 막을
        // 일이 아니다. NO만 막는다.
        if (command == TaskCommand.PAUSE && skill.pauseSupport == Support.SUPPORT_NO) {
            return TaskTransition.Rejected(
                RejectionCode.REJECTION_CODE_PAUSE_UNSUPPORTED,
                "pause_support=NO: ${skill.skillType}",
            )
        }
        if (command == TaskCommand.CANCEL && skill.cancelSupport == Support.SUPPORT_NO) {
            return TaskTransition.Rejected(
                RejectionCode.REJECTION_CODE_CANCEL_UNSUPPORTED,
                "cancel_support=NO: ${skill.skillType}",
            )
        }

        val next: TaskState? = when (command) {
            TaskCommand.START -> if (state == TaskState.ACCEPTED) TaskState.RUNNING else null
            TaskCommand.PAUSE -> if (state == TaskState.RUNNING) TaskState.PAUSED else null
            TaskCommand.RESUME -> if (state == TaskState.PAUSED) TaskState.RUNNING else null
            // §4.4 보정 — 비종착 여섯 전부에서 합법이다. RETRIABLE·
            // NEEDS_INTERVENTION에서 못 받으면 재시도를 포기한 태스크가
            // 영원히 비종착으로 남아 §9.3의 드레인이 영영 0이 되지 않는다.
            TaskCommand.CANCEL -> if (state.isTerminal) null else TaskState.CANCELLING
            TaskCommand.RETRY -> when (state) {
                TaskState.RETRIABLE, TaskState.NEEDS_INTERVENTION -> TaskState.RUNNING
                else -> null
            }
        }

        if (next == null) {
            return TaskTransition.Rejected(
                RejectionCode.REJECTION_CODE_INVALID_TRANSITION,
                "$state 에서 $command 는 §4.4 표에 없다",
            )
        }

        // CANCELLING에서의 재취소는 멱등이다 — 응답을 못 받아 재전송한 경우.
        if (command == TaskCommand.CANCEL && state == TaskState.CANCELLING) {
            return TaskTransition.Moved(TaskState.CANCELLING, TaskState.CANCELLING)
        }

        val moved = transitionTo(next, "$command")
        if (moved !is TaskTransition.Moved) return moved

        when (command) {
            TaskCommand.START -> startSkill()
            TaskCommand.RESUME -> applySkill(SkillCommand.RESUME)
            TaskCommand.PAUSE -> applySkill(SkillCommand.SUSPEND)
            TaskCommand.RETRY -> {
                attempt += 1
                startSkill()
            }
            // 취소는 즉시가 아니다. 스킬은 복구를 수행하는 동안 계속 돈다.
            //
            // 되돌릴 것이 있는지는 **스킬이 돌고 있는지**로 판단한다.
            // RETRIABLE·NEEDS_INTERVENTION에서는 스킬이 이미 READY라(§4.5)
            // 복구가 즉시 끝난다 — 관측 순서는 그래도 CANCELLING → 종착이다.
            TaskCommand.CANCEL -> {
                val running = skillMachine?.state
                if (running != SkillState.RUNNING && running != SkillState.SUSPENDED) {
                    onRecoveryComplete()
                }
            }
        }

        return moved
    }

    // ── 스킬에서 올라오는 사건 (§4.5의 전파 규칙)

    /** 전파 규칙 1·3. `CANCELLING` 중이면 `Resolution`을 보지 않는다. */
    fun onSkillHalted(resolution: Resolution) {
        val next = if (state == TaskState.CANCELLING) {
            // 규칙 3 — 취소는 이미 결정된 것이고 남은 질문은
            // "되돌리는 데 성공했는가"뿐이다.
            TaskState.CANCELLED_RECOVERY_FAILED
        } else {
            when (resolution) {
                Resolution.SELF_RETRIABLE -> TaskState.RETRIABLE
                Resolution.NEEDS_INTERVENTION -> TaskState.NEEDS_INTERVENTION
                Resolution.TERMINAL -> TaskState.FAILED
            }
        }

        if (transitionTo(next, "onSkillHalted($resolution)") is TaskTransition.Moved) {
            // §4.5 — HALTED → 즉시 Reset → READY.
            applySkill(SkillCommand.HALT)
            applySkill(SkillCommand.RESET)
        }
    }

    /** 복구까지 마쳤다 → `CANCELLED`(§4.4). */
    fun onRecoveryComplete() {
        if (state != TaskState.CANCELLING) return
        if (transitionTo(TaskState.CANCELLED, "onRecoveryComplete") is TaskTransition.Moved) {
            applySkill(SkillCommand.HALT)
            applySkill(SkillCommand.RESET)
        }
    }

    /** 정상 완료 → `SUCCEEDED`. */
    fun onSkillComplete() {
        if (transitionTo(TaskState.SUCCEEDED, "onSkillComplete") is TaskTransition.Moved) {
            applySkill(SkillCommand.COMPLETE)
        }
    }

    // ── 멱등성과 갱신 (§4.4의 두 표)

    /**
     * [update]가 파라미터를 실제로 교체할 것인가.
     *
     * **갱신 전에 파라미터를 검사해야 하는 호출자가 쓴다** — 교체한 뒤에
     * 검사하면 거절하면서도 이미 바꿔 놓은 상태가 된다. 조건을 호출자가
     * 다시 쓰면 두 벌이 되므로 규칙은 여기 하나뿐이다.
     */
    fun willApply(revision: Int): Boolean =
        revision > this.revision && !state.isTerminal && state != TaskState.CANCELLING

    fun update(revision: Int, parameters: List<ParameterValue>): UpdateOutcome {
        if (revision < this.revision) return UpdateOutcome.Outdated
        // 같은 revision은 같은 핸들을 그대로 돌려준다. 파라미터도 안 덮는다.
        if (revision == this.revision) return UpdateOutcome.Idempotent(state)
        if (!willApply(revision)) return UpdateOutcome.Rejected

        this.revision = revision
        this.parameters = parameters
        // 진행률은 새 revision에서 0부터 다시 세고 attempt는 0으로 되돌아간다.
        attempt = 0
        progress.restart()

        return if (state == TaskState.RUNNING) {
            // §4.4 — 스킬을 Halt → Reset → 새 파라미터로 Start. 태스크는 RUNNING 유지.
            applySkill(SkillCommand.HALT)
            applySkill(SkillCommand.RESET)
            applySkill(SkillCommand.START, parameters)
            UpdateOutcome.RestartedSkill(state)
        } else {
            UpdateOutcome.ParametersOnly(state)
        }
    }

    // ── 상태 쓰기의 유일한 지점

    /**
     * **모든 상태 변경이 여기를 지난다.** 진입점이 늘어도 래치가 새지 않는
     * 유일한 방법이다.
     */
    private fun transitionTo(next: TaskState, via: String): TaskTransition {
        if (state.isTerminal) {
            return TaskTransition.Rejected(
                RejectionCode.REJECTION_CODE_INVALID_TRANSITION,
                "$state 는 종착이다: $via",
            )
        }
        val from = state
        state = next
        listener.onTaskTransition(taskId, skill.skillType, from, next, revision, attempt)
        return TaskTransition.Moved(from, next)
    }

    /**
     * **스킬에 대한 모든 명령이 여기를 지난다.** 여덟 군데서 직접 부르면
     * 리스너 배선이 그중 하나를 빠뜨렸을 때 그 전이만 조용히 사라진다 —
     * 상태 쓰기를 [transitionTo] 하나로 모은 것과 같은 이유다.
     */
    private fun applySkill(command: SkillCommand, parameters: List<ParameterValue> = emptyList()) {
        val machine = skillMachine ?: return
        val result = machine.apply(command, parameters)
        // 거절은 보고하지 않는다 — 안 일어난 일이다.
        if (result is SkillTransition.Moved) {
            listener.onSkillTransition(taskId, skill.skillType, result.from, result.to)
        }
    }

    private fun startSkill() {
        skillMachine ?: SkillMachine().also { skillMachine = it }
        applySkill(SkillCommand.START, parameters)
        progress.restart()
    }

    /** 시험용 진입 — `TaskMachineFixtures`가 §4.5 대응표를 세울 때 쓴다. */
    internal fun seed(state: TaskState, skill: SkillMachine?, parameters: List<ParameterValue>) {
        this.state = state
        this.skillMachine = skill
        this.parameters = parameters
        progress.restart()
    }
}
