package dev.picasso.registry.binding

import dev.picasso.registry.Semver
import dev.picasso.registry.revision.RevisionStatus
import dev.picasso.registry.store.Db
import java.sql.Connection

/** §8.4 ③의 활성화 결과. */
sealed interface ActivateOutcome {
    data class Activated(val superseded: Long?) : ActivateOutcome

    /** 승인 조건을 못 채웠다. **거부이지 실패가 아니다** — 조건을 채우면 된다. */
    data class Refused(val detail: String) : ActivateOutcome
}

/** 바인딩 결과. */
sealed interface BindOutcome {
    data class Bound(val bindingId: Long, val unbound: Long?) : BindOutcome

    data class Refused(val detail: String) : BindOutcome
}

/**
 * §8.4 ③의 활성화와 §9.1의 바인딩.
 *
 * ## 활성화는 바인딩을 안 건드리고, 바인딩은 epoch를 안 올린다
 *
 * §8.2가 나눈 것이다. ③은 개정판을 `ACTIVE`로 만들 뿐이고, 어느 기체가
 * 그것을 쓰는지는 바인딩이 정하며, `capability_epoch` 증가는 ④에서
 * **발신자**(`mimic`)가 한다. 세 곳 중 하나라도 남의 일을 하면 같은 값을
 * 두 곳이 다투게 된다.
 *
 * ## 승인 조건 셋(§8.4 ③)
 *
 * `status`가 `TESTED` 또는 `SUPERSEDED`이고, 세 스위트(`CONTRACT`·`NEGATIVE`·
 * `DETERMINISM`) **각각의 최신 실행**이 `PASS`여야 한다.
 *
 * **최신만 보는 것이 요점이다.** 옛 `FAIL`이 남아 있어도 그 뒤 `PASS`가
 * 있으면 통과다 — 그러지 않으면 한 번 실패한 개정판을 영영 못 살린다.
 */
class BindingService(private val db: Db) {

    /**
     * 시험 결과를 기록한다(§8.4 ②의 보고 절반).
     *
     * 세 스위트가 모두 `PASS`가 되면 `VALIDATED` → `TESTED`. **여기서 올리는
     * 이유**는 상태가 사실을 따라가야 하기 때문이다 — 사람이 따로 올리게
     * 두면 시험은 통과했는데 상태는 아닌 창이 생기고, 그 창에서 활성화가
     * 거부된다.
     */
    fun recordTestRun(
        profileRevisionId: Long,
        suite: String,
        result: String,
        ranBy: String,
    ): Boolean = db.transaction { c ->
        require(suite in SUITES) { "모르는 스위트다: $suite (아는 것: $SUITES)" }
        require(result in setOf("PASS", "FAIL")) { "모르는 결과다: $result" }

        c.prepareStatement(
            "INSERT INTO revision_test_run (profile_revision_id, suite, result, ran_by) " +
                "VALUES (?, ?, ?, ?)",
        ).use {
            it.setLong(1, profileRevisionId); it.setString(2, suite)
            it.setString(3, result); it.setString(4, ranBy)
            it.executeUpdate()
        }

        val promoted = allSuitesPass(c, profileRevisionId) &&
            statusOf(c, profileRevisionId) == RevisionStatus.VALIDATED
        if (promoted) {
            setStatus(c, profileRevisionId, RevisionStatus.TESTED)
            audit(c, ranBy, "PROFILE_REVISION_TESTED", "$profileRevisionId")
        }
        promoted
    }

