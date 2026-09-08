package dev.picasso.registry.revision

import dev.picasso.registry.store.Db
import java.sql.Connection
import java.time.Instant

sealed interface AnnounceOutcome {
    data class Announced(val skillTypeId: Long, val replaced: Instant?) : AnnounceOutcome

    /** **사유를 남긴다.** 조작이 조용히 아무것도 안 하는 것이 가장 나쁘다. */
    data class Rejected(val detail: String) : AnnounceOutcome
}

sealed interface WithdrawOutcome {
    data class Withdrawn(val was: Instant) : WithdrawOutcome
    data class NothingToWithdraw(val detail: String) : WithdrawOutcome
    data class Rejected(val detail: String) : WithdrawOutcome
}

/**
 * §9.3의 **계약 축 폐기 예고**를 기입한다 — `skill_type_deprecation`.
 *
 * ## 왜 운영자 조작인가 (`skill_type`은 읽기 전용인데)
 *
 * §8.1이 `skill_type`을 읽기 전용으로 둔 이유는 **계약이 그 값을 소유**하기
 * 때문이다 — 이름·major·`introduced_in_semver`는 전부 proto에서 나온다.
 *
 * **`deprecated_after`는 proto에서 나오지 않는다.** 계약에는 "이 스킬을
 * 언제까지 쓸 수 있는가"를 적을 자리가 없고(`SkillDeclaration.deprecated_after`는
 * **프로파일 축**이다), 있다 해도 그것은 릴리스 사실이 아니라 **운영 판단**이다.
 * 같은 계약을 쓰는 사이트가 서로 다른 시점에 접을 수 있다.
 *
 * 표가 그 판단의 흔적을 든다 — `announced_by`와 `note`는 동기화 잡이 채울
 * 값이 아니다. §8.5의 조작 열넷에 *"계약 축 폐기 예고 설정"*이 들어 있는 것도
 * 같은 이유다.
 *
 * ## 예고는 게이트가 아니다
 *
 * §9.3이 못박았다 — *"예고는 정보이지 게이트가 아니다. 게이트는 두 조회다."*
 * 그래서 이 조작은 무엇도 열지 않는다. `DEPRECATION_PUBLISHED`가 이것을
 * 보지만, 그것은 **순서**를 강제할 뿐이다: 예고 없이 이행을 기다리면 소비자는
 * 자기가 옮겨야 하는 줄 모르고, 그러면 두 조회가 영원히 0이 안 된다.
 *
 * ## 되돌릴 수 있다
 *
 * §9.5가 `ANNOUNCE` 단계의 가역성을 *"가역 — 예고를 지우면 된다"*로 규정했다.
 * [withdraw]가 그 자리이며, 없으면 그 표의 "가역"이 거짓말이 된다.
 */
class DeprecationService(private val db: Db) {

    /**
     * @param deprecatedAfter 이 시각 이후로는 쓰지 말라는 예고.
     * @return 이미 예고가 있었으면 [AnnounceOutcome.Announced.replaced]에 옛 값이
     *   담긴다. **덮어쓰기를 막지 않는다** — 예고를 늦추는 것도 앞당기는 것도
     *   운영 판단이고, 무엇이 바뀌었는지는 감사 로그가 든다.
     */
    fun announce(
        skillType: String,
        major: Int,
        deprecatedAfter: Instant,
        actor: String,
        note: String? = null,
    ): AnnounceOutcome = db.transaction { c ->
        val skillTypeId = skillTypeId(c, skillType, major)
            ?: return@transaction AnnounceOutcome.Rejected(
                "모르는 스킬 타입이다: $skillType@$major",
            )

        val previous = currentDeprecation(c, skillTypeId)

        c.prepareStatement(
            """
            INSERT INTO skill_type_deprecation
                (skill_type_id, deprecated_after, announced_by, note)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (skill_type_id) DO UPDATE SET
                deprecated_after = EXCLUDED.deprecated_after,
                announced_by     = EXCLUDED.announced_by,
                announced_at     = now(),
                note             = EXCLUDED.note
            """.trimIndent(),
        ).use { s ->
            s.setLong(1, skillTypeId)
            s.setTimestamp(2, java.sql.Timestamp.from(deprecatedAfter))
            s.setString(3, actor)
            s.setString(4, note)
            s.executeUpdate()
        }

        audit(c, actor, "SKILL_TYPE_DEPRECATION_ANNOUNCE", "$skillType@$major:$deprecatedAfter")
        AnnounceOutcome.Announced(skillTypeId, previous)
    }

    /** §9.5의 `ANNOUNCE`가 가역인 근거. */
    fun withdraw(skillType: String, major: Int, actor: String): WithdrawOutcome =
        db.transaction { c ->
            val skillTypeId = skillTypeId(c, skillType, major)
                ?: return@transaction WithdrawOutcome.Rejected(
                    "모르는 스킬 타입이다: $skillType@$major",
                )

            val previous = currentDeprecation(c, skillTypeId)
                // **없는 것을 지운 것으로 보고하지 않는다.** 운영자가 "취소했다"고
                // 읽으면 실제로는 다른 축(프로파일)의 예고가 남아 있는 것을 못 본다.
                ?: return@transaction WithdrawOutcome.NothingToWithdraw(
                    "$skillType@$major 에 계약 축 예고가 없다",
                )

            c.prepareStatement("DELETE FROM skill_type_deprecation WHERE skill_type_id = ?")
                .use { it.setLong(1, skillTypeId); it.executeUpdate() }

            audit(c, actor, "SKILL_TYPE_DEPRECATION_WITHDRAW", "$skillType@$major")
            WithdrawOutcome.Withdrawn(previous)
        }

    private fun skillTypeId(c: Connection, name: String, major: Int): Long? =
        c.prepareStatement("SELECT skill_type_id FROM skill_type WHERE name = ? AND major = ?")
            .use { s ->
                s.setString(1, name); s.setInt(2, major)
                s.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
            }

    private fun currentDeprecation(c: Connection, skillTypeId: Long): Instant? =
        c.prepareStatement(
            "SELECT deprecated_after FROM skill_type_deprecation WHERE skill_type_id = ?",
        ).use { s ->
            s.setLong(1, skillTypeId)
            s.executeQuery().use { rs -> if (rs.next()) rs.getTimestamp(1).toInstant() else null }
        }

    private fun audit(c: Connection, actor: String, operation: String, subject: String) =
        c.prepareStatement(
            "INSERT INTO audit_log (operation, actor, subject) VALUES (?, ?, ?)",
        ).use {
            it.setString(1, operation); it.setString(2, actor); it.setString(3, subject)
            it.executeUpdate()
        }
}
