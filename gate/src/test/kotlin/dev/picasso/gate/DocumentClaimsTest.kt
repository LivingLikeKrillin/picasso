package dev.picasso.gate

import dev.picasso.gate.checks.Check07ModelBranching
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **세어서 적은 것을 다시 센다.**
 *
 * 이 저장소가 반복해 물린 자리다 — 모듈이 늘고 검사가 늘고 어댑터가 느는데 산문의 숫자는 그대로였다. 사람이
 * 훑어 고치면 다음 달에 다시 어긋나므로, **숫자를 코드에서 세어 문서와 댄다.**
 *
 * ## 왜 게이트에 있나
 *
 * 게이트가 이미 저장소의 파일을 읽어 규칙을 집행하는 자리이고(검사 4·5·7·8), 이 검사도 같은 종류다. **다만
 * 게이트 검사로 만들지는 않았다** — 게이트는 PR 을 막는 것이고 산문의 숫자 하나로 남의 PR 을 막으면 그 검사는
 * 곧 꺼진다. 여기서는 시험으로 둔다.
 *
 * ## 텍스트만 읽는다
 *
 * `gate` 는 `contracts` 에도 `registry` 에도 의존하지 않는다(§3.2). 그래서 계약 개정판도 진단 개수도 **소스를
 * 문자열로 읽어** 센다 — 의존을 만들어 이 시험을 편하게 하면 그것이 §3.2 를 어기는 첫 예외가 된다.
 */
class DocumentClaimsTest {

    private val repo: Path = Path.of("..").normalize()

    private fun read(relative: String): String =
        Files.readString(repo.resolve(relative)).replace("\r\n", "\n")

    private val readme by lazy { read("README.md") }
    private val design by lazy { read("docs/superpowers/specs/2026-09-05-picasso-design.md") }

    /** `settings.gradle.kts` 의 `include(...)` 에 적힌 이름 전부. */
    private fun modules(): List<String> =
        Regex("\"([a-z0-9-]+)\"").findAll(read("settings.gradle.kts").substringAfter("rootProject.name"))
            .map { it.groupValues[1] }
            .filter { it != "picasso" || true }
            .toList()

    private fun claimed(pattern: String, where: String = readme): Int {
        val match = Regex(pattern).find(where) ?: error("문서에서 이 진술을 못 찾았다: $pattern")
        // 산문의 낱말에는 문장부호가 붙는다 — 그것까지 세지 않는다.
        val word = match.groupValues[1].trim('.', ',', ')', '(', '·', ':', '*')
        return word.toIntOrNull() ?: NUMERALS[word] ?: error("셀 수 없는 낱말이다: '$word'")
    }

    @Test
    fun `README 의 모듈 나무가 빌드의 모듈과 같다`() {
        // 나무에 없는 모듈은 **읽는 사람에게 없는 모듈**이고, 나무에만 있는 모듈은 사라진 것을 가리킨다.
        val tree = readme.substringAfter("```").substringBefore("```")
        val declared = modules().toSet()
        val missing = declared.filterNot { "$it/" in tree }
        assertEquals(emptyList(), missing, "빌드에는 있는데 README 나무에 없다")

        val listed = Regex("^([a-z0-9-]+)/", RegexOption.MULTILINE).findAll(tree).map { it.groupValues[1] }.toSet()
        assertEquals(emptyList(), (listed - declared - IGNORED_DIRS).toList(), "README 나무에만 있고 빌드에 없다")
    }

    @Test
    fun `게이트 검사의 수를 README 가 맞게 적는다`() {
        assertEquals(GateChecks.all().size, claimed("""검사 (\S+)이 있고"""), "검사가 늘었는데 README 가 그대로다")
    }

    @Test
    fun `실물 어댑터의 수를 README 가 맞게 적는다`() {
        // `adapter-core`·`adapter-host` 는 기종을 모르는 공용 모듈이라 세지 않는다(ADR 33·39).
        val adapters = modules().count { it.startsWith("adapter-") && it !in setOf("adapter-core", "adapter-host") }
        assertEquals(adapters, claimed("""실물 어댑터가 (\S+) 있다"""), "어댑터가 늘었는데 README 가 그대로다")
        assertEquals(adapters, claimed("""어댑터 (\S+) 중 어느 것도 실물에 붙여 보지 못했다"""))
    }

    @Test
    fun `거리 문서의 수를 README 가 맞게 적는다`() {
        // 거리 문서는 **잰 벤더 표면마다 하나**다. 어댑터가 늘면 여기도 는다.
        val measured = Files.list(repo.resolve("profile/distance")).use { s ->
            s.filter { it.fileName.toString().endsWith(".json") }.count().toInt()
        }
        assertEquals(measured, claimed("""실물 (\S+)이 계약에 얼마나 닿나"""), "거리 문서가 늘었는데 README 가 그대로다")
    }

    @Test
    fun `계약 개정판을 README 가 맞게 적는다`() {
        val semver = Regex("""val contractSemver = "([0-9.]+)"""").find(read("contracts/build.gradle.kts"))
            ?.groupValues?.get(1) ?: error("contracts 가 개정판을 안 적는다")
        assertTrue("계약 개정판은 **$semver**" in readme, "README 의 계약 개정판이 $semver 가 아니다")
    }

    @Test
    fun `진단 표면의 수를 README 가 맞게 적는다`() {
        val diagnostics = Regex("""@GetMapping\("/diag/""")
            .findAll(read("registry/src/main/kotlin/dev/picasso/registry/web/DiagController.kt")).count()
        assertEquals(diagnostics, claimed("""진단 표면 (\S+)"""), "진단이 늘었는데 README 가 그대로다")
    }

    @Test
    fun `게이트 7번이 보는 모듈 목록을 설계 문서가 그대로 적는다`() {
        // **목록이 두 곳에 있다.** 코드가 정본이고 문서는 그것을 옮긴 것이므로, 어긋나면 문서가 틀린 것이다.
        val listed = Check07ModelBranching.MODULES.joinToString(" · ") { "`$it`" }
        assertTrue(listed in design, "설계 §11.2 의 7번 목록이 코드와 다르다 — 코드: $listed")
    }

    private companion object {
        /** 이 저장소의 산문은 작은 수를 낱말로 쓴다. 셈은 여기서 한 번만 한다. */
        val NUMERALS = mapOf(
            "하나" to 1, "둘" to 2, "셋" to 3, "넷" to 4, "다섯" to 5, "여섯" to 6, "일곱" to 7,
            "여덟" to 8, "아홉" to 9, "열" to 10, "열하나" to 11, "열둘" to 12, "열셋" to 13,
            "열넷" to 14, "열다섯" to 15, "열여섯" to 16, "열일곱" to 17, "열여덟" to 18,
            "열아홉" to 19, "스물" to 20,
        )

        /** 모듈이 아니지만 나무에 있는 것들 — 도구와 문서. */
        val IGNORED_DIRS = setOf("tools", "docs", "profile", "ci")
    }
}
