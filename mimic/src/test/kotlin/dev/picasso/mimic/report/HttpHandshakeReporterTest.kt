package dev.picasso.mimic.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.picasso.contracts.v1.CapabilityRequirement
import dev.picasso.contracts.v1.NegotiateRequest
import dev.picasso.contracts.v1.NegotiateResponse
import dev.picasso.mimic.transport.GrpcFixture
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import dev.picasso.uplink.report.HandshakeReport
import dev.picasso.uplink.report.HttpHandshakeReporter

/**
 * `mimic ⇢ registry`의 와이어(§3.2).
 *
 * **진짜 소켓으로 본다.** 적재 표면과의 계약은 경로·질의 파라미터·토큰
 * 헤더·본문 형식 넷인데, 그중 어느 것도 타입으로 붙들려 있지 않다 —
 * `registry`는 `mimic`을 모르고 `mimic`도 `registry`를 모르기 때문이다.
 * 그 대가가 이 시험이다.
 *
 * 진짜 레지스트리와 맞물리는 것은 4a-3의 end-to-end가 본다. 여기서는
 * **나가는 쪽이 약속대로 나가는가**만 본다.
 */
class HttpHandshakeReporterTest {

    private val received = mutableListOf<Captured>()
    private var status = 200

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/ingest/handshake") { exchange: HttpExchange ->
            received += Captured(
                // **`query`가 아니라 `rawQuery`다.** 전자는 디코딩된 값을 주므로
                // `%26`이 `&`로 풀려 보이고, 그러면 **인코딩을 아예 안 하는
                // 결함과 구별되지 않는다** — 실측으로 그렇게 오인했다.
                path = exchange.requestURI.path,
                query = exchange.requestURI.rawQuery ?: "",
                authorization = exchange.requestHeaders.getFirst("Authorization") ?: "",
                contentType = exchange.requestHeaders.getFirst("Content-Type") ?: "",
                body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8),
            )
            val body = "{}".toByteArray()
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }

    private val reporter by lazy {
        HttpHandshakeReporter("http://127.0.0.1:${server.address.port}", "test-token")
    }

    @AfterTest fun stop() = server.stop(0)

    @Test
    fun `보고가 적재 경로로 나간다`() {
        reporter.report(report("line-b"))

        val sent = received.single()
        // **경로까지 본다.** `createContext`는 접두사 매칭이라 스텁이
        // `/ingest/handshakes`도 받아 준다 — 경로 오타가 실제로는 404인데
        // 여기서는 200으로 보인다(실측으로 주입이 안 잡혔다).
        assertEquals("/ingest/handshake", sent.path)
        assertEquals("site=line-b", sent.query)
        assertEquals("Bearer test-token", sent.authorization)
        assertTrue(sent.contentType.startsWith("application/json"), sent.contentType)
    }

    @Test
    fun `본문에 오간 두 메시지가 그대로 실린다`() {
        reporter.report(report("line-b"))

        val body = ObjectMapper().readTree(received.single().body)
        assertTrue(body.has("request"), body.toString())
        assertTrue(body.has("response"), body.toString())
        // protobuf JSON은 필드를 lowerCamel로 낸다. 적재 표면이 같은 규약으로
        // 읽으므로 여기서 확인해 두면 규약이 갈라진 것을 일찍 안다.
        assertEquals(
            "line-controller",
            body.get("request").get("header").get("clientId").asText(),
        )
    }

    @Test
    fun `site가 URL 인코딩된다`() {
        // site에 토픽 레벨로 못 쓰는 문자는 `Topics`가 막지만, 질의 문자열은
        // 그 방어 밖이다 — 인코딩을 빼면 `&` 하나가 파라미터를 쪼갠다.
        reporter.report(report("line a&b"))

        assertEquals("site=line+a%26b", received.single().query)
    }

    @Test
    fun `400이면 던진다`() {
        // **조용히 넘기면 원장이 비는 것으로만 드러난다.** 400은 보고가
        // 틀렸다는 뜻이라 고쳐야 하고, 던져야 폴백이 파일로 붙든다.
        status = 400

        assertFailsWith<IllegalStateException> { reporter.report(report("line-b")) }
    }

    @Test
    fun `401이면 던진다`() {
        status = 401

        val thrown = assertFailsWith<IllegalStateException> { reporter.report(report("line-b")) }
        assertTrue("401" in (thrown.message ?: ""), thrown.message)
    }

    @Test
    fun `닿지 못하면 던진다`() {
        val dead = HttpHandshakeReporter("http://127.0.0.1:1", "test-token")

        assertFailsWith<Exception> { dead.report(report("line-b")) }
    }

    private data class Captured(
        val path: String,
        val query: String,
        val authorization: String,
        val contentType: String,
        val body: String,
    )

    private fun report(site: String) = HandshakeReport(
        site = site,
        request = NegotiateRequest.newBuilder()
            .setHeader(GrpcFixture.requestHeader("r1"))
            .setRequirement(
                CapabilityRequirement.newBuilder()
                    .setClientId("line-controller").setRobotId("r1")
                    .addRequirements("pick_place@^1.2"),
            ).build(),
        response = NegotiateResponse.newBuilder().setAccepted(true).build(),
    )
}
