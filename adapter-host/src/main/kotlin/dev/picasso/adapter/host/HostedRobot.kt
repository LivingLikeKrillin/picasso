package dev.picasso.adapter.host

import com.google.protobuf.Descriptors
import dev.picasso.adapter.core.Acceptance
import dev.picasso.adapter.core.FaultObservation
import dev.picasso.adapter.core.Refusal
import dev.picasso.adapter.core.RobotAdapter
import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.FaultEvent
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.ProfileRef
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.TaskTransition
import dev.picasso.contracts.wire.ContractIdentity
import dev.picasso.contracts.wire.TaskStates
import dev.picasso.profile.ProfileDocument
import dev.picasso.profile.projection.CapabilityProjection
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/**
 * 계약 뒤에 선 기체 하나 — 어댑터 하나, 프로파일 하나, 그리고 계약이 요구하는 **기록**(태스크 갱신 로그·이벤트 버퍼·
 * 헤더 열).
 *
 * ## 미믹과 무엇이 같고 무엇이 다른가
 *
 * 계약 쪽 모양은 미믹과 같아야 한다 — 같은 `WatchTask` 로그, 같은 `sequence` 축, 같은 헤더 열(§5.5). 소비자가 미믹과
 * 실물을 **엔드포인트만 바꿔** 쓰는 것이 이 저장소의 주장이고, 그 주장이 참이려면 이 층이 미믹의 계약 거동을 그대로
 * 재현해야 한다. 다른 것은 **상태의 출처**다: 미믹은 프로파일과 상태기계가 상태를 만들고, 여기는 어댑터가 로봇에게서
 * 읽어 온다. 그래서 이 클래스에는 시간이 없다 — [pump] 가 어댑터에 묻고, 달라진 것만 적는다.
 *
 * ## 펌프
 *
 * 스레드가 없다. 모든 RPC 가 [pump] 를 먼저 부르고(미믹의 `settle` 과 같은 이유 — 그러지 않으면 열린 `WatchTask` 가
 * 다른 RPC 가 만든 전이를 놓친다), 운영 배치에서는 스케줄러가 부른다. 시각은 [clock] 로만 읽는다.
 *
 * ## 이 층이 하지 않는 것
 *
 * 판단. 어댑터가 거절하면 거절이고 로봇이 실패라 하면 실패다. 무엇을 할지는 계약 소비자(미들웨어)의 일이다.
 */
