package dev.picasso.harness

import dev.picasso.profile.Requirement
import dev.picasso.profile.RequirementSet
import dev.picasso.registry.PostgresSupport
import dev.picasso.registry.ledger.ConsumerKind
import dev.picasso.registry.ledger.LedgerService
import dev.picasso.registry.ledger.RequirementSource
import dev.picasso.registry.store.Db
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 완료 기준 18의 앞 절반을 **진짜 협상으로** 본다.
 *
 * ## 왜 `registry`의 시험만으로는 부족한가
 *
 * `LedgerTest`는 `observe(...)`를 직접 부른다. 그것은 *"적재가 되는가"*를
 * 증명하지, *"협상 성공이 적재로 이어지는가"*를 증명하지 않는다. 그 사이에는
 * **누가 성공을 판정하는가**가 있고, 이 저장소의 규율은 그 판정이 `mimic`에
 * 있어야 한다는 것이다(§3.2 — `registry`는 `mimic`을 모른다).
 *
 * 그래서 여기서는 `client`가 실제로 `Negotiate`를 부르고, **그 응답의
 * `accepted`가 참일 때만** 원장에 얹는다. 레지스트리가 요구 문자열을 다시
 * 파싱해 "이건 되겠지"를 계산하는 구현이면 이 시험이 의미를 잃는다 —
 * 그래서 **거절된 요구가 원장에 안 오르는 것**을 함께 본다.
 *
 * ## 운영에서는 무엇이 이 자리인가
 *
 * §5.4의 성공 보고다. 브로커가 없으므로(§15.30) 그 보고를 나르는 구독기는
 * 아직 없고(§15.34), 여기서는 하네스가 그 자리를 맡는다. **나르는 것은
 * 전송이지 판정이 아니다.**
 */
class LedgerObservationTest {

