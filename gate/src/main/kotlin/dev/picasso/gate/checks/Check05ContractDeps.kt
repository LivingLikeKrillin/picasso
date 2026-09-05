package dev.picasso.gate.checks

import dev.picasso.gate.CheckResult
import dev.picasso.gate.Finding
import dev.picasso.gate.GateCheck
import dev.picasso.gate.Resource
import dev.picasso.gate.Severity
import dev.picasso.gate.input.GateInput
import java.nio.file.Files
import java.nio.file.Path

/**
 * 검사 5 — `contracts`는 프로젝트 내 의존이 0이다(설계 §3.2).
 *
 * **Gradle을 부르지 않고 빌드 스크립트의 텍스트를 본다.** 게이트가 Gradle을
 * 부르면 gate → Gradle → gate가 되고 시험이 몇 초에서 몇 분이 된다.
 *
 * 그래서 한계가 셋 있다(§15).
 *  - 관례 플러그인이나 별도 스크립트가 주입하는 의존은 **못 잡는다**.
 *  - 블록 주석 안의 `project(` 는 **거짓 실패**다. 줄 주석만 걷어낸다.
 *  - 루트가 자기 자신을 위해 갖는 프로젝트 의존도, `subprojects` 블록이
 *    파일에 있으면 **거짓 실패**다. 블록의 경계를 파싱하지 않기 때문이다.
 *
 * 잡지 못하는 것을 잡는 척하지 않는 것이 요점이다.
 */
class Check05ContractDeps : GateCheck {

    override val id = "5"
    override val name = "contracts 의존 0"
    override val requires = setOf(Resource.REPO)

    override fun run(input: GateInput): CheckResult {
        val root = input.repoRoot!!
        val findings = mutableListOf<Finding>()

        val contractsBuild = root.resolve(CONTRACTS_BUILD)
        if (!Files.isRegularFile(contractsBuild)) {
            // 파일이 사라졌는데 통과하면 검사가 조용히 무력해진 것이다.
            return CheckResult.Failed(
                id,
                listOf(
                    Finding(
                        id, Severity.ERROR,
                        "$CONTRACTS_BUILD 를 찾을 수 없다 — 검사할 대상이 없으면 통과가 아니다",
                        CONTRACTS_BUILD,
                    ),
                ),
            )
        }

        findings += scan(contractsBuild, CONTRACTS_BUILD) { dep ->
            "contracts가 프로젝트 의존을 갖는다: $dep " +
                "— 설계 §3.2에 따라 contracts의 프로젝트 내 의존은 0이어야 한다"
        }

        // 루트가 subprojects/allprojects로 모든 모듈에 의존을 걸 수 있다.
        // contracts/build.gradle.kts만 보면 이 경로로 §3.2가 조용히 깨진다.
        val rootBuild = root.resolve(ROOT_BUILD)
        if (Files.isRegularFile(rootBuild) &&
            SHARED_BLOCK.containsMatchIn(Files.readString(rootBuild))
        ) {
            findings += scan(rootBuild, ROOT_BUILD) { dep ->
                "루트가 모든 모듈에 프로젝트 의존을 줄 수 있다: $dep " +
                    "— contracts에도 걸리므로 설계 §3.2 위반이다"
            }
        }

        return if (findings.isEmpty()) CheckResult.Passed(id)
        else CheckResult.Failed(id, findings)
    }

    private fun scan(file: Path, label: String, message: (String) -> String): List<Finding> =
        codeLines(Files.readString(file)).mapNotNull { (lineNo, line) ->
            PROJECT_DEP.find(line)?.let { m ->
                // 대안 둘 중 매치된 쪽을 고른다.
                val dep = m.groupValues.drop(1).first { it.isNotEmpty() }
                Finding(id, Severity.ERROR, message(dep), "$label:$lineNo")
            }
        }

    /**
     * 줄 주석을 뺀 코드 줄만 (줄번호, 내용)으로 돌려준다.
     * 블록 주석은 걷어내지 않는다 — 거짓 실패가 나며, 그 한계는 위 KDoc에 있다.
     */
    private fun codeLines(text: String): List<Pair<Int, String>> =
        text.lineSequence()
            .mapIndexed { i, line -> (i + 1) to line.substringBefore("//") }
            .filter { it.second.isNotBlank() }
            .toList()

    private companion object {
        const val CONTRACTS_BUILD = "contracts/build.gradle.kts"
        const val ROOT_BUILD = "build.gradle.kts"

        /**
         * `project(":gate")`, `project(path = ":gate")`, `projects.gate` 셋을 잡는다.
         * 셋째를 빠뜨리면 타입세이프 프로젝트 접근자로 그냥 우회된다 —
         * `settings.gradle.kts`에 `enableFeaturePreview` 한 줄이면 켜진다.
         */
        val PROJECT_DEP = Regex(
            """project\s*\(\s*(?:path\s*=\s*)?["'](:[^"']*)["']""" +
                """|\bprojects\.([A-Za-z][A-Za-z0-9_.]*)""",
        )

        val SHARED_BLOCK = Regex("""\b(subprojects|allprojects)\s*\{""")
    }
}
