package dev.picasso.mimic.transport

import dev.picasso.contracts.v1.GetCapabilitiesRequest
import dev.picasso.contracts.v1.GetCapabilitiesResponse
import dev.picasso.contracts.v1.GetKnownSiteNamesRequest
import dev.picasso.contracts.v1.GetKnownSiteNamesResponse
import dev.picasso.contracts.v1.NegotiateRequest
import dev.picasso.contracts.v1.NegotiateResponse
import dev.picasso.contracts.v1.SkillServiceGrpc
import dev.picasso.uplink.report.HandshakeReport
import dev.picasso.uplink.report.HandshakeReporter
import io.grpc.Status
import io.grpc.stub.StreamObserver

/**
 * §4.4의 `GetCapabilities`와 §5.4의 `Negotiate`.
 *
 * **투영을 여기서 다시 계산하지 않는다.** [dev.picasso.mimic.RobotInstance]가
 * 기동 때 만든 것을 그대로 돌려준다 — 완료 기준 10이 "능력을 하드코딩하면
 * 여기서 걸린다"고 한 자리이고, 서비스가 자기 나름대로 조립하기 시작하면
 * 프로파일과 응답 사이에 두 번째 경로가 생긴다.
 */
class SkillServiceImpl(
    private val registry: RobotRegistry,
    /**
     * §5.4 — *"결과는 성공·실패 모두 `registry`에 보고된다."*
     *
     * 기본값이 [HandshakeReporter.NONE]인 것은 §3.2가 이 방향을 **런타임
     * 접근**으로 두었기 때문이다 — 상대가 없어도 모듈이 동작해야 한다.
     */
    private val reporter: HandshakeReporter = HandshakeReporter.NONE,
) : SkillServiceGrpc.SkillServiceImplBase() {

    override fun getCapabilities(
        request: GetCapabilitiesRequest,
        observer: StreamObserver<GetCapabilitiesResponse>,
    ) = reply(observer) {
        val hosted = registry.require(request.header)

        // 헤더가 권위이고 페이로드의 robot_id는 복사본이다(§5.5).
        //
        // 어긋나면 IDENTITY_MISMATCH이지만 **이 응답에는 Rejection 자리가
        // 없다** — GetCapabilitiesResponse가 header와 capability뿐이다.
        // 계약이 만든 비대칭이라 이 RPC만 gRPC 상태로 나간다(§15).
        val payloadRobotId = request.robotId
        if (payloadRobotId.isNotBlank() && payloadRobotId != request.header.robotId) {
            throw Status.INVALID_ARGUMENT
                .withDescription(
                    "헤더와 페이로드의 robot_id가 다르다: " +
                        "헤더='${request.header.robotId}', 페이로드='$payloadRobotId'",
                )
                .asRuntimeException()
        }

        GetCapabilitiesResponse.newBuilder()
            .setHeader(hosted.headers.forResponse(GetCapabilitiesResponse.getDescriptor()))
            .setCapability(hosted.instance.capability)
            .also { builder ->
                // **Capability 밖이다.** 프로파일에서 파생되지 않고 기체가
                // 읽어 오는 사실이므로, 안에 넣으면 완료 기준 10의 투영 일치
                // 시험이 성립하지 않는다. 못 읽는 기종이면 아예 안 싣는다.
                hosted.instance.robotSoftware?.let(builder::setRobotSoftware)
            }
            .build()
    }

    /**
     * 이 기체가 아는 **사이트 이름들**(ADR 35).
     *
     * **등록했다는 사람의 말을 기체의 보고로 바꾸는 자리다.** 여기까지 오기
     * 전에는 운영자가 레지스트리에 "했다"고 적는 것뿐이었고, 오타 하나나
     * 빠뜨린 이름 하나를 아무도 못 잡았다.
     *
     * 셋을 구별해서 답한다.
     *
     * | 상태 | 어떻게 답하나 |
     * |---|---|
     * | 이름을 호스팅 못 하는 기종 | `unsupported = true` |
     * | 호스팅하는데 하나도 등록 안 됨 | 빈 목록, `unsupported = false` |
     * | 아는 이름이 있음 | 목록과 [GetKnownSiteNamesResponse.getTotalCount] |
     *
     * **가운데를 첫째와 접으면 안 된다** — 등록할 자리가 없는 기체에게 등록을
     * 요구하게 된다.
     *
     * 목록은 `protocol_limits.max_array_length`에 걸려 **잘린다.** 잘린 것을
     * 알리는 것이 `total_count`이며, 세지 않으면 지도가 큰 사이트에서 목록이
     * 조용히 잘리고 소비자가 그것을 전부로 읽는다.
     */
    override fun getKnownSiteNames(
        request: GetKnownSiteNamesRequest,
        observer: StreamObserver<GetKnownSiteNamesResponse>,
    ) = reply(observer) {
        val hosted = registry.require(request.header)

        val payloadRobotId = request.robotId
        if (payloadRobotId.isNotBlank() && payloadRobotId != request.header.robotId) {
            throw Status.INVALID_ARGUMENT
                .withDescription(
                    "헤더와 페이로드의 robot_id가 다르다: " +
                        "헤더='${request.header.robotId}', 페이로드='$payloadRobotId'",
                )
                .asRuntimeException()
        }

        val known = hosted.instance.knownSiteNames
        val limit = hosted.instance.document.maxArrayLength

        GetKnownSiteNamesResponse.newBuilder()
            .setHeader(hosted.headers.forResponse(GetKnownSiteNamesResponse.getDescriptor()))
            .also { builder ->
                if (known == null) {
                    builder.unsupported = true
                } else {
                    builder.totalCount = known.size
                    builder.addAllNames(known.take(limit))
                }
            }
            .build()
    }

    override fun negotiate(
        request: NegotiateRequest,
        observer: StreamObserver<NegotiateResponse>,
    ) = reply(observer) {
        val hosted = registry.require(request.header)
        val rejections = Negotiator.negotiate(
            hosted.instance.capability,
            request.header,
            request.requirement,
        )

        val response = NegotiateResponse.newBuilder()
            .setHeader(hosted.headers.forResponse(NegotiateResponse.getDescriptor()))
            // accepted와 거절 목록이 어긋나면 클라이언트가 통과했다고 믿는다.
            // 둘을 따로 계산하지 않는다.
            .setAccepted(rejections.isEmpty())
            .addAllRejections(rejections)
            .build()

        // **§5.4 — 보고 실패는 핸드셰이크 결과에 영향을 주지 않는다.**
        //
        // 그래서 삼킨다. 삼켜도 잃지 않는 것은 폴백의 몫이고(FileHandshakeReporter),
        // 삼킨 것이 조용하지 않은 것은 워터마크의 몫이다 — 적재가 멈추면
        // §9.3의 조회가 `NotObservable`이 되어 축소를 막는다.
        runCatching {
            reporter.report(HandshakeReport(hosted.instance.site, request, response))
        }

        response
    }
}
