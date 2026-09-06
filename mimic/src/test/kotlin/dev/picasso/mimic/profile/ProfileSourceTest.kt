package dev.picasso.mimic.profile

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProfileSourceTest {

    private fun repoFile(vararg parts: String): Path = Path.of("..", *parts).normalize()

    private val schema: Path = repoFile("profile", "schema", "capability-profile.schema.json")
    private val fixture: Path = repoFile("profile", "fixtures", "minimal.json")

    @Test
    fun `픽스처를 읽는다`() {
        val loaded = FileProfileSource(schema).load(fixture)
        assertEquals("fixture", loaded.vendor)
        assertEquals("minimal", loaded.model)
    }

    @Test
    fun `스키마를 통과하지 못하면 기동을 거부한다`() {
        // §10.2 — 능력을 모르는 채 표면을 열지 않는다.
        val broken = Files.createTempFile("bad", ".json")
        Files.writeString(broken, """{"vendor":"v","model":"m"}""")

        val e = assertFailsWith<ProfileRejected> { FileProfileSource(schema).load(broken) }
        assertTrue(e.message!!.contains("스키마"))
        assertTrue(e.findings.isNotEmpty(), "무엇이 틀렸는지 말하지 않는다")
    }

    @Test
    fun `JSON이 아니면 기동을 거부한다`() {
        val broken = Files.createTempFile("bad", ".json")
        Files.writeString(broken, "{ 이건 JSON이 아니다")
        assertFailsWith<ProfileRejected> { FileProfileSource(schema).load(broken) }
    }

    @Test
    fun `파일이 없으면 기동을 거부한다`() {
        assertFailsWith<ProfileRejected> {
            FileProfileSource(schema).load(Path.of("없는파일.json"))
        }
    }

    @Test
    fun `스키마 소견이 JVM 로케일에 흔들리지 않는다`() {
        // 게이트 검사 3번과 같은 이유다. 기동 거부 메시지가 환경에 따라
        // 달라지면 harness가 그것을 문자열로 다룰 수 없다.
        val broken = Files.createTempFile("bad", ".json")
        Files.writeString(broken, """{"vendor":"v","model":"m"}""")

        fun findingsUnder(locale: Locale): List<String> {
            val saved = Locale.getDefault()
            return try {
                Locale.setDefault(locale)
                assertFailsWith<ProfileRejected> { FileProfileSource(schema).load(broken) }.findings
            } finally {
                Locale.setDefault(saved)
            }
        }

        assertEquals(findingsUnder(Locale.KOREAN), findingsUnder(Locale.ENGLISH))
    }
}
