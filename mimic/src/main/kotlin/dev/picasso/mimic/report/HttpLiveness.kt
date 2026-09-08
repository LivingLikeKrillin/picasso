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
/**
 * 기체가 아는 사이트 이름의 **요약**(ADR 35).
 *
 * **이름 자체를 나르지 않는다.** 계약의 `GetKnownSiteNames`는 목록을 주지만
 * 레지스트리는 그것을 갖지 않는다 — 사이트 이름의 주인은 사이트이고
 * `registry`는 *"등록됐는가"* 만 상태로 든다(ADR 35의 결정 3). 개수와
 * "호스팅 못 함"만 있으면 그 판정이 선다.
 *
 * [unsupported]와 `count == 0`을 **접으면 안 된다.** 앞은 등록할 자리가 없는
 * 기종이고 뒤는 자리는 있는데 비어 있는 것이다. 접으면 자리가 없는 기체에게
 * 등록을 요구하게 된다.
 */
data class SiteNameSummary(val unsupported: Boolean, val count: Int)

interface LivenessObservations {
    /**
     * @param software 기체가 보고하는 로봇 소프트웨어. **못 읽는 기종이면
     *   `null`이고 빈 문자열이 아니다** — 신원 질의가 아예 없는 실물이 있다.
     * @param siteNames 기체가 아는 사이트 이름의 요약(ADR 35). 아직 물어본
     *   적이 없으면 `null`이며, 그것은 "없다"가 아니라 **"모른다"** 다.
     */
    fun onConnection(
        header: MessageHeader,
        state: ConnectionState,
        software: String?,
        siteNames: SiteNameSummary? = null,
    )

    companion object {
        val NONE = object : LivenessObservations {
            override fun onConnection(
                header: MessageHeader,
                state: ConnectionState,
                software: String?,
                siteNames: SiteNameSummary?,
            ) = Unit
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
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .build(),
    private val timeout: Duration = REQUEST_TIMEOUT,
) : LivenessObservations {

    private val base = baseUrl.trimEnd('/')

    override fun onConnection(
        header: MessageHeader,
        state: ConnectionState,
        software: String?,
        siteNames: SiteNameSummary?,
    ) {
        val message = ConnectionMessage.newBuilder().setHeader(header).setState(state).build()
        val body = JsonFormat.printer().omittingInsignificantWhitespace().print(message)

        // 계약 메시지에 없는 값이라 쿼리로 간다 — `/ingest/handshake` 의
        // `site` 와 같은 자리다.
        val params = buildList {
            software?.let { add("software=" + URLEncoder.encode(it, StandardCharsets.UTF_8)) }
            // **셋을 구별해 싣는다** — 안 실으면 "모른다", `unsupported=true`면
            // 호스팅 못 하는 기종, 숫자면 그만큼 안다.
            siteNames?.let {
                add("site_names_unsupported=${it.unsupported}")
                add("site_names_count=${it.count}")
            }
        }
        val query = if (params.isEmpty()) "" else params.joinToString("&", prefix = "?")

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
