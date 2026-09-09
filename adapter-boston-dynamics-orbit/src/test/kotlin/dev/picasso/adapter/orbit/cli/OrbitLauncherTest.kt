package dev.picasso.adapter.orbit.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.picasso.adapter.orbit.OrbitHttpLink
import dev.picasso.client.PicassoClient
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.TaskState
import dev.picasso.uplink.RecordingPublisher
import dev.picasso.uplink.report.DiscoveryAck
import dev.picasso.uplink.report.RobotDiscovery
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * **배치가 실제로 선다.** 런처가 플릿에 붙어 기체를 발견하고 계약 뒤에 세우며, 소비자가 **실 포트로** 그것을
 * 두드려 일을 시킨다.
 *
 * ## 이 시험이 처음 지나는 것
 *
 * 여태 조립은 시험 안에만 있었다 — `HostedRobot` 을 손으로 만들고 in-process 전송으로 붙였다. 여기서는
 * **런처가 조립하고 실 TCP 포트가 열린다.** 남쪽만 스텁이고(가짜 Orbit HTTP) 그 사이 전부가 진짜다:
 * `PicassoClient` → gRPC/netty → `AdapterHost` → `OrbitAdapter` → `OrbitHttpLink` → HTTP.
 *
 * **진짜 Orbit 인스턴스는 여전히 아니다**(C-3). 스텁이 답하는 모양은 우리가 벤더 문서에서 읽어 적은 것이다.
 */
class OrbitLauncherTest {

    private lateinit var orbit: HttpServer
    private lateinit var orbitUrl: String
    private val routes = mutableMapOf<String, Pair<Int, String>>()
    private val received = mutableListOf<String>()

    /** 파견 뒤에 실행이 끝난 것으로 답할지. 벤더의 시간 축을 시험이 쥔다. */
    private var runEnded = false

    @BeforeTest
    fun startOrbit() {
        orbit = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        orbit.createContext("/") { handle(it) }
        orbit.start()
        orbitUrl = "http://127.0.0.1:${orbit.address.port}"

        routes["/"] = 200 to ""
        routes["/api/v0/api_token/authenticate/"] = 200 to """{"ok":true}"""
        routes["/api/v0/robots/"] = 200 to """[
            {"hostname":"spot-a.line-a","nickname":"spot-a","robotIndex":0,"username":"admin"},
            {"hostname":"spot-b.line-a","nickname":"spot-b","robotIndex":1,"username":"admin"}]"""
        routes["/api/v0/missions/"] = 200 to """[{"uuid":"m-1","name":"DOCK-3"}]"""
        routes["/api/v0/calendar/mission/dispatch/spot-a"] = 200 to """{"uuid":"run-1"}"""
        routes["/api/v0/run_events/"] = 200 to """{"resources":[{"uuid":"e-1","actionName":"dock","error":0}]}"""
    }

    @AfterTest
    fun stopOrbit() = orbit.stop(0)

