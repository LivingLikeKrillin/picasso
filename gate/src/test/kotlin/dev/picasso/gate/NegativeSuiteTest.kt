package dev.picasso.gate

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.gate.buf.ProcessBufRunner
import dev.picasso.gate.cli.InputCollector
import dev.picasso.gate.input.ChangedFiles
import dev.picasso.gate.input.LedgerAnswer
import dev.picasso.gate.input.LedgerQuery
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import kotlin.streams.asSequence
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 검사 10 — 앞의 아홉이 실제로는 안 막고 있는 상태를 잡는다(설계 §11.2).
 *
 * 케이스를 데이터로 보관하고 **격리 실행**한다. 깨진 proto 조각을 저장소
 * 본체에 두면 진짜 CI가 깨지기 때문이다.
 *
 * **원본 트리가 기준선이고 덮어쓰기가 변경이다** — PR이 하는 일과 같다.
 * 그래서 케이스가 합성 diff를 들고 다닐 필요가 없다.
 */
class NegativeSuiteTest {
    // **완료 기준 12** — 깨는 PR이 사람 없이 차단된다. 여기 케이스가
    // 게이트 검사 1~8에 하나씩 대응한다(§11.2의 9번).


    private val repoRoot: Path = Path.of("..").toAbsolutePath().normalize()
    private val caseRoot: Path = repoRoot.resolve("gate/negative")
    private val mapper = ObjectMapper()
    private val trash = mutableListOf<Path>()

    /** CI가 참으로 준다. Docker 없는 로컬을 막지 않으면서 CI의 조용한 건너뜀은 막는다. */
    private val strict = System.getProperty("picasso.negative.strict") == "true"

    /**
     * `picasso.buf` 시스템 프로퍼티.
     *
     * 공백으로 쪼개지 않는다 — 이 저장소의 경로에 공백이 있다. `|`로 나눈다.
     *
     * **마지막 원소는 스크립트 자리이며 복사본 경로로 교체된다**([bufFor]).
     * 리눅스 CI: `<repo>/tools/buf` → `[<work>/tools/buf]`
     * Windows: `<bash.exe>|<아무 값>` → `[<bash.exe>, <work>/tools/buf]`
     */
    private val bufCommand: List<String> =
        System.getProperty("picasso.buf")?.split("|")?.filter { it.isNotBlank() } ?: emptyList()

