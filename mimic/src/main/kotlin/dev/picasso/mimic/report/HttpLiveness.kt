package dev.picasso.mimic.report

import com.google.protobuf.util.JsonFormat
import dev.picasso.contracts.v1.ConnectionMessage
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.MessageHeader
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * 기체가 살아 있다는 사실을 레지스트리로 민다.
 *
 * ## 왜 태스크 적재와 따로인가
 *
 * `task` 표는 **태스크를 받은 기체만** 안다. 일이 없던 기체와 죽은 기체가
 * 거기서는 같아 보이고, 그 둘을 못 가리면 §9.3의 축소 판정이 조용해진
 * 발신자를 "쓰는 사람 0명"으로 읽는다.
 *
 * ## 폴백이 없다 — 의도다
 *
 * §15.45가 태스크 관측을 파일에 남긴 것과 성질이 반대다. 태스크 전이는
 * **사실**이라 늦게 도착해도 참이지만, 생존은 **시점이 곧 내용**이다.
 * 되밀면 레지스트리가 그것을 받은 시각으로 적어 **죽은 기체를 살아 있다고
 * 거짓말한다.** 실패하면 버리고 다음 발행에서 다시 온다.
 */
interface LivenessObservations {
    fun onConnection(header: MessageHeader, state: ConnectionState)

    companion object {
        val NONE = object : LivenessObservations {
            override fun onConnection(header: MessageHeader, state: ConnectionState) = Unit
        }
    }
}

/**
 * `POST /ingest/liveness`. **던진다** — 삼킬 책임은 [IngestBridge]에 있고
 * 여기서는 실패를 실패라고 말하는 것이 일이다.
 */
class HttpLiveness(
    baseUrl: String,
    private val token: String,
    /**
     * 기체가 보고하는 로봇 소프트웨어 식별자를 찾는다. **못 읽는 기종이면
     * `null`이고 빈 문자열이 아니다** — 신원 질의가 아예 없는 실물이 있다(§2.3).
     */
    private val software: (String) -> String? = { null },
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .build(),
    private val timeout: Duration = REQUEST_TIMEOUT,
) : LivenessObservations {

    private val base = baseUrl.trimEnd('/')

    override fun onConnection(header: MessageHeader, state: ConnectionState) {
        val message = ConnectionMessage.newBuilder().setHeader(header).setState(state).build()
        val body = JsonFormat.printer().omittingInsignificantWhitespace().print(message)

        // 계약 메시지에 없는 값이라 쿼리로 간다 — `/ingest/handshake` 의
        // `site` 와 같은 자리다.
        val reported = software(header.robotId)
        val query = reported
            ?.let { "?software=" + URLEncoder.encode(it, StandardCharsets.UTF_8) }
            ?: ""

        val request = HttpRequest.newBuilder(URI.create("$base/ingest/liveness$query"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $token")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("생존 보고가 거절됐다: ${response.statusCode()} ${response.body()}")
        }
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)

        /** 발행 경로에 있으므로 짧다 — [HttpTaskObservations] 와 같은 이유다. */
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(3)
    }
}
