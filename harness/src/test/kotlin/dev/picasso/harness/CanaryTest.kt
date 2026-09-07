package dev.picasso.harness

import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.mimic.RegistryBinding
import dev.picasso.mimic.RegistrySource
import dev.picasso.mimic.control.v1.PullRegistryRequest
import dev.picasso.registry.Fixtures
import dev.picasso.registry.PostgresSupport
import dev.picasso.registry.adapter.AdapterService
import dev.picasso.registry.adapter.RegisterOutcome
import dev.picasso.registry.binding.ActivateOutcome
import dev.picasso.registry.binding.BindOutcome
import dev.picasso.registry.binding.BindingService
import dev.picasso.registry.observe.ObservationService
import dev.picasso.registry.revision.RevisionService
import dev.picasso.registry.revision.SkillTypeSync
import dev.picasso.registry.revision.SubmitOutcome
import dev.picasso.registry.store.Db
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 완료 기준 20 — **일부 기체만 새 개정판으로 바인딩했을 때 두 개정판이
 * 동시에 돌고, 헤더 `profile_ref`로 어느 기체가 어느 개정판인지 관측된다.**
 *
 * ## 왜 이 시험이 `harness`에 있나
 *
 * 카나리는 **레지스트리에서 바인딩하고 → `mimic`이 당기고 → 헤더에 실려
 * 나오고 → 다시 레지스트리가 적재하는** 한 줄이다. 반으로 쪼개 한쪽은
 * `registry`에서, 한쪽은 `mimic`에서 보면 **두 표면을 서로 비교하는 것**이
 * 되고, 그 사이에 낀 결함은 어느 쪽에도 안 보인다. `harness`만이 둘 다 안다.
 *
 * ## 카나리는 공짜다(§9.7 ⑤)
 *
 * `robot_binding`이 기체 단위이므로 일부만 새 조합으로 바인딩하면 된다.
 * 그때 **옛 개정판은 `SUPERSEDED`이지만 그것을 쓰는 바인딩은 살아 있다** —
 * 새로 붙일 수는 없고 이미 붙은 것은 돈다. 그것이 카나리 상태의 정확한
 * 모양이며, 이 시험이 그 상태를 실제로 만든다.
 *
 * ## 폴링이 HTTP가 아닌 자리
 *
 * 운영에서 `mimic`은 레지스트리를 HTTP로 당긴다(§10.3). `mimic`은
 * `registry`를 빌드 의존하지 않으므로(§3.2) 여기서는 시험이 그 자리에
 * **DB를 직접 읽는 출처**를 끼운다. 끼우는 것은 전송이지 판정이 아니다 —
 * 무엇을 내줄지는 레지스트리의 바인딩 표가 정한다.
 */
class CanaryTest {

    private lateinit var db: Db
    private lateinit var revisions: RevisionService
    private lateinit var bindings: BindingService
    private lateinit var adapters: AdapterService
    private lateinit var observations: ObservationService
    private var adapterVersionId: Long = 0

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        revisions = RevisionService(db, Fixtures.validator())
        bindings = BindingService(db)
        adapters = AdapterService(db)
        observations = ObservationService(db)
        SkillTypeSync(db).sync(Fixtures.descriptor(), CONTRACT_SEMVER, "sync")

        listOf(R1, R2).forEach { id ->
            db.transaction { c ->
                c.prepareStatement(
                    "INSERT INTO robot (robot_id, site_id, serial_number) " +
                        "VALUES (?, 'line-a', ?)",
                ).use { it.setString(1, id); it.setString(2, "sn-$id"); it.executeUpdate() }
            }
        }

