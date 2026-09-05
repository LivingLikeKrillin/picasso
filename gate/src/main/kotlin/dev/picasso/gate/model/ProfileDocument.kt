package dev.picasso.gate.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * 프로파일 문서의 읽기 전용 뷰. 검사가 읽는 것을 전부 노출한다.
 *
 * 값 어휘(Support·Resolution 등)를 enum으로 좁히지 않고 문자열로 두는 것은
 * 의도다 — 미지의 값을 만나면 **검사 3번이 스키마로 거절해야** 하고,
 * 여기서 파싱이 먼저 터지면 어느 검사가 무엇을 막았는지 알 수 없게 된다.
 *
 * 스킬·파라미터를 Map이 아니라 List로 유지하는 것도 의도다 — 검사 3번의
 * 중복 규칙 둘((skill_type, major) 중복, 스킬 내 key 중복)이 Map으로 접으면
 * 판정 불가능해진다.
 */
class ProfileDocument private constructor(
    val path: String,
    val raw: String,
    private val root: JsonNode,
) {
    val schemaVersion: String get() = root.path("schema_version").asText()
    val vendor: String get() = root.path("vendor").asText()
    val model: String get() = root.path("model").asText()
    val revision: Int get() = root.path("revision").asInt()
    val exclusiveControlRequired: Boolean
        get() = root.path("exclusive_control_required").asBoolean()
    val replayBufferSize: Int get() = root.path("replay_buffer_size").asInt()

    val publishIntervalMinSeconds: Int
        get() = root.path("publish_interval").path("min_seconds").asInt()
    val publishIntervalMaxSeconds: Int
        get() = root.path("publish_interval").path("max_seconds").asInt()
    val maxStringLength: Int
        get() = root.path("protocol_limits").path("max_string_length").asInt()
    val maxArrayLength: Int
        get() = root.path("protocol_limits").path("max_array_length").asInt()

    val skills: List<SkillEntry> by lazy {
        root.path("skills").map { s ->
            SkillEntry(
                skillType = s.path("skill_type").asText(),
                major = s.path("major").asInt(),
                minor = s.path("minor").asInt(),
                pauseSupport = s.path("pause_support").asText(),
                cancelSupport = s.path("cancel_support").asText(),
                deprecatedAfter = s.get("deprecated_after")?.asText(),
                parameters = s.path("parameters").map { p ->
                    ParameterEntry(
                        key = p.path("key").asText(),
                        valueType = p.path("value_type").asText(),
                        optional = p.path("optional").asBoolean(),
                        minValue = p.get("min_value")?.asDouble(),
                        maxValue = p.get("max_value")?.asDouble(),
                        unit = p.get("unit")?.asText(),
                        allowedValues = p.path("allowed_values").map { it.asText() },
                        maxLength = p.get("max_length")?.asInt(),
                    )
                },
            )
        }
    }

    val optionalFields: List<OptionalFieldEntry> by lazy {
        root.path("optional_fields").map { f ->
            OptionalFieldEntry(
                parameterPath = f.path("parameter_path").asText(),
                support = f.path("support").asText(),
            )
        }
    }

    val failureModes: List<FailureModeEntry> by lazy {
        root.path("failure_modes").map { f ->
            FailureModeEntry(
                errorType = f.path("error_type").asText(),
                skillType = f.get("skill_type")?.asText(),
                resolution = f.path("resolution").asText(),
            )
        }
    }

    /**
     * 검사 6번이 문서를 통째로 diff할 때 쓴다(설계 §11.1 — 6번은 언제나
     * 평탄화 테이블이 아니라 document를 diff한다).
     */
    fun tree(): JsonNode = root.deepCopy()

    /** 소견의 location에 쓴다. */
    fun locationOf(pointer: String): String = "$path#$pointer"

    data class SkillEntry(
        val skillType: String,
        val major: Int,
        val minor: Int,
        val pauseSupport: String,
        val cancelSupport: String,
        val deprecatedAfter: String?,
        val parameters: List<ParameterEntry>,
    )

    data class ParameterEntry(
        val key: String,
        val valueType: String,
        val optional: Boolean,
        val minValue: Double?,
        val maxValue: Double?,
        /** 단위 변경은 축소다 — §11.2 6번 규칙 1 */
        val unit: String?,
        /** 허용 값 축소는 축소다 */
        val allowedValues: List<String>,
        /** 문자열 길이 축소는 축소다 */
        val maxLength: Int?,
    )

    data class OptionalFieldEntry(
        val parameterPath: String,
        /** SUPPORTED → REQUIRED는 축소다 */
        val support: String,
    )

    data class FailureModeEntry(
        val errorType: String,
        val skillType: String?,
        val resolution: String,
    )

    companion object {
        private val mapper = ObjectMapper()

        /**
         * 던지지 않는다. 깨진 문서는 검사 3번이 소견으로 보고해야지
         * 게이트를 죽여서는 안 된다.
         */
        fun parse(path: String, json: String): Result<ProfileDocument> =
            runCatching { ProfileDocument(path, json, mapper.readTree(json)) }
    }
}
