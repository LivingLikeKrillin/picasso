package dev.picasso.middleware.mission

import dev.picasso.contracts.v1.SkillCatalog
import dev.picasso.middleware.DeadlineOutcome
import dev.picasso.middleware.FloorOwner
import dev.picasso.middleware.FloorOwnership
import java.time.Instant

/**
 * 활성화 거부 하나 — **종류와 후속 행동**으로 보인다(운영 관리 화면 설계 제안 §8.4).
 *
 * 사유 텍스트만 주면 읽는 쪽이 문자열 대조에 기대므로 종류를 열거형으로 둔다. 후속 행동과 해결 담당은 종류가 정한다
 * ([MissionRefusalKind]) — 종류를 가르는 기준이 곧 후속 행동이기 때문이다.
 *
 * @param nodeId 막힌 노드. 정의 전체의 문제(읽을 수 없음)면 널이다.
 * @param observed 지금 값. 없는 것이 문제면 «없음» 이다.
 * @param expected 통과에 필요한 값.
 * @param checkedAt 마지막 확인 시각 — 활성화를 시도한 시각이다.
 * @param basisVersion 근거 버전 — 어느 버전의 현장 설정으로 판정했는가. 활성화 검사는 현장 설정 버전과 무관한 판정이라
 *   «해당 없음»(널)이다. 현장 설정에 버전이 생기면(S3c) 그 번호가 온다.
 */
data class MissionRefusal(
    val kind: MissionRefusalKind,
    val nodeId: String?,
    val observed: String,
    val expected: String,
    val checkedAt: Instant,
    val basisVersion: Int? = null,
) {
    /** 해결 담당 — 화면 안의 누구인가, 화면 밖인가. */
    val owner: RefusalOwner get() = kind.owner

    /** 바로 갈 작업 하나. */
    val nextAction: String get() = kind.nextAction
}

/** 거부를 푸는 쪽. */
enum class RefusalOwner {
    /** 화면 안의 시운전·통합 엔지니어 — 임무 버전 작성, 신호 사양, 기체 배치를 맡는다. */
    ENGINEER,

    /** 화면 밖 — 자원 소유 레지스터는 현장의 것이고 이 저장소 밖에 산다(§15.154). */
    OUTSIDE_CONSOLE,
}

/** 거부 종류. **후속 행동이 다르면 종류가 다르다.** */
enum class MissionRefusalKind(val owner: RefusalOwner, val nextAction: String) {
    /** 문서를 읽을 수 없다 — 모르는 키, 빠진 칸, 형이 틀린 값, 중복 키, 문법. */
    UNREADABLE(RefusalOwner.ENGINEER, "정의 JSON 의 틀린 칸을 고친다"),

    /** 노드 id 가 정의 안에서 겹친다. 리비전과 작업 응답이 단위 id 로 접으므로 겹치면 한쪽이 사라진다. */
    DUPLICATE_NODE_ID(RefusalOwner.ENGINEER, "노드 id 를 겹치지 않게 고친다"),

    /** 기한 검사 — 대기 노드에 양의 기한이 없거나 기한 뒤 상태가 허용 밖이다. */
    DEADLINE_INVALID(RefusalOwner.ENGINEER, "대기 노드의 기한 칸을 고친다"),

    /** 신호 검사 — 참조한 신호가 신호 사양에 없다. */
    SIGNAL_NOT_IN_SPEC(RefusalOwner.ENGINEER, "신호 이름을 고치거나 신호 사양에 더한다"),

    /** 신호 검사 — 기대 값이 그 신호의 종류로 읽힐 수 없는 값이다(참거짓 신호에 `true`·`false` 밖의 값). 영영 안 끝나는 대기다. */
    SIGNAL_VALUE_INVALID(RefusalOwner.ENGINEER, "기대 값을 신호 종류에 맞게 고친다"),

    /** 자원 검사 — 대기 신호의 자리에 바닥 소유자가 없다(`Unowned`). 관문과 같은 규칙이다. */
    FLOOR_UNOWNED(RefusalOwner.OUTSIDE_CONSOLE, "그 자리의 바닥 소유를 선언한다"),

