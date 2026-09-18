package dev.picasso.middleware

import java.time.Instant

/** 승인을 누른 것이 사람인가 에이전트인가. **감사에 남는 값이고 지표를 가르는 축이다.** */
enum class ApproverKind { PERSON, AGENT }

/**
 * 승인을 누른 쪽(ADR 43).
 *
 * **위조 가능하다.** 이 값은 부인방지가 아니라 **조사 단서**다 — 레지스트리의 `actor` 와 같은 성질이고,
 * 같은 한계를 든다(§15.168). 서명을 요구하지 않는 이유는 요구할 수 있는 곳이 아직 없기 때문이며,
 * 없는 보증을 있는 척 적지 않는다.
 *
 * **기본값을 두지 않는다.** 누가 눌렀는지 안 적고도 승인이 되면 그것이 무기명 승인 경로이고,
 * 자원 소유 대장이 비워 두면 안 된다고 적은 자리가 바로 거기다.
 */
data class Approver(val id: String, val kind: ApproverKind)

/**
 * 선언된 조치 하나 — **어느 스킬을, 어떤 값으로**(ADR 44).
 *
 * 스킬 이름만으로는 범위가 아니다. `pick_place` 를 덮는 자격은 *"놓아도 된다"* 가 아니라 *"무엇이든
 * 어디에든 놓아도 된다"* 이고, 둘의 차이가 곧 이 자격이 존재하는 이유다. 그래서 값을 같이 적는다.
 *
 * **여기 적는 것은 사람이 미리 정할 수 있는 값뿐이다.** 어디에 놓을지는 현장의 상설 결정이라 미리
 * 적히지만, 지금 무엇을 들었는지는 기체가 말하는 사실이라 적을 수 없다 — 그 칸은 관측에서 온다
 * ([Entitlements] 아래의 값 출처 표).
 */
data class DeclaredAction(
    /** 계약의 스킬 이름. */
    val skillType: String,
    /**
     * 이 조치를 그 스킬로 보낼 때 실을 값. **관측에서 오는 칸을 여기 적으면 좁히기로만 쓰인다** —
     * 적힌 값과 관측이 어긋나면 승인이 거절된다. 덮어쓰지는 못한다.
     */
    val parameters: Map<String, String>,
)

/**
 * 한 승인자에게 **선언된** 자동 승인 자격(ADR 43·44).
 *
 * **계산되지 않는다.** 계산된 자격은 계산이 틀리면 조용히 넓어지고, 선언된 자격은 사람의 행위이므로
 * 감사 가능하고 만료를 걸 수 있다. 이 층은 선언을 읽고 대조할 뿐 만들지 않는다.
 *
 * **빈 집합은 «아무것도 안 덮는다» 다.** «전부» 가 아니다 — 비어 있는 것을 전부로 읽으면 선언을
 * 반쯤 적은 배치가 무제한 자격을 얻는다. 넓히려면 적어야 한다.
 */
data class Entitlement(
    val approverId: String,
    /** 이 자격이 덮는 조치 — 유형과 값이 한 쌍이다(ADR 44). */
    val actions: List<DeclaredAction>,
    /** 이 자격이 덮는 기체. 범위(셀·기종)는 배치가 이 집합으로 옮겨 적는다. */
    val robotIds: Set<String>,
    /**
     * 이 시각부터는 자격이 없다. **널을 두지 않는다** — 만료 없는 선언은 다시 들여다볼 일이 없고,
     * 그러면 프로파일이 바뀌어도 계약이 올라가도 그 자격이 그대로 남는다.
     */
    val expiresAt: Instant,
) {

    init {
        // **같은 유형을 두 번 적으면 어느 값이 쓰이는지 선언이 답하지 않는다.** 하나를 고르는 규칙을
        // 여기서 만들면 그 규칙이 선언보다 세진다 — 고르지 않고 거부한다.
        require(actions.distinctBy { it.skillType }.size == actions.size) {
            "같은 조치 유형을 두 번 선언했다: ${actions.map { it.skillType }}"
        }
    }

    /** 이 자격이 덮는 조치 유형. [actions] 에서 나오며 따로 선언되지 않는다. */
    val skillTypes: Set<String> get() = actions.mapTo(mutableSetOf()) { it.skillType }

    fun actionFor(skillType: String): DeclaredAction? = actions.firstOrNull { it.skillType == skillType }
}

/**
 * 자격 선언 목록 — **밖에서 선언되고 안에서 판정된다**(ADR 43).
 *
 * 값이 설정 파일에 있든 레지스트리에 있든 이 층의 코드는 같다. 그 선택은 미결이 아니라 배치의
 * 결정이므로, 이 층은 포트만 알고 구현체의 자리를 모른다.
 *
 * ## 자동 승인이 실을 값은 어디서 오나 — 둘뿐이고 지어내는 자리는 없다(ADR 44)
 *
 * | 파라미터 | 출처 | 왜 |
 * |---|---|---|
 * | 계약이 `is_object_reference` 로 표시한 칸 | **관측** — 지금 든 것(`HoldState.objectRef`) | 무엇을 들었는지는 기체가 말하는 사실이다. 미리 적으면 상황을 미리 단정하는 것이 된다 |
 * | 그 밖의 필수 칸 | **선언** — [DeclaredAction.parameters] | 어디에 놓을지는 현장의 상설 결정이고 상황마다 바뀌지 않는다 |
 *
 * 둘 중 어느 쪽도 못 채우면 **거절이고 사람에게 남는다.** 빈 값을 채워 넣는 갈래는 없다 — 그것이
 * 곧 승인이 무엇을 승인하는지 모르는 채 눌리는 경우다.
 */
interface Entitlements {

    /**
     * 이 승인자에게 선언된 것. 없으면 널이다 — **«없다» 와 «만료됐다» 를 여기서 접지 않는다.**
     * 접으면 운영자가 「아직 안 올렸다」와 「기간이 지났다」를 같은 답으로 받는다.
     */
    fun declaredFor(approverId: String): Entitlement?

    /**
     * 아무것도 선언하지 않은 배치. **기본값이고, 그래서 에이전트 승인은 전부 거절된다**(deny by default,
     * ADR 42). 라인은 안 선다 — 사람은 선언 없이 승인하기 때문이다.
     */
    object None : Entitlements {
        override fun declaredFor(approverId: String): Entitlement? = null
    }
}
