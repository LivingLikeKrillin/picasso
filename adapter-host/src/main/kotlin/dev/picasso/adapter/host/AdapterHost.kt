package dev.picasso.adapter.host

import io.grpc.Server
import io.grpc.ServerBuilder

/**
 * 어댑터 호스트 — [HostedRobot] 하나를 계약의 gRPC 서비스 셋(`SkillService`·`TaskService`·`EventService`) 뒤에 세운다.
 *
 * 미믹의 `MimicServer` 와 같은 자리다. 소비자는 엔드포인트만 바꿔 미믹과 실물 어댑터를 오간다 — 그것이 이 저장소의
 * 주장이고 이 클래스가 그 주장의 실물 쪽 절반이다.
 *
 * 발행(상태·이벤트·연결)은 [HostedRobot] 이 자기 `Publisher` 로 내고, 레지스트리 적재는 그 발행자를 `uplink` 의
 * `IngestBridge` 로 감싼 쪽(조립하는 쪽)이 한다 — 미믹의 CLI 가 `RegistryLink.wrap` 으로 하는 것과 같다.
 *
 * **여기 없는 것** — 레지스트리 폴링(§10.3), `Negotiate`, 운영 배치에서 [pump] 를 부르는 스케줄러(모든 RPC 가 펌프를
 * 지나므로 소비자가 폴링하면 그것이 곧 구동이다 — 스케줄러는 런처와 함께 온다).
 */
class AdapterHost(val robot: HostedRobot, builder: ServerBuilder<*>) {

    private val server: Server = builder
        .addService(HostSkillService(robot))
        .addService(HostTaskService(robot))
        .addService(HostEventService(robot))
        .build()

    val port: Int get() = server.port

    /** 포트를 열고 **그 다음에** `ONLINE` 을 발행한다(§10.2 의 순서 — 붙을 수 없는 기체를 살아 있다고 알리지 않는다). */
    fun start(): AdapterHost = apply {
        server.start()
        robot.announceOnline()
    }

    /** 어댑터에 묻고 달라진 것을 적어 열린 스트림에 민다. 시험과 스케줄러가 부른다. */
    fun pump() = robot.pump()

    /** `OFFLINE` 을 남기고 닫는다 — 정상 종료는 Last Will 이 안 나가므로 여기서 말해야 한다(§4.7). */
    fun shutdown() {
        robot.goOffline()
        server.shutdownNow()
    }
}