    /** 스킬 검사 — 계약 카탈로그에 없는 스킬이다. */
    SKILL_NOT_IN_CONTRACT(RefusalOwner.ENGINEER, "스킬 이름을 계약 카탈로그의 이름으로 고친다"),

    /** 스킬 검사 — 계약에는 있으나 현장 기체가 제공하지 않는다. */
    SKILL_NOT_ON_SITE(RefusalOwner.ENGINEER, "그 스킬을 제공하는 기체를 현장에 둔다"),

    /** 안전 검사 — 안전 신호를 기다린다. 안전 기능은 이 소프트웨어 계약을 거치지 않는다(ADR 32). */
    SAFETY_SIGNAL_WAIT(RefusalOwner.ENGINEER, "대기 노드를 안전 신호가 아닌 신호로 바꾼다"),
}

/**
 * 검증기 — 임무 정의가 활성화될 수 있는가. **라이브러리다** — 같은 규칙으로 화면이 저장 전에 막고 활성화가 막는다.
 *
 * 검사는 다섯이고(기한·신호·자원·스킬·안전) 그 앞에 노드 id 중복을 본다. **모든 거부를 모아 낸다** — 하나만 알리면
 * 고치고 다시 내고 또 거부되는 왕복이 생긴다.
 *
 * 함수 이름은 관문의 판정 이름과 겹치지 않게 둔다 — 자리 대조 시험이 이름으로 판정의 자리를 찾는다.
 *
 * ## 보지 않는 것
 *
 * - **파라미터 이름.** 오타는 실행 때 하위가 `PARAMETER_INVALID` 로 거부한다
 * - **작업 지시에서 오는 자리**(슬롯·제시 자리). 정의에 고정으로 적힌 것이 아니라 실행 때 관문이 본다
 * - **작업 지시의 설비 id 와 대기 노드 id 의 겹침.** 설비 id 는 작업 지시가 오기 전에는 모른다
 */
object MissionValidator {

    /**
     * @param signals 현장 신호 사양.
     * @param floors 바닥 소유. 관문이 받는 것과 같은 것을 준다.
     * @param siteSkills 현장 기체들이 제공하는 스킬 이름의 합.
     * @param at 활성화를 시도한 시각 — 거부의 마지막 확인 시각이 된다.
     */
    fun validate(
        definition: MissionDefinition,
        signals: List<SignalSpec>,
        floors: FloorOwnership,
        siteSkills: Set<String>,
        at: Instant,
    ): List<MissionRefusal> {
        val byName = signals.associateBy { it.name }
        return duplicateNodeIds(definition, at) +
            deadlineRefusals(definition, at) +
            signalRefusals(definition, byName, at) +
            floorRefusals(definition, byName, floors, at) +
            skillRefusals(definition, siteSkills, at) +
            safetyRefusals(definition, byName, at)
    }

    /** 계약 카탈로그의 스킬 이름 전부 — 기술자의 `skill_type_name` 옵션에서 읽는다. */
    val contractSkills: Set<String> by lazy {
        SkillCatalog.getDescriptor().messageTypes
            .mapNotNull { it.options.getExtension(SkillCatalog.skillTypeName).takeIf { name -> !name.isNullOrEmpty() } }
            .toSet()
    }

    private fun duplicateNodeIds(definition: MissionDefinition, at: Instant): List<MissionRefusal> =
        definition.steps.groupingBy { it.id }.eachCount().filterValues { it > 1 }.map { (id, n) ->
            MissionRefusal(MissionRefusalKind.DUPLICATE_NODE_ID, id, "$n 번 나온다", "정의 안에서 한 번", at)
        }

    private fun waits(definition: MissionDefinition) = definition.steps.filterIsInstance<WaitStep>()

