package dev.picasso.mimic.control

import dev.picasso.mimic.control.v1.AdvanceClockRequest
import dev.picasso.mimic.control.v1.AdvanceClockResponse
import dev.picasso.mimic.control.v1.ClockMode
import dev.picasso.mimic.control.v1.ControlServiceGrpc
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import dev.picasso.mimic.control.v1.DumpInternalStateResponse
import dev.picasso.mimic.control.v1.InternalFault
import dev.picasso.mimic.control.v1.InternalTask
import dev.picasso.mimic.control.v1.SetClockModeRequest
import dev.picasso.mimic.control.v1.SetClockModeResponse
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.Reference
import dev.picasso.mimic.engine.RealClock
import dev.picasso.mimic.engine.VirtualClock
import dev.picasso.mimic.transport.MimicServer
import dev.picasso.mimic.transport.RobotRegistry
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
