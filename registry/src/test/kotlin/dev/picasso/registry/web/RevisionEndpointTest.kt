package dev.picasso.registry.web

import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.registry.Fixtures
import dev.picasso.registry.PostgresSupport
import dev.picasso.registry.adapter.AdapterService
import dev.picasso.registry.adapter.RegisterOutcome
import dev.picasso.registry.binding.BindingService
import dev.picasso.registry.ingest.LivenessService
import dev.picasso.registry.ingest.SiteNameReport
import dev.picasso.registry.revision.SkillTypeSync
import dev.picasso.registry.store.Db
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
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 개정판과 바인딩의 조작 문(picasso-ops P2·S1d 스펙 §6.2). **결과 타입이 응답 코드와 `reason` 으로 옮겨졌는가.**
 *
 * 서비스 시험은 결과를 보고, 이 시험은 그것이 문에서 어떻게 보이는지와 운영자 토큰 관문이 새 경로에도 걸렸는지 본다.
 * 관문은 경로 패턴으로 걸리므로 새 경로가 패턴 밖이면 단위 시험이 전부 초록인 채로 문이 열린다.
 */
@SpringBootTest(
    classes = [RegistryApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class RevisionEndpointTest {

    @Autowired
    private lateinit var rest: TestRestTemplate

    @LocalServerPort
    private var port: Int = 0

    private lateinit var db: Db
    private var build: Long = 0

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        SkillTypeSync(db).sync(Fixtures.descriptor(), SEMVER, "sync")
        val adapters = AdapterService(db)
        build = (adapters.registerVersion(adapters.registerAdapter("acme", "drv", "op"), "1.0.0", SEMVER, "op") as RegisterOutcome.Registered)
            .adapterVersionId
        PostgresSupport.execute("INSERT INTO robot (robot_id, site_id, serial_number) VALUES ('r1','line-a','sn-r1')")
    }

    private fun call(method: HttpMethod, path: String, body: String? = null, token: String? = OPERATOR_TOKEN, actor: String? = "engineer/kim") =
        rest.exchange(
            "http://localhost:$port$path",
            method,
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

    private fun submit(document: String = Fixtures.good()) = call(HttpMethod.POST, "/operations/profile-revisions", document)

    private fun activate(id: Long) = call(HttpMethod.POST, "/operations/profile-revisions/$id/activation")

    private fun bind(robot: String, body: String) = call(HttpMethod.POST, "/operations/robots/$robot/binding", body)

    private fun field(json: String, key: String): String = Regex(""""$key"\s*:\s*"?([^",}]+)""").find(json)!!.groupValues[1]

    private fun passed(id: Long) = BindingService.SUITE_NAMES.forEach { BindingService(db).recordTestRun(id, it, "PASS", "harness") }

    private fun active(): Long {
        val id = field(submit().body!!, "profile_revision_id").toLong()
        passed(id)
        assertEquals(200, activate(id).statusCode.value())
        return id
    }

    @Test
    fun `새 경로는 모두 운영자 토큰 뒤다`() {
        listOf(
            HttpMethod.GET to "/operations/skill-types",
            HttpMethod.GET to "/operations/profile-revisions",
            HttpMethod.POST to "/operations/profile-revisions",
            HttpMethod.POST to "/operations/profile-revisions/1/activation",
            HttpMethod.POST to "/operations/robots/r1/binding",
        ).forEach { (method, path) ->
            assertEquals(401, call(method, path, "{}", token = INGEST_TOKEN).statusCode.value(), path)
        }
    }

    @Test
    fun `스킬 종류는 계약 semver 와 사이트 명칭 키를 싣는다`() {
        val r = call(HttpMethod.GET, "/operations/skill-types")

        assertEquals(200, r.statusCode.value(), r.body)
        assertTrue(r.body!!.contains("\"contract_semver\":\"$SEMVER\""), r.body)
        assertTrue(Regex(""""name":"navigate_to"[^}]*"site_reference_keys":\["location"]""").containsMatchIn(r.body!!), r.body)
    }

    @Test
    fun `제출은 새로 201, 같은 문서 200, 단조 위반 409, 못 읽으면 400 이다`() {
        val first = submit()
        val again = submit()

        assertEquals(201, first.statusCode.value(), first.body)
        assertEquals("VALIDATED", field(first.body!!, "status"), first.body)
        assertEquals(200, again.statusCode.value(), again.body)
        assertEquals(field(first.body!!, "profile_revision_id"), field(again.body!!, "profile_revision_id"))

        val conflict = submit(Fixtures.good().replace("\"seconds\": 20", "\"seconds\": 21"))
        assertEquals(409, conflict.statusCode.value(), conflict.body)
        assertEquals("1", field(conflict.body!!, "highest"))

        assertEquals(400, submit("{not json").statusCode.value())
        assertEquals(400, call(HttpMethod.POST, "/operations/profile-revisions", Fixtures.good(), actor = null).statusCode.value())
    }

    @Test
    fun `검증에 실패한 문서도 201 이고 DRAFT 와 사유를 싣는다`() {
        val r = submit(Fixtures.badErrorType())

        assertEquals(201, r.statusCode.value(), r.body)
        assertEquals("DRAFT", field(r.body!!, "status"))
        assertTrue(Regex(""""reasons":\["[^"]+""").containsMatchIn(r.body!!), r.body)
    }

    @Test
    fun `목록은 상태·스위트 결과·최신 시험 요청을 싣는다`() {
        val id = field(submit().body!!, "profile_revision_id").toLong()
        BindingService(db).recordTestRun(id, "CONTRACT", "PASS", "harness")
        call(HttpMethod.POST, "/operations/profile-revisions/$id/test-requests")

        val r = call(HttpMethod.GET, "/operations/profile-revisions")

        assertEquals(200, r.statusCode.value(), r.body)
        assertTrue(r.body!!.contains("\"status\":\"VALIDATED\""), r.body)
        assertTrue(Regex(""""CONTRACT":\{"result":"PASS"""").containsMatchIn(r.body!!), r.body)
        assertTrue(Regex(""""latest_test_request":\{"request_id":\d+,"requested_by":"engineer/kim"""").containsMatchIn(r.body!!), r.body)
    }

    @Test
    fun `활성화는 200, 다시 200, 조건 미달 409, 없으면 404 다`() {
        val id = field(submit().body!!, "profile_revision_id").toLong()
        BindingService(db).recordTestRun(id, "CONTRACT", "PASS", "harness")

        val refused = activate(id)
        assertEquals(409, refused.statusCode.value(), refused.body)
        assertEquals("VALIDATED", field(refused.body!!, "status"))
        assertTrue(refused.body!!.contains("\"suites\":{\"CONTRACT\":\"PASS\"}"), refused.body)
        assertEquals(400, call(HttpMethod.POST, "/operations/profile-revisions/$id/activation", actor = null).statusCode.value())

        passed(id)
        assertEquals(200, activate(id).statusCode.value())
        val again = activate(id)
        assertEquals(200, again.statusCode.value())
        assertTrue(again.body!!.contains("\"already\":true"), again.body)

        assertEquals(404, activate(999_999).statusCode.value())
    }

    @Test
    fun `바인딩은 201, 같은 조합 200, 404 와 409 는 reason 으로 갈린다`() {
        val revision = active()
        val body = """{"adapter_version_id":$build,"profile_revision_id":$revision}"""

        assertEquals(400, call(HttpMethod.POST, "/operations/robots/r1/binding", body, actor = null).statusCode.value())
        val first = bind("r1", body)
        assertEquals(201, first.statusCode.value(), first.body)
        val again = bind("r1", body)
        assertEquals(200, again.statusCode.value(), again.body)
        assertEquals(field(first.body!!, "robot_binding_id"), field(again.body!!, "robot_binding_id"))

        mapOf(
            bind("ghost", body) to "UNKNOWN_ROBOT",
            bind("r1", """{"adapter_version_id":$build,"profile_revision_id":999999}""") to "UNKNOWN_REVISION",
            bind("r1", """{"adapter_version_id":999999,"profile_revision_id":$revision}""") to "UNKNOWN_BUILD",
        ).forEach { (r, reason) ->
            assertEquals(404, r.statusCode.value(), r.body)
            assertEquals(reason, field(r.body!!, "reason"))
        }

        val validated = field(submit(Fixtures.good(revision = 2)).body!!, "profile_revision_id")
        val notActive = bind("r1", """{"adapter_version_id":$build,"profile_revision_id":$validated}""")
        assertEquals(409, notActive.statusCode.value(), notActive.body)
        assertEquals("REVISION_NOT_ACTIVE", field(notActive.body!!, "reason"))

        PostgresSupport.execute("UPDATE robot SET retired_at = now(), retired_by = 'op', retired_reason = 'sold' WHERE robot_id = 'r1'")
        val retired = bind("r1", body)
        assertEquals(409, retired.statusCode.value(), retired.body)
        assertEquals("ROBOT_RETIRED", field(retired.body!!, "reason"))
    }

    @Test
    fun `낮은 계약의 빌드는 409 CONTRACT_TOO_OLD 이고 빈 본문은 400 이다`() {
        val revision = active()
        val adapters = AdapterService(db)
        val old = (adapters.registerVersion(adapters.registerAdapter("acme", "old", "op"), "0.0.1", "0.0.1", "op") as RegisterOutcome.Registered)
            .adapterVersionId

        val r = bind("r1", """{"adapter_version_id":$old,"profile_revision_id":$revision}""")
        assertEquals(409, r.statusCode.value(), r.body)
        assertEquals("CONTRACT_TOO_OLD", field(r.body!!, "reason"))
        assertEquals(400, bind("r1", "{}").statusCode.value())
    }

    /** 빌드 id 를 개정판 id 와 다르게 만든다. 빈 DB 에서는 둘 다 1 이라 칸을 바꿔 읽어도 초록이었다(결함 주입으로 확인). */
    @Test
    fun `바인딩 진단 행이 빌드 id, 바인딩한 이, 명칭 기록과 보고 칸을 싣는다`() {
        val revision = active()
        val adapters = AdapterService(db)
        val other = (adapters.registerVersion(adapters.registerAdapter("acme", "drv2", "op"), "2.0.0", SEMVER, "op") as RegisterOutcome.Registered)
            .adapterVersionId
        check(other != revision) { "빌드 id 와 개정판 id 가 같다 — 칸을 바꿔 읽어도 못 가른다" }
        bind("r1", """{"adapter_version_id":$other,"profile_revision_id":$revision}""")
        call(HttpMethod.POST, "/operations/site-names?robot=r1")

        val r = call(HttpMethod.GET, "/diag/bindings", token = null)

        assertEquals(200, r.statusCode.value(), r.body)
        assertEquals("$other", field(r.body!!, "adapterVersionId"))
        assertEquals("engineer/kim", field(r.body!!, "boundBy"))
        assertTrue(Regex(""""boundAt":"\d{4}-\d{2}-\d{2}T""").containsMatchIn(r.body!!), r.body)
        assertEquals("engineer/kim", field(r.body!!, "siteNamesRegisteredBy"))
        assertTrue(Regex(""""siteNamesRegisteredAt":"\d{4}-\d{2}-\d{2}T""").containsMatchIn(r.body!!), r.body)
        assertEquals("CLAIMED", field(r.body!!, "siteNames"))
        assertTrue(r.body!!.contains("\"siteNamesReportedAt\":null"), r.body)

        // 기체가 이름 셋을 안다고 답한다. 사람의 기록과 기체의 답이 따로 보여야 한다.
        LivenessService(db).record(
            MessageHeader.newBuilder().setRobotId("r1").setCapabilityEpoch(1).build(),
            ConnectionState.CONNECTION_STATE_ONLINE,
            null,
            SiteNameReport(unsupported = false, count = 3),
        )
        val answered = call(HttpMethod.GET, "/diag/bindings", token = null).body!!
        assertEquals("CONFIRMED", field(answered, "siteNames"))
        assertEquals("3", field(answered, "siteNamesCount"))
        assertEquals("false", field(answered, "siteNamesUnsupported"))
        assertTrue(Regex(""""siteNamesReportedAt":"\d{4}-\d{2}-\d{2}T""").containsMatchIn(answered), answered)
    }

    @Test
    fun `기체 거절 메시지가 기체 id 와 퇴역 시각을 값으로 찍는다`() {
        val unknown = call(HttpMethod.DELETE, "/operations/robots/ghost/retirement")
        assertEquals(404, unknown.statusCode.value(), unknown.body)
        assertTrue(unknown.body!!.contains("모르는 기체다: ghost"), unknown.body)

        call(HttpMethod.POST, "/operations/robots/r1/retirement", """{"reason":"sold"}""")
        val again = call(HttpMethod.POST, "/operations/robots", """{"robot_id":"r1","site":"line-a","serial_number":"sn-r1"}""")
        assertEquals(409, again.statusCode.value(), again.body)
        assertTrue(Regex("""r1 은 \d{4}-\d{2}-\d{2}""").containsMatchIn(again.body!!), again.body)
    }

    private companion object {
        const val INGEST_TOKEN = "ingest-secret"
        const val OPERATOR_TOKEN = "operator-secret"
        val SEMVER: String = dev.picasso.contracts.wire.ContractIdentity.semver

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("picasso.db.url") { PostgresSupport.jdbcUrl }
            registry.add("picasso.db.user") { PostgresSupport.username }
            registry.add("picasso.db.password") { PostgresSupport.password }
            registry.add("picasso.ingest.token") { INGEST_TOKEN }
            registry.add("picasso.operator.token") { OPERATOR_TOKEN }
            registry.add("picasso.profile.schema") {
                Path.of("..").toAbsolutePath().normalize().resolve("profile/schema/capability-profile.schema.json").toString()
            }
        }
    }
}
