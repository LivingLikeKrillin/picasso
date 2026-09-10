package dev.picasso.middleware

import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.TaskState
import java.time.Duration
import java.time.Instant

/**
 * 정준 모델 — 실행 하나의 상태(설계 §1.1, 보고서 10.3).
 *
 * 축이 둘이고 독립이다. [PhysicalState]는 물리 세계에서 무슨 일이 일어났는가,
 * [UpstreamAck]는 그 사실이 상류에 전달·확정됐는가. `PHYSICALLY_DONE × SENT_UNACKED`
 * 는 오류가 아니라 정상적인 중간 상태이며, 장애 뒤 할 일이 *다시 운반*이 아니라
 * *기존 결과 전달*임을 이 분리가 말한다.
 *
 * 계약(④)의 `TaskState`는 그 아래 **원자 태스크 하나**의 상태다. 둘은 다른
 * 층이고 하나가 다른 하나를 대신하지 않는다.
 */
enum class PhysicalState {
    REQUESTED, ACCEPTED, RUNNING, PARTIAL, IN_DOUBT, OPERATOR_HOLD,
    PHYSICALLY_DONE, UNVERIFIED, FAILED, CANCELING, ABORTED;

    val isSettled: Boolean
        get() = this == PHYSICALLY_DONE || this == UNVERIFIED || this == FAILED ||
            this == ABORTED || this == PARTIAL
}

enum class UpstreamAck { NOT_SENT, SENT_UNACKED, ACKED }

/**
 * 완료 근거 등급(설계 §1.3, 보고서 11.3). 순서가 곧 세기다.
 *
 * - E0 로봇 자체 보고 — 계약 종착. 어댑터가 로봇에 직결된 경우
 * - E1 플릿 확인 — 플릿이 자기 완료 조건으로 발행한 완료(어댑터가 플릿에 붙은 경우, 또는 D 수준 위임)
 * - E2 독립 설비 확인 — 인계 설비·셀 검증 장치의 신호([CellSignals])
 * - E3 업무 확인 — 상류의 ack
 */
enum class Evidence { E0, E1, E2, E3 }

/**
 * 시간창 δ(보고서 12.1·12.2) — 하류 보고 시각 `t_r` 을 기준으로 설비 신호 시각 `t_p` 가
 * `[t_r − before, t_r + after]` 안에 있어야 그 신호가 **이 완료의** 근거다.
 *
 * 앞쪽 폭은 *이전 것의 신호*(아직 남아 있는 옛 용기·옛 부품)를 걸러 내고, 뒤쪽 폭은
 * 보고 지연·폴링 지연·네트워크 지연을 합한 것이다. **현장별 설정**이며 능력이 기본값을 든다.
 */
data class EvidenceWindow(val before: Duration, val after: Duration)

/** 원자 단위(슬롯·용기) 하나의 검증 결과 — 하류 보고와 독립 설비 신호의 대조(보고서 12.3). */
enum class Verification {
    /** 요구 등급이 하류 보고 이하라 설비 확인을 묻지 않았다. */
    NOT_REQUESTED,
    /** 신호가 있고 기대와 맞는다. */
    MATCHED,
    /** 시간창 안에 신호가 없다 — 하류는 끝났다는데 설비가 말이 없다. `UNVERIFIED`. */
    ABSENT,
    /** 신호가 있으나 기대와 다르다(B형 슬롯에 A형, 다른 용기). 오인계 의심 — `FAILED` + 운영자. */
    MISMATCH,
}

/**
 * 원자 단위의 상태.
 *
 * - [IN_DOUBT] — 요청은 보냈는데 답을 못 받았다(보고서 13장). 접수됐는지 모른다. 해소 순서(13.2)를 [Middleware]가 집행한다
 * - [VERIFYING] — 하류는 끝났다고 했고, 시간창이 닫힐 때까지 설비 신호를 기다리는 중
 * - [OPERATOR_HOLD] — 하류는 실패라는데 설비에는 있다(보고서 12.3 둘째 행), 또는 `IN_DOUBT` 를 자동으로 못 풀었다(13.2 ③). 운영자가 [OperatorDecision]을 낸다
 */
enum class UnitState { PENDING, IN_DOUBT, RUNNING, VERIFYING, OPERATOR_HOLD, DONE, UNVERIFIED, FAILED, ABORTED }

