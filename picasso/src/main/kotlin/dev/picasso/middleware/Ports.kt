package dev.picasso.middleware

import dev.picasso.client.PicassoClient
import dev.picasso.client.TaskFollower
import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.ValueType
import dev.picasso.contracts.v1.WatchTaskResponse
import java.time.Instant

/**
 * 하류(로봇) 포트 — 계약(④)의 소비자. 미들웨어는 이것 너머를 모른다.
 *
 * **당기는 모양이다.** 스트림을 기다리며 블로킹하지 않고 지금까지 온 갱신을
 * 읽는다. 시험이 가상 시계를 밀고 [Middleware.pump]를 부르는 결정적 구동과
 * 맞추기 위해서다(§12.1).
 */
interface RobotPort {
    /**
     * 계약(④)은 같은 `(task_id, revision)` 재전송이 같은 핸들이다(§4.4) — 그것이 보고서 13.2 ①의 조회다.
     * 벤더가 참조 키를 안 받아도 어댑터가 매핑을 들어 이 답을 지킨다(어댑터 재시작은 밖, §1.3 B-1).
     */
    val executionLookup: ExecutionLookup get() = ExecutionLookup.CLIENT_REFERENCE

    /**
     * 이 기체가 **무엇을 드는가**(계약 `GetCapabilities`).
     *
     * **널은 못 물어봤다**이지 *아무것도 안 든다* 가 아니다. 선택 파라미터를 붙일지 판정하는 데만 쓰므로,
     * 널이면 **안 붙인다** — 막는 방향이다(§15.41).
     */
    fun capabilities(robotId: String): Capability? = null

    fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse
    fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse>
    fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse

    /**
     * 기체의 현재값(계약 `GetSnapshot`, §4.8) — 활성 결함·연결 상태·태스크 상태·다음 이벤트 번호. **`null` 은 못
     * 물어봤다는 뜻**이지 결함이 없다는 뜻이 아니다 — 둘을 접으면 관측 실패가 정상으로 읽힌다(원장의
     * `Observed`/`NotObservable` 과 같은 이유).
     */
    fun snapshot(robotId: String): RobotSnapshot?

    /** 재생 버퍼(계약 `ReplayEvents`, §4.8) — [from] 부터의 이벤트. 벗어났으면 [Replay.Evicted]. 못 물어봤으면 `null`. */
    fun replay(robotId: String, from: Long): Replay?
}

/** [PicassoClient] 위의 [RobotPort]. 핸들마다 팔로워 하나를 붙여 두고 그것을 읽는다. */
class ClientRobotPort(private val client: PicassoClient) : RobotPort {

    /** `PicassoClient` 가 이미 세대별로 캐시한다 — 여기서 또 들면 두 캐시가 어긋난다. */
    override fun capabilities(robotId: String): Capability? = runCatching { client.capabilities(robotId) }.getOrNull()

    private val followers = mutableMapOf<String, TaskFollower>()

    override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse =
        client.start(
            robotId, taskId, revision, skillType,
            parameters.map { (k, v) -> typed(robotId, skillType, k, v) },
        )

    /**
     * 문자열 하나를 **계약이 선언한 타입**으로 옮긴다.
     *
     * 미들웨어의 파라미터가 전부 문자열인 것은 상류 때문이다 — ISA-95 의 `Value` 가 문자열이고, 층 ③ 은 그것을
     * 그대로 나른다. 계약은 타입이 있다(`ValueType`). **그 경계가 여기다.**
     *
     * 타입을 모르면(능력을 못 물어봤거나 선언에 없는 키) 문자열로 보낸다 — 선언에 없는 코어 키는 어차피
     * 로봇이 거절하고(§5.3 fail-closed), 그 거절이 조용한 변환보다 낫다.
     *
     * ★이 자리가 없던 동안 미들웨어가 보내던 것은 전부 문자열 파라미터였다. `verify_grasp`(BOOL)가 처음으로
     * 타입이 다른 것이었고, 문자열로 보내니 **태스크가 통째로 `PARAMETER_INVALID` 로 죽었다**(§15.116).
     */
    private fun typed(robotId: String, skillType: String, key: String, value: String): ParameterValue {
        val builder = ParameterValue.newBuilder().setKey(key)
        val declared = capabilities(robotId)
            ?.skillsList?.firstOrNull { it.skillType == skillType }
            ?.parametersList?.firstOrNull { it.key == key }
        return when (declared?.valueType) {
            ValueType.VALUE_TYPE_BOOL -> builder.setBoolValue(value.toBooleanStrict()).build()
            ValueType.VALUE_TYPE_NUMBER -> builder.setNumberValue(value.toDouble()).build()
            ValueType.VALUE_TYPE_INTEGER -> builder.setIntegerValue(value.toLong()).build()
            else -> builder.setStringValue(value).build()
        }
    }

