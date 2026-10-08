package dev.picasso.mimic.transport

import io.grpc.BindableService
import io.grpc.ForwardingServerCallListener
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.ServerServiceDefinition

/**
 * 계약 표면을 세운다.
 *
 * **`ServerBuilder`를 인자로 받는다.** in-process(시험)와 Netty(CLI)가 같은
 * 코드로 서야 한다 — 시험이 세우는 것과 CLI가 세우는 것이 다르면 시험이
 * 표면을 증명하지 못한다.
 */
class MimicServer(
    private val registry: RobotRegistry,
    builder: ServerBuilder<*>,
    /** §5.4의 핸드셰이크 결과 보고. 붙이지 않으면 아무 데도 안 나간다. */
    reporter: dev.picasso.uplink.report.HandshakeReporter =
        dev.picasso.uplink.report.HandshakeReporter.NONE,
) {

    private val taskService = TaskServiceImpl(registry)

    /**
     * 엔진 전체를 지키는 잠금 하나.
     *
     * 엔진(태스크 표·로그·시계·열린 스트림)에는 잠금이 없다. 시험은 in-process 채널의 `directExecutor` 로
     * 한 스레드에서 돌아 문제가 안 보였다. 네트워크 채널에서는 RPC 가 gRPC 스레드에서 오고 시간은 다른
     * 스레드가 흘리므로, 둘이 같은 태스크 표를 동시에 만진다. 그래서 RPC 와 [advance] 를 이 잠금 하나로
     * 줄 세운다. 재진입되므로 같은 스레드에서 시간을 흘리다 RPC 를 받는 in-process 시험은 그대로다.
     */
    private val lock = Any()

    /** 이 잠금 아래에서 [block] 을 돈다. 같은 프로세스에서 엔진 상태를 읽는 쪽(현장 대역 등)이 쓴다. */
    fun <T> exclusive(block: () -> T): T = synchronized(lock, block)

    /** [service] 의 모든 호출을 [lock] 아래로 줄 세운다. 제어 채널도 이것을 지난다. */
    fun serialized(service: BindableService): ServerServiceDefinition =
        ServerInterceptors.intercept(service, Serialized(lock))

    private val server: Server = builder
        .addService(serialized(SkillServiceImpl(registry, reporter)))
        .addService(serialized(taskService))
        .addService(serialized(EventServiceImpl(registry)))
        .build()

    /**
     * 시간을 흘린다 — 시계를 전진시키고, 그것이 만든 전이를 반영하고,
     * 열려 있는 스트림에 민다.
     *
     * **셋이 한 함수인 것이 요점이다.** 시계 참조만 밖으로 내주면 호출자가
     * 전진만 시키고 `tick()`을 부르지 않아 아무 일도 안 일어나거나, 부르더라도
     * 밀어내기를 빠뜨려 열린 스트림이 멈춘다 — 그러면 결함이 시험 실패가
     * 아니라 **정지**로 나타난다.
     *
     * **Chunk 6의 `AdvanceClock`(§10.5)도 여기로 내려와야 한다.** 제어 채널이
     * 자기 전진 경로를 따로 만들면 시험과 운영이 서로 다른 코드로 시간을
     * 흘리게 되고, `harness`를 다시 설계하게 된다.
     */
    fun advance(duration: java.time.Duration) = exclusive {
        registry.clocks.forEach { it.advance(duration) }
        taskService.settleAll()
        // §7.2의 최대 발행 간격. **스케줄러가 아니라 시계가 만든다**(§12.1).
        registry.hosted.forEach { it.instance.events.publishStateIfDue() }
    }

    /** 시계를 건드리지 않고 전이만 반영해 민다. */
    fun settle() = exclusive { taskService.settleAll() }

    /**
     * §10.5의 `Step`. 한 기체를 한 칸 돌린다.
     *
     * **제어 채널도 이 문으로 지난다** — [advance]와 같은 이유다. 따로
     * 만들면 시험과 운영이 서로 다른 코드로 상태를 움직이게 되고, 밀어내기를
     * 빠뜨리면 열린 스트림이 멈춘다.
     */
    fun step(hosted: RobotRegistry.Hosted): Int = exclusive { taskService.step(hosted) }

    /**
     * 시간을 안 흘리고 이미 생긴 전이만 민다. `ForceFault`가 쓴다 —
     * 정착시키면 `CANCELLING` 창이 닫히고, 안 밀면 열린 스트림이 그 전이를
     * 통째로 놓친다.
     */
    fun push(hosted: RobotRegistry.Hosted) = exclusive { taskService.push(hosted) }

    val port: Int get() = server.port

    /**
     * §10.2의 기동 순서 마지막 — **포트를 열고 `ONLINE`을 발행한다.**
     *
     * `RobotInstance` 생성이 아니라 여기서 하는 이유는, 포트가 안 열렸는데
     * 온라인이라고 알리면 소비자가 붙을 수 없는 기체를 살아 있다고 읽기
     * 때문이다.
     */
    fun start(): MimicServer = apply {
        server.start()
        // 포트가 열린 뒤라 RPC 가 들어올 수 있다. 온라인 발행도 같은 잠금 아래에 둔다.
        exclusive { registry.hosted.forEach { it.instance.events.announceOnline() } }
    }

    fun shutdown() {
        server.shutdownNow()
    }

    /** CLI가 프로세스를 살려 두는 방법. 없으면 기동하자마자 종료한다. */
    fun awaitTermination() {
        server.awaitTermination()
    }

    /** 호출의 시작과 모든 콜백(요청·반쯤 닫힘·취소·완료·준비)을 한 잠금 아래에서 돈다. */
    private class Serialized(private val lock: Any) : ServerInterceptor {
        override fun <ReqT : Any, RespT : Any> interceptCall(
            call: ServerCall<ReqT, RespT>,
            headers: Metadata,
            next: ServerCallHandler<ReqT, RespT>,
        ): ServerCall.Listener<ReqT> {
            val delegate = synchronized(lock) { next.startCall(call, headers) }
            return object : ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(delegate) {
                override fun onMessage(message: ReqT) = synchronized(lock) { super.onMessage(message) }
                override fun onHalfClose() = synchronized(lock) { super.onHalfClose() }
                override fun onCancel() = synchronized(lock) { super.onCancel() }
                override fun onComplete() = synchronized(lock) { super.onComplete() }
                override fun onReady() = synchronized(lock) { super.onReady() }
            }
        }
    }
}
