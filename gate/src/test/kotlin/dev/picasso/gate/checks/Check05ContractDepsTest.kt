package dev.picasso.gate.checks

import dev.picasso.gate.CheckResult
import dev.picasso.gate.Resource
import dev.picasso.gate.input.GateInput
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Check05ContractDepsTest {

    private fun repo(contractsBuild: String, rootBuild: String = "plugins { }\n"): Path {
        val root = Files.createTempDirectory("picasso-check05")
        root.resolve("build.gradle.kts").writeText(rootBuild)
        root.resolve("contracts").createDirectories()
        root.resolve("contracts/build.gradle.kts").writeText(contractsBuild)
        return root
    }

    private fun run(root: Path) = Check05ContractDeps().run(GateInput(repoRoot = root))

    @Test
    fun `저장소를 못 읽으면 돌지 않는다`() {
        assertEquals(setOf(Resource.REPO), Check05ContractDeps().requires)
    }

    @Test
    fun `실제 저장소에 대해 통과한다`() {
        // 임시 디렉터리만 시험하면 이 검사가 우리 저장소를 실패시키는지조차
        // 모른다. 루트에 subprojects { } 블록이 있으므로 거짓 실패가 나기 쉽다.
        val real = Path.of("..").normalize().toAbsolutePath()
        val r = Check05ContractDeps().run(GateInput(repoRoot = real))
        assertTrue(r is CheckResult.Passed, "실제 저장소를 실패시켰다: $r")
    }

    @Test
    fun `의존이 없으면 통과한다`() {
        assertTrue(run(repo("// 코드가 없다. 디스크립터는 buf가 만든다.\n")) is CheckResult.Passed)
    }

    @Test
    fun `contracts가 프로젝트 의존을 가지면 실패한다`() {
        val r = run(repo("dependencies {\n    implementation(project(\":gate\"))\n}\n"))
        r as CheckResult.Failed
        assertTrue(r.findings.single().message.contains("gate"))
        assertTrue(r.findings.single().location!!.contains("contracts/build.gradle.kts"))
    }

    @Test
    fun `add 형태의 의존도 잡는다`() {
        val r = run(repo("dependencies {\n    add(\"implementation\", project(\":gate\"))\n}\n"))
        assertTrue(r is CheckResult.Failed)
    }

    @Test
    fun `이름 인자 형태의 의존도 잡는다`() {
        val r = run(repo("dependencies {\n    implementation(project(path = \":gate\"))\n}\n"))
        assertTrue(r is CheckResult.Failed, "project(path = ...) 로 우회됐다")
    }

    @Test
    fun `타입세이프 접근자도 잡는다`() {
        // settings.gradle.kts에 enableFeaturePreview 한 줄이면 켜지는, 코틀린
        // DSL 저장소에서 흔한 형태다. project( 만 찾으면 그대로 우회된다.
        val r = run(repo("dependencies {\n    implementation(projects.gate)\n}\n"))
        r as CheckResult.Failed
        assertTrue(r.findings.single().message.contains("gate"))
    }

    @Test
    fun `루트가 모든 모듈에 프로젝트 의존을 주면 실패한다`() {
        // contracts/build.gradle.kts만 보면 이 경로로 §3.2가 조용히 깨진다.
        val r = run(
            repo(
                "// 비었다\n",
                rootBuild = "subprojects {\n    dependencies { add(\"implementation\", project(\":gate\")) }\n}\n",
            ),
        )
        r as CheckResult.Failed
        assertTrue(r.findings.single().location!!.contains("build.gradle.kts"))
    }

    @Test
    fun `줄 주석 안의 project 는 무시한다`() {
        // 이 파일들은 사람이 읽는 문서이기도 하다. 주석에서 규칙을 설명하는
        // 것이 CI를 막으면 주석을 안 쓰게 된다. 실제로 contracts/build.gradle.kts에
        // 그런 주석이 있다.
        assertTrue(run(repo("// project(\":gate\") 를 넣으면 검사 5번이 막는다\n")) is CheckResult.Passed)
    }

    @Test
    fun `contracts 빌드 파일이 없으면 실패한다`() {
        // 파일이 사라졌는데 통과하면 검사가 무력해진 것이다.
        val root = Files.createTempDirectory("picasso-check05-empty")
        root.resolve("build.gradle.kts").writeText("plugins { }\n")
        assertTrue(Check05ContractDeps().run(GateInput(repoRoot = root)) is CheckResult.Failed)
    }
}
