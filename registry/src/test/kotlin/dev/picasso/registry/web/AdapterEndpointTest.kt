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
 * P1 — 어댑터 제품·빌드의 조작 문. **응답 코드가 결과마다 갈리는가.**
 *
 * 서비스 시험은 결과 타입을 보고, 이 시험은 그 타입이 코드로 옮겨졌는지 본다. 400 과 409 를 접으면 운영자가
 * «고쳐서 다시» 와 «다른 버전 번호로» 를 못 가른다.
 */
@SpringBootTest(
    classes = [RegistryApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class AdapterEndpointTest {

    @Autowired
    private lateinit var rest: TestRestTemplate

    @LocalServerPort
    private var port: Int = 0

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
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

    private fun declareAdapter() = call(HttpMethod.POST, "/operations/adapters", """{"vendor":"acme","name":"drv"}""")

    private fun adapterIdOf(json: String): Long = Regex(""""adapter_id"\s*:\s*(\d+)""").find(json)!!.groupValues[1].toLong()

    /** 선언이 실패하면 다른 시험 안에서 NPE 로 터지지 않고 여기서 응답 본문과 함께 멈춘다. */
    private fun declaredAdapterId(): Long {
        val r = declareAdapter()
        assertTrue(r.statusCode.value() == 201 || r.statusCode.value() == 200, "제품 선언 실패: ${r.statusCode.value()} ${r.body}")
        return adapterIdOf(r.body!!)
    }

    private fun versions(id: Long, body: String) = call(HttpMethod.POST, "/operations/adapters/$id/versions", body)

    @Test
    fun `조작 토큰 없이는 선언도 목록도 401 이다`() {
        assertEquals(401, call(HttpMethod.POST, "/operations/adapters", """{"vendor":"a","name":"b"}""", token = null).statusCode.value())
        assertEquals(401, call(HttpMethod.GET, "/operations/adapters", token = null).statusCode.value())
        assertEquals(401, call(HttpMethod.POST, "/operations/adapters", """{"vendor":"a","name":"b"}""", token = INGEST_TOKEN).statusCode.value())
    }

    @Test
    fun `X-Actor 없이 선언하면 400 이다`() {
        val r = call(HttpMethod.POST, "/operations/adapters", """{"vendor":"a","name":"b"}""", actor = null)
        assertEquals(400, r.statusCode.value(), r.body)
        val versions = call(HttpMethod.POST, "/operations/adapters/1/versions", """{"version":"1.0.0","contract_semver":"$SEMVER"}""", actor = null)
        assertEquals(400, versions.statusCode.value(), versions.body)
    }

    @Test
    fun `제품 선언은 처음 201, 다시 200 이고 같은 adapter_id 다`() {
        val first = declareAdapter()
        val again = declareAdapter()
        assertEquals(201, first.statusCode.value(), first.body)
        assertEquals(200, again.statusCode.value(), again.body)
        assertEquals(adapterIdOf(first.body!!), adapterIdOf(again.body!!))
        val empty = call(HttpMethod.POST, "/operations/adapters", """{"vendor":"","name":"drv"}""")
        assertEquals(400, empty.statusCode.value(), empty.body)
    }

    @Test
    fun `빌드 선언은 처음 201, 같은 내용 재요청은 200 이다`() {
        val id = declaredAdapterId()
        val body = """{"version":"1.0.0","contract_semver":"$SEMVER"}"""
        val first = versions(id, body)
        val again = versions(id, body)
        assertEquals(201, first.statusCode.value(), first.body)
        assertEquals(200, again.statusCode.value(), again.body)
        assertEquals(
            Regex(""""adapter_version_id"\s*:\s*(\d+)""").find(first.body!!)!!.groupValues[1],
            Regex(""""adapter_version_id"\s*:\s*(\d+)""").find(again.body!!)!!.groupValues[1],
        )
    }

    @Test
    fun `같은 버전에 다른 계약값이면 409 다`() {
        val id = declaredAdapterId()
        versions(id, """{"version":"1.0.0","contract_semver":"$SEMVER"}""")
        val conflict = versions(id, """{"version":"1.0.0","contract_semver":"9.9.9"}""")
        assertEquals(409, conflict.statusCode.value(), conflict.body)
        assertTrue(conflict.body!!.contains("\"existing_contract_semver\":\"$SEMVER\""), "기존 계약값을 돌려주지 않는다: ${conflict.body}")
    }

    @Test
    fun `계약 semver 형식이 틀리면 400 이다`() {
        val id = declaredAdapterId()
        val bad = versions(id, """{"version":"1.0.0","contract_semver":"nope"}""")
        assertEquals(400, bad.statusCode.value(), bad.body)
        val emptyVersion = versions(id, """{"version":"","contract_semver":"$SEMVER"}""")
        assertEquals(400, emptyVersion.statusCode.value(), emptyVersion.body)
    }

    @Test
    fun `모르는 제품의 빌드는 404 다`() {
        val r = versions(424242, """{"version":"1.0.0","contract_semver":"$SEMVER"}""")
        assertEquals(404, r.statusCode.value(), r.body)
        assertTrue(r.body!!.contains("모르는 어댑터다"), "«처리기 없음» 404 와 구별되지 않는다: ${r.body}")
    }

    @Test
    fun `목록 응답이 스펙의 모양이다`() {
        val id = declaredAdapterId()
        versions(id, """{"version":"1.0.0","contract_semver":"$SEMVER"}""")
        val list = call(HttpMethod.GET, "/operations/adapters")
        assertEquals(200, list.statusCode.value(), list.body)
        val body = list.body!!
        listOf("adapter_id", "vendor", "name", "versions", "adapter_version_id", "version", "contract_semver", "conformance", "registered_at", "registered_by")
            .forEach { key -> assertTrue(body.contains("\"$key\""), "키가 없다: $key — $body") }
        assertTrue(body.contains("\"UNTESTED\""), body)
        assertTrue(body.contains("\"engineer/kim\""), "X-Actor 가 registered_by 로 가지 않았다: $body")
        // 키만 있고 값이 엉뚱한 자리에 들어가는 결함(vendor 에 name 등)을 잡으려고 키·값 쌍을 본다.
        listOf(
            "\"vendor\":\"acme\"",
            "\"name\":\"drv\"",
            "\"version\":\"1.0.0\"",
            "\"contract_semver\":\"$SEMVER\"",
            "\"conformance\":\"UNTESTED\"",
            "\"registered_by\":\"engineer/kim\"",
            "\"versions\":[{\"adapter_version_id\":",
        ).forEach { pair -> assertTrue(body.contains(pair), "키·값 쌍이 없다: $pair — $body") }
        assertTrue(Regex(""""registered_at":"\d{4}-\d{2}-\d{2}T""").containsMatchIn(body), "registered_at 이 ISO 시각이 아니다: $body")
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
        }
    }
}
