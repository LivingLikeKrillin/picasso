package dev.picasso.registry

import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.ProfileRef
import dev.picasso.contracts.v1.Rejection
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.registry.adapter.AdapterService
import dev.picasso.registry.adapter.ConformanceStatus
import dev.picasso.registry.adapter.RegisterOutcome
import dev.picasso.registry.binding.ActivateOutcome
import dev.picasso.registry.binding.BindOutcome
import dev.picasso.registry.binding.BindingService
import dev.picasso.registry.diag.DiagnosticsService
import dev.picasso.registry.observe.EpochCause
import dev.picasso.registry.observe.ObservationService
import dev.picasso.registry.revision.RevisionService
import dev.picasso.registry.revision.SkillTypeSync
import dev.picasso.registry.revision.SubmitOutcome
import dev.picasso.registry.store.Db
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * §8.5의 진단 1~4.
 *
 * ## 각 진단마다 넷을 본다
 *
 * (a) 빈 경우 (b) 한 줄 (c) 여럿의 정렬 (d) **필터가 실제로 좁히는가.**
 *
 * (d)가 빠지면 **필터를 무시하는 구현이 통과한다** — 사이트를 넘겨도 전부
 * 돌려주는 진단은 (a)(b)(c)를 전부 지난다. 이 저장소가 이미 카탈로그에서
 * 한 번 물린 자리다.
 */
class DiagnosticsTest {

    private lateinit var db: Db
    private lateinit var revisions: RevisionService
    private lateinit var bindings: BindingService
    private lateinit var adapters: AdapterService
    private lateinit var observations: ObservationService
    private lateinit var diag: DiagnosticsService
    private var adapterVersionId: Long = 0

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        revisions = RevisionService(db, Fixtures.validator())
        bindings = BindingService(db)
        adapters = AdapterService(db)
        observations = ObservationService(db)
        diag = DiagnosticsService(db)
        SkillTypeSync(db).sync(Fixtures.descriptor(), CONTRACT_SEMVER, "sync")

