package dev.picasso.mimic.engine

import dev.picasso.contracts.v1.ParameterDeclaration
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.SkillDeclaration
import dev.picasso.contracts.v1.ValueType

/** 파라미터 하나가 선언을 어긴 이유. `Rejection.detail`과 `references`가 된다. */
data class ParameterProblem(val key: String, val reason: String)

/**
 * `StartTask`가 실어 온 파라미터를 프로파일의 선언과 대조한다(§5.3·§10.4 ③).
 *
 * **§10.4 ③이 "따로 만들 것이 없다"고 한 것이 이것이다** — 프로파일이 선언한
 * 값 범위·허용 값·최대 길이를 그대로 강제하면, "클라이언트가 기종 A에서
 * 통과하고 기종 B에서 거절당하는 상황"이 실패 주입 없이 저절로 생긴다.
 *
 * §5.3의 두 방향을 지킨다.
 *
 * | 종류 | 모르는 것을 받으면 |
 * |---|---|
 * | 코어 (`grip_force`) | **실패**(fail-closed) |
 * | 벤더 확장 (`x-<vendor>.<key>`) | **무시**(fail-open). 코어는 `x-`를 읽지 않는다 |
 *
 * **어긴 것을 전부 열거한다.** 첫 실패에서 끊으면 클라이언트가 고칠 때마다
 * 한 번씩 왕복하게 되고, 그것은 `Negotiate`가 거절을 한 번에 돌려주는 규율과
 * 모순이다.
 */
object ParameterCheck {

    /** §5.3 — 벤더 확장 접두사. 코어는 이것을 읽지 않는다. */
    const val VENDOR_PREFIX = "x-"

    fun check(
        skill: SkillDeclaration,
        values: List<ParameterValue>,
        maxStringLength: Int,
    ): List<ParameterProblem> {
        val problems = mutableListOf<ParameterProblem>()
        val declared = skill.parametersList.associateBy { it.key }

        values.groupBy { it.key }.filterValues { it.size > 1 }.keys.sorted().forEach {
            // 조용히 마지막 값을 쓰면 어느 것이 적용됐는지 알 수 없다.
            problems += ParameterProblem(it, "같은 키를 두 번 실었다")
        }

        values.forEach { value ->
            if (value.key.startsWith(VENDOR_PREFIX)) return@forEach

            val declaration = declared[value.key]
            if (declaration == null) {
                problems += ParameterProblem(
                    value.key,
                    "${skill.skillType}이 선언하지 않은 키다 (선언된 것: ${declared.keys.sorted()})",
                )
                return@forEach
            }
            problems += violations(declaration, value, maxStringLength)
        }

        val supplied = values.map { it.key }.toSet()
        skill.parametersList.filterNot { it.optional }.filterNot { it.key in supplied }.forEach {
            problems += ParameterProblem(it.key, "필수 파라미터가 없다")
        }

        return problems
    }

    private fun violations(
        declaration: ParameterDeclaration,
        value: ParameterValue,
        maxStringLength: Int,
    ): List<ParameterProblem> {
        val key = declaration.key
        val expected = expectedCase(declaration.valueType)

        if (value.valueCase != expected) {
            // **관대하게 받지 않는다.** INTEGER를 NUMBER 자리에 허용하기
            // 시작하면 어디까지 넓힐지가 코드의 암묵 지식이 되고, 클라이언트는
            // GetCapabilities로 선언된 타입을 이미 알고 있다.
            return listOf(
                ParameterProblem(
                    key,
                    "값 타입이 선언과 다르다: 선언=${declaration.valueType}, 실린 값=${value.valueCase}",
                ),
            )
        }

        val problems = mutableListOf<ParameterProblem>()

        when (declaration.valueType) {
            ValueType.VALUE_TYPE_NUMBER -> problems += range(declaration, key, value.numberValue)
            ValueType.VALUE_TYPE_INTEGER ->
                problems += range(declaration, key, value.integerValue.toDouble())

            ValueType.VALUE_TYPE_STRING -> {
                val text = value.stringValue
                if (declaration.hasMaxLength() && text.length > declaration.maxLength) {
                    problems += ParameterProblem(
                        key, "최대 길이를 넘는다: ${text.length} > ${declaration.maxLength}",
                    )
                }
                // 프로토콜 한계는 스킬 선언과 별개다(§7.2). 핸드셰이크에서
                // 걸러지지만 협상하지 않은 클라이언트도 있다.
                if (text.length > maxStringLength) {
                    problems += ParameterProblem(
                        key, "프로토콜 문자열 한계를 넘는다: ${text.length} > $maxStringLength",
                    )
                }
            }

            ValueType.VALUE_TYPE_ENUM -> {
                // 허용 값 목록이 없으면 송신 직전 검사가 아무것도 강제하지
                // 못한다(skill.proto). 비어 있으면 프로파일이 잘못된 것이므로
                // 여기서 통과시키지 않는다.
                if (value.stringValue !in declaration.allowedValuesList) {
                    problems += ParameterProblem(
                        key,
                        "허용 값이 아니다: '${value.stringValue}' " +
                            "(허용: ${declaration.allowedValuesList})",
                    )
                }
            }

            ValueType.VALUE_TYPE_BOOL -> Unit
            ValueType.VALUE_TYPE_UNSPECIFIED, ValueType.UNRECOGNIZED ->
                error("선언의 value_type이 미정이다: $key — 투영이 이것을 만들 수 없다")
        }

        return problems
    }

    private fun range(
        declaration: ParameterDeclaration,
        key: String,
        actual: Double,
    ): List<ParameterProblem> = buildList {
        // **선언한 것만 본다.** 명시적 존재를 계약에 넣은 이유가 이것이다 —
        // 하한을 선언하지 않은 파라미터를 0으로 읽으면 음수가 전부 거절된다.
        if (declaration.hasMinValue() && actual < declaration.minValue) {
            add(ParameterProblem(key, "하한 미만이다: $actual < ${declaration.minValue}"))
        }
        if (declaration.hasMaxValue() && actual > declaration.maxValue) {
            add(ParameterProblem(key, "상한 초과다: $actual > ${declaration.maxValue}"))
        }
    }

    private fun expectedCase(type: ValueType): ParameterValue.ValueCase = when (type) {
        ValueType.VALUE_TYPE_BOOL -> ParameterValue.ValueCase.BOOL_VALUE
        ValueType.VALUE_TYPE_INTEGER -> ParameterValue.ValueCase.INTEGER_VALUE
        ValueType.VALUE_TYPE_NUMBER -> ParameterValue.ValueCase.NUMBER_VALUE
        // ENUM형도 문자열로 싣는다 — 허용 값 검사는 프로파일이 하지 계약이 하지 않는다.
        ValueType.VALUE_TYPE_STRING, ValueType.VALUE_TYPE_ENUM ->
            ParameterValue.ValueCase.STRING_VALUE
        ValueType.VALUE_TYPE_UNSPECIFIED, ValueType.UNRECOGNIZED ->
            error("선언의 value_type이 미정이다")
    }
}
