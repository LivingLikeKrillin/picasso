package dev.picasso.uplink.report

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * 어댑터가 플릿에서 본 기체 하나(ADR 37 의 **발견**).
 *
 * **일련번호가 널일 수 있다.** 플릿이 그것을 안 나르는 벤더가 있다 — Orbit 의 `Robot` 자원에 없다(§15.103).
 * 주소를 그 자리에 넣으면 거짓말이 되므로 없는 채로 올린다.
 *
 * **접속 정보를 안 싣는다.** 필드 자체가 없다 — 발견된 기체의 접속 정보는 플릿이 갖는다(ADR 37 결정 4).
 */
data class DiscoveredRobotReport(
    val robotId: String,
    val serialNumber: String? = null,
    val displayName: String? = null,
)

/** 적재 문이 돌려주는 것. **부분 성공이다** — 하나가 거절돼도 나머지는 들어간다. */
data class DiscoveryAck(val recorded: List<String>, val refused: Map<String, String>)

/**
 * 발견한 기체 목록을 레지스트리의 **적재 문**으로 올린다(`POST /ingest/robots`).
 *
 * ## 왜 이것이 `uplink` 에 있나
 *
 * 기종을 모르는 결선이기 때문이다. 무엇을 발견했는지는 어댑터가 알고, **그것을 어디로 어떻게 올리는지는**
 * 미믹·어댑터 호스트가 공유하는 이 모듈이 안다 — 생존 보고·태스크 관측과 같은 자리다. 벤더가 늘어도 이
 * 클라이언트는 하나다.
 *
 * ## 문이 곧 출처다
 *
 * 여기로 올린 것은 **관측**이다(ADR 37 결정 3). 같은 사실을 사람이 조작 문으로 적으면 그것은 *선언* 이고,
 * 둘의 차이는 페이로드가 아니라 어느 문으로 들어왔는가다. 그래서 이 본문에 `origin` 이 없다.
 */
fun interface RobotDiscovery {

    /**
     * @param site **어댑터가 배포된 사이트**다. 플릿은 우리 `site_id` 를 모르지만 어댑터는 자기가 어디 있는지 안다
     *   (§15.101 이 ADR 37 의 미결을 그렇게 닫았다).
     */
    fun report(site: String, robots: List<DiscoveredRobotReport>): DiscoveryAck

    companion object {
        /** 레지스트리 없이 도는 모드(§3.2 의 "없을 때"). 발견은 어댑터 안에서 끝나고 원장은 그 기체를 모른다. */
        val NONE = RobotDiscovery { _, robots -> DiscoveryAck(emptyList(), robots.associate { it.robotId to "레지스트리 연계가 없다" }) }
    }
}

/**
 * `POST /ingest/robots?site=`.
 *
 * **던진다.** 삼키면 발견이 조용히 아무 일도 안 한 것이 되고, 그 침묵은 *"플릿에 기체가 없다"* 와 화면에서
 * 구별되지 않는다. 삼킬지는 부르는 쪽이 정한다.
 *
 * **부분 성공은 예외가 아니다.** 거절된 기체가 섞여도 200 이 오고 사유가 본문에 담긴다 — 목록 하나가 통째로
 * 실패하면 플릿에 기체를 하나 더한 날 발견 전체가 멈춘다.
 */
class HttpRobotDiscovery(
    baseUrl: String,
    private val token: String,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
    private val timeout: Duration = REQUEST_TIMEOUT,
) : RobotDiscovery {

    private val base = baseUrl.trimEnd('/')

    override fun report(site: String, robots: List<DiscoveredRobotReport>): DiscoveryAck {
        val body = robots.joinToString(",", prefix = "[", postfix = "]") { robot ->
            buildString {
                append("""{"robot_id":${quote(robot.robotId)}""")
                robot.serialNumber?.let { append(""","serial_number":${quote(it)}""") }
                robot.displayName?.let { append(""","display_name":${quote(it)}""") }
                append("}")
            }
        }

        val request = HttpRequest.newBuilder(URI.create("$base/ingest/robots?site=${URLEncoder.encode(site, StandardCharsets.UTF_8)}"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $token")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() / 100 != 2) {
            error("발견 적재가 거부됐다: HTTP ${response.statusCode()} ${response.body()}")
        }
        return parse(response.body())
    }

    /**
     * 답을 읽는다. **JSON 파서를 안 들인다** — 이 모듈은 계약 메시지에만 protobuf JSON 을 쓰고, 이 본문은
     * 우리 표면의 것이라 모양이 고정돼 있다. 못 읽으면 빈 답을 내지 않고 **그대로 터진다**(조용한 성공 금지).
     */
    private fun parse(body: String): DiscoveryAck {
        val recorded = RECORDED.find(body)?.groupValues?.get(1).orEmpty()
            .split(",").map { it.trim().trim('"') }.filter { it.isNotEmpty() }
        val refused = REFUSED_ENTRY.findAll(REFUSED.find(body)?.groupValues?.get(1).orEmpty())
            .associate { it.groupValues[1] to it.groupValues[2] }
        return DiscoveryAck(recorded, refused)
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)
        val RECORDED = Regex(""""recorded"\s*:\s*\[([^]]*)]""")
        val REFUSED = Regex(""""refused"\s*:\s*\{([^}]*)}""")
        val REFUSED_ENTRY = Regex(""""([^"]+)"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    }
}
