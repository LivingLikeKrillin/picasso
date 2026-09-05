package dev.picasso.gate.checks

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import dev.picasso.gate.CheckResult
import dev.picasso.gate.Finding
import dev.picasso.gate.GateCheck
import dev.picasso.gate.Resource
import dev.picasso.gate.Severity
import dev.picasso.gate.input.GateInput
import dev.picasso.gate.model.ProfileDocument
import java.util.Locale

/**
 * 검사 3 — 프로파일 스키마 + 스키마로 표현할 수 없는 규칙 넷(설계 §11.2).
 *
 * JSON Schema 2020-12에는 **필드 간 수치 비교가 없고**, `uniqueItems`는
 * 항목 전체를 비교하므로 부분 키 중복을 잡지 못한다. Chunk 4에서 넷 다
 * 스키마로는 통과한다는 것을 실측했다.
 *
 * **스키마를 `requires`에 넣지 않는다.** 넣으면 스키마 자원이 없을 때 러너가
 * 이 검사를 통째로 건너뛰고 깨진 프로파일을 아무도 말하지 않은 채 종료코드
 * 0이 난다. 스키마 부재는 부분 건너뜀으로 낮추고, 그것을 하드 실패로
 * 만드는 것은 호출 지점의 몫이다(`GateRunner(required = …)`).
 *
 * `error_type` 어휘는 여기서 따로 보지 않는다 — 스키마의 enum과 `X_` 패턴이
 * 담당하며, 어댑터 전용 둘(`TERMINAL_STATE_VIOLATED`·`CONTROL_AUTHORITY_LOST`)이
 * 그 enum에서 빠져 있어 프로파일이 선언하면 스키마 검증이 거절한다.
 * 시험이 그 배선을 확인한다.
 */
class Check03ProfileSchema : GateCheck {

    override val id = "3"
    override val name = "프로파일 스키마와 구조 규칙"
    override val requires = setOf(Resource.PROFILE_DOCUMENT)

    private val mapper = ObjectMapper()

    override fun run(input: GateInput): CheckResult {
        val findings = mutableListOf<Finding>()

        // 파싱조차 안 된 문서. 이 검사가 말하지 않으면 아무도 말하지 않는다.
        input.malformed.forEach { m ->
            findings += Finding(
                id, Severity.ERROR,
                "프로파일을 JSON으로 읽을 수 없다: ${m.message}",
                m.path,
            )
        }

        input.profiles.forEach { doc -> findings += structuralRules(doc) }

        val schemaJson = input.schemaJson
        if (schemaJson == null) {
            // 스키마 검증만 못 한다. 구조 규칙과 깨진 문서 보고는 이미 끝났다.
            val skipped = setOf(Resource.PROFILE_SCHEMA)
            return if (findings.isEmpty()) CheckResult.Passed(id, skippedParts = skipped)
            else CheckResult.Failed(id, findings, skippedParts = skipped)
        }

        val schema = compile(schemaJson)
        input.profiles.forEach { doc -> findings += validateAgainstSchema(schema, doc) }

        return if (findings.isEmpty()) CheckResult.Passed(id)
        else CheckResult.Failed(id, findings)
    }

    private fun compile(schemaJson: String): JsonSchema =
        JsonSchemaFactory
            .getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(schemaJson, CONFIG)

    private fun validateAgainstSchema(schema: JsonSchema, doc: ProfileDocument): List<Finding> =
        schema.validate(mapper.readTree(doc.raw)).map { v ->
            Finding(
                id, Severity.ERROR,
                v.message,
                // instanceLocation은 $.skills[0].minor 형태다. 파일 경로와
                // 함께 조립해야 사람이 파일을 열 수 있다.
                doc.locationOf(v.instanceLocation.toString()),
            )
        }

    /** 스키마로 표현할 수 없는 넷. 소견은 §11.2 표의 규칙 이름을 말한다. */
    private fun structuralRules(doc: ProfileDocument): List<Finding> {
        val out = mutableListOf<Finding>()

        if (doc.publishIntervalMinSeconds > doc.publishIntervalMaxSeconds) {
            out += Finding(
                id, Severity.ERROR,
                "publish_interval이 뒤집혔다: " +
                    "min_seconds=${doc.publishIntervalMinSeconds} > " +
                    "max_seconds=${doc.publishIntervalMaxSeconds}",
                doc.locationOf("/publish_interval"),
            )
        }

        doc.skills.groupingBy { it.skillType to it.major }.eachCount()
            .filterValues { it > 1 }
            .forEach { (key, count) ->
                out += Finding(
                    id, Severity.ERROR,
                    "(skill_type, major) 중복이다: ${key.first}@${key.second} 가 ${count}번 선언됐다 " +
                        "— 설계 §5.2가 이 쌍을 동일성으로 규정한다",
                    doc.locationOf("/skills"),
                )
            }

        doc.skills.forEachIndexed { i, s ->
            s.parameters.groupingBy { it.key }.eachCount()
                .filterValues { it > 1 }
                .forEach { (key, count) ->
                    out += Finding(
                        id, Severity.ERROR,
                        "${s.skillType}@${s.major} 안에서 파라미터 key 중복이다: " +
                            "'$key'가 ${count}번 — 어느 선언이 유효한지 미정의가 된다",
                        doc.locationOf("/skills/$i/parameters"),
                    )
                }

            s.parameters.forEachIndexed { j, p ->
                val lo = p.minValue
                val hi = p.maxValue
                if (lo != null && hi != null && lo > hi) {
                    out += Finding(
                        id, Severity.ERROR,
                        "범위가 뒤집혔다: ${s.skillType}@${s.major}.${p.key} " +
                            "min_value=$lo > max_value=$hi",
                        doc.locationOf("/skills/$i/parameters/$j"),
                    )
                }
            }
        }

        return out
    }

    private companion object {
        /**
         * networknt는 스키마 소견을 로케일별로 번역한다. 못 박지 않으면
         * 같은 위반이 이 머신에서는 한국어로, `LANG=C`인 CI에서는 영어로 나온다.
         * 게이트 출력이 환경에 따라 달라지면 소견을 문자열로 다루는 모든
         * 것(음성 하네스 포함)이 흔들린다. 고르는 것이 요점이지 무엇을
         * 고르느냐가 아니다.
         */
        val CONFIG: SchemaValidatorsConfig =
            SchemaValidatorsConfig.builder().locale(Locale.KOREAN).build()
    }
}
