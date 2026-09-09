package dev.picasso.uplink.report

import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat
import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.StateMessage
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * 태스크 관측을 레지스트리 적재 표면으로 민다.
 *
 * ## 이것은 스펙과 다른 임시 경로다
 *
 * §3.2는 태스크 전이를 `registry ⇠ 브로커` **구독**으로 규정했다. 브로커를
 * 안 붙였으므로(§15.30) 여기서는 `mimic ⇢ registry`로 직접 민다.
 *
 * **브로커가 붙는 날 사라지는 것은 이 클래스뿐이다.** 레지스트리의 적재
 * 표면과 [dev.picasso.contracts.v1.StateMessage] 입력은 그대로 남고, 구독기가
 * 같은 서비스를 부른다 — 그것이 가능하도록 넘기는 것을 계약 타입으로
 * 못박아 뒀다.
 *
 * ## 던진다
 *
 * 삼키면 [FallbackTaskObservations]가 파일로 붙들 기회를 잃는다. 발행을
 * 막지 않을 책임은 [IngestBridge]에 있고, 여기서는 실패를 실패라고 말하는
 * 것이 일이다.
 */
class HttpTaskObservations(
    baseUrl: String,
    private val token: String,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .build(),
    private val timeout: Duration = REQUEST_TIMEOUT,
) : TaskObservations {

    private val base = baseUrl.trimEnd('/')

    override fun onState(message: StateMessage) = post(message)

    override fun onEvent(event: Event) = post(event)

    /**
     * 스냅샷과 전이가 **같은 경로로** 간다. 레지스트리가 본문을 보고 무엇인지
     * 안다 — 경로를 둘로 나누면 어느 쪽으로 보낼지를 여기서 판정하게 되고,
     * 그 판정이 적재의 것과 갈릴 수 있다.
     */
    private fun post(message: Message) {
        val request = HttpRequest.newBuilder(URI.create("$base/ingest/task"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $token")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    printer.print(message),
                    StandardCharsets.UTF_8,
                ),
            )
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() / 100 != 2) {
            throw IllegalStateException(
                "적재가 거부됐다: HTTP ${response.statusCode()} ${response.body()}",
            )
        }
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)

        /**
         * 발행 경로에 있으므로 짧다. §5.4가 보고 실패는 협상에 영향을 주지
         * 않는다고 했지만 **느린 것은 영향을 준다** — 적재가 멈춰 있으면
         * 발행이 그만큼 밀리고, 그것은 "영향 없음"이 아니다.
         */
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(3)
        val printer: JsonFormat.Printer = JsonFormat.printer().omittingInsignificantWhitespace()
    }
}
