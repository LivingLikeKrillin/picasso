package dev.picasso.mimic.control

import dev.picasso.mimic.control.v1.AdvanceClockRequest
import dev.picasso.mimic.control.v1.AdvanceClockResponse
import dev.picasso.mimic.control.v1.ClockMode
import dev.picasso.mimic.control.v1.CapabilityChangeResponse
import dev.picasso.mimic.control.v1.ControlServiceGrpc
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import dev.picasso.mimic.control.v1.DumpInternalStateResponse
import dev.picasso.mimic.control.v1.ForceControlAuthorityLossRequest
import dev.picasso.mimic.control.v1.ForceControlAuthorityLossResponse
import dev.picasso.mimic.control.v1.ForceFaultRequest
import dev.picasso.mimic.control.v1.ForceFaultResponse
import dev.picasso.mimic.control.v1.ForceTerminalViolationRequest
import dev.picasso.mimic.control.v1.ForceTerminalViolationResponse
import dev.picasso.mimic.control.v1.InjectTransportFaultRequest
import dev.picasso.mimic.control.v1.InjectTransportFaultResponse
import dev.picasso.mimic.control.v1.InternalFault
import dev.picasso.mimic.control.v1.InternalTask
import dev.picasso.mimic.control.v1.RemoveCapabilityRequest
import dev.picasso.mimic.control.v1.RestoreCapabilityRequest
import dev.picasso.mimic.control.v1.SetClockModeRequest
import dev.picasso.mimic.control.v1.SetConnectionRequest
import dev.picasso.mimic.control.v1.SetConnectionResponse
import dev.picasso.mimic.control.v1.SetSeedRequest
import dev.picasso.mimic.control.v1.SetSeedResponse
import dev.picasso.mimic.control.v1.SetSingleStepRequest
import dev.picasso.mimic.control.v1.SetSingleStepResponse
import dev.picasso.mimic.control.v1.StepRequest
import dev.picasso.mimic.control.v1.StepResponse
import dev.picasso.mimic.control.v1.SetClockModeResponse
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.Reference
import dev.picasso.mimic.engine.AuthorityOutcome
import dev.picasso.mimic.engine.ForceOutcome
import dev.picasso.mimic.engine.RealClock
import dev.picasso.mimic.engine.ViolationOutcome
import dev.picasso.mimic.engine.VirtualClock
import dev.picasso.mimic.transport.MimicServer
import dev.picasso.mimic.transport.RobotRegistry
import dev.picasso.mimic.transport.TransportFaults
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.Status
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.StreamObserver
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.time.format.DateTimeFormatter

/**
 * §10.5의 제어 채널. **별도 포트에 두며 루프백에만 바인딩한다**(§6.3).
 *
 * 표준 계약과 같은 표면에 두면 프로덕션 소비자가 손댈 수 있고 목이 계약을
 * 오염시킨다. 그래서 proto도 `contracts/`가 아니라 `mimic/` 안에 있다.
 */
