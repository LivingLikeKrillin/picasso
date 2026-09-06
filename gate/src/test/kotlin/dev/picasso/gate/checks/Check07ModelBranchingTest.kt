package dev.picasso.gate.checks

import dev.picasso.gate.CheckResult
import dev.picasso.gate.Resource
import dev.picasso.gate.input.GateInput
import dev.picasso.profile.ProfileDocument
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Check07ModelBranchingTest {

    private val check = Check07ModelBranching()

    private fun repo(): Path = Files.createTempDirectory("picasso-check07")

    /** 세 모듈의 `src/main`을 만든다. 없으면 검사가 실패하는 것이 정상이다. */
    private fun scaffold(root: Path, sources: Map<String, String> = emptyMap()): Path {
        listOf("client", "mimic", "harness").forEach { module ->
            val dir = root.resolve("$module/src/main/kotlin").createDirectories()
            dir.resolve("Placeholder.kt").writeText("package x\n")
        }
        sources.forEach { (relative, text) ->
            val file = root.resolve(relative)
            file.parent.createDirectories()
            file.writeText(text)
        }
        return root
    }

    /** **경로가 `profile/profiles/` 아래여야 탐색어가 된다.** */
    private fun profile(
        vendor: String,
        model: String,
        dir: String = "profile/profiles",
    ): ProfileDocument =
        ProfileDocument.parse(
            "$dir/$vendor-$model.json",
            """{"schema_version":"1.0.0","vendor":"$vendor","model":"$model","revision":1}""",
        ).getOrThrow()

    private fun run(root: Path, vararg profiles: ProfileDocument) =
        check.run(GateInput(repoRoot = root, profiles = profiles.toList()))

    // ── 기본

    @Test
    fun `기종 좌표가 없으면 통과한다`() {
        val root = scaffold(repo())
        assertTrue(run(root, profile("acme-corp", "humanoid-a")) is CheckResult.Passed)
    }

    @Test
    fun `출하 소스에 model 값이 있으면 실패한다`() {
        val root = scaffold(
            repo(),
            mapOf("mimic/src/main/kotlin/Bad.kt" to "val gait = if (id == \"humanoid-a\") 1 else 2\n"),
        )
        val result = run(root, profile("acme-corp", "humanoid-a"))
        result as CheckResult.Failed
        assertTrue("humanoid-a" in result.findings.single().message)
        assertTrue(result.findings.single().location!!.endsWith("Bad.kt:1"))
    }

    @Test
    fun `vendor 값도 찾는다`() {
        // model만 찾으면 vendor로 분기하는 코드가 지나간다.
        val root = scaffold(
            repo(),
            mapOf("client/src/main/kotlin/Bad.kt" to "const val V = \"acme-corp\"\n"),
        )
        assertTrue(run(root, profile("acme-corp", "humanoid-a")) is CheckResult.Failed)
    }

    @Test
    fun `세 모듈을 전부 본다`() {
        // 하나만 보면 나머지 둘이 침묵한다.
        listOf("client", "mimic", "harness").forEach { module ->
            val root = scaffold(
                repo(),
                mapOf("$module/src/main/kotlin/Bad.kt" to "\"humanoid-a\"\n"),
            )
            assertTrue(
                run(root, profile("acme-corp", "humanoid-a")) is CheckResult.Failed,
                "$module 을 안 봤다",
            )
        }
    }

    @Test
    fun `시험 소스는 보지 않는다`() {
        // A1Test가 두 기종을 이름으로 집는 것은 필요하고 정당하다.
        val root = scaffold(
            repo(),
            mapOf("harness/src/test/kotlin/A1Test.kt" to "\"humanoid-a\"\n"),
        )
        assertTrue(run(root, profile("acme-corp", "humanoid-a")) is CheckResult.Passed)
    }

    @Test
    fun `주석 안이어도 실패한다`() {
        // 부분적으로 걷어내면 "http://humanoid-a" 같은 문자열이 // 이후로
        // 잘려 위반이 숨는다. 이 세 모듈은 기종 이름을 알 이유가 없다.
        val root = scaffold(
            repo(),
            mapOf("mimic/src/main/kotlin/Bad.kt" to "// humanoid-a 는 그리퍼가 있다\n"),
        )
        assertTrue(run(root, profile("acme-corp", "humanoid-a")) is CheckResult.Failed)
    }

    // ── 파생

    @Test
    fun `찾는 문자열을 프로파일에서 파생한다`() {
        // 리터럴로 박으면 세 번째 기종이 들어올 때 검사가 그것을 모른다 —
        // 완료 기준 11이 겨냥하는 자리다.
        val root = scaffold(
            repo(),
            mapOf("mimic/src/main/kotlin/Bad.kt" to "\"quadruped-c\"\n"),
        )
        assertTrue(
            run(root, profile("acme-corp", "humanoid-a")) is CheckResult.Passed,
            "모르는 기종을 찾았다",
        )
        assertTrue(
            run(root, profile("acme-corp", "humanoid-a"), profile("acme-corp", "quadruped-c"))
                is CheckResult.Failed,
            "프로파일에 있는 기종을 못 찾았다",
        )
    }

    @Test
    fun `여러 위반을 전부 보고한다`() {
        val root = scaffold(
            repo(),
            mapOf(
                "mimic/src/main/kotlin/A.kt" to "\"humanoid-a\"\n",
                "client/src/main/kotlin/B.kt" to "\"quadruped-b\"\n",
            ),
        )
        val result = run(root, profile("acme-corp", "humanoid-a"), profile("acme-corp", "quadruped-b"))
        result as CheckResult.Failed
        assertEquals(2, result.findings.size, "${result.findings.map { it.location }}")
    }

    // ── 무력화 방지

    @Test
    fun `문서가 있어도 좌표가 비면 실패한다`() {
        // 통과시키면 아무것도 안 찾는다.
        val root = scaffold(repo())
        val empty = ProfileDocument.parse(
            "profile/profiles/x.json", """{"schema_version":"1.0.0"}""",
        ).getOrThrow()
        assertTrue(check.run(GateInput(repoRoot = root, profiles = listOf(empty)))
            is CheckResult.Failed)
    }

    @Test
    fun `모듈이 사라지면 실패한다`() {
        // 검사할 대상이 없으면 통과가 아니다.
        val root = scaffold(repo())
        Files.walk(root.resolve("harness")).use { s ->
            s.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
        assertTrue(run(root, profile("acme-corp", "humanoid-a")) is CheckResult.Failed)
    }

    @Test
    fun `요구 자원을 선언한다`() {
        // 문서 없이 도는 순간 아무것도 안 찾는 통과가 된다.
        assertEquals(setOf(Resource.REPO, Resource.PROFILE_DOCUMENT), check.requires)
    }

    // ── 실제 트리

    @Test
    fun `픽스처의 좌표는 찾지 않는다`() {
        // 픽스처는 `fixture`·`minimal` 같은 흔한 낱말을 쓴다. 그것까지 찾으면
        // 출하 소스의 `minimalHeaders` 같은 이름 하나가 기종 분기와 무관한
        // 이유로 게이트를 빨갛게 만들고, 그러면 사람이 검사를 끈다.
        val root = scaffold(
            repo(),
            mapOf("mimic/src/main/kotlin/Ok.kt" to "val minimalHeaders = 1\n"),
        )
        assertTrue(
            run(root, profile("acme-corp", "fx-minimal", dir = "profile/fixtures"),
                profile("acme-corp", "humanoid-a")) is CheckResult.Passed,
            "픽스처 좌표를 찾았다",
        )
    }

    @Test
    fun `약한 좌표는 조용히 쓰지 않고 실패한다`() {
        // 짧거나 구분 기호가 없는 좌표는 평범한 식별자와 겹친다.
        // 조용히 쓰면 거짓 실패가 나고, 조용히 버리면 그 기종이 검사 밖이다.
        val root = scaffold(repo())
        listOf("acme" to "small", "ab" to "humanoid-a").forEach { (vendor, model) ->
            val result = run(root, profile(vendor, model))
            assertTrue(result is CheckResult.Failed, "'$vendor'/'$model' 를 조용히 썼다")
            assertTrue("약하다" in result.findings.single().message)
        }
    }

    @Test
    fun `소스가 아닌 텍스트도 본다`() {
        // 기종→거동 표를 resources의 yaml에 두는 것도 분기다.
        val root = scaffold(
            repo(),
            mapOf("mimic/src/main/resources/gait.yaml" to "humanoid-a: biped\n"),
        )
        assertTrue(run(root, profile("acme-corp", "humanoid-a")) is CheckResult.Failed)
    }

    @Test
    fun `현재 저장소가 통과한다`() {
        // 전제다. 지금 소스가 이미 위반이면 검사를 넣는 순간 CI가 빨개지고,
        // 그것을 피하려고 범위를 좁히다 보면 검사가 아무것도 안 잡게 된다.
        val root = Path.of("..").toAbsolutePath().normalize()
        val documents = listOf("humanoid-a", "quadruped-b").map { name ->
            val path = root.resolve("profile/profiles/$name.json")
            ProfileDocument.parse(
                path.toString(),
                Files.readString(path).replace("\r\n", "\n"),
            ).getOrThrow()
        }
        val result = check.run(GateInput(repoRoot = root, profiles = documents))
        assertTrue(
            result is CheckResult.Passed,
            "현재 트리가 검사 7을 통과하지 못한다:\n" +
                (result as? CheckResult.Failed)?.findings?.joinToString("\n") {
                    "  ${it.location}: ${it.message}"
                },
        )
    }
}
