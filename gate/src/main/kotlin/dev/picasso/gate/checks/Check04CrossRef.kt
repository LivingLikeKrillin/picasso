package dev.picasso.gate.checks

import dev.picasso.gate.CheckResult
import dev.picasso.gate.Finding
import dev.picasso.gate.GateCheck
import dev.picasso.gate.Resource
import dev.picasso.gate.Severity
import dev.picasso.gate.input.GateInput
import dev.picasso.gate.model.ContractIndex
import dev.picasso.profile.ProfileDocument
import dev.picasso.gate.model.SkillTypeDef

/**
 * 검사 4 — 프로파일과 계약 카탈로그의 **양방향** 대조(설계 §11.2).
 *
 * 한 방향만 보면 프로파일이 필수 파라미터를 빠뜨려도 통과한다 — 없는 것을
 * 선언하지 않았을 뿐이라 참조 무결성은 깨지지 않기 때문이다.
 *
 * 빈 스킬 배열은 여기서 보지 않는다. 스키마의 `skills.minItems: 1`이
 * 검사 3번에서 막는다.
 */
class Check04CrossRef : GateCheck {

    override val id = "4"
    override val name = "프로파일 ↔ proto 교차검증"
    override val requires = setOf(Resource.PROFILE_DOCUMENT, Resource.CONTRACT_DESCRIPTOR)

    override fun run(input: GateInput): CheckResult {
        val index = ContractIndex.from(input.descriptor!!)

        catalogIntegrity(index)?.let { return it }

        val findings = mutableListOf<Finding>()

        input.profiles.forEach { doc ->
            doc.skills.forEachIndexed { i, s ->
                val def = index.find(s.skillType, s.major)
                if (def == null) {
                    findings += Finding(
                        id, Severity.ERROR,
                        "계약에 없는 능력을 선언했다: ${s.skillType}@${s.major}. " +
                            "계약이 아는 것: ${index.skillTypes().sorted().joinToString(", ")}",
                        doc.locationOf("/skills/$i"),
                    )
                    return@forEachIndexed
                }

                findings += checkMinor(doc, i, s, def)
                findings += forward(doc, i, s, def)
                findings += backward(doc, i, s, def)
            }

            findings += optionalFieldRefs(doc)
        }

        return if (findings.isEmpty()) CheckResult.Passed(id)
        else CheckResult.Failed(id, findings)
    }

    /**
     * 카탈로그 자체가 성하지 않으면 아래 판정이 전부 의심스러워진다.
     * 특히 **빈 카탈로그를 그냥 두면 소견이 프로파일을 탓한다** — "계약이
     * 아는 것: "(뒤가 빔)을 보고 사람이 프로파일을 들여다보게 된다.
     */
    private fun catalogIntegrity(index: ContractIndex): CheckResult? {
        if (index.isEmpty()) {
            return CheckResult.Failed(
                id,
                listOf(
                    Finding(
                        id, Severity.ERROR,
                        "계약 카탈로그가 비었다 — 디스크립터에 skill_type_name 옵션을 단 메시지가 " +
                            "하나도 없다. 프로파일이 아니라 디스크립터가 낡았거나 잘못 만들어졌다 " +
                            "(파일 ${index.scannedFiles}개를 훑었다)",
                        CATALOG,
                    ),
                ),
            )
        }

        val findings = mutableListOf<Finding>()

        index.duplicates().forEach { (name, major) ->
            findings += Finding(
                id, Severity.ERROR,
                "계약 카탈로그에 $name@$major 가 둘 이상 있다 " +
                    "— 설계 §8.3이 (name, major)를 유일하게 규정한다",
                CATALOG,
            )
        }

        index.all().forEach { def ->
            def.unreachableParameters().forEach { p ->
                findings += Finding(
                    id, Severity.ERROR,
                    "어떤 minor에서도 나타날 수 없는 파라미터다: ${def.name}@${def.major}.${p.key} " +
                        "— since_minor=${p.sinceMinor}인데 max_minor=${def.maxMinor}다",
                    def.protoMessage,
                )
            }
        }

        return if (findings.isEmpty()) null else CheckResult.Failed(id, findings)
    }

    /**
     * 선언한 minor가 계약의 최신을 넘지 않는가.
     *
     * `maxMinorDeclared`가 거짓이면 계약이 아직 minor를 정하지 않은 것이라
     * 판정하지 않는다. 미선언을 0으로 읽으면 거짓 실패가 난다.
     */
    private fun checkMinor(
        doc: ProfileDocument,
        i: Int,
        s: ProfileDocument.SkillEntry,
        def: SkillTypeDef,
    ): List<Finding> =
        if (def.maxMinorDeclared && s.minor > def.maxMinor) {
            listOf(
                Finding(
                    id, Severity.ERROR,
                    "${s.skillType}@${s.major}의 minor ${s.minor}가 계약의 최신 ${def.maxMinor}를 넘는다",
                    doc.locationOf("/skills/$i/minor"),
                ),
            )
        } else {
            emptyList()
        }

