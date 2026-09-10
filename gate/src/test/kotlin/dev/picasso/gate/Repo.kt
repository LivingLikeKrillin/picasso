package dev.picasso.gate

import java.nio.file.Files
import java.nio.file.Path

/**
 * 시험이 저장소 파일을 읽는 **유일한 문** — 그리고 읽는 순간 **그 파일이 `:gate:test` 의 입력으로 선언돼
 * 있는지** 확인한다.
 *
 * ## 왜 이런 것이 필요한가
 *
 * 이 저장소가 **같은 구멍에 네 번 물렸다.** 시험이 저장소 파일을 읽는데 그 경로가 Gradle 의 `inputs` 에 없으면,
 * 그 파일만 고친 변경에서 `:gate:test` 가 **UP-TO-DATE 로 넘어간다** — 시험이 아예 안 돌고 빌드는 초록이다.
 * 픽스처에서 한 번, 검사 7번의 모듈 목록에서 한 번, 조사 문서에서 한 번, 그리고 2026-09-10 에 문서 링크에서
 * 한 번. 매번 고친 방법은 *`build.gradle.kts` 에 줄을 하나 더한다* 였고, **매번 다음 사람이 그것을 잊었다.**
 *
 * ## 그래서 기억할 것을 없앤다
 *
 * `gate/build.gradle.kts` 의 `repoInputs` **하나**가 두 곳에 쓰인다 — Gradle 의 입력 선언과, 여기로 넘어오는
 * 목록. 선언 안 한 파일을 시험이 읽으면 **그 자리에서 실패한다.** 새 시험이 새 파일을 읽어도, 누가 선언 줄을
 * 지워도 잡힌다.
 *
 * ## 여기를 안 지나는 것
 *
 * **게이트 자신이 읽는 것은 여기 안 온다.** `Check05`·`Check07`·음성 하네스는 저장소 트리를 **넘겨받아**
 * 훑는 것이 일이고(그것이 검사의 내용이다), 그 경로를 여기서 막으면 검사를 못 돌린다. 이 문은 **시험이
 * 단언하려고 읽는 파일**의 것이다.
 */
object Repo {

    val root: Path = Path.of("..").normalize().toAbsolutePath()

    /**
     * `:gate:test` 가 넘겨준 선언 목록(저장소 상대 경로).
     *
     * **없으면 실패한다.** 조용히 통과시키면 이 문이 아무 일도 안 하는 채로 남고, 그것이 정확히 이 파일이
     * 막으려는 실패 방식이다.
     */
    private val declared: List<Path> by lazy {
        val raw = System.getProperty("picasso.gate.declaredInputs")
            ?: error("picasso.gate.declaredInputs 가 없다 — gate/build.gradle.kts 가 그것을 넘겨야 한다")
        raw.split("|").filter { it.isNotBlank() }.map { root.resolve(it).normalize() }
    }

    /** 저장소 안의 경로 하나. 선언 안 된 것이면 던진다. */
    fun path(relative: String): Path = root.resolve(relative).normalize().also(::guard)

    fun read(relative: String): String = Files.readString(path(relative)).replace("\r\n", "\n")

    fun read(path: Path): String = Files.readString(path.also(::guard)).replace("\r\n", "\n")

    /** 디렉터리의 파일들. 디렉터리가 선언돼 있으면 그 아래는 전부 선언된 것이다. */
    fun list(relative: String, suffix: String = ""): List<Path> =
        Files.list(path(relative)).use { stream ->
            stream.filter { it.toString().endsWith(suffix) }.sorted().toList()
        }

    private fun guard(candidate: Path) {
        val target = candidate.toAbsolutePath().normalize()
        if (declared.any { target == it || target.startsWith(it) }) return
        error(
            "이 파일은 `:gate:test` 의 입력으로 선언돼 있지 않다: ${root.relativize(target)}\n" +
                "  gate/build.gradle.kts 의 `repoInputs` 에 더하라.\n" +
                "  선언 안 하면 이 파일만 고친 변경에서 시험이 UP-TO-DATE 로 안 돌고 빌드가 초록이 된다 — " +
                "이 저장소가 네 번 물린 구멍이다.",
        )
    }
}
