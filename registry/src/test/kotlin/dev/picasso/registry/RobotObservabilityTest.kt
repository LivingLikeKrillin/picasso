package dev.picasso.registry

import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.gate.input.LedgerAnswer
import dev.picasso.registry.binding.ActivateOutcome
import dev.picasso.registry.binding.BindOutcome
import dev.picasso.registry.binding.BindingService
import dev.picasso.registry.adapter.AdapterService
import dev.picasso.registry.adapter.RegisterOutcome
import dev.picasso.registry.ingest.LivenessService
import dev.picasso.registry.ledger.LedgerService
import dev.picasso.registry.ledger.Observability
import dev.picasso.registry.ledger.RegistryLedgerQuery
import dev.picasso.registry.ledger.RobotObservability
import dev.picasso.registry.revision.RevisionService
import dev.picasso.registry.revision.SkillTypeSync
import dev.picasso.registry.revision.SubmitOutcome
import dev.picasso.registry.store.Db
import java.time.Duration
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **기체 하나가 조용해지면 축소가 막히는가.**
 *
 * 이 시험이 이 개선의 이유다. 여기 오기 전 `RegistryLedgerQuery`는 관측선을
 * 전역 `MAX(last_seen)` 하나로 판정했고, 그러면 **셋 중 둘만 보고해도 원장이
 * 신선하다고 답한다.** 조용해진 셋째가 아직 그 능력을 돌리고 있어도 답은
 * `Observed(0)`이었다.
 *
 * §15.41이 *"틀리는 방향을 정해 둔다"*고 했고 그 방향은 막는 쪽이었다.
 * 전역 워터마크는 그 규율이 새는 자리였다.
 *
 * ## `HIBERNATING`을 따로 보는 이유
 *
 * §4.7이 그것을 *"침묵하지만 정상"*으로 정의했다. 침묵으로 읽으면 계약이
 * 만든 정상 상태가 **모든 축소를 영구히 막는 차단자**가 된다. 그래서 살아
 * 있는 것으로 세되 상한을 둔다 — 보고하고 죽은 어댑터가 영원히 면제되면
 * 그 오답은 축소를 **여는** 쪽이다.
 */
class RobotObservabilityTest {

    private lateinit var db: Db
    private lateinit var ledger: LedgerService
    private lateinit var liveness: LivenessService
    private lateinit var bindings: BindingService
    private lateinit var observability: RobotObservability
    private var revisionId: Long = 0
    private var adapterVersionId: Long = 0
    private var clock: Instant = Instant.parse("2026-09-08T09:00:00Z")

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        ledger = LedgerService(db)
        liveness = LivenessService(db) { clock }
        bindings = BindingService(db)
        observability = RobotObservability(db, now = { clock })
        SkillTypeSync(db).sync(Fixtures.descriptor(), CONTRACT_SEMVER, "sync")

        val adapters = AdapterService(db)
        val adapterId = adapters.registerAdapter("acme", "drv", "op")
        val version = adapters.registerVersion(adapterId, "1.0.0", CONTRACT_SEMVER, "op")
        assertTrue(version is RegisterOutcome.Registered, "$version")
        adapterVersionId = version.adapterVersionId

