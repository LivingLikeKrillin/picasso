package dev.picasso.gate.cli

import dev.picasso.gate.buf.BufRunner
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
        profileDir: Path,
        schemaFile: Path? = null,
        descriptorFile: Path? = null,
        baselineDir: Path? = null,
        contractBaseline: String? = null,
        buf: BufRunner? = null,
    ): GateInput {
        val head = readProfiles(profileDir)

        return GateInput(
            profiles = head.documents,
            malformed = head.malformed,
            schemaJson = schemaFile?.takeIf { it.isRegularFile() }?.let(Files::readString),
            descriptor = descriptorFile?.takeIf { it.isRegularFile() }?.let(Files::readAllBytes),
            repoRoot = repoRoot,
            baseline = baselineDir?.let(::readBaseline),
            contractBaseline = contractBaseline,
            buf = buf,
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
    private fun readBaseline(dir: Path): Map<ProfileKey, String>? {
        if (!Files.isDirectory(dir)) return null
        val parsed = readProfiles(dir)
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
