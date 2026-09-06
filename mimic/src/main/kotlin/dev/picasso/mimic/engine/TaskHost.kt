package dev.picasso.mimic.engine

import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.Lifetime
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

/** §10.5의 `ForceFault` 결과. */
sealed interface ForceOutcome {
    /** @param taskState 전파가 보낸 곳. 로봇 수준이면 `null`. */
    data class Raised(val raised: Boolean, val taskState: TaskState?) : ForceOutcome

    data class NotFound(val taskId: String) : ForceOutcome

    data class Rejected(val detail: String) : ForceOutcome
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
    /** 엔진이 전이를 보고할 곳(§4.7). 발행은 전송이 붙인다. */
    private val listener: EngineListener = EngineListener.NONE,
    /** 이 기체의 결함들(§4.6). 수명을 거두는 것은 [tick] 하나다. */
    private val faults: FaultRegistry = FaultRegistry(clock),
    /**
     * §10.4 ①의 추첨. 기체마다 하나이며 인출 순서가 §12.1의 불변식이다.
     *
     * **기본값을 두지 않는다.** `Seeded`의 계약이 "기체마다 하나"인데
     * 기본 인자는 그것을 **조용히 깨는 문**이다 — 넘기는 것을 잊은 호출자가
     * 기체의 스트림과 다른 스트림을 갖게 되고 컴파일은 통과한다.
     */
    private val random: Seeded,
) {
    private val draw = FailureDraw(document)

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
                taskId = taskId,
                listener = listener,
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
        // §4.3의 UNTIL_NEW_TASK — 새 태스크가 접수되면 그 수명의 결함이
        // 사라진다. 부르는 곳이 없으면 그 수명이 영원이 된다.
        faults.onNewTask().forEach { listener.onFault(it, cleared = true) }

        record(task)
        // **접수도 전이다.** 안 알리면 접수만 되고 아직 tick을 안 받은
        // 태스크가 재구성에서 통째로 사라진다 — §4.4가 `ACCEPTED`를 도달
        // 가능하게 둔 이상 가정이 아니다.
        listener.onTaskTransition(taskId, skillType, null, TaskState.ACCEPTED, revision, 0)

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
     * 하는 일은 넷이다 — **수명이 지난 결함을 거두고**(§4.3), **접수한
     * 태스크를 집어 들고**(`ACCEPTED` → `RUNNING`), **소요시간을 채운 태스크의
     * 결말을 뽑고**(`RUNNING` → 성공이면 `SUCCEEDED`, 실패면 §4.5 전파 규칙
     * 1이 보내는 곳), **취소의 복구를 마친다**(`CANCELLING` → `CANCELLED`).
     *
     * **전수 축은 상태 열이다.** 종착 여부만 보는 구현은 `CANCELLING`인
     * 태스크를 `SUCCEEDED`로 만든다 — `CANCELLING`은 종착이 아니고 스킬은
     * 복구를 수행하며 계속 돌기 때문이다(실측). §4.4의 취소 의미론과 완료
     * 기준 6의 "`CANCELLING` → 종착 순서"가 통째로 무너진다.
     */
    fun tick() {
        // **수명을 여기서만 거둔다.** 조회하면서 지우면 소멸 시점이 관측자에
        // 달리고 §12.1이 깨진다. 거둔 것마다 해소를 알린다 — 안 알리면
        // 이벤트를 접는 소비자가 지워진 결함을 영원히 든다.
        faults.expire().forEach { listener.onFault(it, cleared = true) }

        tasks.values.forEach { task ->
            when (task.machine.state) {
                // 접수한 태스크를 로봇이 집어 든다.
                TaskState.ACCEPTED -> {
                    task.machine.apply(TaskCommand.START)
                    record(task)
                }

                // **실패 추첨 지점은 여기 하나다.** 다른 데 두면 뽑는 횟수가
                // 관측 횟수에 달리고(모든 RPC가 tick을 부른다) 소비자가
                // 보는 것이 결과를 바꾼다 — §12.1이 깨지는 자리다.
                //
                // 같은 `Seeded`를 쓰는 인출이 하나 더 있다 — [durationOf]의
                // 지터다. 그쪽은 **태스크 생성 때 한 번**이므로 순서는
                // "태스크마다 지터 한 번, 완주할 때 실패 한 번"이다.
                TaskState.RUNNING -> if (task.machine.progress() >= 1.0) {
                    val failure = draw.drawFor(task.skillType, random)
                    if (failure == null) {
                        task.machine.onSkillComplete()
                    } else {
                        // **결함을 먼저 올리고 알린 뒤에 태스크를 보낸다.**
                        // 원인이 결과보다 먼저 나가야 이벤트를 접는 소비자가
                        // 원인 없는 실패를 보지 않는다.
                        faults.raise(FailureDraw.faultOf(failure, task.skillType, task.taskId))
                            ?.let { listener.onFault(it, cleared = false) }
                        halt(task, FailureDraw.resolutionOf(failure.resolution))
                    }
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
                //
                // **여기서는 추첨하지 않는다 — 그것이 결정이다.** 선언된 실패
                // 모드는 **완주 판정에서만** 뽑는다. `rate`를 "이 스킬 실행당
                // 발생 확률"로 읽은 것이고, 복구는 그 실행이 아니라 그 실행을
                // 되돌리는 구간이다. §4.5의 대응표가 `CANCELLING` 중에도 스킬을
                // 돌게 두므로 자명하지 않아 여기 적는다. 복구 실패
                // (`CANCELLED_RECOVERY_FAILED`)는 §10.5의 `ForceFault`만
                // 만든다 — 완료 기준 8b가 그 경로를 시험한다.
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

    /**
     * §10.5의 `ForceFault`. 프로파일이 선언한 실패 모드 하나를 **지금** 세운다.
     *
     * **선언되지 않은 `error_type`은 거절한다.** §4.5 전파 규칙 1의 입력인
     * `resolution`이 프로파일에서만 오므로, 허용하면 태스크 종착 판정이
     * 미정의가 된다. 어댑터 전용 둘(`TERMINAL_STATE_VIOLATED`·
     * `CONTROL_AUTHORITY_LOST`)은 스키마의 enum에서 빠져 있어 프로파일에
     * 있을 수 없고, 따라서 이 문으로도 못 들어온다 — 전용 RPC가 따로 있다.
     *
     * **등급은 모드가 정하고, `taskId`는 어디로 전파할지를 정한다.** 스킬
     * 수준 모드(`skill_type`이 있다)는 `taskId`가 필수이며 그 태스크의 스킬이
     * 모드가 지목한 스킬이어야 한다 — 아니면 결함의 `skill_id`와 태스크의
     * 스킬이 어긋난 것이 나간다. 로봇 수준 모드는 `taskId`가 선택이다.
     *
     * **정착시키지 않는다.** 여기서 `tick()`을 부르면 `CANCELLING`이던
     * 태스크가 같은 호출 안에서 `CANCELLED`로 넘어가, 완료 기준 8b가 보려는
     * 복구 실패의 창이 닫힌다.
     */
    fun forceFault(errorType: String, taskId: String): ForceOutcome {
        val mode = document.failureModes.firstOrNull { it.errorType == errorType }
            ?: return ForceOutcome.Rejected(
                "프로파일이 선언하지 않은 error_type이다: $errorType " +
                    "(선언된 것: ${document.failureModes.map { it.errorType }})",
            )

        val task = when {
            taskId.isEmpty() && mode.skillType != null -> return ForceOutcome.Rejected(
                "스킬 수준 모드는 task_id가 필요하다: $errorType (${mode.skillType})",
            )
            taskId.isEmpty() -> null
            else -> tasks[taskId] ?: return ForceOutcome.NotFound(taskId)
        }

        if (task != null) {
            if (mode.skillType != null && mode.skillType != task.skillType) {
                return ForceOutcome.Rejected(
                    "모드가 지목한 스킬과 태스크의 스킬이 다르다: " +
                        "${mode.skillType} != ${task.skillType}",
                )
            }
            // **종착 넷은 래치되어 있고**(§4.4), `ACCEPTED`는 스킬 인스턴스가
            // 없어 §4.5의 대응표에 halt가 없다. `RETRIABLE`·
            // `NEEDS_INTERVENTION`도 스킬이 `READY`라 정지시킬 것이 없다.
            if (task.machine.state !in FAULTABLE) {
                return ForceOutcome.Rejected(
                    "${task.machine.state} 에서는 결함을 받지 않는다(받는 상태: $FAULTABLE)",
                )
            }
        }

        // **결함을 먼저 올리고 알린 뒤에 태스크를 보낸다** — tick()과 같은
        // 순서다. 원인이 결과보다 먼저 나가야 소비자가 원인 없는 실패를
        // 보지 않는다.
        val fault = FailureDraw.faultOf(mode, task?.skillType.orEmpty(), taskId)
        val raised = faults.raise(fault)?.also { listener.onFault(it, cleared = false) } != null

        if (task != null) {
            halt(task, FailureDraw.resolutionOf(mode.resolution))
            record(task)
        }
        return ForceOutcome.Raised(raised, task?.machine?.state)
    }

    /**
     * 스킬을 정지시키고 **기체 수준 뒤처리까지 한다**(완료 기준 8b).
     *
     * **이 파일에서 `onSkillHalted`를 부르는 곳은 여기 하나다.** 뒤처리를
     * 호출자마다 두면 나중에 생기는 halt 경로가 결함 없이 복구 실패를
     * 만들고, 그때는 "동반한다"가 경로에 따라 참이 된다.
     *
     * ## 복구 실패의 결함은 엔진이 낸다
     *
     * §4.4가 *"후자는 거의 언제나 로봇 수준 결함을 동반하며, 그 결함이
     * `can_accept_new_task=false`를 든다"*고 했다. 그것을 밖에서 넣게 두면
     * 완료 기준 8b의 "동반한다"를 **시험이 자기가 넣은 것으로** 확인하게
     * 된다.
     *
     * **순서는 종착이 먼저, 결함이 나중이다.** `tick()`의 원인→결과와
     * 반대로 보이지만 아니다 — 복구 실패를 일으킨 원인은 이미 나간 그
     * 결함이고, 이것은 **복구가 실패했다는 사실의 결과**라 종착보다 먼저
     * 낼 수가 없다.
     */
    private fun halt(task: TaskRuntime, resolution: Resolution) {
        task.machine.onSkillHalted(resolution)
        if (task.machine.state != TaskState.CANCELLED_RECOVERY_FAILED) return
        faults.raise(recoveryFailed())?.let { listener.onFault(it, cleared = false) }
    }

    /** 현재 상태를 로그에 한 줄 적는다. */
    fun record(task: TaskRuntime): TaskUpdate = task.log.record(
        state = task.machine.state,
        revision = task.machine.revision,
        attempt = task.machine.attempt,
        progress = task.machine.progress(),
        occurredAt = clock.now(),
    )

    /**
     * 이 태스크가 얼마나 걸릴지. §10.4 ②의 소요시간 지터가 여기서 붙는다.
     *
     * **태스크 생성 때 한 번만 뽑는다** — [start]가 새 기체를 세울 때만
     * 부르기 때문이다. 진행률을 물을 때마다 뽑으면 **관측이 소요시간을
     * 바꾸고**(§12.1), 갱신마다 뽑으면 같은 revision을 다시 보내는 멱등
     * 재전송이 소요시간을 바꾼다(§4.4가 그것을 같은 핸들이라고 못박았다).
     * 재시도로 스킬이 다시 서도 다시 뽑지 않는다 — 프로파일은 **스킬당**
     * 소요시간을 선언하지 시도당 소요시간을 선언하지 않는다.
     *
     * `jitter_ratio`가 0이면 [Seeded.jitter]가 난수를 건드리지 않는다.
     * 인출 수가 프로파일에 달리는 것은 괜찮다 — 프로파일이 입력이다.
     * 안 되는 것은 인출 수가 **관측**에 달리는 것이다.
     */
    private companion object {

        /**
         * 복구 실패가 동반하는 **로봇 수준** 결함(§4.4).
         *
         * | 필드 | 값 | 왜 |
         * |---|---|---|
         * | `error_type` | `INTERNAL_ERROR` | 코어 여섯 중 "그 밖"이다. 벤더 접두사를 붙이면 기종마다 달라져 소비자가 분기한다 |
         * | 두 불리언 | `false`/`false` | "로봇이 물건을 든 채 멈춰 있다"가 §4.4가 `CANCELLED`와 이것을 나눈 이유다 |
         * | `references` | 없음 | 되돌리기에 실패한 것은 **기체**이지 스킬이 아니다 |
         * | `active_until` | `UNTIL_CLEARED` | `UNTIL_NEW_TASK`는 틀렸다 — 새 태스크를 받는다고 로봇이 물건을 내려놓지 않는다 |
         *
         * **막지는 않는다.** `can_accept_new_task=false`인데 새 태스크가 오면
         * 엔진은 그대로 받는다 — §4.6이 "판단은 밖으로, 사실은 안으로"라
         * 했으므로 그것은 소비자의 판단이고, 막으면 계약이 정책을 갖는다.
         */
        fun recoveryFailed(): Fault = Fault.newBuilder()
            .setErrorType("INTERNAL_ERROR")
            .setCanContinueCurrentTask(false)
            .setCanAcceptNewTask(false)
            .setErrorHint(
                "취소한 태스크의 되돌리기에 실패했다. 로봇이 대상을 든 채 멈춰 있을 수 " +
                    "있으니 현장에서 상태를 확인하고 결함을 해소하십시오.",
            )
            .setActiveUntil(Lifetime.newBuilder().setKind(Lifetime.Kind.KIND_UNTIL_CLEARED))
            .build()

        /**
         * `ForceFault`를 받는 태스크 상태(§4.5의 대응표).
         *
         * **열 중 셋이다.** 종착 넷은 래치되어 있고, `ACCEPTED`는 스킬
         * 인스턴스가 아직 없으며, `RETRIABLE`·`NEEDS_INTERVENTION`은 스킬이
         * `READY`라 정지시킬 것이 없다.
         */
        val FAULTABLE = setOf(TaskState.RUNNING, TaskState.PAUSED, TaskState.CANCELLING)
    }

    private fun durationOf(skillType: String): Double {
        val entry = document.durations.firstOrNull { it.skillType == skillType }
        // 프로파일이 소요시간을 선언하지 않은 스킬은 즉시 끝나는 것으로
        // 두지 않는다 — 그러면 진행률이 관측 불가능해진다.
            ?: error("프로파일이 소요시간을 선언하지 않았다: $skillType")
        return random.jitter(entry.seconds, entry.jitterRatio)
    }
}
