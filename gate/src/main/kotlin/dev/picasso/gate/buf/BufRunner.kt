package dev.picasso.gate.buf

import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** `buf` 한 번 실행의 결과. stdout과 stderr를 합쳐 담는다 — buf는 위반을 stderr로 낸다. */
data class BufResult(val exitCode: Int, val output: String)

/**
 * `buf` 실행의 경계.
 *
 * 검사가 `ProcessBuilder`를 직접 만들면 시험할 수 없다. Docker가 있는
 * 머신에서만 도는 시험은 없는 머신에서 조용히 건너뛰게 되고, 그것이 이
 * 모듈이 막으려는 바로 그 실패다.
 */
fun interface BufRunner {
    /** @param workingDir buf 모듈이 있는 디렉터리(보통 `contracts/`) */
    fun run(args: List<String>, workingDir: Path): BufResult
}

/**
 * 진짜 `buf`를 부른다.
 *
 * @param command 실행기와 그 앞에 붙는 것들. 인자는 [run]이 뒤에 붙인다.
 *
 *   **절대 경로여야 한다.** `workingDir`가 `contracts/`이므로 상대 경로는
 *   그 안에서 해석돼 빗나간다(실측: `bash tools/buf` → exit 127,
 *   `contracts/tools/buf`를 찾는다).
 *
 *   - 리눅스 CI: `listOf(repoRoot.resolve("tools/buf").toString())`
 *   - Windows: `listOf("<Git 설치 경로>/bin/bash.exe", repoRoot.resolve("tools/buf").toString())`
 *     `"bash"`만 쓰면 PATH에서 **WSL의 `System32\bash.exe`**가 잡혀 Windows
 *     경로를 못 번역한다(실측).
 *
 * @param timeoutSeconds Docker가 이미지를 처음 당기면 오래 걸린다.
 */
class ProcessBufRunner(
    private val command: List<String>,
    private val timeoutSeconds: Long = 300,
) : BufRunner {

    override fun run(args: List<String>, workingDir: Path): BufResult =
        try {
            val process = ProcessBuilder(command + args)
                .directory(workingDir.toFile())
                // buf는 위반을 stderr로 낸다. 버리면 소견이 비어 사람이 못 고친다.
                .redirectErrorStream(true)
                .start()

            // 자식의 stdin에 EOF를 준다. 안 닫으면 입력을 기다리는 자식이
            // 영원히 산다(실측: 10분 넘게 정지). docker run이 자격증명
            // 프롬프트를 띄우면 CI 잡이 게이트에서 매달린다.
            process.outputStream.close()

            // 출력을 본 스레드에서 EOF까지 읽으면 waitFor(timeout)이 자식이
            // 끝난 뒤에야 불려 timeoutSeconds가 사문이 된다
            // (실측: 2초 선언, 12.9초 소요).
            val sink = StringBuilder()
            val pump = Thread {
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { sink.appendLine(it) }
                }
            }.apply { isDaemon = true; start() }

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                pump.join(PUMP_DRAIN_MS)
                BufResult(
                    TIMED_OUT,
                    "buf가 ${timeoutSeconds}초 안에 끝나지 않았다: ${command + args}\n$sink",
                )
            } else {
                pump.join(PUMP_DRAIN_MS)
                BufResult(process.exitValue(), sink.toString())
            }
        } catch (t: Exception) {
            // 예외로 던지면 GateRunner가 "검사가 예외로 중단됐다"만 남긴다.
            // 여기서 결과로 만들면 사람이 무엇을 못 찾았는지 바로 읽는다.
            BufResult(
                NOT_RUN,
                "buf를 실행하지 못했다: ${command + args}\n${t.message ?: t::class.simpleName}",
            )
        }

    private companion object {
        const val TIMED_OUT = -2
        const val NOT_RUN = -1
        const val PUMP_DRAIN_MS = 5_000L
    }
}
