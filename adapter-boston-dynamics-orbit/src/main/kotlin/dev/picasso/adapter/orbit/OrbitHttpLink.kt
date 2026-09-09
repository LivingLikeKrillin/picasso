package dev.picasso.adapter.orbit

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.adapter.core.VendorSurface
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * [OrbitLink] 의 **실제 구현** — Orbit 인스턴스에 HTTP 로 붙는다.
 *
 * ## 이것이 없던 동안 무엇이 비어 있었나
 *
 * 남쪽 포트(§15.102)와 어댑터(§15.103)를 짓는 동안 `OrbitLink` 의 구현은 시험의 가짜뿐이었다. 그래서 **와이어를
 * 한 번도 안 지났다** — 직렬화도, 상태 코드도, 인증 헤더도, 경로 규약도 검사받은 적이 없었다. 이 클래스가 그
 * 층이고, 스텁 서버를 상대로 시험한다. **진짜 Orbit 인스턴스에 붙여 본 것은 여전히 아니다**(C-3).
 *
 * ## 스펙이 아니라 **클라이언트를 따른다**
 *
 * 게시 스펙과 벤더의 파이썬 클라이언트가 **어긋나는 자리가 있고**, 어긋나면 클라이언트를 따른다 — 서버가 실제로
 * 받는 모양의 증거가 그쪽이기 때문이다. 어긋남을 여기 적어 둔다.
 *
 * | | 게시 스펙 | 벤더 클라이언트 | 우리 |
 * |---|---|---|---|
 * | 파견 경로 | **없다** | `calendar/mission/dispatch/{nickname}?currentDriverId=…` | 클라이언트 |
 * | `schedule.timeMs` | `type: integer` | `{low, high, unsigned}` | 클라이언트 |
 * | 미션 지목 | `task.missionId` | `task.dispatchTarget.missionId` | 클라이언트 |
 * | GET 경로 | `/robots`(슬래시 없음) | `/api/v0/{path}/` — **언제나 뒤에 슬래시** | 클라이언트 |
 *
 * ## 인증이 두 단계다
 *
 * 토큰만으로는 안 된다. 벤더 클라이언트가 ① 먼저 루트에 GET 해서 **`x-csrf-token` 쿠키**를 받고 그것을 같은 이름의
 * 헤더로 되돌려 보낸 뒤 ② `Authorization: Bearer <토큰>` 을 얹고 ③ `api_token/authenticate` 로 토큰이 유효한지
 * 확인한다. 그 셋을 그대로 한다 — **①을 빼면 토큰이 맞아도 거절당하는데 그 실패가 인증 실패처럼 보인다.**
 *
 * ## 응답 봉투가 둘이다
 *
 * `/robots`·`/missions` 는 **배열 그대로**이고 `/runs/`·`/run_events/` 는 `{limit, offset, total, resources}` 다.
 * 벤더가 그렇게 갈라 뒀고 짐작할 수 있는 것이 아니다 — 매니페스트에 둘 다 있다.
 *
 * ## `https` 를 강요하지 않는다
 *
 * 벤더 클라이언트는 `https://{hostname}` 을 박아 둔다. 여기는 **기저 URL 을 받는다** — 스텁 서버를 상대로 와이어를
 * 지나는 시험을 돌리기 위해서다. 운영에서는 `https://` 를 넘긴다. 이것이 벤더와 다른 유일한 자리이고, 다른 이유는
 * 시험 가능성이다.
 */
