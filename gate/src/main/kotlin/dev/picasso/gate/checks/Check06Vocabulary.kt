package dev.picasso.gate.checks

import dev.picasso.gate.CheckResult
import dev.picasso.gate.Finding
import dev.picasso.gate.GateCheck
import dev.picasso.gate.Resource
import dev.picasso.gate.Severity
import dev.picasso.gate.input.GateInput
import dev.picasso.gate.input.LedgerAnswer
import dev.picasso.gate.input.LedgerQuery
import dev.picasso.profile.ProfileKey
import dev.picasso.profile.ProfileDocument

/**
 * 검사 6 — 능력 어휘 파괴 검사와 확장/축소 분류(설계 §11.2·§9.3·§9.4).
 *
 * **입력은 언제나 프로파일 문서다**(§11.1). CI에는 DB가 없으므로 문서로
 * 통일해야 CI와 `registry` 두 호출이 같은 답을 낸다.
 *
 * 2번과의 역할 분담(§9.4): 2번은 계약 축의 구조, 6번은 프로파일 축의 어휘와
 * 파급. proto만 바뀐 PR은 여기 잡히지 않고 2번이 막는다.
 */
class Check06Vocabulary : GateCheck {

    override val id = "6"
    override val name = "능력 어휘 파괴와 확장·축소 분류"
    override val requires = setOf(Resource.PROFILE_DOCUMENT, Resource.BASELINE)

    override fun run(input: GateInput): CheckResult {
        val baseline = input.baseline!!
        val findings = mutableListOf<Finding>()
        val skipped = mutableSetOf<Resource>()

        input.profiles.forEach { head ->
            val key = ProfileKey(head.vendor, head.model)
            // 기준선이 없으면 신규다. 파괴 검사는 통과로 처리한다(§11.1).
            val baseJson = baseline[key] ?: return@forEach

            val base = parseBaseline(key, baseJson, head.path, findings) ?: return@forEach

            findings += compareSkills(base, head, input.registry, skipped)
            findings += compareProjection(base, head)
        }

        // **기준선에 있는데 새 문서 목록에 없는 프로파일 = 파일 삭제.**
        // input.profiles만 순회하면 이것이 완전히 침묵한다 — 그 기종의 능력이
        // 전부 소멸하는 최대 규모의 축소인데도.
        val headKeys = input.profiles.map { ProfileKey(it.vendor, it.model) }.toSet()
        (baseline.keys - headKeys).forEach { gone ->
            val location = "기준선:$gone"
            val base = parseBaseline(gone, baseline.getValue(gone), location, findings)
                ?: return@forEach

            if (base.skills.isEmpty()) {
                findings += Finding(
                    id, Severity.WARNING,
                    "기준선에 있던 프로파일 $gone 이 사라졌다 (선언한 스킬 0개)",
                    location,
                )
            }
            base.skills.forEach { old ->
                findings += shrink(
                    "$location#/skills",
                    "프로파일 제거: $gone — ${old.skillType}@${old.major}",
                    old, input.registry, skipped,
                )
            }
        }

        return if (findings.any { it.severity == Severity.ERROR }) {
            CheckResult.Failed(id, findings, skippedParts = skipped)
        } else {
            CheckResult.Passed(id, findings, skippedParts = skipped)
        }
    }

    /** 깨진 기준선을 조용히 "신규"로 넘기면 파괴 검사가 통째로 사라진다. */
    private fun parseBaseline(
        key: ProfileKey,
        json: String,
        location: String,
        findings: MutableList<Finding>,
    ): ProfileDocument? =
        ProfileDocument.parse("기준선:$key", json).getOrElse {
            findings += Finding(
                id, Severity.ERROR,
                "기준선 문서를 읽을 수 없다($key): ${it.message}",
                location,
            )
            null
        }

    // ── 규칙 1과 2

    private fun compareSkills(
        base: ProfileDocument,
        head: ProfileDocument,
        registry: LedgerQuery?,
        skipped: MutableSet<Resource>,
    ): List<Finding> {
        val out = mutableListOf<Finding>()
        val location = head.locationOf("/skills")

        // (skill_type, major)로 짝지으면 major 증가를 "삭제 + 추가"로 오해한다.
        val headByType = head.skills.groupBy { it.skillType }
        val newlyRequired = requiredPaths(head) - requiredPaths(base)

        base.skills.forEach { old ->
            val siblings = headByType[old.skillType]

            if (siblings == null) {
                out += shrink(location, "스킬 제거: ${old.skillType}@${old.major}", old, registry, skipped)
                return@forEach
            }

            val same = siblings.firstOrNull { it.major == old.major }
            if (same == null) {
                val label = if (siblings.any { it.major > old.major }) "major 증가" else "능력 제거"
                out += shrink(location, "$label: ${old.skillType}@${old.major}", old, registry, skipped)
                return@forEach
            }

            out += inPlace(head, old, same, newlyRequired, registry, skipped)
        }

        out += optionalFieldChanges(base, head, newlyRequired)
        return out
    }

