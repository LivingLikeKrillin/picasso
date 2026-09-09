package dev.picasso.middleware

import dev.picasso.client.PicassoClient
import dev.picasso.client.TaskFollower
import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.WatchTaskResponse

/**
 * 하류(로봇) 포트 — 계약(④)의 소비자. 미들웨어는 이것 너머를 모른다.
 *
 * **당기는 모양이다.** 스트림을 기다리며 블로킹하지 않고 지금까지 온 갱신을
 * 읽는다. 시험이 가상 시계를 밀고 [Middleware.pump]를 부르는 결정적 구동과
 * 맞추기 위해서다(§12.1).
 */
interface RobotPort {
    fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse
    fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse>
    fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse
}

/** [PicassoClient] 위의 [RobotPort]. 핸들마다 팔로워 하나를 붙여 두고 그것을 읽는다. */
class ClientRobotPort(private val client: PicassoClient) : RobotPort {

    private val followers = mutableMapOf<String, TaskFollower>()

    override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse =
        client.start(
            robotId, taskId, revision, skillType,
            parameters.map { (k, v) -> ParameterValue.newBuilder().setKey(k).setStringValue(v).build() },
        )

    override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> =
        followers.getOrPut(handle.taskId) { client.follow(robotId, handle, from = 0) }.updates

    override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse = client.cancel(robotId, handle)
}

/**
 * 독립 설비 신호 — E2 의 원천(설계 §1.3, 보고서 12장). 인계 설비·셀 검증 장치.
 *
 * 현장 PLC 는 밀지 않고 폴링으로 읽는 것이 설치 기반의 현실이므로 이것도 **묻는**
 * 모양이다. 시험에서는 PLC/WCS Mimic 이 이것을 구현한다.
 */
interface CellSignals {
    /** 그 자리에 무엇이 있는가. 신호가 없으면 `null` — 빈 자리가 아니라 **말이 없다**. */
    fun observe(location: String): SlotSignal?

    object None : CellSignals {
        override fun observe(location: String): SlotSignal? = null
    }
}

/** 한 자리의 설비 신호 — 재석 여부와 식별된 자재. */
data class SlotSignal(val occupied: Boolean, val material: String?)
