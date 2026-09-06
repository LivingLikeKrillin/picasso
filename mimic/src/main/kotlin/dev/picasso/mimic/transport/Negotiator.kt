package dev.picasso.mimic.transport

import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.CapabilityRequirement
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.OptionalFieldSupport
import dev.picasso.contracts.v1.Reference
import dev.picasso.contracts.v1.Rejection
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.profile.Requirement
import io.grpc.Status

/**
 * §5.4의 핸드셰이크 판정. 순수 함수다 — 입력은 능력·헤더·요구, 출력은 거절 목록.
 *
 * **안 맞는 것을 한 번에 전부 돌려준다.** `NegotiateResponse`만 `oneof`가
 * 아닌 것이 그 때문이다 — 협상은 요구 집합 전체를 한 번에 보므로 "무엇 하나
 * 때문에 실패했는가"가 아니라 "무엇무엇이 안 맞는가"를 줘야 클라이언트가 한
 * 번에 고친다. 첫 거절에서 끊는 구현은 "각 코드가 나온다"만 보는 시험을
 * **전부 통과한다**(각 케이스가 한 가지만 어기기 때문이다).
 *
 * 요구 문자열을 해석하지 못하면 판정 자체가 불가능하므로 gRPC 상태로 나간다.
 * **그때도 틀린 것을 전부 열거한다** — 요구 집합은 설정 파일이고(§5.4) 오타는
 * 왕복 한 번에 다 알려줘야 한다.
 */
object Negotiator {

    fun negotiate(
        capability: Capability,
        header: MessageHeader,
        requirement: CapabilityRequirement,
    ): List<Rejection> {
        val requirements = parseAll(requirement.requirementsList)

        return buildList {
            addAll(identity(header, requirement))
            requirements.forEach { addAll(version(capability, it)) }
            addAll(requiredOptionalFields(capability, requirement))
            addAll(limits(capability, requirement))
        }
    }

    /** 해석할 수 없는 요구는 판정 불가다. 전부 모아 한 번에 던진다. */
    private fun parseAll(texts: List<String>): List<Requirement> {
        val problems = mutableListOf<String>()
        val parsed = texts.mapNotNull { text ->
            runCatching { Requirement.parse(text) }
                .onFailure { problems += it.message!! }
                .getOrNull()
        }
        if (problems.isNotEmpty()) {
            throw Status.INVALID_ARGUMENT
                .withDescription("요구를 해석할 수 없다:\n${problems.joinToString("\n") { "  - $it" }}")
                .asRuntimeException()
        }
        return parsed
    }

    /**
     * 헤더가 권위이고 페이로드의 `client_id`·`robot_id`는 복사본이다(§5.4).
     *
     * 거절 목록에 **함께** 싣는다. 신원만 단독 반환하면 클라이언트가 고칠
     * 때마다 한 번씩 왕복하게 된다.
     */
    private fun identity(header: MessageHeader, requirement: CapabilityRequirement): List<Rejection> =
        buildList {
            if (requirement.clientId != header.clientId) {
                add(
                    reject(
                        RejectionCode.REJECTION_CODE_IDENTITY_MISMATCH,
                        "client_id가 헤더와 다르다: 헤더='${header.clientId}', " +
                            "페이로드='${requirement.clientId}'",
                    ),
                )
            }
            if (requirement.robotId != header.robotId) {
                add(
                    reject(
                        RejectionCode.REJECTION_CODE_IDENTITY_MISMATCH,
                        "robot_id가 헤더와 다르다: 헤더='${header.robotId}', " +
                            "페이로드='${requirement.robotId}'",
                        Reference.Key.KEY_ROBOT_ID to header.robotId,
                    ),
                )
            }
        }

    /**
     * §5.2 — 동일성은 major가 결정하고, 같은 major 안에서 로봇의 `minor`가
     * 요구 이상이어야 한다.
     *
     * **minor 부족도 `MAJOR_MISMATCH`다.** §12.2의 13번이 협상 거절을 다섯으로
     * 못박았고 버전 불만족을 뜻하는 코드가 그것뿐이다. 이름이 실제보다 좁으므로
     * `detail`이 무엇이 부족한지 말한다(§15).
     *
     * 스킬이 아예 없으면 `SKILL_ABSENT` **하나만** 낸다 — 둘 다 내면 소비자가
     * 같은 요구에 대해 두 가지를 고치려 든다.
     */
    private fun version(capability: Capability, requirement: Requirement): List<Rejection> {
        val declared = capability.skillsList.filter { it.skillType == requirement.skillType }
        if (declared.isEmpty()) {
            return listOf(
                reject(
                    RejectionCode.REJECTION_CODE_SKILL_ABSENT,
                    "선언하지 않은 스킬이다: $requirement",
                    Reference.Key.KEY_SKILL_ID to requirement.skillType,
                ),
            )
        }
        if (declared.any { requirement.satisfiedBy(it.major, it.minor) }) return emptyList()

        val versions = declared.joinToString { "${it.major}.${it.minor}" }
        val sameMajor = declared.filter { it.major == requirement.major }
        val detail = if (sameMajor.isEmpty()) {
            "major가 다르다: 요구=$requirement, 로봇=$versions"
        } else {
            "minor가 요구에 못 미친다: 요구=$requirement, 로봇=$versions"
        }
        return listOf(
            reject(
                RejectionCode.REJECTION_CODE_MAJOR_MISMATCH,
                detail,
                Reference.Key.KEY_SKILL_ID to requirement.skillType,
            ),
        )
    }

