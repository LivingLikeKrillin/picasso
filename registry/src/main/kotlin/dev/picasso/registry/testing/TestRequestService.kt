package dev.picasso.registry.testing

import dev.picasso.registry.binding.BindingService
import dev.picasso.registry.store.Db
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * 집어간 시험 요청 하나.
 *
 * @param claimedAt 집은 시각. **DB 에 적힌 값 그대로다** — 보고가 이 값을 돌려 실어야 받아 주므로, 메모리의
 *   `Instant`(나노초)를 내주면 DB(마이크로초)와 어긋나 모든 보고가 거절된다.
 */
data class ClaimedRequest(
    val requestId: Long,
    val profileRevisionId: Long,
    val documentJson: String,
    val claimedAt: Instant = Instant.EPOCH,
)

/** 시험 요청의 결과(picasso-ops P2·S1d 스펙 §5.1 의 1단계). */
sealed interface TestRequested {
    data class Created(val requestId: Long) : TestRequested

    /** 끝나지 않은 요청이 이미 있다. 그 요청이다. */
    data class Existing(val requestId: Long) : TestRequested

    data object UnknownRevision : TestRequested

    /** `DRAFT`·`REVOKED` 는 시험할 문서가 아니다. */
    data class NotTestable(val status: String) : TestRequested
}

/** 스위트 하나의 결과. [detail] 은 실행기가 낸 JSON 문자열이다. */
data class SuiteRun(val suite: String, val result: String, val detail: String?)

/** 결과 보고의 결과(스펙 §5.1 의 4단계). */
sealed interface ReportOutcome {
    /** 받았다. [status] 는 보고 뒤의 개정판 상태다. */
    data class Recorded(val status: String) : ReportOutcome

    data object UnknownRequest : ReportOutcome

    /** 집은 실행기나 집은 시각이 다르다 — 다시 집힌 요청에 죽은 실행기의 늦은 보고가 섞이지 않게 한다. */
    data object NotClaimer : ReportOutcome

    data object AlreadyCompleted : ReportOutcome

    data class BadResults(val detail: String) : ReportOutcome
}

/**
 * §8.4 ②의 시험 요청. **레지스트리는 적재만 하고 `harness`가 집어간다.**
 *
 * §3.2의 순환 회피 규칙 1이 이 모양을 강제한다 — *"`registry`는 `mimic`도
 * `harness`도 모른다."* 레지스트리가 하네스를 부르면 레지스트리가 하네스를
 * 알아야 하고, 그러면 시험 도구가 없으면 레지스트리가 안 뜨게 된다.
 *
 * ## 클레임은 만료된다
 *
 * 기본 15분. **만료가 없으면 `harness`가 죽었을 때 요청이 영구히 잡힌다** —
 * 그 개정판은 영영 `TESTED`가 못 되고, 활성화도 못 한다. 되살리는 유일한
 * 길이 사람이 DB를 고치는 것이 되면 그것은 운영 도구가 아니다.
 *
 * ## 끝난 요청은 다시 안 집힌다
 *
 * 보고([report])가 요청을 끝남으로 적는다(V16). 만료는 막힌 요청을 풀려는 것이지 끝난 요청을 되살리려는 것이
 * 아니다.
 */
