package dev.picasso.middleware

import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.protobuf.util.JsonFormat
import dev.picasso.capability.HoldEffects
import dev.picasso.capability.ObjectReferences
import dev.picasso.capability.RemedyStep
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.SkillDeclaration
import dev.picasso.contracts.wire.ContractIdentity

/**
 * 밖에서 들어오는 **승인 시도**(ADR 44, `docs/orchestration.md` §7).
 *
 * ## «무엇을 하라» 가 아니라 «그것을 하라» 다
 *
 * 부르는 쪽은 **조치를 기술하지 못한다.** 주문도 걸음도 값도 싣지 않고, 이미 이 층이 계산해 두고 거절한
 * 제안 하나를 가리킬 뿐이다. 그래서 이 표면이 열려도 부르는 쪽의 권한은 «승인 시도 한 번» 에서 늘지
 * 않는다 — 늘어날 칸이 없다.
 *
 * 앞 판은 주문을 받았다. 주문을 받으면 부르는 쪽이 주문을 **지어낼** 수 있고, 그것은 승인 표면이 아니라
 * 일반 접수 표면이다. 프로세스 안에서는 안 드러났다 — 부르는 쪽이 시험뿐이었기 때문이다.
 */
data class ApprovalAttempt(
    /** 누가 누르는가. **위조 가능하며 부인방지가 아니다**(§15.3) — 조사 단서다. */
    val approver: Approver,
    val robotId: String,
    val jobOrderId: String,
    /**
     * 부르는 쪽이 **본** 조치 열. 그 사이 제안이 바뀌었으면 거절한다.
     *
     * **비워 두는 길을 두지 않는다.** 두면 안 적은 부르는 쪽이 이 대조를 조용히 잃고, 자기가 못 본
     * 조치를 자기 이름으로 승인한다.
     */
    val sawSkillTypes: List<String>,
)

/**
 * 승인 시도가 거절된 **종류**(ADR 44).
 *
 * 사유 산문만 내면 읽는 쪽은 한국어를 문자열로 맞춰야 하고, 그 시험은 «거절됐다» 만 보므로 **아무
 * 거절에나 초록이 된다** — 자격이 없어서인지 제안이 없어서인지 갈리지 않는다. 종류를 가르는 기준은
 * 하나다: **다음 행동이 다른가.**
 */
enum class ApprovalRefusal {
    /** 가려 둔 제안이다. 사람이 먼저 진단해야 한다 — 자격으로 뚫리지 않는다. */
    WITHHELD,

    /** 그 (기체, 주문) 에 승인할 제안이 없다. */
    NO_PROPOSAL,

    /** 부르는 쪽이 본 조치 열과 지금 제안이 다르다. 다시 읽고 다시 시도하는 것이 다음 행동이다. */
    PROPOSAL_CHANGED,

    /** 자동 승인 자격이 선언돼 있지 않다. 올리는 것이 다음 행동이다. */
    NOT_DECLARED,

    /** 선언이 만료됐다. 갱신하는 것이 다음 행동이다. */
    EXPIRED,

    /** 선언의 범위 밖 기체다. 범위를 넓히는 것이 다음 행동이다. */
    ROBOT_OUT_OF_SCOPE,

    /** 선언이 안 덮는 조치 유형이다. 범위를 넓히거나 사람이 누르는 것이 다음 행동이다. */
    SKILL_OUT_OF_SCOPE,

    /** 필수 값이 선언에 없다. 선언에 값을 적는 것이 다음 행동이다. */
    VALUE_NOT_DECLARED,

    /**
     * 든 것의 이름을 못 봤다. 관측이 없으면 **자동으로는 안 누른다** — 사람은 볼 수 있으므로 사람이
     * 누르는 것이 다음 행동이다.
     */
    OBJECT_NOT_OBSERVED,

    /** 선언이 적은 대상과 지금 든 것이 다르다. 이 자격은 이 상황의 것이 아니다. */
    DECLARED_CONTRADICTS_OBSERVED,

    /** 기체의 능력을 못 물어봤다. 조치가 실행 가능한지 확인할 수 없다. */
    CAPABILITY_UNKNOWN,

    /** 에이전트가 값을 실었다. 값은 선언과 관측에서만 온다. */
    VALUES_NOT_ACCEPTED,