    @AfterTest
    fun cleanup() {
        trash.forEach { dir ->
            runCatching {
                Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        }
        trash.clear()
    }

    private data class Case(
        val dir: Path,
        val targets: String,
        val needs: Set<String>,
        val expect: String,
        val reason: String,
        /** §9.3의 두 조회 답. 없으면 원장 없음(부분 건너뜀). */
        val ledger: Pair<Int, Int>?,
        /**
         * 검사 8번이 볼 변경 목록. **overlay가 없는 케이스는 이것만 갖는다** —
         * "소스가 섞인 커밋"은 파일을 덮어써서 표현할 수 있는 것이 아니다.
         */
        val changedFiles: List<String>,
        val addedFiles: List<String>,
    )

    private fun cases(): List<Case> {
        check(Files.isDirectory(caseRoot)) { "음성 케이스 디렉터리가 없다: $caseRoot" }
        return Files.list(caseRoot).use { s ->
            s.asSequence()
                .filter { Files.isDirectory(it) }
                .sortedBy { it.fileName.toString() }
                .map { dir ->
                    // 원시 예외 메시지만 나오면 어느 케이스가 왜인지 못 읽는다.
                    runCatching {
                        val node = mapper.readTree(Files.readString(dir.resolve("case.json")))
                        Case(
                            dir = dir,
                            targets = node.path("targets").asText(),
                            needs = node.path("needs").map { it.asText() }.toSet(),
                            expect = node.path("expect").asText(),
                            reason = node.path("reason").asText(),
                            ledger = node.get("ledger")?.let {
                                it.path("active_consumers").asInt() to
                                    it.path("inflight_tasks").asInt()
                            },
                            changedFiles = node.path("changed_files").map { f -> f.asText() },
                            addedFiles = node.path("added_files").map { f -> f.asText() },
                        )
                    }.getOrElse { error("케이스 ${dir.fileName}: ${it.message}") }
                }
                .toList()
        }
    }

    @Test
    fun `케이스가 검사 1부터 6까지 전부를 겨냥한다`() {
        // 겨냥하지 않는 검사가 있으면 그 검사는 아무도 시험하지 않는 것이다.
        val covered = cases().map { it.targets }.toSet()
        // **검사 목록에서 파생한다.** 리터럴로 두면 검사를 더하면서 음성
        // 케이스를 안 만들어도 이 시험이 초록으로 남는다 — "무엇의 전수인가"를
        // 틀리는 바로 그 방식이다.
        val wanted = GateChecks.all().map { it.id }.toSet()
        assertTrue(covered.containsAll(wanted), "겨냥되지 않은 검사가 있다: ${wanted - covered}")
    }

    @Test
    fun `모든 overlay가 원본과 다르다`() {
        // 치환이 빗나가 원본과 같은 파일이 만들어지면 그 케이스는 아무것도
        // 시험하지 않는다. Chunk 6에서 이미 밟은 실패다.
        cases().forEach { case ->
            val overlay = case.dir.resolve("overlay")
            if (!Files.isDirectory(overlay)) {
                // **overlay 없이 데이터만 갖는 케이스가 있다** — 검사 8의
                // "소스가 섞인 커밋"은 파일을 덮어써서 표현할 수 있는 것이
                // 아니다. 다만 아무것도 안 담은 케이스는 여전히 공허하다.
                assertTrue(
                    case.changedFiles.isNotEmpty() || case.addedFiles.isNotEmpty(),
                    "${case.dir.fileName}: overlay도 변경 목록도 없다 — 이 케이스는 아무것도 시험하지 않는다",
                )
                return@forEach
            }

            var files = 0
            Files.walk(overlay).use { s ->
                s.asSequence().filter { it.isRegularFile() }.forEach { f ->
                    files++
                    val rel = f.relativeTo(overlay).toString()
                    val original = repoRoot.resolve(rel)
                    if (Files.exists(original)) {
                        assertTrue(
                            !Files.readAllBytes(f).contentEquals(Files.readAllBytes(original)),
                            "${case.dir.fileName}: overlay가 원본과 같다 " +
                                "— 이 케이스는 아무것도 시험하지 않는다: $rel",
                        )
                    } else {
                        // **파일을 새로 더하는 것도 정당한 위반이다** — 검사
                        // 7번의 기종 분기가 그렇다. 원본을 통째로 베껴 한 줄
                        // 고치는 것보다 낡을 여지가 적다. 치환이 빗나가는
                        // 실패 방식이 애초에 없으므로 위 단언의 대상이 아니고,
                        // 비어 있지만 않으면 된다.
                        assertTrue(
                            Files.size(f) > 0,
                            "${case.dir.fileName}: 새로 더한 파일이 비었다: $rel",
                        )
                    }
                }
            }
            assertTrue(files > 0, "${case.dir.fileName}: overlay가 비었다")
        }
    }

    @Test
    fun `원본 트리는 모든 검사를 통과한다`() {
        // 케이스가 실패하는 것이 케이스 때문임을 보장한다. 원본이 이미
        // 실패하고 있으면 아래 시험이 아무것도 증명하지 않는다.
        val work = materialize(overlay = null)
        val report = gate().run(collect(work, ledger = null))

        assertTrue(report.failed.isEmpty(), "원본 트리가 이미 실패한다:\n${report.render()}")
        assertTrue(
            report.absentRequired.isEmpty(),
            "게이트 입력이 안 갖춰졌다: ${report.absentRequired}",
        )
        // 검사가 SKIP인 채로 이 시험이 초록이면 "원본이 통과한다"는 주장이 빈다.
        // §11.1이 인정하는 상시 건너뜀은 REGISTRY 하나뿐이고, buf가 없는
        // 로컬에서만 BUF를 더 봐준다 — strict(CI)에서는 봐주지 않는다.
        val allowed = buildSet {
            add(Resource.REGISTRY)
            if (!strict && bufCommand.isEmpty()) add(Resource.BUF)
        }
        assertEquals(
            emptyList(),
            report.skipped.filterNot { allowed.containsAll(it.missing) },
            "허용되지 않은 건너뜀이 있다(허용: $allowed):\n${report.render()}",
        )
    }

    @Test
    fun `CI가 게이트와 같은 프로파일 디렉터리를 넘긴다`() {
        // 목록이 세 곳에 있다 — CLI 기본값, 이 하네스, CI의 워크플로.
        // 앞의 둘은 ProfileDirectories를 쓰지만 YAML은 Kotlin을 못 읽는다.
        // 그래서 여기서 대조한다. 하나만 고치면 나머지가 조용히 다른 것을
        // 검사하게 되고, 그것이 §15.17이 적어 둔 실패 방식이다.
        val ci = Files.readString(repoRoot.resolve(".github/workflows/ci.yml"))
        ProfileDirectories.ALL.forEach {
            assertTrue(it in ci, "CI가 $it 를 넘기지 않는다 — 그 디렉터리는 검사 밖이다")
        }
    }

    @Test
    fun `CI가 검사 8의 입력을 만들고 그것을 요구한다`() {
        // **검사 8이 존재하는 것과 도는 것은 다르다.** 요구 목록에 없으면
        // diff 생성이 어느 날 조용히 깨져도 SKIP으로 넘어가고, 완료 기준
        // 11의 기제가 아무 소리 없이 사라진다. 종료 코드는 0인 채로.
        val ci = Files.readString(repoRoot.resolve(".github/workflows/ci.yml"))
        listOf(
            "--changed-files-from",
            "--added-files-from",
            // 이름 바꾸기가 "소스 변경 0으로 새 기종"으로 읽히는 것을 막는다.
            "--no-renames",
            "--diff-filter=A",
            "CHANGED_FILES",
        ).forEach {
            assertTrue(it in ci, "CI에 '$it' 이 없다 — 검사 8이 돌지 않는다")
        }
    }

    @Test
    fun `모든 음성 케이스가 겨냥한 검사를 기대한 이유로 실패시킨다`() {
        val skipped = mutableListOf<String>()
        val leaked = mutableListOf<String>()

        cases().forEach { case ->
            if ("BUF" in case.needs && bufCommand.isEmpty()) {
                skipped += "${case.dir.fileName}(buf 없음)"
                return@forEach
            }

            // overlay 없이 변경 목록만 갖는 케이스가 있다(검사 8).
            val work = materialize(
                overlay = case.dir.resolve("overlay").takeIf { Files.isDirectory(it) },
            )
            val report = gate().run(collect(work, case.ledger, case.changedFiles, case.addedFiles))
            val target = report.results.firstOrNull { it.checkId == case.targets }

            if (target !is CheckResult.Failed) {
                leaked += "${case.dir.fileName}: 검사 ${case.targets}이 막지 않았다 " +
                    "(${target?.let { it::class.simpleName } ?: "결과 없음"}). ${case.reason}\n" +
                    report.render().prependIndent("    ")
                return@forEach
            }

            // 검사 id만 보면 엉뚱한 이유의 실패가 통과로 읽힌다 — buf 마운트가
            // 어긋나 "had no .proto files"로 실패해도 초록이 났다(실측).
            // location도 함께 본다. 스키마 검증기의 소견은 위반한 값을
            // 메시지에 안 싣고 위치로만 가리킨다.
            val why = target.findings.joinToString(separator = "\n") { f ->
                f.message + (f.location?.let { " @$it" } ?: "")
            }
            if (case.expect.isNotBlank() && !why.contains(case.expect)) {
                leaked += "${case.dir.fileName}: 검사 ${case.targets}이 실패하긴 했으나 " +
                    "이유가 다르다. 기대='${case.expect}'\n${why.prependIndent("    ")}"
            }
        }

        if (leaked.isNotEmpty()) fail("음성 케이스가 새어 나갔다:\n${leaked.joinToString("\n")}")

        if (skipped.isNotEmpty()) {
            val message = "음성 케이스를 건너뛰었다: ${skipped.joinToString(", ")}"
            if (strict) fail("$message — CI에서는 건너뛸 수 없다")
            println("[negative] $message")
        }
    }

    // ── 격리 실행

    private fun materialize(overlay: Path?): Path {
        val tmp = Files.createTempDirectory("picasso-negative")
        // Path가 Iterable<Path>라 += 는 원소 추가와 전체 병합 사이에서 모호해진다.
        trash.add(tmp)
        val work = tmp.resolve("work")
        copyTree(repoRoot, work)
        copyTree(repoRoot, work.resolve(BASELINE_DIR))
        overlay?.let { copyTree(it, work) }
        return work
    }

    private fun copyTree(from: Path, to: Path) {
        Files.walk(from).use { stream ->
            stream.asSequence()
                .filter { it.isRegularFile() }
                .filterNot { isExcluded(it.relativeTo(from)) }
                .forEach { src ->
                    val dst = to.resolve(src.relativeTo(from).toString())
                    dst.parent.createDirectories()
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
                }
        }
    }

    /**
     * `Path.startsWith`는 요소 단위 비교라 루트의 `build/`만 걸린다. 그러면
     * `gate/build` 아래 전부(installDist·클래스·리포트)가 케이스마다 두 벌씩
     * 복사된다 — 실측 768파일 26.8MB. 요소 어디에 있든 제외하되 디스크립터만
     * 되살린다. 없으면 검사 4가 조용히 건너뛴다.
     */
    private fun isExcluded(rel: Path): Boolean =
        rel != DESCRIPTOR && rel.any { it.toString() in EXCLUDED }

    private fun collect(
        work: Path,
        ledger: Pair<Int, Int>?,
        changedFiles: List<String> = emptyList(),
        addedFiles: List<String> = emptyList(),
    ) =
        InputCollector(work).collect(
            // **CLI와 같은 목록을 쓴다.** 여기만 픽스처로 두면 원본 트리
            // 시험이 실제 기종 프로파일을 아예 안 보고, 스키마를 어긴
            // humanoid-a가 음성 하네스를 통과한 채 CI의 게이트 스텝에서야
            // 걸린다 — 하네스가 막겠다고 선언한 조용한 통과다.
            profileDirs = ProfileDirectories.ALL.map(work::resolve),
            schemaFile = work.resolve("profile/schema/capability-profile.schema.json"),
            descriptorFile = work.resolve("contracts/build/descriptor.binpb"),
            baselineDirs = ProfileDirectories.ALL.map { work.resolve("$BASELINE_DIR/$it") },
            contractBaseline = "../$BASELINE_DIR/contracts",
            buf = bufFor(work),
            // **null이 아니라 빈 목록이다.** null이면 검사 8이 건너뛰고,
            // 그러면 `원본 트리는 모든 검사를 통과한다`가 허용되지 않은
            // 건너뜀으로 실패한다. 빈 목록은 "이 변경은 아무것도 안 바꿨다"이고
            // 검사 8은 그것을 통과로 판정한다 — 판정을 했다는 뜻이다.
            changed = ChangedFiles(changedFiles, addedFiles),
        ).copy(registry = ledger?.let(::FakeLedger))

    /**
     * **복사본 안의 `tools/buf`를 부른다.** 원본을 부르면 그 스크립트가 자기
     * 위치에서 저장소 루트를 잡아 **원본 트리를 마운트하므로** 덮어쓴 파일이
     * 컨테이너에 안 보인다(실측: `Module "path: "."" had no .proto files`).
     * 검사 1·2가 실패하긴 하므로 케이스가 통과한 것처럼 읽힌다 — 이 하네스가
     * 막겠다고 선언한 바로 그 조용한 통과다.
     */
    private fun bufFor(work: Path): ProcessBufRunner? {
        if (bufCommand.isEmpty()) return null
        val script = work.resolve("tools/buf")
        // Files.copy는 실행 권한을 옮기지 않는다. 리눅스 CI에서 필수다.
        script.toFile().setExecutable(true)
        return ProcessBufRunner(bufCommand.dropLast(1) + script.toString())
    }

    private fun gate(): GateRunner {
        val required = GateChecks.REQUIRED_IN_CI -
            (if (bufCommand.isEmpty()) setOf(Resource.BUF) else emptySet())
        return GateRunner(GateChecks.all(), required)
    }

    private class FakeLedger(private val counts: Pair<Int, Int>) : LedgerQuery {
        override fun activeConsumers(skillType: String, major: Int) =
            LedgerAnswer.Observed(counts.first, Instant.EPOCH)

        override fun inflightTasks(skillType: String, major: Int) =
            LedgerAnswer.Observed(counts.second, Instant.EPOCH)
    }

    private companion object {
        const val BASELINE_DIR = ".picasso-baseline"
        val EXCLUDED = setOf(".git", "build", ".gradle", ".idea", ".kotlin")
        val DESCRIPTOR: Path = Path.of("contracts", "build", "descriptor.binpb")
    }
}
