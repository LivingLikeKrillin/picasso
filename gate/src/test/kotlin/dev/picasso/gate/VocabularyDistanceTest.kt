package dev.picasso.gate

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import dev.picasso.gate.model.ContractIndex
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **계약 어휘가 실물에 닿는지를 잰 기록**(`profile/distance/`)을 붙든다.
 *
 * ## 문서가 셋인 이유
 *
 * | 문서 | 묻는 것 |
 * |---|---|
 * | `profile/vendors/` | 벤더가 **무엇을 주는가** |
 * | `profile/provenance/` | 프로파일의 값이 **어디서 왔는가** |
 * | `profile/distance/` | 우리 어휘가 **실물에 닿는가** |
 *
 * 셋이 다른 질문이라 접으면 안 된다. 조사는 벤더 문서의 전사이고, 출처는
 * 프로파일 한 장의 계보이며, 거리는 **계약 쪽의 판단**이다.
 *
 * ## 이 시험이 막는 것
 *
 * **스킬을 더해 놓고 안 재는 것.** 계약 카탈로그를 `ContractIndex`로 훑어
 * 모든 스킬에 항목을 요구하므로, 어휘가 늘면 실물마다 재야 하고 안 재면
 * 빨갛다. §15.7이 경고한 것 — 계약이 실물이 아니라 에뮬레이터에 맞춰 굳는
 * 것 — 을 매번 재게 만드는 장치다.
 *
 * **닿는다고 적고 안 선언하거나, 선언하고 안 닿는다고 적는 것.** 둘을 묶어
 * 두지 않으면 두 문서가 각자 옳은 채로 갈린다.
 */
class VocabularyDistanceTest {

    private val mapper = ObjectMapper()
    private val distanceDir = Path.of("..", "profile", "distance").normalize()
    private val profilesDir = Path.of("..", "profile", "profiles").normalize()
    private val surveyDir = Path.of("..", "profile", "vendors").normalize()
    private val schemaPath = Path.of("..", "profile", "schema", "distance.schema.json")

    private fun read(path: Path): JsonNode = mapper.readTree(Files.readString(path))

    private fun documents(): List<Pair<String, JsonNode>> =
        Files.list(distanceDir).use { stream ->
            stream.filter { it.name.endsWith(".json") }.map { it.name to read(it) }.toList()
        }.sortedBy { it.first }

    private fun realModels(): List<Path> =
        Files.list(profilesDir).use { stream ->
            stream.filter { it.name.endsWith(".json") }
                .filter { read(it).path("vendor").asText() != REFERENCE_VENDOR }
                .toList()
        }.sortedBy { it.name }

    /** 계약이 아는 스킬 전부. **리터럴로 박지 않는다** — 박으면 이 시험이 낡는다. */
    private val catalog: Set<String> by lazy {
        val property = System.getProperty("picasso.descriptor")
            ?: error("picasso.descriptor 시스템 프로퍼티가 없다 — gate/build.gradle.kts 배선을 보라")
        val path = Path.of(property)
        check(Files.exists(path)) {
            "디스크립터가 없다: $property\n" +
                "먼저 만들어라:\n" +
                "  mkdir -p contracts/build && ( cd contracts && ../tools/buf build -o build/descriptor.binpb )"
        }
        ContractIndex.from(Files.readAllBytes(path)).skillTypes().toSet()
    }

    @Test
    fun `측정 문서가 스키마를 통과한다`() {
        val schema = JsonSchemaFactory
            .getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(Files.readString(schemaPath), CONFIG)

        val all = documents()
        // 하나뿐이면 "거리"가 비교 없는 판정이 된다. 두 실물이 있어야 서로가
        // 서로의 대조군이며, 실제로 그 대조에서 층 발견이 나왔다.
        assertTrue(all.size >= MINIMUM, "측정 문서가 $MINIMUM 장 미만이다: ${all.map { it.first }}")

        all.forEach { (name, node) ->
            val problems = schema.validate(node)
            assertTrue(problems.isEmpty(), "$name 이 스키마를 어긴다: ${problems.map { it.message }}")
        }
    }

    @Test
    fun `실물 기종은 반드시 재어져 있다`() {
        val measured = documents().mapNotNull { (_, n) -> n.path("profile").takeIf { !it.isMissingNode }?.asText() }
            .toSet()

        realModels().forEach { path ->
            assertTrue(
                path.name in measured,
                "${path.name} 은 실물인데 거리를 안 쟀다 — 어휘가 닿는지 아무도 모르는 기종이 생긴다",
            )
        }
    }

    @Test
    fun `가리키는 프로파일과 조사가 실재한다`() {
        documents().forEach { (name, node) ->
            val survey = surveyDir.resolve(node.path("survey").asText())
            assertTrue(Files.isRegularFile(survey), "$name 이 없는 조사를 가리킨다: $survey")

            val profile = node.path("profile")
            if (!profile.isMissingNode) {
                assertTrue(
                    Files.isRegularFile(profilesDir.resolve(profile.asText())),
                    "$name 이 없는 프로파일을 가리킨다: ${profile.asText()}",
                )
            }
        }
    }

    @Test
    fun `계약의 모든 스킬을 실물마다 잰다`() {
        // **이것이 이 파일의 핵심 단언이다.** 스킬을 하나 더하면 실물마다
        // 재야 하고, 안 재면 여기가 빨개진다. 재지 않은 어휘가 조용히
        // 늘어나는 것이 §15.7이 경고한 것의 진행 방식이다.
        assertTrue(catalog.isNotEmpty(), "계약 카탈로그가 비었다 — 디스크립터를 못 읽었다")

        documents().forEach { (name, node) ->
            val measured = node.path("skills").fieldNames().asSequence().toSet()
            assertEquals(
                catalog, measured,
                "$name 이 잰 스킬과 계약 카탈로그가 다르다 " +
                    "(안 잰 것: ${catalog - measured}, 계약에 없는 것: ${measured - catalog})",
            )
        }
    }

