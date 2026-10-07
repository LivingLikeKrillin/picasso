package dev.picasso.registry.web

import dev.picasso.registry.Fixtures
import dev.picasso.registry.PostgresSupport
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
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 시험 요청의 세 문(picasso-ops P2·S1d 스펙 §5.1). **요청은 조작 토큰, 집기·보고는 적재 토큰이다.**
 *
 * 서비스 시험은 결과 타입을 보고, 이 시험은 그 타입이 응답 코드로 옮겨졌는지와 토큰이 문을 가르는지 본다. 운영자
 * 토큰으로 결과를 적을 수 있으면 «실행기만 결과를 적는다» 가 무너진다.
 */
@SpringBootTest(
    classes = [RegistryApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class TestRequestEndpointTest {

    @Autowired
    private lateinit var rest: TestRestTemplate

    @LocalServerPort
    private var port: Int = 0

    private var revision: Long = 0

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        val db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        SkillTypeSync(db).sync(Fixtures.descriptor(), SEMVER, "sync")
        revision = (RevisionService(db, Fixtures.validator()).submit(Fixtures.good(), "op") as SubmitOutcome.Stored).profileRevisionId
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

    private fun request(id: Long = revision) = call(HttpMethod.POST, "/operations/profile-revisions/$id/test-requests")

    private fun claim(worker: String = "site-runner", token: String? = INGEST_TOKEN) =
        call(HttpMethod.POST, "/ingest/test-requests/claim", """{"worker":"$worker"}""", token = token, actor = null)

    private fun report(requestId: Long, claimedAt: String, results: String = RESULTS, worker: String = "site-runner", token: String? = INGEST_TOKEN) =
        call(
            HttpMethod.POST, "/ingest/test-requests/$requestId/results",
            """{"worker":"$worker","claimed_at":"$claimedAt","results":$results}""", token = token, actor = null,
        )

    private fun field(json: String, key: String): String = Regex(""""$key"\s*:\s*"?([^",}]+)""").find(json)!!.groupValues[1]

    @Test
    fun `요청은 조작 토큰 뒤이고 새로 만들면 201, 다시 요청하면 같은 요청 200 이다`() {
        assertEquals(401, call(HttpMethod.POST, "/operations/profile-revisions/$revision/test-requests", token = INGEST_TOKEN).statusCode.value())

        val first = request()
        val second = request()

        assertEquals(201, first.statusCode.value(), first.body)
        assertEquals(200, second.statusCode.value(), second.body)
        assertEquals(field(first.body!!, "request_id"), field(second.body!!, "request_id"))
    }

    @Test
    fun `X-Actor 없이 요청하면 400 이다`() {
        assertEquals(400, call(HttpMethod.POST, "/operations/profile-revisions/$revision/test-requests", actor = null).statusCode.value())
    }

    @Test
    fun `없는 개정판은 404, DRAFT 는 409 다`() {
        assertEquals(404, request(999_999).statusCode.value())

        PostgresSupport.execute("UPDATE profile_revision SET status = 'DRAFT' WHERE profile_revision_id = $revision")
        val r = request()
        assertEquals(409, r.statusCode.value(), r.body)
        assertTrue(r.body!!.contains("\"status\":\"DRAFT\""), r.body)
    }

    @Test
    fun `집기는 적재 토큰 뒤이고 집을 것이 없으면 204 다`() {
        assertEquals(401, claim(token = OPERATOR_TOKEN).statusCode.value())
        assertEquals(204, claim().statusCode.value())
    }

    @Test
    fun `집으면 요청 id, 집은 시각, 제출된 문서를 준다`() {
        request()
        val r = claim()

        assertEquals(200, r.statusCode.value(), r.body)
        assertTrue(Regex(""""claimed_at":"\d{4}-\d{2}-\d{2}T""").containsMatchIn(r.body!!), r.body)
        assertTrue(r.body!!.contains("\"profile_revision_id\":$revision"), r.body)
        assertTrue(r.body!!.contains("\\\"vendor\\\""), "문서가 문자열로 실리지 않았다: ${r.body}")
    }

    @Test
    fun `보고는 적재 토큰 뒤이고 받으면 200 과 보고 뒤의 상태다`() {
        val requestId = field(request().body!!, "request_id").toLong()
        val claimedAt = field(claim().body!!, "claimed_at")

        assertEquals(401, report(requestId, claimedAt, token = OPERATOR_TOKEN).statusCode.value())
        val r = report(requestId, claimedAt)
        assertEquals(200, r.statusCode.value(), r.body)
        assertTrue(r.body!!.contains("\"status\":\"TESTED\""), r.body)
    }

    @Test
    fun `보고의 409 는 reason 으로 갈린다`() {
        val requestId = field(request().body!!, "request_id").toLong()
        val claimedAt = field(claim().body!!, "claimed_at")

        val other = report(requestId, claimedAt, worker = "other-runner")
        assertEquals(409, other.statusCode.value(), other.body)
        assertTrue(other.body!!.contains("\"reason\":\"NOT_CLAIMER\""), other.body)

        report(requestId, claimedAt)
        val again = report(requestId, claimedAt)
        assertEquals(409, again.statusCode.value(), again.body)
        assertTrue(again.body!!.contains("\"reason\":\"COMPLETED\""), again.body)
    }

    @Test
    fun `스위트가 모자라거나 시각이 틀리면 400, 없는 요청은 404 다`() {
        val requestId = field(request().body!!, "request_id").toLong()
        val claimedAt = field(claim().body!!, "claimed_at")

        assertEquals(400, report(requestId, claimedAt, results = """[{"suite":"CONTRACT","result":"PASS"}]""").statusCode.value())
        assertEquals(400, report(requestId, "어제").statusCode.value())
        assertEquals(404, report(999_999, claimedAt).statusCode.value())
    }

    private companion object {
        const val INGEST_TOKEN = "ingest-secret"
        const val OPERATOR_TOKEN = "operator-secret"
        val SEMVER: String = dev.picasso.contracts.wire.ContractIdentity.semver

        const val RESULTS = """[{"suite":"CONTRACT","result":"PASS","detail":{"checks":3,"failures":[]}},""" +
            """{"suite":"NEGATIVE","result":"PASS","detail":{"checks":5,"failures":[]}},""" +
            """{"suite":"DETERMINISM","result":"PASS","detail":{"checks":2,"failures":[]}}]"""

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
