package dev.picasso.registry.testing

import dev.picasso.registry.store.Db
import java.sql.Connection
import java.time.Duration
import java.time.Instant

/** 집어간 시험 요청 하나. */
data class ClaimedRequest(
    val requestId: Long,
    val profileRevisionId: Long,
    val documentJson: String,
)

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
 */
class TestRequestService(
    private val db: Db,
    private val now: () -> Instant = Instant::now,
    private val claimFor: Duration = Duration.ofMinutes(15),
) {

    /** 시험을 요청한다. @return 요청 id. */
    fun request(profileRevisionId: Long, actor: String): Long = db.transaction { c ->
        val id = c.prepareStatement(
            "INSERT INTO revision_test_request (profile_revision_id, requested_by) " +
                "VALUES (?, ?) RETURNING request_id",
        ).use { s ->
            s.setLong(1, profileRevisionId); s.setString(2, actor)
            s.executeQuery().use { rs -> check(rs.next()); rs.getLong(1) }
        }
        audit(c, actor, "TEST_REQUEST", "$profileRevisionId")
        id
    }

    /**
     * 하나를 집어간다. **아직 안 잡혔거나 클레임이 만료된 것**만 대상이다.
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
            WHERE r.claimed_by IS NULL OR r.claim_expires_at <= ?
            ORDER BY r.request_id
            LIMIT 1
            FOR UPDATE OF r SKIP LOCKED
            """.trimIndent(),
        ).use { s ->
            s.setObject(1, at.atOffset(java.time.ZoneOffset.UTC))
            s.executeQuery().use { rs ->
                if (!rs.next()) null
                else ClaimedRequest(rs.getLong(1), rs.getLong(2), rs.getString(3))
            }
        } ?: return@transaction null

        c.prepareStatement(
            "UPDATE revision_test_request " +
                "SET claimed_by = ?, claimed_at = ?, claim_expires_at = ? WHERE request_id = ?",
        ).use { s ->
            s.setString(1, worker)
            s.setObject(2, at.atOffset(java.time.ZoneOffset.UTC))
            s.setObject(3, at.plus(claimFor).atOffset(java.time.ZoneOffset.UTC))
            s.setLong(4, row.requestId)
            s.executeUpdate()
        }
        row
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

    private fun audit(c: Connection, actor: String, operation: String, subject: String) =
        c.prepareStatement(
            "INSERT INTO audit_log (operation, actor, subject) VALUES (?, ?, ?)",
        ).use {
            it.setString(1, operation); it.setString(2, actor); it.setString(3, subject)
            it.executeUpdate()
        }
}
