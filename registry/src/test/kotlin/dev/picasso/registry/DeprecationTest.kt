package dev.picasso.registry

import dev.picasso.registry.plan.CheckType
import dev.picasso.registry.plan.PreconditionCheck
import dev.picasso.registry.plan.Preconditions
import dev.picasso.registry.ledger.LedgerService
import dev.picasso.registry.revision.AnnounceOutcome
import dev.picasso.registry.revision.DeprecationService
import dev.picasso.registry.revision.SkillTypeSync
import dev.picasso.registry.revision.WithdrawOutcome
import dev.picasso.registry.store.Db
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §9.3의 **계약 축 폐기 예고**에 쓰기 경로를 붙인다(§15.43).
 *
 * ## 이 표에 쓰는 코드가 없으면 무슨 일이 생기나
 *
 * §9.3이 폐기 시각을 *"계약 축과 프로파일 축 두 값 중 이른 쪽"*으로 규정했다.
 * 계약 축에 값을 넣을 방법이 없으면 그 규칙은 **언제나 프로파일 축을 고른다** —
 * 규칙이 규칙이 아니게 되고, `V6__catalog.sql`이 그 표를 만들면서 적어 둔
 * 이유가 무너진다.
 *
 * ## 예고는 게이트가 아니다
 *
 * 그래서 이 시험은 "예고했으니 축소가 열린다"를 보지 않는다. 예고가 여는 것은
 * `DEPRECATION_PUBLISHED` 하나뿐이고, 축소를 여는 것은 §9.3의 **두 조회**다.
 */
class DeprecationTest {

    private lateinit var db: Db
    private lateinit var deprecations: DeprecationService
    private lateinit var checks: Preconditions

