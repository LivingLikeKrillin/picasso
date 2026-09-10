package dev.picasso.adapter.host

import dev.picasso.adapter.core.Applied
import dev.picasso.adapter.core.Refusal
import dev.picasso.adapter.core.SiteNames
import dev.picasso.contracts.v1.CancelTaskRequest
import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.EventServiceGrpc
import dev.picasso.contracts.v1.GetCapabilitiesRequest
import dev.picasso.contracts.v1.GetCapabilitiesResponse
import dev.picasso.contracts.v1.GetKnownSiteNamesRequest
import dev.picasso.contracts.v1.GetKnownSiteNamesResponse
import dev.picasso.contracts.v1.GetSnapshotRequest
import dev.picasso.contracts.v1.GetSnapshotResponse
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.NegotiateRequest
import dev.picasso.contracts.v1.NegotiateResponse
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.PauseTaskRequest
import dev.picasso.contracts.v1.PauseTaskResponse
import dev.picasso.contracts.v1.Rejection
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.ReplayEventsRequest
import dev.picasso.contracts.v1.ReplayEventsResponse
import dev.picasso.contracts.v1.ResumeTaskRequest
import dev.picasso.contracts.v1.ResumeTaskResponse
import dev.picasso.contracts.v1.RetryTaskRequest
import dev.picasso.contracts.v1.RetryTaskResponse
import dev.picasso.contracts.v1.SkillServiceGrpc
import dev.picasso.contracts.v1.StartTaskRequest
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskServiceGrpc
import dev.picasso.contracts.v1.TaskSnapshot
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.WatchTaskRequest
import dev.picasso.contracts.v1.WatchTaskResponse
import dev.picasso.capability.Negotiation
import dev.picasso.capability.Negotiator
import dev.picasso.contracts.wire.RequestHeaders
import dev.picasso.uplink.report.HandshakeReport
import dev.picasso.uplink.report.HandshakeReporter
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver

/** 단항 RPC 하나를 응답한다 — 새어 나온 `StatusRuntimeException` 을 `UNKNOWN` 으로 접히지 않게(미믹과 같은 이유). */
internal inline fun <T> reply(observer: StreamObserver<T>, block: () -> T) {
    try {
        observer.onNext(block())
        observer.onCompleted()
    } catch (e: StatusRuntimeException) {
        observer.onError(e)
    }
}

/**
 * 라우팅 — 계약 개정판 차단이 먼저, 그 다음 `robot_id`.
 *
 * **기체 여럿을 들 수 있다.** 플릿 뒤에는 기체가 여럿이고(§15.106) 계약은 요청 헤더의 `robot_id` 로 그중 하나를
 * 지목한다 — 미믹이 한 프로세스에 여럿을 호스팅하는 것과 같은 편의이며 같은 단서가 붙는다: **PoC 의 편의이지
 * 아키텍처 주장이 아니다**(ADR 21). 그리고 **모든 RPC 가 펌프를
 * 먼저 지난다** — 그러지 않으면 열린 `WatchTask` 가 다른 RPC 가 만든 전이를 놓친다.
 */
internal class Routing(private val robots: Map<String, HostedRobot>) {

    /** 기체 하나짜리 배치 — 시험과 직결 어댑터가 쓴다. */
    constructor(robot: HostedRobot) : this(mapOf(robot.robotId to robot))

    fun enter(header: MessageHeader): HostedRobot {
        val compatibility = RequestHeaders.compatibility(header)
        if (compatibility.blocking) {
            throw Status.FAILED_PRECONDITION.withDescription("계약 개정판이 호환되지 않는다: $compatibility").asRuntimeException()
        }
        if (header.robotId.isBlank()) {
            throw Status.INVALID_ARGUMENT.withDescription("요청 헤더에 robot_id가 없다 — §5.5는 gRPC 요청에 싣는다").asRuntimeException()
        }
        val robot = robots[header.robotId]
            ?: throw Status.NOT_FOUND.withDescription(
                "호스팅하지 않는 기체다: ${header.robotId} (있는 것: ${robots.keys.sorted()})",
            ).asRuntimeException()
        robot.pump()
        return robot
    }
}

// ── TaskService (§4.4)

internal class HostTaskService(robots: Map<String, HostedRobot>) : TaskServiceGrpc.TaskServiceImplBase() {

