package dev.picasso.profile

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * 클라이언트가 요구하는 스킬 하나. §5.2의 `pick_place@^1.2` 형식.
 *
 * 캐럿은 **major 안에서 호환**을 뜻한다 — 같은 major이고 로봇의 `minor`가
 * 요구 이상이면 만족한다. §5.2가 "minor 증가 = 선택 파라미터 추가만,
 * 클라이언트가 몰라도 동작해야 한다"고 했으므로 **클라이언트가 낮은 minor를
 * 요구하는 것은 정상이고**, 반대 방향(로봇이 낮음)이 불만족이다.
 */
data class Requirement(val skillType: String, val major: Int, val minor: Int) {

    override fun toString() = "$skillType@^$major.$minor"

    /** 로봇이 선언한 `major.minor`가 이 요구를 만족하는가. */
    fun satisfiedBy(robotMajor: Int, robotMinor: Int): Boolean =
        robotMajor == major && robotMinor >= minor

    companion object {
        /**
         * 문법은 하나다. **관대하게 파싱하지 않는다** — `pick_place@1.2`를
         * 받아 주면 캐럿이 없는 요구가 있는 것처럼 되고, 그것이 무슨 뜻인지
         * 계약이 정한 바가 없다.
         */
        private val GRAMMAR = Regex("""^([^@\s]+)@\^(\d+)\.(\d+)$""")

        fun parse(text: String): Requirement {
            val match = GRAMMAR.matchEntire(text)
                ?: throw IllegalArgumentException(
                    "요구 문법이 아니다: '$text' (형식: <skill_type>@^<major>.<minor>)",
                )
            return Requirement(
                match.groupValues[1],
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
            )
        }
    }
}

/** `limits_needed`. **0은 "말하지 않았다"다** — 계약의 proto3 암묵 존재와 같은 뜻이다. */
data class LimitsNeeded(val maxStringLength: Int = 0, val maxArrayLength: Int = 0)

/**
 * 클라이언트가 세션 시작 때 제시하는 요구 집합(§5.4).
 *
 * **`profile-model`에 있다.** `client`(요구를 만드는 쪽)와 `mimic`(판정하는
 * 쪽)이 둘 다 쓰는데, 발신자 모듈에 두면 §3.2가 금지한 `client → mimic`
 * 간선이 생기거나 `client`가 같은 문법을 두 번째로 짜게 된다.
 *
 * **§5.4가 "코드가 아니라 설정"이라 못박았으므로 파일에서 읽는다.**
 * `client`는 기종을 식별해 분기하지 않는다.
 *
 * JSON Schema를 따로 두지 않는다. 프로파일에 스키마가 있는 이유는 게이트
 * 검사 3번과 `registry`가 같은 선언을 공유해야 하기 때문인데(§7.1), 요구
 * 집합은 기동 때 한 소비자가 읽는 것이라 파서의 엄격함으로 족하다. 대신
 * **모르는 키를 조용히 무시하지 않는다** — 오타 난 `requirement`가 "요구
 * 없음"이 되면 협상이 무조건 통과한다.
 */
data class RequirementSet(
    val clientId: String,
    val requirements: List<Requirement>,
    val optionalFieldsUsed: List<String>,
    val limitsNeeded: LimitsNeeded,
) {
    companion object {

        const val SCHEMA_VERSION = "1.0.0"

        private val KNOWN = setOf(
            "schema_version", "client_id", "requirements",
            "optional_fields_used", "limits_needed",
        )

        private val mapper = ObjectMapper()
            // 프로파일과 같은 이유다 — 중복 멤버를 마지막 값으로 접으면
            // {"requirements":[...],"requirements":[]} 가 통과한다.
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)

        /**
         * **틀린 것을 전부 열거하고 한 번에 던진다.** §5.4가 요구 집합을 설정
         * 파일이라 한 이상 오타는 왕복 한 번에 다 알려줘야 한다 — `Negotiate`가
         * 거절 다섯을 한 번에 돌려주는 것과 같은 이유이고, 파싱만 첫 실패에서
         * 끊으면 그 규율과 모순이다.
         */
        fun parse(path: String, json: String): RequirementSet {
            val root = runCatching { mapper.readTree(json) }.getOrElse {
                throw IllegalArgumentException("요구 집합이 JSON이 아니다: $path (${it.message})")
            }
            require(root != null && root.isObject) {
                "요구 집합의 루트가 JSON 객체가 아니다: $path"
            }

            val problems = mutableListOf<String>()

            (root.fieldNames().asSequence().toSet() - KNOWN).sorted().forEach {
                problems += "모르는 키: '$it'"
            }

            val version = root.path("schema_version").asText()
            if (version != SCHEMA_VERSION) {
                problems += "schema_version이 '$SCHEMA_VERSION'이 아니다: '$version'"
            }

            val clientId = root.path("client_id").asText()
            if (clientId.isBlank()) problems += "client_id가 비었다"

            val rawRequirements = root.path("requirements")
            if (!rawRequirements.isArray || rawRequirements.isEmpty) {
                problems += "requirements가 비었다 — 요구가 없으면 협상이 무조건 통과한다"
            }

            val requirements = rawRequirements.mapNotNull { node ->
                runCatching { Requirement.parse(node.asText()) }
                    .onFailure { problems += it.message!! }
                    .getOrNull()
            }

            requirements.groupBy { it.skillType }.filterValues { it.size > 1 }.forEach { (type, dup) ->
                // 같은 스킬을 두 버전으로 요구하는 것은 모순이다. 조용히
                // 하나를 고르면 어느 쪽이 판정됐는지 알 수 없다.
                problems += "같은 스킬을 두 번 요구했다: $type (${dup.joinToString()})"
            }

            val optionalFields = root.path("optional_fields_used").map { it.asText() }
            val limits = root.path("limits_needed").let {
                LimitsNeeded(
                    maxStringLength = it.path("max_string_length").asInt(),
                    maxArrayLength = it.path("max_array_length").asInt(),
                )
            }

            require(problems.isEmpty()) {
                "요구 집합을 받아들일 수 없다: $path\n" + problems.joinToString("\n") { "  - $it" }
            }

            return RequirementSet(clientId, requirements, optionalFields, limits)
        }
    }
}
