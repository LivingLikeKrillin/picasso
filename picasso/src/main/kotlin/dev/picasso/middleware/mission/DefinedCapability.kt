package dev.picasso.middleware.mission

import dev.picasso.middleware.DeadlineOutcome
import dev.picasso.middleware.EquipmentRequirement
import dev.picasso.middleware.Evidence
import dev.picasso.middleware.ExecutionUnit
import dev.picasso.middleware.JobOrder
import dev.picasso.middleware.LogicalCapability
import dev.picasso.middleware.Route
import dev.picasso.middleware.UnitState
import dev.picasso.middleware.WaitSpec
import java.time.Duration

/**
 * 해석기 — 임무 정의([MissionDefinition])를 [LogicalCapability] 로 바꾼다.
 *
 * 그래서 실행·리비전·인시던트는 코드 케이퍼빌리티와 **같은 경로**를 탄다. 엔진이 아는 것은 계획된 단위 시퀀스뿐이고,
 * 그것이 코드에서 왔는지 데이터에서 왔는지 모른다.
 *
 * **검증을 통과한 정의만 받는다.** 대기 노드의 기한과 기한 뒤 상태를 생성 때 엔진의 값으로 바꾸며, 없거나 허용 밖이면
 * 여기서 실패한다 — 계획 때 실패하면 작업 지시를 받는 도중에 미들웨어가 넘어진다. 활성화는 검증기를 먼저 부르므로
 * ([InMemoryMissionCatalog.activate]) 그 길에서는 여기서 실패하지 않는다.
 */
class DefinedCapability(val definition: MissionDefinition) : LogicalCapability {

    override val workMasterId: String = definition.workMasterId

    override val maxEvidence: Evidence = definition.maxEvidence

    override val preferredOptionals: Map<String, String> = definition.preferredOptionals

    /** 대기 노드마다 엔진의 대기 사양. 생성 때 한 번 바꾼다. */
    private val waits: Map<String, WaitSpec> = definition.steps.filterIsInstance<WaitStep>().associate { step ->
        val seconds = requireNotNull(step.deadlineSeconds) { "검증을 거치지 않은 정의다: ${step.id} 에 기한이 없다" }
        require(seconds > 0) { "검증을 거치지 않은 정의다: ${step.id} 의 기한이 양수가 아니다($seconds)" }
        val outcome = DeadlineOutcome.entries.firstOrNull { it.name == step.onDeadline }
            ?: throw IllegalArgumentException("검증을 거치지 않은 정의다: ${step.id} 의 기한 뒤 상태가 허용 밖이다(${step.onDeadline})")
        step.id to WaitSpec(step.signal, step.expect, Duration.ofSeconds(seconds), outcome)
    }

    override fun plan(order: JobOrder): List<ExecutionUnit> = definition.steps.flatMap { step ->
        when (step) {
            is WaitStep -> listOf(waitUnit(step))
            is UnitStep -> units(step, order)
        }
    }

    /**
     * 대기 노드 하나 = 대기 단위 하나. 단위 id 는 노드 id 다 — 작업 응답의 완료·미완료 목록에 다른 단위처럼 오른다.
     *
     * 대기 사양을 **파라미터에도 싣는다.** 엔진은 [ExecutionUnit.wait] 를 읽고, 파라미터는 인시던트의 `intent.unitParameters`
     * 로 나가 무엇을 언제까지 기다렸는지를 칸을 늘리지 않고 보인다.
     */
    private fun waitUnit(step: WaitStep): ExecutionUnit {
        val wait = waits.getValue(step.id)
        return ExecutionUnit(
            unitId = step.id,
            route = Route.SIGNAL,
            skillType = WaitSpec.SKILL_TYPE,
            parameters = mapOf(
                WaitSpec.P_SIGNAL to wait.signal,
                WaitSpec.P_EXPECT to wait.expect,
                WaitSpec.P_DEADLINE_SECONDS to wait.deadline.seconds.toString(),
                WaitSpec.P_ON_DEADLINE to wait.onDeadline.name,
            ),
            expectedIdentity = null,
            source = null,
            destination = null,
            wait = wait,
        )
    }

    /**
     * 단위 노드 하나 = [UnitStep.forEach] 쓰임의 설비마다 로봇 단위 하나, 작업 지시의 설비 순서대로.
     *
     * 짝 규칙은 코드 `PrepareSequencedRack` 과 같다. 후보는 짝 쓰임의 설비 중 맞출 속성이 있는 것이고, 같은 값이 여럿이면
     * **목록에서 뒤엣것**이다(`associateBy`). 반복 중인 설비에 그 속성이 없으면 짝이 없다.
     */
    private fun units(step: UnitStep, order: JobOrder): List<ExecutionUnit> {
        val pairing = step.pairWith
        val partners: Map<String, EquipmentRequirement> = pairing?.let { p ->
            order.equipmentRequirements
                .filter { it.equipmentUse == p.equipmentUse && p.property in it.properties }
                .associateBy { it.properties.getValue(p.property) }
        }.orEmpty()

        return order.equipmentRequirements
            .filter { it.equipmentUse == step.forEach }
            .map { item ->
                val paired = pairing?.let { p -> item.properties[p.property]?.let { partners[it] } }
                val unpaired = pairing != null && paired == null
                val value = { source: ValueSource -> resolve(source, item, paired) }
                ExecutionUnit(
                    unitId = value(step.unitId)!!,
                    route = Route.ROBOT,
                    skillType = step.skill,
                    parameters = step.parameters.mapNotNull { (name, source) -> value(source)?.let { name to it } }.toMap(),
                    expectedIdentity = step.expectedIdentity?.let(value),
                    source = step.source?.let(value),
                    destination = step.destination?.let(value),
                    // 짝이 없으면 시작도 못 한다 — 부족은 계획에서 드러난다(코드 케이퍼빌리티와 같은 자리).
                    state = if (unpaired) UnitState.FAILED else UnitState.PENDING,
                    failureClass = if (unpaired) step.whenUnpaired else null,
                )
            }
    }

    /** 값 출처 하나를 푼다. 출처가 값을 못 내면 대체값, 그것도 없으면 `null`. */
    private fun resolve(source: ValueSource, item: EquipmentRequirement, paired: EquipmentRequirement?): String? =
        when (source.from) {
            ValueFrom.ITEM_ID -> item.id
            ValueFrom.ITEM_PROPERTY -> source.property?.let { item.properties[it] }
            ValueFrom.PAIRED_ID -> paired?.id
        } ?: source.otherwise
}
