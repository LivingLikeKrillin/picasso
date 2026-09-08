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

    /**
     * **시맨틱 파라미터를 하나도 안 쓰는 기종의 프로파일.**
     *
     * `move_relative` 하나만 선언하므로 ADR 35의 등록 대상이 비어 있고,
     * 그래서 `SiteNameStatus.NOT_REQUIRED`가 되는 유일한 경우다.
     *
     * **픽스처를 새로 짓지 않고 실물 프로파일을 가리킨다.** 지어내면 "그런
     * 기종이 있다"가 시험 안의 가정이 되는데, 실제로 G1이 그 기종이고 그
     * 사실이 `profile/distance/unitree-g1.json`에 재어져 있다. 언젠가 G1이
     * 시맨틱 스킬을 선언하면 이 시험이 깨지고, **그 깨짐이 옳다** — 전제가
     * 바뀐 것이지 시험이 낡은 것이 아니다.
     */
    fun moveOnly(): String =
        Files.readString(repoRoot.resolve("profile/profiles/unitree-g1.json")).replace("\r\n", "\n")

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

    /**
     * 스킬 하나를 **뺀** 문서. 게이트 검사 6번이 "스킬 제거"로 분류하는
     * 축소다(§11.2 6번).
     *
     * **문자열 치환이 아니라 파싱해서 뺀다.** 스킬 객체는 여러 줄이라
     * 치환으로 지우면 픽스처 서식이 바뀔 때 조용히 아무것도 안 지운 문서가
     * 나오고, 그러면 "축소를 안 막았다"가 아니라 "축소가 아니었다"가 된다 —
     * 초록인데 아무것도 안 본 시험이다.
     *
     * `durations`도 함께 뺀다. 투영 밖이라 소견을 안 만들지만(§11.2), 없는
     * 스킬의 소요시간이 남아 있으면 문서가 자기모순이다.
     */
    fun shrunk(revision: Int = 2, skill: String = "navigate_to"): String {
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val root = mapper.readTree(good(revision = revision))
            as com.fasterxml.jackson.databind.node.ObjectNode
        var removed = 0
        listOf("skills", "durations").forEach { field ->
            val array = root.get(field)
                as? com.fasterxml.jackson.databind.node.ArrayNode ?: return@forEach
            val keep = mapper.createArrayNode()
            array.forEach { node ->
                if (node.get("skill_type")?.asText() == skill) removed++ else keep.add(node)
            }
            root.set<com.fasterxml.jackson.databind.JsonNode>(field, keep)
        }
        check(removed >= 2) { "$skill 을 픽스처에서 못 뺐다($removed) — 축소가 안 만들어진다" }
        return mapper.writeValueAsString(root)
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
