package dev.picasso.gate

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **§15.7의 규율을 약속에서 검사로 바꾸는 것이 이 파일의 일이다.**
 *
 * §15.7이 적어 둔 방어책은 하나였다 — *"프로파일을 벤더 문서에서 파생시키고
 * 괴리가 발견되면 고친다."* 그것은 습관이지 장치가 아니다. 지키지 않아도
 * 아무 데도 빨간불이 안 들어오고, 안 지킨 프로파일과 지킨 프로파일이
 * 파일만 봐서는 구별되지 않는다.
 *
 * `profile/provenance/`가 프로파일의 각 값이 어디서 왔는지를 적고, 그 키가
 * `profile/vendors/`의 조사와 **일대일**이라 둘을 맞대 볼 수 있다.
 *
 * ## 무엇을 막고 무엇을 막지 않는가
 *
 * **값을 채우는 것을 막지 않는다.** 벤더가 안 주는 값을 어댑터가 정하는 것은
 * 정당하고 불가피하다 — G1은 자기가 누구인지도, 프로토콜 한계도, 발행 주기도
 * 말하지 않지만 프로파일에는 그 자리가 있다(§9.7의 어댑터가 하는 일이다).
 *
 * **막는 것은 어댑터가 정한 값이 로봇의 선언인 척하는 것이다.** 조사가
 * "벤더가 이것을 주지 않는다"(`level: NONE`)고 적은 항목을 출처 문서가
 * `VENDOR_DOC`이나 `ROBOT`이라 주장하면 여기서 빨갛게 난다.
 *
 * ## 이것이 적합성 시험은 아니다
 *
 * 선언이 어디서 왔는지만 본다. **선언대로 로봇이 행동하는지는 안 본다** —
 * 그것이 §9.7 ④이고 C-3이며 여전히 열려 있다.
 */
class ProfileProvenanceTest {

    private val mapper = ObjectMapper()
    private val provenanceDir = Path.of("..", "profile", "provenance").normalize()
    private val profilesDir = Path.of("..", "profile", "profiles").normalize()
    private val surveyDir = Path.of("..", "profile", "vendors").normalize()
    private val schemaPath = Path.of("..", "profile", "schema", "provenance.schema.json")

    private fun read(path: Path): JsonNode = mapper.readTree(Files.readString(path))

    private fun documents(): List<Pair<String, JsonNode>> =
        Files.list(provenanceDir).use { stream ->
            stream.filter { it.name.endsWith(".json") }.map { it.name to read(it) }.toList()
        }.sortedBy { it.first }

    private fun realModels(): List<Path> =
        Files.list(profilesDir).use { stream ->
            stream.filter { it.name.endsWith(".json") }
                .filter { read(it).path("vendor").asText() != REFERENCE_VENDOR }
                .toList()
        }.sortedBy { it.name }

    @Test
    fun `출처 문서가 스키마를 통과한다`() {
        val schema = JsonSchemaFactory
            .getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(Files.readString(schemaPath), CONFIG)

        val all = documents()
        // 하나도 없으면 아래 시험 전부가 공허하게 초록이다.
        assertTrue(all.isNotEmpty(), "출처 문서가 하나도 없다")

        all.forEach { (name, node) ->
            val problems = schema.validate(node)
            assertTrue(problems.isEmpty(), "$name 이 스키마를 어긴다: ${problems.map { it.message }}")
        }
    }

    @Test
    fun `실물 기종에는 출처 문서가 반드시 있다`() {
        // **이 단언이 없으면 출처를 안 적는 것으로 검사를 피할 수 있다.**
        // 참조 프로파일(`picasso-ref`)은 벤더 문서에서 파생한 것이 아니므로
        // 대상이 아니다 — 그것은 §7.4가 정한 성격이고 `derived_from.source`가
        // 스스로 그렇게 적고 있다.
        val declared = documents().map { (_, n) -> n.path("profile").asText() }.toSet()

        realModels().forEach { path ->
            assertTrue(
                path.name in declared,
                "${path.name} 은 참조 프로파일이 아닌데 출처 문서가 없다 " +
                    "— 값이 어디서 왔는지 아무도 모르는 프로파일이 생긴다",
            )
        }
    }

