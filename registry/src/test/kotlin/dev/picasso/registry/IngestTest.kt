package dev.picasso.registry

import dev.picasso.contracts.v1.CapabilityRequirement
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.NegotiateRequest
import dev.picasso.contracts.v1.NegotiateResponse
import dev.picasso.contracts.v1.ProfileRef
import dev.picasso.contracts.v1.Rejection
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.StateMessage
import dev.picasso.contracts.v1.TaskSnapshot
import dev.picasso.contracts.v1.TaskState
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
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §15.40이 적어 둔 것을 닫는 자리 — **원장에 운영 생산자가 생긴다.**
 *
 * 여태 `consumer_requirement`와 `task`를 채우는 것은 시험과 `harness`뿐이었고,
 * 그래서 운영에서는 §9.3의 두 조회가 언제나 `NotObservable`이었다.
 *
 * 여기서 보는 것은 **적재가 되는가**가 아니라 **틀린 보고를 안 받는가**다.
 * 받아 적기만 하는 적재기는 앞의 것만으로 통과하고, 그 뒤에 모순된 보고가
 * 원장을 오염시킨다 — 원장이 §9.3의 축소 판정을 떠받치므로 그 오염은
 * **아직 쓰는 능력의 제거 승인**으로 끝난다.
 */
class IngestTest {