    /** 자격은 섰는데 접수 관문이 거절했다(자리 경쟁·구역 겹침 등). 자격의 문제가 아니다. */
    REFUSED_BY_GATE,

    /**
     * 자격은 섰으나 **조치가 안 나갔다** — 그 주문이 이미 같은 판으로 서 있어 접수가 멱등으로 접혔다
     * (§15.175). 승인으로 세지 않는다. 접으면 «승인했는데 아무 일도 안 일어났다» 가 성공으로 보인다.
     */
    REMEDY_NOT_APPLIED,
}

/** 승인 시도의 답. **거절은 정상 경로다** — 오류가 아니다. */
sealed interface ApprovalOutcome {

    /**
     * 승인됐다. **실린 값을 돌려준다** — 부르는 쪽이 값을 고르지 않았으므로, 자기 이름으로 무엇이
     * 나갔는지 아는 길이 이것뿐이다. 안 돌려주면 승인이 부르는 쪽에서도 눈 감고 누르는 단추가 된다.
     */
    data class Approved(val executionId: String, val steps: List<ApprovedStep>) : ApprovalOutcome

    data class Refused(val refusal: ApprovalRefusal, val reason: String) : ApprovalOutcome
}

/** 승인으로 실제로 나간 걸음 하나. */
data class ApprovedStep(val skillType: String, val parameters: Map<String, String>)

/**
 * 에이전트 승인이 실을 값을 **채운다 — 지어내지 않는다**(ADR 44).
 *
 * 출처가 둘뿐이다. 계약이 `is_object_reference` 로 표시한 칸은 **관측**에서, 나머지 필수 칸은 사람의
 * **선언**에서. 어느 쪽도 못 채우면 거절이고 사람에게 남는다.
 *
 * **순수 함수다.** 미들웨어도 포트도 시계도 모른다 — 관측을 인자로 받는다. 그래서 실물에서 아직 못
 * 만드는 상황(기체가 든 채라면서 이름을 안 주는 경우, §15.173)도 시험이 직접 세울 수 있다.
 */
object RemedyValues {

    sealed interface Resolution {
        data class Filled(val parameters: List<Map<String, String>>) : Resolution
        data class Refused(val refusal: ApprovalRefusal, val reason: String) : Resolution
    }

    /**
     * @param observed **지금** 관측한 파지. 걸음마다 효과로 앞으로 굴린다 — 첫 걸음이 놓으면 둘째 걸음이
     *   들 것의 이름은 아무도 모르고, 그때는 채우지 않고 거절한다(§15.174).
     */
    fun resolve(
        steps: List<RemedyStep>,
        declared: Map<String, SkillDeclaration>,
        entitlement: Entitlement,
        observed: HoldState?,
    ): Resolution {
        var hold = observed
        val filled = mutableListOf<Map<String, String>>()

        steps.forEachIndexed { at, step ->
            val where = "조치 ${at + 1}(${step.skillType})"
            val skill = declared[step.skillType]
                ?: return Resolution.Refused(ApprovalRefusal.CAPABILITY_UNKNOWN, "$where 를 기체가 선언하지 않았다")
            val action = entitlement.actionFor(step.skillType)
                ?: return Resolution.Refused(ApprovalRefusal.SKILL_OUT_OF_SCOPE, "$where 가 선언에 없다")

            val required = skill.parametersList.filterNot { it.optional }.map { it.key }
            val values = action.parameters.toMutableMap()

            for (key in required.filter { it in ObjectReferences.keysOf(step.skillType) }) {
                val held = hold?.takeIf { it.kind == HoldKind.HOLD_KIND_HOLDING }?.objectRef.orEmpty()
                if (held.isEmpty()) {
                    return Resolution.Refused(
                        ApprovalRefusal.OBJECT_NOT_OBSERVED,
                        "$where 의 «$key» 는 지금 든 것의 이름인데 그것을 못 봤다",
                    )
                }
                // **선언은 좁히기만 한다.** 적혀 있으면 관측과 같아야 하고 덮어쓰지는 못한다 —
                // 덮어쓸 수 있으면 선언이 사실을 이기고, 자동 승인이 엉뚱한 것을 집는다.
                val named = values[key]
                if (named != null && named != held) {
                    return Resolution.Refused(
                        ApprovalRefusal.DECLARED_CONTRADICTS_OBSERVED,
                        "$where 의 선언은 «$named» 인데 지금 든 것은 «$held» 다",
                    )
                }
                values[key] = held
            }

            val missing = required.filterNot { it in values }
            if (missing.isNotEmpty()) {
                return Resolution.Refused(
                    ApprovalRefusal.VALUE_NOT_DECLARED,
                    "$where 에 필요한 값이 선언에 없다: $missing",
                )
            }

            filled += values
            hold = hold?.let { HoldEffects.after(step.skillType, it) }
        }
        return Resolution.Filled(filled)
    }
}

