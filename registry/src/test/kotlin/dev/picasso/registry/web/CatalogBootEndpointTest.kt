package dev.picasso.registry.web

import dev.picasso.registry.Fixtures
import dev.picasso.registry.PostgresSupport
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * registry 가 **기동하면서** 계약 카탈로그를 넣는가(설계 §8.3 ④, picasso-ops P2·S1d 스펙 §6.1).
 *
 * 다른 표면 시험은 시험마다 스키마를 지우고 손으로 동기화하므로 기동 동기화를 못 본다. 이 시험은 기동 전에 스키마를
 * 세우고([properties]) 기동 뒤에는 아무것도 지우지 않는다 — 손으로 넣은 것이 없으니 보이는 행은 기동이 넣은 것이다.
 */
@SpringBootTest(
    classes = [RegistryApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class CatalogBootEndpointTest {

    @Autowired
    private lateinit var rest: TestRestTemplate

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `기동하면 스킬 종류가 계약과 같고 감사 행위자는 registry 다`() {
        val r = rest.exchange(
            "http://localhost:$port/operations/skill-types",
            HttpMethod.GET,
            HttpEntity<String>(HttpHeaders().apply { set("Authorization", "Bearer $OPERATOR_TOKEN") }),
            String::class.java,
        )
        assertEquals(200, r.statusCode.value(), r.body)

        val contract = dev.picasso.gate.model.ContractIndex.from(Fixtures.descriptor()).skillTypes().toSet()
        val synced = PostgresSupport.queryAll("SELECT DISTINCT name FROM skill_type") { it.getString(1) }.toSet()
        assertEquals(contract, synced)
        contract.forEach { assertTrue(r.body!!.contains("\"name\":\"$it\""), "$it 이 조회에 없다: ${r.body}") }
        assertEquals(
            listOf("registry"),
            PostgresSupport.queryAll("SELECT actor FROM audit_log WHERE operation = 'SKILL_TYPE_SYNC'") { it.getString(1) },
        )
    }

    private companion object {
        const val OPERATOR_TOKEN = "operator-secret"

        // 기동 전에 스키마를 세운다. 운영에서 마이그레이션이 런처의 몫인 것과 같은 순서다. 속성 공급자 안에서 하면
        // 스프링이 그 값을 다시 읽을 때마다 지워지므로 클래스가 처음 쓰일 때 한 번만 한다.
        init {
            PostgresSupport.reset()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("picasso.db.url") { PostgresSupport.jdbcUrl }
            registry.add("picasso.db.user") { PostgresSupport.username }
            registry.add("picasso.db.password") { PostgresSupport.password }
            registry.add("picasso.operator.token") { OPERATOR_TOKEN }
        }
    }
}
