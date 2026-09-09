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

// ── 하류(플릿) 포트 — D 수준 위임. 프로젝트용 계약이다.

/**
 * 플릿에 맡기는 운반 하나(보고서 5장 — 시나리오 ①의 하류 구현 방식 하나: *"Fleet 시스템이
 * 용기 운반 전체를 제공하면 하나의 작업으로 위임한다"*).
 *
 * **이것은 프로젝트용 계약이지 특정 벤더 API 의 재현이 아니다**(보고서 4.1). 벤더
 * AMR API·VDA 5050 은 구현하지 않는다(`scenarios.md` §1 규칙 1). 여기 있는 것은
 * 보고서 3.4 의 필수 계약 일곱 중 이 시나리오가 요구하는 것 — 클라이언트 참조로
 * 멱등한 접수, 실행 식별과 조회, 성공의 뜻, 실패, 중단.
 *
 * @param reference 클라이언트 참조 키. 같은 참조로 다시 맡기면 새 운반이 생기지 않는다 —
 *   보고서 13.2 가 하류에 요구하는 그것이며, 접수 직후 단절의 `IN_DOUBT` 가 이것으로 풀린다.
 */
data class TransportOrder(
    val reference: String,
    val containerId: String,
    val source: String,
    val destination: String,
)

/** 플릿이 발급한 운반 식별자. */
@JvmInline
value class TransportHandle(val id: String)

/**
 * 운반의 상태 — 플릿이 자기 완료 조건으로 말한다.
 *
 * **성공의 뜻(보고서 10.2·5장)**: [DELIVERED] 는 *도착 ∧ 하역 완료 ∧ 목적지 설비가 인수 ∧
 * AMR 이 용기를 계속 보유하고 있지 않음* 이다. 이것이 플릿 계약이 보장하는 사후조건이고,
 * 그래서 E1 이다. 용기가 **그** 용기인지는 설비(E2)가 말한다.
 */
enum class TransportState {
    ACCEPTED,
    /** 출발지에서 용기를 인수했다 — 관측한 용기 태그가 [TransportStatus.observedContainer] 에. */
    PICKED_UP,
    IN_TRANSIT,
    /** 목적지가 비어 있지 않아 인계를 기다린다. 임의의 다른 자리에 내려놓지 않는다. */
    WAITING_HANDOVER,
    DELIVERED,
    /** 출발지의 용기가 요청과 달라 **인수하지 않았다**. */
    REJECTED_AT_SOURCE,
    CANCELLED,
    FAILED,
}

data class TransportStatus(
    val state: TransportState,
    /** 플릿이 읽은 용기 태그. 출발지 불일치의 근거이며, 없으면 `null`. */
    val observedContainer: String?,
    /** AMR 이 지금 용기를 싣고 있는가 — 잔여 물리 상태. */
    val holding: Boolean,
    val detail: String? = null,
)

interface AmrFleetPort {
    /** 맡긴다. 플릿이 거절하면 `null`. 같은 [TransportOrder.reference] 는 같은 핸들이다. */
    fun dispatch(order: TransportOrder): TransportHandle?
    fun status(handle: TransportHandle): TransportStatus
    fun cancel(handle: TransportHandle): Boolean

    object None : AmrFleetPort {
        override fun dispatch(order: TransportOrder): TransportHandle? = null
        override fun status(handle: TransportHandle): TransportStatus =
            TransportStatus(TransportState.FAILED, null, false, "플릿이 없다")
        override fun cancel(handle: TransportHandle): Boolean = false
    }
}

// ── 독립 설비 신호 — E2

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

/**
 * 한 자리의 설비 신호 — 재석 여부와 설비가 읽은 식별자(부품 타입 라벨이든 용기 태그든,
 * 설비가 읽을 수 있는 것 — §15.80 의 신원 수단).
 */
data class SlotSignal(val occupied: Boolean, val identity: String?)
