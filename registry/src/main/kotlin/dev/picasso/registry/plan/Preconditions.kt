package dev.picasso.registry.plan

import dev.picasso.registry.ledger.LedgerService
import dev.picasso.registry.store.Db

/** §9.5의 `precondition.checks[].type` 다섯. */
enum class CheckType {
    NO_ACTIVE_CONSUMERS,
    NO_INFLIGHT_TASKS,
    DEPRECATION_PUBLISHED,
    NO_ACTIVE_BINDINGS,
    SUCCESSOR_ACTIVE,
}

/** 검사 하나. `params`는 `type`마다 다르다. */
data class PreconditionCheck(val type: CheckType, val params: Map<String, String>)

/**
 * 검사 하나의 결과.
 *
 * **차단 사유를 함께 낸다.** *"현재 차단 사유: 소비자 2, 진행 중 3"*(§9.5).
 * 참/거짓만 내면 운영자는 왜 안 되는지 모르고, 모르면 우회를 찾는다.
 */
data class CheckOutcome(val type: CheckType, val satisfied: Boolean, val detail: String)

/**
 * §9.5의 전제 조건 다섯. **운영자가 판단하지 않는다**(§9.3).
 *
 * ## 조회가 거짓말하면 막을 것이 없다
 *
 * §9.3이 *"축소 단계의 진입 조건은 시간이 아니라 관측"*이라며 조회에 권한을
 * 넘겼다. 그러므로 이 클래스가 틀리면 **완료 기준 19가 통째로 무너진다** —
 * 그리고 무너진 것이 안 보인다. 화면은 여전히 "충족"이라 적혀 있다.
 *
 * 그래서 각 검사에 **양성과 음성 시험이 둘 다** 있어야 한다. 언제나 참을
 * 돌려주는 구현은 양성만으로 통과하고, 그것이 정확히 19번이 막으려는
 * 결함이다.
 */
