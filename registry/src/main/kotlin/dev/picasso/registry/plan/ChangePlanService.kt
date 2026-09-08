package dev.picasso.registry.plan

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.registry.binding.ActivateOutcome
import dev.picasso.registry.binding.BindingService
import dev.picasso.registry.store.Db
import java.sql.Connection

/** §9.5의 `intent` 넷. */
enum class Intent {
    REMOVE_CAPABILITY,
    MIGRATE_MAJOR,
    RETIRE_ADAPTER_VERSION,
    RETIRE_PROFILE_REVISION,
}

/** §9.5의 단계 `kind` 넷. */
/**
 * §9.5의 단계 종류.
 *
 * [VERIFY_WITHDRAWAL] 은 **`APPLY` 뒤에 온다.** §9.3이 시작 조건 둘을
 * 엄격하게 정해 놓고 끝은 열어 뒀는데, §15.5의 폴링 지연과 §10.6의 "불통 중
 * 마지막 능력 유지" 때문에 **카탈로그에서는 사라졌는데 로봇은 여전히 받는**
 * 중간 상태가 실제로 생긴다. 가역성은 `DRAIN` 과 같이 **무해** — 아무것도
 * 하지 않고 조건 충족만 기다린다.
 */
enum class StepKind { ANNOUNCE, OBSERVE_MIGRATION, DRAIN, APPLY, VERIFY_WITHDRAWAL }

sealed interface CreateOutcome {
    data class Created(val planId: Long) : CreateOutcome

    /**
     * **기존 계획을 가리킨다.** 그냥 거부하면 운영자는 왜 안 되는지 모르고
     * 대상을 조금 바꿔 두 번째 계획을 만든다 — 그러면 유일 인덱스를
     * 우회한 채 같은 능력을 겨냥한 계획이 둘이 된다.
     */
    data class AlreadyLive(val planId: Long, val detail: String) : CreateOutcome
}

sealed interface ExecuteOutcome {
    data class Executed(val detail: String) : ExecuteOutcome

    /** @param blocking 막고 있는 검사들. 화면이 그대로 표에 그린다. */
    data class Refused(val blocking: List<CheckOutcome>, val detail: String) : ExecuteOutcome
}

/** 진단 6번의 한 단계. `satisfied`는 **지금 재평가한 값**이다. */
data class StepView(
    val seq: Int,
    val kind: StepKind,
    val checks: List<CheckOutcome>,
    val executed: Boolean,
)

/** 진단 6번의 한 계획. */
data class PlanView(
    val planId: Long,
    val intent: Intent,
    val site: String,
    val status: String,
    val steps: List<StepView>,
)

/**
 * §9.5의 변경 계획. **완료 기준 19가 여기 선다.**
 *
 * ## 운영자가 의도를 선언하면 시스템이 절차를 만든다
 *
 * 단계를 운영자가 쓰게 하면 그때부터 절차가 **사람의 기억에 산다.** 그러면
 * 급할 때 단계가 줄고, 급할 때가 정확히 그러면 안 되는 때다.
 *
 * ## 전제 조건은 실행 시점에 다시 평가한다
 *
 * `change_plan_step.satisfied`는 화면용 캐시이고 권위가 아니다(§9.5).
 * `satisfied=true`가 된 뒤 새 소비자가 협상에 성공하거나 새 태스크가
 * 시작되면 실행이 **거부된다.** 이것이 없으면 *"충족을 확인한 순간"과
 * "실행한 순간" 사이의 창*이 사고가 된다.
 */
