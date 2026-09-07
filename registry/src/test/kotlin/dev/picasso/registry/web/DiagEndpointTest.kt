package dev.picasso.registry.web

import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.ProfileRef
import dev.picasso.contracts.v1.Rejection
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.registry.Fixtures
import dev.picasso.registry.PostgresSupport
import dev.picasso.registry.adapter.AdapterService
import dev.picasso.registry.adapter.RegisterOutcome
import dev.picasso.registry.binding.ActivateOutcome
import dev.picasso.registry.binding.BindOutcome
import dev.picasso.registry.binding.BindingService
import dev.picasso.registry.ledger.ConsumerKind
import dev.picasso.registry.ledger.LedgerService
import dev.picasso.registry.observe.ObservationService
import dev.picasso.registry.revision.RevisionService
import dev.picasso.registry.revision.SkillTypeSync
import dev.picasso.registry.revision.SubmitOutcome
import dev.picasso.registry.store.Db
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 진단 넷이 **실제로 라우팅되고 직렬화되는가.**
 *
 * ## 이 시험 하나만 서버를 띄운다
 *
 * 진단의 거동은 `DiagnosticsTest`가 망라했다. 여기서 그것을 다시 하면
 * 스위트가 느려지고, 그러면 **결함 주입 라운드가 줄어든다** — 이 저장소가
 * 지켜 온 것이 그것이다.
 *
 * **그러나 이 시험이 없으면 "HTTP 표면"은 주장이다.** 경로 오타·질의
 * 파라미터 이름 불일치(`robot_id` vs `robotId`)·직렬화 실패는 도메인
 * 시험이 전부 통과해도 남는다. 그것만 본다.
 */