    constructor(robot: HostedRobot) : this(mapOf(robot.robotId to robot))

    private val routing = Routing(robots)

    private class Watcher(val observer: StreamObserver<WatchTaskResponse>, var nextIndex: Long)

    private val watchers = mutableMapOf<String, MutableList<Watcher>>()

    init {
        // **기체마다 매단다.** 스트림은 태스크 단위이고 태스크는 기체의 것이므로, 미는 쪽이 어느 기체인지 알아야
        // 헤더(세션·`profile_ref`)를 그 기체의 것으로 쓸 수 있다.
        robots.values.forEach { hosted -> hosted.onUpdate += { task, _ -> push(hosted, task) } }
    }

    private fun push(robot: HostedRobot, task: HostedRobot.HostedTask) {
        val open = watchers[task.taskId] ?: return
        open.forEach { watcher ->
            task.updates.drop(watcher.nextIndex.toInt()).forEach { watcher.observer.onNext(responseOf(robot, task, it)) }
            watcher.nextIndex = task.updates.size.toLong()
        }
        if (task.terminal) {
            open.forEach { it.observer.onCompleted() }
            watchers.remove(task.taskId)
        }
    }

    override fun startTask(request: StartTaskRequest, observer: StreamObserver<StartTaskResponse>) = reply(observer) {
        val robot = routing.enter(request.header)
        val builder = StartTaskResponse.newBuilder().setHeader(robot.header(StartTaskResponse.getDescriptor()))
        identity(request.header, request.robotId)?.let { return@reply builder.setRejection(it).build() }

        when (val outcome = robot.start(request.taskId, request.revision, request.skillType, parametersOf(request.parametersList))) {
            is HostedRobot.StartOutcome.Accepted -> builder.setHandle(handleOf(robot, outcome.task))
            is HostedRobot.StartOutcome.Idempotent -> builder.setHandle(handleOf(robot, outcome.task))
            is HostedRobot.StartOutcome.Rejected -> builder.setRejection(rejection(outcome.code, outcome.detail))
            is HostedRobot.StartOutcome.Unavailable -> throw outcome.status.asRuntimeException()
        }
        builder.build()
    }

    override fun watchTask(request: WatchTaskRequest, observer: StreamObserver<WatchTaskResponse>) {
        // **괄호가 있어야 한다.** `a to b ?: c` 는 `(a to b) ?: c` 로 읽혀 쌍이 널이 아니므로 엘비스가 안 걸린다 —
        // 그러면 태스크가 널인 채로 흘러간다(실측으로 컴파일러가 잡았다).
        val (robot, task) = try {
            val hosted = routing.enter(request.header)
            hosted to (
                hosted.task(request.handle.taskId)
                    ?: throw Status.NOT_FOUND.withDescription("모르는 태스크다: ${request.handle.taskId}").asRuntimeException()
                )
        } catch (e: StatusRuntimeException) {
            observer.onError(e); return
        }
        val from = request.fromUpdateIndex
        if (from > task.updates.size) {
            observer.onError(Status.OUT_OF_RANGE.withDescription("아직 없는 색인이다: $from (있는 것: 0..${task.updates.size - 1})").asRuntimeException())
            return
        }
        task.updates.drop(from.toInt()).forEach { observer.onNext(responseOf(robot, task, it)) }
        if (task.terminal) {
            observer.onCompleted()
            return
        }
        watchers.getOrPut(task.taskId) { mutableListOf() }.add(Watcher(observer, task.updates.size.toLong()))
    }

    override fun pauseTask(request: PauseTaskRequest, observer: StreamObserver<PauseTaskResponse>) = reply(observer) {
        val robot = routing.enter(request.header)
        val (state, rejection) = manipulate(robot, request.handle, "pause") { robot.adapter.pause() }
        PauseTaskResponse.newBuilder().setHeader(robot.header(PauseTaskResponse.getDescriptor()))
            .also { b -> state?.let(b::setState); rejection?.let(b::setRejection) }.build()
    }

    override fun resumeTask(request: ResumeTaskRequest, observer: StreamObserver<ResumeTaskResponse>) = reply(observer) {
        val robot = routing.enter(request.header)
        // **지금 파라미터로** 다시 시작한다 — 멈춘 동안 갱신이 왔으면 그것이다(§4.4).
        val (state, rejection) = manipulate(robot, request.handle, "resume") { robot.adapter.resume(it.parameters) }
        ResumeTaskResponse.newBuilder().setHeader(robot.header(ResumeTaskResponse.getDescriptor()))
            .also { b -> state?.let(b::setState); rejection?.let(b::setRejection) }.build()
    }