    /** 같은 major 안의 변화. 규칙 1 — 선언된 버전 증가가 분류와 맞는가. */
    private fun inPlace(
        head: ProfileDocument,
        old: ProfileDocument.SkillEntry,
        new: ProfileDocument.SkillEntry,
        newlyRequired: Set<String>,
        registry: LedgerQuery?,
        skipped: MutableSet<Resource>,
    ): List<Finding> {
        val out = mutableListOf<Finding>()
        val oldParams = old.parameters.associateBy { it.key }
        val newParams = new.parameters.associateBy { it.key }
        val where = "${old.skillType}@${old.major}"
        val location = head.locationOf("/skills")

        val needsMajor = mutableListOf<String>()
        val needsMinor = mutableListOf<String>()

        // 사라진 키 — §5.2의 "파라미터 키는 불변이다"
        (oldParams.keys - newParams.keys).forEach {
            needsMajor += "'$it' 키가 사라졌다 (§5.2 파라미터 키는 불변이다)"
        }

        // 새 키 — 필수면 major, 선택이면 minor
        (newParams.keys - oldParams.keys).forEach { key ->
            if (newParams.getValue(key).optional) needsMinor += "선택 파라미터 '$key' 추가"
            else needsMajor += "필수 파라미터 '$key' 추가"
        }

        // 남은 키의 변화
        (oldParams.keys intersect newParams.keys).forEach { key ->
            val o = oldParams.getValue(key)
            val n = newParams.getValue(key)
            if (o.valueType != n.valueType) needsMajor += "'$key'의 value_type이 ${o.valueType} → ${n.valueType}"
            if (o.unit != n.unit) needsMajor += "'$key'의 unit(단위)이 ${o.unit} → ${n.unit}"
            if (o.optional && !n.optional) needsMajor += "'$key'가 선택 → 필수"
        }

        // §5.3 — 보내지 않는 클라이언트가 핸드셰이크에서 걸린다.
        // 클라이언트에게는 필수 파라미터 추가와 같은 실패다.
        newlyRequired
            .filter { path -> newParams.keys.any { path.endsWith(".$it") } }
            .forEach { needsMajor += "optional_fields의 '$it'가 REQUIRED가 됐다" }

        // 사전 조건(설계안 §6, §15.143). **추가는 축소다** — 어제 든 채로 보내던 소비자가 오늘 거절된다.
        // major 로 흡수할 수 없다: 프로파일의 major 는 계약 카탈로그의 major 에 묶여 있어(검사 4번)
        // 프로파일이 혼자 올릴 수 없다. 그래서 스킬 제거와 같은 길을 간다 — §9.3 의 두 조회(active 소비자,
        // 비종착 태스크)를 원장에 묻고, 원장이 없으면 건너뜀을 남긴다. 제거는 확장이라 막지 않되 침묵시키지 않는다.
        // 값이 바뀐 조건은 제거 + 추가로 읽혀 축소가 된다.
        val oldPre = old.preconditions.toSet()
        val newPre = new.preconditions.toSet()
        (newPre - oldPre).forEach {
            out += shrink(location, "사전 조건 추가: $where 에 ${it.subject} requires ${it.requires}", old, registry, skipped)
        }
        (oldPre - newPre).forEach {
            out += Finding(id, Severity.WARNING, "사전 조건 제거: $where 의 ${it.subject} requires ${it.requires} — 확장이다", location)
        }

        when {
            needsMajor.isNotEmpty() -> out += Finding(
                id, Severity.ERROR,
                "$where 는 major 증가가 필요한 변경인데 major가 그대로다: ${needsMajor.joinToString("; ")}",
                location,
            )

            needsMinor.isNotEmpty() && new.minor <= old.minor -> out += Finding(
                id, Severity.ERROR,
                "$where 는 minor 증가가 필요한 변경인데 minor가 ${old.minor} → ${new.minor}다: " +
                    needsMinor.joinToString("; "),
                location,
            )
        }

        // skills 안의 파라미터 아닌 필드는 compareProjection이 skills를 빼므로
        // 여기서 보지 않으면 어느 쪽도 보지 않는다(실측: YES → NO가 완전 침묵).
        // §5.2가 분류하지 않으므로 막지 않되 침묵시키지도 않는다.
        if (old.pauseSupport != new.pauseSupport) {
            out += supportChange(location, "$where 의 pause_support", old.pauseSupport, new.pauseSupport)
        }
        if (old.cancelSupport != new.cancelSupport) {
            out += supportChange(location, "$where 의 cancel_support", old.cancelSupport, new.cancelSupport)
        }

        out += narrowing(location, where, oldParams, newParams)
        return out
    }

