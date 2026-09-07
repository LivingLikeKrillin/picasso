package dev.picasso.registry.web

import com.google.protobuf.util.JsonFormat
import dev.picasso.contracts.v1.CapabilityRequirement
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.NegotiateRequest
import dev.picasso.contracts.v1.NegotiateResponse
import dev.picasso.contracts.v1.ProfileRef
import dev.picasso.contracts.v1.Rejection
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.StateMessage
import dev.picasso.contracts.v1.TaskSnapshot
import dev.picasso.contracts.v1.TaskState
import dev.picasso.registry.Fixtures
import dev.picasso.registry.PostgresSupport
import dev.picasso.registry.binding.ActivateOutcome
import dev.picasso.registry.binding.BindingService
import dev.picasso.registry.ledger.LedgerService
import dev.picasso.registry.revision.RevisionService
import dev.picasso.registry.revision.SkillTypeSync
import dev.picasso.registry.revision.SubmitOutcome
import dev.picasso.registry.store.Db
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 적재 표면이 **실제로 라우팅되고, 관문이 실제로 막는가.**
 *
 * ## 관문은 여기서만 증명된다
 *
 * [IngestToken]의 거동은 단위로 볼 수 있지만 **인터셉터가 그 경로에 실제로
 * 걸렸는지**는 서버를 띄워야 안다. 배선을 빠뜨린 관문은 단위 시험이 전부
 * 초록인 채로 문을 열어 둔다 — 그리고 그 문 뒤에 §9.3의 축소 판정을
 * 떠받치는 원장이 있다.
 *
 * ## 토큰이 맞을 때도 본다
 *
 * 401만 보면 **언제나 막는 관문**이 통과한다. 그러면 적재가 통째로 죽고,
 * 죽은 것은 워터마크가 늙어 축소가 막히는 모습으로만 나타난다 — 아무도
 * 그것을 관문 탓이라 생각하지 않는다.
 */