    @Test
    fun `가리키는 프로파일과 조사가 실재한다`() {
        // 이름으로 짐작하지 않고 문서가 적은 대로 따라간다. 끊어져 있으면
        // 아래 대조가 아무것도 안 맞대 보고 초록이 된다.
        documents().forEach { (name, node) ->
            val profile = profilesDir.resolve(node.path("profile").asText())
            val survey = surveyDir.resolve(node.path("survey").asText())
            assertTrue(Files.isRegularFile(profile), "$name 이 없는 프로파일을 가리킨다: $profile")
            assertTrue(Files.isRegularFile(survey), "$name 이 없는 조사를 가리킨다: $survey")
        }
    }

    @Test
    fun `벤더가 안 주는 값을 벤더가 준 것처럼 적지 않는다`() {
        // **이 파일의 핵심 단언이다.** 조사가 `NONE`이라 적은 항목은 근거가
        // 없다는 뜻이고, 근거가 없는 값의 출처는 `ADAPTER`(어댑터가 정했다)
        // 아니면 `ABSENT`(안 적었다)뿐이다.
        eachItem { doc, item, origin, level ->
            if (level == "NONE") {
                assertTrue(
                    origin in FABRICATED,
                    "$doc 의 $item — 조사는 벤더가 이것을 주지 않는다고 적었는데 " +
                        "출처를 '$origin' 이라 주장한다. 어댑터가 정한 값이 " +
                        "로봇의 선언인 척하는 것이다(§15.7)",
                )
            }
        }
    }

    @Test
    fun `벤더가 주는 값을 조용히 비우지 않는다`() {
        // 위 단언의 반대 방향이다. 조사가 `FULL`이라 적었으면 근거가 있다는
        // 뜻이고, 그런데도 `ABSENT`이면 **있는 근거를 안 쓴 것**이다. 그
        // 판단이 옳을 수는 있으나 조용히 지나가서는 안 된다 — 조사를 고치든
        // 프로파일을 채우든 둘 중 하나를 해야 한다.
        eachItem { doc, item, origin, level ->
            if (level == "FULL") {
                assertTrue(
                    origin !in FABRICATED,
                    "$doc 의 $item — 조사는 벤더가 이 값을 준다고 적었는데 " +
                        "출처가 '$origin' 이다. 조사가 과장이거나 프로파일이 빠뜨렸다",
                )
            }
        }
    }

    @Test
    fun `근거 없는 출처 판정이 없다`() {
        // 조사 문서에 건 것과 같은 규율이다. `ADAPTER`라고만 적으면 나중에
        // 그것이 판단인지 게으름인지 구분되지 않는다.
        documents().forEach { (name, node) ->
            node.path("coverage").fields().forEach { (item, value) ->
                val note = value.path("note").asText()
                assertTrue(
                    note.trim().length >= MIN_NOTE,
                    "$name 의 $item 에 근거가 없다시피 하다: '$note'",
                )
            }
        }
    }

    @Test
    fun `대조가 일곱 항목을 빠짐없이 지난다`() {
        // **위 두 대조가 공허해지는 길** — 출처 문서와 조사의 키가 어긋나면
        // 맞대 볼 것이 없어 단언이 0번 돈다. 횟수를 센다.
        var compared = 0
        eachItem { _, _, _, _ -> compared += 1 }
        assertEquals(
            documents().size * COVERAGE_ITEMS, compared,
            "출처와 조사를 맞대 본 횟수가 모자란다 — 키가 어긋났다",
        )
    }

    /** 출처 문서의 각 항목을 그 조사의 같은 항목과 짝지어 [body]에 준다. */
    private fun eachItem(body: (doc: String, item: String, origin: String, level: String) -> Unit) {
        documents().forEach { (name, node) ->
            val survey = read(surveyDir.resolve(node.path("survey").asText()))
            node.path("coverage").fields().forEach { (item, value) ->
                val level = survey.path("coverage").path(item).path("level")
                assertTrue(!level.isMissingNode, "$name 의 $item 이 조사에 없다")
                body(name, item, value.path("origin").asText(), level.asText())
            }
        }
    }

    private companion object {
        /** §7.4의 참조 프로파일. 벤더 문서에서 파생한 것이 아니다. */
        const val REFERENCE_VENDOR = "picasso-ref"

        /** 벤더에게 근거가 없을 때 허용되는 출처 둘. */
        val FABRICATED = setOf("ADAPTER", "ABSENT")

        /** `vendor-survey.schema.json`·`provenance.schema.json`이 함께 요구하는 항목 수. */
        const val COVERAGE_ITEMS = 7

        const val MIN_NOTE = 10

        val CONFIG: SchemaValidatorsConfig =
            SchemaValidatorsConfig.builder().locale(Locale.KOREAN).build()
    }
}