    private fun handle(exchange: HttpExchange) {
        received += "${exchange.requestMethod} ${exchange.requestURI.path}"
        if (exchange.requestURI.path == "/") {
            exchange.responseHeaders.add("Set-Cookie", "x-csrf-token=csrf-1; Path=/")
        }
        val payload = if (exchange.requestURI.path == "/api/v0/runs/") {
            // 실행은 시험이 끝냈다고 할 때까지 안 끝난다 — 종착은 `endTime` 이 정한다.
            val end = if (runEnded) ""","endTime":"2026-09-10T00:05:00Z"""" else ""
            """{"resources":[{"uuid":"run-1","robotNickname":"spot-a","startTime":"2026-09-10T00:00:00Z"$end}]}"""
        } else {
            routes[exchange.requestURI.path]?.second ?: """{"error":"no route"}"""
        }
        val status = if (exchange.requestURI.path == "/api/v0/runs/") 200 else (routes[exchange.requestURI.path]?.first ?: 404)
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun options() = OrbitLauncher.Options(
        orbitUrl = orbitUrl,
        token = "orbit-token",
        site = "line-a",
        instance = "orbit-line-a",
        profile = PROFILE,
        port = 0,
        // 시험이 걸음을 센다 — 스케줄러가 돌면 스위트가 벽시계에 매인다(§12.1 과 같은 이유).
        pumpMillis = 0,
    )

    @Test
    fun `런처가 플릿에서 발견한 기체들을 실 포트의 계약 뒤에 세운다`() {
        val discovered = mutableListOf<String>()
        val sink = RobotDiscovery { site, instance, robots ->
            discovered += robots.map { "$site/$instance/${it.robotId}" }
            DiscoveryAck(robots.map { it.robotId }, emptyMap())
        }
        val published = RecordingPublisher()

        OrbitLauncher(options()).start(publisher = published, discovery = sink).getOrThrow().use { running ->
            // **발견이 먼저다.** 무엇을 세울지가 그 답에서 온다.
            assertEquals(listOf("line-a/orbit-line-a/spot-a", "line-a/orbit-line-a/spot-b"), discovered)
            assertEquals(listOf("spot-a", "spot-b"), running.host.robots.keys.sorted())
            assertTrue(running.port > 0, "실 포트가 안 열렸다")

            // **포트가 열린 뒤에 ONLINE 이 나간다**(§10.2) — 기체마다 하나씩.
            assertEquals(2, published.publications.count { it.retained })

            val channel: ManagedChannel = ManagedChannelBuilder.forAddress("127.0.0.1", running.port).usePlaintext().build()
            try {
                val client = PicassoClient(channel, "line-controller")

                // 한 프로세스가 기체 여럿을 든다 — 소비자는 헤더의 robot_id 로 지목한다.
                assertTrue(client.capabilities("spot-a").skillsList.any { it.skillType == "navigate_to" })
                assertTrue(client.capabilities("spot-b").skillsList.isNotEmpty())

                // **일을 시킨다.** 계약의 location 이 저작된 미션의 이름으로 읽혀 파견된다.
                val handle = client.start(
                    "spot-a", "t-1", revision = 1, skillType = "navigate_to",
                    parameters = listOf(ParameterValue.newBuilder().setKey("location").setStringValue("DOCK-3").build()),
                )
                assertTrue(handle.hasHandle(), handle.rejection.toString())
                assertTrue("POST /api/v0/calendar/mission/dispatch/spot-a" in received, received.toString())

                // **스트림을 먼저 연다.** 열기 전에 일어난 전이는 되짚어 오고, 그 뒤의 것은 밀려 온다.
                val follower = client.follow("spot-a", handle.handle)

                // **스냅샷은 못 준다.** 플릿이 기체 결함을 안 나르므로 호스트가 `UNAVAILABLE` 로 답한다 —
                // *결함 없음* 으로 접지 않기로 한 결과가 여기서 계약 면에 그대로 나타난다(§15.99).
                val blind = assertFailsWith<StatusRuntimeException> { client.snapshot("spot-a") }
                assertEquals(Status.Code.UNAVAILABLE, blind.status.code)

                // 실행이 끝나면 종착이 계약으로 올라온다. 펌프는 **아무 RPC 나** 지나므로 아는 이름 질의로 민다.
                runEnded = true
                assertEquals(listOf("DOCK-3"), client.knownSiteNames("spot-a").namesList)

                // 실 소켓이라 밀려 오는 데 시간이 든다. **완료를 기다리되 무한정은 아니다** — 안 오면 시험이
                // 정지가 아니라 실패로 끝나야 한다.
                val deadline = System.nanoTime() + 5_000_000_000L
                while (!follower.completed && System.nanoTime() < deadline) Thread.sleep(20)
                assertTrue(follower.completed, "종착 스트림이 안 닫혔다: ${follower.updates.map { it.state }}")
                assertEquals(TaskState.TASK_STATE_SUCCEEDED, follower.updates.last().state)
            } finally {
                channel.shutdownNow()
            }
        }
    }

    @Test
    fun `플릿에 못 붙으면 포트를 열지 않는다`() {
        routes["/api/v0/api_token/authenticate/"] = 401 to """{"error":"bad token"}"""

        val failure = OrbitLauncher(options()).start()
        // **붙지도 못하면서 포트를 열면 소비자가 아무것도 못 하는 기체를 본다.**
        assertTrue(failure.isFailure)
        assertTrue("못 붙었다" in (failure.exceptionOrNull()?.message ?: ""), failure.exceptionOrNull()?.message ?: "")
    }

    @Test
    fun `플릿에 기체가 없으면 세울 것이 없다고 한다`() {
        routes["/api/v0/robots/"] = 200 to "[]"

        val failure = OrbitLauncher(options()).start()
        assertTrue(failure.isFailure)
        // 빈 채로 포트만 여는 것은 "기체가 있는데 안 붙는다" 와 화면에서 구별되지 않는다.
        assertTrue("하나도 없다" in (failure.exceptionOrNull()?.message ?: ""), failure.exceptionOrNull()?.message ?: "")
    }

    private companion object {
        val PROFILE: Path = Path.of("..", "profile", "fixtures", "minimal.json").normalize()
    }
}