    override fun cancelTask(request: CancelTaskRequest, observer: StreamObserver<CancelTaskResponse>) = reply(observer) {
        val robot = routing.enter(request.header)
        val (state, rejection) = manipulate(robot, request.handle, "cancel") { robot.adapter.cancel() }
        CancelTaskResponse.newBuilder().setHeader(robot.header(CancelTaskResponse.getDescriptor()))
            .also { b -> state?.let(b::setState); rejection?.let(b::setRejection) }.build()
    }

    override fun retryTask(request: RetryTaskRequest, observer: StreamObserver<RetryTaskResponse>) = reply(observer) {
        val robot = routing.enter(request.header)
        val (state, rejection) = manipulate(robot, request.handle, "retry") { robot.adapter.retry(it.parameters) }
        RetryTaskResponse.newBuilder().setHeader(robot.header(RetryTaskResponse.getDescriptor()))
            .also { b -> state?.let(b::setState); rejection?.let(b::setRejection) }.build()
    }

    /**
     * 조작 넷의 공통 — 태스크를 찾고, 종착이면 래치(§4.4), 아니면 어댑터에 시키고 그 답을 계약으로 옮긴다.
     *
     * 어댑터가 *수단이 없다* 고 하면 그 조작의 코드(`PAUSE_UNSUPPORTED`·`CANCEL_UNSUPPORTED`)다 — 지원하지 않는 것을
     * 지원하는 것처럼 감추지 않는다. 재개·재시도에는 그런 코드가 없어 `INVALID_TRANSITION` 에 사정을 붙인다.
     */
    private fun manipulate(
        robot: HostedRobot,
        handle: TaskHandle,
        what: String,
        apply: (HostedRobot.HostedTask) -> Applied,
    ): Pair<TaskState?, Rejection?> {
        val task = robot.task(handle.taskId)
            ?: throw Status.NOT_FOUND.withDescription("모르는 태스크다: ${handle.taskId}").asRuntimeException()
        if (task.terminal) {
            return null to rejection(RejectionCode.REJECTION_CODE_INVALID_TRANSITION, "${task.taskId} 은 이미 ${task.last.state} 다 — 종착은 되돌아가지 않는다(§4.4)")
        }
        return when (val applied = apply(task)) {
            Applied.Ok -> {
                robot.syncCurrent()
                task.last.state to null
            }
            is Applied.Refused -> when (applied.reason) {
                Refusal.NO_VENDOR_PRIMITIVE -> when (what) {
                    "pause" -> null to rejection(RejectionCode.REJECTION_CODE_PAUSE_UNSUPPORTED, applied.detail)
                    "cancel" -> null to rejection(RejectionCode.REJECTION_CODE_CANCEL_UNSUPPORTED, applied.detail)
                    else -> null to rejection(RejectionCode.REJECTION_CODE_INVALID_TRANSITION, "$what: ${applied.detail}")
                }
                Refusal.LINK_ERROR -> throw Status.UNAVAILABLE.withDescription(applied.detail).asRuntimeException()
                Refusal.VENDOR_SURFACE_ABSENT -> throw Status.UNAVAILABLE.withDescription(applied.detail).asRuntimeException()
                else -> {
                    robot.syncCurrent()
                    null to rejection(RejectionCode.REJECTION_CODE_INVALID_TRANSITION, "$what: ${applied.detail}")
                }
            }
        }
    }

    private fun identity(header: MessageHeader, payloadRobotId: String): Rejection? =
        if (payloadRobotId.isNotBlank() && payloadRobotId != header.robotId) {
            rejection(RejectionCode.REJECTION_CODE_IDENTITY_MISMATCH, "헤더와 페이로드의 robot_id가 다르다: 헤더='${header.robotId}', 페이로드='$payloadRobotId'")
        } else {
            null
        }

    private fun handleOf(robot: HostedRobot, task: HostedRobot.HostedTask): TaskHandle =
        TaskHandle.newBuilder().setTaskId(task.taskId).setRevision(task.revision).setRobotId(robot.robotId).build()