    fun activate(profileRevisionId: Long, actor: String): ActivateOutcome = db.transaction { c ->
        val status = statusOf(c, profileRevisionId)
            ?: return@transaction ActivateOutcome.Refused("없는 개정판이다: $profileRevisionId")

        if (status !in ACTIVATABLE) {
            return@transaction ActivateOutcome.Refused(
                "활성화할 수 있는 상태가 아니다: $status (가능: $ACTIVATABLE)",
            )
        }
        if (!allSuitesPass(c, profileRevisionId)) {
            return@transaction ActivateOutcome.Refused(
                "세 스위트의 최신 실행이 모두 PASS가 아니다: ${latestResults(c, profileRevisionId)}",
            )
        }

        // 같은 기종의 옛 활성 개정판은 **지우지 않고** SUPERSEDED로 내린다 —
        // 롤백이 "이전 개정판 재활성화"이므로 남아 있어야 한다(§8.4 ⑥).
        val previous = c.prepareStatement(
            """
            SELECT r.profile_revision_id FROM profile_revision r
            WHERE r.status = 'ACTIVE' AND r.profile_id =
                  (SELECT profile_id FROM profile_revision WHERE profile_revision_id = ?)
            """.trimIndent(),
        ).use { s ->
            s.setLong(1, profileRevisionId)
            s.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }
        previous?.let { setStatus(c, it, RevisionStatus.SUPERSEDED) }

        setStatus(c, profileRevisionId, RevisionStatus.ACTIVE)
        c.prepareStatement(
            "UPDATE profile_revision SET activated_by = ?, activated_at = now() " +
                "WHERE profile_revision_id = ?",
        ).use { it.setString(1, actor); it.setLong(2, profileRevisionId); it.executeUpdate() }

        audit(c, actor, "PROFILE_REVISION_ACTIVATE", "$profileRevisionId")
        ActivateOutcome.Activated(previous)
    }

    /**
     * §9.1의 바인딩. **네 축 중 둘을 한 행으로 묶는다** — 어느 기체가 어느
     * 어댑터 버전과 어느 개정판을 쓰는가.
     *
     * **계약 semver 불만족 조합은 거부한다.** 어댑터가 모르는 스킬을 프로파일이
     * 선언한 것이고, 그 로봇에 그 스킬로 태스크를 걸면 어댑터가 이해하지 못한다.
     */
    fun bind(
        robotId: String,
        adapterVersionId: Long,
        profileRevisionId: Long,
        actor: String,
        reason: String? = null,
    ): BindOutcome = db.transaction { c ->
        if (statusOf(c, profileRevisionId) != RevisionStatus.ACTIVE) {
            return@transaction BindOutcome.Refused(
                "활성 개정판이 아니다: $profileRevisionId (${statusOf(c, profileRevisionId)})",
            )
        }

        val contractSemver = c.prepareStatement(
            "SELECT contract_semver FROM adapter_version WHERE adapter_version_id = ?",
        ).use { s ->
            s.setLong(1, adapterVersionId)
            s.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        } ?: return@transaction BindOutcome.Refused("없는 어댑터 버전이다: $adapterVersionId")

        val tooNew = requiredSemvers(c, profileRevisionId)
            .filter { (_, introduced) -> Semver.parse(introduced) > Semver.parse(contractSemver) }
        if (tooNew.isNotEmpty()) {
            return@transaction BindOutcome.Refused(
                "어댑터의 계약 semver($contractSemver)가 낮다 — " +
                    tooNew.joinToString { "${it.first}은 ${it.second}부터" },
            )
        }

        // **해제는 삭제가 아니라 `unbound_at`이다.** 이력이 남아야 진단 1번이
        // "이 기체는 어느 어댑터·개정판이었는가"에 답할 수 있다.
        val unbound = c.prepareStatement(
            "UPDATE robot_binding SET unbound_at = now() " +
                "WHERE robot_id = ? AND unbound_at IS NULL RETURNING robot_binding_id",
        ).use { s ->
            s.setString(1, robotId)
            s.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }

        val id = c.prepareStatement(
            "INSERT INTO robot_binding " +
                "(robot_id, adapter_version_id, profile_revision_id, bound_by, reason) " +
                "VALUES (?, ?, ?, ?, ?) RETURNING robot_binding_id",
        ).use { s ->
            s.setString(1, robotId); s.setLong(2, adapterVersionId)
            s.setLong(3, profileRevisionId); s.setString(4, actor); s.setString(5, reason)
            s.executeQuery().use { rs -> check(rs.next()); rs.getLong(1) }
        }

        audit(c, actor, "ROBOT_BIND", robotId)
        BindOutcome.Bound(id, unbound)
    }

