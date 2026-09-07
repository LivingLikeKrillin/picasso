package dev.picasso.harness

import dev.picasso.mimic.report.TaskObservations
import dev.picasso.gate.CheckResult
import dev.picasso.gate.GateRunner
import dev.picasso.gate.checks.Check06Vocabulary
import dev.picasso.gate.input.GateInput
import dev.picasso.profile.ProfileDocument
import dev.picasso.profile.ProfileKey
import dev.picasso.profile.RequirementSet
import dev.picasso.registry.Fixtures
import dev.picasso.registry.PostgresSupport
import dev.picasso.registry.binding.ActivateOutcome
import dev.picasso.registry.binding.BindingService
import dev.picasso.registry.ingest.HandshakeIngestOutcome
import dev.picasso.registry.ingest.HandshakeIngestService
import dev.picasso.registry.ingest.TaskIngestService
import dev.picasso.registry.ledger.LedgerService
import dev.picasso.registry.ledger.RegistryLedgerQuery
import dev.picasso.registry.observe.ObservationService
import dev.picasso.registry.revision.RevisionService
import dev.picasso.registry.revision.SkillTypeSync
import dev.picasso.registry.revision.SubmitOutcome
import dev.picasso.registry.store.Db
import dev.picasso.contracts.v1.NegotiateRequest
import dev.picasso.contracts.v1.NegotiateResponse
import dev.picasso.contracts.v1.ParameterValue
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **§15.40이 닫혔다는 관측.**
 *
 * 그 항목이 적은 것은 이랬다 — *"원장의 두 관측선에 운영 생산자가 없다.
 * 그래서 운영에서는 §9.3의 두 조회가 모두 `NotObservable`이고, 검사 6번은
 * 축소를 승인도 거부도 하지 않는다."*
 *
 * 여기서 보는 것은 그 두 관측선이 **실제로 채워지고, 그 결과로 검사 6번의
 * 판정이 바뀌는가**다:
 *
 *  1. `client`가 진짜로 협상한다 → `mimic`이 보고한다 → 원장에 `OBSERVED`
 *  2. 소비자가 남아 있으므로 **축소가 거부된다**
 *  3. 소비자가 감쇠한다 → 이번엔 태스크가 돈다 → 발행이 `task`에 적재된다
 *  4. 비종착 태스크가 있으므로 **여전히 거부된다**
 *  5. 태스크가 끝난다 → 둘 다 0 → **같은 문서가 승인된다**
 *
 * 다섯이 한 흐름이어야 한다. 따로 보면 **"언제나 거부한다"가 2·4를
 * 통과하고**, 그 구현은 축소를 영원히 막아 기능을 죽인다.
 *
 * ## 무엇을 실제 경로로 쓰고 무엇을 안 쓰는가
 *
 * 협상 판정은 `mimic`이 하고(§3.2 — `registry`는 `mimic`을 모른다), 보고는
 * `HandshakeReporter`가 나른다. 태스크 전이는 [IngestBridge]가 발행을 받아
 * 넘긴다 — 브로커 자리다(§15.30).
 *
 * **HTTP는 여기서 안 지난다.** 와이어는 `IngestEndpointTest`(경로·토큰·형식)와
 * `HttpHandshakeReporterTest`(나가는 쪽)가 각각 본다. 여기서 다시 서버를
 * 띄우면 스위트가 느려지고, 그러면 결함 주입 라운드가 줄어든다 — 이 저장소가
 * 지켜 온 것이 그것이다. 여기서 볼 것은 **판정이 바뀌는가** 하나다.
 */
class LedgerIngestEndToEndTest {