    private lateinit var db: Db
    private lateinit var ledger: LedgerService
    private lateinit var handshakes: HandshakeIngestService
    private lateinit var tasks: TaskIngestService
    private var revisionId: Long = 0

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
        revisionId = stored.profileRevisionId
        val bindings = BindingService(db)
        BindingService.SUITE_NAMES.forEach { bindings.recordTestRun(revisionId, it, "PASS", "harness") }
        assertTrue(bindings.activate(revisionId, "op") is ActivateOutcome.Activated)
    }

    // ── 핸드셰이크 적재

    @Test
    fun `성공한 협상이 원장에 OBSERVED로 실린다`() {
        val outcome = handshakes.record(request(), accepted(), "line-a")

        assertTrue(outcome is HandshakeIngestOutcome.Recorded, "$outcome")
        assertTrue(outcome.accepted)
        assertEquals(1, ledger.activeConsumerCount("pick_place"))
    }

    @Test
    fun `실패한 협상은 거절 표에 실리고 원장은 안 건드린다`() {
        val outcome = handshakes.record(request(), rejected(), "line-a")

        assertTrue(outcome is HandshakeIngestOutcome.Recorded, "$outcome")
        assertTrue(!outcome.accepted)
        assertEquals(
            0, ledger.activeConsumerCount("pick_place"),
            "거절된 요구가 원장에 들어가면 축소가 영영 안 열린다",
        )
        assertEquals(1, PostgresSupport.queryOne("SELECT count(*) FROM handshake_rejection") { it.getInt(1) })
    }

    @Test
    fun `성공인데 거절이 실려 있으면 거부한다`() {
        // 모순된 보고를 받아 적으면 "쓰는 사람 0명임을 관측했다"의 근거가
        // 거짓이 되고, 그 거짓 위에서 축소가 열린다.
        val contradictory = accepted().toBuilder().addRejections(rejection()).build()

        val outcome = handshakes.record(request(), contradictory, "line-a")

        assertTrue(outcome is HandshakeIngestOutcome.Refused, "$outcome")
        assertEquals(0, ledger.activeConsumerCount("pick_place"))
    }

    @Test
    fun `실패인데 사유가 없으면 거부한다`() {
        val empty = NegotiateResponse.newBuilder().setAccepted(false).build()

        assertTrue(handshakes.record(request(), empty, "line-a") is HandshakeIngestOutcome.Refused)
    }

    @Test
    fun `성공인데 요구가 없으면 거부한다`() {
        // 소비자 행만 생기고 요구는 없는 유령이 쌓인다.
        val noRequirements = NegotiateRequest.newBuilder()
            .setHeader(header())
            .setRequirement(CapabilityRequirement.newBuilder().setClientId(CLIENT).setRobotId("r1"))
            .build()

        val outcome = handshakes.record(noRequirements, accepted(), "line-a")

        assertTrue(outcome is HandshakeIngestOutcome.Refused, "$outcome")
    }

    @Test
    fun `헤더가 권위다 — 페이로드의 client_id를 안 쓴다`() {
        // §5.4. 페이로드를 쓰면 헤더와 다른 소비자로 적재되고, 원장의
        // consumer_id가 §8.3이 정한 "헤더의 client_id"와 갈라진다.
        val mismatched = request().toBuilder()
            .setRequirement(
                request().requirement.toBuilder().setClientId("someone-else"),
            ).build()

        handshakes.record(mismatched, accepted(), "line-a")

        val rows = ledger.dependents("pick_place").active
        assertEquals(listOf(CLIENT), rows.map { it.consumerId })
    }

    @Test
    fun `client_id나 site가 비면 거부한다`() {
        val noClient = request().toBuilder()
            .setHeader(header().toBuilder().setClientId("")).build()

        assertTrue(handshakes.record(noClient, accepted(), "line-a") is HandshakeIngestOutcome.Refused)
        assertTrue(handshakes.record(request(), accepted(), "") is HandshakeIngestOutcome.Refused)
    }

    // ── 태스크 적재

    @Test
    fun `비종착 태스크가 적재되어 드레인이 0이 아니다`() {
        val outcome = tasks.record(state(snapshot("t1", TaskState.TASK_STATE_RUNNING)))

        assertEquals(1, outcome.recorded, "${outcome.skipped}")
        assertEquals(
            1,
            PostgresSupport.queryOne("SELECT count(*) FROM task WHERE NOT terminal") { it.getInt(1) },
        )
    }

    @Test
    fun `종착 태스크는 드레인에서 빠진다`() {
        tasks.record(state(snapshot("t1", TaskState.TASK_STATE_SUCCEEDED)))

        assertEquals(
            0,
            PostgresSupport.queryOne("SELECT count(*) FROM task WHERE NOT terminal") { it.getInt(1) },
        )
    }

    @Test
    fun `종착은 되돌리지 않는다`() {
        // 늦게 도착한 옛 스냅샷(§10.4의 REORDER)이 종착을 비종착으로
        // 되돌리면 **드레인이 영영 안 끝나고 축소가 영원히 막힌다.**
        tasks.record(state(snapshot("t1", TaskState.TASK_STATE_SUCCEEDED)))
        tasks.record(state(snapshot("t1", TaskState.TASK_STATE_RUNNING)))

        val row = PostgresSupport.queryOne(
            "SELECT state, terminal FROM task WHERE task_id = 't1'",
        ) { it.getString(1) to it.getBoolean(2) }

        assertEquals("TASK_STATE_SUCCEEDED" to true, row)
    }

    @Test
    fun `종착한 뒤에도 관측 시각은 오른다`() {
        // 워터마크가 늙으면 원장이 스스로를 못 보게 된다(§9.3의 관측선).
        tasks.record(state(snapshot("t1", TaskState.TASK_STATE_SUCCEEDED)))
        PostgresSupport.execute("UPDATE task SET updated_at = now() - interval '2 hours'")

        tasks.record(state(snapshot("t1", TaskState.TASK_STATE_SUCCEEDED)))

        val fresh = PostgresSupport.queryOne(
            "SELECT updated_at > now() - interval '1 minute' FROM task WHERE task_id = 't1'",
        ) { it.getBoolean(1) }
        assertTrue(fresh, "종착 뒤 갱신이 멈추면 조용한 라인에서 관측선이 죽는다")
    }

    @Test
    fun `비종착 사이의 전이는 갱신된다`() {
        tasks.record(state(snapshot("t1", TaskState.TASK_STATE_ACCEPTED)))
        tasks.record(state(snapshot("t1", TaskState.TASK_STATE_RUNNING)))

        assertEquals(
            "TASK_STATE_RUNNING",
            PostgresSupport.queryOne("SELECT state FROM task WHERE task_id = 't1'") { it.getString(1) },
        )
    }

    @Test
    fun `모르는 개정판은 통째로 건너뛴다`() {
        val unknown = state(snapshot("t1", TaskState.TASK_STATE_RUNNING), revision = 99)

        val outcome = tasks.record(unknown)

        assertEquals(0, outcome.recorded)
        assertTrue(outcome.skipped.single().contains("모르는 개정판"), "${outcome.skipped}")
    }

    @Test
    fun `모르는 스킬은 그 행만 건너뛴다`() {
        // 메시지 전체를 버리면 스킬 하나가 낯설다는 이유로 같은 로봇의
        // 다른 태스크가 드레인에서 통째로 사라진다.
        val message = state(
            snapshot("t1", TaskState.TASK_STATE_RUNNING),
            snapshot("t2", TaskState.TASK_STATE_RUNNING, skill = "inspect"),
        )

        val outcome = tasks.record(message)

        assertEquals(1, outcome.recorded)
        assertEquals(1, outcome.skipped.size, "${outcome.skipped}")
    }

    @Test
    fun `같은 이름의 major가 둘이면 개정판이 선언한 쪽을 고른다`() {
        // `TaskSnapshot`에는 major가 없다. 이름만으로 고르면 major가 둘일 때
        // 아무거나 집게 되고, 그러면 §9.3의 드레인이 **다른 major의 축소를
        // 막거나 못 막는다.** 개정판을 거쳐 가면 pinning된 좌표와 같은 답이
        // 나온다 — 그것이 이 조회가 `profile_skill`을 지나는 이유다.
        //
        // 계약에는 스킬마다 major가 하나뿐이라 이 상황은 여기서 만든다.
        PostgresSupport.execute(
            "INSERT INTO skill_type (name, major, introduced_in_semver) " +
                "VALUES ('pick_place', 2, '9.9.9')",
        )

        tasks.record(state(snapshot("t1", TaskState.TASK_STATE_RUNNING)))

        val major = PostgresSupport.queryOne(
            "SELECT s.major FROM task t JOIN skill_type s ON s.skill_type_id = t.skill_type_id " +
                "WHERE t.task_id = 't1'",
        ) { it.getInt(1) }
        assertEquals(1, major, "개정판이 선언하지 않은 major를 집었다")
    }

    @Test
    fun `robot_id가 없으면 아무것도 안 적재한다`() {
        val headless = state(snapshot("t1", TaskState.TASK_STATE_RUNNING)).toBuilder()
            .setHeader(header().toBuilder().setRobotId("")).build()

        assertEquals(0, tasks.record(headless).recorded)
    }

    // ── 씨앗

    private fun header() = MessageHeader.newBuilder()
        .setClientId(CLIENT)
        .setRobotId("r1")
        .setProfileRef(ProfileRef.newBuilder().setProfileId("fixture/minimal").setRevision(1))
        .build()

    private fun request() = NegotiateRequest.newBuilder()
        .setHeader(header())
        .setRequirement(
            CapabilityRequirement.newBuilder()
                .setClientId(CLIENT)
                .setRobotId("r1")
                .addRequirements("pick_place@^1.2"),
        )
        .build()

    private fun accepted() = NegotiateResponse.newBuilder().setAccepted(true).build()

    private fun rejected() = NegotiateResponse.newBuilder()
        .setAccepted(false).addRejections(rejection()).build()

    private fun rejection() = Rejection.newBuilder()
        .setCode(RejectionCode.REJECTION_CODE_SKILL_ABSENT)
        .setDetail("그런 스킬이 없다")
        .build()

    private fun snapshot(
        taskId: String,
        state: TaskState,
        skill: String = "pick_place",
    ): TaskSnapshot = TaskSnapshot.newBuilder()
        .setTaskId(taskId).setSkillType(skill).setState(state).setRevision(1).setAttempt(0)
        .build()

    private fun state(vararg snapshots: TaskSnapshot, revision: Int = 1): StateMessage =
        StateMessage.newBuilder()
            .setHeader(
                header().toBuilder().setProfileRef(
                    ProfileRef.newBuilder().setProfileId("fixture/minimal").setRevision(revision),
                ),
            )
            .addAllTasks(snapshots.toList())
            .build()

    private companion object {
        const val CLIENT = "line-controller"
        val CONTRACT_SEMVER: String = dev.picasso.contracts.wire.ContractIdentity.semver
    }
}
