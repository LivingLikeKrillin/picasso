package dev.picasso.mimic.engine

import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.SkillDeclaration
import dev.picasso.profile.ProfileDocument

/** 기체가 들고 있는 태스크 하나. */
class TaskRuntime(
    val taskId: String,
    val skillType: String,
    val machine: TaskMachine,
) {
    val log = TaskLog()
}

/** `StartTask`의 결과. §4.4의 두 표와 §5.3·§10.4 ③의 판정이 여기 모인다. */
sealed interface StartOutcome {
    /** 새 태스크이거나 갱신을 받아들였다. */
    data class Accepted(val task: TaskRuntime) : StartOutcome

    /** 같은 revision을 다시 받았다 — 같은 핸들을 그대로 돌려준다(§4.4). */
    data class Idempotent(val task: TaskRuntime) : StartOutcome

    data class Rejected(
        val code: RejectionCode,
        val detail: String,
        val parameterKeys: List<String> = emptyList(),
    ) : StartOutcome
}

/**
 * 한 기체가 호스팅하는 태스크들. **전송을 모른다** — `transport`가 이 결과를
 * wire로 옮긴다(§10.1).
 */
class TaskHost(
    private val capability: Capability,
    private val document: ProfileDocument,
    private val clock: Clock,
) {
    private val tasks = LinkedHashMap<String, TaskRuntime>()

    fun find(taskId: String): TaskRuntime? = tasks[taskId]

    /** 호스팅 중인 태스크 전부. 전송이 열린 스트림에 밀 때 쓴다. */
    val all: Collection<TaskRuntime> get() = tasks.values

    fun skillOf(skillType: String): SkillDeclaration? =
        capability.skillsList.firstOrNull { it.skillType == skillType }

    fun start(
        taskId: String,
        revision: Int,
        skillType: String,
        parameters: List<ParameterValue>,
    ): StartOutcome {
        val existing = tasks[taskId]
        if (existing != null) return update(existing, revision, skillType, parameters)

        val skill = skillOf(skillType) ?: return StartOutcome.Rejected(
            // 애초에 선언한 적 없는 스킬이다. 있었다가 사라진 것(CAPABILITY_WITHDRAWN)과
            // 소비자의 대응이 다르다 — 저쪽은 캐시를 다시 세우면 되고 이쪽은
            // 요구 집합이 틀린 것이다.
            RejectionCode.REJECTION_CODE_SKILL_ABSENT,
            "선언하지 않은 스킬이다: $skillType (선언된 것: ${capability.skillsList.map { it.skillType }})",
        )

        validate(skill, parameters)?.let { return it }

        val task = TaskRuntime(
            taskId = taskId,
            skillType = skillType,
            machine = TaskMachine(
                skill = skill,
                clock = clock,
                durationSeconds = durationOf(skillType),
                initialRevision = revision,
                initialParameters = parameters,
            ),
        )
        tasks[taskId] = task

        // **접수까지다. 실행 개시는 [tick]이 한다.**
        //
        // 계약에 "이제 실행하라"는 RPC가 따로 없어서 여기서 START까지 해
        // 버리기 쉬운데, 그러면 §4.4가 정의한 `ACCEPTED`가 표면에서 **도달
        // 불가능**해진다 — 어떤 RPC로도 그 상태의 태스크를 만날 수 없으므로
        // §4.4의 갱신 표에서 `ACCEPTED` 행이 영원히 시험되지 않는다(실측:
        // 상태 열 개를 도는 시험이 그 행을 만들지 못했다).
        //
        // `StartTask`는 접수 응답이지 "돌고 있다"가 아니다(§4.4).
        record(task)

        return StartOutcome.Accepted(task)
    }

    /** §4.4의 두 표 — 수신한 `revision` 4행과 갱신을 받은 상태 7행. */
    private fun update(
        task: TaskRuntime,
        revision: Int,
        skillType: String,
        parameters: List<ParameterValue>,
    ): StartOutcome {
        if (skillType != task.skillType) {
            return StartOutcome.Rejected(
                RejectionCode.REJECTION_CODE_INVALID_TRANSITION,
                "같은 task_id를 다른 스킬로 다시 보냈다: ${task.skillType} → $skillType",
            )
        }

        // **갱신이 실제로 적용될 때만 미리 검사한다.**
        //
        // 뒤에 검사하면 이미 파라미터가 교체된 뒤라 거절하면서도 상태를
        // 바꿔 놓게 된다. 앞에서 무조건 검사하면 낮은 revision이나 종착
        // 태스크에 대해 OUTDATED_REVISION·INVALID_TRANSITION 대신
        // PARAMETER_INVALID가 나가 소비자가 엉뚱한 것을 고친다.
        if (task.machine.willApply(revision)) {
            validate(skillOf(task.skillType)!!, parameters)?.let { return it }
        }

        return when (val outcome = task.machine.update(revision, parameters)) {
            is UpdateOutcome.Idempotent -> StartOutcome.Idempotent(task)

            UpdateOutcome.Outdated -> StartOutcome.Rejected(
                RejectionCode.REJECTION_CODE_OUTDATED_REVISION,
                "이미 지난 revision이다: 받은 값=$revision, 현재=${task.machine.revision}",
            )

            UpdateOutcome.Rejected -> StartOutcome.Rejected(
                RejectionCode.REJECTION_CODE_INVALID_TRANSITION,
                "${task.machine.state} 에서는 갱신을 받지 않는다(§4.4)",
            )

            is UpdateOutcome.ParametersOnly, is UpdateOutcome.RestartedSkill -> {
                record(task)
                StartOutcome.Accepted(task)
            }
        }
    }

    private fun validate(
        skill: SkillDeclaration,
        parameters: List<ParameterValue>,
    ): StartOutcome.Rejected? {
        val problems = ParameterCheck.check(skill, parameters, capability.protocolLimits.maxStringLength)
        if (problems.isEmpty()) return null
        return StartOutcome.Rejected(
            RejectionCode.REJECTION_CODE_PARAMETER_INVALID,
            // 어긴 것을 전부 싣는다. 하나만 알려주면 클라이언트가 고칠 때마다
            // 한 번씩 왕복한다.
            problems.joinToString("; ") { "${it.key}: ${it.reason}" },
            problems.map { it.key }.distinct(),
        )
    }

    /**
     * 시계가 흐른 만큼 태스크를 진전시킨다. **RPC 진입에서만 부른다** —
     * 배경 스레드를 두면 가상 시계와 충돌해 §12.1의 결정성이 깨진다.
     *
     * 하는 일은 셋이다 — **접수한 태스크를 집어 들고**(`ACCEPTED` → `RUNNING`),
     * **소요시간을 채운 태스크를 완주시키고**(`RUNNING` → `SUCCEEDED`),
     * **취소의 복구를 마친다**(`CANCELLING` → `CANCELLED`).
     *
     * **전수 축은 상태 열이다.** 종착 여부만 보는 구현은 `CANCELLING`인
     * 태스크를 `SUCCEEDED`로 만든다 — `CANCELLING`은 종착이 아니고 스킬은
     * 복구를 수행하며 계속 돌기 때문이다(실측). §4.4의 취소 의미론과 완료
     * 기준 6의 "`CANCELLING` → 종착 순서"가 통째로 무너진다.
     *
     * 이 청크에는 실패 주입이 없으므로 완주는 언제나 성공이다. 프로파일의
     * 실패 모드와 시드 추첨은 제어 채널 청크가 만든다.
     */
    fun tick() {
        tasks.values.forEach { task ->
            when (task.machine.state) {
                // 접수한 태스크를 로봇이 집어 든다.
                TaskState.ACCEPTED -> {
                    task.machine.apply(TaskCommand.START)
                    record(task)
                }

                TaskState.RUNNING -> if (task.machine.progress() >= 1.0) {
                    task.machine.onSkillComplete()
                    record(task)
                }

                // 복구를 마친다. **이것이 없으면 취소한 태스크가 영영
                // CANCELLING에 남는다**(실측: 열린 WatchTask 스트림이 닫히지
                // 않았다) — §4.4의 "복구까지 마치면 CANCELLED"에 도달하는
                // 경로가 아예 없었다. 그러면 §9.3의 드레인 판정이 영영 0이
                // 되지 않아 축소가 영구히 막힌다.
                //
                // 복구에 걸리는 시간은 프로파일이 선언하지 않으므로 다음
                // tick에 끝난다. 관측 순서는 그래도 CANCELLING → 종착이다.
                // 복구 실패는 실패 주입이 만들 일이라 이 청크에 없다(§15).
                TaskState.CANCELLING -> {
                    task.machine.onRecoveryComplete()
                    record(task)
                }

                // 나머지 일곱은 시간이 흐른다고 저절로 변하지 않는다.
                // **`when`에 `else`를 쓰지 않는다** — 상태가 늘면 컴파일이
                // 깨져야지, 조용히 "아무 일 없음"으로 접히면 안 된다.
                TaskState.PAUSED, TaskState.RETRIABLE,
                TaskState.NEEDS_INTERVENTION, TaskState.SUCCEEDED, TaskState.FAILED,
                TaskState.CANCELLED, TaskState.CANCELLED_RECOVERY_FAILED,
                -> Unit
            }
        }
    }

    /** 현재 상태를 로그에 한 줄 적는다. */
    fun record(task: TaskRuntime): TaskUpdate = task.log.record(
        state = task.machine.state,
        revision = task.machine.revision,
        attempt = task.machine.attempt,
        progress = task.machine.progress(),
        occurredAt = clock.now(),
    )

    private fun durationOf(skillType: String): Double =
        document.durations.firstOrNull { it.skillType == skillType }?.seconds
        // 프로파일이 소요시간을 선언하지 않은 스킬은 즉시 끝나는 것으로
        // 두지 않는다 — 그러면 진행률이 관측 불가능해진다.
            ?: error("프로파일이 소요시간을 선언하지 않았다: $skillType")
}
