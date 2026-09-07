package dev.picasso.harness

import dev.picasso.mimic.report.FallbackReplay
import dev.picasso.mimic.report.FallbackTaskObservations
import dev.picasso.mimic.report.FileTaskObservations
import dev.picasso.mimic.report.ReplayOutcome
import dev.picasso.mimic.report.TaskObservations
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.picasso.contracts.v1.CapabilityRequirement
import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.NegotiateRequest
import dev.picasso.contracts.v1.NegotiateResponse
import dev.picasso.contracts.v1.ProfileRef
import dev.picasso.contracts.v1.StateMessage
import dev.picasso.contracts.v1.TaskSnapshot
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.TaskTransition
import dev.picasso.mimic.report.FileHandshakeReporter
import dev.picasso.mimic.report.HandshakeReport
import dev.picasso.registry.Fixtures
import dev.picasso.registry.PostgresSupport
import dev.picasso.registry.binding.ActivateOutcome
import dev.picasso.registry.binding.BindingService
import dev.picasso.registry.ingest.HandshakeIngestOutcome
import dev.picasso.registry.ingest.HandshakeIngestService
import dev.picasso.registry.ingest.TaskIngestService
import dev.picasso.registry.ledger.LedgerService
import dev.picasso.registry.observe.ObservationService
import dev.picasso.registry.revision.RevisionService
import dev.picasso.registry.revision.SkillTypeSync
import dev.picasso.registry.revision.SubmitOutcome
import dev.picasso.registry.store.Db
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §15.46 — 폴백 파일이 **다시 원장이 된다.**
 *
 * ## 멱등이 이 클래스의 전제다
 *
 * 재적재는 "어디까지 밀었는가"를 기록하지 않고 **전부 다시 민다.** 커서를
 * 두면 그것이 세 번째 진실이 되고 어긋난 날 밀지 않은 구간이 조용히 생기기
 * 때문이다. 그 대신 적재가 멱등이라는 성질에 기대므로, **그 성질이 깨지면
 * 재적재가 원장을 틀리게 만든다** — 여기서 그것을 붙든다.
 */
class FallbackReplayTest {

    private lateinit var db: Db
    private lateinit var ledger: LedgerService
    private lateinit var handshakes: HandshakeIngestService
    private lateinit var tasks: TaskIngestService
    private lateinit var replay: FallbackReplay

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        ledger = LedgerService(db)
        handshakes = HandshakeIngestService(ledger, ObservationService(db))
        tasks = TaskIngestService(db)
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

