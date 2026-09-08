package dev.picasso.registry.web

import dev.picasso.registry.Fixtures
import dev.picasso.registry.PostgresSupport
import dev.picasso.registry.adapter.AdapterService
import dev.picasso.registry.adapter.RegisterOutcome
import dev.picasso.registry.binding.ActivateOutcome
import dev.picasso.registry.binding.BindOutcome
import dev.picasso.registry.binding.BindingService
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
 * 조작 표면이 **실제로 라우팅되고, 조작 관문이 적재 관문과 다른가.**
 *
 * ## 이 파일에서 가장 중요한 시험
 *
 * [`적재 토큰으로는 조작하지 못한다`]다. 적재 토큰은 어댑터마다 배포되어
 * **현장에 나가 있고**, 그것으로 §8.5의 조작까지 되면 기체 하나가 운영자
 * 조작을 수행할 수 있다. 두 관문을 한 인터셉터로 합치거나 토큰을 하나로
 * 두면 그 승격이 생기고, **코드 어디에도 안 적히므로 안 보인다.**
 *
 * ## 관문이 배선됐는지는 서버를 띄워야 안다
 *
 * [OperatorToken]의 거동은 단위로 볼 수 있지만 인터셉터가 그 경로에 실제로
 * 걸렸는지는 여기서만 드러난다 — 배선을 빠뜨린 관문은 단위 시험이 전부
 * 초록인 채로 문을 열어 둔다.
 */