    private fun responseOf(robot: HostedRobot, task: HostedRobot.HostedTask, update: HostedRobot.TaskUpdate): WatchTaskResponse =
        WatchTaskResponse.newBuilder()
            .setHeader(robot.header(WatchTaskResponse.getDescriptor(), updateIndex = update.index, stateAsOf = update.occurredAt))
            .setState(update.state)
            .setRevision(update.revision)
            .setAttempt(update.attempt)
            .setProgress(update.progress)
            .setPartialResult(update.partialResult)
            .setHold(update.hold)
            .setProgressBasis(update.progressBasis)
            .also { b -> update.fault?.let(b::setFault) }
            .build()

    private companion object {
        fun rejection(code: RejectionCode, detail: String): Rejection = Rejection.newBuilder().setCode(code).setDetail(detail).build()

        /** 계약의 값 → 어댑터가 받는 값. 타입은 계약의 oneof 가 정한다. */
        fun parametersOf(values: List<ParameterValue>): Map<String, Any> = values.associate { p ->
            p.key to when (p.valueCase) {
                ParameterValue.ValueCase.BOOL_VALUE -> p.boolValue
                ParameterValue.ValueCase.INTEGER_VALUE -> p.integerValue
                ParameterValue.ValueCase.NUMBER_VALUE -> p.numberValue
                ParameterValue.ValueCase.STRING_VALUE -> p.stringValue
                ParameterValue.ValueCase.VALUE_NOT_SET, null -> ""
            }
        }
    }
}

// ── SkillService (§5.4)

internal class HostSkillService(
    robots: Map<String, HostedRobot>,
    /**
     * §5.4 — *"결과는 성공·실패 모두 `registry`에 보고된다."* 기본값이 [HandshakeReporter.NONE] 인 것은 §3.2 가
     * 이 방향을 **런타임 접근**으로 두었기 때문이다: 레지스트리가 없어도 호스트는 돈다.
     */
    private val reporter: HandshakeReporter = HandshakeReporter.NONE,
) : SkillServiceGrpc.SkillServiceImplBase() {

    constructor(robot: HostedRobot, reporter: HandshakeReporter = HandshakeReporter.NONE) :
        this(mapOf(robot.robotId to robot), reporter)

    private val routing = Routing(robots)

    override fun getCapabilities(request: GetCapabilitiesRequest, observer: StreamObserver<GetCapabilitiesResponse>) = reply(observer) {
        val robot = routing.enter(request.header)
        if (request.robotId.isNotBlank() && request.robotId != request.header.robotId) {
            throw Status.INVALID_ARGUMENT.withDescription("헤더와 페이로드의 robot_id가 다르다").asRuntimeException()
        }
        // `robot_software` 는 싣지 않는다 — 기체에 물어 오는 사실이고 어댑터 셋 중 그것을 읽는 것이 아직 없다(Spot 은 GetRobotId 가 있으나 안 읽는다).
        GetCapabilitiesResponse.newBuilder()
            .setHeader(robot.header(GetCapabilitiesResponse.getDescriptor()))
            .setCapability(robot.capability)
            .build()
    }

    /**
     * §5.4 의 핸드셰이크. **판정은 미믹과 같은 함수**(`capability` 모듈의 `Negotiator`)이고 여기서는 계약으로 옮기고
     * 보고할 뿐이다. 둘로 두면 같은 요구 집합에 미믹이 수락하고 실물이 거절하는 일이 가능해진다(§15.100).
     *
     * **판정 기준은 프로파일의 투영이다** — `GetCapabilities` 가 돌려주는 그것과 같다. 어댑터가 그 선언을 실제로
     * 다 드는지는 여기서 확인하지 않으며, 갈리면 협상을 통과한 소비자가 `StartTask` 에서 거절당한다(C-3 적합성).
     */
    override fun negotiate(request: NegotiateRequest, observer: StreamObserver<NegotiateResponse>) = reply(observer) {
        val robot = routing.enter(request.header)

        // 판정 불가는 거절이 아니다 — 요구를 못 읽어 답 자체가 없는 것이라 응답이 아니라 gRPC 상태로 나간다.
        val judged = when (val outcome = Negotiator.negotiate(robot.capability, request.header, request.requirement)) {
            is Negotiation.Judged -> outcome
            is Negotiation.Unparseable -> throw Status.INVALID_ARGUMENT.withDescription(outcome.detail).asRuntimeException()
        }

        val response = NegotiateResponse.newBuilder()
            .setHeader(robot.header(NegotiateResponse.getDescriptor()))
            // accepted 와 거절 목록을 따로 계산하지 않는다 — 어긋나면 소비자가 통과했다고 믿는다.
            .setAccepted(judged.rejections.isEmpty())
            .addAllRejections(judged.rejections)
            .build()

        // **§5.4 — 보고 실패는 핸드셰이크 결과에 영향을 주지 않는다.** 그래서 삼킨다. 삼켜도 잃지 않는 것은
        // 폴백의 몫이고(`FileHandshakeReporter`), 삼킨 것이 조용하지 않은 것은 워터마크의 몫이다.
        runCatching { reporter.report(HandshakeReport(robot.site, request, response)) }

        response
    }

    override fun getKnownSiteNames(request: GetKnownSiteNamesRequest, observer: StreamObserver<GetKnownSiteNamesResponse>) = reply(observer) {
        val robot = routing.enter(request.header)
        val builder = GetKnownSiteNamesResponse.newBuilder().setHeader(robot.header(GetKnownSiteNamesResponse.getDescriptor()))
        when (val names = robot.adapter.knownSiteNames()) {
            SiteNames.Unsupported -> builder.setUnsupported(true)
            is SiteNames.Known -> builder
                .addAllNames(names.names.take(robot.document.maxArrayLength))
                .setTotalCount(names.names.size)
            // 못 물어봤다 — 0 개도 못 함도 아니다. 계약에 그 자리가 없어 gRPC 상태다.
            is SiteNames.Unavailable -> throw Status.UNAVAILABLE.withDescription(names.reason).asRuntimeException()
        }
        builder.build()
    }
}