        replay = FallbackReplay(
            handshakes = { site, request, response ->
                val outcome = handshakes.record(request, response, site)
                check(outcome is HandshakeIngestOutcome.Recorded) { "적재 거부: $outcome" }
            },
            tasks = object : TaskObservations {
                override fun onState(message: StateMessage) {
                    val outcome = tasks.record(message)
                    check(outcome.skipped.isEmpty()) { "적재 건너뜀: ${outcome.skipped}" }
                }

                override fun onEvent(event: Event) {
                    val outcome = tasks.record(event)
                    check(outcome.skipped.isEmpty()) { "적재 건너뜀: ${outcome.skipped}" }
                }
            },
        )
    }

    // ── 핸드셰이크

    @Test
    fun `폴백에 쌓인 협상이 원장이 된다`() {
        val path = tempFile()
        FileHandshakeReporter(path).report(
            HandshakeReport("line-a", request(), accepted()),
        )

        val outcome = replay.replayHandshakes(path)

        assertEquals(1, outcome.replayed, "${outcome.failed}")
        assertEquals(1, ledger.activeConsumerCount("pick_place"))
    }

    @Test
    fun `두 번 밀어도 원장이 같다`() {
        // **재적재의 전제다.** 커서를 안 두는 대신 멱등에 기대므로, 이것이
        // 깨지면 재적재가 원장을 틀리게 만든다.
        val path = tempFile()
        val reporter = FileHandshakeReporter(path)
        reporter.report(HandshakeReport("line-a", request(), accepted()))

        replay.replayHandshakes(path)
        replay.replayHandshakes(path)

        assertEquals(1, ledger.activeConsumerCount("pick_place"), "같은 소비자가 둘로 셌다")
        assertEquals(
            1,
            PostgresSupport.queryOne("SELECT count(*) FROM consumer_requirement") { it.getInt(1) },
        )
    }

    @Test
    fun `적재가 거부하면 그 줄이 실패로 남는다`() {
        // 실패한 줄은 **원문 그대로** 남아야 새 폴백 파일이 될 수 있다.
        val path = tempFile()
        val contradictory = accepted().toBuilder().addRejections(
            dev.picasso.contracts.v1.Rejection.newBuilder()
                .setCode(dev.picasso.contracts.v1.RejectionCode.REJECTION_CODE_SKILL_ABSENT)
                .setDetail("x"),
        ).build()
        FileHandshakeReporter(path).report(HandshakeReport("line-a", request(), contradictory))

        val outcome = replay.replayHandshakes(path)

        assertEquals(0, outcome.replayed)
        assertEquals(1, outcome.failed.size)
        assertTrue(outcome.failed.single().startsWith("{"), "원문이 아니다: ${outcome.failed}")
    }

    // ── 태스크

    @Test
    fun `폴백에 쌓인 전이가 드레인이 된다`() {
        val path = tempFile()
        val file = FileTaskObservations(path)
        FallbackTaskObservations(exploding(), file).onEvent(transition(TaskState.TASK_STATE_RUNNING))

        val outcome = replay.replayTasks(path)

        assertEquals(1, outcome.replayed, "${outcome.failed}")
        assertEquals(
            1,
            PostgresSupport.queryOne("SELECT count(*) FROM task WHERE NOT terminal") { it.getInt(1) },
        )
    }

    @Test
    fun `kind에 따라 경로가 갈린다`() {
        // 안 가르면 전이가 스냅샷으로 들어가 `tasks` 배열이 비어 아무것도
        // 안 실린다 — 그런데 `replayed`는 올라가 성공처럼 보인다.
        val path = tempFile()
        val file = FileTaskObservations(path)
        val chain = FallbackTaskObservations(exploding(), file)
        chain.onState(snapshot())
        chain.onEvent(transition(TaskState.TASK_STATE_RUNNING))

        assertEquals(2, replay.replayTasks(path).replayed)
        assertEquals(
            1,
            PostgresSupport.queryOne("SELECT count(*) FROM task") { it.getInt(1) },
            "같은 태스크가 두 줄이 됐다",
        )
    }

    @Test
    fun `재적재도 종착을 되돌리지 않는다`() {
        // 폴백 파일의 순서가 뒤집혀 있어도(§10.4의 REORDER) 종착이 살아난다.
        val path = tempFile()
        val chain = FallbackTaskObservations(exploding(), FileTaskObservations(path))
        chain.onEvent(transition(TaskState.TASK_STATE_SUCCEEDED))
        chain.onEvent(transition(TaskState.TASK_STATE_RUNNING))

        replay.replayTasks(path)

        assertEquals(
            "TASK_STATE_SUCCEEDED",
            PostgresSupport.queryOne("SELECT state FROM task WHERE task_id = 't1'") { it.getString(1) },
        )
    }

    // ── 깨진 줄

    @Test
    fun `깨진 줄은 세고 나머지는 민다`() {
        // **파일 전체를 버리면** 잘린 마지막 줄 하나 때문에 그 앞의 멀쩡한
        // 관측이 전부 사라진다. JSONL로 적은 이유가 그것이다.
        val path = tempFile()
        FileHandshakeReporter(path).report(HandshakeReport("line-a", request(), accepted()))
        Files.write(
            path,
            "{\"site\":\"line-a\",\"reque".toByteArray(),
            java.nio.file.StandardOpenOption.APPEND,
        )

        val outcome = replay.replayHandshakes(path)

        assertEquals(1, outcome.replayed)
        assertEquals(1, outcome.malformed, "깨진 줄을 조용히 버렸다")
    }

    @Test
    fun `site가 없는 줄은 밀지 않는다`() {
        // 손상된 파일이나 옛 형식이 섞이면 `site`가 빠질 수 있다. 기본값으로
        // 밀면 **엉뚱한 사이트의 소비자로 적재되고**, `consumer.site`는 §8.3이
        // "토픽의 site"라 정한 값이라 그때부터 사이트별 조회가 조용히 틀린다.
        val path = tempFile()
        Files.write(path, (withoutField("site") + "\n").toByteArray())

        val outcome = replay.replayHandshakes(path)

        assertEquals(0, outcome.replayed)
        assertEquals(1, outcome.failed.size)
        assertEquals(0, outcome.malformed, "JSON으로는 유효하므로 깨진 줄이 아니다")
        assertEquals(0, ledger.activeConsumerCount("pick_place"))
    }

    @Test
    fun `kind가 없는 줄은 밀지 않는다`() {
        // 어느 경로로 보낼지 모르는 줄을 아무 쪽으로나 보내면, 전이가
        // 스냅샷으로 들어가 `tasks` 배열이 비어 아무것도 안 실린다.
        val path = tempFile()
        val chain = FallbackTaskObservations(exploding(), FileTaskObservations(path))
        chain.onEvent(transition(TaskState.TASK_STATE_RUNNING))
        val stripped = tempFile()
        val node = ObjectMapper().readTree(Files.readAllLines(path).single()) as ObjectNode
        node.remove("kind")
        Files.write(stripped, (node.toString() + "\n").toByteArray())

        val outcome = replay.replayTasks(stripped)

        assertEquals(0, outcome.replayed)
        assertEquals(1, outcome.failed.size)
        assertEquals(0, PostgresSupport.queryOne("SELECT count(*) FROM task") { it.getInt(1) })
    }

    @Test
    fun `파일이 없으면 아무 일도 없다`() {
        val outcome = replay.replayTasks(Path.of("없는-파일.jsonl"))

        assertEquals(ReplayOutcome(0, emptyList(), 0), outcome)
    }

    // ── 씨앗

    /** 폴백 줄에서 필드 하나를 뺀다. 손상된 파일을 흉내 낸다. */
    private fun withoutField(field: String): String {
        val path = tempFile()
        FileHandshakeReporter(path).report(HandshakeReport("line-a", request(), accepted()))
        val node = ObjectMapper().readTree(Files.readAllLines(path).single()) as ObjectNode
        node.remove(field)
        return node.toString()
    }

    private fun tempFile(): Path =
        Files.createTempDirectory("picasso-replay").resolve("fallback.jsonl")

    private fun exploding() = object : TaskObservations {
        override fun onState(message: StateMessage) = throw IllegalStateException("적재가 죽었다")
        override fun onEvent(event: Event) = throw IllegalStateException("적재가 죽었다")
    }

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

    private fun transition(to: TaskState) = Event.newBuilder()
        .setHeader(header())
        .setTaskTransition(
            TaskTransition.newBuilder()
                .setTaskId("t1").setSkillType("pick_place")
                .setFrom(TaskState.TASK_STATE_ACCEPTED).setTo(to)
                .setRevision(1),
        ).build()

    private fun snapshot() = StateMessage.newBuilder()
        .setHeader(header())
        .addTasks(
            TaskSnapshot.newBuilder()
                .setTaskId("t1").setSkillType("pick_place")
                .setState(TaskState.TASK_STATE_RUNNING).setRevision(1),
        ).build()

    private companion object {
        val CONTRACT_SEMVER: String = dev.picasso.contracts.wire.ContractIdentity.semver
    }
}