    /**
     * §5.3 — 로봇이 `REQUIRED`로 선언한 선택 필드를 클라이언트가 쓰지 않으면
     * **핸드셰이크에서** 걸린다.
     *
     * `SUPPORTED`는 거절이 아니다. 선언된 선택 필드를 안 쓰면 무조건 거절하는
     * 구현은 `REQUIRED`만 보는 시험을 통과한다.
     */
    private fun requiredOptionalFields(
        capability: Capability,
        requirement: CapabilityRequirement,
    ): List<Rejection> {
        val used = requirement.optionalFieldsUsedList.toSet()
        return capability.optionalFieldsList
            .filter { it.support == OptionalFieldSupport.OPTIONAL_FIELD_SUPPORT_REQUIRED }
            .filterNot { it.parameterPath in used }
            .map {
                reject(
                    RejectionCode.REJECTION_CODE_REQUIRED_OPTIONAL_MISSING,
                    "로봇이 요구하는 선택 필드를 쓰지 않는다: ${it.parameterPath}",
                    Reference.Key.KEY_PARAMETER_KEY to it.parameterPath,
                )
            }
    }

    /**
     * §5.4 — `limits_needed`가 `LIMIT_EXCEEDED`의 판정 입력이며 **핸드셰이크에서**
     * 본다. 이것이 없으면 한계 초과가 런타임 발행 시점(§10.4 ③)에야 드러난다.
     *
     * **한계는 둘이다.** 하나만 보면 나머지를 무시하는 구현이 통과한다.
     * 0은 "말하지 않았다"이므로 막지 않는다(proto3 암묵 존재).
     */
    private fun limits(capability: Capability, requirement: CapabilityRequirement): List<Rejection> {
        val needed = requirement.limitsNeeded
        val declared = capability.protocolLimits

        return listOfNotNull(
            exceeds("max_string_length", needed.maxStringLength, declared.maxStringLength),
            exceeds("max_array_length", needed.maxArrayLength, declared.maxArrayLength),
        )
    }

    /**
     * **`needed == 0`을 따로 걸러내지 않는다.** 0은 "말하지 않았다"이지만,
     * 프로파일 스키마가 로봇 쪽 한계에 `minimum: 1`을 강제하므로
     * `0 > declared`는 결코 참이 되지 않는다 — 걸러내는 가드는 도달 불가능한
     * 죽은 코드이고, 방어인 척하지만 아무것도 막지 못한다(실측: 가드를 지워도
     * 시험이 전부 초록이었다).
     *
     * 그 스키마 제약이 이 판단을 지탱하므로 `NegotiateTest`가 그것을 직접
     * 확인한다. 스키마가 0을 허용하도록 풀리면 여기를 다시 봐야 한다.
     */
    private fun exceeds(name: String, needed: Int, declared: Int): Rejection? =
        if (needed > declared) {
            reject(
                RejectionCode.REJECTION_CODE_LIMIT_EXCEEDED,
                "$name 이 로봇의 한계를 넘는다: 요구=$needed, 로봇=$declared",
                Reference.Key.KEY_PARAMETER_KEY to name,
            )
        } else {
            null
        }

    /**
     * 거절에는 언제나 이유가 붙는다 — openTCS `ExplainedBoolean(value, reason)`의
     * 일반화다. 거절만 하고 이유를 안 주면 운영에서 원인을 못 찾는다.
     */
    private fun reject(
        code: RejectionCode,
        detail: String,
        reference: Pair<Reference.Key, String>? = null,
    ): Rejection = Rejection.newBuilder()
        .setCode(code)
        .setDetail(detail)
        .also { builder ->
            reference?.let { (key, value) ->
                builder.addReferences(Reference.newBuilder().setKey(key).setValue(value))
            }
        }
        .build()
}
