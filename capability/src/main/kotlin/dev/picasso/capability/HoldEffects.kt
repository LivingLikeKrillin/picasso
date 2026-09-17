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
     * 이 단위가 **끝난 시점에** 기대되는 파지(설계안 §5, 중단 시점 기준). 기대할 것이 없으면 `null`.
     *
     * 종료가 성공이면 효과대로다 — 놓는 스킬은 빈손으로 끝나야 한다. 성공이 아니면 **어디까지 갔는지**가
     * 기대를 정한다: 쥐었다가 놓지 못한 채 끝났으면 든 채여야 한다. 쥔 적이 없으면 기대할 것이 없다.
     *
     * 종료 시점의 효과([after])만으로 기대를 잡으면 «쥐고 실패했는데 빈손» 이 정상으로 읽힌다 — 그것이
     * 설계안 §1.1 과 §10.1 둘째 시나리오가 겨냥한 바로 그 경우다.
     */
    fun expectedAtEnd(skillType: String, everHeld: Boolean, completed: Boolean): HoldKind? = when {
        !grasps(skillType) && !releases(skillType) -> null
        completed -> after(skillType, HoldState.getDefaultInstance()).kind
        everHeld -> HoldKind.HOLD_KIND_HOLDING
        else -> null
    }

    /**
     * 기대와 관측을 맞댄다.
     *
     * **관측이 없거나 볼 수 없으면 판정하지 않는다.** 효과는 관측이 있을 때만 미결을 줄인다 — 관측 없이
     * 선언만으로 판정하면 선언을 근거로 현실을 단정하는 것이 되고, 관측 경로가 죽었을 때 고장난 기체가
     * 멀쩡해 보인다(§5.2). 그래서 `NOT_OBSERVABLE` 과 침묵은 둘 다 `null` 이다.
     *
     * ## `null` 이 두 뜻을 진다
     *
     * **«판정했고 어긋남 없음» 과 «판정 못 함» 이 같은 값으로 나간다.** 지금 이 층의 소비자는 둘을
     * 같게 다루고(어느 쪽이든 사건을 열지 않는다), 읽는 사람은 번들의 `expectedHold`·`observedHold` 로
     * 구별한다. 그래서 가르지 않았다 — 소비자 없는 표면을 만들지 않는다(ADR 9).
     *
     * 접어 둔 것이 위험해지는 경우는 하나뿐이다: **구체 관측 값이 늘어나는 것.** 그때 «어긋남 없음» 이
     * 잘못 나가는데, 아래 `when` 이 `else` 를 안 쓰므로 그 전에 컴파일이 깨진다([isConcreteObservation]).
     */
    fun compare(expected: HoldKind, observed: HoldKind): HoldMismatch? {
        // 관측이 구체적이지 않으면 판정 자체가 없다. 부류는 한 자리에서만 정한다.
        //
        // ★**기대 쪽은 여기서 막지 않는다.** 막아 봤자 아래 `when` 이 같은 답을 내므로 결함을 주입해도
        //   아무 시험도 빨개지지 않는다 — 등가 변이다. 아무도 구별할 수 없는 검사는 들고 있지 않는다.
        if (!observed.isConcreteObservation) return null
        if (expected == observed) return null

        // ★**`else` 를 쓰지 않는다.** 구체 관측이 셋이 되는 날 어긋남의 모양도 둘로는 모자라고,
        //   그 사실이 여기서 컴파일 오류로 선다. `else` 로 접으면 새 값이 조용히 한쪽으로 분류된다.
        return when (expected) {
            HoldKind.HOLD_KIND_HOLDING -> HoldMismatch.PAYLOAD_LOST
            HoldKind.HOLD_KIND_EMPTY -> HoldMismatch.INCOMPLETE_RELEASE
            // 기대할 것이 없었다 — 관측이 무엇이든 판정하지 않는다.
            HoldKind.HOLD_KIND_NOT_OBSERVABLE, HoldKind.HOLD_KIND_UNSPECIFIED, HoldKind.UNRECOGNIZED -> null
        }
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