/**
 * 하류가 **클라이언트 참조로 기존 실행을 찾아 주는가**(보고서 13.2 ①, 16장 *"실행 조회 가능 여부 — 이 하류에서
 * `IN_DOUBT` 가 자동 해소되는가"*).
 *
 * - [CLIENT_REFERENCE] — 같은 참조로 다시 물으면 **같은 실행**이 돌아온다. 계약(④)은 같은 `(task_id, revision)` 재전송이
 *   같은 핸들이고(§4.4), 프로젝트용 플릿 계약은 [TransportOrder.reference] 가 그것이다. `IN_DOUBT` 가 자동으로 풀린다.
 * - [NONE] — 그런 조회가 없다. 조사한 실물 하류 셋이 전부 이쪽이었고(13.2), 어댑터가 매핑을 들어 계약 쪽으로는
 *   [CLIENT_REFERENCE] 로 보이게 한다. 이 값이면 **자동 재실행 금지**가 기본이다 — 다시 보내면 물리 작업이 둘이 될 수 있다.
 */
enum class ExecutionLookup { CLIENT_REFERENCE, NONE }

/**
 * 운영자의 판단 — 12.3 둘째 행 *"운영자 확인 후 PHYSICALLY_DONE 또는 재작업"*, 그리고 13.2 ③.
 *
 * [REWORK] 는 **명시적 재요청**이다(13.3 — 명령 재시도는 미실행이 확인된 경우에만, 사람이 확인하고 낸다). 새 정체성으로 간다.
 */
enum class OperatorDecision { CONFIRM_DONE, REWORK }

/**
 * 지연 이벤트(보고서 15.1) — 단위의 지금 버전보다 **낮은 버전**을 단 하류 종착. 폐기하지 않고 여기 보존한다(감사·사후 분석).
 * v17 을 돌리다 v18 로 바꿨는데 뒤늦게 온 v17 의 완료로 v18 을 닫지 않는다 — 그 완료가 무엇을 뜻하는지는 설비가 v18 의
 * 기대에 대고 다시 본다.
 */
data class LateEvent(
    val unitId: String,
    val revision: Int,
    val currentRevision: Int,
    val downstreamState: String,
    val occurredAt: Instant,
)

/**
 * 단위가 어느 하류로 가는가(보고서 3.2 축 1·2).
 *
 * - [ROBOT] — 계약(④)의 원자 스킬 하나. 기종은 어댑터 뒤에 있다
 * - [FLEET] — D 수준 위임. 운반 전체를 플릿에 맡기고 결과만 받는다([AmrFleetPort], 프로젝트용 계약)
 */
enum class Route { ROBOT, FLEET }

// ── 상류 인터페이스의 모양 — OPC UA ISA-95 Job Control 10031-4 의 타입을 따른다

/** `ISA95EquipmentDataType` 의 모양. `EquipmentUse` 값은 표준이 열어 두었고 우리가 지은 말이다. */
data class EquipmentRequirement(
    val id: String,
    val equipmentUse: String,
    val properties: Map<String, String> = emptyMap(),
)

/** `ISA95MaterialDataType` 의 모양 — 타입(`MaterialDefinitionID`)으로 온다. 인스턴스 id 는 이력 키다(§15.80). */
data class MaterialRequirement(
    val materialDefinitionId: String,
    val quantity: Int,
)

/**
 * `ISA95JobOrderDataType` 의 모양. 상류(MES·WMS — 예상 소비자)가 낸다.
 *
 * @param version 생산 순서 버전. 곧 원자 태스크의 `revision` 이다(§1.6).
 * @param requiredEvidence 상류가 지정하는 요구 근거 등급(보고서 11.3). 재고 확정은 E2, 진행 모니터링은 E0.
 */
data class JobOrder(
    val jobOrderId: String,
    val workMasterId: String,
    val version: Int,
    val requiredEvidence: Evidence = Evidence.E0,
    val parameters: Map<String, String> = emptyMap(),
    val materialRequirements: List<MaterialRequirement> = emptyList(),
    val equipmentRequirements: List<EquipmentRequirement> = emptyList(),
)

/**
 * 원자 단위 하나 — 슬롯 하나, 용기 하나. 하류의 실행 하나에 대응한다.
 *
 * @param unitId 상류가 아는 단위 이름(슬롯 id·용기 id). 중단점과 부분 완료 목록이 이것으로 말한다.
 * @param expectedIdentity 설비가 그 자리에서 읽어야 할 것 — 부품 타입이거나 용기 태그.
 */