    /** 프로파일 → 계약. 선언한 것이 계약에 있고 타입과 필수 여부가 같은가. */
    private fun forward(
        doc: ProfileDocument,
        i: Int,
        s: ProfileDocument.SkillEntry,
        def: SkillTypeDef,
    ): List<Finding> {
        val valid = def.parametersAt(s.minor).associateBy { it.key }
        val out = mutableListOf<Finding>()

        s.parameters.forEachIndexed { j, p ->
            val contractParam = valid[p.key]
            if (contractParam == null) {
                val laterOnly = def.parameters.firstOrNull { it.key == p.key }
                out += Finding(
                    id, Severity.ERROR,
                    if (laterOnly != null) {
                        "${s.skillType}@${s.major}.${s.minor}에서 '${p.key}'를 쓸 수 없다 " +
                            "— 계약에서 since_minor=${laterOnly.sinceMinor}에 생겼다"
                    } else {
                        "계약에 없는 파라미터를 선언했다: ${s.skillType}@${s.major}.${p.key}"
                    },
                    doc.locationOf("/skills/$i/parameters/$j"),
                )
                return@forEachIndexed
            }

            if (p.valueType != contractParam.valueType.name) {
                out += Finding(
                    id, Severity.ERROR,
                    "값 타입이 계약과 다르다: ${s.skillType}@${s.major}.${p.key} " +
                        "— 프로파일 ${p.valueType}, 계약 ${contractParam.valueType}",
                    doc.locationOf("/skills/$i/parameters/$j/value_type"),
                )
            }

            // 빠뜨리는 것만 거짓말이 아니다. 키는 있으므로 양방향 대조를 둘 다
            // 통과하는데, 클라이언트는 그것 없이 태스크를 보내도 된다고 믿는다.
            // 반대 방향(계약 선택 → 프로파일 필수)은 §5.3의 "필수 선택 필드"가
            // 정당한 선언이므로 막지 않는다.
            if (p.optional && !contractParam.isOptional) {
                out += Finding(
                    id, Severity.ERROR,
                    "계약이 필수라 한 파라미터를 선택으로 선언했다: " +
                        "${s.skillType}@${s.major}.${p.key} — 계약 is_optional=false",
                    doc.locationOf("/skills/$i/parameters/$j/optional"),
                )
            }
        }

        return out
    }

    /**
     * 계약 → 프로파일. **이 방향이 없으면 필수 파라미터 누락이 통과한다.**
     * 선택 파라미터 누락은 정상이다 — 프로파일은 기종이 무엇을 지원하는지를
     * 말하는 것이고, 선택을 지원하지 않는 기종이 있는 것이 당연하다.
     */
    private fun backward(
        doc: ProfileDocument,
        i: Int,
        s: ProfileDocument.SkillEntry,
        def: SkillTypeDef,
    ): List<Finding> {
        val declared = s.parameters.map { it.key }.toSet()
        return def.requiredParametersAt(s.minor)
            .filter { it.key !in declared }
            .map { missing ->
                Finding(
                    id, Severity.ERROR,
                    "필수 파라미터가 빠졌다: ${s.skillType}@${s.major}.${s.minor}의 " +
                        "'${missing.key}' — 계약에서 since_minor=${missing.sinceMinor}, 필수",
                    doc.locationOf("/skills/$i/parameters"),
                )
            }
    }

    /**
     * `optional_fields`가 실재하는 파라미터를 가리키는가. 스키마는 점표기
     * 패턴만 강제하므로 오타가 그대로 통과한다.
     */
    private fun optionalFieldRefs(doc: ProfileDocument): List<Finding> {
        val known = doc.skills.flatMap { s -> s.parameters.map { it.key } }.toSet()
        return doc.optionalFields.mapIndexedNotNull { k, f ->
            val key = f.parameterPath.substringAfterLast('.')
            if (key in known) {
                null
            } else {
                Finding(
                    id, Severity.ERROR,
                    "optional_fields가 선언되지 않은 파라미터를 가리킨다: ${f.parameterPath}",
                    doc.locationOf("/optional_fields/$k"),
                )
            }
        }
    }

    private companion object {
        const val CATALOG = "contracts/proto/picasso/v1/skill_catalog.proto"
    }
}
