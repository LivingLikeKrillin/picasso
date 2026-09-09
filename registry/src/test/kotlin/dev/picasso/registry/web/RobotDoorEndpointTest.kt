package dev.picasso.registry.web

import dev.picasso.registry.PostgresSupport
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 기체 등록의 **문 둘**이 실제로 갈려 있는가(ADR 37 결정 3).
 *
 * ## 이 파일에서 가장 중요한 시험
 *
 * [`본문이 출처를 정하지 못한다`] 다. 출처가 본문에서 오면 **적재 토큰을 든 현장의 기체가 스스로 "사람이 선언했다"고
 * 적을 수 있고**, 그 승격은 `OperatorToken` 이 막으려던 바로 그것이다(§15.38). 서비스 시험은 메서드를 직접 부르므로
 * 이 구멍을 못 본다 — 문을 지나야만 드러난다.
 *
 * 나머지 셋은 관문 배선이다. 인터셉터가 새 경로에 실제로 걸렸는지는 서버를 띄워야 알고, 빠뜨린 관문은 단위 시험이
 * 전부 초록인 채로 문을 열어 둔다.
 */
@SpringBootTest(
    classes = [RegistryApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class RobotDoorEndpointTest {

    @Autowired
    private lateinit var rest: TestRestTemplate

    @LocalServerPort
    private var port: Int = 0

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
    }

    private fun declare(body: String, token: String? = OPERATOR_TOKEN, actor: String? = "operator-1") =
        rest.exchange(
            "http://localhost:$port/operations/robots",
            HttpMethod.POST,
            HttpEntity(
                body,
                HttpHeaders().apply {
                    contentType = MediaType.APPLICATION_JSON
                    token?.let { set("Authorization", "Bearer $it") }
                    actor?.let { set("X-Actor", it) }
                },
            ),
            String::class.java,
        )

    private fun discover(body: String, site: String = "line-a", token: String? = INGEST_TOKEN) =
        rest.exchange(
            "http://localhost:$port/ingest/robots?site=$site",
            HttpMethod.POST,
            HttpEntity(
                body,
                HttpHeaders().apply {
                    contentType = MediaType.APPLICATION_JSON
                    token?.let { set("Authorization", "Bearer $it") }
                },
            ),
            String::class.java,
        )

    private fun diag() = rest.getForEntity("http://localhost:$port/diag/robots", String::class.java)

    private fun originOf(robotId: String): String? =
        PostgresSupport.queryOne("SELECT origin FROM robot WHERE robot_id = '$robotId'") { it.getString(1) }

    // ── 관문

    @Test
    fun `적재 토큰으로는 선언하지 못하고, 조작 토큰으로는 발견을 올리지 못한다`() {
        // 신뢰 경계가 다르다. 적재 토큰은 어댑터마다 배포되어 현장에 나가 있다.
        assertEquals(401, declare(R1, token = INGEST_TOKEN).statusCode.value())
        assertEquals(401, discover("""[{"robot_id":"r2","serial_number":"sn-r2"}]""", token = OPERATOR_TOKEN).statusCode.value())
        // 한쪽만 보면 토큰을 하나로 합쳐도 통과한다.
        assertEquals(401, declare(R1, token = null).statusCode.value())
        assertEquals(401, discover("""[{"robot_id":"r2","serial_number":"sn-r2"}]""", token = null).statusCode.value())
    }

    // ── 문이 곧 출처다

    @Test
    fun `본문이 출처를 정하지 못한다`() {
        // 조작 문에 `origin` 을 실어 보낸다. **아무 일도 일어나지 않아야 한다** — 출처는 이 요청이 어느 문으로
        // 들어왔는가이고, 본문이 정하면 적재 토큰을 든 쪽이 사람의 선언을 위조할 수 있다.
        val response = declare("""{"robot_id":"r1","site":"line-a","serial_number":"sn-r1","origin":"DISCOVERED"}""")
        assertEquals(201, response.statusCode.value(), response.body)
        assertEquals("DECLARED", originOf("r1"))

        // 반대 방향도. 적재 문에 실어도 발견이다.
        assertEquals(200, discover("""[{"robot_id":"r2","serial_number":"sn-r2","origin":"DECLARED"}]""").statusCode.value())
        assertEquals("DISCOVERED", originOf("r2"))
    }

    @Test
    fun `선언은 201 이고 상태는 사람의 말이다`() {
        val response = declare(R1)
        assertEquals(201, response.statusCode.value())
        // **"REGISTERED" 를 박아 두면 오타 난 기체로 선언한 화면이 초록이다.** 등록한 뒤의 실제 상태를 낸다.
        assertTrue("CLAIMED" in (response.body ?: ""), response.body ?: "")

        // 같은 문으로 다시 들이면 갱신이다.
        assertEquals(200, declare(R1).statusCode.value())
    }

    @Test
    fun `다른 문으로 다시 들이면 409 이고, 발견은 부분 성공으로 낸다`() {
        assertEquals(201, declare(R1).statusCode.value())

        // 조작 문 쪽은 하나짜리라 상태 코드로 갈린다 — 400 과 접으면 안 된다. 이미 발견된 기체는 운영자가 고칠 것이 없다.
        assertEquals(200, discover("""[{"robot_id":"r2","serial_number":"sn-r2"}]""").statusCode.value())
        val wrongDoor = declare("""{"robot_id":"r2","site":"line-a","serial_number":"sn-r2"}""")
        assertEquals(409, wrongDoor.statusCode.value(), wrongDoor.body)
        assertTrue("DISCOVERED" in (wrongDoor.body ?: ""), wrongDoor.body ?: "")

        // 적재 문 쪽은 목록이라 200 에 사유를 담는다 — 하나가 거절돼 목록 전체가 멈추면 그 멈춤은
        // "플릿에서 사라졌다" 와 화면에서 구별되지 않는다.
        val mixed = discover("""[{"robot_id":"r1","serial_number":"sn-r1"},{"robot_id":"r3","serial_number":"sn-r3"}]""")
        assertEquals(200, mixed.statusCode.value())
        val body = mixed.body ?: ""
        assertTrue("r3" in body && "r1" in body, body)
        assertEquals("DISCOVERED", originOf("r3"))
        assertEquals("DECLARED", originOf("r1"), "거절이 조용히 갱신으로 샜다")
    }

    @Test
    fun `발견 문에 접속 정보를 실으면 거절 사유가 온다`() {
        // 결정 4 — 플릿 경유의 접속 정보는 플릿이 갖는다. 조용히 버리면 보낸 쪽은 우리가 그것을 안다고 믿는다.
        val response = discover("""[{"robot_id":"r1","serial_number":"sn-r1","endpoint":"10.0.0.1:50051"}]""")
        assertEquals(200, response.statusCode.value())
        assertTrue("플릿이 갖는다" in (response.body ?: ""), response.body ?: "")
        assertEquals(0, PostgresSupport.queryOne("SELECT count(*) FROM robot") { it.getInt(1) })
    }

    @Test
    fun `행위자를 안 주면 선언하지 못한다`() {
        // 비워 두면 감사 로그에 unknown 이 쌓이고, 그러면 조사 단서로도 못 쓴다.
        assertEquals(400, declare(R1, actor = null).statusCode.value())
    }

    // ── 진단

    @Test
    fun `진단 9번이 사람의 말과 기체의 답을 갈라 보여 준다`() {
        declare(R1)
        val body = diag().body ?: ""
        assertTrue("\"status\":\"CLAIMED\"" in body, body)
        assertTrue("\"origin\":\"DECLARED\"" in body, body)
        // 진단은 read-only 라 관문이 없다(§15.38). 그 성질이 여기서 유지되는지도 함께 본다.
        assertEquals(200, diag().statusCode.value())
    }

    private companion object {
        const val R1 = """{"robot_id":"r1","site":"line-a","serial_number":"sn-r1","endpoint":"10.0.0.1:50051"}"""
        const val INGEST_TOKEN = "ingest-secret"
        const val OPERATOR_TOKEN = "operator-secret"

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("picasso.db.url") { PostgresSupport.jdbcUrl }
            registry.add("picasso.db.user") { PostgresSupport.username }
            registry.add("picasso.db.password") { PostgresSupport.password }
            registry.add("picasso.ingest.token") { INGEST_TOKEN }
            registry.add("picasso.operator.token") { OPERATOR_TOKEN }
        }
    }
}