data class ExecutionUnit(
    val unitId: String,
    val route: Route,
    val skillType: String,
    /** 아래 넷은 **버전의 것**이다 — 새 버전이 도는 단위에 붙으면(15.3) 기대도 새 버전의 것으로 바뀐다. 검증은 지금 기대에 대고 한다. */
    var parameters: Map<String, String>,
    var expectedIdentity: String?,
    var source: String?,
    var destination: String?,
    var state: UnitState = UnitState.PENDING,
    var taskId: String = "",
    var revision: Int = 0,
    /** 재작업 횟수. 하류 태스크의 정체성이 갈리는 자리다 — 같은 `task_id` 는 계약이 같은 핸들로 돌려준다. */
    var attempt: Int = 0,
    var reached: Evidence = Evidence.E0,
    var verification: Verification = Verification.NOT_REQUESTED,
    /** 실패의 정준 분류. 어댑터가 벤더 코드에서 옮긴 것이 계약의 `Fault.error_type` 으로 온다. */
    var failureClass: String? = null,
    /** 종착이 아닌 사정 — 인계 대기, 관측한 태그, 신호의 시각 같은 것. 지연 보고와 기록의 내용이다. */
    var note: String? = null,
    var hold: HoldState = HoldState.getDefaultInstance(),
    /** 하류가 끝났다고 한 시각 `t_r`. 시간창의 기준. */
    var downstreamDoneAt: Instant? = null,
    /** `t_r + after`. 이 시각을 지나도 신호가 없으면 `UNVERIFIED`. */
    var evidenceDeadline: Instant? = null,
    /** 근거로 채택한 신호의 시각 `t_p`. */
    var evidenceAt: Instant? = null,
    /** 설비에 몇 번 물었는가 — 12.2 의 재확인 횟수. */
    var rechecks: Int = 0,
    /** 하류에 요청을 보낸 시각. `IN_DOUBT` 관측 창의 기준이다. */
    var requestedAt: Instant? = null,
    /** 마지막으로 **올라간** 진행률과 그 시각. 정체 판정의 기준이다(계약 0.8.0). */
    var progress: Double = 0.0,
    var progressAt: Instant? = null,
    /**
     * 이 기체가 이 단위의 진행률을 **잴 수 있는가.** 널이면 아직 갱신을 못 봤다.
     * 거짓이면 정체 판정을 하지 않는다 — 0 을 멈춤으로 읽지 않는다.
     */
    var progressObservable: Boolean? = null,
    /** 이미 정체로 알렸는가. 한 번만 알린다 — 같은 사실을 되풀이하면 운영자가 곧 무시한다. */
    var progressStalled: Boolean = false,
    /** `IN_DOUBT` 에서 같은 참조로 다시 물은 횟수(13.2 ①). */
    var lookups: Int = 0,
    /**
     * 하류가 종착에 실어 준 결과 참조 — 계약의 `partial_result`. 점검(③)의 *측정값 또는 증거 자료 참조*가 올 자리이며
     * 지금은 아무 발신자도 채우지 않는다(§15.76). 비어 있으면 `null`.
     */
    var result: String? = null,
)

/**
 * 기체의 **현재값** — 계약 `GetSnapshot`(§4.8). 활성 결함·연결 상태·태스크 상태와 *다음에 올* 이벤트 번호.
 * 이벤트는 발생한 사실이고 이것은 지금이다 — 둘을 함께 써야 신규 소비자가 놓친 전이를 세울 수 있다.
 */
data class RobotSnapshot(
    val sequence: Long,
    val faults: List<Fault>,
    val connection: ConnectionState,
    val tasks: Map<String, TaskState>,
)

/** `ReplayEvents(from)` 의 답 — 이벤트들이거나, 버퍼를 벗어났으니 스냅샷부터 다시 세우라는 말. */
sealed interface Replay {
    data class Events(val events: List<Event>) : Replay
    data object Evicted : Replay
}

/**
 * 실행이 본 이벤트 하나 — 감사 자취. 발행 열의 번호와 시각을 그대로 든다(다시 찍지 않는다 — 30초 전 사건이 방금 것으로
 * 보이면 안 된다). [kind] 는 계약 이벤트의 종류(`FAULT_RAISED`·`FAULT_CLEARED`·`TASK_TRANSITION`·`CAPABILITY_CHANGED`)와
 * 이 층이 낸 것(`RESYNC`·`LINK_BROKEN`·`LINK_RESTORED`)이다.
 */
