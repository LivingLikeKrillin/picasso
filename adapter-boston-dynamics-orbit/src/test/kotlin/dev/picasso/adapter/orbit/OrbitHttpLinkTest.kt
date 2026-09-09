package dev.picasso.adapter.orbit

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 남쪽이 **와이어를 지난다.**
 *
 * §15.103 까지 `OrbitLink` 의 구현은 시험의 가짜뿐이었고, 그래서 직렬화도 상태 코드도 인증 헤더도 경로 규약도
 * 검사받은 적이 없었다. 여기서는 스텁 HTTP 서버를 세워 **우리가 실제로 무엇을 보내고 무엇을 읽는지**를 본다.
 *
 * ## 이 시험이 붙드는 것
 *
 * 벤더의 파이썬 클라이언트와 **어긋나면 서버가 거절한다.** 그리고 그 어긋남은 게시 스펙을 보고 짜면 생긴다 —
 * 스펙과 클라이언트가 다른 자리가 넷이다(파견 경로 · `timeMs` 의 모양 · 미션이 `dispatchTarget` 아래 · GET 의
 * 뒤 슬래시). 그 넷을 여기서 단언한다.
 *
 * **여전히 진짜 Orbit 인스턴스는 아니다**(C-3). 스텁이 답하는 모양은 우리가 벤더 문서에서 읽어 적은 것이고,
 * 배포본이 그와 같은지는 붙어 봐야 안다.
 */
class OrbitHttpLinkTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    /** 받은 요청을 그대로 모은다 — 경로·헤더·본문. */
    private val received = mutableListOf<Recorded>()

    private data class Recorded(val method: String, val path: String, val query: String?, val headers: Map<String, List<String>>, val body: String)

    /** 경로별 응답. 시험이 갈아 끼운다. */
    private val routes = mutableMapOf<String, Pair<Int, String>>()

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"

        // 루트는 CSRF 쿠키를 준다 — 벤더 클라이언트의 첫 단계다.
        routes["/"] = 200 to ""
        routes["/api/v0/api_token/authenticate/"] = 200 to """{"ok":true}"""
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        received += Recorded(
            exchange.requestMethod,
            exchange.requestURI.path,
            exchange.requestURI.query,
            exchange.requestHeaders.toMap(),
            body,
        )
        if (exchange.requestURI.path == "/") {
            exchange.responseHeaders.add("Set-Cookie", "x-csrf-token=csrf-42; Path=/; HttpOnly")
        }
        val (status, payload) = routes[exchange.requestURI.path] ?: (404 to """{"error":"no such route"}""")
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun connect() = OrbitHttpLink.connect(baseUrl, TOKEN).getOrThrow()

    private fun requestFor(path: String) = received.last { it.path == path }

    // ── 인증

    @Test
    fun `루트에서 받은 CSRF 쿠키를 헤더로 되돌리고 토큰을 확인한다`() {
        connect()

        // ① 루트 GET → ③ 토큰 확인. 벤더 클라이언트의 순서 그대로다.
        assertEquals(listOf("/", "/api/v0/api_token/authenticate/"), received.map { it.path })

        val check = requestFor("/api/v0/api_token/authenticate/")
        assertEquals(listOf("Bearer $TOKEN"), check.headers["Authorization"])
        // **①을 빼면 토큰이 맞아도 거절당하는데 그 실패가 인증 실패처럼 보인다.**
        assertEquals(listOf("csrf-42"), check.headers["X-csrf-token"])
    }

    @Test
    fun `토큰이 안 통하면 링크를 아예 안 만든다`() {
        routes["/api/v0/api_token/authenticate/"] = 401 to """{"error":"bad token"}"""

        val failure = OrbitHttpLink.connect(baseUrl, "wrong")
        assertTrue(failure.isFailure)
        // 모든 요청이 401 로 답하는 링크를 쥐여 주는 것보다 낫다 — 실패가 붙는 시점에 보인다.
        assertTrue("인증" in (failure.exceptionOrNull()?.message ?: ""), failure.exceptionOrNull()?.message ?: "")
    }

    // ── 경로 규약과 봉투

    @Test
    fun `GET 은 경로 뒤에 슬래시를 붙이고, 배열과 resources 봉투를 둘 다 읽는다`() {
        routes["/api/v0/robots/"] = 200 to """[{"hostname":"h1","nickname":"spot-a","robotIndex":0,"username":"admin"}]"""
        routes["/api/v0/runs/"] = 200 to """{"limit":20,"offset":0,"total":1,"resources":[
            {"uuid":"run-1","robotNickname":"spot-a","robotSerial":"sn-1","missionName":"DOCK-3",
             "missionStatus":"whatever","endTime":"2026-09-10T00:05:00Z","actionCount":3,"pendingActionCount":0,
             "startTime":"2026-09-10T00:00:00Z"}]}"""

        val link = connect()

        // **배열 그대로인 자원.** 슬래시가 붙는다 — 게시 스펙은 `/robots` 인데 벤더 클라이언트가 그렇게 친다.
        assertEquals(listOf("spot-a"), link.fleet.robots().getOrThrow().map { it.nickname })
        assertEquals("/api/v0/robots/", requestFor("/api/v0/robots/").path)

        // **봉투가 있는 자원.** `resources` 를 안 벗기면 아무것도 안 읽힌다.
        val run = link.runs.latestRun("spot-a").getOrThrow()!!
        assertEquals("run-1", run.uuid)
        assertEquals("whatever", run.missionStatus, "판정에 안 쓰더라도 원문은 실려야 한다")
        assertEquals("2026-09-10T00:05:00Z", run.endTime)
        // 별명으로 거르고 창을 정해 받는다 — 벤더가 정렬 값을 안 적어 우리가 고른다.
        val runs = requestFor("/api/v0/runs/")
        assertTrue("robotNickname=spot-a" in (runs.query ?: ""), runs.query ?: "")
        assertTrue("limit=${OrbitHttpLink.RUN_WINDOW}" in (runs.query ?: ""), runs.query ?: "")
    }

    @Test
    fun `없는 경로는 실패이고, 봉투가 아니면 조용히 빈 목록이 되지 않는다`() {
        val link = connect()
        // 이 배포본에 그 자원이 없다 — 404 다.
        assertTrue(link.missions.missions().isFailure)

        routes["/api/v0/missions/"] = 200 to """{"unexpected":"shape"}"""
        val wrong = link.missions.missions()
        // **빈 목록으로 접으면 "플릿에 미션이 없다" 가 되고 그 위에서 이름 조회가 조용히 실패한다.**
        assertTrue(wrong.isFailure, "모르는 모양을 빈 목록으로 읽었다")
    }

    @Test
    fun `사건의 오류 코드에서 없는 것과 0 을 가른다`() {
        routes["/api/v0/run_events/"] = 200 to """{"resources":[
            {"uuid":"e-1","actionName":"a","error":0},
            {"uuid":"e-2","actionName":"b"}]}"""

        val events = connect().runs.events("run-1").getOrThrow()
        // 0 은 벤더가 *오류 없음* 으로 쓰는 값이고, 안 실린 것은 모르는 것이다. 접으면 어댑터의 판정이 달라진다.
        assertEquals(listOf(0, null), events.map { it.error })
        assertTrue("runUuid=run-1" in (requestFor("/api/v0/run_events/").query ?: ""))
    }

    // ── 파견 — 스펙이 아니라 클라이언트를 따른다

    @Test
    fun `파견 본문이 벤더 클라이언트의 모양이다`() {
        routes["/api/v0/calendar/mission/dispatch/spot-a"] = 200 to """{"uuid":"run-9"}"""

        val accepted = assertIs<DispatchResult.Accepted>(connect().dispatch.dispatch("spot-a", "m-1", "picasso"))
        assertEquals("run-9", accepted.runUuid)

        val sent = requestFor("/api/v0/calendar/mission/dispatch/spot-a")
        // POST 는 뒤에 슬래시를 **안** 붙인다 — GET 과 다르고, 그것도 벤더 클라이언트의 규약이다.
        assertEquals("POST", sent.method)
        assertEquals("currentDriverId=picasso", sent.query)

        // **게시 스펙대로 짜면 서버가 거절하는 자리 둘.**
        assertTrue(""""timeMs":{"low":1,"high":0,"unsigned":false}""" in sent.body, sent.body)
        assertTrue(""""dispatchTarget":{"missionId":"m-1"}""" in sent.body, sent.body)
        // 스펙의 모양을 그대로 보내지 않는다는 것을 반대쪽으로도 못박는다.
        assertTrue(""""timeMs":1""" !in sent.body, sent.body)
        assertTrue(""""task":{"missionId"""" !in sent.body, sent.body)

        assertTrue(""""nickname":"spot-a"""" in sent.body, sent.body)
        assertTrue("Driver Triggered Mission (picasso)" in sent.body, sent.body)
    }

    @Test
    fun `플릿의 거절과 못 닿음을 가른다`() {
        // 404 는 **답이다** — 이 배포본에 그 경로가 없다는 플릿의 말이지 못 닿은 것이 아니다.
        val refused = assertIs<DispatchResult.Refused>(connect().dispatch.dispatch("spot-a", "m-1", "picasso"))
        assertTrue("404" in refused.detail, refused.detail)

        // 서버를 내리면 답이 없다.
        val link = connect()
        server.stop(0)
        val unreachable = assertIs<DispatchResult.Unreachable>(link.dispatch.dispatch("spot-a", "m-1", "picasso"))
        assertNull(unreachable.detail.toIntOrNull(), "상태 코드가 아니라 전송 실패여야 한다")
    }

    private companion object {
        const val TOKEN = "orbit-token"
    }
}
