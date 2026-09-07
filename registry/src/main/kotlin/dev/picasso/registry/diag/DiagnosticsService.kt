package dev.picasso.registry.diag

import dev.picasso.gate.CheckResult
import dev.picasso.gate.GateRunner
import dev.picasso.gate.Severity
import dev.picasso.gate.checks.Check06Vocabulary
import dev.picasso.gate.input.GateInput
import dev.picasso.profile.ProfileDocument
import dev.picasso.profile.ProfileKey
import dev.picasso.registry.store.Db

/**
 * 목록형 진단의 기본 상한.
 *
 * **HTTP 표면과 도메인이 같은 값을 봐야 한다.** 컨트롤러가 자기 기본값을
 * 따로 적으면 `curl /diag/epochs`와 `diagnostics.epochs(...)`가 다른 개수를
 * 돌려주고, 시험은 도메인 쪽만 본다.
 */
object DiagAnswerLimits {
    const val DEFAULT_TEXT = "100"
    const val DEFAULT = 100
}

/** 진단 1번의 한 줄 — 이 기체는 지금(또는 그때) 무엇으로 도는가. */
data class BindingRow(
    val robotId: String,
    val siteId: String,
    val vendor: String,
    val model: String,
    val profileRevisionId: Long,
    val revision: Int,
    val adapterName: String,
    val adapterVersion: String,
    val conformanceStatus: String,
    val active: Boolean,
)

/**
 * 진단 1번의 답. **두 질문에 한 번에 답한다**(§8.5).
 *
 * 쪼개면 운영자가 두 번 조회해 머릿속에서 조인한다. 그리고 그 조인은
 * 사람이 하는 순간 틀린다 — 두 조회 사이에 바인딩이 바뀌면 화면의 두
 * 반쪽이 서로 다른 시점을 말한다.
 */
data class BindingsAnswer(
    val rows: List<BindingRow>,
    /** `profile_revision_id` → 지금 그것으로 도는 기체 수. **활성만 센다.** */
    val robotsPerRevision: Map<Long, Int>,
)

/** 진단 2번의 답. */
data class DiffAnswer(
    val from: Int,
    val to: Int,
    val findings: List<DiffFinding>,
    /**
     * **선택 필드는 아직 안 본다.** `profile_optional_field`가 미룬 표라
     * 빈 목록으로 내면 "차이가 없다"는 거짓말이 된다 — 그 거짓말 위에서
     * 운영자가 축소를 승인한다.
     */
    val optionalFields: String = "not_tracked",
)

data class DiffFinding(val severity: String, val message: String, val location: String?)

/** 진단 3번의 한 줄. */
data class EpochRow(
    val epoch: Long,
    val cause: String,
    val profileId: String,
    val revision: Int,
    val occurredAt: String,
)

/** 진단 4번의 한 줄. */
data class RejectionRow(
    val rejectionId: Long,
    val robotId: String,
    val clientId: String,
    val reasonCode: String,
    val requirement: String,
    val detail: String?,
    val at: String,
)

/**
 * §8.5의 진단 여섯 중 **1~4**. 5·6은 원장과 변경 계획이 서는 3b다.
 *
 * **프레임워크를 모른다**(§3.4). 여기가 답하는 것은 데이터이고, 그것을
 * HTTP로 내는 것은 `web/`의 일이다. 섞으면 진단의 거동을 시험하려고
 * 서버를 띄워야 하고, 그러면 시험이 느려져 결함 주입이 줄어든다.
 */
