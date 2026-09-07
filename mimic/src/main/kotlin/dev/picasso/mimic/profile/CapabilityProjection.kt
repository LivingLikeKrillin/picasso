package dev.picasso.mimic.profile

import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.OptionalFieldDeclaration
import dev.picasso.contracts.v1.OptionalFieldSupport
import dev.picasso.contracts.v1.ParameterDeclaration
import dev.picasso.contracts.v1.ProtocolLimits
import dev.picasso.contracts.v1.PublishInterval
import dev.picasso.contracts.v1.SkillDeclaration
import dev.picasso.contracts.v1.Support
import dev.picasso.contracts.v1.ValueType
import dev.picasso.profile.ProfileDocument

/**
 * 프로파일 문서를 `Capability`로 투영한다.
 *
 * **설계 §7.2의 표가 이 함수의 명세다.** 완료 기준 10이 "프로파일 문서에서
 * 이 표대로 파생한 값 == `GetCapabilities` 응답"을 정확 비교하며, 능력을
 * 하드코딩하면 거기서 걸린다.
 *
 * 순수 함수다 — 같은 문서는 언제나 같은 메시지가 된다. 기종별 분기가 없고
 * 코드는 해석기다(§10.1).
 *
 * **비투영 다섯을 싣지 않는다** — `schema_version`, `derived_from`,
 * 소요시간·지터, 실패 모드와 `rate`·`resolution`, 재생 버퍼 크기.
 * [ProfileDocument.NON_PROJECTION]이 그 목록이고 게이트 6번도 같은 상수를
 * 쓴다. `Capability`에 자리 자체가 없으므로 실을 수도 없다.
 *
 * **`derived_from`은 `GetCapabilitiesResponse.robot_software`와 짝이다.**
 * 프로파일이 "어느 펌웨어에서 파생했는가"를 선언하고 기체가 "나는 지금 어느
 * 펌웨어인가"를 보고한다. 둘을 대조하는 것은 `registry`의 일이며, 그래서
 * 전자는 투영 밖이고 후자는 `Capability` 밖이다 — 하나는 판단의 근거이고
 * 하나는 사실이다(§4.9의 "판단은 밖으로, 사실은 안으로").
 */
object CapabilityProjection {

    fun of(document: ProfileDocument): Capability =
        Capability.newBuilder()
            // 기종 좌표
            .setVendor(document.vendor)
            .setModel(document.model)
            .setProfileRevision(document.revision)
            // 제어 소유권 (§4.9)
            .setExclusiveControlRequired(document.exclusiveControlRequired)
            // 상태 발행 간격 — 소비자의 신선도 판정 입력
            .setPublishInterval(
                PublishInterval.newBuilder()
                    .setMinSeconds(document.publishIntervalMinSeconds)
                    .setMaxSeconds(document.publishIntervalMaxSeconds),
            )
            // 프로토콜 한계
            .setProtocolLimits(
                ProtocolLimits.newBuilder()
                    .setMaxStringLength(document.maxStringLength)
                    .setMaxArrayLength(document.maxArrayLength),
            )
            .addAllSkills(document.skills.map(::skill))
            .addAllOptionalFields(document.optionalFields.map(::optionalField))
            .build()

    private fun skill(entry: ProfileDocument.SkillEntry): SkillDeclaration =
        SkillDeclaration.newBuilder()
            .setSkillType(entry.skillType)
            .setMajor(entry.major)
            .setMinor(entry.minor)
            .setPauseSupport(support(entry.pauseSupport))
            .setCancelSupport(support(entry.cancelSupport))
            .also { builder ->
                // 폐기 예고는 선택이다. §9.3의 예고 단계가 여기에 쓴다.
                entry.deprecatedAfter?.let(builder::setDeprecatedAfter)
            }
            .addAllParameters(entry.parameters.map(::parameter))
            .build()

    private fun parameter(entry: ProfileDocument.ParameterEntry): ParameterDeclaration =
        ParameterDeclaration.newBuilder()
            .setKey(entry.key)
            .setValueType(valueType(entry.valueType))
            .setOptional(entry.optional)
            .addAllAllowedValues(entry.allowedValues)
            .also { builder ->
                // **선언한 것만 싣는다.** 계약이 명시적 존재를 쓰는 이유가
                // 이것이다 — 없는 제약을 0으로 실으면 소비자가 "음수 금지"나
                // "빈 문자열만 허용"으로 읽는다.
                entry.minValue?.let(builder::setMinValue)
                entry.maxValue?.let(builder::setMaxValue)
                entry.unit?.let(builder::setUnit)
                entry.maxLength?.let(builder::setMaxLength)
            }
            .build()

    private fun optionalField(entry: ProfileDocument.OptionalFieldEntry): OptionalFieldDeclaration =
        OptionalFieldDeclaration.newBuilder()
            .setParameterPath(entry.parameterPath)
            .setSupport(optionalFieldSupport(entry.support))
            .build()

    // ── 값 어휘 매핑
    //
    // **미지의 값을 만나면 실패한다.** 조용히 기본값으로 접으면 소비자가
    // 없는 능력을 믿는다 — UNKNOWN으로 접으면 "시도해도 된다"가 되고
    // UNSPECIFIED로 접으면 소비자가 무엇을 해야 할지 모른다. 프로파일이
    // 스키마를 통과했다면 여기 오지 않으므로, 오면 스키마와 계약이 어긋난
    // 것이고 그것은 게이트 3·4번이 잡아야 할 일이다.

    private fun support(value: String): Support = when (value) {
        "YES" -> Support.SUPPORT_YES
        "NO" -> Support.SUPPORT_NO
        "UNKNOWN" -> Support.SUPPORT_UNKNOWN
        else -> error("프로파일의 Support 값을 계약으로 옮길 수 없다: '$value'")
    }

    private fun valueType(value: String): ValueType = when (value) {
        "BOOL" -> ValueType.VALUE_TYPE_BOOL
        "INTEGER" -> ValueType.VALUE_TYPE_INTEGER
        "NUMBER" -> ValueType.VALUE_TYPE_NUMBER
        "STRING" -> ValueType.VALUE_TYPE_STRING
        "ENUM" -> ValueType.VALUE_TYPE_ENUM
        else -> error("프로파일의 ValueType 값을 계약으로 옮길 수 없다: '$value'")
    }

    private fun optionalFieldSupport(value: String): OptionalFieldSupport = when (value) {
        "SUPPORTED" -> OptionalFieldSupport.OPTIONAL_FIELD_SUPPORT_SUPPORTED
        "REQUIRED" -> OptionalFieldSupport.OPTIONAL_FIELD_SUPPORT_REQUIRED
        else -> error("프로파일의 OptionalFieldSupport 값을 계약으로 옮길 수 없다: '$value'")
    }
}
