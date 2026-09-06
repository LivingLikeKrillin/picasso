package dev.picasso.profile

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

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
                deprecatedAfter = s.field("deprecated_after")?.asText(),
                parameters = s.path("parameters").map { p ->
                    ParameterEntry(
                        key = p.path("key").asText(),
                        valueType = p.path("value_type").asText(),
                        optional = p.path("optional").asBoolean(),
                        minValue = p.field("min_value")?.asDouble(),
                        maxValue = p.field("max_value")?.asDouble(),
                        unit = p.field("unit")?.asText(),
                        allowedValues = p.path("allowed_values").map { it.asText() },
                        maxLength = p.field("max_length")?.asInt(),
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

    /**
     * 소요시간과 지터. **투영에 들어가지 않는다**(§7.2) — 진행률 파생에만
     * 쓰고 소비자는 진행률을 받지 소요시간을 받지 않는다. 그래도 `mimic`이
     * 읽어야 하므로 모델에는 있다.
     */
    val durations: List<DurationEntry> by lazy {
        root.path("durations").map { d ->
            DurationEntry(
                skillType = d.path("skill_type").asText(),
                seconds = d.path("seconds").asDouble(),
                jitterRatio = d.field("jitter_ratio")?.asDouble() ?: 0.0,
            )
        }
    }

    val failureModes: List<FailureModeEntry> by lazy {
        root.path("failure_modes").map { f ->
            FailureModeEntry(
                errorType = f.path("error_type").asText(),
                skillType = f.field("skill_type")?.asText(),
                resolution = f.path("resolution").asText(),
            )
        }
    }

    /**
     * 문서 전체. 원문 보존이 필요할 때 쓴다.
     *
     * **검사 6번은 이것이 아니라 [projection]을 쓴다** — §5.2의 버전 규칙은
     * 투영 필드에만 걸리므로, 통째 diff하면 소요시간 조정 같은 비투영 변경에
     * major/minor 증가를 요구하게 된다.
     */
    fun tree(): JsonNode = root.deepCopy()

    /**
     * 어휘 diff 대상만 남긴 트리. 검사 6번이 쓴다(설계 §11.1).
     * 비투영 필드는 §7.2가 정한다 — 능력이 아니라 문서의 메타이거나
     * 에뮬레이터의 거동 설정이라 버전 규칙의 대상이 아니다.
     */
    fun projection(): JsonNode =
        (root.deepCopy() as ObjectNode).remove(NON_PROJECTION)

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

    data class DurationEntry(
        val skillType: String,
        val seconds: Double,
        /** 0이면 지터 없음. */
        val jitterRatio: Double,
    )

    data class FailureModeEntry(
        val errorType: String,
        val skillType: String?,
        val resolution: String,
    )

    companion object {
        /**
         * §7.2 — 투영에 들어가지 않는 최상위 필드. [projection]이 뺀다.
         * `schema_version`은 문서의 메타이고, 나머지 셋은 에뮬레이터의
         * 거동 설정이라 §5.2의 버전 규칙 대상이 아니다.
         */
        val NON_PROJECTION: Set<String> =
            setOf("schema_version", "durations", "failure_modes", "replay_buffer_size")

        private val mapper = ObjectMapper()
            // 기본 설정은 중복 멤버 이름을 조용히 마지막 값으로 접는다.
            // 스키마 검증기는 이미 파싱된 트리를 보므로 역시 못 잡는다.
            // 그러면 {"min_value":10,"min_value":1,"max_value":5} 로
            // 검사 3번의 min ≤ max 규칙을 통째로 우회할 수 있다(실측).
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)

        /**
         * 던지지 않는다. 깨진 문서는 검사 3번이 소견으로 보고해야지
         * 게이트를 죽여서는 안 된다.
         */
        fun parse(path: String, json: String): Result<ProfileDocument> = runCatching {
            val root = mapper.readTree(json)
            // 빈 파일·배열·스칼라가 전부 readTree를 통과한다. 막지 않으면
            // 0바이트 프로파일이 malformed가 아니라 "스킬 0개인 정상 문서"가
            // 되어 검사 4·6번이 대조할 게 없다며 PASS를 낸다(실측).
            require(root != null && root.isObject) {
                "프로파일 루트가 JSON 객체가 아니다: ${root?.nodeType ?: "빈 문서"}"
            }
            ProfileDocument(path, json, root)
        }

        /** 명시적 JSON null을 값으로 접지 않는다. `unit: null`이 "null" 문자열이 되는 것을 막는다. */
        private fun JsonNode.field(name: String): JsonNode? = get(name)?.takeIf { !it.isNull }
    }
}
