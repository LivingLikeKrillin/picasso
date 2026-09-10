package dev.picasso.gate

import java.nio.file.Path
import java.security.MessageDigest

object ClaimSurface {

    /**
     * 주장의 자리 — **손으로 안 적는다.** `:gate:test` 의 선언된 입력에서 고른다.
     *
     * 실측(2026-09-10): 선언된 입력 아래의 `.md` 는 루트 `README.md` · `docs/` 아래 전부 ·
     * `<모듈>/README.md` 뿐이다. 그래서 거르는 것은 계획 문서뿐이며, **새 문서가 생기면 저절로
     * 자리가 된다.**
     *
     * ★**Kotlin 은 블록 주석이 중첩된다**(Java 와 다르다). 그래서 주석 안에 `/` 바로 뒤 `*` 가 오는
     * 글자를 쓰면 그 자리가 중첩 주석의 시작이 되어 파일 끝까지 안 닫힌다. 이 KDoc 이 실제로 한 번
     * 그렇게 깨졌다 — 글롭을 적으려다가.
     */
    fun documents(): List<Path> =
        Repo.declaredFiles()
            .filter { it.fileName.toString().endsWith(".md") }
            .filterNot { relative(it).startsWith("docs/superpowers/plans/") }

    /** ★`normalize()` 를 뺀 적이 있다. `Repo.root` 는 `…/gate/..` 꼴이라 이 값이 **의미로** 쓰이는
     *  첫 자리가 여기다(경로 접두 판정·`== "README.md"`). JDK 세부에 매달리지 않는다. */
    fun relative(path: Path): String =
        Repo.root.normalize().relativize(path.normalize()).joinToString("/")

    /** 설계 문서의 경계. 이 제목부터는 일지라 주장이 아니다(스펙 §2). */
    const val JOURNAL_HEADING = "## 15."

    private val STAMP =
        Regex("""^> 마지막 대조: (\d{4}-\d{2}-\d{2}) · sha256:([0-9a-f]{12}) · 열림: (.+)$""")

    data class Stamp(val date: String, val sha: String, val openIds: List<String>)

    /**
     * 줄마다 **펜스 밖인가.** 펜스를 여닫는 줄 자체는 밖이 아니다.
     *
     * ★도장 형식을 설명하는 문서는 예시를 품는다. 펜스를 모르면 예시가 도장으로 읽히고, 그 순간
     * 검사가 빨개지고 도구가 그 예시를 지운다.
     *
     * ★**규칙은 여기 한 벌이다** — `LimitsLedger` 도 이것을 쓴다. 두 벌로 두면 어느 날 한쪽만
     * 는다(§15.115 가 이 저장소에 남긴 규율).
     */
    fun outsideFence(lines: List<String>): List<Boolean> {
        var fenced = false
        return lines.map { line ->
            if (line.trimStart().startsWith("```")) { fenced = !fenced; false } else !fenced
        }
    }

    private fun scan(content: String): Pair<List<String>, List<Boolean>> {
        val lines = content.replace("\r\n", "\n").lines()
        return lines to outsideFence(lines)
    }

    fun stampOf(content: String): Stamp? {
        val (lines, outside) = scan(content)
        val m = lines.indices.asSequence()
            .filter { outside[it] }
            .mapNotNull { STAMP.find(lines[it]) }
            .firstOrNull() ?: return null
        val ids = m.groupValues[3].trim()
        return Stamp(
            date = m.groupValues[1],
            sha = m.groupValues[2],
            openIds = if (ids == "없음") emptyList()
                else ids.split(",").map(String::trim).filter(String::isNotEmpty),
        )
    }

    /** 펜스 밖 도장 줄을 빼고 펜스 밖 일지 제목 앞에서 끊은 본문. **해시의 대상이다.** */
    fun bodyOf(content: String): String {
        val (lines, outside) = scan(content)
        val cut = lines.indices.firstOrNull { outside[it] && lines[it].startsWith(JOURNAL_HEADING) }
        val end = cut ?: lines.size
        return (0 until end)
            .filterNot { outside[it] && STAMP.matches(lines[it]) }
            .joinToString("\n") { lines[it] }
            .trimEnd()
    }

    fun shaOf(body: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(body.replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(12)

    /** 파일에서 바로. **`Repo` 를 지난다** — 선언 밖은 여기서 던진다. */
    fun expectedSha(path: Path): String = shaOf(bodyOf(Repo.read(path)))

    fun stamp(path: Path): Stamp? = stampOf(Repo.read(path))
}
