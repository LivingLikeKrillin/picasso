package dev.picasso.mimic.cli

import dev.picasso.mimic.RobotInstance
import dev.picasso.mimic.engine.Clock
import dev.picasso.mimic.engine.RealClock
import dev.picasso.mimic.engine.VirtualClock
import dev.picasso.mimic.profile.FileProfileSource
import dev.picasso.mimic.profile.ProfileRejected
import dev.picasso.mimic.transport.MimicServer
import dev.picasso.mimic.transport.RobotRegistry
import io.grpc.ServerBuilder
import java.nio.file.Path
import java.time.Instant
import kotlin.system.exitProcess

/**
 * `mimic --robot <id>=<profile.json> [--robot ...] --schema <path> [--port 0]`
 *
 * `--robot`을 **하나의 옵션**으로 둔다. `--profile`과 `--robot-id`를 각각
 * 반복시키면 짝이 어긋난 채로도 기동한다.
 */
class MimicCli {

    class Started(val server: MimicServer, val robotIds: Set<String>)

    /** 기동한 서버. 프로세스가 소유하며, 시험은 이것으로 닫는다. */
    var started: Started? = null
        private set

    /**
     * **`exitProcess`를 안에 두지 않는다.** 그러면 종료 코드를 시험할 때
     * 시험 JVM이 죽는다. `main`이 감싼다.
     */
    fun run(args: Array<String>, out: Appendable, err: Appendable): Int {
        val robots = mutableMapOf<String, Path>()
        var schema: Path? = null
        var port = 0
        var virtual = false
        var seed = 0L

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            val value = args.getOrNull(i + 1)
            if (arg.startsWith("--") && value == null) return usage(err, "$arg 에 값이 없다")
            when (arg) {
                "--robot" -> {
                    val spec = value!!
                    val parts = spec.split("=", limit = 2)
                    if (parts.size != 2 || parts.any { it.isBlank() }) {
                        return usage(err, "--robot 은 <id>=<profile.json> 형식이다: '$spec'")
                    }
                    // 조용히 덮으면 프로파일 하나가 사라진 채 기동한다.
                    if (robots.put(parts[0], Path.of(parts[1])) != null) {
                        return usage(err, "같은 robot_id를 두 번 줬다: ${parts[0]}")
                    }
                    i += 2
                }
                "--schema" -> { schema = Path.of(value!!); i += 2 }
                "--port" -> {
                    port = value!!.toIntOrNull() ?: return usage(err, "--port 가 정수가 아니다")
                    i += 2
                }
                "--clock" -> {
                    virtual = when (val mode = value!!) {
                        "virtual" -> true
                        "real" -> false
                        else -> return usage(err, "--clock 은 real 또는 virtual 이다: '$mode'")
                    }
                    i += 2
                }
                "--seed" -> {
                    seed = value!!.toLongOrNull() ?: return usage(err, "--seed 가 정수가 아니다")
                    i += 2
                }
                else -> return usage(err, "모르는 인자다: '$arg'")
            }
        }

        if (robots.isEmpty()) return usage(err, "--robot 이 하나도 없다")
        val schemaPath = schema ?: return usage(err, "--schema 가 없다")

        val running = start(robots, schemaPath, port, virtual, seed, err) ?: return 2
        started = running
        out.appendLine("mimic 이 포트 ${running.server.port} 에서 ${running.robotIds.sorted()} 를 호스팅한다")
        return 0
    }

    /**
     * §10.2 — 프로파일 로드 → 스키마 검증(실패 시 **기동 거부**) → 투영 →
     * 상태머신 → `session_id` → 포트 개방.
     *
     * **기체 하나가 거부되면 전부 기동하지 않는다.** 부분 기동은 소비자에게
     * 거짓말이다 — 없는 기체를 물으면 `NOT_FOUND`가 나오는데, 그것이
     * "설정에 없다"인지 "프로파일이 깨졌다"인지 구별할 방법이 없다.
     */
    fun start(
        robots: Map<String, Path>,
        schema: Path,
        port: Int,
        virtual: Boolean,
        seed: Long,
        err: Appendable,
    ): Started? {
        val source = FileProfileSource(schema)
        val clock: Clock = if (virtual) VirtualClock(Instant.EPOCH) else RealClock()

        val instances = robots.map { (id, path) ->
            try {
                RobotInstance(id, source.load(path), clock, seed)
            } catch (e: ProfileRejected) {
                err.appendLine("기동 거부: $id — ${e.message}")
                return null
            }
        }

        return Started(
            MimicServer(RobotRegistry(instances), ServerBuilder.forPort(port)).start(),
            robots.keys,
        )
    }

    private fun usage(err: Appendable, message: String): Int {
        err.appendLine(message)
        err.appendLine(
            "사용법: mimic --robot <id>=<profile.json> [--robot ...] --schema <path> " +
                "[--port <n>] [--clock real|virtual] [--seed <n>]",
        )
        return 2
    }
}

fun main(args: Array<String>) {
    val cli = MimicCli()
    val code = cli.run(args, System.out, System.err)
    if (code != 0) exitProcess(code)
    // 기동에 성공했으면 프로세스가 살아 있어야 한다.
    cli.started?.server?.awaitTermination()
}
