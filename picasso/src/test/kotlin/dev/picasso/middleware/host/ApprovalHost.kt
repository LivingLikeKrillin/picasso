package dev.picasso.middleware.host

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.picasso.middleware.ApprovalWire
import dev.picasso.middleware.Json
import dev.picasso.middleware.Middleware
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * 승인 시도를 **프로세스 밖에서** 받는 입(`docs/orchestration.md` §7.4).
 *
 * ## 여기가 담는 쪽이다
 *
 * `picasso` 는 라이브러리이므로 진입점도 소켓도 안 든다(§6). 밖으로 내는 일은 **본 계층을 세우는 쪽**의
 * 몫이고, v1 에서 실행 계층을 실제로 구동하는 유일한 주체는 시나리오 구동기다 — 파일 내보내기가 시험
 * 소스에 사는 것과 같은 이유로 이 입도 여기 있다. 진짜 배치가 생기면 포트 여섯을 다 드는 호스트가
 * 이 코드를 대신하며, 그때 바뀌는 것은 **이 파일 하나**다. `ApprovalWire` 는 그대로다.
 *
 * ## 자물쇠 하나를 지난다
 *
 * 미들웨어는 **단일 적용자**를 전제로 한다(배선도의 `pump`). HTTP 스레드가 그 전제를 깨지 않도록
 * 승인도 구동 루프도 같은 자물쇠를 지난다. 동시에 들어온 승인 둘이 같은 제안을 두 번 쓰는 일이 여기서 막힌다.
 *
 * ## 거절은 200 이다
 *
 * 거절은 설계된 정상 경로이므로 오류 코드로 내지 않는다 — 내면 읽는 쪽에서 «자격 없음» 과 «시스템
 * 고장» 이 같은 모양이 된다. 오류로 내는 것은 **못 읽는 요청**(400)과 **틀린 메서드**(405)뿐이며,
 * 그 둘도 JSON 으로 답한다. 부르는 쪽이 답을 파싱하는 길이 하나여야 한다.
 */
class ApprovalHost(
    private val middleware: Middleware,
    /** 미들웨어를 만지는 모든 손이 지나는 자물쇠. 구동 루프가 같은 것을 든다. */
    private val lock: Any,
    port: Int = 0,
) : AutoCloseable {

    /** **루프백에만 붙는다.** 이 입은 신원을 인증하지 않으므로(§15.3) 밖으로 열 것이 아니다. */
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)

    val port: Int get() = server.address.port

    init {
        server.createContext(PATH, ::serve)
        // 한 번에 하나. 어차피 자물쇠를 지나므로 스레드를 늘려도 얻을 것이 없다.
        server.executor = null
        server.start()
    }

    private fun serve(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") {
                return reply(exchange, 405, error("승인 시도는 POST 다"))
            }
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val attempt = try {
                ApprovalWire.decode(body)
            } catch (e: IllegalArgumentException) {
                // **못 읽는 요청은 거절이 아니다.** 부르는 쪽이 자기 오타와 «자격 없음» 을 구별해야 한다.
                return reply(exchange, 400, error(e.message ?: "승인 요청을 읽지 못했다"))
            }
            val outcome = synchronized(lock) { middleware.attemptApproval(attempt) }
            reply(exchange, 200, ApprovalWire.encode(outcome))
        } finally {
            exchange.close()
        }
    }

    private fun error(reason: String): String = """{"error":${Json.quote(reason)}}"""

    private fun reply(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    override fun close() = server.stop(0)

    companion object {
        const val PATH: String = "/approvals"
    }
}