class HostedRobot(
    val robotId: String,
    val document: ProfileDocument,
    val adapter: RobotAdapter,
    private val clock: () -> Instant = { Instant.now() },
) {
    val capability: Capability = CapabilityProjection.of(document)

    /** 세션 — 프로세스 안에서 유일하면 족하다(미믹과 같은 이유로 난수가 아니라 카운터). */
    val sessionId: String = "host-${SESSIONS.incrementAndGet()}"

    /** 프로파일이 바뀌지 않는 한 1 이다 — 이 호스트는 아직 런타임 능력 변경을 안 낸다. */
    val capabilityEpoch: Long = 1

    private val eventIds = AtomicLong()
    private val nextSequence = AtomicLong()
    private val buffer = ArrayDeque<Event>()

    /** 재생 버퍼에서 실제로 버려진 가장 높은 번호. 아직 아무것도 안 버렸으면 널. */
    var evictedUpTo: Long? = null
        private set

    private val book = linkedMapOf<String, HostedTask>()
    private var current: HostedTask? = null

    /** 마지막 [pump] 에서 결함을 봤는가. 거짓이면 [faults] 는 낡은 것이다 — 스냅샷은 그때 `UNAVAILABLE` 이다. */
    var faultsObservable: Boolean = true
        private set

    var faults: Map<String, Fault> = emptyMap()
        private set

    /** 갱신이 적힐 때마다 부른다 — `WatchTask` 스트림이 여기 매달린다. */
    internal val onUpdate = mutableListOf<(HostedTask, TaskUpdate) -> Unit>()

    /** 태스크 하나의 기록 — 계약의 `WatchTask` 로그. 종착은 래치된다(§4.4). */
    class HostedTask internal constructor(val taskId: String, val revision: Int, val skillType: String) {
        internal val log = mutableListOf<TaskUpdate>()
        val updates: List<TaskUpdate> get() = log.toList()
        val last: TaskUpdate get() = log.last()
        val terminal: Boolean get() = TaskStates.isTerminal(last.state)
    }

    data class TaskUpdate(
        val index: Long,
        val state: TaskState,
        val revision: Int,
        val attempt: Int,
        val progress: Double,
        val partialResult: String,
        val hold: HoldState,
        val fault: Fault?,
        val occurredAt: Instant,
    )

    sealed interface StartOutcome {
        data class Accepted(val task: HostedTask) : StartOutcome
        data class Idempotent(val task: HostedTask) : StartOutcome
        data class Rejected(val code: RejectionCode, val detail: String) : StartOutcome
        /** 계약이 답할 자리가 없는 사정 — gRPC 상태로 나간다. */
        data class Unavailable(val status: io.grpc.Status) : StartOutcome
    }

    fun now(): Instant = clock()

    fun task(taskId: String): HostedTask? = book[taskId]

    val tasks: Collection<HostedTask> get() = book.values

    val events: List<Event> get() = buffer.toList()

    val sequence: Long get() = nextSequence.get()

    // ── 접수 (§4.4 — 같은 (task_id, revision) 은 같은 핸들, 종착은 래치)

    fun start(taskId: String, revision: Int, skillType: String, parameters: Map<String, Any>): StartOutcome {
        book[taskId]?.let { existing ->
            return when {
                revision == existing.revision -> StartOutcome.Idempotent(existing)
                revision < existing.revision -> StartOutcome.Rejected(
                    RejectionCode.REJECTION_CODE_OUTDATED_REVISION,
                    "받은 revision=$revision, 현재=${existing.revision}",
                )
                existing.terminal -> StartOutcome.Rejected(
                    RejectionCode.REJECTION_CODE_INVALID_TRANSITION,
                    "${existing.taskId} 은 이미 ${existing.last.state} 다 — 종착은 되돌아가지 않는다(§4.4)",
                )
                // **도는 태스크의 갱신은 아직 안 한다.** §4.4 의 RUNNING 갱신은 Halt → Reset → Start 인데 어댑터
                // 셋 중 아무도 그 합성을 들지 않는다(§15.98 정직 항목). 지어서 되는 척하지 않는다.
                else -> StartOutcome.Rejected(
                    RejectionCode.REJECTION_CODE_INVALID_TRANSITION,
                    "이 호스트는 도는 태스크를 갱신하지 않는다 — 취소한 뒤 새 task_id 로 다시 요청하라",
                )
            }
        }

        current?.takeIf { !it.terminal }?.let {
            return StartOutcome.Rejected(
                RejectionCode.REJECTION_CODE_INVALID_TRANSITION,
                "이미 도는 태스크가 있다: ${it.taskId} — 실물은 배타적 제어 모델이다(§4.9)",
            )
        }

        validate(skillType, parameters)?.let { return it }

        return when (val accepted = adapter.accept(taskId, skillType, parameters, clock())) {
            is Acceptance.Accepted -> {
                val task = HostedTask(taskId, revision, skillType)
                book[taskId] = task
                current = task
                record(task, TaskState.TASK_STATE_ACCEPTED)
                syncCurrent()
                StartOutcome.Accepted(task)
            }

            is Acceptance.Refused -> refused(accepted)
        }
    }

    /**
     * 프로파일이 선언한 것에 대고 본다 — 선언 안 한 스킬, 빠진 필수 파라미터, 문자열 길이. **값의 범위와 허용 값은
     * 어댑터·로봇이 답한다**(미믹의 §10.4 ③ 검사를 여기서 다 흉내내지 않는다 — 여기는 로봇이 있다).
     */
    private fun validate(skillType: String, parameters: Map<String, Any>): StartOutcome.Rejected? {
        val skill = document.skills.firstOrNull { it.skillType == skillType }
            ?: return StartOutcome.Rejected(
                RejectionCode.REJECTION_CODE_SKILL_ABSENT,
                "프로파일이 선언하지 않은 스킬이다: $skillType (선언: ${document.skills.map { it.skillType }})",
            )
        val missing = skill.parameters.filter { !it.optional && it.key !in parameters }.map { it.key }
        if (missing.isNotEmpty()) {
            return StartOutcome.Rejected(RejectionCode.REJECTION_CODE_REQUIRED_OPTIONAL_MISSING, "필수 파라미터가 없다: $missing")
        }
        parameters.forEach { (key, value) ->
            if (value is String) {
                val limit = skill.parameters.firstOrNull { it.key == key }?.maxLength ?: document.maxStringLength
                if (value.length > limit) {
                    return StartOutcome.Rejected(RejectionCode.REJECTION_CODE_LIMIT_EXCEEDED, "$key 의 길이 ${value.length} 이 한도 $limit 를 넘는다")
                }
            }
        }
        return null
    }

    /**
     * 어댑터의 거절 → 계약. **어휘가 하나씩 대응하지는 않는다** — 계약의 거절 코드는 소비자의 요청이 틀린 경우를 위해
     * 만들어졌고, 어댑터의 거절에는 *로봇이 지금 못 받는다* 도 있다. 그런 것은 `INVALID_TRANSITION` 에 사정을 붙인다
     * (§15.98 정직 항목 — 계약에 그 자리가 없다). 우리 쪽 배선이 틀린 것(신원)과 남쪽이 안 닿는 것(링크)은 계약이 답할
     * 일이 아니라 gRPC 상태다.
     */
    private fun refused(refusal: Acceptance.Refused): StartOutcome = when (refusal.reason) {
        Refusal.UNSUPPORTED_SKILL -> StartOutcome.Rejected(RejectionCode.REJECTION_CODE_SKILL_ABSENT, refusal.detail)
        Refusal.PARAMETER_MISSING -> StartOutcome.Rejected(RejectionCode.REJECTION_CODE_REQUIRED_OPTIONAL_MISSING, refusal.detail)
        Refusal.SITE_NAME_UNKNOWN, Refusal.SITE_NAME_AMBIGUOUS ->
            StartOutcome.Rejected(RejectionCode.REJECTION_CODE_PARAMETER_INVALID, refusal.detail)
        Refusal.VENDOR_SURFACE_ABSENT -> StartOutcome.Rejected(RejectionCode.REJECTION_CODE_CAPABILITY_WITHDRAWN, refusal.detail)
        Refusal.ALREADY_RUNNING, Refusal.TERMINAL_LATCHED, Refusal.NO_TASK, Refusal.NO_VENDOR_PRIMITIVE ->
            StartOutcome.Rejected(RejectionCode.REJECTION_CODE_INVALID_TRANSITION, refusal.detail)
        Refusal.VENDOR_REJECTED -> StartOutcome.Rejected(
            RejectionCode.REJECTION_CODE_INVALID_TRANSITION,
            "${refusal.detail} [class=${refusal.failureClass.name.removePrefix("FAILURE_CLASS_")}; vendor=${refusal.vendorDetail}]",
        )
        Refusal.CONTROL_AUTHORITY_LOST -> StartOutcome.Rejected(RejectionCode.REJECTION_CODE_INVALID_TRANSITION, refusal.detail)
        Refusal.IDENTITY_UNSET -> StartOutcome.Unavailable(io.grpc.Status.FAILED_PRECONDITION.withDescription(refusal.detail))
        Refusal.LINK_ERROR -> StartOutcome.Unavailable(io.grpc.Status.UNAVAILABLE.withDescription(refusal.detail))
    }

    // ── 펌프

    /** 어댑터에 묻고 달라진 것을 적는다. 몇 번 불러도 같다. */
    fun pump() {
        current?.takeIf { !it.terminal }?.let { task ->
            val state = adapter.poll(clock())
            if (state != task.last.state) record(task, state)
        }
        when (val observed = adapter.faults()) {
            is FaultObservation.Observed -> {
                faultsObservable = true
                val next = observed.faults.associateBy(::faultKey)
                (next.keys - faults.keys).forEach { emit(faultEvent(next.getValue(it), cleared = false)) }
                (faults.keys - next.keys).forEach { emit(faultEvent(faults.getValue(it), cleared = true)) }
                faults = next
            }
            is FaultObservation.NotObservable -> faultsObservable = false
        }
    }

    /** 조작 뒤 — 시간을 안 흘리고 어댑터가 지금 말하는 상태만 적는다. */
    internal fun syncCurrent() {
        val task = current ?: return
        if (task.terminal) return
        val state = adapter.state
        if (state != TaskState.TASK_STATE_UNSPECIFIED && state != task.last.state) record(task, state)
    }

    private fun record(task: HostedTask, state: TaskState) {
        val failure = if (state in FAILURE_STATES) adapter.failure() else null
        val result = if (state == TaskState.TASK_STATE_SUCCEEDED) adapter.result().orEmpty() else ""
        val from = task.log.lastOrNull()?.state ?: TaskState.TASK_STATE_UNSPECIFIED
        val update = TaskUpdate(
            index = task.log.size.toLong(),
            state = state,
            revision = task.revision,
            attempt = 0,
            progress = if (state == TaskState.TASK_STATE_SUCCEEDED) 1.0 else 0.0,
            partialResult = result,
            hold = adapter.hold().toProto(),
            fault = failure,
            occurredAt = clock(),
        )
        task.log += update
        emit(
            Event.newBuilder()
                .setTaskTransition(
                    TaskTransition.newBuilder()
                        .setTaskId(task.taskId).setSkillType(task.skillType)
                        .setFrom(from).setTo(state)
                        .setRevision(task.revision).setAttempt(0),
                ),
        )
        onUpdate.forEach { it(task, update) }
    }

    private fun faultEvent(fault: Fault, cleared: Boolean): Event.Builder =
        Event.newBuilder().setFaultEvent(FaultEvent.newBuilder().setFault(fault).setCleared(cleared))

    private fun emit(builder: Event.Builder) {
        val sequence = nextSequence.getAndIncrement()
        val event = builder.setHeader(header(Event.getDescriptor()).toBuilder().setSequence(sequence)).build()
        buffer.addLast(event)
        while (buffer.size > document.replayBufferSize) {
            evictedUpTo = buffer.removeFirst().header.sequence
        }
    }

    /** `ReplayEvents(from)` — 벗어났으면 널(축출), 아니면 그 번호부터. */
    fun replay(from: Long): List<Event>? {
        val evicted = evictedUpTo
        if (evicted != null && from <= evicted) return null
        return buffer.filter { it.header.sequence >= from }
    }

    // ── 헤더 (§5.5 의 응답 열 — 미믹의 ResponseHeaders 와 같은 표)

    fun header(schema: Descriptors.Descriptor, updateIndex: Long? = null, stateAsOf: Instant? = null): MessageHeader {
        val now = clock()
        return MessageHeader.newBuilder()
            .setSchemaId(schema.fullName)
            .setContractDigest(ContractIdentity.digest)
            .setContractSemver(ContractIdentity.semver)
            .setRobotId(robotId)
            .setCapabilityEpoch(capabilityEpoch)
            .setSessionId(sessionId)
            .setProfileRef(ProfileRef.newBuilder().setProfileId("${document.vendor}/${document.model}").setRevision(document.revision))
            .setEventId("$sessionId-%08d".format(eventIds.incrementAndGet()))
            .setOccurredAt(ISO.format(now))
            .setStateAsOf(ISO.format(stateAsOf ?: now))
            .also { b -> updateIndex?.let(b::setUpdateIndex) }
            .build()
    }

    private fun faultKey(fault: Fault): String =
        fault.errorType + fault.referencesList.joinToString(prefix = "[", postfix = "]") { "${it.key}=${it.value}" }

    private companion object {
        val SESSIONS = AtomicLong()
        val ISO: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
        val FAILURE_STATES = setOf(TaskState.TASK_STATE_FAILED, TaskState.TASK_STATE_RETRIABLE, TaskState.TASK_STATE_NEEDS_INTERVENTION)
    }
}
