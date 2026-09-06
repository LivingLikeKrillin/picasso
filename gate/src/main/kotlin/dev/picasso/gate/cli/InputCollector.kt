package dev.picasso.gate.cli

import dev.picasso.gate.buf.BufRunner
import dev.picasso.gate.input.ChangedFiles
import dev.picasso.gate.input.GateInput
import dev.picasso.gate.input.MalformedProfile
import dev.picasso.profile.ProfileKey
import dev.picasso.profile.ProfileDocument
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.streams.asSequence

/**
 * 디스크에서 [GateInput]을 만든다.
 *
 * **git을 부르지 않는다.** 기준선 문서를 꺼내는 것은 호출 지점의 일이고
 * (§11.1), 여기는 이미 꺼내 놓은 디렉터리를 읽는다. `registry` 호출 지점은
 * 이 클래스를 쓰지 않고 DB에서 직접 만든다.
 */
class InputCollector(private val repoRoot: Path) {

    fun collect(
        profileDirs: List<Path>,
        schemaFile: Path? = null,
        descriptorFile: Path? = null,
        baselineDirs: List<Path> = emptyList(),
        contractBaseline: String? = null,
        buf: BufRunner? = null,
        changed: ChangedFiles? = null,
    ): GateInput {
        val head = readAll(profileDirs)

        return GateInput(
            profiles = head.documents,
            malformed = head.malformed,
            schemaJson = schemaFile?.takeIf { it.isRegularFile() }?.let(Files::readString),
            descriptor = descriptorFile?.takeIf { it.isRegularFile() }?.let(Files::readAllBytes),
            repoRoot = repoRoot,
            baseline = readBaseline(baselineDirs),
            contractBaseline = contractBaseline,
            buf = buf,
            changed = changed,
        )
    }

    /**
     * **없는 디렉터리(null)와 빈 디렉터리(빈 맵)의 뜻이 다르다.** 없으면
     * 검사 6이 건너뛰고, 비었으면 신규라 파괴 검사가 통과한다(§11.1).
     *
     * 깨진 기준선을 조용히 빼면 빈 맵이 되어 `Resource.BASELINE`이 "있다"로
     * 잡히고, 검사 6이 모든 프로파일을 신규로 분류해 **경고 한 줄 없이
     * 완전한 PASS**를 낸다. 그래서 여기서 시끄럽게 죽는다.
     */
    private fun readBaseline(dirs: List<Path>): Map<ProfileKey, String>? {
        // **하나라도 못 읽으면 null이다.** 일부만 읽고 빈 맵을 만들면
        // 그쪽 프로파일이 전부 "신규"가 되어 파괴 검사가 통과한다.
        if (dirs.isEmpty() || dirs.any { !Files.isDirectory(it) }) return null
        val parsed = readAll(dirs)
        require(parsed.malformed.isEmpty()) {
            "기준선 문서를 읽을 수 없다: " +
                parsed.malformed.joinToString { "${it.path} — ${it.message}" }
        }
        // baseline은 문서 원문 맵이다(Chunk 5 보정 I4). ProfileDocument가 아니다.
        return parsed.documents.associate { ProfileKey(it.vendor, it.model) to it.raw }
    }

    private class Parsed(
        val documents: List<ProfileDocument>,
        val malformed: List<MalformedProfile>,
    )

    /**
     * 디렉터리 여럿에서 모은다.
     *
     * §7.4가 픽스처(`profile/fixtures/`)와 실제 기종(`profile/profiles/`)을
     * 나눠 두라고 하는데, 하나만 보면 **나머지가 검사 3·4·6 밖에 놓인다** —
     * 완료 기준 9("두 기종이 동일 스키마 통과")가 아무 근거 없이 참이 된다.
     *
     * **없는 디렉터리를 조용히 넘기지 않는다.** 넘기면 CI의 오타 하나가
     * "프로파일 0개"가 되고, 게이트는 아무것도 검사하지 않으면서 초록이 된다.
     *
     * **같은 `(vendor, model)`이 두 곳에 있으면 거절한다.** 문서의 동일성이
     * 그 쌍이므로(§8.3) 조용히 하나가 이기면 다른 하나는 영영 검사되지 않는다.
     */
    private fun readAll(dirs: List<Path>): Parsed {
        require(dirs.isNotEmpty()) { "프로파일 디렉터리가 하나도 지정되지 않았다" }
        dirs.forEach {
            require(Files.isDirectory(it)) { "프로파일 디렉터리가 없다: $it" }
        }

        val parsed = dirs.map(::readProfiles)
        val documents = parsed.flatMap { it.documents }

        documents.groupBy { ProfileKey(it.vendor, it.model) }
            .filterValues { it.size > 1 }
            .forEach { (key, duplicates) ->
                throw IllegalArgumentException(
                    "같은 기종이 두 곳에 있다: $key — ${duplicates.map { it.path }}",
                )
            }

        return Parsed(documents, parsed.flatMap { it.malformed })
    }

    private fun readProfiles(dir: Path): Parsed {
        if (!Files.isDirectory(dir)) return Parsed(emptyList(), emptyList())

        val documents = mutableListOf<ProfileDocument>()
        val malformed = mutableListOf<MalformedProfile>()

        Files.list(dir).use { stream ->
            stream.asSequence()
                .filter { it.isRegularFile() && it.extension == "json" }
                .sortedBy { it.name }
                .forEach { file ->
                    // repoRoot 밖(임시 디렉터리·다른 드라이브)이면 relativize가
                    // 던진다. 절대 경로가 되는 것이 예외로 죽는 것보다 낫다.
                    val path = runCatching {
                        repoRoot.relativize(file.toAbsolutePath()).toString()
                    }.getOrElse { file.toString() }

                    // readString이 parse의 runCatching 밖에 있으면 UTF-8이
                    // 아닌 파일에서 CLI가 스택트레이스로 죽는다 — 입력 수집은
                    // GateRunner의 예외 보호 밖이다.
                    runCatching { Files.readString(file) }
                        .mapCatching { ProfileDocument.parse(path, it).getOrThrow() }
                        .onSuccess { documents += it }
                        .onFailure {
                            malformed += MalformedProfile(path, it.message ?: "파싱 실패")
                        }
                }
        }

        return Parsed(documents, malformed)
    }
}