    private fun supportChange(location: String, what: String, from: String, to: String): Finding {
        val withdrawn = from == "YES" && to != "YES"
        return Finding(
            id, Severity.WARNING,
            "$what 가 $from → $to" +
                if (withdrawn) " — 능력 철회다. §5.2가 분류하지 않아 막지 않는다" else "",
            location,
        )
    }

    /**
     * 사양이 분류하지 않는 제약 축소. 막지 않고 남긴다 — 사양과 코드가
     * 갈라지지 않게 하되, 조용히 넘기지도 않는다(§15).
     */
    private fun narrowing(
        location: String,
        where: String,
        oldParams: Map<String, ProfileDocument.ParameterEntry>,
        newParams: Map<String, ProfileDocument.ParameterEntry>,
    ): List<Finding> =
        (oldParams.keys intersect newParams.keys).mapNotNull { key ->
            val o = oldParams.getValue(key)
            val n = newParams.getValue(key)
            // 지역 변수로 받는다. ProfileDocument가 다른 모듈로 나간 뒤로는
            // nullable val을 스마트 캐스트할 수 없다 — 같은 모듈에서는 되고
            // 모듈 경계를 넘으면 안 된다(공개 API 프로퍼티라 값이 바뀔 수
            // 있다고 컴파일러가 본다).
            val oMaxLength = o.maxLength
            val nMaxLength = n.maxLength
            val oMin = o.minValue
            val nMin = n.minValue
            val oMax = o.maxValue
            val nMax = n.maxValue

            val reasons = buildList {
                if (o.allowedValues.isNotEmpty() && !n.allowedValues.containsAll(o.allowedValues)) {
                    add("허용값이 줄었다 (${o.allowedValues} → ${n.allowedValues})")
                }
                if (oMaxLength != null && nMaxLength != null && nMaxLength < oMaxLength) {
                    add("max_length가 $oMaxLength → $nMaxLength")
                }
                if (oMin != null && nMin != null && nMin > oMin) {
                    add("min_value가 $oMin → $nMin")
                }
                if (oMax != null && nMax != null && nMax < oMax) {
                    add("max_value가 $oMax → $nMax")
                }
            }
            if (reasons.isEmpty()) {
                null
            } else {
                Finding(
                    id, Severity.WARNING,
                    "$where.$key 의 제약이 좁아졌다: ${reasons.joinToString("; ")} " +
                        "— §5.2가 분류하지 않는 변경이라 막지 않는다",
                    location,
                )
            }
        }

    /**
     * `inPlace`가 흡수하지 못한 `optional_fields` 변경.
     *
     * 스킬 파라미터와 대응하지 않는 경로가 REQUIRED가 되는 경우가 특히
     * 나쁘다 — §5.3의 대표 예시(`task.parameters.approach_vector`)가 정확히
     * 그것이고, 어느 스킬의 major에도 붙일 수 없어 버전으로 흡수할 수 없다.
     */
    private fun optionalFieldChanges(
        base: ProfileDocument,
        head: ProfileDocument,
        newlyRequired: Set<String>,
    ): List<Finding> {
        val out = mutableListOf<Finding>()
        val location = head.locationOf("/optional_fields")
        val allKeys = head.skills.flatMap { s -> s.parameters.map { it.key } }.toSet()

        newlyRequired
            .filterNot { path -> allKeys.any { path.endsWith(".$it") } }
            .forEach {
                out += Finding(
                    id, Severity.ERROR,
                    "optional_fields의 '$it' 가 REQUIRED가 됐다 — 보내지 않는 클라이언트가 " +
                        "핸드셰이크에서 걸린다(§5.3). 어느 스킬 파라미터와도 대응하지 않아 " +
                        "major로 흡수할 수 없다",
                    location,
                )
            }

        val gone = base.optionalFields.map { it.parameterPath }.toSet() -
            head.optionalFields.map { it.parameterPath }.toSet()
        gone.forEach {
            out += Finding(
                id, Severity.WARNING,
                "optional_fields에서 '$it' 선언이 사라졌다 — 선택 필드 지원 철회다",
                location,
            )
        }

        return out
    }

