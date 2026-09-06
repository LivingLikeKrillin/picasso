package dev.picasso.gate.cli

import dev.picasso.gate.GateChecks
import dev.picasso.gate.Resource
import dev.picasso.profile.ProfileKey
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    fun `검사 여덟이 전부 목록에 있고 id가 겹치지 않는다`() {
        // §11.2는 아홉이다. 9번은 검사가 아니라 음성 하네스라 목록에 없다 —
        // 그 사실이 여기 적혀 있어야 "여덟이면 다 됐다"로 읽히지 않는다.
        val ids = GateChecks.all().map { it.id }
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8"), ids.sorted())
    }

    @Test
    fun `디렉터리의 프로파일을 전부 읽는다`() {
        val root = repo()
        val dir = root.resolve("profile/fixtures").createDirectories()
        dir.resolve("a.json").writeText(profile("acme", "r1"))
        dir.resolve("b.json").writeText(profile("acme", "r2"))
        // JSON이 아닌 것은 무시한다 — README를 프로파일로 읽으면 안 된다.
        dir.resolve("README.md").writeText("# 프로파일")

        val input = InputCollector(root).collect(profileDirs = listOf(dir))

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

        val input = InputCollector(root).collect(profileDirs = listOf(dir))

        assertEquals(1, input.profiles.size)
        assertEquals(1, input.malformed.size)
        assertTrue(input.malformed.single().path.endsWith("bad.json"))
    }

    @Test
    fun `없는 프로파일 디렉터리는 조용히 넘기지 않는다`() {
        // 예전에는 빈 목록이 되고 --require 가 그것을 잡아 주기를 기대했다.
        // 그러면 --require 없이 도는 로컬 실행에서 **오타 하나가 깨끗한
        // PASS**가 된다 — 게이트가 아무것도 검사하지 않으면서 초록이다.
        val root = repo()
        val error = assertFailsWith<IllegalArgumentException> {
            InputCollector(root).collect(profileDirs = listOf(root.resolve("없는디렉터리")))
        }
        assertTrue("없는디렉터리" in error.message!!, error.message!!)
    }

    @Test
    fun `디렉터리 여럿에서 모은다`() {
        // §7.4가 픽스처와 실제 기종을 다른 디렉터리에 둔다. 하나만 보면
        // 나머지가 검사 3·4·6 밖에 놓인다.
        val root = repo()
        val fixtures = root.resolve("profile/fixtures").createDirectories()
        val profiles = root.resolve("profile/profiles").createDirectories()
        fixtures.resolve("a.json").writeText(profile("acme", "fixture"))
        profiles.resolve("b.json").writeText(profile("acme", "humanoid"))
        profiles.resolve("c.json").writeText(profile("acme", "quadruped"))

        val both = InputCollector(root).collect(profileDirs = listOf(fixtures, profiles))
        assertEquals(3, both.profiles.size)

        // 시험이 비지 않았는지 — 한쪽만 주면 나머지가 안 잡힌다.
        assertEquals(1, InputCollector(root).collect(profileDirs = listOf(fixtures)).profiles.size)
        assertEquals(2, InputCollector(root).collect(profileDirs = listOf(profiles)).profiles.size)
    }

    @Test
    fun `두 디렉터리에 같은 기종이 있으면 거절한다`() {
        // 문서의 동일성이 (vendor, model)이므로(§8.3) 조용히 하나가 이기면
        // 다른 하나는 영영 검사되지 않는다.
        val root = repo()
        val one = root.resolve("one").createDirectories()
        val two = root.resolve("two").createDirectories()
        one.resolve("a.json").writeText(profile("acme", "same"))
        two.resolve("b.json").writeText(profile("acme", "same"))

        val error = assertFailsWith<IllegalArgumentException> {
            InputCollector(root).collect(profileDirs = listOf(one, two))
        }
        assertTrue("acme/same" in error.message!!, error.message!!)
    }

    @Test
    fun `기준선도 여럿에서 모은다`() {
        val root = repo()
        val dir = root.resolve("p").createDirectories()
        dir.resolve("a.json").writeText(profile("acme", "r1"))
        val baseA = root.resolve("base-a").createDirectories()
        val baseB = root.resolve("base-b").createDirectories()
        baseA.resolve("a.json").writeText(profile("acme", "r1"))
        baseB.resolve("b.json").writeText(profile("acme", "r2"))

        val input = InputCollector(root).collect(
            profileDirs = listOf(dir),
            baselineDirs = listOf(baseA, baseB),
        )
        assertEquals(2, input.baseline!!.size)
    }

    @Test
    fun `기준선 디렉터리 하나라도 없으면 기준선이 없는 것이다`() {
        // 일부만 읽고 빈 맵을 만들면 그쪽 프로파일이 전부 "신규"가 되어
        // 파괴 검사가 조용히 통과한다.
        val root = repo()
        val dir = root.resolve("p").createDirectories()
        dir.resolve("a.json").writeText(profile("acme", "r1"))
        val base = root.resolve("base").createDirectories()
        base.resolve("a.json").writeText(profile("acme", "r1"))

        val input = InputCollector(root).collect(
            profileDirs = listOf(dir),
            baselineDirs = listOf(base, root.resolve("없는것")),
        )
        assertNull(input.baseline, "기준선을 반쯤 읽고 있다")
    }

    @Test
    fun `기준선 디렉터리를 기종 좌표로 키잉한다`() {
        // 파일 이름이 아니라 (vendor, model)이다 — 리네임에 견디려면 그래야 한다.
        val root = repo()
        val head = root.resolve("head").createDirectories()
        head.resolve("x.json").writeText(profile("acme", "r1"))
        val base = root.resolve("base").createDirectories()
        base.resolve("전혀-다른-이름.json").writeText(profile("acme", "r1"))

        val input = InputCollector(root).collect(profileDirs = listOf(head), baselineDirs = listOf(base))

        assertEquals(setOf(ProfileKey("acme", "r1")), input.baseline!!.keys)
    }

    @Test
    fun `기준선 디렉터리를 안 주면 null이고 비어 있으면 빈 맵이다`() {
        // null = 출처에 못 닿음(건너뜀), 빈 맵 = 신규(통과). 뜻이 다르다.
        val root = repo()
        val head = root.resolve("head").createDirectories()
        head.resolve("x.json").writeText(profile("acme", "r1"))

        assertEquals(null, InputCollector(root).collect(profileDirs = listOf(head)).baseline)

        val emptyBase = root.resolve("empty-base").createDirectories()
        assertEquals(
            emptyMap(),
            InputCollector(root).collect(profileDirs = listOf(head), baselineDirs = listOf(emptyBase)).baseline,
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
            InputCollector(root).collect(profileDirs = listOf(head), baselineDirs = listOf(base))
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

        val input = InputCollector(root).collect(profileDirs = listOf(dir))
        assertEquals(1, input.malformed.size)
    }

    @Test
    fun `없는 스키마나 디스크립터를 조용히 넘기지 않는다`() {
        val root = repo()
        val head = root.resolve("head").createDirectories()
        head.resolve("x.json").writeText(profile("acme", "r1"))

        val input = InputCollector(root).collect(
            profileDirs = listOf(head),
            schemaFile = root.resolve("없음.json"),
            descriptorFile = root.resolve("없음.binpb"),
        )

        assertTrue(Resource.PROFILE_SCHEMA !in input.available())
        assertTrue(Resource.CONTRACT_DESCRIPTOR !in input.available())
    }
}
