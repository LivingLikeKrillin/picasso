package dev.picasso.middleware

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

    /** 상류 요청을 원자 단위 열로. 계획이지 실행이 아니다. */
    fun plan(order: JobOrder): List<ExecutionUnit>
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
            .filter { it.equipmentUse == USE_SOURCE }
            .associateBy { it.properties[PROP_MATERIAL] }

        return order.equipmentRequirements
            .filter { it.equipmentUse == USE_DESTINATION }
            .map { slot ->
                val material = slot.properties[PROP_MATERIAL]
                val source = material?.let { sources[it] }
                ExecutionUnit(
                    unitId = slot.id,
                    skillType = SKILL,
                    parameters = mapOf(
                        P_OBJECT to (source?.id ?: ""),
                        P_DESTINATION to slot.id,
                    ),
                    expectedMaterial = material,
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

        /** `EquipmentUse` 값 — 표준이 열어 두었고 **우리가 지은 말**이다. */
        const val USE_DESTINATION = "destination"
        const val USE_SOURCE = "source"
        const val PROP_MATERIAL = "material"

        /** 그 타입을 제시하는 자리가 주문에 없다 — 부품 부족의 미들웨어 쪽 이름. */
        const val NO_SOURCE = "NO_SOURCE_FOR_MATERIAL"
    }
}