class ChangePlanService(
    private val db: Db,
    private val preconditions: Preconditions,
    private val bindings: BindingService,
) {

    private val mapper = ObjectMapper()

    /**
     * 계획을 만든다. **단계는 §9.5의 표대로 시스템이 산출한다.**
     *
     * @param target `intent`가 겨냥하는 것. `REMOVE_CAPABILITY`·`MIGRATE_MAJOR`는
     *   `skill`(과 `major`), 폐기 둘은 각각의 id.
     */
    fun create(
        intent: Intent,
        target: Map<String, String>,
        site: String,
        actor: String,
    ): CreateOutcome = db.transaction { c ->
        val key = targetKey(intent, target)

        existingLive(c, key, site)?.let { existing ->
            return@transaction CreateOutcome.AlreadyLive(
                existing,
                "같은 대상을 겨냥한 계획이 이미 있다: $key",
            )
        }

        val planId = c.prepareStatement(
            "INSERT INTO change_plan (intent, target, target_key, site, created_by) " +
                "VALUES (?, ?::jsonb, ?, ?, ?) RETURNING plan_id",
        ).use { s ->
            s.setString(1, intent.name)
            s.setString(2, mapper.writeValueAsString(target))
            s.setString(3, key); s.setString(4, site); s.setString(5, actor)
            s.executeQuery().use { rs -> check(rs.next()); rs.getLong(1) }
        }

        // **plan 은 계획을 만든 뒤에야 안다.** stepsFor 가 자리표시자를 두고
        // 여기서 실제 id 로 바꾼다 — 자리표시자가 그대로 남으면
        // CAPABILITY_WITHDRAWN 이 "plan 파라미터가 숫자가 아니다"로 막는다.
        stepsFor(intent, target).map { (kind, checks) ->
            kind to checks.map { check ->
                if (check.params["plan"] == PLAN_ID_PLACEHOLDER) {
                    check.copy(params = check.params + ("plan" to planId.toString()))
                } else {
                    check
                }
            }
        }.forEachIndexed { i, (kind, checks) ->
            c.prepareStatement(
                "INSERT INTO change_plan_step (plan_id, seq, kind, precondition) " +
                    "VALUES (?, ?, ?, ?::jsonb)",
            ).use { s ->
                s.setLong(1, planId); s.setInt(2, i + 1); s.setString(3, kind.name)
                s.setString(
                    4,
                    mapper.writeValueAsString(
                        mapOf(
                            "checks" to checks.map {
                                mapOf("type" to it.type.name, "params" to it.params)
                            },
                        ),
                    ),
                )
                s.executeUpdate()
            }
        }

        audit(c, actor, "CHANGE_PLAN_CREATE", "$intent:$key@$site")
        CreateOutcome.Created(planId)
    }

    private companion object {
        /** [create] 가 실제 `plan_id` 로 바꾼다. */
        const val PLAN_ID_PLACEHOLDER = "<plan>"
    }

    /**
     * §9.5의 표 — `intent`별 단계와 각 단계의 검사.
     *
     * **`APPLY`에 검사를 다시 단다.** 앞 단계에서 충족을 봤더라도 실행하는
     * 것은 `APPLY`이고, 그 사이에 상태가 바뀔 수 있다. 앞 단계의
     * `satisfied`를 믿으면 재평가가 아무 뜻이 없어진다.
     */
    private fun stepsFor(
        intent: Intent,
        target: Map<String, String>,
    ): List<Pair<StepKind, List<PreconditionCheck>>> {
        val skill = target["skill"]
        // **major 를 함께 넘긴다.** 없으면 두 조회가 이름만 보고, 그러면 다른
        // major 를 쓰는 소비자·태스크가 축소를 막는다(§15.50이 그 상태였다).
        val skillParams = mapOf("skill" to (skill ?: ""), "major" to (target["major"] ?: ""))
        val consumers = PreconditionCheck(CheckType.NO_ACTIVE_CONSUMERS, skillParams)
        val drained = PreconditionCheck(CheckType.NO_INFLIGHT_TASKS, skillParams)
        val announced = PreconditionCheck(CheckType.DEPRECATION_PUBLISHED, mapOf("skill" to (skill ?: "")))
        // `plan` 은 계획을 만든 뒤에야 알 수 있으므로 create 가 채운다.
        val withdrawn = PreconditionCheck(
            CheckType.CAPABILITY_WITHDRAWN,
            skillParams + ("plan" to PLAN_ID_PLACEHOLDER),
        )

        return when (intent) {
            Intent.REMOVE_CAPABILITY -> listOf(
                StepKind.ANNOUNCE to listOf(announced),
                StepKind.OBSERVE_MIGRATION to listOf(consumers),
                StepKind.DRAIN to listOf(drained),
                StepKind.APPLY to listOf(announced, consumers, drained),
                StepKind.VERIFY_WITHDRAWAL to listOf(withdrawn),
            )

            Intent.MIGRATE_MAJOR -> {
                val successor = PreconditionCheck(
                    CheckType.SUCCESSOR_ACTIVE,
                    mapOf("profile_revision_id" to (target["successor_revision_id"] ?: "")),
                )
                listOf(
                    StepKind.ANNOUNCE to listOf(announced),
                    StepKind.OBSERVE_MIGRATION to listOf(successor, consumers),
                    StepKind.DRAIN to listOf(drained),
                    StepKind.APPLY to listOf(successor, consumers, drained),
                )
            }

            Intent.RETIRE_ADAPTER_VERSION -> {
                val free = PreconditionCheck(
                    CheckType.NO_ACTIVE_BINDINGS,
                    mapOf("adapter_version_id" to (target["adapter_version_id"] ?: "")),
                )
                listOf(
                    StepKind.OBSERVE_MIGRATION to listOf(free),
                    StepKind.DRAIN to listOf(free),
                    StepKind.APPLY to listOf(free),
                )
            }

            Intent.RETIRE_PROFILE_REVISION -> {
                val free = PreconditionCheck(
                    CheckType.NO_ACTIVE_BINDINGS,
                    mapOf("profile_revision_id" to (target["profile_revision_id"] ?: "")),
                )
                listOf(
                    StepKind.OBSERVE_MIGRATION to listOf(free),
                    StepKind.APPLY to listOf(free),
                )
            }
        }
    }

    /**
     * 단계를 실행한다. **전제 조건을 지금 다시 평가한다.**
     *
     * @param successorRevisionId `REMOVE_CAPABILITY`·`MIGRATE_MAJOR`의
     *   `APPLY`가 활성화할 개정판 — **그 스킬을 뺀 새 개정판**(§9.5).
     *   계약 축은 안 건드린다: 계약은 배포이며 `registry`가 막을 수 없다.
     */
    fun execute(
        planId: Long,
        seq: Int,
        actor: String,
        successorRevisionId: Long? = null,
    ): ExecuteOutcome {
        val (kind, checks) = stepOf(planId, seq)
        val outcomes = preconditions.evaluate(checks)
        val blocking = outcomes.filterNot { it.satisfied }

        if (blocking.isNotEmpty()) {
            // **캐시도 함께 내린다.** 화면이 "충족"이라 적힌 채 실행만
            // 거부되면 운영자는 시스템이 고장 났다고 읽는다.
            markSatisfied(planId, seq, false, blocking)
            return ExecuteOutcome.Refused(
                blocking,
                "차단 사유: " + blocking.joinToString("; ") { it.detail },
            )
        }

        return db.transaction { c ->
            val detail = when (kind) {
                // 앞의 셋은 **아무것도 하지 않는다**(§9.5의 가역성 열).
                // 조건 충족을 기다리는 것이 하는 일의 전부다.
                StepKind.ANNOUNCE,
                StepKind.OBSERVE_MIGRATION,
                StepKind.DRAIN,
                StepKind.VERIFY_WITHDRAWAL,
                -> "조건 충족 확인"

                StepKind.APPLY -> {
                    val detail = apply(c, planId, actor, successorRevisionId)
                    recordBaseline(c, planId)
                    detail
                }
            }

            c.prepareStatement(
                "UPDATE change_plan_step SET satisfied = true, satisfied_at = now(), " +
                    "executed_at = now(), observability = 'OBSERVED' " +
                    "WHERE plan_id = ? AND seq = ?",
            ).use { it.setLong(1, planId); it.setInt(2, seq); it.executeUpdate() }

            audit(c, actor, "CHANGE_PLAN_STEP", "$planId#$seq:$kind")
            ExecuteOutcome.Executed(detail)
        }
    }

    /**
     * `APPLY`가 실제로 하는 일(§9.5의 표).
     *
     * `REMOVE_CAPABILITY`·`MIGRATE_MAJOR`는 **그 스킬을 뺀 새 개정판을
     * 활성화한다.** 폐기 둘은 표시만 바꾼다.
     */
    private fun apply(
        c: Connection,
        planId: Long,
        actor: String,
        successorRevisionId: Long?,
    ): String {
        val intent = Intent.valueOf(
            c.prepareStatement("SELECT intent FROM change_plan WHERE plan_id = ?").use { s ->
                s.setLong(1, planId)
                s.executeQuery().use { rs -> check(rs.next()); rs.getString(1) }
            },
        )

        val detail = when (intent) {
            Intent.REMOVE_CAPABILITY, Intent.MIGRATE_MAJOR -> {
                val successor = requireNotNull(successorRevisionId) {
                    "$intent 의 APPLY 는 활성화할 개정판을 요구한다 — " +
                        "그 스킬을 뺀 새 개정판이다(§9.5)"
                }
                // **활성화는 `BindingService`가 한다.** 여기서 status를 직접
                // 쓰면 §8.4 ③의 승인 조건(TESTED/SUPERSEDED, 세 스위트 PASS)을
                // 지나지 않는 두 번째 활성화 경로가 생긴다.
                when (val outcome = bindings.activate(successor, actor)) {
                    is ActivateOutcome.Activated -> "개정판 $successor 활성화"
                    is ActivateOutcome.Refused -> throw IllegalStateException(
                        "후계 개정판을 활성화할 수 없다: ${outcome.detail}",
                    )
                }
            }

            Intent.RETIRE_ADAPTER_VERSION -> "어댑터 버전 폐기 표시"

            Intent.RETIRE_PROFILE_REVISION -> {
                c.prepareStatement(
                    "UPDATE profile_revision SET status = 'REVOKED' " +
                        "WHERE profile_revision_id = " +
                        "(SELECT (target->>'profile_revision_id')::bigint " +
                        " FROM change_plan WHERE plan_id = ?)",
                ).use { it.setLong(1, planId); it.executeUpdate() }
                "개정판 폐기"
            }
        }

        c.prepareStatement(
            "UPDATE change_plan SET status = 'APPLIED', applied_at = now() WHERE plan_id = ?",
        ).use { it.setLong(1, planId); it.executeUpdate() }
        return detail
    }

    /** 계획을 버린다. **이미 실행된 단계는 되돌아가지 않는다**(§9.5). */
    fun abandon(planId: Long, actor: String) = db.transaction { c ->
        c.prepareStatement("UPDATE change_plan SET status = 'ABANDONED' WHERE plan_id = ?")
            .use { it.setLong(1, planId); it.executeUpdate() }
        audit(c, actor, "CHANGE_PLAN_ABANDON", "$planId")
    }

    /**
     * 진단 6번 — 진행 중 계획과 각 단계의 충족 여부.
     *
     * **캐시가 아니라 지금 재평가한 값을 낸다.** 캐시를 내면 화면이
     * "열렸다"고 하는데 실행은 거부되는 상태가 생기고, 그때 운영자는
     * 화면을 믿는다.
     */
    fun plans(includeFinished: Boolean = false): List<PlanView> {
        val plans = db.open().use { c ->
            c.prepareStatement(
                "SELECT plan_id, intent, site, status FROM change_plan " +
                    "WHERE (? OR status NOT IN ('APPLIED','ABANDONED')) ORDER BY plan_id",
            ).use { s ->
                s.setBoolean(1, includeFinished)
                s.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                listOf(
                                    rs.getLong(1), rs.getString(2),
                                    rs.getString(3), rs.getString(4),
                                ),
                            )
                        }
                    }
                }
            }
        }

        return plans.map { row ->
            val planId = row[0] as Long
            PlanView(
                planId = planId,
                intent = Intent.valueOf(row[1] as String),
                site = row[2] as String,
                status = row[3] as String,
                steps = stepsOf(planId).map {
                    StepView(it.seq, it.kind, preconditions.evaluate(it.checks), it.executed)
                },
            )
        }
    }

    // ── 읽기

    private fun stepOf(planId: Long, seq: Int): Pair<StepKind, List<PreconditionCheck>> =
        stepsOf(planId).firstOrNull { it.seq == seq }
            ?.let { it.kind to it.checks }
            ?: throw IllegalArgumentException("없는 단계다: $planId#$seq")

    private data class StepRow(
        val seq: Int,
        val kind: StepKind,
        val checks: List<PreconditionCheck>,
        val executed: Boolean,
    )

    private fun stepsOf(planId: Long): List<StepRow> = db.open().use { c ->
        c.prepareStatement(
            "SELECT seq, kind, precondition::text, (executed_at IS NOT NULL) " +
                "FROM change_plan_step WHERE plan_id = ? ORDER BY seq",
        ).use { s ->
            s.setLong(1, planId)
            s.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            StepRow(
                                rs.getInt(1),
                                StepKind.valueOf(rs.getString(2)),
                                parseChecks(rs.getString(3)),
                                rs.getBoolean(4),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun parseChecks(json: String): List<PreconditionCheck> {
        val node = mapper.readTree(json)["checks"] ?: return emptyList()
        return node.map { check ->
            PreconditionCheck(
                CheckType.valueOf(check["type"].asText()),
                check["params"].properties().associate { (k, v) -> k to v.asText() },
            )
        }
    }

    private fun existingLive(c: Connection, key: String, site: String): Long? =
        c.prepareStatement(
            "SELECT plan_id FROM change_plan WHERE target_key = ? AND site = ? " +
                "AND status NOT IN ('APPLIED','ABANDONED')",
        ).use { s ->
            s.setString(1, key); s.setString(2, site)
            s.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }

    /**
     * @param blocking 차단한 결과들. **관측 불가가 하나라도 있으면 그 사실을
     *   함께 적는다** — "소비자가 둘이라 막혔다"와 "관측선이 끊겨 알 수
     *   없다"는 운영자가 할 일이 전혀 다르다.
     */
    private fun markSatisfied(
        planId: Long,
        seq: Int,
        value: Boolean,
        blocking: List<CheckOutcome> = emptyList(),
    ) = db.transaction { c ->
        val blind = blocking.any { it.observability == Observed.NOT_OBSERVABLE }
        c.prepareStatement(
            "UPDATE change_plan_step SET satisfied = ?, " +
                "satisfied_at = CASE WHEN ? THEN now() ELSE NULL END, " +
                "observability = ? " +
                "WHERE plan_id = ? AND seq = ?",
        ).use {
            it.setBoolean(1, value); it.setBoolean(2, value)
            it.setString(3, if (blind) "NOT_OBSERVABLE" else "OBSERVED")
            it.setLong(4, planId); it.setInt(5, seq); it.executeUpdate()
        }
    }

    /**
     * `APPLY` 순간 각 기체의 `capability_epoch` 을 박는다.
     *
     * **여기서 박지 않으면 [CheckType.CAPABILITY_WITHDRAWN] 이 판정할 기준이
     * 없다.** 나중에 세면 그 사이 새로 바인딩된 기체가 baseline 없이 들어와
     * "이미 반영됨"으로 읽힌다.
     */
    private fun recordBaseline(c: Connection, planId: Long) {
        c.prepareStatement(
            """
            INSERT INTO withdrawal_baseline (change_plan_id, robot_id, epoch_at_apply)
            SELECT ?, l.robot_id, l.capability_epoch
            FROM robot_liveness l
            JOIN robot_binding b ON b.robot_id = l.robot_id AND b.unbound_at IS NULL
            ON CONFLICT (change_plan_id, robot_id) DO NOTHING
            """.trimIndent(),
        ).use { it.setLong(1, planId); it.executeUpdate() }
    }

    /**
     * `intent`와 `target`을 정규화한 문자열. **경합 방지의 열쇠다.**
     *
     * 정규화가 약하면 `{skill:pick_place, major:1}`과
     * `{major:1, skill:pick_place}`가 다른 키가 되고, **같은 능력을 겨냥한
     * 계획이 둘 생긴다** — 각자 자기 전제 조건만 보면서.
     */
    private fun targetKey(intent: Intent, target: Map<String, String>): String =
        intent.name + ":" + target.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }

    private fun audit(c: Connection, actor: String, operation: String, subject: String) =
        c.prepareStatement(
            "INSERT INTO audit_log (operation, actor, subject) VALUES (?, ?, ?)",
        ).use {
            it.setString(1, operation); it.setString(2, actor); it.setString(3, subject)
            it.executeUpdate()
        }
}
