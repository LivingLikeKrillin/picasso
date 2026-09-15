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
}
