package dev.picasso.harness

import dev.picasso.adapter.core.Acceptance
import dev.picasso.adapter.core.Applied
import dev.picasso.adapter.core.FaultObservation
import dev.picasso.adapter.core.HoldObservation
import dev.picasso.adapter.core.Refusal
import dev.picasso.adapter.core.RobotAdapter
import dev.picasso.adapter.core.SiteNames
import dev.picasso.adapter.host.AdapterHost
import dev.picasso.adapter.host.HostUplink
import dev.picasso.adapter.host.HostedRobot
import dev.picasso.client.PicassoClient
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.StateMessage
import dev.picasso.contracts.v1.TaskState
import dev.picasso.profile.ProfileDocument
import dev.picasso.profile.Requirement
import dev.picasso.profile.RequirementSet
import dev.picasso.registry.Fixtures
import dev.picasso.registry.PostgresSupport
import dev.picasso.registry.adapter.AdapterService
import dev.picasso.registry.adapter.RegisterOutcome
import dev.picasso.registry.binding.ActivateOutcome
import dev.picasso.registry.binding.BindOutcome
import dev.picasso.registry.binding.BindingService
import dev.picasso.registry.ingest.HandshakeIngestOutcome
import dev.picasso.registry.ingest.HandshakeIngestService
import dev.picasso.registry.ingest.LivenessOutcome
import dev.picasso.registry.ingest.LivenessService
import dev.picasso.registry.ingest.SiteNameReport
import dev.picasso.registry.ingest.TaskIngestService
import dev.picasso.registry.ledger.LedgerService
import dev.picasso.registry.ledger.Observability
import dev.picasso.registry.ledger.RobotObservability
import dev.picasso.registry.observe.ObservationService
import dev.picasso.registry.revision.RevisionService
import dev.picasso.registry.revision.SkillTypeSync
import dev.picasso.registry.revision.SubmitOutcome
import dev.picasso.registry.store.Db
import dev.picasso.uplink.RecordingPublisher
import dev.picasso.uplink.report.IngestBridge
import dev.picasso.uplink.report.HandshakeReporter
import dev.picasso.uplink.report.LivenessObservations
import dev.picasso.uplink.report.SiteNameSummary
import dev.picasso.uplink.report.TaskObservations
import io.grpc.ManagedChannel
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * **레지스트리가 어댑터 호스트의 기체를 본다.** `LedgerIngestEndToEndTest` 가 미믹으로 증명한 두 관측선(§9.3) 중 기체 쪽
 * 둘 — 생존과 태스크 — 을 실물 어댑터 경로에서 다시 본다. 미믹과 호스트가 같은 `uplink` 결선(`IngestBridge`)을 타므로,
 * 여기서 갈리는 것은 **발행의 출처**뿐이다: 상태기계가 아니라 어댑터가 말한 것이 발행되고, 그것이 적재된다.
 *
 * 순서: 원장이 이 기체를 못 본다 → 호스트가 뜬다(`ONLINE`) → 원장이 본다 → 태스크가 돈다 → `task` 표에 비종착으로 앉는다
 * → 종착한다 → 드레인 → 호스트가 닫힌다(`OFFLINE`) → 원장에 그 상태가 남는다.
 *
 * 두 번째 시험이 **세 번째 관측선**(소비자 요구)을 본다 — 협상이 호스트에 생기면서(§15.100) 미믹만 채우던 그 줄을
 * 실물 어댑터 경로도 채운다. 이것이 없으면 호스트 위의 기체는 *"쓰는 사람 0명"* 으로 보이고 그 위에서 축소가 열린다.
 *
 * **HTTP 는 여기서 안 지난다** — 미믹 쪽 시험과 같은 이유로 적재 서비스를 직접 싱크로 붙인다. 와이어는 `IngestEndpointTest` 가
 * 본다. 기체 행은 여전히 SQL 로 넣는다: 로봇 등록 엔드포인트가 아직 없다(ADR 37 미결). 이 시험이 증명하는 것은 *등록된*
 * 기체의 관측이 호스트에서 흘러온다는 것까지다.
 */
class HostIngestEndToEndTest {

