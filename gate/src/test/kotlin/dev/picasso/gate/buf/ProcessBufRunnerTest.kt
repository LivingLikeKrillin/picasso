package dev.picasso.gate.buf

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessBufRunnerTest {

    // buf 자체는 Docker가 있어야 돌므로 여기서 부르지 않는다. 프로세스 배선
    // (인자 전달·종료코드·출력 포획·타임아웃)만 확인하며, 그것은 어느 머신에나
    // 있는 JVM으로 확인할 수 있다. 진짜 buf 배선은 Chunk 8의 음성 하네스와
    // CI가 본다.
    private val java: String =
        Path.of(System.getProperty("java.home"), "bin", "java").toString()

    private fun runner(timeout: Long = 300) = ProcessBufRunner(listOf(java), timeout)

    @Test
    fun `종료코드 0과 출력을 돌려준다`() {
        val r = runner().run(listOf("-version"), Path.of("."))
        assertEquals(0, r.exitCode)
        assertTrue(r.output.contains("version"), "출력을 못 잡았다: ${r.output}")
    }

    @Test
    fun `실패한 종료코드를 그대로 돌려준다`() {
        // 0이 아닌 것을 0으로 접으면 검사 1·2번이 통과로 샌다.
        val r = runner().run(listOf("--그런옵션없다"), Path.of("."))
        assertTrue(r.exitCode != 0, "실패를 0으로 접었다")
    }

    @Test
    fun `표준오류도 출력에 담는다`() {
        // buf는 위반을 stderr로 낸다. 놓치면 소견이 비어 사람이 못 고친다.
        val r = runner().run(listOf("--그런옵션없다"), Path.of("."))
        assertTrue(r.output.isNotBlank(), "stderr를 버렸다")
    }

    @Test
    fun `실행기가 없으면 예외가 아니라 실패로 돌아온다`() {
        // GateRunner가 예외를 Failed로 접긴 하지만, 여기서 소견을 만들 수
        // 있으면 사람이 "buf를 못 찾았다"를 바로 읽는다.
        val r = ProcessBufRunner(listOf("picasso-존재하지-않는-실행기"))
            .run(listOf("lint"), Path.of("."))
        assertTrue(r.exitCode != 0)
        assertTrue(r.output.isNotBlank())
    }

    @Test
    fun `타임아웃이 실제로 걸린다`() {
        // readText()로 EOF까지 읽은 뒤 waitFor(timeout)을 부르면 타임아웃이
        // 사문이 된다 — readText()가 돌아왔다는 건 자식이 이미 끝났다는 뜻이다.
        // 실측으로 확인된 결함이고, 이 시험이 그것을 막는다.
        val dir = Files.createTempDirectory("picasso-sleeper")
        val src = dir.resolve("Sleeper.java")
        Files.writeString(
            src,
            "public class Sleeper{public static void main(String[] a)throws Exception{Thread.sleep(60000);}}",
        )

        val started = System.nanoTime()
        val r = runner(timeout = 2).run(listOf(src.toString()), dir)
        val seconds = (System.nanoTime() - started) / 1_000_000_000

        assertTrue(seconds < 20, "타임아웃이 사문이다: ${seconds}초 걸렸다")
        assertTrue(r.exitCode != 0, "타임아웃인데 성공으로 돌아왔다")
    }
}