    @Test
    fun `닿는다고 적은 것과 선언한 것이 같다`() {
        // **양방향이다.** 한쪽만 보면 다른 쪽이 조용히 어긋난다 —
        // "닿는다고 적고 선언 안 함"과 "선언하고 안 닿는다고 적음"은 서로
        // 다른 사고이고 둘 다 나쁘다.
        documents().forEach { (name, node) ->
            val profileName = node.path("profile")
            if (profileName.isMissingNode) return@forEach

            val declared = read(profilesDir.resolve(profileName.asText()))
                .path("skills").map { it.path("skill_type").asText() }.toSet()

            val reachable = node.path("skills").fields().asSequence()
                .filter { it.value.path("reachable").asText() == "YES" }
                .map { it.key }.toSet()

            assertEquals(
                reachable, declared,
                "$name — 닿는다고 잰 것과 프로파일이 선언한 것이 다르다 " +
                    "(재고 선언 안 함: ${reachable - declared}, 선언하고 안 잼: ${declared - reachable})",
            )
        }
    }

    @Test
    fun `못 닿는다면 무엇이 없는지 적는다`() {
        // 판정만 남기면 다음 사람이 같은 조사를 처음부터 다시 한다.
        // 반대로 닿는데 빈 것이 있다고 적으면 그 목록이 무의미해진다.
        eachSkill { doc, skill, node ->
            val missing = node.path("missing").map { it.asText() }
            if (node.path("reachable").asText() == "YES") {
                assertEquals(emptyList(), missing, "$doc 의 $skill 은 닿는데 빠진 것이 있다고 적혀 있다")
            } else {
                assertTrue(missing.isNotEmpty(), "$doc 의 $skill 이 못 닿는데 무엇이 없는지 안 적혀 있다")
            }
        }
    }

    @Test
    fun `부재를 단정하려면 1차 근거가 있어야 한다`() {
        // **2026-09-08에 이 저장소가 크게 물린 자리를 필드로 막는다.**
        //
        // Digit 측정이 통째로 뒤집혔다 — `navigate_to` PARTIAL→YES,
        // `pick_place` NO→YES, 계층 COMMAND→MISSION. 원인이 하나였다:
        // 벤더 문서가 안 닿아 **제3자 래퍼 코드**를 읽었는데 그것이 API의
        // 부분집합이라, 거기 없는 것을 **없는 것으로 읽었다.**
        //
        // 근거 등급을 §15.65에 산문으로 적어 두고도 그 위에 `NO`를 얹은 것이
        // 실수였다. 등급이 낮으면 `NO`가 아니라 `UNKNOWN`이어야 한다 —
        // §7.2의 `Support` 3값이 말하는 그것이고, **조사 문서에서 지키던
        // 규율을 거리 문서에서 안 지켰다.**
        //
        // 이제 산문이 아니라 필드이고, 여기가 강제한다.
        eachSkill { doc, skill, node ->
            if (node.path("reachable").asText() != "NO") return@eachSkill
            val grade = grade(doc, node)
            assertTrue(
                grade in GROUNDED,
                "$doc 의 $skill 이 '$grade' 근거로 부재를 단정한다 — " +
                    "그 등급으로는 UNKNOWN 까지만 적을 수 있다",
            )
        }
    }

    @Test
    fun `근거 없는 측정이 없다`() {
        eachSkill { doc, skill, node ->
            val evidence = node.path("evidence").asText()
            assertTrue(
                evidence.trim().length >= MIN_EVIDENCE,
                "$doc 의 $skill 에 근거가 없다시피 하다: '$evidence'",
            )
        }
        documents().forEach { (name, node) ->
            val layer = node.path("layer").path("evidence").asText()
            assertTrue(layer.trim().length >= MIN_EVIDENCE, "$name 의 계층 판정에 근거가 없다: '$layer'")
        }
    }

    @Test
    fun `대조가 실제로 돈 횟수를 센다`() {
        // 위 단언들이 0번 돌면서 초록인 길을 막는다.
        var seen = 0
        eachSkill { _, _, _ -> seen += 1 }
        assertEquals(
            documents().size * catalog.size, seen,
            "측정 항목을 훑은 횟수가 모자란다",
        )
    }

    /** 항목이 덮어썼으면 그것, 아니면 문서의 등급. */
    private fun grade(doc: String, node: JsonNode): String {
        val own = node.path("evidence_grade")
        if (!own.isMissingNode) return own.asText()
        return documents().single { it.first == doc }.second.path("evidence_grade").asText()
    }

    private fun eachSkill(body: (doc: String, skill: String, node: JsonNode) -> Unit) {
        documents().forEach { (name, node) ->
            node.path("skills").fields().forEach { (skill, value) -> body(name, skill, value) }
        }
    }

    private companion object {
        /** §7.4의 참조 프로파일. 실물이 아니므로 잴 대상이 아니다. */
        const val REFERENCE_VENDOR = "picasso-ref"

        /** 하나면 비교 없는 판정이다. 둘이어야 서로가 대조군이다. */
        const val MINIMUM = 2

        const val MIN_EVIDENCE = 10

        /** 부재를 단정해도 되는 근거 등급. 나머지로는 `UNKNOWN` 까지만이다. */
        val GROUNDED = setOf("VENDOR_PRIMARY", "VENDOR_DOC")

        val CONFIG: SchemaValidatorsConfig =
            SchemaValidatorsConfig.builder().locale(Locale.KOREAN).build()
    }
}
