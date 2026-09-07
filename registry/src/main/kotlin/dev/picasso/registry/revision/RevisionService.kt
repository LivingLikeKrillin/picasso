package dev.picasso.registry.revision

import dev.picasso.profile.ProfileDocument
import dev.picasso.registry.store.Db
import java.security.MessageDigest
import java.sql.Connection

/** §8.3의 `profile_revision.status`. */
enum class RevisionStatus { DRAFT, VALIDATED, TESTED, ACTIVE, SUPERSEDED, REVOKED }

/** 제출의 결과. */
sealed interface SubmitOutcome {
    /** @param status 검증을 지났으면 `VALIDATED`, 아니면 `DRAFT`에 머문다. */
    data class Stored(
        val profileRevisionId: Long,
        val revision: Int,
        val status: RevisionStatus,
        val reasons: List<String> = emptyList(),
    ) : SubmitOutcome

    /** 문서가 아예 개정판이 될 수 없다 — 번호 규칙 위반 등. */
    data class Rejected(val detail: String) : SubmitOutcome
}

/**
 * §8.4 ①의 개정판 등록.
 *
 * **검증에 실패해도 저장한다.** `DRAFT`에 머무르며 사유가 붙고 편집 후
 * 재제출할 수 있다 — 거절하고 버리면 운영자가 무엇이 틀렸는지 보면서 고칠
 * 자리가 없어진다.
 *
 * **개정판 번호를 채번하지 않는다.** 문서가 스스로 선언한 값을 그대로
 * 쓴다(§7.2의 기종 좌표). 레지스트리가 매기면 문서의 좌표와 DB의 좌표가
 * 둘이 되고, 그때 `profile_ref`가 무엇을 가리키는지 알 수 없다. 강제하는
 * 것은 **`profile_id` 안에서 단조 증가**뿐이다.
 */
