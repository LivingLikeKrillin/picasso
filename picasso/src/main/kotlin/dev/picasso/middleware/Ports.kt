package dev.picasso.middleware

import dev.picasso.client.PicassoClient
import dev.picasso.client.TaskFollower
import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.ValueType
import dev.picasso.contracts.v1.WatchTaskResponse
import java.time.Duration
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

/**
 * [PicassoClient] 위의 [RobotPort]. 태스크마다 받은 갱신을 누적해 들고, 스트림이 닫히면 다시 붙는다([watch]).
 *
 * 태스크별 표는 잠금 없는 맵이다. 호출자는 미들웨어 하나이고 그 미들웨어를 지키는 호스트의 잠금 아래에서
 * 부른다고 전제한다(미들웨어 자체에 스레드가 없다).
 */
class ClientRobotPort(private val client: PicassoClient) : RobotPort {

    /** `PicassoClient` 가 이미 세대별로 캐시한다 — 여기서 또 들면 두 캐시가 어긋난다. */
    override fun capabilities(robotId: String): Capability? = runCatching { client.capabilities(robotId) }.getOrNull()

    /** 태스크마다 지금까지 받은 갱신과 지금 열린 팔로워. 팔로워는 끊기면 바뀌고 누적 목록은 남는다. */
    private class Followed(var follower: TaskFollower) {
        val updates = mutableListOf<WatchTaskResponse>()

        /** [follower] 에서 이미 [updates] 로 옮긴 수. */
        var taken = 0
    }

    private val followed = mutableMapOf<String, Followed>()

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

    /**
     * 지금까지 받은 갱신 **전체**를 돌려준다(미들웨어가 마지막 원소를 상태로 읽는다).
     *
     * 스트림은 기한(`PicassoClient` 의 `deadlineSeconds`)이 지나거나 연결이 끊기면 닫힌다. 그때 마지막 갱신이
     * 종료가 아니면 **그 다음 갱신 번호부터 다시 붙는다** — 계약의 `from_update_index` 다. 다시 붙지 않으면
     * 기한보다 긴 스킬의 종료를 영영 못 본다. 이어 붙인 목록은 갱신 번호가 늘기만 한다.
     */
    override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> {
        val f = followed.getOrPut(handle.taskId) { Followed(client.follow(robotId, handle, from = 0)) }
        take(f)
        if (f.follower.ended && f.updates.lastOrNull()?.state !in TERMINAL) {
            val next = f.updates.lastOrNull()?.let { it.header.updateIndex + 1 } ?: 0L
            f.follower = client.follow(robotId, handle, from = next)
            f.taken = 0
            take(f)
        }
        return f.updates.toList()
    }

    /** 팔로워가 새로 받은 것을 누적 목록으로 옮긴다. */
    private fun take(f: Followed) {
        val fresh = f.follower.updates
        f.updates += fresh.drop(f.taken)
        f.taken = fresh.size
    }

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

    private companion object {
        /** 이 상태 뒤에는 갱신이 없다 — 다시 붙을 까닭이 없다. */
        val TERMINAL = setOf(
            TaskState.TASK_STATE_SUCCEEDED,
            TaskState.TASK_STATE_FAILED,
            TaskState.TASK_STATE_CANCELLED,
            TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED,
        )
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

    /**
     * 이름 있는 설비 신호의 **지금 값**(설비 대기, [Route.SIGNAL]). 이름은 현장 신호 사양의 이름이다.
     *
     * `null` 은 **읽지 못했다**는 뜻이고 «기대 값이 아니다» 와 다르다. 설비 대기는 `null` 을 받으면 계속 기다리고,
     * 기한이 지나면 정해 둔 상태로 간다 — 못 읽은 것을 아닌 것으로 접지 않는다.
     *
     * 기본값이 `null` 인 것은 **자리별 점유만 내는 설비가 이름 있는 신호를 모르기** 때문이다. 어느 주소가 어느 신호인지의
     * 매핑은 이 층이 아니라 드라이버의 일이다.
     */
    fun signal(name: String): NamedSignal? = null

    object None : CellSignals {
        override fun observe(location: String): SlotSignal? = null
    }
}

/**
 * 이름 있는 설비 신호 하나의 값과 그 관측 시각.
 *
 * 값은 문자열이다 — 신호 사양의 종류가 `BOOLEAN` 이면 `true`·`false` 다. [observedAt] 이 `null` 이면 설비가
 * 시각을 안 주는 것이고 **읽은 순간**이 그 시각이다([SlotSignal] 과 같은 규칙). 시각은 자취에 남기는 데만 쓴다.
 */
data class NamedSignal(val value: String, val observedAt: Instant? = null)

// ── 임무 정의 — 작업 지시를 실행 단위로 펼치는 케이퍼빌리티의 출처

/**
 * WorkMaster 마다 **지금 활성인** 케이퍼빌리티와 그 임무 버전을 주는 포트.
 *
 * 미들웨어는 새 작업 지시를 계획할 때만 이것을 읽는다. 실행은 생성 때 읽은 쌍을 쥐고 끝까지 그것으로 돈다 —
 * 리비전도 그 쌍으로 계획한다. 그래서 활성 버전이 바뀌어도 **도는 실행은 옛 버전으로 끝난다.**
 *
 * 활성화(새 버전을 세우는 일)는 이 포트에 없다. 그것은 구현의 일이고, 검증을 통과해야 선다
 * (`dev.picasso.middleware.mission.InMemoryMissionCatalog`). 이 층은 읽기만 한다.
 */
interface MissionCatalog {