data class ObservedEvent(
    val sequence: Long,
    val occurredAt: String,
    val kind: String,
    val detail: String,
)

/** 취소 응답(보고서 14.1) — 원상복구가 아니라 중단점과 잔여 물리 상태의 보고다. */
data class CancelReport(
    val executionId: String,
    val version: Int,
    val accepted: Boolean,
    val motionStopped: Boolean,
    val completedUnits: List<String>,
    val inProgressUnit: String?,
    val notStartedUnits: List<String>,
    val residualHold: HoldState,
    /** 정리 동작의 결과 — 계약의 `CANCELLED`(done) / `CANCELLED_RECOVERY_FAILED`(failed). */
    val cleanup: String,
    val finalState: PhysicalState,
    /**
     * 하류가 진행 중 단위의 중단을 **거절**한 사유(계약의 거절 코드) — 그 스킬이 취소를 안 든다(`CANCEL_UNSUPPORTED`).
     * 그러면 그 단위는 끝까지 가고 다음 경계에서 멈춘다. 보고서 16장 *"취소 수준 지원 범위와 취소 가능 지점 제약"* 을
     * 드러내는 자리이고, 지원하지 않는 것을 지원하는 것처럼 감추지 않는다(7장). 하류가 받아들였으면 `null`.
     */
    val refusal: String? = null,
    /**
     * 하류가 중단을 거절해([refusal]) 진행 중 단위가 **끝까지 간 뒤** 멈췄을 때, 그 단위. 이때 [inProgressUnit] 은 `null`
     * 이다 — 중단된 것이 없다. 앞 판은 같은 이름을 [inProgressUnit] 과 [completedUnits] 양쪽에 두어 두 사실을 한 자리로
     * 말했다(§15.93 정직 항목).
     */
    val stoppedAfter: String? = null,
)

/**
 * `ISA95JobResponseDataType` 의 모양에 정준 결과를 얹은 것 — 상류에 내는 결과 통보.
 *
 * 상류에 드러내는 것(보고서 16장): 도달 등급과 요구 등급 충족 여부, 아직 확인되지
 * 않았다는 사실(`UNVERIFIED`), 일부 단위만 완료됐다는 사실과 그 목록, 운영자 판단
 * 필요, 인계 대기 같은 지연. 감추는 것: SDK, 하류 상태 이름, 어댑터가 어느 층에 붙었는지.
 */
data class JobResponse(
    val jobResponseId: String,
    val jobOrderId: String,
    val version: Int,
    val physicalState: PhysicalState,
    val requiredEvidence: Evidence,
    val reachedEvidence: Evidence,
    val completedUnits: List<String>,
    val unverifiedUnits: List<String>,
    /** 미완료 단위와 그 사유 — 정준 분류·검증 결과·지연 사정. 벤더 이름은 없다. */
    val incompleteUnits: Map<String, String>,
    val operatorRequired: Boolean,
    val residualHold: HoldState,
    /** 결과가 아직 확인되지 않은 단위 — 요청은 갔는데 접수됐는지 모른다(16장 *"드러내야 하는 것"*). */
    val inDoubtUnits: List<String> = emptyList(),
    /** 이 실행의 하류가 `IN_DOUBT` 를 자동으로 푸는가(16장 — 실행 조회 가능 여부). 거짓이면 그 상황은 운영자에게 간다. */
    val autoResolvesInDoubt: Boolean = true,
    /** 완료 단위가 실어 온 결과 참조(있는 것만). 점검의 측정값·증거 자료 참조가 올 자리 — 지금은 비어 있다. */
    val results: Map<String, String> = emptyMap(),
    /**
     * 기체가 *새 태스크를 받을 수 없다*(`can_accept_new_task=false`)고 말하는 활성 결함의 정준 분류 — 그래서 다음 단위를
     * 시작하지 않고 세워 두었다. 16장 *"자동 복구가 불가능해 운영자 판단이 필요하다는 사실"*. 비어 있으면 막힌 것이 없다.
     */
    val blockedBy: List<String> = emptyList(),
    /** 기체와의 연결 상태(계약 `ConnectionState` 의 이름). `CONNECTION_BROKEN`·`OFFLINE` 이면 도는 단위의 결과는 미확정이다. */
    val connection: String = ConnectionState.CONNECTION_STATE_ONLINE.name,
    var ack: UpstreamAck = UpstreamAck.SENT_UNACKED,
)