class DiagnosticsService(
    private val db: Db,
    /**
     * diff가 부르는 파괴 검사. **게이트의 그 검사를 그대로 쓴다.**
     *
     * 진단이 자기 분류를 새로 만들면 **CI가 막는 것과 화면이 보여주는 것이
     * 갈린다** — 게이트는 축소라 부르는데 화면은 확장이라 부르는 날이 오고,
     * 그날 운영자는 화면을 믿는다.
     */
    private val vocabulary: dev.picasso.gate.GateCheck = Check06Vocabulary(),
) {

    // ── 진단 1: GET /diag/bindings

    /**
     * @param site 사이트로 좁힌다. null이면 전부.
     * @param includeHistory 해제된 바인딩도 낸다. **카탈로그와 다른 점이
     *   여기다** — 카탈로그는 "지금 무엇으로 도는가"에 답하고(§9.1), 진단은
     *   "무엇이었는가"에도 답해야 한다.
     */
    fun bindings(site: String? = null, includeHistory: Boolean = false): BindingsAnswer {
        val rows = db.open().use { c ->
            c.prepareStatement(
                """
                SELECT r.robot_id, r.site_id, cp.vendor, cp.model,
                       pr.profile_revision_id, pr.revision,
                       a.name, av.version, av.conformance_status,
                       (b.unbound_at IS NULL) AS active
                FROM robot_binding b
                JOIN robot r               ON r.robot_id = b.robot_id
                JOIN profile_revision pr   ON pr.profile_revision_id = b.profile_revision_id
                JOIN capability_profile cp ON cp.profile_id = pr.profile_id
                JOIN adapter_version av    ON av.adapter_version_id = b.adapter_version_id
                JOIN adapter a             ON a.adapter_id = av.adapter_id
                WHERE (? OR b.unbound_at IS NULL)
                  AND (? IS NULL OR r.site_id = ?)
                ORDER BY r.robot_id, b.bound_at DESC, b.robot_binding_id DESC
                """.trimIndent(),
            ).use { s ->
                s.setBoolean(1, includeHistory)
                s.setString(2, site); s.setString(3, site)
                s.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                BindingRow(
                                    robotId = rs.getString(1),
                                    siteId = rs.getString(2),
                                    vendor = rs.getString(3),
                                    model = rs.getString(4),
                                    profileRevisionId = rs.getLong(5),
                                    revision = rs.getInt(6),
                                    adapterName = rs.getString(7),
                                    adapterVersion = rs.getString(8),
                                    conformanceStatus = rs.getString(9),
                                    active = rs.getBoolean(10),
                                ),
                            )
                        }
                    }
                }
            }
        }

        // **활성만 센다.** 이력을 함께 세면 "이 개정판을 쓰는 기체 수"가
        // 실제 대수를 넘고, 축소 판단이 그 숫자를 본다.
        val perRevision = rows.filter { it.active }
            .groupingBy { it.profileRevisionId }.eachCount()

        return BindingsAnswer(rows, perRevision)
    }

    // ── 진단 2: GET /diag/diff?from=&to=

    /**
     * 두 개정판의 능력 diff. **게이트 6번을 그대로 부른다.**
     *
     * @throws IllegalArgumentException 두 개정판이 같은 기종이 아닐 때.
     *   서로 다른 기종을 diff하면 6번은 "기준선 없음 → 신규"로 읽어 **아무
     *   차이도 없다고 답한다.** 그것이 가장 위험한 거짓말이다.
     */
    fun diff(fromRevisionId: Long, toRevisionId: Long): DiffAnswer {
        val from = documentOf(fromRevisionId)
        val to = documentOf(toRevisionId)

        val fromDoc = ProfileDocument.parse("revision-$fromRevisionId", from.second).getOrThrow()
        val toDoc = ProfileDocument.parse("revision-$toRevisionId", to.second).getOrThrow()

        require(fromDoc.vendor == toDoc.vendor && fromDoc.model == toDoc.model) {
            "다른 기종끼리는 diff하지 않는다: " +
                "${fromDoc.vendor}/${fromDoc.model} vs ${toDoc.vendor}/${toDoc.model}"
        }

        val report = GateRunner(listOf(vocabulary)).run(
            GateInput(
                profiles = listOf(toDoc),
                baseline = mapOf(ProfileKey(fromDoc.vendor, fromDoc.model) to from.second),
            ),
        )

        val findings = report.results.flatMap { result ->
            when (result) {
                is CheckResult.Passed -> result.findings
                is CheckResult.Failed -> result.findings
                // **건너뜀을 "차이 없음"으로 접지 않는다.** 자원이 없어 못
                // 본 것을 깨끗하다고 내면 화면이 조용히 거짓말한다.
                is CheckResult.Skipped -> return DiffAnswer(
                    from = fromDoc.revision,
                    to = toDoc.revision,
                    findings = listOf(
                        DiffFinding(
                            Severity.ERROR.name,
                            "diff를 내지 못했다: ${result.reason}",
                            null,
                        ),
                    ),
                )
            }
        }

        return DiffAnswer(
            from = fromDoc.revision,
            to = toDoc.revision,
            findings = findings.map { DiffFinding(it.severity.name, it.message, it.location) },
        )
    }

    // ── 진단 3: GET /diag/epochs?robot_id=

    /** 세대 이력. **최신이 위다** — 운영자가 찾는 것은 방금 무슨 일이 났나다. */
    fun epochs(robotId: String, limit: Int = DiagAnswerLimits.DEFAULT): List<EpochRow> = db.open().use { c ->
        c.prepareStatement(
            """
            SELECT epoch, cause,
                   profile_ref->>'profile_id', (profile_ref->>'revision')::int,
                   occurred_at
            FROM capability_epoch_log
            WHERE robot_id = ?
            ORDER BY occurred_at DESC, epoch_log_id DESC
            LIMIT ?
            """.trimIndent(),
        ).use { s ->
            s.setString(1, robotId); s.setInt(2, limit)
            s.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            EpochRow(
                                epoch = rs.getLong(1),
                                cause = rs.getString(2),
                                profileId = rs.getString(3),
                                revision = rs.getInt(4),
                                occurredAt = rs.getTimestamp(5).toInstant().toString(),
                            ),
                        )
                    }
                }
            }
        }
    }

    // ── 진단 4: GET /diag/rejections

    /**
     * 어떤 클라이언트가 어떤 요구로 거절당했는가. 완료 기준 13의 보고 절반.
     *
     * @param reasonCode 코드로 좁힌다. null이면 전부. **좁히는 것이
     *   중요한 이유는 다섯 코드의 대응이 서로 다르기 때문이다**(§4.3) —
     *   `SKILL_ABSENT`는 요구 집합이 틀린 것이고 `LIMIT_EXCEEDED`는
     *   값이 큰 것이다.
     */
    fun rejections(reasonCode: String? = null, limit: Int = DiagAnswerLimits.DEFAULT): List<RejectionRow> =
        db.open().use { c ->
            c.prepareStatement(
                """
                SELECT rejection_id, robot_id, client_id, reason_code,
                       requirement::text, detail::text, at
                FROM handshake_rejection
                WHERE (? IS NULL OR reason_code = ?)
                ORDER BY at DESC, rejection_id DESC
                LIMIT ?
                """.trimIndent(),
            ).use { s ->
                s.setString(1, reasonCode); s.setString(2, reasonCode); s.setInt(3, limit)
                s.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                RejectionRow(
                                    rejectionId = rs.getLong(1),
                                    robotId = rs.getString(2),
                                    clientId = rs.getString(3),
                                    reasonCode = rs.getString(4),
                                    requirement = rs.getString(5),
                                    detail = rs.getString(6),
                                    at = rs.getTimestamp(7).toInstant().toString(),
                                ),
                            )
                        }
                    }
                }
            }
        }

    /** @return (revision, document) */
    private fun documentOf(profileRevisionId: Long): Pair<Int, String> = db.open().use { c ->
        c.prepareStatement(
            "SELECT revision, document::text FROM profile_revision WHERE profile_revision_id = ?",
        ).use { s ->
            s.setLong(1, profileRevisionId)
            s.executeQuery().use { rs ->
                require(rs.next()) { "없는 개정판이다: $profileRevisionId" }
                rs.getInt(1) to rs.getString(2)
            }
        }
    }
}
