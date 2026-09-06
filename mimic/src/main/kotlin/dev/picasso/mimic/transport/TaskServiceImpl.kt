package dev.picasso.mimic.transport

import com.google.protobuf.Descriptors
import dev.picasso.contracts.v1.CancelTaskRequest
import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.PauseTaskRequest
import dev.picasso.contracts.v1.PauseTaskResponse
import dev.picasso.contracts.v1.Reference
import dev.picasso.contracts.v1.Rejection
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.ResumeTaskRequest
import dev.picasso.contracts.v1.ResumeTaskResponse
import dev.picasso.contracts.v1.RetryTaskRequest
import dev.picasso.contracts.v1.RetryTaskResponse
import dev.picasso.contracts.v1.StartTaskRequest
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskServiceGrpc
import dev.picasso.contracts.v1.WatchTaskRequest
import dev.picasso.contracts.v1.WatchTaskResponse
import dev.picasso.mimic.engine.StartOutcome
import dev.picasso.mimic.engine.toProto
import dev.picasso.mimic.engine.TaskCommand
import dev.picasso.mimic.engine.TaskRuntime
import dev.picasso.mimic.engine.TaskTransition
import dev.picasso.mimic.engine.TaskUpdate
import io.grpc.Status
import io.grpc.stub.StreamObserver
import java.time.format.DateTimeFormatter

/**
 * §4.4의 여섯 RPC.
 *
 * **판정은 응답 `oneof`, 요청을 해석하지 못한 것은 gRPC 상태다.** 자리를
 * RPC마다 달리 정하면 소비자가 표면마다 다른 분기를 쓰게 되고, 그것이
 * `task.proto`가 "표면마다 bool/state/oneof를 섞지 않는다"고 한 이유다.
 *
 * `tick()`을 **모든 RPC 진입에서** 부른다. 배경 스레드를 두면 가상 시계와
 * 충돌해 §12.1의 결정성이 깨진다(§10.3).
 */
