package dev.picasso.registry.diag

import dev.picasso.gate.CheckResult
import dev.picasso.gate.GateRunner
import dev.picasso.gate.Severity
import dev.picasso.gate.checks.Check06Vocabulary
import dev.picasso.gate.input.GateInput
import dev.picasso.profile.ProfileDocument
import dev.picasso.profile.ProfileKey
import dev.picasso.registry.binding.SiteNameRegistration
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
    /**
     * 관측선 상태 — `REPORTING` | `SILENT` | `HIBERNATING` | `DISCONNECTED` | `NEVER`.
     *
     * `NEVER`가 §15.47이 말한 그 상태다: 바인딩은 됐는데 한 번도 보고한 적이
     * 없는 기체. §9.3의 두 조회가 그것 때문에 막히며, **여기 나오지 않으면
     * 운영자는 축소를 시도할 때에야 안다.**
     */
    val liveness: String,

    /**
     * 이 바인딩이 **사이트의 이름들을 아는가**(ADR 35의 결정 3).
     *
     * `NOT_REQUIRED` / `UNREGISTERED` / `REGISTERED`. §9.7 ④의
     * `conformance_status`, §15.47의 `liveness`와 같은 자리다 — **막지 않고
     * 보이게 한다.** 등록 대상은 프로파일이 선언한 스킬의 시맨틱 파라미터에서
     * 유도하므로 기종마다 손으로 적는 목록이 없다.
     */
    val siteNames: String,

    /** 무엇을 알아야 하는가. 비어 있으면 [siteNames]가 `NOT_REQUIRED`다. */
    val siteNameKeys: List<String>,
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
    /** 관측선 신선도 판정용. 시험이 고정한다. */
    private val now: () -> java.time.Instant = java.time.Instant::now,
) {

    // ── 진단 1: GET /diag/bindings

    /**
     * @param site 사이트로 좁힌다. null이면 전부.
     * @param includeHistory 해제된 바인딩도 낸다. **카탈로그와 다른 점이
     *   여기다** — 카탈로그는 "지금 무엇으로 도는가"에 답하고(§9.1), 진단은
     *   "무엇이었는가"에도 답해야 한다.
     */
    /**
     * 관측선 판정 한 낱말.
     *
     * **§15.47이 인정한 것을 보이게 하는 자리다** — 하트비트를 안 보내는
     * 어댑터를 붙이면 그 사이트의 축소가 통째로 멈추는데, 지금까지 그 사실이
     * 축소를 시도해야만 드러났다. 여기 나오면 붙이는 순간 보인다.
     *
     * §9.7 ④의 `UNTESTED`와 같은 판단이다 — *"우리는 실물 검증을 아직 안
     * 했다가 화면에 보여야 정직하다."*
     */
    private fun liveness(at: java.time.Instant?, state: String?): String = when {
        at == null -> "NEVER"
        state in DEAD_CONNECTION -> "DISCONNECTED"
        java.time.Duration.between(at, now()) > LIVENESS_WINDOW ->
            if (state == HIBERNATING) "HIBERNATING" else "SILENT"
        else -> "REPORTING"
    }

    fun bindings(site: String? = null, includeHistory: Boolean = false): BindingsAnswer {
        // **사이트 이름 상태는 SQL 이 아니라 유도로 채운다.** 질의에 섞으면
        // 유도 규칙(선언한 스킬 × 계약의 시맨틱 표시)이 SQL 안으로 흩어지고,
        // 그러면 규칙을 고칠 때 두 곳을 봐야 한다.
        val siteNames = SiteNameRegistration(db)
        val rows = db.open().use { c ->
            c.prepareStatement(
                """
                SELECT r.robot_id, r.site_id, cp.vendor, cp.model,
                       pr.profile_revision_id, pr.revision,
                       a.name, av.version, av.conformance_status,
                       (b.unbound_at IS NULL) AS active,
                       l.last_reported_at, l.connection_state
                FROM robot_binding b
                JOIN robot r               ON r.robot_id = b.robot_id
                JOIN profile_revision pr   ON pr.profile_revision_id = b.profile_revision_id
                JOIN capability_profile cp ON cp.profile_id = pr.profile_id
                JOIN adapter_version av    ON av.adapter_version_id = b.adapter_version_id
                JOIN adapter a             ON a.adapter_id = av.adapter_id
                LEFT JOIN robot_liveness l ON l.robot_id = r.robot_id
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
                                    liveness = liveness(
                                        rs.getTimestamp(11)?.toInstant(),
                                        rs.getString(12),
                                    ),
                                    // 아래에서 유도로 채운다. 여기서 비워 두는
                                    // 것이 아니라 채운 뒤 갈아 끼운다 —
                                    // 빈 문자열이 화면에 새어 나가면 그것이
                                    // "모른다"로 읽힌다.
                                    siteNames = "",
                                    siteNameKeys = emptyList(),
                                ),
                            )
                        }
                    }
                }
            }
        }

        // **유도는 행마다 한 번씩이다.** 기체 수만큼 질의가 늘지만 진단은
        // 운영자가 보는 화면이고, 유도 규칙을 한 자리에 두는 값이 그보다 크다.
        val enriched = rows.map { row ->
            val keys = siteNames.required(row.robotId)
            row.copy(
                siteNames = siteNames.statusOf(row.robotId).name,
                siteNameKeys = keys.sorted(),
            )
        }

        // **활성만 센다.** 이력을 함께 세면 "이 개정판을 쓰는 기체 수"가
        // 실제 대수를 넘고, 축소 판단이 그 숫자를 본다.
        val perRevision = enriched.filter { it.active }
            .groupingBy { it.profileRevisionId }.eachCount()

        return BindingsAnswer(enriched, perRevision)
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
    /**
     * 프로파일이 전제한 펌웨어와 기체가 보고한 펌웨어를 대조한다.
     *
     * **판정이 세 값인 것이 요점이다.** 일치·불일치로 접으면 신원 질의가
     * 아예 없는 기종(§2.3의 Unitree)이 언제나 불일치로 보이고, 그러면
     * 진짜 불일치가 그 소음에 묻힌다.
     *
     * **막지는 않는다.** 어느 정도 차이까지 허용하는가는 정책이고 정책
     * 저장소가 비목표다(§1.3). 여기서는 보이게만 한다.
     */
    fun software(site: String? = null): List<SoftwareRow> = db.open().use { c ->
        val sql = buildString {
            append(
                """
                SELECT r.robot_id,
                       pr.document->'derived_from'->>'software_version',
                       l.robot_software
                FROM robot_binding b
                JOIN robot r ON r.robot_id = b.robot_id
                JOIN profile_revision pr ON pr.profile_revision_id = b.profile_revision_id
                LEFT JOIN robot_liveness l ON l.robot_id = r.robot_id
                WHERE b.unbound_at IS NULL
                """.trimIndent(),
            )
            if (site != null) append(" AND r.site_id = ?")
            append(" ORDER BY r.robot_id")
        }
        c.prepareStatement(sql).use { s ->
            if (site != null) s.setString(1, site)
            s.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val declared = rs.getString(2)
                        val reported = rs.getString(3)
                        add(
                            SoftwareRow(
                                robotId = rs.getString(1),
                                declared = declared,
                                reported = reported,
                                verdict = when {
                                    reported == null -> "UNREPORTED"
                                    declared == reported -> "MATCH"
                                    else -> "MISMATCH"
                                },
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * 오래 비종착으로 남은 태스크. **아무도 정리하지 않는 태스크가 그 스킬의
     * 축소를 영원히 막는다**(§9.3의 드레인).
     *
     * `needsHuman`이 따로 있는 이유는 `RETRIABLE`·`NEEDS_INTERVENTION`이
     * **기다린다고 풀리지 않기** 때문이다. 둘의 탈출구는 `RetryTask`나
     * `CancelTask`이고, 재시도 정책의 주인인 미션 계층이 비목표이므로(§1.3)
     * 지금은 사람이 걸어야 한다.
     */
    fun stalled(
        olderThanHours: Long = STALL_THRESHOLD_HOURS,
    ): List<StalledRow> = db.open().use { c ->
        c.prepareStatement(
            """
            SELECT t.task_id, t.robot_id, s.name, s.major, t.state, t.updated_at
            FROM task t
            JOIN skill_type s ON s.skill_type_id = t.skill_type_id
            WHERE NOT t.terminal
              AND t.updated_at < now() - make_interval(hours => ?::int)
            ORDER BY t.updated_at
            """.trimIndent(),
        ).use { s ->
            s.setInt(1, olderThanHours.toInt())
            s.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val state = rs.getString(5)
                        add(
                            StalledRow(
                                taskId = rs.getString(1),
                                robotId = rs.getString(2),
                                skillType = rs.getString(3),
                                major = rs.getInt(4),
                                state = state,
                                updatedAt = rs.getTimestamp(6).toInstant().toString(),
                                needsHuman = state in NEEDS_HUMAN,
                            ),
                        )
                    }
                }
            }
        }
    }

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

    private companion object {
        /**
         * **상수다.** 정책 저장소가 비목표이므로(§1.3) 설정 가능하게 만들지
         * 않는다. 이 값이 정책이 되는 날 그것을 담을 자리부터 만들어야 한다.
         */
        const val STALL_THRESHOLD_HOURS: Long = 24

        /** 기다린다고 안 풀리는 둘(§4.4). */
        val NEEDS_HUMAN = setOf("TASK_STATE_RETRIABLE", "TASK_STATE_NEEDS_INTERVENTION")

        /**
         * `RobotObservability`의 기본 창과 **같은 상수를 쓴다.** 두 곳이
         * 갈라지면 진단이 "REPORTING"이라 적힌 기체 때문에 축소가 막히고,
         * 운영자는 원장이 아니라 엉뚱한 것을 뒤진다.
         */
        val LIVENESS_WINDOW: java.time.Duration =
            dev.picasso.registry.ledger.RobotObservability.DEFAULT_FRESHNESS

        const val HIBERNATING = "CONNECTION_STATE_HIBERNATING"

        val DEAD_CONNECTION = setOf(
            "CONNECTION_STATE_OFFLINE",
            "CONNECTION_STATE_CONNECTION_BROKEN",
            "CONNECTION_STATE_UNSPECIFIED",
        )
    }
}

/** 진단 7번의 한 줄. `declared`는 프로파일 선언, `reported`는 기체가 말한 것. */
data class SoftwareRow(
    val robotId: String,
    val declared: String?,
    val reported: String?,
    /** MATCH | MISMATCH | UNREPORTED */
    val verdict: String,
)

/** 진단 8번의 한 줄. */
data class StalledRow(
    val taskId: String,
    val robotId: String,
    val skillType: String,
    val major: Int,
    val state: String,
    val updatedAt: String,
    /** 참이면 기다려도 안 풀린다 — 사람이 RetryTask 나 CancelTask 를 걸어야 한다. */
    val needsHuman: Boolean,
)