    // ── 축소와 원장 조회

    private fun shrink(
        location: String,
        what: String,
        old: ProfileDocument.SkillEntry,
        registry: LedgerQuery?,
        skipped: MutableSet<Resource>,
    ): List<Finding> {
        if (registry == null) {
            skipped += Resource.REGISTRY
            return listOf(
                Finding(
                    id, Severity.WARNING,
                    "축소로 분류됐다 — $what. 원장이 없어 §9.3의 두 조회를 하지 못했다",
                    location,
                ),
            )
        }

        val consumers = registry.activeConsumers(old.skillType, old.major)
        val inflight = registry.inflightTasks(old.skillType, old.major)

        val unknown = listOfNotNull(
            (consumers as? LedgerAnswer.NotObservable)?.let { "소비자 수: ${it.reason}" },
            (inflight as? LedgerAnswer.NotObservable)?.let { "비종착 태스크 수: ${it.reason}" },
        )
        if (unknown.isNotEmpty()) {
            // **0이 아니다.** 브로커 구독이 끊겼을 뿐 소비자는 살아 있을 수 있다.
            skipped += Resource.REGISTRY
            return listOf(
                Finding(
                    id, Severity.WARNING,
                    "축소로 분류됐다 — $what. 원장이 관측하지 못했다: ${unknown.joinToString("; ")}",
                    location,
                ),
            )
        }

        val observedConsumers = consumers as LedgerAnswer.Observed
        val observedInflight = inflight as LedgerAnswer.Observed

        if (observedConsumers.count == 0 && observedInflight.count == 0) {
            // 관측했다는 사실 자체가 산출물이다. PASS 한 줄만 남으면
            // 게이트가 무엇을 승인했는지 아무도 모른다.
            return listOf(
                Finding(
                    id, Severity.WARNING,
                    "축소가 승인됐다 — $what. §9.3 진입 조건 충족: active 소비자 0, " +
                        "비종착 태스크 0 (관측 시각 ${observedConsumers.asOf})",
                    location,
                ),
            )
        }

        return listOf(
            Finding(
                id, Severity.ERROR,
                "축소가 거부됐다 — $what. §9.3의 진입 조건이 충족되지 않았다: " +
                    "active 소비자 ${observedConsumers.count}, 비종착 태스크 ${observedInflight.count}",
                location,
            ),
        )
    }

    // ── 안전망

    /**
     * 타입 접근자가 보는 것은 `skills`와 `optional_fields`뿐이다. 투영에는
     * 그 밖에도 `exclusive_control_required`·`publish_interval`·
     * `protocol_limits`가 있고, 그중 첫째는 켜지는 것만으로 실질적 파괴다
     * (§4.9 — 소비자는 자신이 유일한 명령자임을 전제해야 한다).
     *
     * 비투영 필드는 [ProfileDocument.projection]이 이미 뺐으므로 소요시간
     * 조정은 여기 걸리지 않는다.
     */
    private fun compareProjection(base: ProfileDocument, head: ProfileDocument): List<Finding> {
        val oldTree = base.projection()
        val newTree = head.projection()

        return (oldTree.fieldNames().asSequence().toSet() + newTree.fieldNames().asSequence())
            .filter { it !in HANDLED_ELSEWHERE }
            .filter { oldTree.get(it) != newTree.get(it) }
            .map { field ->
                Finding(
                    id, Severity.WARNING,
                    "투영 필드가 바뀌었다: $field (${oldTree.get(field)} → ${newTree.get(field)})" +
                        if (field == EXCLUSIVE) {
                            " — 소비자는 이제 자신이 유일한 명령자임을 전제해야 한다(§4.9)"
                        } else {
                            ""
                        },
                    head.locationOf("/$field"),
                )
            }
    }

    private fun requiredPaths(doc: ProfileDocument): Set<String> =
        doc.optionalFields.filter { it.support == "REQUIRED" }.map { it.parameterPath }.toSet()

    private companion object {
        const val EXCLUSIVE = "exclusive_control_required"

        /** 타입 접근자로 이미 자세히 본 것들. 투영 diff가 중복으로 말하지 않는다. */
        val HANDLED_ELSEWHERE = setOf("skills", "optional_fields", "vendor", "model", "revision")
    }
}