    /** 이 WorkMaster 의 지금 활성인 정의. 없으면 `null` — 그 작업 지시는 «모르는 논리적 능력» 으로 거부된다. */
    fun active(workMasterId: String): ActiveMission?

    companion object {
        /** 코드로 정의한 케이퍼빌리티 셋. 미들웨어의 기본 목록이다. 부를 때마다 새 인스턴스를 만든다. */
        fun codeCapabilities(): List<LogicalCapability> = listOf(PrepareSequencedRack(), DeliverContainer(), InspectAsset())

        /** 코드 케이퍼빌리티만으로 된 카탈로그 — 임무 버전이 없다. 같은 WorkMaster 가 둘이면 뒤엣것이 남는다. */
        fun of(capabilities: List<LogicalCapability>): MissionCatalog {
            val byWorkMaster = capabilities.associateBy { it.workMasterId }
            return object : MissionCatalog {
                override fun active(workMasterId: String): ActiveMission? =
                    byWorkMaster[workMasterId]?.let { ActiveMission(it, missionVersion = null) }
            }
        }
    }
}

/**
 * 활성인 정의 하나 — 케이퍼빌리티와 그것이 어느 임무 버전인지.
 *
 * @param missionVersion 데이터 정의면 그 WorkMaster 안에서 1부터 오르는 번호, 코드 케이퍼빌리티면 `null`.
 *   작업 지시의 버전(`JobOrder.version`, 단위의 `revision`)과 다른 축이다.
 */
data class ActiveMission(val capability: LogicalCapability, val missionVersion: Int?)

/**
 * 한 자리의 설비 신호 — 재석 여부와 설비가 읽은 식별자(부품 타입 라벨이든 용기 태그든,
 * 설비가 읽을 수 있는 것 — §15.80 의 신원 수단), 그리고 그 신호의 시각 `t_p`.
 *
 * [observedAt] 이 `null` 이면 설비가 시각을 안 주는 것이다 — 폴링으로 현재값만 읽는 PLC 가
 * 그렇다. 그때는 **읽은 순간**이 `t_p` 다(보고서 12.2 — 짧은 신호는 PLC 쪽 래치 비트나
 * 카운터가 있어야 폴링이 놓치지 않는다).
 */
data class SlotSignal(val occupied: Boolean, val identity: String?, val observedAt: Instant? = null)

// ── 현장 시간값 — 케이퍼빌리티 기본값을 덮는 현장 설정 한 세트

/**
 * 현장 시간값의 출처. 미들웨어는 [Middleware.pump] 가 시작할 때 **한 번** 읽고 그 라운드의 모든 판정에 같은 값을 쓴다.
 *
 * `null` 은 현장 값이 없다는 뜻이고, 그때는 케이퍼빌리티의 기본값([LogicalCapability.evidenceWindow]·
 * [LogicalCapability.inDoubtGrace]·[LogicalCapability.stallWindow])으로 판정한다. 값을 쓰기 전에 검사하는 것은 구현의 일이다.
 * 범위 밖 값을 받아 둔 구현은 그것을 주지 말고 마지막으로 통과한 값을 준다([SiteTimings.problems]).
 *
 * [current] 는 pump 안에서 불리므로 미들웨어를 지키는 소비자(호스트)의 잠금 아래에서 돈다고 전제한다(미들웨어 자체에
 * 스레드가 없다). 그래서 막히지 않아야 하고, 이미 검사한 스냅숏을 돌려준다. 여기서 DB 를 읽지 않는다. 읽기 주기가 다른
 * 스레드에서 값을 바꾸면 안전하게 게시한다(`@Volatile` 필드나 `AtomicReference`).
 */
fun interface SiteTimingsSource {

    /** 지금 적용할 값. 없으면 `null`. */
    fun current(): SiteTimings?

    companion object {
        /** 현장 값이 없다. 미들웨어의 기본값이며, 모든 판정이 케이퍼빌리티 값으로 돈다. */
        val NONE: SiteTimingsSource = SiteTimingsSource { null }
    }
}

/**
 * 현장 설정 한 버전의 시간값 넷. 현장 전체에 한 값이며, 값이 있으면 모든 케이퍼빌리티의 같은 값을 덮는다.
 *
 * 값은 **초 단위 정수**다. 허용 범위는 이 라이브러리가 쥐고([EVIDENCE_WINDOW_BEFORE_SECONDS] 들), 쓰는 쪽은 적용 전에
 * [problems] 로 검사한다. 생성자는 검사하지 않는다. 범위 밖 값을 읽어 «왜 적용하지 않았나» 를 보여야 하는 쪽이 있기 때문이다.
 *
 * @param siteSettingsVersion 현장 설정의 버전. 1 부터 오른다. 인시던트의 의도에 실린다.
 * @param evidenceWindowBefore 근거 시간 윈도우의 앞 폭. 늘리면 옛 신호가 완료 근거로 들어온다.
 * @param evidenceWindowAfter 근거 시간 윈도우의 뒤 폭. 줄이면 `UNVERIFIED` 가 는다. 단위가 완료될 때 근거 기한에 저장된다.
 * @param inDoubtGrace `IN_DOUBT` 에서 물리 관측을 기다리는 유예. 줄이면 운영자 대기가 는다.
 * @param stallWindow 진행 정체를 사람에게 보이기까지의 유예. 결과 판정은 바꾸지 않는다.
 */
data class SiteTimings(
    val siteSettingsVersion: Long,
    val evidenceWindowBefore: Duration,
    val evidenceWindowAfter: Duration,
    val inDoubtGrace: Duration,
    val stallWindow: Duration,
) {
    /** 앞·뒤 폭을 케이퍼빌리티와 같은 모양으로. */
    val evidenceWindow: EvidenceWindow get() = EvidenceWindow(before = evidenceWindowBefore, after = evidenceWindowAfter)

    /**
     * 허용 범위를 벗어난 칸마다 문장 하나. 비어 있으면 적용해도 된다.
     *
     * 문장은 칸 이름으로 시작한다(`"stallWindow: ..."`). 범위 밖 값은 적용하지 않고 마지막으로 적용한 버전을 유지한다
     * (운영 관리 화면 설계 제안 §9). 초 단위가 아닌 값(밀리초가 남는 값)도 범위 밖으로 친다.
     */
    fun problems(): List<String> = buildList {
        if (siteSettingsVersion < 1) add("siteSettingsVersion: 1 이상이어야 한다 ($siteSettingsVersion)")
        outside("evidenceWindowBefore", evidenceWindowBefore, EVIDENCE_WINDOW_BEFORE_SECONDS)?.let(::add)
        outside("evidenceWindowAfter", evidenceWindowAfter, EVIDENCE_WINDOW_AFTER_SECONDS)?.let(::add)
        outside("inDoubtGrace", inDoubtGrace, IN_DOUBT_GRACE_SECONDS)?.let(::add)
        outside("stallWindow", stallWindow, STALL_WINDOW_SECONDS)?.let(::add)
    }

    private fun outside(field: String, value: Duration, range: LongRange): String? = when {
        value.nano != 0 -> "$field: 초 단위 정수여야 한다 ($value)"
        value.seconds !in range -> "$field: ${range.first}~${range.last} 초 밖이다 (${value.seconds})"
        else -> null
    }

    companion object {
        /**
         * 앞 폭의 허용 범위(초). 하한은 셀이 슬롯을 보고 기체가 완료를 보고하는 순서가 뒤바뀌는 것을 받기 위함이고,
         * 상한(기본값의 4배)은 옛 신호가 완료 근거로 들어오는 파급을 묶는다. 기본값 30.
         */
        val EVIDENCE_WINDOW_BEFORE_SECONDS: LongRange = 5L..120L

        /** 뒤 폭의 허용 범위(초). 하한은 pump 주기와 셀 폴링 주기를 넘기기 위함이고, 상한은 기본값의 8배다. 기본값 15. */
        val EVIDENCE_WINDOW_AFTER_SECONDS: LongRange = 5L..120L

        /**
         * `IN_DOUBT` 유예의 허용 범위(초). 하한은 같은 참조로 다시 묻는 상한(pump 마다 한 번, 세 번)을 마친 뒤에도 설비를
         * 여러 번 읽을 폭이고, 상한은 기본값의 10배다. 기본값 60.
         */
        val IN_DOUBT_GRACE_SECONDS: LongRange = 10L..600L

        /** 진행 정체 유예의 허용 범위(초). 하한은 기종 프로파일의 가장 긴 발행 간격(30초)이고, 상한은 기본값의 12배다. 기본값 300. */
        val STALL_WINDOW_SECONDS: LongRange = 30L..3600L

        /** 초 단위 정수로 만든다. 저장소가 초 단위 정수로 드는 값을 그대로 옮길 때 쓴다. */
        fun ofSeconds(siteSettingsVersion: Long, evidenceWindowBefore: Long, evidenceWindowAfter: Long, inDoubtGrace: Long, stallWindow: Long) =
            SiteTimings(
                siteSettingsVersion,
                Duration.ofSeconds(evidenceWindowBefore),
                Duration.ofSeconds(evidenceWindowAfter),
                Duration.ofSeconds(inDoubtGrace),
                Duration.ofSeconds(stallWindow),
            )
    }
}