class TestRequestService(
    private val db: Db,
    private val now: () -> Instant = Instant::now,
    private val claimFor: Duration = Duration.ofMinutes(15),
    private val bindings: BindingService = BindingService(db),
) {

    /**
     * 시험을 요청한다. @return 요청 id.
     *
     * [requestTest] 에 위임한다. SQL 경로를 하나로 두려는 것이다. 위임으로 바뀐 동작은 둘이다 — 열린 요청이 있으면
     * 새로 만들지 않고 그 id 를 돌려주고, 없는 개정판과 `DRAFT`·`REVOKED` 는 예외다.
     */
    fun request(profileRevisionId: Long, actor: String): Long = when (val outcome = requestTest(profileRevisionId, actor)) {
        is TestRequested.Created -> outcome.requestId
        is TestRequested.Existing -> outcome.requestId
        TestRequested.UnknownRevision -> throw IllegalArgumentException("없는 개정판이다: $profileRevisionId")
        is TestRequested.NotTestable -> throw IllegalStateException("시험할 수 없는 상태다: ${outcome.status}")
    }

    /**
     * 시험을 요청한다. **멱등이다** — 끝나지 않은 요청이 있으면 그것을 돌려준다.
     *
     * 개정판 행을 잠그고 진행한다. 같은 개정판에 동시에 온 두 요청이 둘 다 «열린 요청 없음» 을 보고 넣으면
     * V16 의 색인이 막아 500 이 되는데, 잠그면 둘째가 첫째의 요청을 본다.
     *
     * 감사 `TEST_REQUEST` 는 새로 만들 때만 남긴다. 멱등 응답은 일어난 일이 없다.
     */
    fun requestTest(profileRevisionId: Long, actor: String): TestRequested = db.transaction { c ->
        val status = c.prepareStatement(
            "SELECT status FROM profile_revision WHERE profile_revision_id = ? FOR UPDATE",
        ).use { s ->
            s.setLong(1, profileRevisionId)
            s.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        } ?: return@transaction TestRequested.UnknownRevision

        if (status in UNTESTABLE) return@transaction TestRequested.NotTestable(status)

        openRequest(c, profileRevisionId)?.let { return@transaction TestRequested.Existing(it) }

        val id = c.prepareStatement(
            "INSERT INTO revision_test_request (profile_revision_id, requested_by, requested_at) " +
                "VALUES (?, ?, ?) RETURNING request_id",
        ).use { s ->
            s.setLong(1, profileRevisionId); s.setString(2, actor)
            s.setObject(3, now().atOffset(ZoneOffset.UTC))
            s.executeQuery().use { rs -> check(rs.next()); rs.getLong(1) }
        }
        audit(c, actor, "TEST_REQUEST", "$profileRevisionId")
        TestRequested.Created(id)
    }

    /**
     * 하나를 집어간다. **아직 안 잡혔거나 클레임이 만료된, 끝나지 않은 것**만 대상이다.
     *
     * `FOR UPDATE SKIP LOCKED`를 쓰는 이유는 하네스가 여럿일 때 같은 요청을
     * 둘이 집어가지 않게 하려는 것이다 — 그러면 같은 개정판을 두 번 돌리고
     * 결과가 경합한다.
     */
    fun claim(worker: String): ClaimedRequest? = db.transaction { c ->
        val at = now()
        val row = c.prepareStatement(
            """
            SELECT r.request_id, r.profile_revision_id, p.document::text
            FROM revision_test_request r
            JOIN profile_revision p ON p.profile_revision_id = r.profile_revision_id
            WHERE r.completed_at IS NULL
              AND (r.claimed_by IS NULL OR r.claim_expires_at <= ?)
            ORDER BY r.request_id
            LIMIT 1
            FOR UPDATE OF r SKIP LOCKED
            """.trimIndent(),
        ).use { s ->
            s.setObject(1, at.atOffset(ZoneOffset.UTC))
            s.executeQuery().use { rs ->
                if (!rs.next()) null
                else ClaimedRequest(rs.getLong(1), rs.getLong(2), rs.getString(3))
            }
        } ?: return@transaction null

        val claimedAt = c.prepareStatement(
            "UPDATE revision_test_request " +
                "SET claimed_by = ?, claimed_at = ?, claim_expires_at = ? WHERE request_id = ? " +
                "RETURNING claimed_at",
        ).use { s ->
            s.setString(1, worker)
            s.setObject(2, at.atOffset(ZoneOffset.UTC))
            s.setObject(3, at.plus(claimFor).atOffset(ZoneOffset.UTC))
            s.setLong(4, row.requestId)
            s.executeQuery().use { rs -> check(rs.next()); rs.getTimestamp(1).toInstant() }
        }
        row.copy(claimedAt = claimedAt)
    }

    /**
     * 결과 셋을 받는다. 실행 3행, 요청의 «끝남», 승격이 **한 트랜잭션**이다.
     *
     * 집은 실행기와 집은 시각이 **둘 다** 같아야 받는다. 실행기 이름은 같은 이름으로 다시 뜰 수 있으므로 이름만으로는
     * 죽은 실행기의 늦은 보고를 못 가른다. 만료가 지났어도 다른 실행기가 다시 집기 전이면 받는다 — 결과는 실제로
     * 돌린 것이고, 만료는 막힌 요청을 풀려는 것이지 결과를 무효로 하려는 것이 아니다.
     */
    fun report(requestId: Long, worker: String, claimedAt: Instant, results: List<SuiteRun>): ReportOutcome {
        badResults(results)?.let { return ReportOutcome.BadResults(it) }

        return db.transaction { c ->
            val row = c.prepareStatement(
                "SELECT profile_revision_id, claimed_by, claimed_at, completed_at " +
                    "FROM revision_test_request WHERE request_id = ? FOR UPDATE",
            ).use { s ->
                s.setLong(1, requestId)
                s.executeQuery().use { rs ->
                    if (!rs.next()) null
                    else Claim(rs.getLong(1), rs.getString(2), rs.getTimestamp(3)?.toInstant(), rs.getTimestamp(4) != null)
                }
            } ?: return@transaction ReportOutcome.UnknownRequest

            if (row.completed) return@transaction ReportOutcome.AlreadyCompleted
            if (row.claimedBy != worker || row.claimedAt != claimedAt.truncatedTo(ChronoUnit.MICROS)) {
                return@transaction ReportOutcome.NotClaimer
            }

            results.forEach { run ->
                bindings.insertRun(c, row.profileRevisionId, run.suite, run.result, worker, requestId, run.detail)
            }
            c.prepareStatement("UPDATE revision_test_request SET completed_at = ? WHERE request_id = ?").use { s ->
                s.setObject(1, now().atOffset(ZoneOffset.UTC)); s.setLong(2, requestId)
                s.executeUpdate()
            }
            bindings.promoteIfAllPass(c, row.profileRevisionId, worker)

            val status = c.prepareStatement("SELECT status FROM profile_revision WHERE profile_revision_id = ?").use { s ->
                s.setLong(1, row.profileRevisionId)
                s.executeQuery().use { rs -> check(rs.next()); rs.getString(1) }
            }
            ReportOutcome.Recorded(status)
        }
    }

    /** 지금 잡혀 있는가. 시험이 만료를 관측하는 데 쓴다. */
    fun claimedBy(requestId: Long): String? = db.open().use { c ->
        c.prepareStatement(
            "SELECT claimed_by FROM revision_test_request WHERE request_id = ?",
        ).use { s ->
            s.setLong(1, requestId)
            s.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    private class Claim(val profileRevisionId: Long, val claimedBy: String?, val claimedAt: Instant?, val completed: Boolean)

    private fun openRequest(c: Connection, profileRevisionId: Long): Long? = c.prepareStatement(
        "SELECT request_id FROM revision_test_request WHERE profile_revision_id = ? AND completed_at IS NULL",
    ).use { s ->
        s.setLong(1, profileRevisionId)
        s.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
    }

    /** 세 스위트가 한 번씩, 결과는 PASS 나 FAIL 이어야 한다. 어긋나면 그 까닭이다. */
    private fun badResults(results: List<SuiteRun>): String? {
        val suites = results.map { it.suite }
        if (suites.sorted() != BindingService.SUITE_NAMES.sorted()) {
            return "스위트 셋이 한 번씩 와야 한다: 받은 것=$suites, 아는 것=${BindingService.SUITE_NAMES}"
        }
        results.firstOrNull { it.result !in RESULTS }?.let { return "모르는 결과다: ${it.suite}=${it.result}" }
        return null
    }

    private fun audit(c: Connection, actor: String, operation: String, subject: String) =
        c.prepareStatement(
            "INSERT INTO audit_log (operation, actor, subject) VALUES (?, ?, ?)",
        ).use {
            it.setString(1, operation); it.setString(2, actor); it.setString(3, subject)
            it.executeUpdate()
        }

    private companion object {
        val UNTESTABLE = setOf("DRAFT", "REVOKED")
        val RESULTS = setOf("PASS", "FAIL")
    }
}
