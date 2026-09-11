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
    private val distanceDir = Repo.path("profile/distance")
    private val profilesDir = Repo.path("profile/profiles")
    private val surveyDir = Repo.path("profile/vendors")
    private val schemaPath = Repo.path("profile/schema/distance.schema.json")

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
                "  ./gradlew :contracts:generateProto   (`:gate:test` 이 이미 여기 매달려 있다)"
        }
        ContractIndex.from(Files.readAllBytes(path)).skillTypes().toSet()
    }

    private val index: ContractIndex by lazy {
        ContractIndex.from(Files.readAllBytes(Path.of(System.getProperty("picasso.descriptor"))))
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
    @Test
    fun `닿는 스킬은 계약 파라미터를 빠짐없이 짚는다`() {
        // ★**산문은 빠진 것을 안 보여 준다.** `evidence` 가 *"파라미터 넷이 하나씩 대응한다"* 라고만
        // 적혀 있으면 넷을 세어 보지 않고도 통과한다. 칸으로 만들면 **안 짚은 파라미터가 빈칸으로 남고**,
        // 이 시험이 그 빈칸을 센다. 벤더 매니페스트 대조가 못 보던 한계 ④(*"다섯을 짚어야 할 자리에
        // 넷만 짚은 것은 통과한다"*)를 계약 쪽 수로 닫는 자리다.
        eachSkill { doc, skill, node ->
            if (node.path("reachable").asText() !in REACHED) return@eachSkill
            val expected = contractParameters(skill)
            val mapped = node.path("parameter_map").fieldNames().asSequence().toSortedSet()
            assertEquals(
                expected, mapped,
                "$doc 의 $skill — 대응표가 계약 파라미터와 다르다 " +
                    "(안 짚음: ${expected - mapped}, 계약에 없음: ${mapped - expected})",
            )
        }
    }

    @Test
    fun `YES 는 필수 파라미터를 안 본 채로 못 선다`() {
        // ★**`NOT_SURVEYED` 와 `UNMAPPED` 를 가른 값이 여기서 나온다.** 선택 파라미터를 안 본 것은
        // 남겨 둘 수 있는 빚이지만, **필수 파라미터를 안 보고 닿는다고 적는 것은 판정이 아니다.**
        // 실측(2026-09-11): 대응표를 처음 채우자 선택 넷이 조사된 적 없다는 것이 드러났고, 그 넷은
        // 전부 선택이라 이 시험을 안 건드린다 — 그 구분이 이 시험의 요점이다.
        val required = requiredParameters()
        eachSkill { doc, skill, node ->
            if (node.path("reachable").asText() != "YES") return@eachSkill
            val blind = node.path("parameter_map").fields().asSequence()
                .filter { it.key in required.getOrDefault(skill, emptySet()) }
                .filter { it.value.path("kind").asText() in UNJUDGED }
                .map { it.key }
                .toList()
            assertEquals(
                emptyList(), blind,
                "$doc 의 $skill 은 닿는다고 적혔는데 필수 파라미터를 안 봤거나 대응이 없다고 적었다",
            )
        }
    }

    @Test
    fun `짚은 벤더 심볼이 그 기종 매니페스트에 있다`() {
        // ★**환각과 오타와 벤더의 개명을 같은 자리에서 막는다.** 매니페스트는 벤더 원문에서 뽑은
        // 이름의 집합이고(이름과 sha256 만 들인다), 어댑터 코드의 `@VendorSurface` 는 이미 이 대조를
        // 받고 있었다. **거리 문서의 인용만 밖에 있었다** — 기계가 1 차로 훑어 초안을 만드는 길을
        // 열려면 그 문이 먼저 닫혀 있어야 한다.
        //
        // 벤더마다 매니페스트의 결이 다르다(Spot 은 필드까지, G1 은 API id 까지). 그래서 규칙은
        // *"있는 것 중 가장 좁은 심볼을 적고 인자 이름은 note 로 내린다"* 이고, 이 시험은 **적은 것이
        // 실재하는가**만 본다 — 그것이 가장 좁은 것인지는 사람이 본다.
        documents().forEach { (name, node) ->
            val adapter = node.path("adapter")
            if (adapter.isMissingNode) return@forEach
            val manifest = Repo.read(adapter.asText() + "/src/test/resources/vendor-manifest.txt")
                .lineSequence()
                .map(String::trim)
                .filterNot { it.isEmpty() || it.startsWith("#") }
                .toHashSet()
            val ghosts = node.path("skills").fields().asSequence().flatMap { (skill, cell) ->
                cell.path("parameter_map").fields().asSequence()
                    .filterNot { it.value.path("vendor").isMissingNode }
                    .map { skill + "." + it.key + " -> " + it.value.path("vendor").asText() }
                    .filterNot { it.substringAfter("-> ") in manifest }
            }.toList()
            assertEquals(emptyList(), ghosts, "$name — 매니페스트에 없는 벤더 심볼을 짚었다")
        }
    }

    @Test
    fun `기계 초안은 판정을 비운다`() {
        // ★★**AI 초안이 사슬의 뿌리로 조용히 승격되는 것을 막는 자리다.**
        //
        // 이 저장소의 기계 검사는 전부 거리 판정을 **참으로 놓고** 돈다 — 판정이 프로파일을 만들고,
        // 프로파일이 협상을 만들고, 협상이 초록을 만든다. 그러니 사람이 안 본 초안이 `YES` 를 들고
        // 들어오면 **그 아래 검증이 전부 무의미해진다.**
        //
        // 막는 방법이 등급 하나다. `MACHINE_DRAFT` 인 칸은 `UNKNOWN` 이어야 하고, `UNKNOWN` 은
        // `YES` 집합에 안 들어가므로 프로파일 선언과 묶이지 않는다(*"닿는다고 적은 것과 선언한 것이
        // 같다"*). 초안이 채우는 것은 `parameter_map` 의 후보와 인용과 조사 범위까지다.
        //
        // ★**판정을 못 채우게 하는 것 자체가 장치다.** 사람이 백지에서 시작하면 *"이게 되나"* 를 묻고
        // 초안을 받으면 *"이게 맞나"* 를 묻는데, 뒤엣것이 훨씬 잘 통과한다.
        eachSkill { doc, skill, node ->
            if (grade(doc, node) != MACHINE_DRAFT) return@eachSkill
            assertEquals(
                "UNKNOWN", node.path("reachable").asText(),
                "$doc 의 $skill 은 사람이 안 본 초안인데 판정이 들어 있다",
            )
        }
    }

    @Test
    fun `프로파일이 선언한 스킬을 그 기종 어댑터가 든다`() {
        // ★★**사슬이 여기서 끊겨 있었다.**
        //
        // ```
        // 거리 판정 ──(닿는다고 적은 것과 선언한 것이 같다)── 프로파일
        //          ──(Check04CrossRef: 파라미터가 카탈로그와 같은가)── 협상
        //          ──( 없음 )────────────────────────────────────── 어댑터
        // ```
        //
        // **미믹은 프로파일이 곧 거동이라 프로파일과 절대 안 어긋난다.** 그래서 `AllModelsTest` 의
        // *"모든 기종이 공통 태스크를 완주한다"* 가 초록인 것은 **어댑터에 대해 아무 말도 하지 않는다.**
        // 프로파일이 선언한 스킬을 그 기종 어댑터가 안 들면 협상은 통과하고 태스크는 실물에서
        // `UNSUPPORTED_SKILL` 로 죽는데, 그때까지 모든 검사가 초록이다.
        //
        // ★**어댑터마다 모양이 달랐던 것도 대조가 없었기 때문이다** — Spot·Digit 은 `when(skillType)`,
        // G1 은 `if (skillType != SKILL)`, Orbit 은 `if (skillType != NAVIGATE)`. 통일될 이유가 없었다.
        //
        // ★**문자열 검사다**(ADR 23 · 게이트 5·7 번과 같은 가족). 주석에 스킬 이름을 적어도 통과한다 —
        // **실수를 막는 장치이지 우회를 막는 장치가 아니다.**
        documents().forEach { (name, node) ->
            val adapter = node.path("adapter")
            val profile = node.path("profile")
            if (adapter.isMissingNode || profile.isMissingNode) return@forEach

            val declared = read(profilesDir.resolve(profile.asText()))
                .path("skills").map { it.path("skill_type").asText() }.toSortedSet()
            val known = skillLiterals(adapter.asText())

            assertEquals(
                declared, known,
                name + " — 프로파일이 선언한 스킬과 어댑터가 아는 스킬이 다르다 " +
                    "(어댑터가 모름: " + (declared - known) + ", 선언 안 됨: " + (known - declared) + ")",
            )
        }
    }

    /**
     * 그 어댑터의 출하 소스에 문자열 상수로 박힌 것 중 **계약 카탈로그의 스킬인 것**.
     *
     * 파라미터 키도 같은 모양의 상수라(`P_LOCATION = "location"`) 값만으로는 못 가른다. 그래서
     * **계약이 아는 스킬 이름과 교집합을 낸다** — 카탈로그가 가려 주므로 이 시험이 스킬 목록을
     * 손으로 안 든다.
     */
    private fun skillLiterals(adapter: String): Set<String> {
        val prefix = adapter + "/src/main/"
        val literal = Regex("""const val [A-Z_]+ = \"([a-z][a-z0-9_]*)\"""")
        return Repo.declaredFiles()
            .filter { ClaimSurface.relative(it).startsWith(prefix) }
            .filter { it.fileName.toString().endsWith(".kt") }
            .flatMap { literal.findAll(Repo.read(it)).map { m -> m.groupValues[1] } }
            .filter { it in catalog }
            .toSortedSet()
    }

    @Test
    fun `문서의 층은 스킬들이 타는 층 중 가장 높은 것과 같다`() {
        // ★**칸의 사실이 문서의 한 줄을 떠받친다.** `layer.vendor_layer` 는 문서마다 하나인데 실제로는
        // **스킬마다 다른 층에 탄다** — Spot 에서 `move_relative` 는 명령, `navigate_to` 는 미션,
        // `inspect` 는 취득이다. 한 줄이 그 여럿을 대표하면 어느 순간 대표가 낡는다.
        //
        // ★G1 문서가 그 애매함을 스스로 적어 뒀다 — *"이 값은 이제 **기종에 태스크 개념이 없다** 가 아니라
        // **우리가 쓰는 표면에 없다** 로 읽어야 한다."* 칸을 채우고 **문서 값을 칸들의 최댓값으로 묶는 것**이
        // 그 문장을 기계로 닫는 방법이다.
        //
        // ★**소비자는 발명이 아니다.** `SpotAdapter` 가 이미 `Layer { COMMAND, MISSION, ACQUISITION }` 를
        // 들고 태스크마다 층을 기억하며 `poll`·`cancel`·`update`·종착 판정이 거기서 갈린다 — 명령 계층은
        // **시계로** 종착을 적는다. 어댑터 안에 갇혀 있던 사실을 문서로 꺼낸 것이다.
        documents().forEach { (name, node) ->
            val scopes = node.path("skills").fields().asSequence()
                .map { it.value.path("execution_scope") }
                .filterNot { it.isMissingNode }
                .map { it.asText() }
                .toList()
            if (scopes.isEmpty()) return@forEach
            val top = scopes.maxBy { SCOPE_ORDER.indexOf(it) }
            assertEquals(
                top, node.path("layer").path("vendor_layer").asText(),
                name + " — 문서의 층과 칸들의 최댓값이 다르다 (칸: " + scopes.distinct().sorted() + ")",
            )
        }
    }

    @Test
    fun `못 닿는 스킬은 층을 안 든다`() {
        // 스키마가 *"닿으면 층이 있어야 한다"* 를 막고, 이 시험이 그 역을 막는다. 못 닿는데 층이 적혀
        // 있으면 위 최댓값이 **재지도 않은 스킬의 층으로** 올라가고, 그러면 문서의 한 줄이 거짓이 된다.
        eachSkill { doc, skill, node ->
            if (node.path("reachable").asText() in REACHED) return@eachSkill
            assertTrue(
                node.path("execution_scope").isMissingNode,
                doc + " 의 " + skill + " 은 못 닿는다고 적혔는데 층이 들어 있다",
            )
        }
    }

    /** 계약이 그 스킬에 정의한 파라미터 전부. **나중 minor 에 붙은 선택 파라미터까지** 센다 —
     *  안 세면 그것이 곧 안 본 칸이 되고, 그 빈칸이 보이지 않는 것이 이 대응표가 막으려는 것이다. */
    private fun contractParameters(skill: String): Set<String> =
        index.all().filter { it.name == skill }
            .flatMap { it.parameters }
            .map { it.key }
            .toSortedSet()

    /** 스킬마다 **필수** 파라미터. 선택은 안 본 채로 둘 수 있지만 이쪽은 아니다. */
    private fun requiredParameters(): Map<String, Set<String>> =
        index.all().groupBy { it.name }.mapValues { (_, defs) ->
            defs.flatMap { it.parameters }.filterNot { it.isOptional }.map { it.key }.toSet()
        }

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
        /** 낮은 것부터. `NONE` = 태스크 개념이 없다 · `COMMAND` = 명령 계층에 일부 생명주기 ·
         *  `MISSION` = 생명주기를 가진 층이 따로 있다. 문서의 층은 이 중 **가장 높은 것**이다. */
        val SCOPE_ORDER = listOf("NONE", "COMMAND", "MISSION")

        const val MACHINE_DRAFT = "MACHINE_DRAFT"

        /** 대응표를 요구하는 판정들. 못 닿는 칸은 짚을 것이 없다. */
        val REACHED = setOf("YES", "PARTIAL")

        /** *"대응을 아직 안 정했다"* 에 해당하는 칸. 필수 파라미터에는 못 온다. */
        val UNJUDGED = setOf("NOT_SURVEYED", "UNMAPPED")

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