class RevisionService(
    private val db: Db,
    private val validator: RevisionValidator,
) {
    fun submit(documentJson: String, actor: String): SubmitOutcome = db.transaction { c ->
        val parsed = ProfileDocument.parse("submitted", documentJson).getOrNull()
            ?: return@transaction SubmitOutcome.Rejected("프로파일을 읽을 수 없다")

        val profileId = upsertProfile(c, parsed.vendor, parsed.model)
        val highest = highestRevision(c, profileId)

        if (highest != null && parsed.revision <= highest) {
            // **거꾸로 가거나 같은 번호는 거부한다.** 허용하면 같은 좌표가
            // 두 문서를 가리키고, `profile_ref`로 재현할 수 없게 된다.
            return@transaction SubmitOutcome.Rejected(
                "개정판 번호가 단조 증가하지 않는다: 받은 값=${parsed.revision}, 현재 최대=$highest",
            )
        }

        val baseline = activeDocument(c, profileId)
        val outcome = validator.validate(documentJson, "revision-${parsed.revision}", baseline)

        val status = if (outcome is ValidationOutcome.Valid) {
            RevisionStatus.VALIDATED
        } else {
            RevisionStatus.DRAFT
        }
        val reasons = when (outcome) {
            is ValidationOutcome.Valid -> emptyList()
            is ValidationOutcome.Invalid -> outcome.reasons
            // **접두사를 붙여 구별한다.** "검증이 못 돌았다"와 "프로파일이
            // 틀렸다"를 같은 문자열로 남기면 운영자가 엉뚱한 것을 고친다.
            is ValidationOutcome.Incomplete -> listOf("검증 미완: " + outcome.detail)
        }

        val id = insertRevision(c, profileId, parsed, documentJson, status, reasons, actor)
        // **선언한 스킬을 펴서 넣는다.** 문서만 두면 바인딩의 semver 검사와
        // 진단 2번의 diff가 JSONB를 매번 파싱해야 하고, 그러면 파싱이 두
        // 곳에 생긴다. 관계로 두는 이유가 그것이다(§8.1).
        insertSkills(c, id, parsed)
        audit(
            c, actor,
            operation = "PROFILE_REVISION_SUBMIT",
            subject = "${parsed.vendor}/${parsed.model}#${parsed.revision}",
            after = """{"status":"$status","reasons":${reasons.size}}""",
        )
        SubmitOutcome.Stored(id, parsed.revision, status, reasons)
    }

    // ── 저장 (프레임워크 없이 JDBC로. §3.4의 "도메인은 프레임워크를 모른다")

    private fun upsertProfile(c: Connection, vendor: String, model: String): Long {
        c.prepareStatement(
            "INSERT INTO capability_profile (vendor, model) VALUES (?, ?) " +
                "ON CONFLICT (vendor, model) DO NOTHING",
        ).use { it.setString(1, vendor); it.setString(2, model); it.executeUpdate() }

        return c.prepareStatement(
            "SELECT profile_id FROM capability_profile WHERE vendor = ? AND model = ?",
        ).use { s ->
            s.setString(1, vendor); s.setString(2, model)
            s.executeQuery().use { rs -> check(rs.next()); rs.getLong(1) }
        }
    }

    private fun highestRevision(c: Connection, profileId: Long): Int? =
        c.prepareStatement(
            "SELECT max(revision) FROM profile_revision WHERE profile_id = ?",
        ).use { s ->
            s.setLong(1, profileId)
            s.executeQuery().use { rs ->
                if (!rs.next()) null else rs.getInt(1).takeUnless { rs.wasNull() }
            }
        }

    /** 파괴 검사의 기준선이 되는 문서 — 지금 `ACTIVE`인 개정판(§11.1). */
    private fun activeDocument(c: Connection, profileId: Long): String? =
        c.prepareStatement(
            "SELECT document::text FROM profile_revision " +
                "WHERE profile_id = ? AND status = 'ACTIVE' ORDER BY revision DESC LIMIT 1",
        ).use { s ->
            s.setLong(1, profileId)
            s.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    private fun insertRevision(
        c: Connection,
        profileId: Long,
        document: ProfileDocument,
        json: String,
        status: RevisionStatus,
        reasons: List<String>,
        actor: String,
    ): Long = c.prepareStatement(
        """
        INSERT INTO profile_revision
            (profile_id, revision, document, document_hash, schema_version,
             status, validation_detail, created_by)
        VALUES (?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?)
        RETURNING profile_revision_id
        """.trimIndent(),
    ).use { s ->
        s.setLong(1, profileId)
        s.setInt(2, document.revision)
        s.setString(3, json)
        s.setString(4, sha256(json))
        s.setString(5, document.schemaVersion)
        s.setString(6, status.name)
        s.setString(7, if (reasons.isEmpty()) null else reasonsJson(reasons))
        s.setString(8, actor)
        s.executeQuery().use { rs -> check(rs.next()); rs.getLong(1) }
    }

    /**
     * 선언한 스킬을 `profile_skill`로 편다.
     *
     * **`skill_type`에 없는 스킬은 건너뛴다.** 동기화가 아직 안 돌았거나
     * 계약에 없는 스킬인데, 여기서 만들어 넣으면 §8.1의 "이 표는 계약이
     * 소유한다"가 깨진다. 그 부재는 바인딩이 보고 막는다.
     */
    private fun insertSkills(c: Connection, revisionId: Long, document: ProfileDocument) {
        document.skills.forEach { skill ->
            val skillTypeId = c.prepareStatement(
                "SELECT skill_type_id FROM skill_type WHERE name = ? AND major = ?",
            ).use { s ->
                s.setString(1, skill.skillType)
                s.setInt(2, skill.major)
                s.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
            } ?: return@forEach

            c.prepareStatement(
                "INSERT INTO profile_skill " +
                    "(profile_revision_id, skill_type_id, minor, pause_support, cancel_support) " +
                    "VALUES (?, ?, ?, ?, ?)",
            ).use { s ->
                s.setLong(1, revisionId)
                s.setLong(2, skillTypeId)
                s.setInt(3, skill.minor)
                s.setString(4, skill.pauseSupport)
                s.setString(5, skill.cancelSupport)
                s.executeUpdate()
            }
        }
    }

    /**
     * §8.5 — **조작 하나가 감사 로그 한 줄이다.** 트랜잭션 안에서 쓰므로
     * 조작이 되돌려지면 로그도 함께 되돌려진다. 밖에서 쓰면 일어나지 않은
     * 일이 기록에 남는다.
     */
    private fun audit(
        c: Connection,
        actor: String,
        operation: String,
        subject: String,
        after: String?,
    ) = c.prepareStatement(
        "INSERT INTO audit_log (operation, actor, subject, after) VALUES (?, ?, ?, ?::jsonb)",
    ).use { s ->
        s.setString(1, operation)
        s.setString(2, actor)
        s.setString(3, subject)
        s.setString(4, after)
        s.executeUpdate()
    }

    private fun reasonsJson(reasons: List<String>): String =
        reasons.joinToString(",", "{\"reasons\":[", "]}") { "\"" + it.replace("\"", "\\\"") + "\"" }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
