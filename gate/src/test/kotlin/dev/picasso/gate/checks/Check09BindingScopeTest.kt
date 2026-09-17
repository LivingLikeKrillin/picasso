package dev.picasso.gate.checks

import dev.picasso.gate.CheckResult
import dev.picasso.gate.input.GateInput
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Check09BindingScopeTest {

    private val check = Check09BindingScope()

    private fun repo(): Path = Files.createTempDirectory("picasso-check09")

    /**
     * 검사가 보는 모듈들의 `src/main` 과 탐색어의 출처를 만든다.
     *
     * **목록을 여기 다시 적지 않는다** — 검사의 것을 읽는다. 두 벌로 두면 모듈이 느는 날 한쪽만
     * 고쳐지고, 그때 이 시험은 "검사가 깨졌다" 가 아니라 "시험이 낡았다" 인데 증상이 같다.
     */
    private fun scaffold(
        root: Path,
        declaration: String = DEFAULT_DECLARATION,
        sources: Map<String, String> = emptyMap(),
    ): Path {
        Check09BindingScope.MODULES.forEach { module ->
            root.resolve("$module/${Check09BindingScope.MAIN}/kotlin").createDirectories()
                .resolve("Placeholder.kt").writeText("package x\n")
        }
        val file = root.resolve(Check09BindingScope.DECLARATION)
        file.parent.createDirectories()
        file.writeText(declaration)
        sources.forEach { (relative, text) ->
            val f = root.resolve(relative)
            f.parent.createDirectories()
            f.writeText(text)
        }
        return root
    }

    private fun run(root: Path) = check.run(GateInput(repoRoot = root))

    // ── 기본

    @Test
    fun `공통 계층이 결속을 모르면 통과한다`() {
        assertTrue(run(scaffold(repo())) is CheckResult.Passed)
    }

    @Test
    fun `공통 계층이 결속 타입을 참조하면 실패한다`() {
        val root = scaffold(
            repo(),
            sources = mapOf("picasso/src/main/kotlin/Bad.kt" to "val s: SiteBindingSource? = null\n"),
        )
        val result = run(root)
        result as CheckResult.Failed
        assertTrue("SiteBindingSource" in result.findings.single().message, result.findings.toString())
        assertTrue(result.findings.single().location!!.endsWith("Bad.kt:1"), result.findings.toString())
    }

    @Test
    fun `레지스트리도 본다`() {
        // 검사 7 의 목록에 `registry` 가 없다. 그 이유로 결속까지 새면 같은 구멍이 두 번째로 열린다.
        val root = scaffold(
            repo(),
            sources = mapOf("registry/src/main/kotlin/Bad.kt" to "fun f(b: SiteBinding) = b.name\n"),
        )
        assertTrue(run(root) is CheckResult.Failed)
    }

    @Test
    fun `탐색어를 선언 파일에서 얻는다`() {
        // **손으로 적지 않는다.** 결속 파일에 타입을 더하면 금지도 저절로 는다.
        val root = scaffold(
            repo(),
            declaration = DEFAULT_DECLARATION + "\ndata class MapDatum(val id: String)\n",
            sources = mapOf("client/src/main/kotlin/Bad.kt" to "val d: MapDatum? = null\n"),
        )
        val result = run(root)
        result as CheckResult.Failed
        assertTrue("MapDatum" in result.findings.single().message, result.findings.toString())
    }

    @Test
    fun `낱말 경계로 본다`() {
        // `SiteBindingSomethingElse` 가 `SiteBinding` 으로 걸리면 검사가 거짓 실패를 내고 곧 꺼진다.
        val root = scaffold(
            repo(),
            sources = mapOf("mimic/src/main/kotlin/Fine.kt" to "val x = \"SiteBindingsAreNotThis\"\n"),
        )
        assertTrue(run(root) is CheckResult.Passed, run(root).toString())
    }

    @Test
    fun `중첩 타입은 탐색어가 아니다`() {
        // 들여쓴 선언은 밖에서 그 이름만으로 못 부른다. 탐색어로 쓰면 흔한 낱말이 거짓 실패를 낸다.
        val root = scaffold(
            repo(),
            declaration = DEFAULT_DECLARATION + "\nsealed interface Outer {\n    data class Known(val v: String) : Outer\n}\n",
            sources = mapOf("uplink/src/main/kotlin/Fine.kt" to "val k = \"Known\"\n"),
        )
        assertTrue(run(root) is CheckResult.Passed, run(root).toString())
    }

    // ── 검사가 조용히 무력해지지 않는다

    @Test
    fun `선언 파일이 없으면 통과가 아니다`() {
        val root = repo()
        Check09BindingScope.MODULES.forEach {
            root.resolve("$it/${Check09BindingScope.MAIN}/kotlin").createDirectories()
                .resolve("Placeholder.kt").writeText("package x\n")
        }
        val result = run(root)
        result as CheckResult.Failed
        assertTrue("찾을 수 없다" in result.findings.single().message, result.findings.toString())
    }

    @Test
    fun `선언이 비면 통과가 아니다`() {
        // 잡을 것이 없는 것과 안 본 것이 같은 색이면 검사는 이미 죽어 있다.
        val root = scaffold(repo(), declaration = "package dev.picasso.adapter.core\n\n// 아직 없다\n")
        val result = run(root)
        result as CheckResult.Failed
        assertTrue("하나도 선언하지 않는다" in result.findings.single().message, result.findings.toString())
    }

    @Test
    fun `모듈이 사라지면 통과가 아니다`() {
        val root = scaffold(repo())
        root.resolve("picasso/${Check09BindingScope.MAIN}/kotlin/Placeholder.kt").let(Files::delete)
        Files.delete(root.resolve("picasso/${Check09BindingScope.MAIN}/kotlin"))
        Files.delete(root.resolve("picasso/${Check09BindingScope.MAIN}"))
        val result = run(root)
        result as CheckResult.Failed
        assertTrue("찾을 수 없다" in result.findings.single().message, result.findings.toString())
    }

    @Test
    fun `검사 번호와 이름이 대장과 같다`() {
        assertEquals("9", check.id)
    }

    private companion object {
        val DEFAULT_DECLARATION = """
            package dev.picasso.adapter.core

            data class SiteBinding(val name: String, val owner: String, val mapVersion: String)

            sealed interface ActiveRevision

            interface SiteBindingSource

            object SiteBindingCheck
        """.trimIndent() + "\n"
    }
}
