package dev.picasso.adapter.host

import dev.picasso.uplink.report.HandshakeReporter
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
 * **여기 없는 것** — 레지스트리 폴링(§10.3), 운영 배치에서 [pump] 를 부르는 스케줄러(모든 RPC 가 펌프를 지나므로
 * 소비자가 폴링하면 그것이 곧 구동이다 — 스케줄러는 런처와 함께 온다).
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

    private val server: Server = builder
        .addService(HostSkillService(robots, reporter))
        .addService(HostTaskService(robots))
        .addService(HostEventService(robots))
        .build()

    val port: Int get() = server.port

    /** 포트를 열고 **그 다음에** `ONLINE` 을 발행한다(§10.2 의 순서 — 붙을 수 없는 기체를 살아 있다고 알리지 않는다). */
    fun start(): AdapterHost = apply {
        server.start()
        robots.values.forEach { it.announceOnline() }
    }

    /** 어댑터에 묻고 달라진 것을 적어 열린 스트림에 민다. 시험과 스케줄러가 부른다. */
    fun pump() = robots.values.forEach { it.pump() }

    /** `OFFLINE` 을 남기고 닫는다 — 정상 종료는 Last Will 이 안 나가므로 여기서 말해야 한다(§4.7). */
    fun shutdown() {
        robots.values.forEach { it.goOffline() }
        server.shutdownNow()
    }
}