    private lateinit var db: Db
    private lateinit var tasks: TaskIngestService
    private lateinit var liveness: LivenessService
    private lateinit var ledger: LedgerService
    private lateinit var handshakes: HandshakeIngestService

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        tasks = TaskIngestService(db)
        liveness = LivenessService(db)
        ledger = LedgerService(db)
        handshakes = HandshakeIngestService(ledger, ObservationService(db))
        SkillTypeSync(db).sync(Fixtures.descriptor(), dev.picasso.contracts.wire.ContractIdentity.semver, "sync")
        PostgresSupport.execute("INSERT INTO robot (robot_id, site_id, serial_number) VALUES ('$ROBOT','line-a','sn')")
        val stored = RevisionService(db, Fixtures.validator()).submit(Fixtures.good(), "op")
        assertTrue(stored is SubmitOutcome.Stored, "$stored")
        val bindings = BindingService(db)
        BindingService.SUITE_NAMES.forEach { bindings.recordTestRun(stored.profileRevisionId, it, "PASS", "harness") }
        assertTrue(bindings.activate(stored.profileRevisionId, "op") is ActivateOutcome.Activated)
        // 바인딩이 있어야 이 기체가 그 능력의 **제공자**다 — 없으면 원장의 답은 Blind 가 아니라 NoProvider 다.
        val adapters = AdapterService(db)
        val version = adapters.registerVersion(adapters.registerAdapter("acme", "host-drv", "op"), "1.0.0", dev.picasso.contracts.wire.ContractIdentity.semver, "op")
        assertTrue(version is RegisterOutcome.Registered, "$version")
        val bound = bindings.bind(ROBOT, version.adapterVersionId, stored.profileRevisionId, "op")
        assertTrue(bound is BindOutcome.Bound, "$bound")
    }

    /** 기종이 없는 어댑터 — 이 시험이 보는 것은 호스트에서 레지스트리까지의 결선이다. */
    private class PlainAdapter : RobotAdapter {
        var next: TaskState = TaskState.TASK_STATE_RUNNING
        private var current: TaskState = TaskState.TASK_STATE_UNSPECIFIED
        override val state: TaskState get() = current
        override fun accept(taskId: String, skillType: String, parameters: Map<String, Any>, startedAt: Instant): Acceptance {
            current = TaskState.TASK_STATE_RUNNING
            return Acceptance.Accepted(taskId)
        }
        override fun poll(now: Instant): TaskState { current = next; return current }
        override fun pause(): Applied = Applied.Refused(Refusal.NO_VENDOR_PRIMITIVE, "없다")
        override fun cancel(): Applied = Applied.Refused(Refusal.NO_VENDOR_PRIMITIVE, "없다")
        override fun hold(): HoldObservation = HoldObservation.Empty
        override fun faults(): FaultObservation = FaultObservation.Observed(emptyList())
        override fun knownSiteNames(): SiteNames = SiteNames.Known(listOf("dock-1", "bay-2"))
        override fun robotSoftware(): String? = "host-fw 1.2.3"
    }

    private inner class World : AutoCloseable {
        var now: Instant = Instant.parse("2026-09-10T00:00:00Z")
        val adapter = PlainAdapter()
        val published = RecordingPublisher()

        /** 미믹 CLI 의 `RegistryLink.wrap` 과 같은 자리 — HTTP 대신 적재 서비스를 직접 싱크로. */
        private val outbound = IngestBridge(
            published,
            sink = object : TaskObservations {
                override fun onState(message: StateMessage) { tasks.record(message) }
                override fun onEvent(event: Event) { tasks.record(event) }
            },
            liveness = object : LivenessObservations {
                override fun onConnection(header: MessageHeader, state: ConnectionState, software: String?, siteNames: SiteNameSummary?) {
                    val outcome = liveness.record(header, state, software, siteNames?.let { SiteNameReport(it.unsupported, it.count) })
                    // 운영에서는 삼키지만 시험은 삼키면 안 된다 — 거부가 조용히 초록이 되지 않게.
                    check(outcome is LivenessOutcome.Recorded) { "생존 적재 거부: $outcome" }
                }
            },
            software = { adapter.robotSoftware() },
            siteNames = { HostUplink.siteNames(adapter) },
        )

        val robot = HostedRobot(ROBOT, ProfileDocument.parse("minimal", Files.readString(PROFILE)).getOrThrow(), adapter, publisher = outbound, site = "line-a") { now }
        private val name = InProcessServerBuilder.generateName()

        /** §5.4 의 핸드셰이크 보고 — 미믹의 `MimicServer` 가 받는 것과 같은 자리다. */
        private val reporter = HandshakeReporter { report ->
            val outcome = handshakes.record(report.request, report.response, report.site)
            check(outcome is HandshakeIngestOutcome.Recorded) { "핸드셰이크 적재 거부: $outcome" }
        }

        val host = AdapterHost(robot, InProcessServerBuilder.forName(name).directExecutor(), reporter).start()
        private val channel: ManagedChannel = InProcessChannelBuilder.forName(name).directExecutor().build()
        val client = PicassoClient(channel, "line-controller")

        override fun close() { channel.shutdownNow(); host.shutdown() }
    }

    @Test
    fun `호스트의 기체가 원장의 두 관측선에 앉고, 태스크가 드레인에 잡혔다가 풀린다`() {
        // ── 0. 바인딩은 됐는데 아직 아무도 안 떴다 — 원장은 이 기체를 못 본다.
        val blind = assertIs<Observability.Blind>(RobotObservability(db).of("navigate_to"))
        assertTrue("한 번도 보고한 적 없는" in blind.reason, blind.reason)

        World().use { w ->
            // ── 1. 기동 발행 `ONLINE` 이 생존 보고가 됐다 — 어댑터가 답한 것(소프트웨어·사이트 이름 개수)이 함께 실렸다.
            assertIs<Observability.Live>(RobotObservability(db).of("navigate_to"), "호스트가 떴는데 원장이 못 본다")
            assertEquals(
                listOf("CONNECTION_STATE_ONLINE", "host-fw 1.2.3", "2"),
                PostgresSupport.queryAll("SELECT connection_state, robot_software, site_names_count::text FROM robot_liveness WHERE robot_id = '$ROBOT'") {
                    listOf(it.getString(1), it.getString(2), it.getString(3))
                }.single(),
            )

            // ── 2. 태스크가 돈다 — 전이 이벤트가 `task` 표에 비종착으로 앉는다(§15.44 — 주기 발행을 안 기다린다).
            w.client.start(ROBOT, "t1", revision = 1, skillType = "navigate_to", parameters = listOf(ParameterValue.newBuilder().setKey("location").setStringValue("dock-1").build()))
            assertEquals(listOf("navigate_to"), inflightSkills(), "호스트의 전이가 task 표에 안 실렸다")
            assertTrue(w.published.publications.isNotEmpty(), "적재를 붙였더니 발행 기록이 비었다")

            // ── 3. 종착한다 — 다음 펌프가 SUCCEEDED 를 적고, 그것이 드레인을 푼다.
            w.adapter.next = TaskState.TASK_STATE_SUCCEEDED
            w.client.snapshot(ROBOT)
            assertEquals(emptyList(), inflightSkills(), "종착했는데 task 표에 비종착으로 남았다")
            assertEquals(
                1, PostgresSupport.queryOne("SELECT count(*) FROM task WHERE terminal") { it.getInt(1) },
                "종착 태스크가 한 건이어야 한다",
            )
        }

        // ── 4. 닫혔다 — `OFFLINE` 이 원장에 남고, 그 기체가 제공하는 능력의 관측선은 다시 끊긴 것으로 판정된다(§4.7).
        assertEquals(
            "CONNECTION_STATE_OFFLINE",
            PostgresSupport.queryOne("SELECT connection_state FROM robot_liveness WHERE robot_id = '$ROBOT'") { it.getString(1) },
        )
        val gone = assertIs<Observability.Blind>(RobotObservability(db).of("navigate_to"))
        assertTrue("연결이 끊긴 기체" in gone.reason, gone.reason)
    }

    @Test
    fun `호스트에서 협상한 소비자가 원장의 관측선에 앉고, 거절은 원장이 아니라 거절 표로 간다`() {
        World().use { w ->
            assertEquals(0, ledger.activeConsumerCount("navigate_to"), "협상 전인데 소비자가 있다")

            val accepted = w.client.negotiate(ROBOT, requirements())
            assertTrue(accepted.accepted, "픽스처가 만족하지 않으면 이 시험이 뜻을 잃는다: ${accepted.rejectionsList}")

            assertEquals(1, ledger.activeConsumerCount("navigate_to"), "호스트의 협상이 원장에 안 실렸다")
            // **요구가 빠짐없이 실려야 한다** — 하나만 적재하면 나머지 스킬은 "쓰는 사람 0명" 으로 보이고 축소가 열린다.
            assertEquals(
                listOf("navigate_to", "pick_place"),
                PostgresSupport.queryAll("SELECT skill_type_name FROM consumer_requirement ORDER BY skill_type_name") { it.getString(1) },
            )

            // 거절된 협상은 원장에 오르지 않는다 — 오르면 그 소비자 때문에 축소가 영영 안 열린다.
            val rejected = w.client.negotiate(ROBOT, impossible())
            assertFalse(rejected.accepted)
            assertEquals(1, ledger.activeConsumerCount("navigate_to"), "거절된 요구가 원장에 올랐다")
            assertEquals(
                rejected.rejectionsList.size,
                PostgresSupport.queryOne("SELECT count(*) FROM handshake_rejection") { it.getInt(1) },
                "거절이 빠짐없이 적재되지 않았다",
            )
        }
    }

    private fun requirements(): RequirementSet = RequirementSet
        .parse("minimal", Files.readString(Path.of("..", "profile", "requirements", "minimal.json").normalize()))
        .copy(clientId = CLIENT)

    /** 어느 로봇도 못 맞추는 요구. major 를 올린다 — 스킬은 있는데 버전이 없다. */
    private fun impossible(): RequirementSet = requirements().let { set ->
        set.copy(requirements = set.requirements.map { Requirement(it.skillType, major = 9, minor = 9) })
    }

    private fun inflightSkills(): List<String> = PostgresSupport.queryAll(
        "SELECT s.name FROM task t JOIN skill_type s ON s.skill_type_id = t.skill_type_id WHERE NOT t.terminal ORDER BY s.name",
    ) { it.getString(1) }

    private companion object {
        const val ROBOT = "host-r1"
        const val CLIENT = "line-controller"
        val PROFILE: Path = Path.of("..", "profile", "fixtures", "minimal.json").normalize()
    }
}