    /** 기한 — 대기 노드마다 양의 기한과 허용된 기한 뒤 상태. 파서가 아니라 여기서 본다. */
    private fun deadlineRefusals(definition: MissionDefinition, at: Instant): List<MissionRefusal> =
        waits(definition).flatMap { wait ->
            val allowed = DeadlineOutcome.entries.joinToString(" 또는 ") { it.name }
            listOfNotNull(
                wait.deadlineSeconds.let { seconds ->
                    if (seconds != null && seconds > 0) {
                        null
                    } else {
                        MissionRefusal(
                            MissionRefusalKind.DEADLINE_INVALID, wait.id,
                            "deadlineSeconds=${seconds ?: "없음"}", "양의 정수 초", at,
                        )
                    }
                },
                wait.onDeadline.let { outcome ->
                    if (DeadlineOutcome.entries.any { it.name == outcome }) {
                        null
                    } else {
                        MissionRefusal(MissionRefusalKind.DEADLINE_INVALID, wait.id, "onDeadline=${outcome ?: "없음"}", allowed, at)
                    }
                },
            )
        }

    /** 신호 — 참조한 신호가 사양에 있는가, 기대 값이 그 신호의 종류로 읽히는가. */
    private fun signalRefusals(definition: MissionDefinition, byName: Map<String, SignalSpec>, at: Instant): List<MissionRefusal> =
        waits(definition).mapNotNull { wait ->
            val spec = byName[wait.signal]
                ?: return@mapNotNull MissionRefusal(
                    MissionRefusalKind.SIGNAL_NOT_IN_SPEC, wait.id, wait.signal,
                    "신호 사양의 이름 중 하나(${byName.keys.sorted().joinToString()})", at,
                )
            if (spec.kind == SignalKind.BOOLEAN && wait.expect !in BOOLEAN_VALUES) {
                MissionRefusal(MissionRefusalKind.SIGNAL_VALUE_INVALID, wait.id, wait.expect, BOOLEAN_VALUES.joinToString(" 또는 "), at)
            } else {
                null
            }
        }

    /**
     * 자원 — 정의에 고정으로 적힌 자리(대기 신호의 자리)의 바닥 소유. **관문과 같은 규칙이다** — `Unowned` 만 거부하고
     * `Declared`·`NotDeclared` 는 통과한다. 선언 없음을 거부로 읽으면 레지스터를 안 붙인 현장에서 자리가 적힌 신호를 쓰는
     * 정의가 모두 거부된다. 자리가 없는 신호는 건너뛴다.
     */
    private fun floorRefusals(
        definition: MissionDefinition,
        byName: Map<String, SignalSpec>,
        floors: FloorOwnership,
        at: Instant,
    ): List<MissionRefusal> = waits(definition).mapNotNull { wait ->
        val where = byName[wait.signal]?.location ?: return@mapNotNull null
        if (floors.ownerOf(where) != FloorOwner.Unowned) return@mapNotNull null
        MissionRefusal(MissionRefusalKind.FLOOR_UNOWNED, wait.id, "$where: 소유자 없음", "$where: 소유자 선언", at)
    }

    /** 스킬 — 계약에 있는 스킬인가, 현장 기체가 제공하는가. 둘은 다른 물음이다. */
    private fun skillRefusals(definition: MissionDefinition, siteSkills: Set<String>, at: Instant): List<MissionRefusal> =
        definition.steps.filterIsInstance<UnitStep>().mapNotNull { unit ->
            when {
                unit.skill !in contractSkills -> MissionRefusal(
                    MissionRefusalKind.SKILL_NOT_IN_CONTRACT, unit.id, unit.skill, "계약 카탈로그의 스킬 이름", at,
                )
                unit.skill !in siteSkills -> MissionRefusal(
                    MissionRefusalKind.SKILL_NOT_ON_SITE, unit.id, unit.skill,
                    "현장 기체가 제공하는 스킬(${siteSkills.sorted().joinToString()})", at,
                )
                else -> null
            }
        }

    /** 안전 — 안전 신호를 기다리는 대기 노드가 없는가(ADR 32). */
    private fun safetyRefusals(definition: MissionDefinition, byName: Map<String, SignalSpec>, at: Instant): List<MissionRefusal> =
        waits(definition).mapNotNull { wait ->
            if (byName[wait.signal]?.safety != true) return@mapNotNull null
            MissionRefusal(MissionRefusalKind.SAFETY_SIGNAL_WAIT, wait.id, "${wait.signal}: 안전 신호", "안전 신호가 아닌 신호", at)
        }

    private val BOOLEAN_VALUES = listOf("true", "false")
}