class Preconditions(
    private val db: Db,
    private val ledger: LedgerService,
) {

    fun evaluate(checks: List<PreconditionCheck>): List<CheckOutcome> = checks.map { evaluate(it) }

    fun evaluate(check: PreconditionCheck): CheckOutcome = when (check.type) {
        CheckType.NO_ACTIVE_CONSUMERS -> noActiveConsumers(check.required("skill"))
        CheckType.NO_INFLIGHT_TASKS -> noInflightTasks(check.required("skill"))
        CheckType.DEPRECATION_PUBLISHED -> deprecationPublished(check.required("skill"))
        CheckType.NO_ACTIVE_BINDINGS -> noActiveBindings(check)
        CheckType.SUCCESSOR_ACTIVE -> successorActive(check)
    }

    /**
     * §9.3의 조회 1. `source`를 구분하지 않는다 — 등록한 것과 관측된 것 중
     * 하나라도 살아 있으면 "사용 중"이다(§8.3 결정 6).
     */
    private fun noActiveConsumers(skill: String): CheckOutcome {
        val count = ledger.activeConsumerCount(skill)
        return CheckOutcome(
            CheckType.NO_ACTIVE_CONSUMERS,
            count == 0,
            if (count == 0) "요구하는 active 소비자 없음" else "아직 $count 소비자가 $skill 을 쓴다",
        )
    }

    /**
     * §9.3의 조회 2 — 드레인. `task.skill_type_id`의 **비종착** 행이 0인가.
     *
     * **`skill_type_id`가 `task`에 있는 이유가 이것이다**(§8.3) — 없으면
     * 드레인을 스킬 단위로 판정할 수 없고, 로봇 전체가 비기를 기다리게 된다.
     */
    private fun noInflightTasks(skill: String): CheckOutcome {
        val count = db.open().use { c ->
            c.prepareStatement(
                """
                SELECT count(*) FROM task t
                JOIN skill_type s ON s.skill_type_id = t.skill_type_id
                WHERE s.name = ? AND NOT t.terminal
                """.trimIndent(),
            ).use { st ->
                st.setString(1, skill)
                st.executeQuery().use { rs -> check(rs.next()); rs.getInt(1) }
            }
        }
        return CheckOutcome(
            CheckType.NO_INFLIGHT_TASKS,
            count == 0,
            if (count == 0) "진행 중 태스크 없음" else "진행 중 태스크 $count 건",
        )
    }

    /**
     * 예고가 실제로 기입됐는가.
     *
     * **예고는 정보이지 게이트가 아니다**(§9.3) — 게이트는 위의 두 조회다.
     * 그런데도 단계로 두는 이유는 **순서**다. 예고 없이 이행을 기다리면
     * 소비자는 자기가 옮겨야 하는 줄 모르고, 조회는 영원히 0이 안 된다.
     *
     * **두 축 중 하나라도 있으면 예고된 것이다**(§9.3). 프로파일 축은 활성
     * 개정판의 `profile_skill.deprecated_after`이고, 계약 축은
     * `skill_type_deprecation`이다. 한쪽만 보면 다른 쪽으로 낸 예고가 없는
     * 것이 되고, 그러면 예고를 해 놓고도 제거가 영원히 안 열린다.
     */
    private fun deprecationPublished(skill: String): CheckOutcome {
        val published = db.open().use { c ->
            c.prepareStatement(
                """
                SELECT (
                    SELECT count(*) FROM profile_skill ps
                    JOIN skill_type s       ON s.skill_type_id = ps.skill_type_id
                    JOIN profile_revision r ON r.profile_revision_id = ps.profile_revision_id
                    WHERE s.name = ? AND r.status = 'ACTIVE'
                      AND ps.deprecated_after IS NOT NULL
                ) + (
                    SELECT count(*) FROM skill_type_deprecation d
                    JOIN skill_type s ON s.skill_type_id = d.skill_type_id
                    WHERE s.name = ?
                )
                """.trimIndent(),
            ).use { st ->
                st.setString(1, skill)
                st.setString(2, skill)
                st.executeQuery().use { rs -> check(rs.next()); rs.getInt(1) }
            }
        }
        return CheckOutcome(
            CheckType.DEPRECATION_PUBLISHED,
            published > 0,
            if (published > 0) "폐기 예고 기입됨" else "$skill 의 폐기 예고가 아직 없다",
        )
    }

    /**
     * 그 어댑터 버전이나 개정판을 쓰는 **활성** 바인딩이 0인가.
     *
     * 해제된 바인딩은 이력이라 안 센다(§9.1). 세면 한 번이라도 쓴 적 있는
     * 개정판은 영원히 폐기할 수 없다.
     */
    private fun noActiveBindings(check: PreconditionCheck): CheckOutcome {
        val (column, value) = check.oneOf("adapter_version_id", "profile_revision_id")
        val count = db.open().use { c ->
            c.prepareStatement(
                "SELECT count(*) FROM robot_binding " +
                    "WHERE $column = ? AND unbound_at IS NULL",
            ).use { st ->
                st.setLong(1, value.toLong())
                st.executeQuery().use { rs -> check(rs.next()); rs.getInt(1) }
            }
        }
        return CheckOutcome(
            CheckType.NO_ACTIVE_BINDINGS,
            count == 0,
            if (count == 0) "활성 바인딩 없음" else "아직 기체 $count 대가 붙어 있다",
        )
    }

    /**
     * 대체할 개정판이 이미 `ACTIVE`이고 **바인딩되어 있는가.**
     *
     * `ACTIVE`만 보면 안 된다. 활성화는 바인딩 전환이 아니고(§8.4 ③),
     * 아무 기체도 안 붙은 개정판을 후계자로 인정하면 **옛 것을 지운 뒤
     * 아무도 그 능력을 못 쓰는 순간**이 생긴다.
     */
    private fun successorActive(check: PreconditionCheck): CheckOutcome {
        val successor = check.required("profile_revision_id").toLong()
        val (status, bound) = db.open().use { c ->
            c.prepareStatement(
                """
                SELECT r.status,
                       (SELECT count(*) FROM robot_binding b
                        WHERE b.profile_revision_id = r.profile_revision_id
                          AND b.unbound_at IS NULL)
                FROM profile_revision r
                WHERE r.profile_revision_id = ?
                """.trimIndent(),
            ).use { st ->
                st.setLong(1, successor)
                st.executeQuery().use { rs ->
                    if (rs.next()) rs.getString(1) to rs.getInt(2) else null to 0
                }
            }
        }
        val ok = status == "ACTIVE" && bound > 0
        return CheckOutcome(
            CheckType.SUCCESSOR_ACTIVE,
            ok,
            when {
                status == null -> "후계 개정판 $successor 이 없다"
                status != "ACTIVE" -> "후계 개정판이 활성이 아니다: $status"
                bound == 0 -> "후계 개정판에 붙은 기체가 없다 — 지우면 아무도 못 쓴다"
                else -> "후계 개정판이 활성이고 기체 $bound 대가 붙어 있다"
            },
        )
    }

    private fun PreconditionCheck.required(key: String): String =
        params[key] ?: throw IllegalArgumentException("$type 에 $key 가 없다: $params")

    private fun PreconditionCheck.oneOf(vararg keys: String): Pair<String, String> {
        val found = keys.mapNotNull { k -> params[k]?.let { k to it } }
        require(found.size == 1) {
            "$type 은 ${keys.toList()} 중 정확히 하나를 요구한다: $params"
        }
        return found.single()
    }
}