class TaskServiceImpl(
    private val registry: RobotRegistry,
) : TaskServiceGrpc.TaskServiceImplBase() {

    /** 열려 있는 `WatchTask` 스트림. `(robotId, taskId)` → 관측자들. */
    private val watchers = mutableMapOf<Pair<String, String>, MutableList<Watcher>>()

    private class Watcher(
        val hosted: RobotRegistry.Hosted,
        val observer: StreamObserver<WatchTaskResponse>,
        /**
         * 다음에 보낼 색인. **커서를 안 두고 마지막 것만 밀면** 한 번의
         * 정착이 갱신 둘을 만들었을 때 앞엣것이 사라져 `update_index`에
         * 구멍이 난다 — 소비자는 그것을 결손으로 읽는다.
         */
        var nextIndex: Long,
    )

    // ── StartTask

    override fun startTask(
        request: StartTaskRequest,
        observer: StreamObserver<StartTaskResponse>,
    ) = reply(observer) {
        val hosted = enter(request.header)
        identity(request.header, request.robotId)?.let { rejection ->
            return@reply StartTaskResponse.newBuilder()
                .setHeader(header(hosted, StartTaskResponse.getDescriptor()))
                .setRejection(rejection).build()
        }

        val before = hosted.instance.tasks.find(request.taskId)?.log?.size
        val outcome = hosted.instance.tasks.start(
            request.taskId,
            request.revision,
            request.skillType,
            request.parametersList,
        )

        val builder = StartTaskResponse.newBuilder()
            .setHeader(header(hosted, StartTaskResponse.getDescriptor()))

        when (outcome) {
            is StartOutcome.Accepted -> builder.setHandle(handleOf(outcome.task, hosted))
            is StartOutcome.Idempotent -> {
                // 같은 핸들을 그대로 돌려준다(§4.4). **로그에 아무것도 더하지
                // 않는다** — 더하면 WatchTask가 유령 갱신을 흘리고 소비자의
                // 멱등 처리가 무의미해진다.
                check(before == outcome.task.log.size) { "멱등 재수신이 로그를 늘렸다" }
                builder.setHandle(handleOf(outcome.task, hosted))
            }
            is StartOutcome.Rejected -> builder.setRejection(rejectionOf(outcome, request.taskId))
        }

        publish(hosted, request.taskId)
        builder.build()
    }

    // ── ack 넷

    override fun retryTask(request: RetryTaskRequest, observer: StreamObserver<RetryTaskResponse>) =
        reply(observer) {
            val (hosted, result) = command(request.header, request.handle, TaskCommand.RETRY)
            RetryTaskResponse.newBuilder()
                .setHeader(header(hosted, RetryTaskResponse.getDescriptor()))
                .also { result.applyTo(it::setState, it::setRejection) }
                .build()
        }

    override fun pauseTask(request: PauseTaskRequest, observer: StreamObserver<PauseTaskResponse>) =
        reply(observer) {
            val (hosted, result) = command(request.header, request.handle, TaskCommand.PAUSE)
            PauseTaskResponse.newBuilder()
                .setHeader(header(hosted, PauseTaskResponse.getDescriptor()))
                .also { result.applyTo(it::setState, it::setRejection) }
                .build()
        }

    override fun resumeTask(request: ResumeTaskRequest, observer: StreamObserver<ResumeTaskResponse>) =
        reply(observer) {
            val (hosted, result) = command(request.header, request.handle, TaskCommand.RESUME)
            ResumeTaskResponse.newBuilder()
                .setHeader(header(hosted, ResumeTaskResponse.getDescriptor()))
                .also { result.applyTo(it::setState, it::setRejection) }
                .build()
        }

    override fun cancelTask(request: CancelTaskRequest, observer: StreamObserver<CancelTaskResponse>) =
        reply(observer) {
            val (hosted, result) = command(request.header, request.handle, TaskCommand.CANCEL)
            CancelTaskResponse.newBuilder()
                .setHeader(header(hosted, CancelTaskResponse.getDescriptor()))
                .also { result.applyTo(it::setState, it::setRejection) }
                .build()
        }

    // ── WatchTask

    override fun watchTask(
        request: WatchTaskRequest,
        observer: StreamObserver<WatchTaskResponse>,
    ) {
        val hosted = try {
            enter(request.header)
        } catch (e: io.grpc.StatusRuntimeException) {
            observer.onError(e); return
        }

        val task = hosted.instance.tasks.find(request.handle.taskId) ?: run {
            observer.onError(
                Status.NOT_FOUND
                    .withDescription("모르는 태스크다: ${request.handle.taskId}")
                    .asRuntimeException(),
            )
            return
        }

        val backlog = try {
            task.log.from(request.fromUpdateIndex)
        } catch (e: IllegalArgumentException) {
            // 조용히 빈 목록을 주면 재접속한 소비자가 오지 않을 갱신을
            // 영원히 기다린다.
            observer.onError(
                Status.OUT_OF_RANGE.withDescription(e.message).asRuntimeException(),
            )
            return
        }

        backlog.forEach { observer.onNext(responseOf(hosted, task, it)) }

        if (task.machine.state.isTerminal) {
            observer.onCompleted()
            return
        }
        // 아직 안 끝났으면 열어 두고 이후 전이를 밀어 준다.
        watchers.getOrPut(hosted.instance.robotId to task.taskId) { mutableListOf() }
            .add(Watcher(hosted, observer, task.log.size.toLong()))
    }

    // ── 내부

    /**
     * 라우팅 + 계약 개정판 차단 + **정착**. 모든 RPC가 여기를 지난다.
     *
     * 정착은 `tick()`과 **밀어내기가 한 쌍**이다. 밀어내기를 명령 RPC에만
     * 두면 `WatchTask`나 다른 RPC의 `tick()`이 만든 전이를 열려 있는
     * 스트림이 통째로 놓치고, 소비자는 그 자리를 결손으로 읽는다(리뷰 실측).
     */
    private fun enter(header: MessageHeader): RobotRegistry.Hosted =
        registry.require(header).also { settle(it) }

    /**
     * 한 기체를 정착시킨다 — 시간이 만든 전이를 반영하고 열린 스트림에 민다.
     *
     * **`MimicServer.advance`가 이것을 부른다.** Chunk 6의 `AdvanceClock`도
     * 같은 함수로 내려와야 한다 — 제어 채널이 따로 전진 경로를 만들면
     * 시험과 운영이 서로 다른 코드로 시간을 흘리게 된다.
     */
    fun settle(hosted: RobotRegistry.Hosted) {
        hosted.instance.tasks.tick()
        hosted.instance.tasks.all.forEach { publish(hosted, it.taskId) }
    }

    fun settleAll() = registry.hosted.forEach(::settle)

    /**
     * 헤더가 권위이고 페이로드는 복사본이다(§5.5).
     *
     * **`Negotiate`와 같은 코드를 쓴다** — 같은 사실을 표면마다 다른 기제로
     * 알리면 소비자가 두 가지 분기를 쓰게 된다.
     */
    private fun identity(header: MessageHeader, payloadRobotId: String): Rejection? =
        if (payloadRobotId.isNotBlank() && payloadRobotId != header.robotId) {
            Rejection.newBuilder()
                .setCode(RejectionCode.REJECTION_CODE_IDENTITY_MISMATCH)
                .setDetail(
                    "robot_id가 헤더와 다르다: 헤더='${header.robotId}', 페이로드='$payloadRobotId'",
                )
                .addReferences(
                    Reference.newBuilder()
                        .setKey(Reference.Key.KEY_ROBOT_ID).setValue(header.robotId),
                )
                .build()
        } else {
            null
        }

    private class CommandResult(
        val state: dev.picasso.contracts.v1.TaskState?,
        val rejection: Rejection?,
    ) {
        fun applyTo(
            setState: (dev.picasso.contracts.v1.TaskState) -> Unit,
            setRejection: (Rejection) -> Unit,
        ) {
            state?.let(setState)
            rejection?.let(setRejection)
        }
    }

    private fun command(
        header: MessageHeader,
        handle: TaskHandle,
        command: TaskCommand,
    ): Pair<RobotRegistry.Hosted, CommandResult> {
        val hosted = enter(header)

        identity(header, handle.robotId)?.let { return hosted to CommandResult(null, it) }

        val task = hosted.instance.tasks.find(handle.taskId)
            ?: throw Status.NOT_FOUND
                .withDescription("모르는 태스크다: ${handle.taskId}")
                .asRuntimeException()

        if (handle.revision < task.machine.revision) {
            return hosted to CommandResult(
                null,
                reject(
                    RejectionCode.REJECTION_CODE_OUTDATED_REVISION,
                    "이미 지난 revision이다: 핸들=${handle.revision}, 현재=${task.machine.revision}",
                    task.taskId,
                ),
            )
        }

        val result = when (val transition = task.machine.apply(command)) {
            is TaskTransition.Moved -> {
                hosted.instance.tasks.record(task)
                CommandResult(task.machine.state.toProto(), null)
            }
            is TaskTransition.Rejected -> CommandResult(
                null,
                // **엔진의 코드를 그대로 옮긴다.** 여기서 뭉개면 소비자가
                // PAUSE_UNSUPPORTED와 INVALID_TRANSITION을 구분하지 못한다.
                reject(transition.code, transition.reason, task.taskId),
            )
        }

        publish(hosted, task.taskId)
        return hosted to result
    }

    /** 열려 있는 스트림에 **아직 안 보낸 것 전부**를 민다. 종착이면 닫는다. */
    private fun publish(hosted: RobotRegistry.Hosted, taskId: String) {
        val key = hosted.instance.robotId to taskId
        val open = watchers[key] ?: return
        val task = hosted.instance.tasks.find(taskId) ?: return

        open.forEach { watcher ->
            task.log.from(watcher.nextIndex).forEach {
                watcher.observer.onNext(responseOf(watcher.hosted, task, it))
            }
            watcher.nextIndex = task.log.size.toLong()
        }
        if (task.machine.state.isTerminal) {
            open.forEach { it.observer.onCompleted() }
            watchers.remove(key)
        }
    }

    private fun responseOf(
        hosted: RobotRegistry.Hosted,
        task: TaskRuntime,
        update: TaskUpdate,
    ): WatchTaskResponse = WatchTaskResponse.newBuilder()
        .setHeader(
            hosted.headers.forResponse(
                WatchTaskResponse.getDescriptor(),
                updateIndex = update.updateIndex,
                // **그때의 시각을 싣는다.** 되짚어 보내면서 지금 시각을
                // 실으면 30초 전 전이가 방금 일어난 것으로 보이고 소비자의
                // 신선도 판정이 거짓말을 한다(§5.5).
                stateAsOf = DateTimeFormatter.ISO_INSTANT.format(update.occurredAt),
            ),
        )
        .setState(update.state.toProto())
        .setRevision(update.revision)
        .setAttempt(update.attempt)
        .setProgress(update.progress)
        .setPartialResult(update.partialResult)
        .build()

    private fun handleOf(task: TaskRuntime, hosted: RobotRegistry.Hosted): TaskHandle =
        TaskHandle.newBuilder()
            .setTaskId(task.taskId)
            .setRevision(task.machine.revision)
            .setRobotId(hosted.instance.robotId)
            .build()

    private fun header(hosted: RobotRegistry.Hosted, descriptor: Descriptors.Descriptor) =
        hosted.headers.forResponse(descriptor)

    private fun rejectionOf(outcome: StartOutcome.Rejected, taskId: String): Rejection =
        reject(outcome.code, outcome.detail, taskId, outcome.parameterKeys)

    private fun reject(
        code: RejectionCode,
        detail: String,
        taskId: String,
        parameterKeys: List<String> = emptyList(),
    ): Rejection = Rejection.newBuilder()
        .setCode(code)
        .setDetail(detail)
        .addReferences(
            // 어느 태스크의 거절인지 응답만 보고 알 수 있어야 한다.
            Reference.newBuilder().setKey(Reference.Key.KEY_TASK_ID).setValue(taskId),
        )
        .also { builder ->
            parameterKeys.forEach {
                builder.addReferences(
                    Reference.newBuilder().setKey(Reference.Key.KEY_PARAMETER_KEY).setValue(it),
                )
            }
        }
        .build()
}
