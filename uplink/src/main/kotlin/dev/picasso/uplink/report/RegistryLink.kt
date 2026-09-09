package dev.picasso.uplink.report

import dev.picasso.uplink.Publisher
import java.nio.file.Path

/**
 * 레지스트리와의 연결을 **한 자리에서** 조립한다.
 *
 * ## 왜 조립을 한곳에 두는가
 *
 * 붙일 것이 다섯이다 — 핸드셰이크 보고, 태스크 적재, 기체 생존 보고, 앞의
 * 둘에 대한 파일 폴백, 그리고 기동 시 재적재. CLI가 이것을 직접 엮으면 **하나를 빠뜨려도 기동은 된다.**
 * 폴백을 안 붙이면 레지스트리가 잠깐 없는 동안의 관측이 사라지고, 재적재를
 * 안 부르면 그 파일이 영영 안 밀린다. 둘 다 조용한 실패다.
 *
 * 여기 모아 두면 빠뜨릴 자리가 하나로 줄고, 시험이 그 하나를 본다.
 *
 * ## 레지스트리가 없으면 아무것도 안 붙는다
 *
 * §3.2가 이 방향을 **런타임 접근**으로 뒀다 — 상대가 없어도 모듈이 돈다.
 * [none] 이 그 모드이며, `mimic`은 파일 모드로 그대로 기동한다.
 */
class RegistryLink private constructor(
    /** §5.4의 핸드셰이크 결과 보고. */
    val reporter: HandshakeReporter,
    /**
     * 발행을 감싸 관측을 적재로 넘기는 데코레이터.
     *
     * **두 번째 인자가 있는 것은 순서 때문이다** — 이 연결은 기체보다 먼저
     * 만들어지므로 "그 기체의 펌웨어"를 지금은 모른다. 발행 시점에 평가되는
     * 람다로 받는다.
     */
    private val decorate: (Publisher, (String) -> String?, (String) -> SiteNameSummary?) -> Publisher,
    private val replay: (() -> ReplayOutcome)?,
    private val handshakeFallback: Path?,
    private val taskFallback: Path?,
) {

    /**
     * 기동할 때 폴백 파일을 밀어 넣는다.
     *
     * **여기가 §15.46이 물은 "언제 부르는가"의 답이다.** 레지스트리가 돌아온
     * 것을 감지하는 장치는 없지만, 프로세스가 다시 뜨는 것이 가장 흔한
     * 복구 계기다.
     *
     * @return 밀어 넣은 결과. 없으면 null(연결이 없거나 파일이 없다).
     */
    /**
     * @param software 그 기체가 보고하는 로봇 소프트웨어를 찾는다. 발행할
     *   때 평가되므로 기체가 아직 없어도 된다.
     */
    fun wrap(
        publisher: Publisher,
        software: (String) -> String? = { null },
        siteNames: (String) -> SiteNameSummary? = { null },
    ): Publisher = decorate(publisher, software, siteNames)

    fun replayFallbacks(): ReplayOutcome? = replay?.invoke()

    /** 폴백이 쌓이는 자리. 운영자가 볼 수 있게 알린다. */
    fun fallbackPaths(): List<Path> = listOfNotNull(handshakeFallback, taskFallback)

    companion object {

        /** 레지스트리 없이 도는 모드(§3.2의 "없을 때"). */
        fun none(): RegistryLink = RegistryLink(
            reporter = HandshakeReporter.NONE,
            decorate = { p, _, _ -> p },
            replay = null,
            handshakeFallback = null,
            taskFallback = null,
        )

        /**
         * @param fallbackDir 적재가 실패한 관측이 쌓이는 곳. 없으면 폴백 없이
         *   삼킨다 — **권하지 않는다.** 삼킨 것을 버리면 레지스트리가 잠깐
         *   없는 동안의 관측이 통째로 사라진다(§15.45).
         */
        fun http(
            baseUrl: String,
            token: String,
            site: String,
            fallbackDir: Path?,
        ): RegistryLink {
            val handshakeHttp = HttpHandshakeReporter(baseUrl, token)
            val taskHttp = HttpTaskObservations(baseUrl, token)
            // **폴백을 안 붙인다.** 생존은 시점이 곧 내용이라 되밀면 죽은
            // 기체를 살아 있다고 거짓말한다(HttpLiveness 의 KDoc).
            val livenessHttp = HttpLiveness(baseUrl, token)

            if (fallbackDir == null) {
                return RegistryLink(
                    reporter = handshakeHttp,
                    decorate = { p, sw, sn -> IngestBridge(p, taskHttp, livenessHttp, sw, sn) },
                    replay = null,
                    handshakeFallback = null,
                    taskFallback = null,
                )
            }

            val handshakeFile = fallbackDir.resolve("handshake-fallback.jsonl")
            val taskFile = fallbackDir.resolve("task-fallback.jsonl")
            val tasks = FallbackTaskObservations(taskHttp, FileTaskObservations(taskFile))

            val replay = FallbackReplay(
                handshakes = { s, request, response ->
                    // 재적재는 **같은 HTTP 경로**를 쓴다. 다른 경로로 밀면
                    // 그 경로만 통과하는 결함이 생긴다.
                    handshakeHttp.report(HandshakeReport(s, request, response))
                },
                tasks = taskHttp,
            )

            return RegistryLink(
                reporter = FallbackHandshakeReporter(
                    handshakeHttp,
                    FileHandshakeReporter(handshakeFile),
                ),
                decorate = { p, sw, sn -> IngestBridge(p, tasks, livenessHttp, sw, sn) },
                replay = {
                    val handshakes = replay.replayHandshakes(handshakeFile)
                    val taskLines = replay.replayTasks(taskFile)
                    ReplayOutcome(
                        handshakes.replayed + taskLines.replayed,
                        handshakes.failed + taskLines.failed,
                        handshakes.malformed + taskLines.malformed,
                    )
                },
                handshakeFallback = handshakeFile,
                taskFallback = taskFile,
            )
        }
    }
}
