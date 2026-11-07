package dev.picasso.adapter.orbit.cli

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 배치 런처를 프로세스로 띄우는 CLI(§15.292). 인자 검증과 기동 거부는 종료 코드와 사유로, 기동은 실 포트로 본다.
 * 남쪽은 [OrbitLauncherTest] 와 같은 가짜 Orbit HTTP 다.
 */
class OrbitCliTest {

    private lateinit var orbit: HttpServer
    private lateinit var orbitUrl: String
    private var robots = """[{"hostname":"spot-a.line-a","nickname":"spot-a","robotIndex":0,"username":"admin"}]"""

    @BeforeTest
    fun startOrbit() {
        orbit = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        orbit.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            if (path == "/") exchange.responseHeaders.add("Set-Cookie", "x-csrf-token=csrf-1; Path=/")
            val (status, body) = when (path) {
                "/" -> 200 to ""
                "/api/v0/api_token/authenticate/" -> 200 to """{"ok":true}"""
                "/api/v0/robots/" -> 200 to robots
                "/api/v0/missions/" -> 200 to "[]"
                else -> 404 to """{"error":"no route"}"""
            }
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        orbit.start()
        orbitUrl = "http://127.0.0.1:${orbit.address.port}"
    }

    @AfterTest
    fun stopOrbit() = orbit.stop(0)

    private fun args(vararg extra: String) = arrayOf(
        "--orbit-url", orbitUrl, "--orbit-token", "orbit-token", "--site", "line-a",
        "--instance", "orbit-line-a", "--profile", PROFILE.toString(), "--port", "0", *extra,
    )

    @Test
    fun `인자가 맞으면 실 포트를 열고 발견한 기체를 적는다`() {
        val out = StringBuilder()
        val err = StringBuilder()
        val cli = OrbitCli()
        try {
            assertEquals(0, cli.run(args("--rediscover-seconds", "60"), out, err), err.toString())
            val running = checkNotNull(cli.started)
            assertTrue(running.port > 0)
            assertTrue("포트 ${running.port} 에서 [spot-a]" in out.toString(), out.toString())
            // 주기를 주면 다시 발견하는 스레드가 선다.
            assertTrue(checkNotNull(running.rediscovery).isAlive)
        } finally {
            cli.close()
        }
    }

    @Test
    fun `필수 인자가 없거나 값이 틀리면 기동하지 않는다`() {
        fun code(vararg a: String): Pair<Int, String> {
            val err = StringBuilder()
            return OrbitCli().run(arrayOf(*a), StringBuilder(), err) to err.toString()
        }
        val noProfile = code("--orbit-url", orbitUrl, "--orbit-token", "t", "--site", "s", "--instance", "i")
        assertEquals(2, noProfile.first)
        assertTrue("--profile" in noProfile.second, noProfile.second)

        assertEquals(2, code(*args("--port", "x")).first)
        assertEquals(2, code(*args("--rediscover-seconds", "-1")).first)
        assertEquals(2, code(*args("--bogus", "1")).first)
        assertEquals(2, code(*args().dropLast(1).toTypedArray()).first)
    }

    @Test
    fun `레지스트리를 주면서 적재 토큰이 없으면 기동하지 않는다`() {
        val err = StringBuilder()
        assertEquals(2, OrbitCli().run(args("--registry", "http://127.0.0.1:1"), StringBuilder(), err))
        assertTrue("--ingest-token" in err.toString(), err.toString())
    }

    @Test
    fun `플릿에 기체가 없으면 기동을 거부하고 사유를 적는다`() {
        robots = "[]"
        val err = StringBuilder()
        val cli = OrbitCli()
        assertEquals(1, cli.run(args(), StringBuilder(), err))
        assertTrue("기동 거부" in err.toString() && "하나도 없다" in err.toString(), err.toString())
        assertEquals(null, cli.started)
    }

    private companion object {
        val PROFILE: Path = Path.of("..", "profile", "fixtures", "minimal.json").normalize()
    }
}