        val adapterId = adapters.registerAdapter("acme", "drv", "op")
        adapterVersionId = (
            adapters.registerVersion(adapterId, "1.0.0", CONTRACT_SEMVER, "op")
                as RegisterOutcome.Registered
            ).adapterVersionId
    }

    /**
     * §10.3의 폴링이 당기는 곳. **레지스트리의 활성 바인딩을 그대로 읽는다.**
     *
     * 여기서 어떤 판정도 하지 않는 것이 요점이다 — 판정을 넣으면 이 시험이
     * 증명하는 것이 "레지스트리의 바인딩"이 아니라 "이 클래스"가 된다.
     */
    private inner class RegistryBackedSource : RegistrySource {
        override fun binding(robotId: String): RegistryBinding? = db.open().use { c ->
            c.prepareStatement(
                """
                SELECT pr.profile_revision_id, pr.revision, pr.document::text
                FROM robot_binding b
                JOIN profile_revision pr ON pr.profile_revision_id = b.profile_revision_id
                WHERE b.robot_id = ? AND b.unbound_at IS NULL
                """.trimIndent(),
            ).use { s ->
                s.setString(1, robotId)
                s.executeQuery().use { rs ->
                    if (rs.next()) RegistryBinding(rs.getLong(1), rs.getInt(2), rs.getString(3))
                    else null
                }
            }
        }
    }

    private fun harness() = Harness(
        mapOf(
            R1 to Path.of("..", "profile", "fixtures", "minimal.json").normalize(),
            R2 to Path.of("..", "profile", "fixtures", "minimal.json").normalize(),
        ),
        registrySource = RegistryBackedSource(),
    )

    private fun Harness.pull(robotId: String) =
        oracle.pullRegistry(PullRegistryRequest.newBuilder().setRobotId(robotId).build())

    // ── 개정판 둘을 세운다

    /** `grip_force` 상한을 좁힌 개정판 2. **거동이 실제로 갈려야 카나리다.** */
    private fun narrowed(): String {
        val text = Fixtures.good(revision = 2).replace("\"max_value\": 120", "\"max_value\": 10")
        check("\"max_value\": 10" in text) { "치환이 아무것도 바꾸지 못했다" }
        return text
    }

    /** 제출 → 시험 PASS 기입 → 활성화. §8.4의 ①②③을 한 걸음으로 줄인다. */
    private fun activate(documentJson: String): Long {
        val stored = revisions.submit(documentJson, "op")
        assertTrue(stored is SubmitOutcome.Stored, "$stored")
        BindingService.SUITE_NAMES.forEach {
            bindings.recordTestRun(stored.profileRevisionId, it, "PASS", "harness")
        }
        val activated = bindings.activate(stored.profileRevisionId, "op")
        assertTrue(activated is ActivateOutcome.Activated, "$activated")
        return stored.profileRevisionId
    }

    private fun bind(robotId: String, profileRevisionId: Long) {
        val outcome = bindings.bind(robotId, adapterVersionId, profileRevisionId, "op")
        assertTrue(outcome is BindOutcome.Bound, "$robotId 바인딩 실패: $outcome")
    }

    private val gripForce80 = listOf(
        ParameterValue.newBuilder().setKey("object_id").setStringValue("b").build(),
        ParameterValue.newBuilder().setKey("destination").setStringValue("d").build(),
        ParameterValue.newBuilder().setKey("grip_force").setNumberValue(80.0).build(),
    )

    // ── 카나리

    @Test
    fun `일부만 새 개정판으로 바꾸면 두 개정판이 동시에 돈다`() {
        val rev1 = activate(Fixtures.good(revision = 1))
        bind(R1, rev1)
        bind(R2, rev1)

        val rev2 = activate(narrowed())
        // **여기가 카나리다.** R2만 새 개정판으로 옮긴다.
        bind(R2, rev2)

        harness().use { harness ->
            assertTrue(harness.pull(R1).changed, "R1이 개정판을 못 집었다")
            assertTrue(harness.pull(R2).changed, "R2가 개정판을 못 집었다")

            // ① 헤더가 갈린다
            val h1 = harness.client().start(R1, "t1", 1, "pick_place", gripForce80).header
            val h2 = harness.client().start(R2, "t2", 1, "pick_place", gripForce80).header

            assertEquals(1, h1.profileRef.revision, "R1이 개정판 1이 아니다")
            assertEquals(2, h2.profileRef.revision, "R2가 개정판 2가 아니다")
            // **한쪽 헤더를 둘 다에 실어도 위의 둘 중 하나는 통과한다.**
            assertNotEquals(
                h1.profileRef.revision, h2.profileRef.revision,
                "두 기체가 같은 개정판을 보고한다 — 카나리가 아니다",
            )
            assertEquals(
                h1.profileRef.profileId, h2.profileRef.profileId,
                "같은 기종이어야 한다 — 다르면 개정판이 아니라 기종이 갈린 것이다",
            )

            // ② 거동이 갈린다. **이것이 "동시에 돈다"의 증거다** —
            //    헤더만 다르고 거동이 같으면 이름표만 바꾼 것이다.
            val onOld = harness.client().start(R1, "t3", 1, "pick_place", gripForce80)
            val onNew = harness.client().start(R2, "t4", 1, "pick_place", gripForce80)
            assertTrue(onOld.hasHandle(), "옛 개정판이 옛 제약을 안 쓴다: ${onOld.rejection}")
            assertEquals(
                RejectionCode.REJECTION_CODE_PARAMETER_INVALID, onNew.rejection.code,
                "새 개정판이 좁아진 제약을 안 쓴다",
            )

            // ③ 관측으로 남는다 — 운영자가 "지금 누가 무엇으로 도는가"를
            //    추측이 아니라 조회로 답한다.
            observations.recordHeader(h1)
            observations.recordHeader(h2)
            assertEquals(1, observedRevision(R1))
            assertEquals(2, observedRevision(R2))
        }
    }

    @Test
    fun `카나리 전에는 둘이 같은 개정판이다`() {
        // 위 시험의 전제. 언제나 갈려 있으면 "일부만 바꿨다"가 아무 뜻도
        // 없다.
        val rev1 = activate(Fixtures.good(revision = 1))
        bind(R1, rev1)
        bind(R2, rev1)

        harness().use { harness ->
            harness.pull(R1)
            harness.pull(R2)
            val h1 = harness.client().start(R1, "t1", 1, "pick_place", gripForce80).header
            val h2 = harness.client().start(R2, "t2", 1, "pick_place", gripForce80).header
            assertEquals(h1.profileRef.revision, h2.profileRef.revision)
            assertEquals(1, h1.profileRef.revision)
        }
    }

    @Test
    fun `전면 전환하면 카나리가 끝난다`() {
        // **끝나는 것까지 봐야 카나리다.** 안 그러면 그냥 갈라진 상태이고,
        // 갈라진 채로 두는 것은 운영이 아니라 사고다.
        val rev1 = activate(Fixtures.good(revision = 1))
        bind(R1, rev1)
        bind(R2, rev1)
        val rev2 = activate(narrowed())
        bind(R2, rev2)

        harness().use { harness ->
            harness.pull(R1)
            harness.pull(R2)

            bind(R1, rev2)
            assertTrue(harness.pull(R1).changed, "전면 전환을 R1이 못 집었다")

            val h1 = harness.client().start(R1, "t1", 1, "pick_place", gripForce80)
            assertEquals(
                RejectionCode.REJECTION_CODE_PARAMETER_INVALID, h1.rejection.code,
                "전면 전환했는데 R1이 옛 제약을 쓴다",
            )
            assertEquals(2, h1.header.profileRef.revision)
        }
    }

    @Test
    fun `옛 개정판은 SUPERSEDED가 되지만 그것을 쓰는 바인딩은 산다`() {
        // 카나리 상태의 정확한 모양이다. **새로 붙일 수는 없고 이미 붙은
        // 것은 돈다** — 이 둘을 함께 보지 않으면 "활성 개정판만 돈다"는
        // 잘못된 모형이 남는다.
        val rev1 = activate(Fixtures.good(revision = 1))
        bind(R1, rev1)
        val rev2 = activate(narrowed())

        assertEquals("SUPERSEDED", statusOf(rev1))
        assertEquals("ACTIVE", statusOf(rev2))
        assertEquals(
            rev1, boundRevisionId(R1),
            "활성화가 이미 붙은 바인딩을 건드렸다 — 발밑을 바꾼 것이다",
        )

        val refused = bindings.bind(R2, adapterVersionId, rev1, "op")
        assertTrue(
            refused is BindOutcome.Refused,
            "SUPERSEDED 개정판에 새로 붙일 수 있다: $refused",
        )
    }

    @Test
    fun `바인딩이 없는 기체는 파일 모드로 돈다`() {
        // §3.2의 "없을 때". 카나리 대상이 아닌 기체가 레지스트리를 못 읽어
        // 멈추면, 카나리가 전체 정지가 된다.
        val rev1 = activate(Fixtures.good(revision = 1))
        bind(R1, rev1)

        harness().use { harness ->
            assertTrue(!harness.pull(R2).changed, "바인딩이 없는데 뭔가를 집었다")
            val started = harness.client().start(R2, "t1", 1, "pick_place", gripForce80)
            assertTrue(started.hasHandle(), "파일 모드가 안 돈다: ${started.rejection}")
        }
    }

    // ── 준비물

    private fun statusOf(id: Long): String = PostgresSupport.queryOne(
        "SELECT status FROM profile_revision WHERE profile_revision_id = $id",
    ) { it.getString(1) }

    private fun boundRevisionId(robotId: String): Long = PostgresSupport.queryOne(
        "SELECT profile_revision_id FROM robot_binding " +
            "WHERE robot_id = '$robotId' AND unbound_at IS NULL",
    ) { it.getLong(1) }

    /** 진단 3번이 답할 것 — **그 기체의 최신 관측 개정판**. */
    private fun observedRevision(robotId: String): Int = PostgresSupport.queryOne(
        "SELECT (profile_ref->>'revision')::int FROM capability_epoch_log " +
            "WHERE robot_id = '$robotId' ORDER BY occurred_at DESC, epoch_log_id DESC LIMIT 1",
    ) { it.getInt(1) }

    private companion object {
        const val R1 = "r1"
        const val R2 = "r2"
        val CONTRACT_SEMVER: String = dev.picasso.contracts.wire.ContractIdentity.semver
    }
}
