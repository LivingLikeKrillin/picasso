package dev.picasso.mimic.report

import com.google.protobuf.util.JsonFormat
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * §3.2의 `mimic ⇢ registry` — 핸드셰이크 결과 보고(§5.4).
 *
 * ## 실패하면 던진다
 *
 * 삼키면 [FallbackHandshakeReporter]가 파일로 넘길 기회를 잃는다. 협상을
 * 막지 않는 책임은 **부르는 쪽**([dev.picasso.mimic.transport.SkillServiceImpl])에
 * 있고, 여기서는 실패를 실패라고 말하는 것이 일이다.
 *
 * ## 짧은 타임아웃
 *
 * 이 호출은 협상 응답 경로에 있다. §5.4가 보고 실패는 협상에 영향을 주지
 * 않는다고 했지만 **느린 것은 영향을 준다** — 레지스트리가 멈춰 있으면
 * 모든 협상이 그만큼 느려지고, 그것은 "영향 없음"이 아니다.
 */
class HttpHandshakeReporter(
    baseUrl: String,
    private val token: String,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .build(),
    private val timeout: Duration = REQUEST_TIMEOUT,
) : HandshakeReporter {

    private val base = baseUrl.trimEnd('/')

    override fun report(report: HandshakeReport) {
        val body = """{"request":${printer.print(report.request)},""" +
            """"response":${printer.print(report.response)}}"""

        val site = URLEncoder.encode(report.site, StandardCharsets.UTF_8)
        val request = HttpRequest.newBuilder(URI.create("$base/ingest/handshake?site=$site"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $token")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        // **2xx가 아니면 던진다.** 400은 보고가 틀렸다는 뜻이고 401은 토큰이
        // 틀렸다는 뜻인데, 둘 다 조용히 넘기면 원장이 비는 것으로만 드러난다.
        if (response.statusCode() / 100 != 2) {
            throw IllegalStateException(
                "적재가 거부됐다: HTTP ${response.statusCode()} ${response.body()}",
            )
        }
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(3)
        val printer: JsonFormat.Printer = JsonFormat.printer().omittingInsignificantWhitespace()
    }
}