    private val after: Instant = Instant.parse("2026-12-31T00:00:00Z")

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        deprecations = DeprecationService(db)
        checks = Preconditions(db, LedgerService(db))
        SkillTypeSync(db).sync(Fixtures.descriptor(), CONTRACT_SEMVER, "sync")
    }

    private fun announced(skill: String, major: Int) = checks.evaluate(
        PreconditionCheck(
            CheckType.DEPRECATION_PUBLISHED,
            mapOf("skill" to skill, "major" to major.toString()),
        ),
    )

    /**
     * `pick_place@2`를 만든다.
     *
     * **계약에 그 major가 없어서 `SkillTypeSync`가 안 넣는다.** 여기서 보려는
     * 것은 예고의 major 해상도이지 동기화가 아니므로 표에 직접 넣는다 —
     * 안 넣으면 `@2`의 판정이 "예고가 없다"가 아니라 **"그 판이 아예 없다"**가
     * 되고, 그러면 두 시험이 같은 false 를 서로 다른 이유로 보게 된다.
     */
    private fun makeMajorTwo() = PostgresSupport.execute(
        "INSERT INTO skill_type (name, major, introduced_in_semver) " +
            "VALUES ('pick_place', 2, '1.0.0')",
    )

    private fun rows(): Int = PostgresSupport.queryOne(
        "SELECT count(*) FROM skill_type_deprecation",
    ) { it.getInt(1) }

    private fun audits(op: String): Int = PostgresSupport.queryOne(
        "SELECT count(*) FROM audit_log WHERE operation = '$op'",
    ) { it.getInt(1) }

    @Test
    fun `예고를 기입하면 전제 조건이 그것을 본다`() {
        // **읽는 쪽이 이미 있었고 쓰는 쪽만 없었다**(§15.43). 그래서 이 시험은
        // 조작 하나로 두 코드를 잇는다.
        assertFalse(announced("pick_place", 1).satisfied, "예고 전인데 기입됐다고 한다")

        val outcome = deprecations.announce("pick_place", 1, after, "op", note = "@2로 옮긴다")

        assertTrue(outcome is AnnounceOutcome.Announced, "$outcome")
        assertNull(outcome.replaced, "처음 기입인데 옛 값이 있다고 한다")
        assertTrue(announced("pick_place", 1).satisfied)
    }

    @Test
    fun `모르는 스킬 타입은 거절한다`() {
        // 조용히 아무것도 안 하면 운영자는 예고한 줄 알고 다음 단계로 간다.
        val outcome = deprecations.announce("nope", 1, after, "op")

        assertTrue(outcome is AnnounceOutcome.Rejected, "$outcome")
        assertTrue("nope@1" in outcome.detail, outcome.detail)
        assertEquals(0, rows())
    }

    @Test
    fun `모르는 major 도 거절한다`() {
        // 표의 PK 가 skill_type_id 이고 그것이 (name, major) 단위다 — 이름만
        // 맞으면 통과하는 구현은 없는 판에 예고를 붙인다.
        val outcome = deprecations.announce("pick_place", 9, after, "op")

        assertTrue(outcome is AnnounceOutcome.Rejected, "$outcome")
        assertEquals(0, rows())
    }

    @Test
    fun `다시 기입하면 옛 값을 돌려주고 덮어쓴다`() {
        // 늦추는 것도 앞당기는 것도 운영 판단이다. 막지 않되 **무엇이
        // 바뀌었는지**는 돌려준다.
        deprecations.announce("pick_place", 1, after, "op")
        val later = after.plusSeconds(86_400)

        val outcome = deprecations.announce("pick_place", 1, later, "op2")

        assertTrue(outcome is AnnounceOutcome.Announced, "$outcome")
        assertEquals(after, outcome.replaced, "옛 값을 안 돌려준다")
        assertEquals(1, rows(), "덮어쓰지 않고 두 줄이 됐다")
        assertEquals(
            later,
            PostgresSupport.queryOne(
                "SELECT deprecated_after FROM skill_type_deprecation",
            ) { it.getTimestamp(1).toInstant() },
        )
    }

    @Test
    fun `예고는 되돌릴 수 있다`() {
        // §9.5가 ANNOUNCE 의 가역성을 "예고를 지우면 된다"로 규정했다.
        // 지울 방법이 없으면 그 표의 "가역"이 거짓말이다.
        deprecations.announce("pick_place", 1, after, "op")

        val outcome = deprecations.withdraw("pick_place", 1, "op")

        assertTrue(outcome is WithdrawOutcome.Withdrawn, "$outcome")
        assertEquals(after, outcome.was)
        assertFalse(announced("pick_place", 1).satisfied, "지웠는데 예고가 남아 있다")
    }

    @Test
    fun `없는 예고를 지운 것으로 보고하지 않는다`() {
        // "취소했다"로 읽으면 운영자는 다른 축(프로파일)의 예고가 남아 있는
        // 것을 못 본다.
        val outcome = deprecations.withdraw("pick_place", 1, "op")

        assertTrue(outcome is WithdrawOutcome.NothingToWithdraw, "$outcome")
    }

    @Test
    fun `두 조작이 감사 로그를 남긴다`() {
        // §8.5 — API 한 번 = 트랜잭션 한 번 = 감사 로그 한 줄.
        deprecations.announce("pick_place", 1, after, "op")
        deprecations.withdraw("pick_place", 1, "op")

        assertEquals(1, audits("SKILL_TYPE_DEPRECATION_ANNOUNCE"))
        assertEquals(1, audits("SKILL_TYPE_DEPRECATION_WITHDRAW"))
    }

    @Test
    fun `거절은 감사 로그를 안 남긴다`() {
        // 일어나지 않은 조작이 기록에 남으면 그 기록으로 답할 수 없는 질문이
        // 생긴다.
        deprecations.announce("nope", 1, after, "op")

        assertEquals(0, audits("SKILL_TYPE_DEPRECATION_ANNOUNCE"))
    }

    // ── major 판정 (§15.50의 남은 반쪽)

    @Test
    fun `다른 major 에 낸 예고는 이 major 를 열지 않는다`() {
        // **이것이 §15.50의 남은 부분이었다.** 이름만 보면 @2 에 낸 예고가
        // @1 의 제거를 열어 준다 — 아직 아무도 옮기라고 듣지 못한 판이다.
        makeMajorTwo()
        assertTrue(
            deprecations.announce("pick_place", 2, after, "op") is AnnounceOutcome.Announced,
        )

        assertFalse(
            announced("pick_place", 1).satisfied,
            "@2 예고가 @1 을 열었다",
        )
        assertTrue(announced("pick_place", 2).satisfied)
    }

    @Test
    fun `프로파일 축 예고도 major 를 짚는다`() {
        // 두 축 중 하나라도 있으면 예고된 것인데(§9.3), 그 "하나"가 같은
        // major 여야 한다.
        makeMajorTwo()
        val revisionId = activateProfile()
        PostgresSupport.execute(
            "UPDATE profile_skill ps SET deprecated_after = now() " +
                "FROM skill_type s WHERE s.skill_type_id = ps.skill_type_id " +
                "AND s.name = 'pick_place' AND s.major = 1 " +
                "AND ps.profile_revision_id = $revisionId",
        )

        assertTrue(announced("pick_place", 1).satisfied, "프로파일 축 예고가 안 보인다")
        assertFalse(announced("pick_place", 2).satisfied, "@1 예고가 @2 를 열었다")
    }

    private fun activateProfile(): Long {
        val stored = dev.picasso.registry.revision.RevisionService(db, Fixtures.validator())
            .submit(Fixtures.good(), "op")
        assertTrue(stored is dev.picasso.registry.revision.SubmitOutcome.Stored, "$stored")
        val bindings = dev.picasso.registry.binding.BindingService(db)
        dev.picasso.registry.binding.BindingService.SUITE_NAMES.forEach {
            bindings.recordTestRun(stored.profileRevisionId, it, "PASS", "harness")
        }
        assertTrue(
            bindings.activate(stored.profileRevisionId, "op")
                is dev.picasso.registry.binding.ActivateOutcome.Activated,
        )
        return stored.profileRevisionId
    }

    private companion object {
        val CONTRACT_SEMVER: String = dev.picasso.contracts.wire.ContractIdentity.semver
    }
}