class ControlServer(
    registry: RobotRegistry,
    private val mimic: MimicServer,
    builder: ServerBuilder<*>,
) {
    private val server: Server = builder.addService(Service(registry, mimic)).build()

    val port: Int get() = server.port

    /** 바인딩된 주소들. 시험이 루프백인지 본다. */
    val boundAddresses: List<InetSocketAddress>
        get() = server.listenSockets.filterIsInstance<InetSocketAddress>()

    fun start(): ControlServer = apply { server.start() }

    fun shutdown() {
        server.shutdownNow()
    }

    companion object {
        /**
         * 루프백에만 여는 빌더.
         *
         * **`ServerBuilder.forPort`를 쓰면 모든 인터페이스에 열린다.** 주소를
         * 지정할 수 있는 것은 `NettyServerBuilder`뿐이라 netty가 컴파일
         * 의존이 된다.
         */
        fun loopback(port: Int): ServerBuilder<*> = NettyServerBuilder.forAddress(
            InetSocketAddress(InetAddress.getLoopbackAddress(), port),
        )
    }

    private class Service(
        private val registry: RobotRegistry,
        private val mimic: MimicServer,
    ) : ControlServiceGrpc.ControlServiceImplBase() {

        override fun setClockMode(
            request: SetClockModeRequest,
            observer: StreamObserver<SetClockModeResponse>,
        ) {
            // 시계 자체는 기동 때 정해진다. 여기서는 **어긋남을 알린다** —
            // 조용히 받아들이면 호출자가 가상 시계를 쓰는 줄 알고 AdvanceClock을
            // 부르다 RealClock의 예외를 만난다.
            val actual = if (registry.clocks.all { it is VirtualClock }) {
                ClockMode.CLOCK_MODE_VIRTUAL
            } else {
                ClockMode.CLOCK_MODE_REAL
            }
            if (request.mode != ClockMode.CLOCK_MODE_UNSPECIFIED && request.mode != actual) {
                observer.onError(
                    Status.FAILED_PRECONDITION
                        .withDescription("기동한 시계 모드와 다르다: 요청=${request.mode}, 실제=$actual")
                        .asRuntimeException(),
                )
                return
            }
            observer.onNext(SetClockModeResponse.newBuilder().setMode(actual).build())
            observer.onCompleted()
        }

        /**
         * **[MimicServer.advance]로 내려온다.** 제어 채널이 자기 전진 경로를
         * 따로 만들면 시험과 운영이 서로 다른 코드로 시간을 흘리게 되고,
         * 열려 있는 스트림에 미는 것을 빠뜨리면 결함이 **정지**로 나타난다.
         */
        override fun advanceClock(
            request: AdvanceClockRequest,
            observer: StreamObserver<AdvanceClockResponse>,
        ) {
            val duration = Duration.ofMillis(request.durationMillis)
            if (duration.isNegative) {
                observer.onError(
                    Status.INVALID_ARGUMENT
                        .withDescription("시계를 되감을 수 없다: $duration")
                        .asRuntimeException(),
                )
                return
            }
            if (registry.clocks.any { it is RealClock }) {
                observer.onError(
                    Status.FAILED_PRECONDITION
                        .withDescription("실시간 시계는 전진시킬 수 없다 — --clock virtual로 기동하라")
                        .asRuntimeException(),
                )
                return
            }

            mimic.advance(duration)

            observer.onNext(
                AdvanceClockResponse.newBuilder()
                    .setNow(
                        DateTimeFormatter.ISO_INSTANT.format(registry.clocks.first().now()),
                    )
                    .build(),
            )
            observer.onCompleted()
        }

        /**
         * 헤더 없이 기체를 찾는다(§10.5는 헤더를 안 싣는다).
         *
         * **못 찾으면 NOT_FOUND로 끝낸다.** 조용히 아무것도 안 하면 호출자가
         * 주입이 먹힌 줄 알고 다음 단언으로 넘어간다.
         */
        private fun <T> hosted(
            robotId: String,
            observer: StreamObserver<T>,
        ): RobotRegistry.Hosted? = registry.byId(robotId) ?: null.also {
            observer.onError(
                Status.NOT_FOUND
                    .withDescription("호스팅하지 않는 기체다: $robotId")
                    .asRuntimeException(),
            )
        }

        private fun <T> reply(observer: StreamObserver<T>, value: T) {
            observer.onNext(value)
            observer.onCompleted()
        }

        /** §12.1 — 시드가 결정성을 만든다는 주장을 시험이 흔들어 보는 문. */
        override fun setSeed(request: SetSeedRequest, observer: StreamObserver<SetSeedResponse>) {
            val hosted = hosted(request.robotId, observer) ?: return
            hosted.instance.reseed(request.seed)
            reply(observer, SetSeedResponse.newBuilder().setSeed(request.seed).build())
        }

        /**
         * §10.4 ②의 명령 주입. 추첨을 기다리지 않고 결함을 지금 세운다.
         *
         * **정착시키지 않는다.** 여기서 tick이 돌면 `CANCELLING`이던 태스크가
         * 같은 호출 안에서 `CANCELLED`로 넘어가 완료 기준 8b가 보려는 복구
         * 실패의 창이 닫힌다. 판단은 엔진에 있고 여기서는 gRPC 상태로만 옮긴다.
         */
        override fun forceFault(
            request: ForceFaultRequest,
            observer: StreamObserver<ForceFaultResponse>,
        ) {
            val hosted = hosted(request.robotId, observer) ?: return
            when (val outcome = hosted.instance.tasks.forceFault(request.errorType, request.taskId)) {
                is ForceOutcome.Raised -> reply(
                    observer,
                    ForceFaultResponse.newBuilder()
                        .setRaised(outcome.raised)
                        .setTaskState(outcome.taskState?.name.orEmpty())
                        .build(),
                ).also {
                    // **정착이 아니라 밀어내기다.** 열린 스트림이 이 전이를
                    // 놓치면 소비자는 그 자리를 결손으로 읽는다.
                    mimic.push(hosted)
                }

                is ForceOutcome.NotFound -> observer.onError(
                    Status.NOT_FOUND
                        .withDescription("호스팅하지 않는 태스크다: ${outcome.taskId}")
                        .asRuntimeException(),
                )

                is ForceOutcome.Rejected -> observer.onError(
                    Status.FAILED_PRECONDITION
                        .withDescription(outcome.detail)
                        .asRuntimeException(),
                )
            }
        }

        override fun setSingleStep(
            request: SetSingleStepRequest,
            observer: StreamObserver<SetSingleStepResponse>,
        ) {
            val hosted = hosted(request.robotId, observer) ?: return
            hosted.instance.singleStep = request.enabled
            reply(
                observer,
                SetSingleStepResponse.newBuilder().setEnabled(request.enabled).build(),
            )
        }

        /**
         * §10.5의 전송 장애 다섯.
         *
         * **모르는 이름은 거절한다.** 조용히 `NONE`으로 접으면 시험이 장애를
         * 건 줄 알고 "복원됐다"를 단언한다 — 아무 장애도 없었으므로 당연히
         * 참이고, 완료 기준 3이 통째로 공허해진다.
         */
        override fun injectTransportFault(
            request: InjectTransportFaultRequest,
            observer: StreamObserver<InjectTransportFaultResponse>,
        ) {
            val hosted = hosted(request.robotId, observer) ?: return
            val kind = TransportFaults.Kind.entries.firstOrNull { it.name == request.kind }
            if (kind == null) {
                observer.onError(
                    Status.INVALID_ARGUMENT
                        .withDescription(
                            "모르는 전송 장애다: '${request.kind}' (아는 것: " +
                                TransportFaults.Kind.entries.joinToString { it.name } + ")",
                        )
                        .asRuntimeException(),
                )
                return
            }

            val every = if (request.lossEvery == 0) hosted.instance.transport.lossEvery
            else request.lossEvery
            if (every < 2) {
                observer.onError(
                    Status.INVALID_ARGUMENT
                        .withDescription("매번 버리면 그것은 DISCONNECT다: $every")
                        .asRuntimeException(),
                )
                return
            }

            hosted.instance.transport.inject(kind, every)
            reply(
                observer,
                InjectTransportFaultResponse.newBuilder()
                    .setKind(kind.name)
                    .setLossEvery(every)
                    .build(),
            )
        }

        /**
         * §8.2의 런타임 축소.
         *
         * **선언한 적 없는 스킬은 거절한다** — 그것은 축소가 아니라 오타이고,
         * 허용하면 소비자가 "있었는데 사라졌다"로 읽는다.
         *
         * **`ForceFault`와 같은 이유로 정착시키지 않고 밀기만 한다** —
         * `CapabilityChanged`는 이벤트라 발행 축을 타지만, 진행 중이던
         * 태스크의 전이가 함께 나갈 일은 없다(축소는 태스크를 안 죽인다).
         */
        override fun removeCapability(
            request: RemoveCapabilityRequest,
            observer: StreamObserver<CapabilityChangeResponse>,
        ) = changeCapability(request.robotId, request.skillType, observer) { instance, skill ->
            instance.withdraw(skill)
        }

        override fun restoreCapability(
            request: RestoreCapabilityRequest,
            observer: StreamObserver<CapabilityChangeResponse>,
        ) = changeCapability(request.robotId, request.skillType, observer) { instance, skill ->
            instance.restore(skill)
        }

        private fun changeCapability(
            robotId: String,
            skillType: String,
            observer: StreamObserver<CapabilityChangeResponse>,
            change: (dev.picasso.mimic.RobotInstance, String) -> Boolean,
        ) {
            val hosted = hosted(robotId, observer) ?: return
            val changed = try {
                change(hosted.instance, skillType)
            } catch (e: IllegalArgumentException) {
                observer.onError(
                    Status.INVALID_ARGUMENT
                        .withDescription(e.message.orEmpty())
                        .asRuntimeException(),
                )
                return
            }
            reply(
                observer,
                CapabilityChangeResponse.newBuilder()
                    .setCapabilityEpoch(hosted.instance.capabilityEpoch)
                    .setChanged(changed)
                    .addAllAvailableSkills(
                        hosted.instance.capability.skillsList.map { it.skillType },
                    )
                    .build(),
            )
            if (changed) mimic.push(hosted)
        }

        /**
         * §4.7의 연결 상태를 강제한다.
         *
         * **모르는 이름은 거절한다.** 조용히 `UNSPECIFIED`로 접으면 시험이
         * 침묵을 만든 줄 알고 다음 단언으로 넘어간다. `UNSPECIFIED` 자체도
         * 거절한다 — 그것은 "안 실었다"이지 상태가 아니다.
         */
        override fun setConnection(
            request: SetConnectionRequest,
            observer: StreamObserver<SetConnectionResponse>,
        ) {
            val hosted = hosted(request.robotId, observer) ?: return
            val state = ConnectionState.entries.firstOrNull { it.name == request.state }
            if (state == null ||
                state == ConnectionState.CONNECTION_STATE_UNSPECIFIED ||
                state == ConnectionState.UNRECOGNIZED
            ) {
                observer.onError(
                    Status.INVALID_ARGUMENT
                        .withDescription(
                            "모르는 연결 상태다: '${request.state}' (아는 것: " +
                                ConnectionState.entries
                                    .filterNot {
                                        it == ConnectionState.UNRECOGNIZED ||
                                            it == ConnectionState.CONNECTION_STATE_UNSPECIFIED
                                    }
                                    .joinToString { it.name } + ")",
                        )
                        .asRuntimeException(),
                )
                return
            }

            val changed = hosted.instance.events.setConnection(state)
            reply(
                observer,
                SetConnectionResponse.newBuilder()
                    .setState(state.name)
                    .setChanged(changed)
                    .build(),
            )
        }

        /**
         * §4.9의 제어 권한 상실. **`ForceFault`와 같은 이유로 정착시키지 않고
         * 밀기만 한다** — 여기서는 태스크 전이가 실제로 생기므로 밀 것이 있다.
         */
        override fun forceControlAuthorityLoss(
            request: ForceControlAuthorityLossRequest,
            observer: StreamObserver<ForceControlAuthorityLossResponse>,
        ) {
            val hosted = hosted(request.robotId, observer) ?: return
            when (val outcome = hosted.instance.tasks.forceControlAuthorityLoss()) {
                is AuthorityOutcome.Lost -> reply(
                    observer,
                    ForceControlAuthorityLossResponse.newBuilder()
                        .setRaised(outcome.raised)
                        .addAllTerminatedTaskIds(outcome.terminated)
                        .build(),
                ).also { mimic.push(hosted) }

                is AuthorityOutcome.Rejected -> observer.onError(
                    Status.FAILED_PRECONDITION
                        .withDescription(outcome.detail)
                        .asRuntimeException(),
                )
            }
        }

        /**
         * §4.4의 래치 위반. **태스크를 건드리지 않는다** — 종착은 래치되며
         * 그것이 계약의 불변식이다. 판단은 엔진에 있고 여기서는 gRPC 상태로만
         * 옮긴다.
         *
         * **`ForceFault`와 달리 밀지 않는다.** 위반은 전이가 아니라
         * 관측이므로 태스크 로그에 아무것도 안 적히고, 따라서 열린
         * `WatchTask`에 밀 것이 없다. 결함은 `EngineListener` →
         * `EventStream`을 타고 발행 축으로 이미 나갔다(§4.7의 두 축).
         *
         * 실측: 여기 `push`를 두고 그것을 지우는 결함을 주입했는데 아무
         * 시험도 안 빨개졌다 — 밀 것이 없으니 당연하다. 죽은 줄이었다.
         */
        override fun forceTerminalViolation(
            request: ForceTerminalViolationRequest,
            observer: StreamObserver<ForceTerminalViolationResponse>,
        ) {
            val hosted = hosted(request.robotId, observer) ?: return
            when (val outcome = hosted.instance.tasks.forceTerminalViolation(request.taskId)) {
                is ViolationOutcome.Seen -> reply(
                    observer,
                    ForceTerminalViolationResponse.newBuilder()
                        .setTaskState(outcome.state.name)
                        .setRaised(outcome.raised)
                        .build(),
                )

                is ViolationOutcome.NotFound -> observer.onError(
                    Status.NOT_FOUND
                        .withDescription("호스팅하지 않는 태스크다: ${outcome.taskId}")
                        .asRuntimeException(),
                )

                is ViolationOutcome.Rejected -> observer.onError(
                    Status.FAILED_PRECONDITION
                        .withDescription(outcome.detail)
                        .asRuntimeException(),
                )
            }
        }

        /** **[MimicServer.step]으로 내려온다** — [advanceClock]과 같은 이유다. */
        override fun step(request: StepRequest, observer: StreamObserver<StepResponse>) {
            val hosted = hosted(request.robotId, observer) ?: return
            reply(observer, StepResponse.newBuilder().setMoved(mimic.step(hosted)).build())
        }

        /**
         * §12.2의 A-2 오라클.
         *
         * **계약 표면의 투영을 부르지 않는다.** `GetSnapshot`이 쓰는 코드를
         * 거치면 투영을 투영과 비교하는 순환이 되어, 투영이 틀려도 양쪽이
         * 똑같이 틀린다. 엔진의 상태를 직접 읽는다.
         */
        override fun dumpInternalState(
            request: DumpInternalStateRequest,
            observer: StreamObserver<DumpInternalStateResponse>,
        ) {
            val hosted = registry.byId(request.robotId) ?: run {
                observer.onError(
                    Status.NOT_FOUND
                        .withDescription("호스팅하지 않는 기체다: ${request.robotId}")
                        .asRuntimeException(),
                )
                return
            }
            val instance = hosted.instance

            observer.onNext(
                DumpInternalStateResponse.newBuilder()
                    .setRobotId(instance.robotId)
                    .setSessionId(instance.sessionId)
                    .setNextSequence(instance.events.nextSequence)
                    .setCapabilityEpoch(instance.capabilityEpoch)
                    .setBufferedEvents(instance.events.buffered.size.toLong())
                    .addAllTasks(
                        instance.tasks.all.map { task ->
                            InternalTask.newBuilder()
                                .setTaskId(task.taskId)
                                .setSkillType(task.skillType)
                                // 엔진의 이름을 그대로 쓴다 — 계약 enum을 쓰면
                                // 이 덤프가 계약 표면의 투영이 된다.
                                .setTaskState(task.machine.state.name)
                                .setRevision(task.machine.revision)
                                .setAttempt(task.machine.attempt)
                                .setProgress(task.machine.progress())
                                .setSkillState(task.machine.skillMachine?.state?.name ?: "")
                                .setLogSize(task.log.size.toLong())
                                .setTerminalViolationSeen(task.terminalViolationSeen)
                                .build()
                        },
                    )
                    // **레지스트리를 직접 읽는다.** GetSnapshot 이 쓰는
                    // 코드를 거치면 8b·8c·8d가 투영을 투영과 비교하게 된다.
                    .addAllFaults(instance.faults.active().map(::flatten))
                    .build(),
            )
            observer.onCompleted()
        }

        /**
         * 계약의 `Fault`를 스칼라로 편다.
         *
         * 결함은 태스크 상태와 달리 엔진 쪽 표현이 따로 없다 — 레지스트리가
         * 드는 것이 계약의 타입 그 자체다. 그래도 펴서 싣는 이유는 같다:
         * `Fault`를 `Fault`와 비교하면 투영이 필드를 빠뜨려도 양쪽이 똑같이
         * 빠뜨린다. 여기서 하나씩 펴 두면 그 자리가 벌어진다.
         */
        private fun flatten(fault: Fault): InternalFault = InternalFault.newBuilder()
            .setErrorType(fault.errorType)
            .setCanContinueCurrentTask(fault.canContinueCurrentTask)
            .setCanAcceptNewTask(fault.canAcceptNewTask)
            .setLifetimeKind(fault.activeUntil.kind.name)
            .setLifetimeUntil(fault.activeUntil.until)
            .setSkillId(reference(fault, Reference.Key.KEY_SKILL_ID))
            .setTaskId(reference(fault, Reference.Key.KEY_TASK_ID))
            .setErrorHint(fault.errorHint)
            .build()

        private fun reference(fault: Fault, key: Reference.Key): String =
            fault.referencesList.firstOrNull { it.key == key }?.value.orEmpty()
    }
}
