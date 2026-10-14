package dev.picasso.adapter.host

import dev.picasso.uplink.report.HandshakeReporter
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
 * 어댑터 호스트 — [HostedRobot] 하나를 계약의 gRPC 서비스 셋(`SkillService`·`TaskService`·`EventService`) 뒤에 세운다.
 *
 * 미믹의 `MimicServer` 와 같은 자리다. 소비자는 엔드포인트만 바꿔 미믹과 실물 어댑터를 오간다 — 그것이 이 저장소의
 * 주장이고 이 클래스가 그 주장의 실물 쪽 절반이다.
 *
 * 발행(상태·이벤트·연결)은 [HostedRobot] 이 자기 `Publisher` 로 내고, 레지스트리 적재는 그 발행자를 `uplink` 의
 * `IngestBridge` 로 감싼 쪽(조립하는 쪽)이 한다 — 미믹의 CLI 가 `RegistryLink.wrap` 으로 하는 것과 같다.
 *
 * **여기 없는 것** — 레지스트리 폴링(§10.3), 운영 배치에서 [pump] 를 부르는 스케줄러(모든 RPC 가 펌프를 지나므로
 * 소비자가 폴링하면 그것이 곧 구동이다 — 스케줄러는 런처와 함께 온다).
 *
 * ## 잠금 하나
 *
 * [HostedRobot] 의 기록(태스크 장부·도는 태스크·재생 버퍼·결함 표)과 `WatchTask` 의 열린 스트림 표에는 잠금이 없다.
 * 시험은 in-process 채널의 `directExecutor` 로 한 스레드에서 돌아 문제가 안 보였다. 실 포트에서는 RPC 가 gRPC
 * 스레드마다 오고 스케줄러는 또 다른 스레드에서 [pump] 를 부르므로, 둘이 같은 기록을 동시에 만진다 — 동시에 온
 * `StartTask` 둘이 다 «도는 태스크 없음» 을 보고 지나가 로봇에 명령이 두 번 나갔고, 재생 버퍼를 읽는 RPC 가
 * `ConcurrentModificationException` 으로 `UNKNOWN` 을 냈다(`AdapterHostConcurrencyTest`). 그래서 모든 RPC 와 [pump]·기동·종료
 * 발행을 이 잠금 하나로 줄 세운다. 미믹의 `MimicServer` 가 같은 이유로 같은 모양을 먼저 가졌다.
 *
 * **호스트 단위다, 기체 단위가 아니다.** 플릿 뒤 기체들은 남쪽 링크 하나를 같이 쓰므로(§15.106) 기체마다 잠그면 그
 * 링크가 다시 동시에 불린다. 대가는 한 기체의 느린 벤더 호출이 같은 프로세스의 다른 기체를 기다리게 하는 것이다.
 * ADR 51 결정 5 의 발신자 세대 대조가 이 층에 들어오면, 대조와 벤더 송신이 한 덩어리로 도는 바닥도 이 잠금이다.
 */
class AdapterHost(
    /**
     * 이 프로세스가 드는 기체들. **플릿 뒤에는 여럿이다**(§15.106) — 계약이 요청 헤더의 `robot_id` 로 지목하며,
     * 미믹이 한 프로세스에 여럿을 호스팅하는 것과 같은 편의다(ADR 21: PoC 의 편의이지 아키텍처 주장이 아니다).
     */
    val robots: Map<String, HostedRobot>,
    builder: ServerBuilder<*>,
    /** §5.4 의 핸드셰이크 결과 보고. 붙이지 않으면 아무 데도 안 나간다 — 미믹의 `MimicServer` 와 같은 자리다. */
    reporter: HandshakeReporter = HandshakeReporter.NONE,
) {

    /** 기체 하나짜리 배치 — 직결 어댑터와 시험이 쓴다. */
    constructor(robot: HostedRobot, builder: ServerBuilder<*>, reporter: HandshakeReporter = HandshakeReporter.NONE) :
        this(mapOf(robot.robotId to robot), builder, reporter)

    /** 기체 하나를 든 호스트의 그 기체. 여럿이면 터진다 — 부르는 쪽이 자기가 무엇을 세웠는지 알아야 한다. */
    val robot: HostedRobot get() = robots.values.single()

    /** 이 호스트의 기록 전체를 지키는 잠금. 재진입된다 — in-process 시험이 펌프를 돌리다 같은 스레드에서 RPC 를 받는다. */
    private val lock = Any()

    /** 이 잠금 아래에서 [block] 을 돈다. 같은 프로세스에서 [robots] 의 기록을 읽거나 움직이는 쪽이 쓴다. */
    fun <T> exclusive(block: () -> T): T = synchronized(lock, block)

    /** [service] 의 모든 호출을 [lock] 아래로 줄 세운다. */
    private fun serialized(service: BindableService): ServerServiceDefinition =
        ServerInterceptors.intercept(service, Serialized(lock))

    private val server: Server = builder
        .addService(serialized(HostSkillService(robots, reporter)))
        .addService(serialized(HostTaskService(robots)))
        .addService(serialized(HostEventService(robots)))
        .build()

    val port: Int get() = server.port

    /** 포트를 열고 **그 다음에** `ONLINE` 을 발행한다(§10.2 의 순서 — 붙을 수 없는 기체를 살아 있다고 알리지 않는다). */
    fun start(): AdapterHost = apply {
        server.start()
        // 포트가 열린 뒤라 RPC 가 들어올 수 있다. 온라인 발행도 같은 잠금 아래에 둔다 — 순번 축을 RPC 와 같이 쓴다.
        exclusive { robots.values.forEach { it.announceOnline() } }
    }

    /** 어댑터에 묻고 달라진 것을 적어 열린 스트림에 민다. 시험과 스케줄러가 부른다. */
    fun pump() = exclusive { robots.values.forEach { it.pump() } }

    /** `OFFLINE` 을 남기고 닫는다 — 정상 종료는 Last Will 이 안 나가므로 여기서 말해야 한다(§4.7). */
    fun shutdown() {
        exclusive { robots.values.forEach { it.goOffline() } }
        server.shutdownNow()
    }

    /** 호출의 시작과 모든 콜백(요청·반쯤 닫힘·취소·완료·준비)을 한 잠금 아래에서 돈다. 단항 RPC 의 본문은 반쯤 닫힘에서 돈다. */
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