    private lateinit var db: Db
    private lateinit var ledger: LedgerService

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        ledger = LedgerService(db)
    }

    private fun harness() = Harness(
        mapOf(ROBOT to Path.of("..", "profile", "fixtures", "minimal.json").normalize()),
    )

    /**
     * 협상하고, **성공했을 때만** 원장에 얹는다.
     *
     * @return 협상이 받아들여졌는가.
     */
    private fun negotiateAndReport(
        harness: Harness,
        clientId: String,
        requirements: RequirementSet,
    ): Boolean {
        val response = harness.client(clientId).negotiate(ROBOT, requirements)
        if (response.accepted) {
            ledger.observe(
                clientId,
                site = SITE,
                requires = requirements.requirements.map { it.toString() },
            )
        }
        return response.accepted
    }

    /**
     * 요구 집합은 **코드가 아니라 설정이다**(§5.4). `profile/requirements/`의
     * 것을 읽고 `client_id`만 갈아 끼운다.
     *
     * 손으로 지어내면 `optional_fields_used`와 `limits_needed`를 빼먹어
     * `REQUIRED_OPTIONAL_MISSING`으로 거절당하고(실측), 그것을 고치려고
     * 값을 찍어 넣으면 **시험이 프로파일의 선언을 두 번째로 적는 자리**가
     * 된다.
     */
    private fun fixture(name: String, clientId: String): RequirementSet =
        RequirementSet.parse(
            name,
            Files.readString(
                Path.of("..", "profile", "requirements", "$name.json").normalize(),
            ).replace("\r\n", "\n"),
        ).copy(clientId = clientId)

    /** 픽스처의 요구에 하나를 더한다. 선언 안 된 스킬을 섞을 때 쓴다. */
    private fun plus(set: RequirementSet, extra: Requirement): RequirementSet =
        set.copy(requirements = set.requirements + extra)

    // ── 성공이 원장에 오른다

    @Test
    fun `협상에 성공하면 등록 안 한 소비자도 원장에 오른다`() {
        harness().use { harness ->
            val accepted = negotiateAndReport(
                harness, "WCS-B",
                fixture("minimal", "WCS-B"),
            )
            assertTrue(accepted, "픽스처가 선언한 것을 요구했는데 거절됐다")
        }

        assertEquals(1, ledger.activeConsumerCount("pick_place"))
        assertEquals(1, ledger.activeConsumerCount("navigate_to"))

        val row = ledger.dependents("pick_place").active.single()
        assertEquals("WCS-B", row.consumerId)
        assertEquals(
            RequirementSource.OBSERVED, row.source,
            "등록한 적이 없는데 DECLARED로 올랐다",
        )
        assertEquals(
            Triple("CLIENT", "WCS-B", false),
            PostgresSupport.queryOne(
                "SELECT kind, display_name, registered FROM consumer WHERE consumer_id = 'WCS-B'",
            ) { Triple(it.getString(1), it.getString(2), it.getBoolean(3)) },
            "자동 생성이 §8.3의 규정과 다르다",
        )
    }

    // ── 거절은 오르지 않는다

    @Test
    fun `거절당한 요구는 원장에 안 오른다`() {
        // **이것이 "레지스트리가 판정하지 않는다"의 관측이다.** 레지스트리가
        // 요구 문자열만 보고 얹는 구현이면 여기서 `inspect`가 원장에 오르고,
        // 그 뒤 아무도 안 쓰는 능력이 영원히 축소를 못 하게 된다.
        harness().use { harness ->
            val accepted = negotiateAndReport(
                harness, "WCS-B", fixture("common", "WCS-B").copy(requirements = listOf(Requirement("inspect", 1, 0))),
            )
            assertTrue(!accepted, "픽스처가 선언 안 한 스킬인데 받아들여졌다")
        }

        assertEquals(
            0, ledger.activeConsumerCount("inspect"),
            "**거절당한 요구가 원장에 올랐다** — 그 능력은 이제 영원히 못 지운다",
        )
        assertEquals(
            0,
            PostgresSupport.queryOne("SELECT count(*) FROM consumer") { it.getInt(1) },
            "거절당한 소비자가 자동 생성됐다",
        )
    }

    @Test
    fun `일부만 맞으면 통째로 안 오른다`() {
        // 협상은 요구 집합 전체를 한 번에 본다(§5.4 — `NegotiateResponse`의
        // 주석). **부분 성공이 없다.** 원장이 절반만 얹으면 로봇이 거절한
        // 세션의 요구가 원장에 사는 상태가 된다.
        harness().use { harness ->
            val accepted = negotiateAndReport(
                harness, "WCS-B",
                plus(fixture("minimal", "WCS-B"), Requirement("inspect", 1, 0)),
            )
            assertTrue(!accepted)
        }
        assertEquals(0, ledger.activeConsumerCount("pick_place"))
        assertEquals(0, ledger.activeConsumerCount("inspect"))
    }

    // ── 등록과 관측이 만나는 자리

    @Test
    fun `등록해 둔 소비자가 협상하면 두 source가 공존한다`() {
        ledger.declare(
            "MES-A", ConsumerKind.UPSTREAM_SYSTEM, SITE, "MES",
            listOf("pick_place@^1.0"),
        )
        harness().use { harness ->
            assertTrue(
                negotiateAndReport(harness, "MES-A", fixture("minimal", "MES-A")),
            )
        }

        val active = ledger.dependents("pick_place").active
        assertEquals(
            listOf(RequirementSource.DECLARED, RequirementSource.OBSERVED),
            active.map { it.source },
            "등록이 관측에 덮였거나 그 반대다: $active",
        )
        assertEquals(
            1, ledger.activeConsumerCount("pick_place"),
            "소비자 하나를 둘로 센다",
        )
        assertEquals(
            Triple("UPSTREAM_SYSTEM", "MES", true),
            PostgresSupport.queryOne(
                "SELECT kind, display_name, registered FROM consumer WHERE consumer_id = 'MES-A'",
            ) { Triple(it.getString(1), it.getString(2), it.getBoolean(3)) },
            "관측이 등록 정보를 되돌렸다",
        )
    }

    @Test
    fun `여러 소비자가 각각 잡힌다`() {
        harness().use { harness ->
            assertTrue(negotiateAndReport(harness, "a", fixture("minimal", "a")))
            assertTrue(negotiateAndReport(harness, "b", fixture("minimal", "b")))
        }
        assertEquals(2, ledger.activeConsumerCount("pick_place"))
    }

    private companion object {
        const val ROBOT = "r1"

        /** `Harness`가 세우는 기체의 사이트. 자동 생성이 이 값을 쓴다(§8.3). */
        const val SITE = "line-a"
    }
}