// ── EventService (§4.8)

internal class HostEventService(robots: Map<String, HostedRobot>) : EventServiceGrpc.EventServiceImplBase() {

    constructor(robot: HostedRobot) : this(mapOf(robot.robotId to robot))

    private val routing = Routing(robots)

    override fun getSnapshot(request: GetSnapshotRequest, observer: StreamObserver<GetSnapshotResponse>) = reply(observer) {
        val robot = routing.enter(request.header)
        // **결함을 못 봤으면 없다고 하지 않는다.** 스냅샷에 "모름" 의 자리가 없으므로 gRPC 상태다 — 소비자는 그것을 못 물어본 것으로 다룬다.
        if (!robot.faultsObservable) {
            throw Status.UNAVAILABLE.withDescription("기체의 결함을 지금 못 본다 — 없다는 뜻이 아니다").asRuntimeException()
        }
        GetSnapshotResponse.newBuilder()
            .setHeader(robot.header(GetSnapshotResponse.getDescriptor()))
            .setSequence(robot.sequence)
            .addAllTasks(
                robot.tasks.map {
                    TaskSnapshot.newBuilder().setTaskId(it.taskId).setSkillType(it.skillType).setState(it.last.state).setRevision(it.revision).setAttempt(0).build()
                },
            )
            .addAllFaults(robot.faults.values)
            // MQTT connection 스트림과 같은 값이다(미믹의 §15.95 정정과 같은 자리).
            .setConnectionState(robot.connectionState)
            .build()
    }

    override fun replayEvents(request: ReplayEventsRequest, observer: StreamObserver<ReplayEventsResponse>) {
        val robot = try {
            routing.enter(request.header)
        } catch (e: StatusRuntimeException) {
            observer.onError(e); return
        }
        val events = robot.replay(request.fromSequence)
        if (events == null) {
            observer.onNext(
                ReplayEventsResponse.newBuilder()
                    .setHeader(robot.header(ReplayEventsResponse.getDescriptor()))
                    .setRejection(
                        Rejection.newBuilder().setCode(RejectionCode.REJECTION_CODE_SEQUENCE_EVICTED)
                            .setDetail("재생 버퍼를 벗어났다: 요청=${request.fromSequence}, 버린 것 ≤ ${robot.evictedUpTo} — GetSnapshot부터 다시 세워라"),
                    )
                    .build(),
            )
            observer.onCompleted()
            return
        }
        events.forEach {
            observer.onNext(ReplayEventsResponse.newBuilder().setHeader(robot.header(ReplayEventsResponse.getDescriptor())).setEvent(it).build())
        }
        observer.onCompleted()
    }
}
