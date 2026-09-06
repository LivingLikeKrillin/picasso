package dev.picasso.mimic.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §10.2의 기동 순서. **기동 거부 메시지가 유일한 산출물이므로** 그것이
 * 실제로 나오는지를 본다.
 */
class MimicCliTest {

    private val schema = Path.of("..", "profile", "schema", "capability-profile.schema.json")
        .normalize().toString()
    private val minimal = Path.of("..", "profile", "fixtures", "minimal.json")
        .normalize().toString()

    private class Run(val code: Int, val out: String, val err: String)

    /** 기동한 서버를 반드시 닫는다 — 안 닫으면 시험이 포트를 흘린다. */
    private fun run(vararg args: String): Run {
        val out = StringBuilder()
        val err = StringBuilder()
        val cli = MimicCli()
        try {
            val code = cli.run(arrayOf(*args), out, err)
            return Run(code, out.toString(), err.toString())
        } finally {
            cli.started?.server?.shutdown()
        }
    }

    @Test
    fun `기체 하나로 기동한다`() {
        val result = run("--robot", "r1=$minimal", "--schema", schema, "--port", "0")
        assertEquals(0, result.code, result.err)
        assertTrue("r1" in result.out, result.out)
    }

    @Test
    fun `--port 0이면 실제 포트를 알려준다`() {
        // 0을 그대로 찍으면 호출자가 붙을 수 없다.
        val result = run("--robot", "r1=$minimal", "--schema", schema, "--port", "0")
        val port = Regex("""포트 (\d+)""").find(result.out)?.groupValues?.get(1)?.toInt()
        assertTrue(port != null && port > 0, "실제 포트를 안 알려준다: ${result.out}")
    }

    @Test
    fun `스키마를 통과하지 못하는 프로파일이면 기동하지 않는다`() {
        // §10.2 — 능력을 모르는 채 표면을 열면 소비자가 없는 능력을 믿는다.
        val broken = Files.createTempFile("broken", ".json")
        Files.writeString(broken, """{"schema_version": "1.0.0", "vendor": "x"}""")
        try {
            val result = run("--robot", "r1=$broken", "--schema", schema)
            assertEquals(2, result.code)
            assertTrue("기동 거부" in result.err, result.err)
        } finally {
            Files.deleteIfExists(broken)
        }
    }

    @Test
    fun `기체 하나가 거부되면 전부 기동하지 않는다`() {
        // 부분 기동은 소비자에게 거짓말이다 — 없는 기체를 물으면 NOT_FOUND가
        // 나오는데 "설정에 없다"인지 "프로파일이 깨졌다"인지 구별할 수 없다.
        val result = run(
            "--robot", "r1=$minimal",
            "--robot", "r2=/nowhere/missing.json",
            "--schema", schema,
        )
        assertEquals(2, result.code)
        assertTrue(result.out.isBlank(), "일부라도 기동했다: ${result.out}")
    }

    @Test
    fun `잘못된 인자를 통째로 확인한다`() {
        // 하나만 보면 나머지가 침묵한다.
        val cases = mapOf(
            "--robot 문법" to arrayOf("--robot", "r1", "--schema", schema),
            "--robot 빈 쪽" to arrayOf("--robot", "=$minimal", "--schema", schema),
            "--robot 중복" to arrayOf("--robot", "r1=$minimal", "--robot", "r1=$minimal", "--schema", schema),
            "--robot 없음" to arrayOf("--schema", schema),
            "--schema 없음" to arrayOf("--robot", "r1=$minimal"),
            "--port 비정수" to arrayOf("--robot", "r1=$minimal", "--schema", schema, "--port", "x"),
            "--clock 미지값" to arrayOf("--robot", "r1=$minimal", "--schema", schema, "--clock", "fast"),
            "모르는 인자" to arrayOf("--robot", "r1=$minimal", "--schema", schema, "--nope", "1"),
            "값 없는 인자" to arrayOf("--robot", "r1=$minimal", "--schema"),
        )
        assertEquals(9, cases.size)

        cases.forEach { (name, args) ->
            val result = run(*args)
            assertEquals(2, result.code, "$name 이 통과했다: ${result.out}")
            assertTrue("사용법" in result.err, "$name 에 사용법이 없다: ${result.err}")
        }
    }
}