    private lateinit var db: Db
    private lateinit var ledger: LedgerService
    private lateinit var handshakes: HandshakeIngestService
    private lateinit var tasks: TaskIngestService

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        ledger = LedgerService(db)
        handshakes = HandshakeIngestService(ledger, ObservationService(db))
        tasks = TaskIngestService(db)
        SkillTypeSync(db).sync(Fixtures.descriptor(), CONTRACT_SEMVER, "sync")
        PostgresSupport.execute(
            "INSERT INTO robot (robot_id, site_id, serial_number) VALUES ('$ROBOT','line-a','sn')",
        )
        val stored = RevisionService(db, Fixtures.validator()).submit(Fixtures.good(), "op")
        assertTrue(stored is SubmitOutcome.Stored, "$stored")
        val bindings = BindingService(db)
        BindingService.SUITE_NAMES.forEach {
            bindings.recordTestRun(stored.profileRevisionId, it, "PASS", "harness")
        }
        assertTrue(bindings.activate(stored.profileRevisionId, "op") is ActivateOutcome.Activated)
    }

    @Test
    fun `협상과 태스크가 적재되어 검사 6번의 판정이 바뀐다`() {
        harness().use { harness ->
            // ── 1. 진짜 협상. 판정은 mimic이 하고 보고는 보고자가 나른다.
            val response = harness.client(CLIENT).negotiate(ROBOT, requirements())
            assertTrue(response.accepted, "픽스처가 만족하지 않으면 이 시험이 뜻을 잃는다")

            assertEquals(
                1, ledger.activeConsumerCount("navigate_to"),
                "협상 성공이 원장에 안 실렸다 — §15.40의 앞 절반이 안 닫혔다",
            )
            // **요구가 빠짐없이 실렸는가.** 하나만 적재하는 구현이면 나머지
            // 스킬은 "쓰는 사람 0명"으로 보이고 그 위에서 축소가 열린다.
            assertEquals(
                listOf("navigate_to", "pick_place"),
                PostgresSupport.queryAll(
                    "SELECT skill_type_name FROM consumer_requirement ORDER BY skill_type_name",
                ) { it.getString(1) },
            )

            // ── 2. 그 스킬의 태스크가 돈다. 두 관측선이 다 살아난다.
            //
            // **`navigate_to`를 쓰는 것은 의도다.** `pick_place`에는 5%
            // `SELF_RETRIABLE`·1% `NEEDS_INTERVENTION` 실패 모드가 있고
            // 둘 다 계약상 **비종착**이라(재시도를 기다린다) 드레인이 안
            // 끝난다 — 실측으로 그 태스크가 남아 판정을 가렸다.
            harness.client(CLIENT).start(
                ROBOT, "t1", revision = 1, skillType = "navigate_to",
                parameters = parametersFor("navigate_to"),
            )
            // **전이 이벤트가 적재를 낳는다**(§15.44) — 주기 발행을 안 기다린다.
            harness.advance(Duration.ofSeconds(5))

            assertEquals(
                listOf("navigate_to"), inflightSkills(),
                "발행이 task 표에 안 실렸다 — §15.40의 뒤 절반이 안 닫혔다",
            )
            // **적재를 붙여도 발행 기록은 그대로다.** 감싼 발행자를 버리는
            // 배선이면 여기가 비고, 그러면 발행을 검증하는 다른 하네스
            // 시험들이 적재를 붙이는 순간 조용히 무의미해진다.
            assertTrue(
                harness.publisher.publications.isNotEmpty(),
                "적재 지점을 붙였더니 발행 기록이 비었다",
            )

            // ── 3. 둘 다 남았으므로 거부되고, **사유가 둘 다 실린다.**
            assertTrue(
                findings().any {
                    "축소가 거부됐다" in it && "active 소비자 1" in it && "비종착 태스크 1" in it
                },
                "두 사유가 함께 안 실렸다: ${findings()}",
            )

            // ── 4. 태스크가 끝난다. 소비자가 남아 **여전히 거부된다.**
            harness.advance(Duration.ofMinutes(5))
            assertEquals(emptyList(), inflightSkills(), "태스크가 종착하지 않았다")

            assertTrue(
                findings().any { "축소가 거부됐다" in it && "active 소비자 1" in it },
                "드레인만 끝났는데 축소가 열렸다: ${findings()}",
            )

            // ── 5. 소비자도 옮겨 간다. 둘 다 0이므로 **같은 문서가 승인된다.**
            decayConsumers()
            assertEquals(0, ledger.activeConsumerCount("navigate_to"))

            assertTrue(
                findings().any { "축소가 승인됐다" in it },
                "둘 다 0인데 축소가 안 열렸다 — 이러면 기능이 죽는다: ${findings()}",
            )
        }
    }

    @Test
    fun `발행 주기보다 짧은 태스크도 드레인에 잡힌다`() {
        // **§15.44가 닫혔다는 관측.**
        //
        // 스냅샷만 적재하면 `navigate_to`(20초)가 발행 상한(30초) 사이에
        // 시작과 종료를 마쳐 **비종착으로 한 번도 안 실린다** — 그러면 §9.3의
        // 드레인이 도는 태스크를 0으로 보고, 틀리는 방향이 축소를 **여는**
        // 쪽이다. 전이 이벤트(§4.7)를 함께 적재해야 닫힌다.
        //
        // **첫 주기 발행을 먼저 소진한다.** `publishStateIfDue()`는 첫 발행을
        // 조건 없이 내보내므로, 태스크를 바로 시작하면 스냅샷 경로가 드레인을
        // 채워 **이벤트 경로가 죽어 있어도 이 시험이 통과한다**(실측으로
        // 주입 셋이 그렇게 빠져나갔다).
        harness().use { harness ->
            harness.advance(Duration.ofSeconds(1))
            assertEquals(emptyList(), inflightSkills(), "아직 태스크가 없어야 한다")

            harness.client(CLIENT).start(
                ROBOT, "t1", revision = 1, skillType = "navigate_to",
                parameters = parametersFor("navigate_to"),
            )
            // 발행 상한(30초)에 한참 못 미치므로 주기 발행은 안 난다.
            harness.advance(Duration.ofSeconds(1))

            assertEquals(
                listOf("navigate_to"), inflightSkills(),
                "전이 이벤트가 적재되지 않았다 — 주기보다 짧은 태스크가 드레인에서 사라진다",
            )
        }
    }

    @Test
    fun `거절된 협상은 원장이 아니라 거절 표로 간다`() {
        harness().use { harness ->
            val response = harness.client(CLIENT).negotiate(ROBOT, impossible())

            assertTrue(!response.accepted)
            assertEquals(
                0, ledger.activeConsumerCount("pick_place"),
                "거절된 요구가 원장에 오르면 축소가 영영 안 열린다",
            )
            // **응답의 거절 수와 같아야 한다.** 하나만 적재하는 구현이면
            // 나머지 사유가 사라지고, 운영자는 무엇을 고쳐야 할지 모른다.
            assertEquals(
                response.rejectionsList.size,
                PostgresSupport.queryOne("SELECT count(*) FROM handshake_rejection") { it.getInt(1) },
                "거절이 빠짐없이 적재되지 않았다",
            )
        }
    }

    @Test
    fun `적재가 안 붙으면 검사 6번이 판정을 못 한다`() {
        // **이 시험이 없으면 앞의 것이 무엇을 증명했는지 모른다.** 적재를
        // 떼면 §15.40이 적어 둔 그 상태로 돌아가야 한다 — "원장이 관측하지
        // 못했다". 그러지 않는다면 판정이 적재가 아니라 다른 데서 온 것이다.
        assertTrue(
            findings().any { "원장이 관측하지 못했다" in it },
            "적재 없이도 판정이 났다: ${findings()}",
        )
    }

    // ── 씨앗

    /** 두 관측선을 실제 경로로 잇는다. */
    private fun harness() = Harness(
        mapOf(ROBOT to Path.of("..", "profile", "fixtures", "minimal.json").normalize()),
        taskSink = object : TaskObservations {
            override fun onState(message: dev.picasso.contracts.v1.StateMessage) {
                tasks.record(message)
            }

            override fun onEvent(event: dev.picasso.contracts.v1.Event) {
                tasks.record(event)
            }
        },
        reporter = { report ->
            val outcome = handshakes.record(report.request, report.response, report.site)
            // 적재가 거부되면 시험이 조용히 초록이 되지 않도록 여기서 깬다.
            // 운영에서는 §5.4대로 삼키지만, 시험은 삼키면 안 된다.
            check(outcome is HandshakeIngestOutcome.Recorded) { "적재 거부: $outcome" }
        },
    )

    /** 지금 비종착인 태스크의 스킬 이름들. */
    private fun inflightSkills(): List<String> = PostgresSupport.queryAll(
        "SELECT s.name FROM task t JOIN skill_type s ON s.skill_type_id = t.skill_type_id " +
            "WHERE NOT t.terminal ORDER BY s.name",
    ) { it.getString(1) }

    private fun parametersFor(skill: String): List<ParameterValue> = when (skill) {
        "pick_place" -> listOf(
            ParameterValue.newBuilder().setKey("object_id").setStringValue("box-1").build(),
            ParameterValue.newBuilder().setKey("destination").setStringValue("bin-2").build(),
        )
        else -> listOf(
            ParameterValue.newBuilder().setKey("location").setStringValue("dock-1").build(),
        )
    }

    private fun requirements(): RequirementSet = RequirementSet.parse(
        // **요구가 둘인 픽스처다.** 하나가 옮겨 가도 다른 하나가 관측선이
        // 살아 있음을 증명해야 하고, 요구가 하나뿐이면 그 소비자가 떠나는
        // 순간 원장이 스스로를 못 보게 된다.
        "minimal",
        Files.readString(Path.of("..", "profile", "requirements", "minimal.json").normalize()),
    ).copy(clientId = CLIENT)

    private fun impossible() = requirements().let { set ->
        set.copy(
            requirements = set.requirements.map {
                dev.picasso.profile.Requirement(it.skillType, major = 9, minor = 9)
            },
        )
    }

    /**
     * `navigate_to`를 쓰던 소비자만 옮겨 간다.
     *
     * **전부 밀면 안 된다.** 워터마크는 `MAX(last_seen)`이라 모든 요구를
     * 과거로 보내면 원장이 스스로를 못 보게 되고, 답은 0이 아니라 "모른다"가
     * 된다 — 그리고 그것이 맞다. 모든 소비자가 40일간 조용한 원장은 정말로
     * 모르는 것이다(실측으로 여기 걸렸다).
     *
     * 남은 `pick_place` 요구가 관측선이 살아 있다는 증거다.
     */
    private fun decayConsumers() {
        PostgresSupport.execute(
            "UPDATE consumer_requirement SET last_seen = now() - interval '40 days' " +
                "WHERE skill_type_name = 'navigate_to'",
        )
        ledger.decay()
    }

    /** 검사 6번 하나만 돌려 **문장 자체**를 본다. 판정만 보면 사유가 안 보인다. */
    private fun findings(): List<String> {
        val head = ProfileDocument.parse("head", Fixtures.shrunk(revision = 2)).getOrThrow()
        val input = GateInput(
            profiles = listOf(head),
            schemaJson = Fixtures.schema(),
            descriptor = Fixtures.descriptor(),
            baseline = mapOf(ProfileKey(head.vendor, head.model) to Fixtures.good()),
            registry = RegistryLedgerQuery(db),
        )
        return GateRunner(listOf(Check06Vocabulary())).run(input).results.flatMap {
            when (it) {
                is CheckResult.Passed -> it.findings
                is CheckResult.Failed -> it.findings
                else -> emptyList()
            }
        }.map { it.message }
    }

    private companion object {
        const val ROBOT = "r1"
        const val CLIENT = "line-controller"
        val CONTRACT_SEMVER: String = dev.picasso.contracts.wire.ContractIdentity.semver
    }
}
