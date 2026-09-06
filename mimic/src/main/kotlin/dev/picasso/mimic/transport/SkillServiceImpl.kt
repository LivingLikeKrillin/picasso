package dev.picasso.mimic.transport

import dev.picasso.contracts.v1.GetCapabilitiesRequest
import dev.picasso.contracts.v1.GetCapabilitiesResponse
import dev.picasso.contracts.v1.NegotiateRequest
import dev.picasso.contracts.v1.NegotiateResponse
import dev.picasso.contracts.v1.SkillServiceGrpc
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

        NegotiateResponse.newBuilder()
            .setHeader(hosted.headers.forResponse(NegotiateResponse.getDescriptor()))
            // accepted와 거절 목록이 어긋나면 클라이언트가 통과했다고 믿는다.
            // 둘을 따로 계산하지 않는다.
            .setAccepted(rejections.isEmpty())
            .addAllRejections(rejections)
            .build()
    }
}