    override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> =
        followers.getOrPut(handle.taskId) { client.follow(robotId, handle, from = 0) }.updates

    override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse = client.cancel(robotId, handle)

    override fun snapshot(robotId: String): RobotSnapshot? = try {
        val s = client.snapshot(robotId)
        RobotSnapshot(
            sequence = s.sequence,
            faults = s.faultsList,
            connection = s.connectionState,
            tasks = s.tasksList.associate { it.taskId to it.state },
        )
    } catch (_: RuntimeException) {
        null
    }

    override fun replay(robotId: String, from: Long): Replay? = try {
        val responses = client.replay(robotId, from)
        if (responses.any { it.hasRejection() && it.rejection.code == RejectionCode.REJECTION_CODE_SEQUENCE_EVICTED }) {
            Replay.Evicted
        } else {
            Replay.Events(responses.filter { it.hasEvent() }.map { it.event })
        }
    } catch (_: RuntimeException) {
        null
    }
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
    /**
     * 이 플릿이 [TransportOrder.reference] 로 기존 운반을 찾아 주는가. 프로젝트용 계약의 기본은 그렇다 —
     * 실물 플릿이 안 그러면 어댑터가 [ExecutionLookup.NONE] 을 선언하고, 그때 `IN_DOUBT` 는 운영자에게 간다.
     */
    val executionLookup: ExecutionLookup get() = ExecutionLookup.CLIENT_REFERENCE

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

    /**
     * 이 자재를 **든 자리들**(§15.153). 출발 자리가 비었을 때 다른 자리를 제시하는 데 쓴다.
     *
     * `null` 은 **설비가 그 질문에 답하지 않는다**는 뜻이고 빈 목록과 다르다. 빈 목록은 «그 자재를 든
     * 자리가 하나도 없다» 는 답이다. 둘을 접으면 못 물어본 것이 «없다» 로 읽혀 운영자가 재고를 의심한다.
     *
     * 기본값이 `null` 인 것은 **이 질문에 답할 수 있는 설비가 흔하지 않기** 때문이다 — 슬롯마다 신호만
     * 내는 접점은 자기 자리밖에 모른다. 답할 수 있는 쪽(WMS 를 낀 셀 제어기)이 구현한다.
     */
    fun holding(material: String): List<String>? = null

    object None : CellSignals {
        override fun observe(location: String): SlotSignal? = null
    }
}

/**
 * 한 자리의 설비 신호 — 재석 여부와 설비가 읽은 식별자(부품 타입 라벨이든 용기 태그든,
 * 설비가 읽을 수 있는 것 — §15.80 의 신원 수단), 그리고 그 신호의 시각 `t_p`.
 *
 * [observedAt] 이 `null` 이면 설비가 시각을 안 주는 것이다 — 폴링으로 현재값만 읽는 PLC 가
 * 그렇다. 그때는 **읽은 순간**이 `t_p` 다(보고서 12.2 — 짧은 신호는 PLC 쪽 래치 비트나
 * 카운터가 있어야 폴링이 놓치지 않는다).
 */
data class SlotSignal(val occupied: Boolean, val identity: String?, val observedAt: Instant? = null)
