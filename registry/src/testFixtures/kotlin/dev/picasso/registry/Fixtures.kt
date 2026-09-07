package dev.picasso.registry

import dev.picasso.registry.revision.RevisionValidator
import java.nio.file.Files
import java.nio.file.Path

/** 시험이 쓰는 문서와 게이트 입력. */
object Fixtures {

    private val repoRoot: Path = Path.of("..").toAbsolutePath().normalize()

    fun schema(): String =
        Files.readString(repoRoot.resolve("profile/schema/capability-profile.schema.json"))

    fun descriptor(): ByteArray = checkNotNull(
        Fixtures::class.java.getResourceAsStream("/picasso.desc"),
    ) { "계약 디스크립터가 클래스패스에 없다 — contracts가 자원으로 안 냈다" }
        .use { it.readBytes() }

    fun validator(): RevisionValidator = RevisionValidator(schema(), descriptor())

    private val fixtureRaw: String by lazy {
        Files.readString(repoRoot.resolve("profile/fixtures/minimal.json")).replace("\r\n", "\n")
    }

    fun good(revision: Int = 1, model: String? = null): String {
        var text = fixtureRaw.replace("\"revision\": 1,", "\"revision\": $revision,")
        check(text != fixtureRaw || revision == 1) { "revision 치환이 안 됐다" }
        if (model != null) {
            val before = text
            text = text.replace("\"model\": \"minimal\"", "\"model\": \"$model\"")
            check(text != before) { "model 치환이 안 됐다" }
        }
        return text
    }

    /** 스키마의 `error_type` enum에 없는 값. 게이트 검사 3번이 막는다. */
    fun badErrorType(): String {
        val text = fixtureRaw.replace("\"LOCALIZATION_LOST\"", "\"NOT_A_REGISTERED_ERROR\"")
        check(text != fixtureRaw) { "치환이 안 됐다" }
        return text
    }

    /**
     * 게이트의 음성 케이스 중 **문서 하나만으로 판정되는 것들**.
     *
     * 계약(proto)을 깨는 케이스는 여기 안 온다 — 레지스트리가 받는 것은
     * 프로파일 문서뿐이다(§11.1).
     *
     * **`06-*`도 뺀다.** 축소 판정은 기준선(지금 `ACTIVE`인 개정판)이 있어야
     * 성립하는데 이 청크에는 활성화가 없다. 넣으면 "기준선이 없어 신규로
     * 통과"한 것을 "레지스트리가 못 막았다"로 읽게 된다 — 시험이 없는 결함을
     * 만들어 낸다. **그 파리티는 Task 3(활성화)에서 온다.**
     */
    fun negativeProfiles(): List<Pair<String, String>> {
        val root = repoRoot.resolve("gate/negative")
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { dirs ->
            dirs.sorted().toList()
                .filterNot { it.fileName.toString().startsWith("06-") }
                .flatMap { dir ->
                    // 케이스는 저장소를 덮어쓰는 오버레이다. 프로파일 문서는
                    // 픽스처와 실기종 두 곳에 놓인다.
                    listOf("overlay/profile/fixtures", "overlay/profile/profiles")
                        .map { dir.resolve(it) }
                        .filter { Files.isDirectory(it) }
                        .flatMap { overlay ->
                            Files.list(overlay).use { files ->
                                files.filter { it.toString().endsWith(".json") }.toList()
                                    .map { dir.fileName.toString() to Files.readString(it) }
                            }
                        }
                }
        }
    }
}
