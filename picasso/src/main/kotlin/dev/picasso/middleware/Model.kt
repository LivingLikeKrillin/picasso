package dev.picasso.middleware

import dev.picasso.contracts.v1.HoldState

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
 * - E1 플릿 확인 — 어댑터가 플릿에 붙은 경우
 * - E2 독립 설비 확인 — 인계 설비·셀 검증 장치의 신호([CellSignals])
 * - E3 업무 확인 — 상류의 ack
 */
enum class Evidence { E0, E1, E2, E3 }

/** 원자 단위(슬롯·용기) 하나의 검증 결과 — 로봇 보고와 독립 설비 신호의 대조(보고서 12.3). */
enum class Verification {
    /** 요구 등급이 E0 이하라 설비 확인을 묻지 않았다. */
    NOT_REQUESTED,
    /** 신호가 있고 기대와 맞는다. */
    MATCHED,
    /** 신호가 없다 — 로봇은 끝났다는데 설비가 말이 없다. `UNVERIFIED`. */
    ABSENT,
    /** 신호가 있으나 기대와 다르다(B형 슬롯에 A형). 오인계 의심 — `FAILED` + 운영자. */
    MISMATCH,
}

/** 원자 단위의 종착. */
enum class UnitState { PENDING, RUNNING, DONE, UNVERIFIED, FAILED, ABORTED }

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
 * 원자 단위 하나 — 슬롯 하나, 용기 하나. 계약의 태스크 하나에 대응한다.
 *
 * @param unitId 상류가 아는 단위 이름(슬롯 id). 중단점과 부분 완료 목록이 이것으로 말한다.
 */
data class ExecutionUnit(
    val unitId: String,
    val skillType: String,
    val parameters: Map<String, String>,
    val expectedMaterial: String?,
    val destination: String?,
    var state: UnitState = UnitState.PENDING,
    var taskId: String = "",
    var revision: Int = 0,
    var reached: Evidence = Evidence.E0,
    var verification: Verification = Verification.NOT_REQUESTED,
    /** 실패의 정준 분류. 어댑터가 벤더 코드에서 옮긴 것이 계약의 `Fault.error_type` 으로 온다. */
    var failureClass: String? = null,
    var hold: HoldState = HoldState.getDefaultInstance(),
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
)

/**
 * `ISA95JobResponseDataType` 의 모양에 정준 결과를 얹은 것 — 상류에 내는 결과 통보.
 *
 * 상류에 드러내는 것(보고서 16장): 도달 등급과 요구 등급 충족 여부, 아직 확인되지
 * 않았다는 사실(`UNVERIFIED`), 일부 단위만 완료됐다는 사실과 그 목록, 운영자 판단
 * 필요. 감추는 것: SDK, 하류 상태 이름, 어댑터가 어느 층에 붙었는지.
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
    /** 미완료 단위와 그 사유 — 정준 분류 또는 검증 결과. 벤더 이름은 없다. */
    val incompleteUnits: Map<String, String>,
    val operatorRequired: Boolean,
    val residualHold: HoldState,
    var ack: UpstreamAck = UpstreamAck.SENT_UNACKED,
)
