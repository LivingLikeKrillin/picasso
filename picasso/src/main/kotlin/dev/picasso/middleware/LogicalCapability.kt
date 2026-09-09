package dev.picasso.middleware

import java.time.Duration

/**
 * 논리적 능력 — 상류 업무 단위의 계약을 원자 단위 열로 나눈다(보고서 3.1, 설계 §1.2).
 *
 * 이름은 보고서가 *프로젝트 정의*로 표시한 것을 그대로 쓴다(ADR 38 결정 6).
 * 조합은 미션 계층의 일이고 그것은 이 모듈 안이다.
 */
interface LogicalCapability {
    val workMasterId: String

    /** 이 능력이 제공할 수 있는 최고 근거 등급(보고서 11.3 규칙 1). */
    val maxEvidence: Evidence

    /**
     * 시간창 δ(보고서 12.2 — 능력 단위 설정 항목). 앞쪽은 옛 신호를 거르는 폭, 뒤쪽은
     * 보고 지연·폴링 지연·네트워크 지연의 합이다. **현장별로 설정**하는 값이며 여기는 기본값이다.
     */
    val evidenceWindow: EvidenceWindow get() = EvidenceWindow(before = Duration.ofSeconds(30), after = Duration.ofSeconds(15))

    /** 상류 요청을 원자 단위 열로. 계획이지 실행이 아니다. */
    fun plan(order: JobOrder): List<ExecutionUnit>
}

/** `EquipmentUse` 값 — 표준이 열어 두었고 **우리가 지은 말**이다. 능력 둘이 같은 낱말을 쓴다. */
object EquipmentUse {
    const val DESTINATION = "destination"
    const val SOURCE = "source"
    const val PROP_MATERIAL = "material"
    const val PROP_CONTAINER = "container"
}

/**
 * `PrepareSequencedRack` — 생산 순서에 맞춰 랙의 슬롯에 부품을 배치한다(시나리오 ②).
 *
 * 입력(ISA-95 JobOrder 의 모양, `docs/scenarios.md` §4.1):
 * - `EquipmentRequirements` 중 `EquipmentUse = destination` 인 것이 슬롯이고 `material`
 *   속성이 그 슬롯에 놓일 부품 **타입**이다.
 * - `EquipmentUse = source` 인 것이 제시 자리이고 `material` 속성이 거기 놓인 타입이다.
 *
 * 슬롯 하나 = `pick_place(object_id = 제시 자리, destination = 슬롯)` 하나. `object_id`
 * 가 부품 타입이 아니라 **제시 자리의 이름**인 것은 로봇에 제품 타입 개념이
 * 없기 때문이다(§15.80) — *이 자리에는 A형만 있다* 가 환경 전제 C 다.
 *
 * 최고 근거 등급은 E2 — 셀 검증 장치([CellSignals])가 있으면 슬롯 점유와 품번을
 * 독립적으로 확인한다.
 */
class PrepareSequencedRack : LogicalCapability {

    override val workMasterId: String = WORK_MASTER

    override val maxEvidence: Evidence = Evidence.E2

    override fun plan(order: JobOrder): List<ExecutionUnit> {
        val sources = order.equipmentRequirements
            .filter { it.equipmentUse == EquipmentUse.SOURCE }
            .associateBy { it.properties[EquipmentUse.PROP_MATERIAL] }

        return order.equipmentRequirements
            .filter { it.equipmentUse == EquipmentUse.DESTINATION }
            .map { slot ->
                val material = slot.properties[EquipmentUse.PROP_MATERIAL]
                val source = material?.let { sources[it] }
                ExecutionUnit(
                    unitId = slot.id,
                    route = Route.ROBOT,
                    skillType = SKILL,
                    parameters = mapOf(
                        P_OBJECT to (source?.id ?: ""),
                        P_DESTINATION to slot.id,
                    ),
                    expectedIdentity = material,
                    source = source?.id,
                    destination = slot.id,
                    // 제시 자리가 없으면 집을 것이 없다 — 시작도 못 한다. 부족은 여기서 드러난다.
                    state = if (source == null) UnitState.FAILED else UnitState.PENDING,
                    failureClass = if (source == null) NO_SOURCE else null,
                )
            }
    }

    companion object {
        const val WORK_MASTER = "PrepareSequencedRack"

        /** 계약 카탈로그의 스킬 이름. 미들웨어가 아는 유일한 하류 어휘는 계약이다. */
        const val SKILL = "pick_place"
        const val P_OBJECT = "object_id"
        const val P_DESTINATION = "destination"

        /** 그 타입을 제시하는 자리가 주문에 없다 — 부품 부족의 미들웨어 쪽 이름. */
        const val NO_SOURCE = "NO_SOURCE_FOR_MATERIAL"
    }
}

/**
 * `DeliverContainer` — 할당된 용기를 출발 인계점에서 도착 인계점으로 공급한다(시나리오 ①).
 *
 * 입력(보고서 5장): `EquipmentUse = source` 인 것이 출발 인계점이고 `container` 속성이
 * WMS 가 할당한 용기, `EquipmentUse = destination` 이 도착 인계점. 상류는 어느 AMR 을
 * 쓸지도 경로도 지정하지 않는다.
 *
 * 단위 하나 = 운반 하나. 하류는 **플릿에 D 수준으로 위임**한다([Route.FLEET]) — 플릿이
 * 운반 전체를 제공하므로 미들웨어가 이동·도킹·하역을 조합하지 않는다. 플릿의 완료가
 * E1 이고, 그 용기가 그 자리에 있는지는 인계 설비(E2)가 말한다. 최고 등급 E2.
 */
class DeliverContainer : LogicalCapability {

    override val workMasterId: String = WORK_MASTER

    override val maxEvidence: Evidence = Evidence.E2

    override fun plan(order: JobOrder): List<ExecutionUnit> {
        val source = order.equipmentRequirements.firstOrNull { it.equipmentUse == EquipmentUse.SOURCE }
        val destination = order.equipmentRequirements.firstOrNull { it.equipmentUse == EquipmentUse.DESTINATION }
        val container = source?.properties?.get(EquipmentUse.PROP_CONTAINER)

        if (source == null || destination == null || container == null) {
            return listOf(
                ExecutionUnit(
                    unitId = container ?: order.jobOrderId,
                    route = Route.FLEET,
                    skillType = TRANSPORT,
                    parameters = emptyMap(),
                    expectedIdentity = container,
                    source = source?.id,
                    destination = destination?.id,
                    state = UnitState.FAILED,
                    failureClass = INCOMPLETE_ORDER,
                ),
            )
        }

        return listOf(
            ExecutionUnit(
                unitId = container,
                route = Route.FLEET,
                skillType = TRANSPORT,
                parameters = mapOf(
                    P_CONTAINER to container,
                    P_SOURCE to source.id,
                    P_DESTINATION to destination.id,
                ),
                expectedIdentity = container,
                source = source.id,
                destination = destination.id,
            ),
        )
    }

    companion object {
        const val WORK_MASTER = "DeliverContainer"

        /** 플릿 계약의 실행 단위 이름 — 계약 카탈로그의 스킬이 아니다. */
        const val TRANSPORT = "transport"
        const val P_CONTAINER = "container"
        const val P_SOURCE = "source"
        const val P_DESTINATION = "destination"

        const val INCOMPLETE_ORDER = "INCOMPLETE_ORDER"
    }
}