@SpringBootTest(
    classes = [RegistryApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class OperationsEndpointTest {

    @Autowired
    private lateinit var rest: TestRestTemplate

    @LocalServerPort
    private var port: Int = 0

    private lateinit var db: Db
    private var adapterVersionId: Long = 0

    @BeforeTest
    fun seed() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        SkillTypeSync(db).sync(Fixtures.descriptor(), CONTRACT_SEMVER, "sync")

        val adapters = AdapterService(db)
        val adapterId = adapters.registerAdapter("acme", "drv", "op")
        val version = adapters.registerVersion(adapterId, "1.0.0", CONTRACT_SEMVER, "op")
        assertTrue(version is RegisterOutcome.Registered, "$version")
        adapterVersionId = version.adapterVersionId
    }

    private fun activate(document: String): Long {
        val bindings = BindingService(db)
        val stored = RevisionService(db, Fixtures.validator()).submit(document, "op")
        assertTrue(stored is SubmitOutcome.Stored, "$stored")
        BindingService.SUITE_NAMES.forEach { bindings.recordTestRun(stored.profileRevisionId, it, "PASS", "harness") }
        assertTrue(bindings.activate(stored.profileRevisionId, "op") is ActivateOutcome.Activated)
        return stored.profileRevisionId
    }

    private fun bound(id: String, document: String = Fixtures.good()) {
        PostgresSupport.execute(
            "INSERT INTO robot (robot_id, site_id, serial_number) VALUES ('$id','line-a','sn-$id')",
        )
        assertTrue(BindingService(db).bind(id, adapterVersionId, activate(document), "op") is BindOutcome.Bound)
    }

    private fun post(robot: String, token: String?, actor: String? = "operator-1") =
        rest.exchange(
            "http://localhost:$port/operations/site-names?robot=$robot",
            HttpMethod.POST,
            HttpEntity<String>(
                null,
                HttpHeaders().apply {
                    token?.let { set("Authorization", "Bearer $it") }
                    actor?.let { set("X-Actor", it) }
                },
            ),
            String::class.java,
        )

    private fun get(robot: String, token: String?) =
        rest.exchange(
            "http://localhost:$port/operations/site-names?robot=$robot",
            HttpMethod.GET,
            HttpEntity<String>(
                null,
                HttpHeaders().apply { token?.let { set("Authorization", "Bearer $it") } },
            ),
            String::class.java,
        )

    // ── 관문 (핵심)

    @Test
    fun `적재 토큰으로는 조작하지 못한다`() {
        // **신뢰 경계가 다르다.** 적재 토큰은 모든 기체가 들고 있으므로,
        // 그것으로 조작이 되면 현장의 기체가 운영자 조작을 할 수 있다.
        bound("r1")
        assertEquals(401, post("r1", token = INGEST_TOKEN).statusCode.value())
    }

    @Test
    fun `조작 토큰으로는 적재하지 못한다`() {
        // 반대 방향도 막는다. 한쪽만 보면 토큰을 하나로 합쳐도 통과한다.
        val response = rest.exchange(
            "http://localhost:$port/requirements",
            HttpMethod.POST,
            HttpEntity(
                """{"consumer_id":"c1","kind":"CLIENT","site":"line-a","display_name":null,"requires":[]}""",
                HttpHeaders().apply {
                    set("Authorization", "Bearer $OPERATOR_TOKEN")
                    contentType = org.springframework.http.MediaType.APPLICATION_JSON
                },
            ),
            String::class.java,
        )
        assertEquals(401, response.statusCode.value())
    }

    @Test
    fun `토큰이 없거나 틀리면 401이다`() {
        bound("r1")
        assertEquals(401, post("r1", token = null).statusCode.value())
        assertEquals(401, post("r1", token = "wrong").statusCode.value())
        assertEquals(401, get("r1", token = null).statusCode.value())
    }

    // ── 거동

    @Test
    fun `기록하면 200과 등록 대상을 낸다`() {
        // **토큰이 맞을 때도 본다.** 401만 보면 "언제나 막는 관문" 이 통과하고,
        // 그러면 조작이 통째로 죽은 것을 아무도 모른다.
        bound("r1")

        val response = post("r1", token = OPERATOR_TOKEN)
        assertEquals(200, response.statusCode.value())
        val body = response.body.orEmpty()
        // **"REGISTERED" 가 아니라 "CLAIMED" 다.** 기록한 순간에는 사람의
        // 말뿐이고 기체는 아직 답한 적이 없다 — 그것을 성공으로 적으면 자기
        // 신고가 관측인 척한다(ADR 35).
        assertTrue("CLAIMED" in body, body)
        assertTrue("location" in body && "object_id" in body && "destination" in body, body)
    }

    @Test
    fun `등록할 것이 없으면 409다`() {
        // 400이 아닌 것이 요점이다 — 요청이 틀린 것이 아니라 이 기체의
        // 상태에서 뜻이 없는 조작이다. 400으로 내면 요청을 고치려 든다.
        bound("r-move", Fixtures.moveOnly())

        val response = post("r-move", token = OPERATOR_TOKEN)
        assertEquals(409, response.statusCode.value())
        assertTrue("NOT_REQUIRED" in response.body.orEmpty())
    }

    @Test
    fun `바인딩이 없으면 404다`() {
        PostgresSupport.execute(
            "INSERT INTO robot (robot_id, site_id, serial_number) VALUES ('r-none','line-a','sn-none')",
        )
        assertEquals(404, post("r-none", token = OPERATOR_TOKEN).statusCode.value())
    }

    @Test
    fun `행위자를 안 주면 거절한다`() {
        // 위조 가능한 이름이지만(§15.3) 없는 것보다 낫다 — 비워 두면 감사
        // 로그가 조사 단서로도 못 쓰인다.
        bound("r1")
        assertEquals(400, post("r1", token = OPERATOR_TOKEN, actor = null).statusCode.value())
    }

    @Test
    fun `조작이 감사 로그에 남는다`() {
        bound("r1")
        assertEquals(200, post("r1", token = OPERATOR_TOKEN).statusCode.value())

        val rows = db.transaction { c ->
            c.prepareStatement(
                "SELECT actor FROM audit_log WHERE operation = 'SITE_NAMES_REGISTERED' AND subject = 'r1'",
            ).use { st ->
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
        }
        assertEquals(listOf("operator-1"), rows)
    }

    @Test
    fun `미리보기가 상태와 대상을 낸다`() {
        bound("r1")
        val body = get("r1", token = OPERATOR_TOKEN).body.orEmpty()
        assertTrue("UNREGISTERED" in body, body)
        assertTrue("location" in body, body)
    }

    private companion object {
        val CONTRACT_SEMVER: String = dev.picasso.contracts.wire.ContractIdentity.semver
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
