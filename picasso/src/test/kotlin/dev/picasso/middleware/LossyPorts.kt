package dev.picasso.middleware

import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.WatchTaskResponse

/**
 * **응답 유실**의 더블 — 요청은 하류에 닿고 답만 오지 않는다(보고서 17장 3번 *"요청 직후 연결 단절"*).
 *
 * 미믹의 gRPC 에는 장애 주입이 없다(`InjectTransportFault` 는 발행 축이다). 그리고 잃어버리는 것은 미들웨어와 하류
 * 사이의 선이므로 그 선의 더블이 여기 있는 것이 맞다 — 하류는 정상적으로 접수했다. 그래서 위임한 **뒤에** 던진다.
 */
class LossyRobotPort(private val delegate: RobotPort, private var dropStartResponses: Int) : RobotPort {
    var starts = 0
        private set

    override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse {
        starts += 1
        val response = delegate.start(robotId, taskId, revision, skillType, parameters)
        if (dropStartResponses > 0) {
            dropStartResponses -= 1
            throw IllegalStateException("response lost after the request reached the robot")
        }
        return response
    }

    override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> = delegate.watch(robotId, handle)
    override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse = delegate.cancel(robotId, handle)
}

/**
 * 플릿 쪽의 같은 더블 — 그리고 **조회 능력을 선언**한다. 조사한 실물 하류 셋이 전부 클라이언트 참조 키를 안 받으므로
 * (보고서 13.2), [ExecutionLookup.NONE] 인 플릿이 현실에 가깝다. 그때 `IN_DOUBT` 는 자동으로 안 풀리고 운영자에게 간다.
 */
class LossyFleet(
    private val delegate: AmrFleetPort,
    private var dropDispatchResponses: Int,
    override val executionLookup: ExecutionLookup = ExecutionLookup.CLIENT_REFERENCE,
) : AmrFleetPort {
    override fun dispatch(order: TransportOrder): TransportHandle? {
        val handle = delegate.dispatch(order)
        if (dropDispatchResponses > 0) {
            dropDispatchResponses -= 1
            throw IllegalStateException("response lost after the fleet accepted the order")
        }
        return handle
    }

    override fun status(handle: TransportHandle): TransportStatus = delegate.status(handle)
    override fun cancel(handle: TransportHandle): Boolean = delegate.cancel(handle)
}
