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

    private val repo: Path = Repo.root

    private fun read(relative: String): String = Repo.read(relative)

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
    fun `모듈마다 문이 있다`() {
        // **모듈 나무가 이름을 적는 것과 그 안으로 들어갈 문이 있는 것은 다르다.** 모듈이 늘면 문도 늘어야 하고,
        // 그것을 사람이 기억하지 않는다.
        val missing = modules().filterNot { Files.isRegularFile(Repo.path("$it/README.md")) }
        assertEquals(emptyList(), missing, "이 모듈에는 README.md 가 없다")
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
        val measured = Repo.list("profile/distance", ".json").size
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

    @Test
    fun `교체 지점의 인터페이스가 전부 실재한다`() {
        // `seams.md` 는 *"실물로 바꾸려면 어디를 고치나"* 에 답하는 문서다. 거기 적힌 타입이 사라지거나
        // 파일이 옮겨지면 **그 답이 틀린 답이 된다** — 이름을 바꾼 사람은 문서를 안 본다.
        val seams = Repo.read("docs/seams.md")
        val table = seams.lines().dropWhile { !it.startsWith("## 색인") }
            .filter { it.startsWith("|") }
            .filterNot { it.startsWith("| 자리 ") || it.startsWith("|---") }
        val row = Regex("""\| [^|]+ \| `([A-Za-z0-9_]+)` \| `([^`]+\.kt)` \|""")

        // ★**못 읽은 줄을 실패로 적는다.** 앞 판은 맞는 줄만 골라 세고 하한(12줄)만 봤는데, 주입이
        // 한 줄에서 백틱만 빼도 그 줄이 조용히 빠지고 하한은 그대로 넘는 것을 찾아냈다 — **검사 범위가 준다.**
        val unreadable = table.filterNot { row.matchEntire(it) != null }
        assertEquals(emptyList(), unreadable, "색인 줄을 못 읽었다 — 모양이 바뀌면 검사가 그 줄을 건너뛴다")

        // 색인과 본문이 **같은 집합**이어야 한다. 한쪽만 보면 반만 막힌다 — 주입이 그것을 보였다:
        // 본문에 안 나오는 줄은 통째로 지워도 안 걸렸다(§15.120). 하한은 내가 손으로 적은 숫자지만
        // 이 대조는 **문서가 스스로 대는 목록**이다.
        val rows = table.mapNotNull { row.matchEntire(it) }
        val indexed = rows.map { it.groupValues[1] }.toSet()
        val named = Regex("""\*\*인터페이스[^*]*\*\*: (.*)""").findAll(seams)
            .flatMap { Regex("`([A-Z][A-Za-z0-9]*[a-z][A-Za-z0-9]*)").findAll(it.groupValues[1]) }
            .map { it.groupValues[1] }.toSet()
        assertEquals(emptySet<String>(), named - indexed, "본문이 이름 지은 인터페이스가 색인에 없다")
        assertEquals(emptySet<String>(), indexed - named, "색인에만 있고 본문이 설명 안 하는 자리가 있다")

        val broken = rows.mapNotNull { hit ->
            val (type, path) = hit.destructured
            val file = Repo.path(path)
            when {
                !Files.isRegularFile(file) -> "$type: 파일이 없다 — $path"
                !Regex("(fun )?interface " + type + "[^A-Za-z0-9_]").containsMatchIn(Files.readString(file)) ->
                    "$type: $path 에 그 인터페이스가 없다"
                else -> null
            }
        }
        assertEquals(emptyList(), broken, "교체 지점 색인이 코드와 다르다")
    }

    @Test
    fun `계약이 적은 담보마다 그것을 지키는 시험이 실재한다`() {
        // **시험이 없는 담보는 담보가 아니라 희망이다.** `contract.md` 는 담보마다 그것을 지키는 시험을
        // 이름으로 댄다. 시험을 지우거나 이름을 바꾼 사람은 그 문서를 안 보므로 여기서 맞댄다.
        val doc = Repo.read("docs/contract.md")
        val cite = Regex("""`([a-z0-9-]+)` · `([A-Za-z0-9]+Test)` · `([^`]+)`""")

        // 담보 표의 **모든 줄**이 시험을 대야 한다. 증명 칸을 비워 두면 주장만 남는다.
        val rows = doc.lines().dropWhile { !it.startsWith("| 담보 |") }.drop(2).takeWhile { it.startsWith("|") }
        assertTrue(rows.size >= 15, "담보 표가 비었거나 모양이 바뀌었다: ${rows.size} 줄")
        assertEquals(emptyList(), rows.filterNot { cite.containsMatchIn(it) }, "담보를 적고 지키는 시험을 안 댔다")

        val broken = cite.findAll(doc).mapNotNull { hit ->
            val (module, cls, name) = hit.destructured
            val file = Files.walk(Repo.path("$module/src/test")).use { walk ->
                walk.filter { it.fileName.toString() == "$cls.kt" }.findFirst().orElse(null)
            }
            when {
                file == null -> "$module · $cls: 그런 시험 파일이 없다"
                !Repo.read(file).contains("fun `$name`(") -> "$module · $cls: `$name` 이 없다"
                else -> null
            }
        }.toList()
        assertEquals(emptyList(), broken, "계약 문서가 대는 시험이 코드에 없다")
    }

    @Test
    fun `ISA-95 대조가 코드와 같은 타입을 대고, 근거 등급이 계약으로 안 샌다`() {
        // 대조표의 값은 **표준의 타입 이름**이다. 코드 주석이 짚는 것과 다르면 둘 중 하나가 낡은 것이다.
        val names = Regex("""ISA95[A-Za-z]+DataType""")
        val inDoc = names.findAll(Repo.read("docs/isa95.md")).map { it.value }.toSet()
        val inCode = Repo.list("picasso/src/main/kotlin/dev/picasso/middleware", ".kt")
            .flatMap { file -> names.findAll(Repo.read(file)).map { it.value } }.toSet()
        assertTrue(inCode.size >= 4, "코드가 짚는 표준 타입이 사라졌다: $inCode")
        assertEquals(inCode, inDoc, "ISA-95 타입 이름이 문서와 코드에서 다르다")

        // ★**§4 의 논거 전체가 이 한 줄에 매달려 있다.** 근거 등급이 계약 면으로 나가면 로봇이 자기 완료의
        // 신뢰도를 자기가 선언하게 되고 — 확인하려는 대상에게 확인을 맡기는 것이 된다.
        val leaked = Repo.list("contracts/proto/picasso/v1", ".proto")
            .filter { Repo.read(it).contains("evidence", ignoreCase = true) }
            .map { it.fileName.toString() }
        assertEquals(emptyList(), leaked, "근거 등급이 계약 proto 로 샜다 — isa95.md §4 의 논거가 무너진다")
    }

    @Test
    fun `설정 표면 목록이 바꾸는 문을 빠짐없이 적는다`() {
        // `commissioning.md` §4 는 *"설정 표면 전부"* 라고 말한다. **전부가 아니면 그 문장이 거짓이고**,
        // 문서에 없는 문으로 현장 설정을 바꿀 수 있다는 뜻이 된다.
        val listed = Regex("""`(GET|POST|DELETE) (/[^`]+)`""").findAll(Repo.read("docs/commissioning.md"))
            .map { it.groupValues[1] + " " + it.groupValues[2] }.toSet()
        val mapped = Repo.list("registry/src/main/kotlin/dev/picasso/registry/web", ".kt")
            .flatMap { file ->
                Regex("""@(Get|Post|Delete)Mapping\("([^"]+)"\)""").findAll(Repo.read(file)).map { it.groupValues[1].uppercase() + " " + it.groupValues[2] }
            }.toSet()
        assertTrue(mapped.size >= 15, "표면을 못 읽었다: $mapped")

        assertEquals(emptySet<String>(), listed - mapped, "문서가 없는 표면을 적는다")

        // **바꾸는 문만 전수를 요구한다.** 열람(`/diag`)은 늘어도 설정 표면이 아니고, 그것까지 요구하면
        // 진단 하나 더할 때마다 이 표가 커져서 아무도 안 읽는다.
        val doors = mapped.filter { it.contains("/operations/") || it.contains("/ingest/") || it.endsWith("/requirements") }
        assertEquals(emptyList(), doors.filterNot { it in listed }.sorted(), "설정을 바꾸는 문이 문서에 없다")
    }

    @Test
    fun `문서가 가리키는 파일이 전부 실재한다`() {
        // 문서 사이의 링크가 이 저장소에서 유일하게 **자동으로 낡는 것**이다 — 파일 이름을 바꾸면 아무도 안 알려 준다.
        // 숫자를 세는 것과 같은 이유로 여기서 본다.
        //
        // ★**앞 판은 `Repo.list("docs", ".md")` 를 썼고 그것은 비재귀다**(`Files.list`). 그래서 `docs/adr/` ·
        // `docs/vendors/` · `docs/superpowers/specs/` 의 링크를 **아예 안 봤고**, ADR 39 가 이름이 바뀐 파일을
        // 가리키는 채로 초록이었다(실측 2026-09-11). 이제 **주장의 자리 전부**를 훑는다 — 목록이
        // `ClaimSurface` 한 곳에서 나오므로 새 문서가 생겨도 저절로 들어온다.
        val docs = ClaimSurface.documents()
        val broken = docs.flatMap { doc ->
            Regex("""\]\(([^)#:]+\.md[^)#]*)\)""").findAll(Files.readString(doc)).map { doc to it.groupValues[1] }
        }.filterNot { (doc, link) -> Files.exists(doc.parent.resolve(link).normalize()) }
            .map { (doc, link) -> "${repo.relativize(doc)} → $link" }

        assertEquals(emptyList(), broken, "문서가 없는 파일을 가리킨다")
    }

    @Test
    fun `상태기계 표의 값이 코드와 같다`() {
        // ★§15.119 와 같은 자리다 — **산문이면 눈에 띌 것이 표의 한 칸에 묻힌다.**
        // 실측(2026-09-11): 실행 상태에 `ACCEPTED` 가 빠져 있었고, 태스크 상태 칸에 **실패 분류**인
        // `CONTROL_AUTHORITY_LOST` 가 섞여 있었다. 그 표의 요지가 *"둘은 다른 층이고 하나가 다른 하나를
        // 대신하지 않는다"* 인데 **표 자신이 셋째 어휘를 들여놓고 있었다.**
        val row = read("docs/architecture.md").lines().single { it.startsWith("| **값** |") }
        val cells = row.split("|").map { it.trim() }
        fun quoted(cell: String) = Regex("`([A-Z_]+)`").findAll(cell).map { it.groupValues[1] }.toList()

        val physical = Regex("""enum class PhysicalState \{([^;]+);""")
            .find(read("picasso/src/main/kotlin/dev/picasso/middleware/Model.kt"))
            ?.groupValues?.get(1)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: error("PhysicalState 를 못 읽었다")
        assertEquals(physical, quoted(cells[2]), "실행 상태 값이 PhysicalState 와 다르다")

        val task = Regex("""TASK_STATE_([A-Z_]+) = \d+""")
            .findAll(read("contracts/proto/picasso/v1/task.proto"))
            .map { it.groupValues[1] }
            .filterNot { it == "UNSPECIFIED" }
            .toList()
        assertEquals(task.sorted(), quoted(cells[3]).sorted(), "태스크 상태 값이 계약의 TaskState 와 다르다")
    }

    @Test
    fun `모듈 의존 그림이 빌드와 같다`() {
        // ★**`architecture.md` 의 의존 그림 아홉 줄 중 게이트가 집행하던 것은 둘뿐이었다**(검사 5 의
        // `contracts` 의존 0, 검사 7 의 기종 문자열). 나머지 일곱은 산문이었고, 그래서 `README.md` 가
        // *"어댑터는 `contracts` 하나에만 의존한다"* 를 **`adapter-core` 가 생긴 뒤에도** 들고 있었다.
        //
        // **출하 의존만 센다.** 시험이 무엇을 끌어오는지는 다른 이야기다 — 하네스가 어댑터를 끌어오는 것이
        // 정상이고, 섞으면 이 표가 아무것도 못 막는다.
        val declared = Regex("""^\s*(\w+)\s*\(\s*(?:testFixtures\s*\()?\s*project\("[:]([a-z0-9-]+)"\)""")
        val actual = modules().associateWith { module ->
            Repo.read("$module/build.gradle.kts").lines()
                .filterNot { it.trimStart().startsWith("//") }
                .mapNotNull { declared.find(it) }
                .filterNot { it.groupValues[1].startsWith("test") }
                .map { it.groupValues[2] }
                .toSortedSet()
        }

        val table = read("docs/architecture.md").substringAfter("## 4b.").substringAfter("```text").substringBefore("```")
        val documented = table.lines().mapNotNull { line ->
            val (name, deps) = line.split("→").takeIf { it.size == 2 } ?: return@mapNotNull null
            name.trim() to deps.trim().takeIf { it != "(없음)" }.orEmpty()
                .split("·").map { it.trim() }.filter { it.isNotEmpty() }.toSortedSet()
        }.toMap()

        assertEquals(actual, documented, "의존이 바뀌었는데 architecture.md §4b 가 그대로다")
    }

    @Test
    fun `게이트가 계약에 빌드 의존 대신 태스크 의존을 건다`() {
        // `README.md` 와 `gate/README.md` 가 *"빌드 의존을 안 걸고 디스크립터 바이트를 읽는다 —
        // `:gate:test` 가 그 태스크에 매달려 있어 손으로 먼저 돌릴 명령이 없다"* 고 적는다.
        // 앞 절반은 §4b 의 표가 대고(게이트의 출하 의존은 `profile-model` 뿐), 뒤 절반이 이 줄이다.
        val build = read("gate/build.gradle.kts")
        assertTrue("dependsOn(\":contracts:generateProto\")" in build, "태스크 의존이 사라졌다 — 손으로 먼저 돌려야 하는 상태가 된다")
        assertTrue("inputs.files(descriptor)" in build, "디스크립터가 입력 선언에서 빠졌다 — 바뀌어도 UP-TO-DATE 로 넘어간다")
    }

    @Test
    fun `모듈 문이 적은 수가 코드와 같다`() {
        // ★**수 세기가 루트 `README.md` 와 `docs/` 만 보고 있었다.** 그래서 모듈 문의 숫자는 아무도 안
        // 셌고, 실측(2026-09-11) 셋이 낡아 있었다 — `contracts` 가 proto 를 다섯이라 적었고(여섯),
        // `registry` 가 마이그레이션을 V14 까지라 적었고(V15), 문 시험을 다섯이라 적었다(넷).
        assertEquals(
            Repo.list("contracts/proto/picasso/v1", ".proto").size,
            claimed("""의 (\S+) 파일이고""", read("contracts/README.md")),
            "proto 파일이 늘거나 줄었는데 contracts/README.md 가 그대로다",
        )

        val latestMigration = Repo.declaredFiles()
            .mapNotNull { Regex("""^V(\d+)__""").find(it.fileName.toString())?.groupValues?.get(1)?.toInt() }
            .maxOrNull() ?: error("마이그레이션을 못 읽었다")
        assertEquals(
            latestMigration,
            claimed("""V1~V(\S+)\)""", read("registry/README.md")),
            "마이그레이션이 늘었는데 registry/README.md 가 그대로다",
        )

        val doors = Repo.declaredFiles().count { it.fileName.toString().endsWith("EndpointTest.kt") }
        assertEquals(
            doors,
            claimed("""`web/\*EndpointTest` (\S+)이""", read("registry/README.md")),
            "문 시험이 늘거나 줄었는데 registry/README.md 가 그대로다",
        )
    }

    @Test
    fun `계약에 남아 있는 스킬 타입의 수를 계약 문서가 맞게 적는다`() {
        // ★**"관문을 못 지났다" 를 "계약에 없다" 로 읽으면 안 된다.** 스킬 카탈로그는 ADR 36 이
        // *양쪽 다 없던 어휘* 로 판정했지만 빼는 것이 major 개정이라 **계약에 그대로 있다.**
        // 실측(2026-09-11): 문서가 그 둘을 안 갈라 적어 계약에 카탈로그가 없는 것처럼 읽혔다.
        val actual = Regex("""skill_type_name\) = """)
            .findAll(read("contracts/proto/picasso/v1/skill_catalog.proto")).count()
        assertEquals(
            actual,
            claimed("""스킬 타입 (\S+)이 거기 있다""", read("docs/contract.md")),
            "카탈로그의 스킬 타입이 늘거나 줄었는데 contract.md 가 그대로다",
        )
    }

    @Test
    fun `레지스트리 문 시험의 수를 검증 근거 표가 맞게 적는다`() {
        // 실측(2026-09-11): 표가 다섯이라 적었는데 넷이었다. 다섯째 후보인 `IngestTokenTest` 는
        // 스스로 *"서버 없이 본다"* 고 적으므로 실물 등급의 증명이 아니다.
        val actual = Repo.declaredFiles().count { it.fileName.toString().endsWith("EndpointTest.kt") }
        assertEquals(
            actual,
            claimed("""`\*EndpointTest` (\S+)""", read("docs/verification.md")),
            "레지스트리 문 시험이 늘거나 줄었는데 verification.md 가 그대로다",
        )
    }

    @Test
    fun `설계 일지의 마지막 번호를 한계 대장이 맞게 적는다`() {
        // ★**이 시험이 없어서 물렸다.** `limits.md` 가 *"번호가 106 까지 갔고"* 라 적어 둔 채 124 까지 갔고,
        // 같은 문서가 §15.123·§15.124 를 대장 행으로 싣고 있었다 — **낡은 산문이 세 줄 아래의
        // *"산문이 낡는 것을 사람이 훑어 막지 않는다"* 를 바로 반증하고 있었다.**
        val last = Regex("""(?m)^(\d+)\. \*\*""").findAll(design)
            .map { it.groupValues[1].toInt() }
            .maxOrNull() ?: error("설계 일지의 번호를 못 읽었다")
        assertEquals(
            last,
            claimed("""번호가 (\S+) 까지 갔고""", read("docs/limits.md")),
            "일지가 늘었는데 limits.md 가 그대로다",
        )
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
