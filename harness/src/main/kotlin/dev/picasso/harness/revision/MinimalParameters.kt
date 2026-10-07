package dev.picasso.harness.revision

import dev.picasso.contracts.v1.ParameterDeclaration
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.SkillDeclaration
import dev.picasso.contracts.v1.ValueType

/**
 * 선언에서 **최소 유효값**과 **어긋난 값**을 만든다(picasso-ops P2·S1d 스펙 §5.5).
 *
 * ## 기종 분기가 아니다
 *
 * [dev.picasso.harness.ContractSuite] 는 *"능력을 보고 값을 고르기 시작하면 그것이 곧 기종 분기"* 라고 적는다.
 * 그 원칙은 **클라이언트**의 것이다 — 같은 클라이언트 코드로 이기종을 다룬다(완료 기준 A-1). 이 객체는 클라이언트가
 * 아니라 시험 데이터를 만드는 쪽이다. 개정판 시험은 처음 보는 프로파일을 받으므로, 값을 손으로 적어 둘 수가 없다.
 * 경계는 ADR 49 에 있다.
 *
 * 값은 선언(키, 형, 범위, 길이, 허용 값)에서만 나온다. 기종 이름도 스킬 이름도 보지 않는다.
 */
object MinimalParameters {

    /** 프로파일이 `REQUIRED` 로 적은 선택 필드의 경로 접두사(§7.2). 키는 그 뒤다. */
    const val PATH_PREFIX = "task.parameters."

    /**
     * 수락될 최소 값 묶음. 필수 키 전부와, [requiredOptional] 에 든 선택 키를 싣는다.
     *
     * @param requiredOptional `REQUIRED` 선택 필드의 키(접두사를 뗀 것)
     */
    fun of(skill: SkillDeclaration, requiredOptional: Set<String>): List<ParameterValue> =
        skill.parametersList
            .filter { !it.optional || it.key in requiredOptional }
            .map { valid(it) }

    /** 선언을 지키는 값 하나. */
    fun valid(declaration: ParameterDeclaration): ParameterValue {
        val builder = ParameterValue.newBuilder().setKey(declaration.key)
        return when (declaration.valueType) {
            ValueType.VALUE_TYPE_STRING -> builder.setStringValue("a")
            ValueType.VALUE_TYPE_BOOL -> builder.setBoolValue(false)
            ValueType.VALUE_TYPE_ENUM -> builder.setStringValue(declaration.allowedValuesList.first())
            ValueType.VALUE_TYPE_INTEGER -> builder.setIntegerValue(inRange(declaration).let { kotlin.math.ceil(it).toLong() })
            ValueType.VALUE_TYPE_NUMBER -> builder.setNumberValue(inRange(declaration))
            ValueType.VALUE_TYPE_UNSPECIFIED, ValueType.UNRECOGNIZED ->
                error("선언의 value_type 이 미정이다: ${declaration.key}")
        }.build()
    }

    /**
     * 선언을 어기는 값들. 이름은 검사 식별자에 붙는다(`above_max`, `below_min`, `too_long`, `not_allowed`).
     * 선언이 없는 제약은 어길 수 없으므로 내지 않는다.
     */
    fun violations(declaration: ParameterDeclaration): List<Pair<String, ParameterValue>> = buildList {
        val builder = { ParameterValue.newBuilder().setKey(declaration.key) }
        when (declaration.valueType) {
            ValueType.VALUE_TYPE_NUMBER -> {
                if (declaration.hasMaxValue()) add("above_max" to builder().setNumberValue(declaration.maxValue + 1).build())
                if (declaration.hasMinValue()) add("below_min" to builder().setNumberValue(declaration.minValue - 1).build())
            }
            ValueType.VALUE_TYPE_INTEGER -> {
                if (declaration.hasMaxValue()) {
                    add("above_max" to builder().setIntegerValue(kotlin.math.floor(declaration.maxValue).toLong() + 1).build())
                }
                if (declaration.hasMinValue()) {
                    add("below_min" to builder().setIntegerValue(kotlin.math.ceil(declaration.minValue).toLong() - 1).build())
                }
            }
            ValueType.VALUE_TYPE_STRING ->
                if (declaration.hasMaxLength()) {
                    add("too_long" to builder().setStringValue("a".repeat(declaration.maxLength + 1)).build())
                }
            ValueType.VALUE_TYPE_ENUM ->
                add("not_allowed" to builder().setStringValue(declaration.allowedValuesList.joinToString("") + "_").build())
            else -> Unit
        }
    }

    /** 0 을 선언 범위 안으로 옮긴 값. 하한이 0 보다 크면 하한, 상한이 0 보다 작으면 상한이다. */
    private fun inRange(declaration: ParameterDeclaration): Double {
        var value = 0.0
        if (declaration.hasMinValue() && value < declaration.minValue) value = declaration.minValue
        if (declaration.hasMaxValue() && value > declaration.maxValue) value = declaration.maxValue
        return value
    }
}
