package dev.picasso.adapter.host

import io.grpc.Server
import io.grpc.ServerBuilder

/**
 * 어댑터 호스트 — [HostedRobot] 하나를 계약의 gRPC 서비스 셋(`SkillService`·`TaskService`·`EventService`) 뒤에 세운다.
 *
 * 미믹의 `MimicServer` 와 같은 자리다. 소비자는 엔드포인트만 바꿔 미믹과 실물 어댑터를 오간다 — 그것이 이 저장소의
 * 주장이고 이 클래스가 그 주장의 실물 쪽 절반이다.
 *
 * **여기 없는 것** — MQTT 발행(상태·이벤트·연결), 레지스트리 적재·폴링, `Negotiate`. 계약의 gRPC 면만 먼저 세웠다.
 * 운영 배치에서 [pump] 를 부르는 스케줄러도 여기 없다(모든 RPC 가 펌프를 지나므로 소비자가 폴링하면 그것이 곧 구동이다).
 */
class AdapterHost(val robot: HostedRobot, builder: ServerBuilder<*>) {

    private val server: Server = builder
        .addService(HostSkillService(robot))
        .addService(HostTaskService(robot))
        .addService(HostEventService(robot))
        .build()

    val port: Int get() = server.port

    fun start(): AdapterHost = apply { server.start() }

    /** 어댑터에 묻고 달라진 것을 적어 열린 스트림에 민다. 시험과 스케줄러가 부른다. */
    fun pump() = robot.pump()

    fun shutdown() {
        server.shutdownNow()
    }
}