        val adapterId = adapters.registerAdapter("acme", "drv", "op")
        adapterVersionId = (
            adapters.registerVersion(adapterId, "1.0.0", CONTRACT_SEMVER, "op")
                as RegisterOutcome.Registered
            ).adapterVersionId
    }

    private fun robot(id: String, site: String = "line-a"): String = db.transaction { c ->
        c.prepareStatement(
            "INSERT INTO robot (robot_id, site_id, serial_number) VALUES (?, ?, ?)",
        ).use { it.setString(1, id); it.setString(2, site); it.setString(3, "sn-$id"); it.executeUpdate() }
        id
    }

    private fun activate(documentJson: String): Long {
        val stored = revisions.submit(documentJson, "op")
        assertTrue(stored is SubmitOutcome.Stored, "$stored")
        BindingService.SUITE_NAMES.forEach {
            bindings.recordTestRun(stored.profileRevisionId, it, "PASS", "harness")
        }
        assertTrue(bindings.activate(stored.profileRevisionId, "op") is ActivateOutcome.Activated)
        return stored.profileRevisionId
    }

    private fun bind(robotId: String, profileRevisionId: Long) {
        val outcome = bindings.bind(robotId, adapterVersionId, profileRevisionId, "op")
        assertTrue(outcome is BindOutcome.Bound, "$outcome")
    }

    private fun header(robotId: String, epoch: Long, revision: Int) = MessageHeader.newBuilder()
        .setRobotId(robotId)
        .setCapabilityEpoch(epoch)
        .setProfileRef(
            ProfileRef.newBuilder().setProfileId("fixture/minimal").setRevision(revision),
        )
        .build()

    // ── 진단 1

    @Test
    fun `바인딩이 없으면 빈 답이다`() {
        val answer = diag.bindings()
        assertEquals(emptyList(), answer.rows)
        assertEquals(emptyMap(), answer.robotsPerRevision)
    }

    @Test
    fun `한 줄에 기체 어댑터 개정판 적합성이 함께 온다`() {
        // 넷이 한 줄에 없으면 운영자가 네 번 조회한다.
        val rev = activate(Fixtures.good())
        bind(robot("r1"), rev)

        val row = diag.bindings().rows.single()
        assertEquals("r1", row.robotId)
        assertEquals("line-a", row.siteId)
        assertEquals("fixture", row.vendor)
        assertEquals("minimal", row.model)
        assertEquals(1, row.revision)
        assertEquals("drv", row.adapterName)
        assertEquals("1.0.0", row.adapterVersion)
        assertEquals(
            "UNTESTED", row.conformanceStatus,
            "**시험 안 된 어댑터가 조용히 돈다** — 완료 기준 16이 여기를 본다",
        )
        assertTrue(row.active)
    }

    @Test
    fun `개정판별 기체 수가 함께 온다`() {
        // §8.5가 진단 1번에 **두 질문**을 걸었다. 쪼개면 운영자가 두 번
        // 조회해 머릿속에서 조인하고, 그 조인은 두 조회 사이에 바인딩이
        // 바뀌면 틀린다.
        val rev = activate(Fixtures.good())
        bind(robot("r1"), rev)
        bind(robot("r2"), rev)
        bind(robot("r3"), rev)

        assertEquals(mapOf(rev to 3), diag.bindings().robotsPerRevision)
    }

    @Test
    fun `해제된 바인딩은 기본으로 안 나오고 이력으로는 나온다`() {
        // **카탈로그와 다른 점이 여기다.** 카탈로그는 "지금 무엇으로
        // 도는가"만 답하고(§9.1) 진단은 "무엇이었는가"에도 답한다.
        val rev1 = activate(Fixtures.good(revision = 1))
        bind(robot("r1"), rev1)
        val rev2 = activate(Fixtures.good(revision = 2))
        bind("r1", rev2)

        val current = diag.bindings()
        assertEquals(1, current.rows.size, "해제된 것이 기본 답에 섞였다")
        assertEquals(rev2, current.rows.single().profileRevisionId)

        val history = diag.bindings(includeHistory = true)
        assertEquals(2, history.rows.size, "이력을 달라 했는데 안 준다")
        assertEquals(
            listOf(true, false), history.rows.map { it.active },
            "최신이 위여야 한다",
        )
        assertEquals(
            mapOf(rev2 to 1), history.robotsPerRevision,
            "**해제된 것까지 세면 실제 대수를 넘는다** — 축소 판단이 그 숫자를 본다",
        )
    }

    @Test
    fun `사이트로 좁힌다`() {
        // (d). 없으면 필터를 무시하는 구현이 나머지를 전부 지난다.
        val rev = activate(Fixtures.good())
        bind(robot("r1", "line-a"), rev)
        bind(robot("r2", "line-b"), rev)

        assertEquals(listOf("r1"), diag.bindings("line-a").rows.map { it.robotId })
        assertEquals(listOf("r2"), diag.bindings("line-b").rows.map { it.robotId })
        assertEquals(emptyList(), diag.bindings("line-z").rows)
        assertEquals(2, diag.bindings().rows.size, "필터를 안 줬는데 좁혔다")
    }

    @Test
    fun `적합성 결과가 반영된다`() {
        val rev = activate(Fixtures.good())
        bind(robot("r1"), rev)
        adapters.recordConformance(adapterVersionId, ConformanceStatus.PASSED, "qa")
        assertEquals("PASSED", diag.bindings().rows.single().conformanceStatus)
    }

    // ── 진단 2

    @Test
    fun `diff가 게이트의 분류를 그대로 낸다`() {
        // **진단이 자기 분류를 새로 만들면** 게이트는 축소라 부르는데
        // 화면은 확장이라 부르는 날이 오고, 그날 운영자는 화면을 믿는다.
        val rev1 = activate(Fixtures.good(revision = 1))
        val narrowed = Fixtures.good(revision = 2).replace("\"max_value\": 120", "\"max_value\": 10")
        check("\"max_value\": 10" in narrowed) { "치환이 안 됐다" }
        val rev2 = activate(narrowed)

        val answer = diag.diff(rev1, rev2)
        assertEquals(1, answer.from)
        assertEquals(2, answer.to)
        assertTrue(
            answer.findings.any { "grip_force" in it.message },
            "수치 범위 축소가 diff에 안 나온다: ${answer.findings}",
        )
    }

    @Test
    fun `안 바뀌었으면 diff가 비어 있다`() {
        // 위 시험의 짝. 언제나 뭔가 나오면 "차이가 있다"가 아무 뜻도 없다.
        val rev1 = activate(Fixtures.good(revision = 1))
        val rev2 = activate(Fixtures.good(revision = 2))
        assertEquals(emptyList(), diag.diff(rev1, rev2).findings)
    }

    @Test
    fun `선택 필드는 안 본다고 답한다`() {
        // **빈 배열로 내면 "선택 필드 차이가 없다"는 거짓말이 된다.**
        // `profile_optional_field`는 아직 미룬 표다.
        val rev1 = activate(Fixtures.good(revision = 1))
        val rev2 = activate(Fixtures.good(revision = 2))
        assertEquals("not_tracked", diag.diff(rev1, rev2).optionalFields)
    }

    @Test
    fun `건너뛴 검사를 차이 없음으로 접지 않는다`() {
        // **자원이 없어 못 본 것을 깨끗하다고 내면 화면이 조용히 거짓말한다.**
        //
        // 이 시험이 없으면 그 가드를 지우는 결함이 안 잡힌다 — 지금 배선에서
        // 6번은 언제나 돌기 때문이다. 그래서 **돌 수 없는 검사를 끼워** 그
        // 길을 실제로 밟는다.
        val rev1 = activate(Fixtures.good(revision = 1))
        val rev2 = activate(Fixtures.good(revision = 2))

        val blind = object : dev.picasso.gate.GateCheck {
            override val id = "6"
            override val name = "돌 수 없는 검사"
            override val requires = setOf(dev.picasso.gate.Resource.REPO)
            override fun run(input: dev.picasso.gate.input.GateInput) =
                error("자원이 없으므로 돌면 안 된다")
        }

        val answer = DiagnosticsService(db, blind).diff(rev1, rev2)
        assertEquals(1, answer.findings.size, "건너뛴 것을 조용히 넘겼다: $answer")
        assertEquals("ERROR", answer.findings.single().severity)
        assertTrue(
            "diff를 내지 못했다" in answer.findings.single().message,
            answer.findings.single().message,
        )
    }

    @Test
    fun `다른 기종끼리는 diff하지 않는다`() {
        // 6번은 기준선 키가 안 맞으면 "신규"로 읽어 **아무 차이도 없다고
        // 답한다.** 그것이 가장 위험한 거짓말이므로 아예 거부한다.
        val a = activate(Fixtures.good(revision = 1))
        val b = revisions.submit(Fixtures.good(revision = 1, model = "other"), "op")
        assertTrue(b is SubmitOutcome.Stored, "$b")

        assertFailsWith<IllegalArgumentException> { diag.diff(a, b.profileRevisionId) }
    }

    // ── 진단 3

    @Test
    fun `세대 이력이 없으면 빈 답이다`() {
        robot("r1")
        assertEquals(emptyList(), diag.epochs("r1"))
    }

    @Test
    fun `세대 이력이 최신부터 온다`() {
        // 운영자가 찾는 것은 방금 무슨 일이 났나다.
        robot("r1")
        observations.recordHeader(header("r1", 1, 1), EpochCause.BINDING_CHANGED)
        observations.recordHeader(header("r1", 2, 2), EpochCause.BINDING_CHANGED)
        observations.recordHeader(header("r1", 3, 2), EpochCause.RUNTIME_DEGRADED)

        val rows = diag.epochs("r1")
        assertEquals(listOf(3L, 2L, 1L), rows.map { it.epoch })
        assertEquals("RUNTIME_DEGRADED", rows.first().cause)
        assertEquals(2, rows.first().revision)
        assertEquals("fixture/minimal", rows.first().profileId)
    }

    @Test
    fun `기체로 좁힌다`() {
        // (d).
        robot("r1"); robot("r2")
        observations.recordHeader(header("r1", 1, 1))
        observations.recordHeader(header("r2", 1, 1))

        assertEquals(1, diag.epochs("r1").size)
        assertEquals(1, diag.epochs("r2").size)
        assertEquals(emptyList(), diag.epochs("없는-기체"))
    }

    @Test
    fun `이력이 제한된다`() {
        robot("r1")
        repeat(5) { observations.recordHeader(header("r1", it.toLong(), 1)) }
        assertEquals(2, diag.epochs("r1", limit = 2).size)
    }

    // ── 진단 4

    @Test
    fun `거절이 없으면 빈 답이다`() {
        assertEquals(emptyList(), diag.rejections())
    }

    @Test
    fun `거절이 최신부터 오고 사유가 함께 온다`() {
        // 사유가 없으면 운영자가 **무엇을 고쳐야 하는지** 모른다.
        reject("r1", "consumer-a", RejectionCode.REJECTION_CODE_SKILL_ABSENT, "선언하지 않은 스킬이다")
        reject("r1", "consumer-b", RejectionCode.REJECTION_CODE_LIMIT_EXCEEDED, "한계를 넘는다")

        val rows = diag.rejections()
        assertEquals(listOf("consumer-b", "consumer-a"), rows.map { it.clientId })
        assertTrue("한계를 넘는다" in rows.first().detail!!, "${rows.first().detail}")
        assertTrue("pick_place@^1.2" in rows.first().requirement, rows.first().requirement)
    }

    @Test
    fun `코드로 좁힌다`() {
        // (d). 다섯 코드의 대응이 서로 다르므로(§4.3) 좁히지 못하면
        // 운영자가 자기가 찾는 종류를 눈으로 골라야 한다.
        reject("r1", "a", RejectionCode.REJECTION_CODE_SKILL_ABSENT, "x")
        reject("r1", "b", RejectionCode.REJECTION_CODE_LIMIT_EXCEEDED, "y")
        reject("r1", "c", RejectionCode.REJECTION_CODE_SKILL_ABSENT, "z")

        assertEquals(
            listOf("c", "a"),
            diag.rejections(RejectionCode.REJECTION_CODE_SKILL_ABSENT.name).map { it.clientId },
        )
        assertEquals(3, diag.rejections().size, "코드를 안 줬는데 좁혔다")
        assertEquals(
            emptyList(),
            diag.rejections(RejectionCode.REJECTION_CODE_IDENTITY_MISMATCH.name),
        )
    }

    @Test
    fun `거절이 제한된다`() {
        repeat(5) { reject("r1", "c$it", RejectionCode.REJECTION_CODE_SKILL_ABSENT, "x") }
        assertEquals(2, diag.rejections(limit = 2).size)
    }

    private fun reject(robotId: String, clientId: String, code: RejectionCode, detail: String) =
        observations.recordRejection(
            robotId, clientId, listOf("pick_place@^1.2"),
            Rejection.newBuilder().setCode(code).setDetail(detail).build(),
        )

    private companion object {
        val CONTRACT_SEMVER: String = dev.picasso.contracts.wire.ContractIdentity.semver
    }
}
