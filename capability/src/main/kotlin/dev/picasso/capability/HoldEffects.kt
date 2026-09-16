package dev.picasso.capability

import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.SkillCatalog

/**
 * 스킬 종류가 잔여 물리 상태에 남기는 **효과**(설계안 §1.1) — 계약 카탈로그의 메시지 옵션에서 읽는다.
 *
 * `grasps_object` 는 수행하는 동안 쥔다는 뜻이고, `releases_object` 는 끝날 때 놓는다는 뜻이다. 둘 다 참인
 * `pick_place` 는 끝난 뒤 빈손이다. 어느 것도 아닌 스킬(참조만 하는 `inspect`, 이동)은 파지를 바꾸지 않고,
 * **카탈로그에 없는 단위**(플릿 계약의 `transport`)도 바꾸지 않는다 — 모르는 것을 바꿨다고 말하지 않는다.
 *
 * 소비자(미들웨어)가 계획 시점에 단위 사슬을 따라가며 조건과 대조하는 데 쓴다. 미믹의 `ObjectReferences.grasps`
 * 는 같은 옵션을 읽는 발신자 쪽 판이다.
 */
object HoldEffects {

    private val byName: Map<String, Pair<Boolean, Boolean>> by lazy {
        SkillCatalog.getDescriptor().messageTypes
            .mapNotNull { message ->
                val name = message.options.getExtension(SkillCatalog.skillTypeName)
                if (name.isNullOrEmpty()) null
                else name to (message.options.getExtension(SkillCatalog.graspsObject) to message.options.getExtension(SkillCatalog.releasesObject))
            }
            .toMap()
    }

    fun grasps(skillType: String): Boolean = byName[skillType]?.first ?: false
    fun releases(skillType: String): Boolean = byName[skillType]?.second ?: false

    /** 이 스킬이 끝난 뒤의 파지 — 놓으면 빈손, 쥐고 안 놓으면 든 채, 그 밖은 그대로. */
    fun after(skillType: String, before: HoldState): HoldState = when {
        releases(skillType) -> HoldState.newBuilder().setKind(HoldKind.HOLD_KIND_EMPTY).build()
        grasps(skillType) -> HoldState.newBuilder().setKind(HoldKind.HOLD_KIND_HOLDING).build()
        else -> before
    }

    /**
     * 선언된 효과와 마지막 관측이 어긋났는가(설계안 §5.1).
     *
     * **관측이 없거나 볼 수 없으면 판정하지 않는다.** 효과는 관측이 있을 때만 미결을 줄인다 — 관측 없이
     * 선언만으로 판정하면 선언을 근거로 현실을 단정하는 것이 되고, 관측 경로가 죽었을 때 고장난 기체가
     * 멀쩡해 보인다(§5.2). 그래서 `NOT_OBSERVABLE` 과 침묵은 둘 다 `null` 이다.
     *
     * 효과를 선언하지 않은 스킬도 `null` 이다 — [after] 가 관측을 그대로 돌려주므로 어긋날 것이 없다.
     */
    fun mismatch(skillType: String, observed: HoldKind): HoldMismatch? {
        if (observed != HoldKind.HOLD_KIND_EMPTY && observed != HoldKind.HOLD_KIND_HOLDING) return null
        return compare(after(skillType, HoldState.newBuilder().setKind(observed).build()).kind, observed)
    }

    /**
     * 기대와 관측을 맞대는 자리. [mismatch] 에서 갈라 둔 이유는 **카탈로그가 표의 한 줄을 아직 못 겨냥하기**
     * 때문이다 — 쥐고 놓지 않는 스킬이 없어 `PAYLOAD_LOST` 쪽은 카탈로그를 통해서는 도달할 수 없다.
     * 규칙을 대칭으로 두되 양쪽 다 시험이 닿게 한다. 닿지 않는 가지는 규칙이 아니라 주석일 뿐이다.
     */
    fun compare(expected: HoldKind, observed: HoldKind): HoldMismatch? = when {
        expected == observed -> null
        expected == HoldKind.HOLD_KIND_HOLDING && observed == HoldKind.HOLD_KIND_EMPTY -> HoldMismatch.PAYLOAD_LOST
        expected == HoldKind.HOLD_KIND_EMPTY && observed == HoldKind.HOLD_KIND_HOLDING -> HoldMismatch.INCOMPLETE_RELEASE
        else -> null
    }
}

/**
 * 효과와 관측이 어긋난 모양 둘(설계안 §5.1). **결함 통지가 없어도** 낼 수 있는 판정이며, 미결(`IN_DOUBT`)이
 * 운영자에게 «모른다» 를 전달하는 자리를 «손에 없다»·«아직 들고 있다» 로 바꾼다. 다음 행동이 갈린다(§5.3).
 */
enum class HoldMismatch {
    /** 들고 끝나야 하는데 빈손이다. */
    PAYLOAD_LOST,

    /** 놓고 끝나야 하는데 아직 들고 있다. */
    INCOMPLETE_RELEASE,
}