class OrbitHttpLink private constructor(
    private val baseUrl: String,
    private val token: String,
    private val csrfToken: String?,
    private val http: HttpClient,
    private val timeout: Duration,
) : OrbitLink {

    // **층을 널로 두지 않는다.** 배포본이 그 경로를 주는지 싸게 물어볼 방법이 없다 — 없으면 요청이 404 로 답하고,
    // 그것은 *못 물어봤다* 가 아니라 *물어봤더니 없다더라* 이므로 각 층의 실패 어휘로 나간다.
    override val fleet: FleetLayer = Fleet()
    override val missions: MissionLayer = Missions()
    override val dispatch: DispatchLayer = Dispatch()
    override val runs: RunLayer = Runs()

    // ── 층

    private inner class Fleet : FleetLayer {
        @VendorSurface("GET /robots", "Robot.hostname", "Robot.nickname", "Robot.robotIndex", "Robot.username")
        override fun robots(): Result<List<OrbitRobot>> = get("robots").map { body ->
            // **배열 그대로다** — 봉투가 없다.
            body.map {
                OrbitRobot(
                    hostname = it.path("hostname").asText(),
                    nickname = it.path("nickname").asText(),
                    robotIndex = it.path("robotIndex").asInt(),
                    username = it.path("username").asText(),
                )
            }
        }
    }

    private inner class Missions : MissionLayer {
        @VendorSurface("GET /missions", "Mission.uuid", "Mission.name")
        override fun missions(): Result<List<OrbitMission>> = get("missions").map { body ->
            body.map { OrbitMission(uuid = it.path("uuid").asText(), name = it.path("name").asText()) }
        }
    }

    private inner class Runs : RunLayer {
        /**
         * **정렬을 벤더에게 안 맡긴다.** `orderBy` 라는 질의 파라미터가 있지만 **값 집합이 스펙에 없어** 무엇을
         * 넣어야 하는지 모른다. 그래서 별명으로 거르고 [RUN_WINDOW] 개를 받아 `startTime` 으로 우리가 고른다.
         * 대가는 명확하다: 그 창 안에 최신 실행이 없으면 못 본다.
         */
        @VendorSurface("GET /runs/", "GET /runs/#resources", "Run.robotNickname", "Run.startTime")
        override fun latestRun(nickname: String): Result<OrbitRun?> =
            get("runs", "robotNickname" to nickname, "limit" to RUN_WINDOW.toString()).map { body ->
                body.maxByOrNull { it.path("startTime").asText("") }?.let { node ->
                    OrbitRun(
                        uuid = node.path("uuid").asText(),
                        robotNickname = node.path("robotNickname").asText(),
                        robotSerial = node.path("robotSerial").textOrNull(),
                        missionName = node.path("missionName").textOrNull(),
                        missionStatus = node.path("missionStatus").textOrNull(),
                        endTime = node.path("endTime").textOrNull(),
                        actionCount = node.path("actionCount").asInt(),
                        pendingActionCount = node.path("pendingActionCount").asInt(),
                    )
                }
            }

        @VendorSurface("GET /run_events/", "GET /run_events/#resources", "RunEvent.runUuid", "RunEvent.error")
        override fun events(runUuid: String): Result<List<OrbitRunEvent>> =
            get("run_events", "runUuid" to runUuid).map { body ->
                body.map { node ->
                    OrbitRunEvent(
                        uuid = node.path("uuid").asText(),
                        actionName = node.path("actionName").textOrNull(),
                        // **없는 것과 0 은 다르다.** 0 은 벤더가 *오류 없음* 으로 쓰는 값이고 없는 것은 안 실린 것이다.
                        error = node.path("error").takeIf { it.isNumber }?.asInt(),
                        time = node.path("time").textOrNull(),
                    )
                }
            }
    }

    private inner class Dispatch : DispatchLayer {
        /**
         * 본문을 **벤더 클라이언트가 보내는 모양 그대로** 만든다. 스펙의 `Schedule` 과 다른 자리가 둘이고
         * (`timeMs` 가 객체, 미션이 `dispatchTarget` 아래) 그 둘이 이 메서드의 이유다.
         */
        @VendorSurface(
            "bosdyn-orbit:calendar/mission/dispatch/{robot_nickname}?currentDriverId={driver_id}",
            "bosdyn-orbit:body:agent.nickname",
            "bosdyn-orbit:body:schedule.timeMs.low",
            "bosdyn-orbit:body:task.dispatchTarget",
            "bosdyn-orbit:body:eventMetadata.name",
        )
        override fun dispatch(nickname: String, missionUuid: String, driverId: String): DispatchResult {
            val payload = """
                {"agent":{"nickname":${quote(nickname)}},
                 "schedule":{"timeMs":{"low":1,"high":0,"unsigned":false},
                             "repeatMs":{"low":0,"high":0,"unsigned":false}},
                 "task":{"dispatchTarget":{"missionId":${quote(missionUuid)}},
                         "forceAcquireEstop":false,"requireDocked":false,"skipInitialization":false},
                 "eventMetadata":{"name":${quote("Driver Triggered Mission ($driverId)")}}}
            """.trimIndent().replace("\n", "")

            val path = "calendar/mission/dispatch/${encode(nickname)}?currentDriverId=${encode(driverId)}"
            val response = runCatching { send(post(path, payload)) }
                .getOrElse { return DispatchResult.Unreachable(it.message ?: it::class.java.simpleName) }

            if (response.statusCode() / 100 != 2) {
                // **답이 왔다.** 404 도 여기다 — *이 배포본에 그 경로가 없다* 는 플릿의 답이지 못 닿은 것이 아니다.
                return DispatchResult.Refused("HTTP ${response.statusCode()} ${response.body().take(ERROR_SNIPPET)}")
            }
            return DispatchResult.Accepted(runCatching { mapper.readTree(response.body()).path("uuid").textOrNull() }.getOrNull())
        }
    }

    // ── 와이어

    /** GET 은 **경로 뒤에 슬래시를 붙인다** — 벤더 클라이언트가 그렇게 한다(POST 는 안 붙인다). */
    private fun get(path: String, vararg query: Pair<String, String>): Result<List<JsonNode>> = runCatching {
        val suffix = if (query.isEmpty()) "" else query.joinToString("&", "?") { "${encode(it.first)}=${encode(it.second)}" }
        val response = send(
            base("$API/$path/$suffix").header("Accept", JSON).GET().build(),
        )
        if (response.statusCode() / 100 != 2) {
            error("Orbit 이 거절했다: HTTP ${response.statusCode()} ${response.body().take(ERROR_SNIPPET)}")
        }
        val tree = mapper.readTree(response.body())
        // 봉투가 둘이다 — 배열 그대로이거나 `resources` 안에 있다.
        val items = if (tree.isArray) tree else tree.path("resources")
        if (!items.isArray) error("배열도 resources 봉투도 아니다: ${response.body().take(ERROR_SNIPPET)}")
        items.toList()
    }

    private fun post(path: String, body: String): HttpRequest =
        base("$API/$path").header("Content-Type", JSON).header("Accept", JSON)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build()

    private fun base(path: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create("$baseUrl$path")).timeout(timeout)
            .header("Authorization", "Bearer $token")
            .also { builder -> csrfToken?.let { builder.header(CSRF, it) } }

    private fun send(request: HttpRequest): HttpResponse<String> =
        http.send(request, HttpResponse.BodyHandlers.ofString())

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        /** `/api/v0` — 벤더 클라이언트가 자원 경로 앞에 붙이는 것. */
        private const val API = "/api/v0"
        private const val JSON = "application/json"
        private const val CSRF = "x-csrf-token"
        private const val ERROR_SNIPPET = 200

        /** 최근 실행을 고를 창. 벤더가 정렬 값을 안 적어 우리가 고르므로 창이 필요하다. */
        const val RUN_WINDOW = 20

        /**
         * 붙는다 — 벤더 클라이언트의 세 단계 그대로.
         *
         * ① 루트에 GET 해서 `x-csrf-token` 쿠키를 받고 ② 그것을 헤더로 되돌리며 ③ `api_token/authenticate` 로
         * 토큰을 확인한다. **③ 이 실패하면 링크를 안 만든다** — 안 만들고 실패를 돌려주는 것이, 모든 요청이
         * 401 로 답하는 링크를 쥐여 주는 것보다 낫다.
         *
         * @param baseUrl `https://<hostname>` — 시험은 여기에 스텁 서버 주소를 넣는다.
         */
        @JvmStatic
        fun connect(
            baseUrl: String,
            token: String,
            http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
            timeout: Duration = Duration.ofSeconds(10),
        ): Result<OrbitHttpLink> = runCatching {
            val root = baseUrl.trimEnd('/')

            // ① 루트에서 CSRF 쿠키를 받는다. **없어도 계속 간다** — 배포본이 그것을 안 쓸 수 있고,
            //    그때 실패는 아래 ③ 에서 인증 실패로 정직하게 나타난다.
            val handshake = http.send(
                HttpRequest.newBuilder(URI.create("$root/")).timeout(timeout).GET().build(),
                HttpResponse.BodyHandlers.discarding(),
            )
            val csrf = handshake.headers().allValues("set-cookie")
                .firstNotNullOfOrNull { cookie ->
                    cookie.split(";").firstOrNull { it.trim().startsWith("$CSRF=") }?.trim()?.removePrefix("$CSRF=")
                }

            val link = OrbitHttpLink(root, token, csrf, http, timeout)

            // ③ 토큰 확인. 벤더 클라이언트와 같은 경로다.
            val authenticated = link.send(link.base("$API/api_token/authenticate/").header("Accept", JSON).GET().build())
            if (authenticated.statusCode() / 100 != 2) {
                error("Orbit 인증에 실패했다: HTTP ${authenticated.statusCode()} — 토큰을 인스턴스에서 발급받아야 한다")
            }
            link
        }

        private val mapper = ObjectMapper()

        private fun JsonNode.textOrNull(): String? = takeIf { it.isTextual }?.asText()?.takeIf { it.isNotEmpty() }
    }
}