@SpringBootTest(
    classes = [RegistryApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class DiagEndpointTest {

    @Autowired
    private lateinit var rest: TestRestTemplate

    @LocalServerPort
    private var port: Int = 0

    private lateinit var db: Db
    private var revisionId: Long = 0
    private var secondRevisionId: Long = 0

    @BeforeTest
    fun seed() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        val revisions = RevisionService(db, Fixtures.validator())
        val bindings = BindingService(db)
        val adapters = AdapterService(db)
        val observations = ObservationService(db)
        SkillTypeSync(db).sync(Fixtures.descriptor(), CONTRACT_SEMVER, "sync")

        db.transaction { c ->
            c.prepareStatement(
                "INSERT INTO robot (robot_id, site_id, serial_number) VALUES ('r1','line-a','sn')",
            ).use { it.executeUpdate() }
        }
        val adapterId = adapters.registerAdapter("acme", "drv", "op")
        val version = adapters.registerVersion(adapterId, "1.0.0", CONTRACT_SEMVER, "op")
            as RegisterOutcome.Registered

        revisionId = activate(revisions, bindings, Fixtures.good(revision = 1))
        assertTrue(
            bindings.bind("r1", version.adapterVersionId, revisionId, "op") is BindOutcome.Bound,
        )
        secondRevisionId = activate(revisions, bindings, Fixtures.good(revision = 2))
        // **해제된 바인딩을 하나 만든다.** 없으면 `history=true`와
        // `history=false`가 같은 답을 내고, 그 파라미터를 안 넘기는 결함이
        // 통과한다(실측 — 그 결함이 안 잡혔다).
        assertTrue(
            bindings.bind("r1", version.adapterVersionId, secondRevisionId, "op")
                is BindOutcome.Bound,
        )

        observations.recordHeader(
            MessageHeader.newBuilder().setRobotId("r1").setCapabilityEpoch(3)
                .setProfileRef(
                    ProfileRef.newBuilder().setProfileId("fixture/minimal").setRevision(1),
                ).build(),
        )
        LedgerService(db).declare(
            "MES-A", ConsumerKind.UPSTREAM_SYSTEM, "line-a", "MES",
            listOf("pick_place@^1.2"),
        )
        observations.recordRejection(
            "r1", "consumer-a", listOf("pick_place@^9.9"),
            Rejection.newBuilder()
                .setCode(RejectionCode.REJECTION_CODE_MAJOR_MISMATCH)
                .setDetail("major가 다르다").build(),
        )
    }

    private fun activate(
        revisions: RevisionService,
        bindings: BindingService,
        documentJson: String,
    ): Long {
        val stored = revisions.submit(documentJson, "op")
        assertTrue(stored is SubmitOutcome.Stored, "$stored")
        BindingService.SUITE_NAMES.forEach {
            bindings.recordTestRun(stored.profileRevisionId, it, "PASS", "harness")
        }
        assertTrue(bindings.activate(stored.profileRevisionId, "op") is ActivateOutcome.Activated)
        return stored.profileRevisionId
    }

    private fun get(path: String): String {
        val response = rest.getForEntity("http://localhost:$port$path", String::class.java)
        assertEquals(200, response.statusCode.value(), "$path → ${response.body}")
        return response.body ?: ""
    }

    @Test
    fun `진단 1번이 선다`() {
        val body = get("/diag/bindings")
        assertTrue("\"robotId\":\"r1\"" in body, body)
        assertTrue("\"conformanceStatus\":\"UNTESTED\"" in body, body)
        assertTrue("\"robotsPerRevision\"" in body, body)
    }

    @Test
    fun `진단 1번의 질의 파라미터가 실제로 읽힌다`() {
        // **파라미터 이름을 잘못 적어도 200이 나온다.** 좁혀지는지를 봐야
        // 배선을 본 것이다.
        assertTrue("\"robotId\":\"r1\"" in get("/diag/bindings?site=line-a"))
        assertTrue("\"robotId\"" !in get("/diag/bindings?site=line-z"), "사이트 필터가 안 걸린다")

        // 이력 파라미터도 같은 이유로 **답이 달라지는지**를 본다.
        assertTrue(
            "\"active\":false" !in get("/diag/bindings"),
            "안 물었는데 해제된 것이 나온다",
        )
        assertTrue(
            "\"active\":false" in get("/diag/bindings?history=true"),
            "이력을 달라 했는데 안 준다 — 파라미터가 안 넘어간다",
        )
    }

    @Test
    fun `진단 2번이 선다`() {
        val body = get("/diag/diff?from=$revisionId&to=$secondRevisionId")
        assertTrue("\"optionalFields\":\"not_tracked\"" in body, body)
        assertTrue("\"from\":1" in body, body)
        assertTrue("\"to\":2" in body, body)
    }

    @Test
    fun `진단 3번이 선다 — 스네이크 케이스 파라미터`() {
        // `robot_id`를 `robotId`로 적으면 400이 난다. 그것이 이 시험의
        // 절반이다.
        val body = get("/diag/epochs?robot_id=r1")
        assertTrue("\"epoch\":3" in body, body)
        assertTrue("\"revision\":1" in body, body)
    }

    @Test
    fun `진단 4번이 선다 — 스네이크 케이스 파라미터`() {
        assertTrue("\"clientId\":\"consumer-a\"" in get("/diag/rejections"))
        assertTrue(
            "\"clientId\":\"consumer-a\"" in
                get("/diag/rejections?reason_code=REJECTION_CODE_MAJOR_MISMATCH"),
        )
        assertTrue(
            "\"clientId\"" !in get("/diag/rejections?reason_code=REJECTION_CODE_SKILL_ABSENT"),
            "사유 코드 필터가 안 걸린다",
        )
    }

    @Test
    fun `진단 5번이 선다`() {
        val body = get("/diag/dependents?skill=pick_place")
        assertTrue("\"consumerId\":\"MES-A\"" in body, body)
        assertTrue("\"active\":[" in body, body)
        assertTrue("\"dormant\":[]" in body, "산 것과 조용한 것이 안 갈렸다: $body")

        // (d) — 좁혀지는지를 봐야 배선을 본 것이다.
        assertTrue(
            "\"consumerId\"" !in get("/diag/dependents?skill=inspect"),
            "아무도 안 쓰는 능력에 소비자가 나온다",
        )
    }

    private companion object {
        val CONTRACT_SEMVER: String = dev.picasso.contracts.wire.ContractIdentity.semver

        /**
         * 컨테이너가 뜬 뒤에야 URL을 안다. **정적 프로퍼티로 못 박으면
         * 스프링이 먼저 읽고 빈 값을 쥔다.**
         */
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("picasso.db.url") { PostgresSupport.jdbcUrl }
            registry.add("picasso.db.user") { PostgresSupport.username }
            registry.add("picasso.db.password") { PostgresSupport.password }
        }
    }
}
