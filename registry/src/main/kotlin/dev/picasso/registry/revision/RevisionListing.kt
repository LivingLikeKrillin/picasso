package dev.picasso.registry.revision

import dev.picasso.registry.store.Db
import java.sql.ResultSet
import java.time.Instant

/** 스위트 하나의 최신 실행. [detail] 은 실행기가 낸 JSON 문자열이다. */
data class SuiteResult(val result: String, val ranAt: Instant, val ranBy: String, val detail: String?)

/** 개정판의 최신 시험 요청 1건. 끝났든 아니든 가장 늦게 들어온 요청이다. */
data class TestRequestRow(
    val requestId: Long,
    val requestedBy: String,
    val requestedAt: Instant,
    val claimedBy: String?,
    val claimedAt: Instant?,
    val claimExpiresAt: Instant?,
    val completedAt: Instant?,
)

/** 개정판 목록의 한 행. */
data class RevisionRow(
    val profileRevisionId: Long,
    val vendor: String,
    val model: String,
    val revision: Int,
    val status: RevisionStatus,
    val reasons: List<String>,
    val documentHash: String,
    val createdBy: String,
    val createdAt: Instant,
    val activatedBy: String?,
    val activatedAt: Instant?,
    /** 스위트 이름 → 최신 실행. 아직 돈 적이 없는 스위트는 빠진다. */
    val suites: Map<String, SuiteResult>,
    val latestRequest: TestRequestRow?,
)

/**
 * 개정판 목록(picasso-ops P2·S1d 스펙 §6.2). **화면이 개정판 하나의 처지를 한 번에 보게 한다** — 상태, 스위트마다의
 * 최신 결과, 시험 요청이 어디까지 왔는지. 따로 물으면 화면이 세 답을 조인하고, 그 사이에 실행기가 보고하면 세 반쪽이
 * 다른 시점을 말한다.
 */
class RevisionListing(private val db: Db) {

    fun list(): List<RevisionRow> = db.transaction { c ->
        val suites = mutableMapOf<Long, MutableMap<String, SuiteResult>>()
        c.prepareStatement(
            """
            SELECT DISTINCT ON (profile_revision_id, suite) profile_revision_id, suite, result, ran_at, ran_by, detail::text
            FROM revision_test_run ORDER BY profile_revision_id, suite, ran_at DESC, run_id DESC
            """.trimIndent(),
        ).use { s ->
            s.executeQuery().use { rs ->
                while (rs.next()) {
                    suites.getOrPut(rs.getLong(1)) { mutableMapOf() }[rs.getString(2)] =
                        SuiteResult(rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getString(5), rs.getString(6))
                }
            }
        }

        val requests = mutableMapOf<Long, TestRequestRow>()
        c.prepareStatement(
            """
            SELECT DISTINCT ON (profile_revision_id) profile_revision_id, request_id, requested_by, requested_at,
                   claimed_by, claimed_at, claim_expires_at, completed_at
            FROM revision_test_request ORDER BY profile_revision_id, requested_at DESC, request_id DESC
            """.trimIndent(),
        ).use { s ->
            s.executeQuery().use { rs ->
                while (rs.next()) {
                    requests[rs.getLong(1)] = TestRequestRow(
                        requestId = rs.getLong(2),
                        requestedBy = rs.getString(3),
                        requestedAt = rs.getTimestamp(4).toInstant(),
                        claimedBy = rs.getString(5),
                        claimedAt = rs.instant(6),
                        claimExpiresAt = rs.instant(7),
                        completedAt = rs.instant(8),
                    )
                }
            }
        }

        c.prepareStatement(
            """
            SELECT r.profile_revision_id, p.vendor, p.model, r.revision, r.status, r.validation_detail::text,
                   r.document_hash, r.created_by, r.created_at, r.activated_by, r.activated_at
            FROM profile_revision r JOIN capability_profile p ON p.profile_id = r.profile_id
            ORDER BY p.vendor, p.model, r.revision
            """.trimIndent(),
        ).use { s ->
            s.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val id = rs.getLong(1)
                        add(
                            RevisionRow(
                                profileRevisionId = id,
                                vendor = rs.getString(2),
                                model = rs.getString(3),
                                revision = rs.getInt(4),
                                status = RevisionStatus.valueOf(rs.getString(5)),
                                reasons = rs.getString(6)
                                    ?.let { MAPPER.readTree(it)["reasons"]?.map { r -> r.asText() } } ?: emptyList(),
                                documentHash = rs.getString(7),
                                createdBy = rs.getString(8),
                                createdAt = rs.getTimestamp(9).toInstant(),
                                activatedBy = rs.getString(10),
                                activatedAt = rs.instant(11),
                                suites = suites[id].orEmpty(),
                                latestRequest = requests[id],
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun ResultSet.instant(column: Int): Instant? = getTimestamp(column)?.toInstant()

    private companion object {
        val MAPPER = com.fasterxml.jackson.databind.ObjectMapper()
    }
}