    /** 지금 이 기체가 쓰는 조합. 없으면 `null`. */
    fun activeBinding(robotId: String): Triple<Long, Long, Long>? = db.open().use { c ->
        c.prepareStatement(
            "SELECT robot_binding_id, adapter_version_id, profile_revision_id " +
                "FROM robot_binding WHERE robot_id = ? AND unbound_at IS NULL",
        ).use { s ->
            s.setString(1, robotId)
            s.executeQuery().use { rs ->
                if (rs.next()) Triple(rs.getLong(1), rs.getLong(2), rs.getLong(3)) else null
            }
        }
    }

    // ── 안쪽

    private fun statusOf(c: Connection, id: Long): RevisionStatus? = c.prepareStatement(
        "SELECT status FROM profile_revision WHERE profile_revision_id = ?",
    ).use { s ->
        s.setLong(1, id)
        s.executeQuery().use { rs ->
            if (rs.next()) RevisionStatus.valueOf(rs.getString(1)) else null
        }
    }

    private fun setStatus(c: Connection, id: Long, status: RevisionStatus) = c.prepareStatement(
        "UPDATE profile_revision SET status = ? WHERE profile_revision_id = ?",
    ).use { it.setString(1, status.name); it.setLong(2, id); it.executeUpdate() }

    /** 스위트별 **최신** 결과. 옛 FAIL이 뒤의 PASS를 덮지 않는다. */
    private fun latestResults(c: Connection, id: Long): Map<String, String> = c.prepareStatement(
        """
        SELECT DISTINCT ON (suite) suite, result FROM revision_test_run
        WHERE profile_revision_id = ? ORDER BY suite, ran_at DESC, run_id DESC
        """.trimIndent(),
    ).use { s ->
        s.setLong(1, id)
        s.executeQuery().use { rs ->
            buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) }
        }
    }

    private fun allSuitesPass(c: Connection, id: Long): Boolean {
        val latest = latestResults(c, id)
        return SUITES.all { latest[it] == "PASS" }
    }

    /** 이 개정판이 선언한 스킬들의 `(이름, 최초 계약 semver)`. */
    private fun requiredSemvers(c: Connection, id: Long): List<Pair<String, String>> =
        c.prepareStatement(
            """
            SELECT t.name, t.introduced_in_semver FROM profile_skill p
            JOIN skill_type t ON t.skill_type_id = p.skill_type_id
            WHERE p.profile_revision_id = ?
            """.trimIndent(),
        ).use { s ->
            s.setLong(1, id)
            s.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
            }
        }

    private fun audit(c: Connection, actor: String, operation: String, subject: String) =
        c.prepareStatement(
            "INSERT INTO audit_log (operation, actor, subject) VALUES (?, ?, ?)",
        ).use {
            it.setString(1, operation); it.setString(2, actor); it.setString(3, subject)
            it.executeUpdate()
        }

    companion object {
        /**
         * §8.4 ③의 세 스위트.
         *
         * **공개한다.** 시험이 이 목록을 리터럴로 다시 적으면 스위트가 늘 때
         * 시험이 조용히 옛 셋만 보게 된다.
         */
        val SUITE_NAMES = listOf("CONTRACT", "NEGATIVE", "DETERMINISM")

        private val SUITES = SUITE_NAMES

        /**
         * 활성화할 수 있는 상태.
         *
         * `SUPERSEDED`가 들어 있는 것이 **롤백이다**(§8.4 ⑥) — 이전 개정판을
         * 다시 활성화하는 것이며 별도 경로가 아니다.
         */
        val ACTIVATABLE = setOf(RevisionStatus.TESTED, RevisionStatus.SUPERSEDED)
    }
}
