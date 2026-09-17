package dev.picasso.adapter.core

/**
 * 자리 이름 하나가 **누구의 것이고, 어느 지도 판에서 배운 것인가**.
 *
 * ## 좌표가 없다. 일부러 없다
 *
 * ADR 34 가 매핑 표의 소유를 어댑터 밖으로 냈고 ADR 35 가 해석을 기체 세계 모델에 맡겼다. 여기가 더하는
 * 것은 그 해석을 **언제 배운 것인지**뿐이다 — 지도가 갱신되거나 개체가 교체되면 같은 이름이 다른 자리를
 * 가리키는데, 그 사실을 아는 자리가 이 저장소에 하나도 없었다(§15.155).
 *
 * 좌표 칸을 두지 않은 이유는 소유권이 아니라 **검증 가능성**이다. 이 저장소에는 좌표·프레임 개념이 없고
 * 미믹에 기하가 없어 좌표 결속을 틀리게 만들 방법이 없다 — 항상 통과하는 시험은 아무것도 보증하지 않는다.
 * 판 이름은 다르다. 맞대 볼 토큰이라 어긋나게 만들 수 있고, 그래서 시뮬레이션에서 진짜로 검증된다.
 *
 * @param owner 이 이름의 값을 참으로 유지하는 쪽. 기체 세계 모델이거나 상위 사이트 정본이다(ADR 34 §4)
 * @param mapVersion 이 결속이 배운 지도 판. 활성 판과 다르면 이름을 풀지 않는다
 */
data class SiteBinding(val name: String, val owner: String, val mapVersion: String)

/**
 * 지금 활성인 지도 판이 무엇인가 — **셋으로 가른다. 둘로 접으면 거짓이 된다.**
 *
 * | 상태 | 뜻 | 접었을 때 |
 * |---|---|---|
 * | [NotConfigured] | 이 배치에 정본이 **없다** | 정본을 안 붙인 현장이 통째로 멈춘다 |
 * | [Known] | 물어봤고 활성 판은 이것이다 | — |
 * | [Unavailable] | **물어보지 못했다** | 모르는 것이 «맞다» 로 읽혀 옛 결속이 그대로 나간다 |
 *
 * 마지막이 이 타입의 이유다. 정본이 죽었을 때 «활성 판을 모른다» 를 «판이 같다» 로 접으면, 지도가 바뀐
 * 뒤에도 명령이 계속 나가고 로그에는 성공이 남는다. [SiteNames] 의 `Unavailable` 과 같은 규율이다.
 */
sealed interface ActiveMap {

    /** 이 배치에 결속 정본이 붙어 있지 않다. 검사할 것이 없고, 없다는 사실은 진단에 보인다. */
    data object NotConfigured : ActiveMap

    /** 물어봤다. */
    data class Known(val version: String) : ActiveMap

    /** 물어보려다 실패했다. **«같다» 가 아니다.** */
    data class Unavailable(val reason: String) : ActiveMap
}

/**
 * 결속 정본에 묻는 자리. **정본은 이 저장소 밖에 산다** — 여기 있는 것은 묻는 면뿐이다.
 *
 * **공통 계층은 이 타입을 참조할 수 없다.** 게이트 검사 9번이 그것을 집행한다 — 자리 이름이 어느 기종의
 * 무엇에 묶이는지는 어댑터 경계의 지식이고, 공통 계층이 그것을 알면 기종 비인지가 한 겹 무너진다.
 */
interface SiteBindingSource {

    /** 지금 활성인 지도 판. */
    fun activeMap(): ActiveMap

    /** 이 이름의 결속. 정본이 그 이름을 모르면 `null` 이다 — **«없다» 이지 «아무 판이나 좋다» 가 아니다.** */
    fun binding(name: String): SiteBinding?

    /** 정본을 안 붙인 배치. 기본값이며, 그것이 결정임을 [ActiveMap.NotConfigured] 가 말한다. */
    object None : SiteBindingSource {
        override fun activeMap(): ActiveMap = ActiveMap.NotConfigured
        override fun binding(name: String): SiteBinding? = null
    }
}

/**
 * 자리 이름이 지금 판에서 유효한가 — **판정만 한다. 이름을 풀지 않는다.**
 *
 * 푸는 것은 기체의 일이고(ADR 35), 여기는 **풀어도 되는지**를 앞서 묻는다. 순서가 뒤집혀 풀고 나서 판을
 * 보면 이미 옛 좌표로 움직인 뒤다.
 */
object SiteBindingCheck {

    /** 판정의 답. 통과면 `null` 이고, 막을 이유가 있으면 그 이유다. */
    fun refusalFor(names: List<String>, source: SiteBindingSource): Refusal? {
        if (names.isEmpty()) return null
        return when (val active = source.activeMap()) {
            // 검사할 정본이 없다. **없는 것을 어긴 것으로 읽지 않는다.**
            ActiveMap.NotConfigured -> null
            is ActiveMap.Unavailable -> Refusal.SITE_BINDING_UNVERIFIABLE
            is ActiveMap.Known -> names.firstNotNullOfOrNull { name ->
                val binding = source.binding(name) ?: return@firstNotNullOfOrNull Refusal.SITE_BINDING_ABSENT
                if (binding.mapVersion != active.version) Refusal.SITE_BINDING_STALE else null
            }
        }
    }

    /** 거절에 붙일 사정. 운영자가 **무엇을 해야 하는지**가 셋 다 다르므로 문장도 다르다. */
    fun detailFor(refusal: Refusal, names: List<String>, source: SiteBindingSource): String {
        val active = source.activeMap()
        return when (refusal) {
            Refusal.SITE_BINDING_UNVERIFIABLE ->
                "결속 정본에 활성 지도 판을 못 물어봤다: ${(active as? ActiveMap.Unavailable)?.reason}. " +
                    "판을 모르는 채로는 자리를 풀지 않는다 — 정본을 고치십시오"
            Refusal.SITE_BINDING_ABSENT ->
                "결속 정본이 모르는 자리 이름이다: ${names.filter { source.binding(it) == null }}. " +
                    "이 판에 등록하십시오 — 오타인지 누락인지 이 층은 모른다"
            Refusal.SITE_BINDING_STALE -> {
                val version = (active as? ActiveMap.Known)?.version
                val stale = names.mapNotNull { source.binding(it) }.filter { it.mapVersion != version }
                "옛 지도 판에서 배운 자리다: " +
                    stale.joinToString { "'${it.name}'(${it.mapVersion}, 소유 ${it.owner})" } +
                    " · 활성 판 $version. 재등록하기 전까지 이 자리로 보내지 않는다"
            }
            else -> error("결속 검사가 내지 않는 거절이다: $refusal")
        }
    }
}
