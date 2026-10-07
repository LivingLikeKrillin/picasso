package dev.picasso.harness.revision

import dev.picasso.registry.Fixtures
import dev.picasso.registry.PostgresSupport
import dev.picasso.registry.revision.RevisionService
import dev.picasso.registry.revision.SkillTypeSync
import dev.picasso.registry.revision.SubmitOutcome
import dev.picasso.registry.store.Db
import dev.picasso.registry.testing.TestRequestService
import dev.picasso.registry.web.RegistryApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 실행기와 registry 의 왕복(picasso-ops P2·S1d 스펙 §3 의 P2a 완료 판정). **요청 → 집기 → 3종 → 보고 → `TESTED`.**
 *
 * registry 는 이 JVM 에 띄우되 실행기는 HTTP 로만 닿는다. 설계 문서 §3.2 가 `harness ⇢ registry` 를 런타임 접근으로
 * 두었고, 실행기의 운영 코드는 `:registry` 를 모른다.
 */
class RevisionRunnerEndToEndTest {

    private lateinit var registry: ConfigurableApplicationContext
    private lateinit var db: Db
    private lateinit var baseUrl: String
    private val schema: Path = Path.of("..", "profile", "schema", "capability-profile.schema.json").normalize()

    @BeforeTest
    fun start() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        SkillTypeSync(db).sync(Fixtures.descriptor(), dev.picasso.contracts.wire.ContractIdentity.semver, "sync")
        // harness 시험 클래스패스에는 slf4j 구현이 둘(mimic 쪽 NOP 와 Spring 의 logback) 있다. Spring 이 로깅을 세우려다
        // «LoggerFactory is not a Logback LoggerContext» 로 기동을 거부하므로 Spring 의 로깅 초기화만 끈다.
        System.setProperty("org.springframework.boot.logging.LoggingSystem", "none")
        registry = SpringApplicationBuilder(RegistryApplication::class.java).run(
            "--server.port=0",
            "--server.address=127.0.0.1",
            "--picasso.db.url=${PostgresSupport.jdbcUrl}",
            "--picasso.db.user=${PostgresSupport.username}",
            "--picasso.db.password=${PostgresSupport.password}",
            "--picasso.operator.token=$OPERATOR",
            "--picasso.ingest.token=$INGEST",
        )
        baseUrl = "http://127.0.0.1:${(registry as WebServerApplicationContext).webServer.port}"
    }

    @AfterTest
    fun stop() = registry.close()

    private fun submitted(name: String): Long {
        val document = Files.readString(Path.of("..", "profile", "profiles", "$name.json").normalize())
        val stored = RevisionService(db, Fixtures.validator()).submit(document, "engineer/kim") as SubmitOutcome.Stored
        assertEquals("VALIDATED", statusOf(stored.profileRevisionId), "사유: ${stored.reasons}")
        return stored.profileRevisionId
    }

    private fun runner(token: String = INGEST) = RevisionTestRunner(HttpTestDesk(baseUrl, token), RevisionSuites(schema), "site-runner")

    @Test
    fun `humanoid-a 와 quadruped-b 가 요청 → 집기 → 3종 → 보고로 TESTED 가 된다`() {
        listOf("humanoid-a", "quadruped-b").forEach { name ->
            val revision = submitted(name)
            TestRequestService(db).request(revision, "engineer/kim")

            assertTrue(runner().pollOnce(), "$name: 집지 못했다")

            assertEquals("TESTED", statusOf(revision), name)
            val runs = PostgresSupport.queryAll(
                "SELECT suite, result, ran_by, request_id IS NOT NULL, (detail->>'checks')::int FROM revision_test_run " +
                    "WHERE profile_revision_id = $revision ORDER BY suite",
            ) { "${it.getString(1)}=${it.getString(2)} by ${it.getString(3)} req=${it.getBoolean(4)} checks>0=${it.getInt(5) > 0}" }
            assertEquals(
                listOf("CONTRACT", "DETERMINISM", "NEGATIVE").map { "$it=PASS by site-runner req=true checks>0=true" },
                runs,
                name,
            )
        }
        assertFalse(runner().pollOnce(), "끝난 요청이 다시 집혔다")
    }

    @Test
    fun `창구가 보고의 409 를 끝남과 남의 요청으로 가른다`() {
        // 실행기는 둘을 다르게 다룬다. 다시 보낸 보고의 «끝남» 은 앞 보고가 반영된 것이고, «남의 요청» 은 버린다(스펙 §5.2).
        TestRequestService(db).request(submitted("quadruped-b"), "engineer/kim")
        val desk = HttpTestDesk(baseUrl, INGEST)
        val claimed = desk.claim("site-runner")!!
        val outcomes = RevisionSuites(schema).run(Files.createTempFile("q-", ".json").also { Files.writeString(it, claimed.document) })

        assertEquals(ReportReply.NotClaimer, desk.report(claimed.requestId, "other-runner", claimed.claimedAt, outcomes))
        assertEquals(ReportReply.Recorded("TESTED"), desk.report(claimed.requestId, "site-runner", claimed.claimedAt, outcomes))
        assertEquals(ReportReply.Completed, desk.report(claimed.requestId, "site-runner", claimed.claimedAt, outcomes))
    }

    @Test
    fun `운영자 토큰으로는 집지 못한다`() {
        val requestId = TestRequestService(db).request(submitted("humanoid-a"), "engineer/kim")

        assertFalse(runner(token = OPERATOR).pollOnce())
        assertEquals(null, TestRequestService(db).claimedBy(requestId), "운영자 토큰으로 요청이 집혔다")
        assertEquals(0, PostgresSupport.queryOne("SELECT count(*) FROM revision_test_run") { it.getInt(1) })
    }

    private fun statusOf(id: Long): String = PostgresSupport.queryOne(
        "SELECT status FROM profile_revision WHERE profile_revision_id = $id",
    ) { it.getString(1) }

    private companion object {
        const val OPERATOR = "operator-secret"
        const val INGEST = "ingest-secret"
    }
}
