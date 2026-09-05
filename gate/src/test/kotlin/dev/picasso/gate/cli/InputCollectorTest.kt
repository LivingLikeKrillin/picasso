package dev.picasso.gate.cli

import dev.picasso.gate.GateChecks
import dev.picasso.gate.Resource
import dev.picasso.gate.input.ProfileKey
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InputCollectorTest {

    private fun repo(): Path = Files.createTempDirectory("picasso-collect")

    private fun profile(vendor: String, model: String) = """
        {
          "schema_version": "1.0.0", "vendor": "$vendor", "model": "$model", "revision": 1,
          "skills": [
            { "skill_type": "navigate_to", "major": 1, "minor": 0,
              "pause_support": "YES", "cancel_support": "YES",
              "parameters": [ { "key": "location", "value_type": "STRING", "optional": false } ] }
          ],
          "publish_interval": { "min_seconds": 1, "max_seconds": 30 },
          "protocol_limits": { "max_string_length": 256, "max_array_length": 32 },
          "exclusive_control_required": false, "replay_buffer_size": 16
        }
    """.trimIndent()

    @Test
    fun `검사 여섯이 전부 목록에 있고 id가 겹치지 않는다`() {
        val ids = GateChecks.all().map { it.id }
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), ids.sorted())
    }

    @Test
    fun `디렉터리의 프로파일을 전부 읽는다`() {
        val root = repo()
        val dir = root.resolve("profile/fixtures").createDirectories()
        dir.resolve("a.json").writeText(profile("acme", "r1"))
        dir.resolve("b.json").writeText(profile("acme", "r2"))
        // JSON이 아닌 것은 무시한다 — README를 프로파일로 읽으면 안 된다.
        dir.resolve("README.md").writeText("# 프로파일")

        val input = InputCollector(root).collect(profileDir = dir)

        assertEquals(2, input.profiles.size)
        assertTrue(input.malformed.isEmpty())
    }

    @Test
    fun `깨진 프로파일을 버리지 않고 malformed로 싣는다`() {
        // 버리면 검사 3번이 아무 말도 못 한다.
        val root = repo()
        val dir = root.resolve("p").createDirectories()
        dir.resolve("good.json").writeText(profile("acme", "r1"))
        dir.resolve("bad.json").writeText("{ 이건 JSON이 아니다")

        val input = InputCollector(root).collect(profileDir = dir)

        assertEquals(1, input.profiles.size)
        assertEquals(1, input.malformed.size)
        assertTrue(input.malformed.single().path.endsWith("bad.json"))
    }

    @Test
    fun `프로파일 디렉터리가 없으면 문서 자원이 없다`() {
        // 조용히 빈 목록을 만들면 required 판정이 그것을 잡아야 한다.
        val root = repo()
        val input = InputCollector(root).collect(profileDir = root.resolve("없는디렉터리"))
        assertTrue(Resource.PROFILE_DOCUMENT !in input.available())
    }

    @Test
    fun `기준선 디렉터리를 기종 좌표로 키잉한다`() {
        // 파일 이름이 아니라 (vendor, model)이다 — 리네임에 견디려면 그래야 한다.
        val root = repo()
        val head = root.resolve("head").createDirectories()
        head.resolve("x.json").writeText(profile("acme", "r1"))
        val base = root.resolve("base").createDirectories()
        base.resolve("전혀-다른-이름.json").writeText(profile("acme", "r1"))

        val input = InputCollector(root).collect(profileDir = head, baselineDir = base)

        assertEquals(setOf(ProfileKey("acme", "r1")), input.baseline!!.keys)
    }

    @Test
    fun `기준선 디렉터리를 안 주면 null이고 비어 있으면 빈 맵이다`() {
        // null = 출처에 못 닿음(건너뜀), 빈 맵 = 신규(통과). 뜻이 다르다.
        val root = repo()
        val head = root.resolve("head").createDirectories()
        head.resolve("x.json").writeText(profile("acme", "r1"))

        assertEquals(null, InputCollector(root).collect(profileDir = head).baseline)

        val emptyBase = root.resolve("empty-base").createDirectories()
        assertEquals(
            emptyMap(),
            InputCollector(root).collect(profileDir = head, baselineDir = emptyBase).baseline,
        )
    }

    @Test
    fun `깨진 기준선을 빈 맵으로 접지 않는다`() {
        // 접으면 Resource.BASELINE이 "있다"로 잡히고 검사 6이 모든 프로파일을
        // "신규"로 분류한다 — 경고 한 줄 없이 완전한 PASS가 되고 스킬 전체
        // 삭제가 그냥 지나간다. Check06의 parseBaseline 방어는 맵에 들어온
        // 것만 보므로 여기서 새면 도달조차 못 한다.
        val root = repo()
        val head = root.resolve("head").createDirectories()
        head.resolve("x.json").writeText(profile("acme", "r1"))
        val base = root.resolve("base").createDirectories()
        base.resolve("broken.json").writeText("{ 이건 JSON이 아니다")

        val e = assertFailsWith<IllegalArgumentException> {
            InputCollector(root).collect(profileDir = head, baselineDir = base)
        }
        assertTrue(e.message!!.contains("기준선"))
    }

    @Test
    fun `UTF-8이 아닌 파일이 게이트를 죽이지 않는다`() {
        // readString이 parse의 runCatching 밖에서 평가되면 여기서 예외가 나고,
        // 그것은 GateRunner의 보호 밖이라 CLI가 스택트레이스로 죽는다.
        val root = repo()
        val dir = root.resolve("p").createDirectories()
        Files.write(dir.resolve("utf16.json"), profile("acme", "r1").toByteArray(Charsets.UTF_16))

        val input = InputCollector(root).collect(profileDir = dir)
        assertEquals(1, input.malformed.size)
    }

    @Test
    fun `없는 스키마나 디스크립터를 조용히 넘기지 않는다`() {
        val root = repo()
        val head = root.resolve("head").createDirectories()
        head.resolve("x.json").writeText(profile("acme", "r1"))

        val input = InputCollector(root).collect(
            profileDir = head,
            schemaFile = root.resolve("없음.json"),
            descriptorFile = root.resolve("없음.binpb"),
        )

        assertTrue(Resource.PROFILE_SCHEMA !in input.available())
        assertTrue(Resource.CONTRACT_DESCRIPTOR !in input.available())
    }
}
