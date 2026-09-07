package dev.picasso.registry

import dev.picasso.gate.GateChecks
import dev.picasso.registry.revision.RevisionService
import dev.picasso.registry.revision.RevisionStatus
import dev.picasso.registry.revision.RevisionValidator
import dev.picasso.registry.store.Db
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §8.4 ①의 개정판 등록 — 완료 기준 15·17이 서는 바닥.
 *
 * **검증은 게이트가 한다**(§11.1). 여기서 확인하는 것은 *레지스트리가 게이트를
 * 부르는가*이지 게이트가 옳은가가 아니다 — 그것은 `gate`의 시험이 본다.
 */
class RevisionLifecycleTest {

    private lateinit var service: RevisionService

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        service = RevisionService(
            Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password),
            Fixtures.validator(),
        )
    }

    private fun status(id: Long): String = PostgresSupport.queryOne(
        "SELECT status FROM profile_revision WHERE profile_revision_id = $id",
    ) { it.getString(1) }

    private fun detail(id: Long): String? = PostgresSupport.queryOne(
        "SELECT validation_detail::text FROM profile_revision WHERE profile_revision_id = $id",
    ) { it.getString(1) }

    // ── 검증

    @Test
    fun `유효한 문서는 VALIDATED가 된다`() {
        val outcome = service.submit(Fixtures.good(), "operator")
        val stored = assertStored(outcome)

        assertEquals(RevisionStatus.VALIDATED, stored.status, "사유: ${stored.reasons}")
        assertEquals("VALIDATED", status(stored.profileRevisionId))
    }

    @Test
    fun `검증 실패는 DRAFT에 머물고 사유가 붙는다`() {
        // **거절하고 버리지 않는다.** 운영자가 무엇이 틀렸는지 보면서 고칠
        // 자리가 §8.4 ①의 "편집 후 재제출"이다.
        val outcome = service.submit(Fixtures.badErrorType(), "operator")
        val stored = assertStored(outcome)

        assertEquals(RevisionStatus.DRAFT, stored.status)
        assertTrue(stored.reasons.isNotEmpty(), "사유 없이 실패했다")
        assertTrue(detail(stored.profileRevisionId)!!.contains("검사"), "사유가 저장 안 됐다")
    }

    @Test
    fun `검증이 게이트와 같은 답을 낸다`() {
        // **두 번째 진실을 막는 시험이다.** 레지스트리가 자체 검증을 두면
        // CI가 통과시킨 프로파일을 여기서 거부하는 날이 오고, 그때 누구도
        // 어느 쪽이 옳은지 말할 수 없다.
        //
        // 게이트의 음성 케이스를 그대로 먹인다 — CI가 막는 것은 여기서도
        // 막혀야 한다.
        Fixtures.negativeProfiles().forEach { (case, json) ->
            PostgresSupport.reset()
            val fresh = RevisionService(
                Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password),
                Fixtures.validator(),
            )
            val stored = assertStored(fresh.submit(json, "operator"))
            assertEquals(
                RevisionStatus.DRAFT, stored.status,
                "$case: CI가 막는 것을 레지스트리가 통과시켰다",
            )
        }
    }

    @Test
    fun `음성 케이스가 실제로 있다`() {
        // 위 시험의 전제. 케이스가 0개면 반복문이 안 돌고 자명하게 참이다.
        val cases = Fixtures.negativeProfiles()
        assertTrue(cases.isNotEmpty(), "게이트 음성 케이스를 못 찾았다")
        assertTrue(
            cases.map { it.first }.distinct().size >= 3,
            "케이스가 너무 적어 파리티가 우연일 수 있다: ${cases.map { it.first }.distinct()}",
        )
    }

    @Test
    fun `필수 자원이 없으면 검증 미완이다`() {
        // **널이어야 자원 부재다.** 빈 문자열은 게이트에게 "있음"으로 보여서
        // (`available()`이 `!= null`로 판정한다) 이 가드가 한 번도 발화하지
        // 않았다 — 실측으로 `required`를 없애는 주입이 안 잡혔다.
        listOf(
            "스키마 없음" to RevisionValidator(null, Fixtures.descriptor()),
            "디스크립터 없음" to RevisionValidator(Fixtures.schema(), null),
            "둘 다 없음" to RevisionValidator(null, null),
        ).forEach { (label, validator) ->
            PostgresSupport.reset()
            val blind = RevisionService(
                Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password),
                validator,
            )
            val stored = assertStored(blind.submit(Fixtures.good(), "operator"))

            assertEquals(RevisionStatus.DRAFT, stored.status, "$label: 안 보고 유효하다고 했다")
            assertTrue(
                stored.reasons.any { it.startsWith("검증 미완") },
                "$label: 미완이 아니라 '프로파일이 틀렸다'로 알렸다 — ${stored.reasons}",
            )
        }
    }

    @Test
    fun `요구 검사가 안 돌면 검증 미완이다`() {
        // §8.4 ①은 셋(스키마 3·교차검증 4·어휘 파괴 6)을 요구한다. 하나라도
        // 안 돌았는데 "유효함"으로 기록되면 그 개정판은 **검증된 적 없이**
        // 활성화될 수 있다.
        //
        // 자원이 다 있는 정상 경로에서는 셋이 다 돌아 이 가드가 발화하지
        // 않는다. **검사 목록을 갈아 끼워야 발화한다** — 그래서 주입 가능하게
        // 열어 뒀다.
        val partial = RevisionValidator(
            Fixtures.schema(),
            Fixtures.descriptor(),
            checks = GateChecks.all().filterNot { it.id == "6" },
        )
        val service = RevisionService(
            Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password),
            partial,
        )
        val stored = assertStored(service.submit(Fixtures.good(), "operator"))

        assertEquals(RevisionStatus.DRAFT, stored.status, "검사 6 없이 유효하다고 했다")
        assertTrue(
            stored.reasons.any { it.startsWith("검증 미완") && it.contains("6") },
            "어느 검사가 안 돌았는지 안 알려준다: ${stored.reasons}",
        )
    }

    @Test
    fun `셋이 다 돌면 미완이 아니다`() {
        // 위 시험의 짝. 언제나 미완이라고 하는 구현이면 그것도 통과한다.
        val stored = assertStored(service.submit(Fixtures.good(), "operator"))
        assertTrue(
            stored.reasons.none { it.startsWith("검증 미완") },
            "정상 경로인데 미완이라고 한다: ${stored.reasons}",
        )
    }

    // ── 개정판 번호

    @Test
    fun `번호는 문서가 선언한다`() {
        // 레지스트리가 채번하면 문서의 좌표와 DB의 좌표가 둘이 된다.
        val stored = assertStored(service.submit(Fixtures.good(revision = 7), "operator"))
        assertEquals(7, stored.revision)
        assertEquals(
            7,
            PostgresSupport.queryOne("SELECT revision FROM profile_revision") { it.getInt(1) },
        )
    }

    @Test
    fun `번호가 거꾸로 가면 거부한다`() {
        assertStored(service.submit(Fixtures.good(revision = 5), "operator"))

        val back = service.submit(Fixtures.good(revision = 4), "operator")
        assertTrue(back is dev.picasso.registry.revision.SubmitOutcome.Rejected, "$back")
        assertEquals(
            1,
            PostgresSupport.queryOne("SELECT count(*) FROM profile_revision") { it.getInt(1) },
            "거부해 놓고 저장했다",
        )
    }

    @Test
    fun `같은 번호를 두 번 올리면 거부한다`() {
        assertStored(service.submit(Fixtures.good(revision = 3), "operator"))
        val again = service.submit(Fixtures.good(revision = 3), "operator")
        assertTrue(again is dev.picasso.registry.revision.SubmitOutcome.Rejected, "$again")
    }

    @Test
    fun `다른 기종은 서로의 번호를 막지 않는다`() {
        // 단조 증가는 `profile_id` 안에서만이다. 전역이면 기종 하나가 다른
        // 기종의 개정판 번호를 못 쓰게 만든다.
        assertStored(service.submit(Fixtures.good(revision = 9), "operator"))
        val other = service.submit(Fixtures.good(revision = 1, model = "other"), "operator")
        assertTrue(other is dev.picasso.registry.revision.SubmitOutcome.Stored, "$other")
    }

    // ── 감사

    @Test
    fun `조작 하나가 감사 로그 한 줄이다`() {
        // §8.5 — 조작 단위가 테이블 행이 아니라 의도여야 한다.
        service.submit(Fixtures.good(), "alice")

        val rows = PostgresSupport.queryOne("SELECT count(*) FROM audit_log") { it.getInt(1) }
        assertEquals(1, rows, "조작 하나에 로그가 $rows 줄이다")

        val actor = PostgresSupport.queryOne("SELECT actor FROM audit_log") { it.getString(1) }
        assertEquals("alice", actor)
    }

    @Test
    fun `거부된 조작은 로그를 안 남긴다`() {
        // 일어나지 않은 일이 기록에 남으면 감사가 거짓말을 한다.
        service.submit(Fixtures.good(revision = 5), "alice")
        service.submit(Fixtures.good(revision = 4), "bob")

        assertEquals(
            1,
            PostgresSupport.queryOne("SELECT count(*) FROM audit_log") { it.getInt(1) },
            "거부된 조작이 로그에 남았다",
        )
    }

    @Test
    fun `실패한 검증도 조작으로 기록된다`() {
        // 검증 실패는 거부가 아니라 **저장된 결과**다(DRAFT). 기록이 없으면
        // "누가 언제 이 초안을 올렸나"에 답할 수 없다.
        service.submit(Fixtures.badErrorType(), "carol")
        assertEquals(
            1,
            PostgresSupport.queryOne("SELECT count(*) FROM audit_log") { it.getInt(1) },
        )
    }

    private fun assertStored(outcome: dev.picasso.registry.revision.SubmitOutcome):
        dev.picasso.registry.revision.SubmitOutcome.Stored {
        assertTrue(
            outcome is dev.picasso.registry.revision.SubmitOutcome.Stored,
            "저장되지 않았다: $outcome",
        )
        return outcome
    }
}