        val stored = revisionService().submit(Fixtures.good(), "op")
        assertTrue(stored is SubmitOutcome.Stored, "$stored")
        revisionId = stored.profileRevisionId
        BindingService.SUITE_NAMES.forEach { bindings.recordTestRun(revisionId, it, "PASS", "harness") }
        assertTrue(bindings.activate(revisionId, "op") is ActivateOutcome.Activated)
    }

    private fun revisionService() = RevisionService(db, Fixtures.validator())

    /** 기체를 등록하고 활성 바인딩을 만든다. */
    private fun bound(id: String) {
        PostgresSupport.execute(
            "INSERT INTO robot (robot_id, site_id, serial_number) VALUES ('$id','line-a','sn-$id')",
        )
        val outcome = bindings.bind(id, adapterVersionId, revisionId, "op")
        assertTrue(outcome is BindOutcome.Bound, "$outcome")
    }

    private fun report(
        id: String,
        state: ConnectionState = ConnectionState.CONNECTION_STATE_ONLINE,
        at: Instant = clock,
    ) {
        LivenessService(db) { at }.record(
            MessageHeader.newBuilder().setRobotId(id).setCapabilityEpoch(1).build(),
            state,
            null,
        )
    }

    /** `Fixtures.good()` 이 선언하는 스킬. 기존 원장 시험과 같은 것을 쓴다. */
    private fun skill() = "pick_place"

    private companion object {
        val CONTRACT_SEMVER: String = dev.picasso.contracts.wire.ContractIdentity.semver
    }

    // ── 관측선 자체

    @Test
    fun `제공하는 기체가 없으면 제공자 없음이다`() {
        // **"관측선이 끊겼다"와 다르다.** 접으면 아직 아무 기체도 안 붙인
        // 스킬의 축소가 영원히 막힌다.
        assertEquals(Observability.NoProvider, observability.of(skill()))
    }

    @Test
    fun `셋 다 신선하면 살아 있다`() {
        listOf("r1", "r2", "r3").forEach { bound(it); report(it) }

        val seen = observability.of(skill())

        assertTrue(seen is Observability.Live, "$seen")
        assertEquals(clock, seen.asOf)
    }

    @Test
    fun `셋 중 하나가 조용하면 볼 수 없다`() {
        // **이 시험이 이 클래스의 이유다.** 전역 워터마크는 여기서 신선하다고
        // 답했다 — r1·r2 가 방금 보고했기 때문이다.
        listOf("r1", "r2").forEach { bound(it); report(it) }
        bound("r3"); report("r3", at = clock.minus(Duration.ofHours(48)))

        val seen = observability.of(skill())

        assertTrue(seen is Observability.Blind, "$seen")
        assertTrue("r3" in seen.reason, "사유에 조용한 기체가 없다: ${seen.reason}")
    }

    @Test
    fun `근거 시각은 가장 뒤처진 기체의 것이다`() {
        // 최댓값을 실으면 조용한 기체가 있어도 방금 관측한 것처럼 보인다.
        val old = clock.minus(Duration.ofHours(6))
        bound("r1"); report("r1", at = old)
        bound("r2"); report("r2")

        val seen = observability.of(skill())

        assertTrue(seen is Observability.Live, "$seen")
        assertEquals(old, seen.asOf, "최댓값을 실었다")
    }

    @Test
    fun `바인딩만 되고 한 번도 안 보고했으면 볼 수 없다`() {
        bound("r1"); report("r1")
        bound("r2")

        val seen = observability.of(skill())

        assertTrue(seen is Observability.Blind, "$seen")
        assertTrue("r2" in seen.reason, seen.reason)
    }

    @Test
    fun `해제된 바인딩의 기체는 안 본다`() {
        bound("r1"); report("r1")
        bound("r2"); report("r2", at = clock.minus(Duration.ofHours(48)))
        PostgresSupport.execute("UPDATE robot_binding SET unbound_at = now() WHERE robot_id = 'r2'")

        val seen = observability.of(skill())

        assertTrue(seen is Observability.Live, "해제된 기체가 축소를 막았다: $seen")
    }

    // ── 연결 상태 네 값

    @Test
    fun `HIBERNATING 은 살아 있는 것으로 센다`() {
        // 계약이 "침묵하지만 정상"을 표현하려고 만든 상태다. 침묵으로 읽으면
        // 절전한 라인이 매일 밤 축소를 막는다.
        bound("r1"); report("r1")
        bound("r2")
        report(
            "r2",
            state = ConnectionState.CONNECTION_STATE_HIBERNATING,
            at = clock.minus(Duration.ofHours(30)),
        )

        val seen = observability.of(skill())

        assertTrue(seen is Observability.Live, "절전한 기체가 축소를 막았다: $seen")
    }

    @Test
    fun `HIBERNATING 도 상한을 넘으면 볼 수 없다`() {
        // 보고하고 죽은 어댑터가 영원히 면제되면 그 오답은 축소를 **연다.**
        bound("r1"); report("r1")
        bound("r2")
        report(
            "r2",
            state = ConnectionState.CONNECTION_STATE_HIBERNATING,
            at = clock.minus(Duration.ofHours(100)),
        )

        val seen = observability.of(skill())

        assertTrue(seen is Observability.Blind, "절전이 영원히 면제됐다: $seen")
        assertTrue("r2" in seen.reason, seen.reason)
    }

    @Test
    fun `OFFLINE 이면 신선해도 볼 수 없다`() {
        // **방금 보고했어도 막는다.** 그 기체는 지금 말할 수 없다고 스스로
        // 알렸고, 그 상태에서 도는 태스크는 관측되지 않는다.
        bound("r1"); report("r1")
        bound("r2"); report("r2", state = ConnectionState.CONNECTION_STATE_OFFLINE)

        val seen = observability.of(skill())

        assertTrue(seen is Observability.Blind, "$seen")
        assertTrue("r2" in seen.reason, seen.reason)
    }

    @Test
    fun `CONNECTION_BROKEN 이면 볼 수 없다`() {
        bound("r1"); report("r1")
        bound("r2"); report("r2", state = ConnectionState.CONNECTION_STATE_CONNECTION_BROKEN)

        assertTrue(observability.of(skill()) is Observability.Blind)
    }

    // ── 게이트 경로가 실제로 이것을 쓰는가

    @Test
    fun `조용한 기체가 있으면 두 조회가 모두 모른다다`() {
        val query = RegistryLedgerQuery(db, robots = observability)
        ledger.observe("line-controller", "line-a", listOf("${skill()}@^1.0"))
        bound("r1"); report("r1")
        bound("r2"); report("r2", at = clock.minus(Duration.ofHours(48)))

        val consumers = query.activeConsumers(skill(), 1)
        val tasks = query.inflightTasks(skill(), 1)

        assertTrue(consumers is LedgerAnswer.NotObservable, "$consumers")
        assertTrue(tasks is LedgerAnswer.NotObservable, "$tasks")
        assertTrue("r2" in consumers.reason, consumers.reason)
    }

    @Test
    fun `기체가 신선하면 세고 근거 시각은 기체의 것이다`() {
        val query = RegistryLedgerQuery(db, robots = observability)
        ledger.observe("line-controller", "line-a", listOf("${skill()}@^1.0"))
        val old = clock.minus(Duration.ofHours(6))
        bound("r1"); report("r1", at = old)

        val answer = query.activeConsumers(skill(), 1)

        assertTrue(answer is LedgerAnswer.Observed, "$answer")
        assertEquals(1, answer.count)
        assertEquals(old, answer.asOf, "근거 시각이 기체 관측선이 아니다")
    }
}
