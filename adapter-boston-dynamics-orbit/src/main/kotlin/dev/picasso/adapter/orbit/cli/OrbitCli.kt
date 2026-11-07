package dev.picasso.adapter.orbit.cli

import dev.picasso.uplink.report.HttpRobotDiscovery
import dev.picasso.uplink.report.RegistryLink
import dev.picasso.uplink.report.RobotDiscovery
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * `orbit-adapter --orbit-url <url> --orbit-token <t> --site <s> --instance <id> --profile <profile.json> [--port 0]`
 *
 * [OrbitLauncher] 를 프로세스로 띄운다. mimic 의 `MimicCli` 와 같은 일을 한다 — 인자를 검증하고, 기동을 거부하면 그 사유를
 * 적고 0 이 아닌 코드로 끝나며, 기동하면 서버가 내려갈 때까지 프로세스를 살려 둔다(§15.292).
 *
 * **레지스트리는 선택이다.** `--registry` 를 주면 `--ingest-token` 이 있어야 하고, 발견과 관측이 그 적재 문으로 간다. 안 주면
 * 적재 없이 돈다(§3.2 의 "없을 때").
 */
class OrbitCli(
    /** 시험이 스텁 링크와 포트를 넣는다. 운영에서는 실물 배선이다. */
    private val launcher: (OrbitLauncher.Options) -> OrbitLauncher = { OrbitLauncher(it) },
) {

    /** 기동한 배치. 프로세스가 소유하며, 시험은 이것으로 닫는다. */
    var started: OrbitLauncher.Running? = null
        private set

    private var link: RegistryLink = RegistryLink.none()

    /** 배치를 내리고 적재 큐를 비운다. */
    fun close() {
        started?.close()
        link.close()
    }

    /** **`exitProcess`를 안에 두지 않는다.** 그러면 종료 코드를 시험할 때 시험 JVM이 죽는다. `main`이 감싼다. */
    fun run(args: Array<String>, out: Appendable, err: Appendable): Int {
        val values = mutableMapOf<String, String>()
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            if (arg !in KNOWN) return usage(err, "모르는 인자다: '$arg'")
            val value = args.getOrNull(i + 1) ?: return usage(err, "$arg 에 값이 없다")
            if (values.put(arg, value) != null) return usage(err, "$arg 를 두 번 줬다")
            i += 2
        }

        val missing = REQUIRED.filter { values[it].isNullOrBlank() }
        if (missing.isNotEmpty()) return usage(err, "필수 인자가 없다: ${missing.joinToString(", ")}")

        val port = values["--port"]?.let { it.toIntOrNull() ?: return usage(err, "--port 가 정수가 아니다") } ?: 0
        val pump = values["--pump-ms"]?.let { it.toLongOrNull()?.takeIf { n -> n > 0 } ?: return usage(err, "--pump-ms 는 양의 정수다") } ?: 1_000
        val rediscover = values["--rediscover-seconds"]?.let {
            it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: return usage(err, "--rediscover-seconds 는 0 이상의 정수다")
        } ?: 0

        val registry = values["--registry"]
        val token = values["--ingest-token"]
        // **토큰 없이 레지스트리를 붙이지 않는다.** 적재 표면은 전부 401을 낼 것이고, 그러면 관측이 통째로 폴백으로 가면서
        // "붙었다"고 보고된다 — 기동에서 막는 편이 정직하다(mimic 과 같은 규칙).
        if (registry != null && token.isNullOrBlank()) return usage(err, "--registry 를 쓰면 --ingest-token 이 있어야 한다")
        if (registry == null && values.containsKey("--fallback-dir")) return usage(err, "--fallback-dir 는 --registry 와 함께 쓴다")

        val site = values.getValue("--site")
        link = registry?.let { RegistryLink.http(it, token!!, site, values["--fallback-dir"]?.let(Path::of)) } ?: RegistryLink.none()
        val discovery: RobotDiscovery = registry?.let { HttpRobotDiscovery(it, token!!) } ?: RobotDiscovery.NONE

        val options = OrbitLauncher.Options(
            orbitUrl = values.getValue("--orbit-url"),
            token = values.getValue("--orbit-token"),
            site = site,
            instance = values.getValue("--instance"),
            profile = Path.of(values.getValue("--profile")),
            port = port,
            pumpMillis = pump,
            rediscoverMillis = rediscover * 1_000,
        )
        val running = launcher(options).start(registry = link, discovery = discovery).getOrElse {
            link.close()
            err.appendLine("기동 거부: ${it.message}")
            return 1
        }
        started = running
        out.appendLine("orbit 어댑터가 포트 ${running.port} 에서 ${running.host.robots.keys.sorted()} 를 호스팅한다")
        if (rediscover > 0) out.appendLine("다시 발견: ${rediscover}초마다")
        if (registry != null) out.appendLine("레지스트리 연계: $registry (site=$site)")
        return 0
    }

    private fun usage(err: Appendable, message: String): Int {
        err.appendLine(message)
        err.appendLine(
            "사용법: orbit-adapter --orbit-url <url> --orbit-token <t> --site <s> --instance <id> --profile <profile.json> " +
                "[--port <n>] [--pump-ms <n>] [--rediscover-seconds <n>] " +
                "[--registry <url> --ingest-token <t> [--fallback-dir <path>]]",
        )
        return 2
    }

    private companion object {
        val REQUIRED = listOf("--orbit-url", "--orbit-token", "--site", "--instance", "--profile")
        val KNOWN = REQUIRED + listOf("--port", "--pump-ms", "--rediscover-seconds", "--registry", "--ingest-token", "--fallback-dir")
    }
}

fun main(args: Array<String>) {
    val cli = OrbitCli()
    val code = cli.run(args, System.out, System.err)
    if (code != 0) exitProcess(code)
    // 프로세스가 내려갈 때 OFFLINE 을 남기고 적재 큐를 비운다.
    Runtime.getRuntime().addShutdownHook(Thread { cli.close() })
    // 기동에 성공했으면 프로세스가 살아 있어야 한다.
    cli.started?.host?.awaitTermination()
}
