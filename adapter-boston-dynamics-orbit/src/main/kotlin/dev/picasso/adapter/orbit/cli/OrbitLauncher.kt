package dev.picasso.adapter.orbit.cli

import dev.picasso.adapter.core.AdapterIdentity
import dev.picasso.adapter.host.AdapterHost
import dev.picasso.adapter.host.HostUplink
import dev.picasso.adapter.host.HostedRobot
import dev.picasso.adapter.orbit.OrbitAdapter
import dev.picasso.adapter.orbit.OrbitDiscovery
import dev.picasso.adapter.orbit.OrbitHttpLink
import dev.picasso.adapter.orbit.OrbitLink
import dev.picasso.profile.ProfileDocument
import dev.picasso.uplink.Publisher
import dev.picasso.uplink.report.RegistryLink
import dev.picasso.uplink.report.RobotDiscovery
import io.grpc.ServerBuilder
import java.nio.file.Files
import java.nio.file.Path

/**
 * **배치 런처** — 플릿에 붙어 기체를 발견하고, 그 기체들을 계약 뒤에 세운다.
 *
 * 이때까지 조립은 시험 안에만 있었다. 미들웨어 → 계약 → 호스트 → 어댑터 → 벤더가 이어지는 것을 시험이 보였지만,
 * **운영에서 그것을 세우는 코드가 없었다** — 그래서 "배치 런처 없음" 이 §15.98 이후 계속 열려 있었다.
 *
 * ## 조립 순서가 곧 ADR 37 의 절차다
 *
 * 1. **플릿에 붙는다**([OrbitHttpLink.connect]) — 못 붙으면 여기서 끝난다. 붙지도 못하면서 포트를 열면 소비자가
 *    아무것도 못 하는 기체를 본다.
 * 2. **발견한다**([OrbitDiscovery.sweep]) — 플릿이 아는 기체 목록을 얻어 레지스트리의 적재 문으로 올린다.
 * 3. **기체마다 어댑터를 세운다** — 계약은 기체 단위이므로 어댑터도 기체마다 하나이고, 링크(플릿 서버)만 공유한다.
 * 4. **포트를 연다** — 그 다음에 `ONLINE` 이 나간다(§10.2 의 순서).
 *
 * ## 프로파일이 하나다
 *
 * 발견된 기체 **전부에 같은 프로파일**을 적용한다. 플릿 뒤의 기종이 섞여 있으면 그것은 틀리며, **우리가 그것을
 * 알아낼 방법이 없다** — Orbit 의 `Robot` 자원에 기종을 말하는 칸이 없다(일련번호가 없는 것과 같은 자리, §15.103).
 * 섞인 사이트에서는 런처를 기종별로 나눠 띄우고 각각 발견 결과를 걸러야 하는데, 걸를 근거가 플릿에 없다.
 *
 * ## 스케줄러가 여기 있다
 *
 * 호스트는 스레드가 없다 — 모든 RPC 가 펌프를 지나므로 소비자가 폴링하면 그것이 곧 구동이다. 다만 **아무도 안
 * 물어보는 동안에도** 상태는 흘러야 한다(주기 발행·결함 관측). 그 스레드가 배치의 것이고 그래서 여기 있다.
 */
class OrbitLauncher(
    private val options: Options,
    /** 시험이 스텁 서버와 in-process 전송을 넣는다. 운영에서는 기본값이 곧 실물 배선이다. */
    private val connect: (Options) -> Result<OrbitLink> = { OrbitHttpLink.connect(it.orbitUrl, it.token).map { link -> link } },
    private val serverBuilder: (Int) -> ServerBuilder<*> = { ServerBuilder.forPort(it) },
) {

    data class Options(
        val orbitUrl: String,
        val token: String,
        val site: String,
        /** 이 배포의 이름. 발견이 이것으로 자기를 신고하고 레지스트리가 그것으로 *"누가 올렸는가"* 에 답한다. */
        val instance: String,
        val profile: Path,
        val port: Int = 0,
        val driverId: String = "picasso",
        /** 펌프 주기(밀리초). 0 이면 스케줄러를 안 돌린다 — 시험이 걸음을 직접 센다. */
        val pumpMillis: Long = 1_000,
    )

    class Running(
        val host: AdapterHost,
        val discovered: List<String>,
        private val pump: Thread?,
    ) : AutoCloseable {
        val port: Int get() = host.port

        override fun close() {
            pump?.interrupt()
            host.shutdown()
        }
    }

    /**
     * 세운다.
     *
     * @param publisher 브로커로 나갈 발행자. 안 주면 아무 데도 안 나간다(§15.30 과 같은 기본값).
     * @param registry 레지스트리 연계. 안 주면 적재 없이 돈다(§3.2 의 "없을 때").
     * @param discovery 발견을 올릴 적재 문.
     */
    fun start(
        publisher: Publisher = Publisher.NONE,
        registry: RegistryLink = RegistryLink.none(),
        discovery: RobotDiscovery = RobotDiscovery.NONE,
    ): Result<Running> = runCatching {
        // ① 플릿에 붙는다. 못 붙으면 여기서 끝난다.
        val link = connect(options).getOrElse { throw IllegalStateException("플릿에 못 붙었다: ${it.message}", it) }

        // ② 발견. **실패하면 세우지 않는다** — 무엇을 세울지가 이 답에서 온다.
        val ack = OrbitDiscovery(link, options.site, options.instance, discovery).sweep()
            .getOrElse { throw IllegalStateException("발견에 실패했다: ${it.message}", it) }
        val fleet = link.fleet ?: throw IllegalStateException("이 Orbit 이 기체 목록을 안 준다")
        val robots = fleet.robots().getOrThrow()

        val document = ProfileDocument.parse(options.profile.toString(), Files.readString(options.profile)).getOrThrow()

        // ③ 기체마다 어댑터 하나. 링크는 공유한다.
        val hosted = robots.associate { robot ->
            val adapter = OrbitAdapter(
                link,
                AdapterIdentity("boston-dynamics", "orbit", robot.nickname),
                nickname = robot.nickname,
                driverId = options.driverId,
            )
            // **발행자를 적재로 감싼다** — 미믹 CLI 의 `RegistryLink.wrap` 과 같은 자리다.
            val outbound = HostUplink.wrap(registry, publisher, adapter)
            robot.nickname to HostedRobot(robot.nickname, document, adapter, publisher = outbound, site = options.site)
        }
        if (hosted.isEmpty()) throw IllegalStateException("플릿에 기체가 하나도 없다 — 세울 것이 없다")

        // ④ 포트를 열고 그 다음에 ONLINE 이 나간다.
        val host = AdapterHost(hosted, serverBuilder(options.port), registry.reporter).start()

        Running(host, ack.recorded, pumpThread(host))
    }

    /**
     * 아무도 안 물어보는 동안에도 상태가 흐르게 한다.
     *
     * **데몬이다.** 이 스레드가 프로세스를 살려 두면 안 된다 — 프로세스를 살려 두는 것은 서버이고, 그것이
     * 내려가면 여기도 같이 끝나야 한다.
     */
    private fun pumpThread(host: AdapterHost): Thread? {
        if (options.pumpMillis <= 0) return null
        return Thread {
            while (!Thread.currentThread().isInterrupted) {
                runCatching { host.pump() }
                try {
                    Thread.sleep(options.pumpMillis)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }.apply {
            isDaemon = true
            name = "orbit-pump"
            start()
        }
    }
}