@SpringBootTest(
    classes = [RegistryApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class IngestEndpointTest {

    @Autowired
    private lateinit var rest: TestRestTemplate

    @LocalServerPort
    private var port: Int = 0

    private lateinit var db: Db
    private lateinit var ledger: LedgerService

    @BeforeTest
    fun seed() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        ledger = LedgerService(db)
        SkillTypeSync(db).sync(Fixtures.descriptor(), CONTRACT_SEMVER, "sync")
        PostgresSupport.execute(
            "INSERT INTO robot (robot_id, site_id, serial_number) VALUES ('r1','line-a','sn')",
        )
        val stored = RevisionService(db, Fixtures.validator()).submit(Fixtures.good(), "op")
        assertTrue(stored is SubmitOutcome.Stored, "$stored")
        val bindings = BindingService(db)
        BindingService.SUITE_NAMES.forEach {
            bindings.recordTestRun(stored.profileRevisionId, it, "PASS", "harness")
        }
        assertTrue(bindings.activate(stored.profileRevisionId, "op") is ActivateOutcome.Activated)
    }

    // ── 관문

    @Test
    fun `토큰이 없으면 적재가 401이다`() {
        WRITE_PATHS.forEach { (path, body) ->
            val status = post(path, body, token = null).statusCode.value()
            assertEquals(401, status, "$path 가 토큰 없이 통과했다")
        }
    }

    @Test
    fun `틀린 토큰도 401이다`() {
        WRITE_PATHS.forEach { (path, _) ->
            assertEquals(401, post(path, "{}", token = "wrong").statusCode.value(), path)
        }
    }

    @Test
    fun `진단은 토큰 없이도 선다`() {
        // §8.5의 승인 경계는 **조작**에 걸린다. 관문이 진단까지 막으면
        // read-only 표면이 죽고, 그것은 이 변경이 의도한 바가 아니다.
        val response = rest.getForEntity(
            "http://localhost:$port/diag/bindings", String::class.java,
        )
        assertEquals(200, response.statusCode.value(), response.body)
    }

    @Test
    fun `상위 표면이 능력 단위로 답한다`() {
        // §9.6의 업스트림 표면. **기체가 아니라 능력이 단위다** — 상위는
        // "3번 로봇"이 아니라 "이 공장에서 pick_place가 되는가"를 묻는다.
        //
        // 토큰 없이 서야 한다. read-only이고 §8.5의 승인 경계는 조작에 걸린다.
        bindRobot()

        val response = rest.getForEntity(
            "http://localhost:$port/catalog?site=line-a", String::class.java,
        )

        assertEquals(200, response.statusCode.value(), response.body)
        val body = response.body ?: ""
        assertTrue("\"skillType\":\"pick_place\"" in body, body)
        assertTrue("\"availableRobots\":1" in body, body)
        // §9.6이 요구하는 나머지 셋도 실려야 한다.
        assertTrue("\"minMinor\"" in body && "\"maxMinor\"" in body, body)
        assertTrue("task.parameters.verify_grasp" in body, "필수 선택 필드가 없다: $body")
    }

    @Test
    fun `다른 사이트를 물으면 비어 있다`() {
        // **line-a에 능력이 있는 상태에서 line-b를 묻는다.** 바인딩 없이
        // 물으면 두 사이트가 똑같이 비어 있어, 파라미터를 안 넘기고 상수를
        // 쓰는 결함이 통과한다 — `history`·`finished`에 이어 같은 실수를
        // 네 번째로 했다(실측).
        bindRobot()
        assertTrue("pick_place" in (get("/catalog?site=line-a") ?: ""), "씨앗이 비었다")

        assertEquals("[]", get("/catalog?site=line-b"))
    }

    private fun get(path: String): String? =
        rest.getForEntity("http://localhost:$port$path", String::class.java).body

    /** 기체 하나를 활성 개정판에 붙인다 — 카탈로그가 답할 것이 생긴다. */
    private fun bindRobot() {
        val adapters = dev.picasso.registry.adapter.AdapterService(db)
        val adapterId = adapters.registerAdapter("acme", "drv", "op")
        val version = adapters.registerVersion(adapterId, "1.0.0", CONTRACT_SEMVER, "op")
                as dev.picasso.registry.adapter.RegisterOutcome.Registered
        val revisionId = PostgresSupport.queryOne(
            "SELECT profile_revision_id FROM profile_revision WHERE status = 'ACTIVE'",
        ) { it.getLong(1) }
        dev.picasso.registry.binding.BindingService(db)
            .bind("r1", version.adapterVersionId, revisionId, "op")
    }

    // ── 실제 적재

    @Test
    fun `핸드셰이크 성공 보고가 원장을 채운다`() {
        val body = """{"request":${json(request())},"response":${json(accepted())}}"""

        val response = post("/ingest/handshake?site=line-a", body, TOKEN)

        assertEquals(200, response.statusCode.value(), response.body)
        assertTrue("\"written\":1" in (response.body ?: ""), response.body)
        assertEquals(1, ledger.activeConsumerCount("pick_place"))
    }

    @Test
    fun `site 질의 파라미터가 실제로 읽힌다`() {
        // **씨앗과 다른 site로 보낸다.** 같은 값만 쓰면 파라미터를 안 넘기고
        // 상수를 쓰는 결함이 통과한다 — `history`(3a-3)·`finished`(3b-2)에서
        // 같은 실수를 두 번 했다.
        val body = """{"request":${json(request())},"response":${json(accepted())}}"""

        post("/ingest/handshake?site=line-b", body, TOKEN)

        assertEquals(
            "line-b",
            PostgresSupport.queryOne("SELECT site FROM consumer") { it.getString(1) },
            "site가 요청이 아니라 어딘가의 상수에서 왔다",
        )
    }

    @Test
    fun `모순된 보고는 400이고 원장은 그대로다`() {
        val contradictory = accepted().toBuilder().addRejections(
            Rejection.newBuilder()
                .setCode(RejectionCode.REJECTION_CODE_SKILL_ABSENT).setDetail("x"),
        ).build()
        val body = """{"request":${json(request())},"response":${json(contradictory)}}"""

        val response = post("/ingest/handshake?site=line-a", body, TOKEN)

        assertEquals(400, response.statusCode.value(), response.body)
        assertEquals(0, ledger.activeConsumerCount("pick_place"))
    }

    @Test
    fun `본문이 계약 메시지가 아니면 400이다`() {
        val response = post(
            "/ingest/handshake?site=line-a",
            """{"request":{"nope":1},"response":{}}""",
            TOKEN,
        )

        assertEquals(400, response.statusCode.value(), response.body)
    }

    @Test
    fun `태스크 적재가 드레인을 채운다`() {
        val response = post("/ingest/task", json(stateMessage()), TOKEN)

        assertEquals(200, response.statusCode.value(), response.body)
        assertEquals(
            1,
            PostgresSupport.queryOne("SELECT count(*) FROM task WHERE NOT terminal") { it.getInt(1) },
        )
    }

    @Test
    fun `생존 보고가 관측선을 세운다`() {
        val body = """
            {"header":{"robotId":"r1","capabilityEpoch":"3"},"state":"CONNECTION_STATE_HIBERNATING"}
        """.trimIndent()

        val response = post("/ingest/liveness?software=4.1.0", body, token = TOKEN)
        assertEquals(200, response.statusCode.value(), response.body)

        // **상태와 소프트웨어까지 본다.** 행이 생겼는지만 보면 연결 상태를
        // 통째로 무시하는 구현이 통과하고, 그러면 HIBERNATING 이 관측선에서
        // 사라져 절전한 기체가 축소를 영구히 막는다.
        val row = PostgresSupport.queryOne(
            "SELECT connection_state, capability_epoch, robot_software " +
                "FROM robot_liveness WHERE robot_id = 'r1'",
        ) { Triple(it.getString(1), it.getLong(2), it.getString(3)) }
        assertEquals(Triple("CONNECTION_STATE_HIBERNATING", 3L, "4.1.0"), row)
    }

    @Test
    fun `생존 보고의 빈 software는 NULL이다`() {
        // `?software=` 만 붙어도 빈 문자열이 온다. 그것을 그대로 실으면
        // 못 읽는 기종이 "버전이 비어 있다"로 원장에 앉는다.
        val body = """{"header":{"robotId":"r1"},"state":"CONNECTION_STATE_ONLINE"}"""
        assertEquals(200, post("/ingest/liveness?software=", body, token = TOKEN).statusCode.value())

        val software = PostgresSupport.queryOne(
            "SELECT robot_software FROM robot_liveness WHERE robot_id = 'r1'",
        ) { it.getString(1) }
        assertEquals(null, software)
    }

    @Test
    fun `요구 등록이 DECLARED로 실린다`() {
        val body = """
            {"consumer_id":"MES-A","kind":"UPSTREAM_SYSTEM","site":"line-a",
             "display_name":"MES","requires":["pick_place@^1.2"]}
        """.trimIndent()

        val response = post("/requirements", body, TOKEN)

        assertEquals(200, response.statusCode.value(), response.body)
        assertEquals(1, ledger.activeConsumerCount("pick_place"))
    }

    @Test
    fun `모르는 kind는 400이다`() {
        val body = """{"consumer_id":"x","kind":"ROBOT","site":"line-a","requires":["a@^1.0"]}"""

        assertEquals(400, post("/requirements", body, TOKEN).statusCode.value())
    }

    // ── 씨앗

    private fun post(path: String, body: String, token: String?) = rest.exchange(
        "http://localhost:$port$path",
        HttpMethod.POST,
        HttpEntity(
            body,
            HttpHeaders().apply {
                add("Content-Type", "application/json")
                token?.let { add("Authorization", "Bearer $it") }
            },
        ),
        String::class.java,
    )

    private fun json(message: com.google.protobuf.Message): String =
        JsonFormat.printer().print(message)

    private fun header() = MessageHeader.newBuilder()
        .setClientId("line-controller")
        .setRobotId("r1")
        .setProfileRef(ProfileRef.newBuilder().setProfileId("fixture/minimal").setRevision(1))
        .build()

    private fun request() = NegotiateRequest.newBuilder()
        .setHeader(header())
        .setRequirement(
            CapabilityRequirement.newBuilder()
                .setClientId("line-controller").setRobotId("r1")
                .addRequirements("pick_place@^1.2"),
        ).build()

    private fun accepted() = NegotiateResponse.newBuilder().setAccepted(true).build()

    private fun stateMessage() = StateMessage.newBuilder()
        .setHeader(header())
        .addTasks(
            TaskSnapshot.newBuilder()
                .setTaskId("t1").setSkillType("pick_place")
                .setState(TaskState.TASK_STATE_RUNNING).setRevision(1),
        ).build()

    private companion object {
        // ASCII만 쓴다 — HTTP 헤더 값이 그 밖을 못 싣는다(IngestToken이 기동에서 막는다).
        const val TOKEN = "test-ingest-token"

        /**
         * **쓰기 표면 전부.** 새 적재 경로를 더하고 여기 안 더하면 관문
         * 시험이 그 경로를 안 본다 — 그것이 조용히 새는 문이다.
         */
        val WRITE_PATHS = listOf(
            "/ingest/handshake?site=line-a" to "{}",
            "/ingest/task" to "{}",
            "/ingest/liveness" to "{}",
            "/requirements" to "{}",
        )

        val CONTRACT_SEMVER: String = dev.picasso.contracts.wire.ContractIdentity.semver

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("picasso.db.url") { PostgresSupport.jdbcUrl }
            registry.add("picasso.db.user") { PostgresSupport.username }
            registry.add("picasso.db.password") { PostgresSupport.password }
            registry.add("picasso.ingest.token") { TOKEN }
        }
    }
}
