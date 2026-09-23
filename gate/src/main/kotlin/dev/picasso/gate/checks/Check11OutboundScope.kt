package dev.picasso.gate.checks

import dev.picasso.gate.CheckResult
import dev.picasso.gate.Finding
import dev.picasso.gate.GateCheck
import dev.picasso.gate.Resource
import dev.picasso.gate.Severity
import dev.picasso.gate.input.GateInput
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

/**
 * 검사 11 — **밖을 부르는 자리는 닫힌 목록이다**(AGENT-06 G1.4, §15.192).
 *
 * 설명 층은 다른 저장소의 다른 언어이고, 이 저장소는 그것을 모른다. 그 사실을 지금까지 든 것은
 * **구조**뿐이었다 — 언어 경계와, 어느 모듈도 그쪽에 빌드 의존이 없다는 것. 그런데 그 둘은
 * **런타임 호출을 막지 못한다.** 출하 소스에 이미 아웃바운드 HTTP 가 있고, 주소는 전부 밖에서
 * 주입받는다. 그러니 설명 층을 부르는 코드를 **지을 수 있다.**
 *
 * ★**이름으로 막을 수 없다.** 금지어를 `narrator` 로 두면 `uplink` 처럼 `baseUrl` 을 주입받는 순간
 * 그 낱말이 소스에 한 번도 안 나타난다. 막아야 하는 것은 이름이 아니라 **밖으로 나가는 문 자체**다.
 *
 * 그래서 **문을 여는 자리를 세어 목록과 댄다.** 목록에 없는 자리가 생기면 실패하고, 목록에 있는데
 * 더는 문을 안 여는 자리가 있어도 실패한다. 뒤쪽이 없으면 목록이 낡아, 옛 이름 아래 새 호출이
 * 조용히 들어올 자리가 남는다.
 *
 * ## 목록을 손으로 적는다 — 그것이 요점이다
 *
 * 검사 7·9 는 탐색어를 선언 파일에서 **유도**한다. 여기는 반대다. 아웃바운드는 **늘어야 할 이유가
 * 스스로 생기지 않는** 종류라, 늘리려면 사람이 이 파일을 고치게 두는 것이 목적이다. 고치는 순간
 * diff 에 보이고 검토가 붙는다.
 *
 * ## 남는 한계
 *
 * **전송이 HTTP 만이다.** gRPC 와 MQTT 는 이 층이 제 기체·제 레지스트리와 말하는 수단이고 상대가
 * 선언돼 있어 여기서 세지 않는다. **그리고 배치가 `baseUrl` 을 설명 층으로 돌리는 것은 못 막는다** —
 * 그것은 소스의 사실이 아니라 운영의 선택이며, 이 검사가 답할 수 있는 자리가 아니다(§15.192).
 */
class Check11OutboundScope : GateCheck {

    override val id = "11"
    override val name = "아웃바운드 경계"

    override val requires = setOf(Resource.REPO)

    override fun run(input: GateInput): CheckResult {
        val root = input.repoRoot!!
        val findings = mutableListOf<Finding>()

        // ★★**모듈의 `src/main` 만 걷는다 — 저장소를 통째로 걷지 않는다.** 통째로 걸으면 음성
        //   하네스의 겹침 파일(`gate/negative/*/overlay/<모듈>/src/main/...`)이 같이 잡혀 **원본
        //   트리가 제 음성 케이스에 걸린다**(실측). 다른 가지의 사본(`.claude/worktrees/`)도 같은
        //   이유로 들어온다. 검사 7 이 모듈을 하나씩 여는 것과 같은 까닭이다.
        //
        //   모듈 목록은 **손으로 안 적는다** — 최상위에서 `src/main` 을 든 디렉터리가 곧 모듈이다.
        val found = mutableSetOf<String>()
        Files.list(root).use { top ->
            top.asSequence()
                .filter { Files.isDirectory(it.resolve(MAIN)) }
                .forEach { module ->
                    Files.walk(module.resolve(MAIN)).use { paths ->
                        paths.asSequence()
                            .filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                            .forEach { file ->
                                val text = Files.readString(file)
                                if (NEEDLES.any { it in text }) {
                                    found += root.relativize(file).joinToString("/")
                                }
                            }
                    }
                }
        }

        // ★**아무것도 못 찾으면 통과가 아니다.** 아웃바운드가 하나도 없는 저장소가 아니므로, 0 은
        //   «깨끗하다» 가 아니라 «훑는 방법이 낡았다» 는 뜻이다.
        if (found.isEmpty()) {
            return CheckResult.Failed(
                id,
                listOf(
                    Finding(
                        id, Severity.ERROR,
                        "출하 소스에서 아웃바운드를 여는 자리를 하나도 못 찾았다 — 탐색 방법이 낡았다",
                        MAIN,
                    ),
                ),
            )
        }

        (found - DECLARED).sorted().forEach {
            findings += Finding(
                id, Severity.ERROR,
                "선언되지 않은 아웃바운드 자리다 — 밖을 부르는 문은 닫힌 목록이다(설계 §11.2 의 11번)",
                it,
            )
        }

        (DECLARED - found).sorted().forEach {
            findings += Finding(
                id, Severity.ERROR,
                "선언된 자리가 더는 아웃바운드를 안 연다 — 목록이 낡으면 그 이름 아래 새 호출이 조용히 들어온다",
                it,
            )
        }

        return if (findings.isEmpty()) CheckResult.Passed(id) else CheckResult.Failed(id, findings)
    }

    private companion object {
        const val MAIN = "src/main"

        /**
         * 아웃바운드를 여는 꼴. 생성 자리만 본다 — 요청 하나하나가 아니라 문이 열리는 지점이다.
         *
         * ★**바늘을 조각으로 잇는다.** 이 파일도 출하 소스라 스스로 훑히는데, 바늘을 통째로 적으면
         * **이 파일이 제 바늘에 걸린다.** 예외 목록을 두는 길도 있으나 그 자리가 곧 구멍이 된다 —
         * 한 파일을 빼 두면 그 파일에 무엇이 들어와도 안 보인다. 조각으로 두면 예외가 없다.
         * 누가 다시 이어 붙이면 이 검사가 저를 잡아 빨개지므로 **조용히 깨지지 않는다.**
         */
        val NEEDLES = ("Http" + "Client").let { listOf("$it.newHttpClient", "$it.newBuilder") }

        /**
         * **밖을 불러도 되는 자리 전부.** 늘리려면 이 줄을 고쳐야 하고, 그것이 이 검사의 목적이다.
         *
         * `uplink` 넷은 이 저장소 자신의 `registry` 로 관측을 올린다(`/ingest` 아래). `adapter-orbit`
         * 하나는 벤더 플릿 관제와 말한다(ADR 37 · 39) — 기종 지식이 갈 수 있는 유일한 자리다.
         */
        val DECLARED = setOf(
            "uplink/src/main/kotlin/dev/picasso/uplink/report/HttpHandshakeReporter.kt",
            "uplink/src/main/kotlin/dev/picasso/uplink/report/HttpLiveness.kt",
            "uplink/src/main/kotlin/dev/picasso/uplink/report/HttpTaskObservations.kt",
            "uplink/src/main/kotlin/dev/picasso/uplink/report/RobotDiscovery.kt",
            "adapter-boston-dynamics-orbit/src/main/kotlin/dev/picasso/adapter/orbit/OrbitHttpLink.kt",
        )
    }
}