/**
 * 승인 표면의 **인코딩**(`docs/orchestration.md` §7). `LedgerExport` 와 같은 성질이다 — 문자열만 다루며
 * 소켓도 파일도 모른다. 담는 쪽이 HTTP 로 내든 무엇으로 내든 이 규약은 그대로다.
 *
 * **거절은 정상 응답이다.** 거절을 오류 코드로 내면 읽는 쪽에서 «자격 없음» 과 «시스템 고장» 이 같은
 * 모양이 된다. 오류로 낼 것은 [decode] 가 던지는 **못 읽는 요청**뿐이다.
 */
object ApprovalWire {

    /** 이 규약의 판. 답마다 싣는다 — 읽는 쪽이 모르는 판을 만나면 멈출 수 있게. */
    const val SCHEMA_VERSION: String = "1"

    /**
     * 요청 한 줄을 읽는다. **못 읽으면 던진다** — 못 읽는 요청은 거절이 아니라 잘못된 요청이고, 둘을
     * 같은 모양으로 내면 읽는 쪽이 자기 오타와 «자격 없음» 을 구별하지 못한다.
     */
    fun decode(json: String): ApprovalAttempt {
        val fields = try {
            Struct.newBuilder().also { JsonFormat.parser().merge(json, it) }.build().fieldsMap
        } catch (e: Exception) {
            throw IllegalArgumentException("승인 요청을 읽지 못했다: ${e.message}", e)
        }
        val kind = string(fields, "approverKind")
        return ApprovalAttempt(
            approver = Approver(
                id = string(fields, "approverId"),
                kind = ApproverKind.entries.firstOrNull { it.name == kind }
                    ?: throw IllegalArgumentException("모르는 승인자 종류다: $kind"),
            ),
            robotId = string(fields, "robotId"),
            jobOrderId = string(fields, "jobOrderId"),
            sawSkillTypes = strings(fields, "sawSkillTypes"),
        )
    }

    fun encode(outcome: ApprovalOutcome): String {
        val o = Obj()
            .str("schemaVersion", SCHEMA_VERSION)
            .str("contractSemver", ContractIdentity.semver)
        return when (outcome) {
            is ApprovalOutcome.Approved -> o
                .str("outcome", "APPROVED")
                .str("executionId", outcome.executionId)
                .raw("steps", outcome.steps.joinToString(",", "[", "]") { step(it) })
            is ApprovalOutcome.Refused -> o
                .str("outcome", "REFUSED")
                .str("refusal", outcome.refusal.name)
                .str("reason", outcome.reason)
        }.done()
    }

    private fun step(s: ApprovedStep): String = Obj()
        .str("skillType", s.skillType)
        .raw("parameters", s.parameters.entries.joinToString(",", "{", "}") { Json.quote(it.key) + ":" + Json.quote(it.value) })
        .done()

    private fun string(fields: Map<String, Value>, name: String): String {
        val value = fields[name] ?: throw IllegalArgumentException("승인 요청에 «$name» 이 없다")
        if (!value.hasStringValue()) throw IllegalArgumentException("승인 요청의 «$name» 이 문자열이 아니다")
        return value.stringValue
    }

    private fun strings(fields: Map<String, Value>, name: String): List<String> {
        val value = fields[name] ?: throw IllegalArgumentException("승인 요청에 «$name» 이 없다")
        if (!value.hasListValue()) throw IllegalArgumentException("승인 요청의 «$name» 이 목록이 아니다")
        return value.listValue.valuesList.map {
            if (!it.hasStringValue()) throw IllegalArgumentException("«$name» 의 원소가 문자열이 아니다")
            it.stringValue
        }
    }
}
