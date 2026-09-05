package dev.picasso.gate

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.gate.buf.ProcessBufRunner
import dev.picasso.gate.cli.InputCollector
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
 * 검사 9 — 앞의 여덟이 실제로는 안 막고 있는 상태를 잡는다(설계 §11.2).
 *
 * 케이스를 데이터로 보관하고 **격리 실행**한다. 깨진 proto 조각을 저장소
 * 본체에 두면 진짜 CI가 깨지기 때문이다.
 *
 * **원본 트리가 기준선이고 덮어쓰기가 변경이다** — PR이 하는 일과 같다.
 * 그래서 케이스가 합성 diff를 들고 다닐 필요가 없다.
 */
class NegativeSuiteTest {

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
        val wanted = setOf("1", "2", "3", "4", "5", "6")
        assertTrue(covered.containsAll(wanted), "겨냥되지 않은 검사가 있다: ${wanted - covered}")
    }

    @Test
    fun `모든 overlay가 원본과 다르다`() {
        // 치환이 빗나가 원본과 같은 파일이 만들어지면 그 케이스는 아무것도
        // 시험하지 않는다. Chunk 6에서 이미 밟은 실패다.
        cases().forEach { case ->
            val overlay = case.dir.resolve("overlay")
            check(Files.isDirectory(overlay)) { "${case.dir.fileName}: overlay/ 가 없다" }

            var files = 0
            Files.walk(overlay).use { s ->
                s.asSequence().filter { it.isRegularFile() }.forEach { f ->
                    files++
                    val rel = f.relativeTo(overlay).toString()
                    val original = repoRoot.resolve(rel)
                    assertTrue(
                        Files.exists(original),
                        "${case.dir.fileName}: 원본에 없는 파일을 덮는다: $rel",
                    )
                    assertTrue(
                        !Files.readAllBytes(f).contentEquals(Files.readAllBytes(original)),
                        "${case.dir.fileName}: overlay가 원본과 같다 " +
                            "— 이 케이스는 아무것도 시험하지 않는다: $rel",
                    )
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
    fun `모든 음성 케이스가 겨냥한 검사를 기대한 이유로 실패시킨다`() {
        val skipped = mutableListOf<String>()
        val leaked = mutableListOf<String>()

        cases().forEach { case ->
            if ("BUF" in case.needs && bufCommand.isEmpty()) {
                skipped += "${case.dir.fileName}(buf 없음)"
                return@forEach
            }

            val work = materialize(overlay = case.dir.resolve("overlay"))
            val report = gate().run(collect(work, case.ledger))
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

    private fun collect(work: Path, ledger: Pair<Int, Int>?) =
        InputCollector(work).collect(
            profileDir = work.resolve("profile/fixtures"),
            schemaFile = work.resolve("profile/schema/capability-profile.schema.json"),
            descriptorFile = work.resolve("contracts/build/descriptor.binpb"),
            baselineDir = work.resolve("$BASELINE_DIR/profile/fixtures"),
            contractBaseline = "../$BASELINE_DIR/contracts",
            buf = bufFor(work),
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
