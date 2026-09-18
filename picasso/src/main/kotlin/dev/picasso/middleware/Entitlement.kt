package dev.picasso.middleware

import dev.picasso.capability.RemedyStep
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
 * 한 승인자에게 **선언된** 자동 승인 자격(ADR 43).
 *
 * **계산되지 않는다.** 계산된 자격은 계산이 틀리면 조용히 넓어지고, 선언된 자격은 사람의 행위이므로
 * 감사 가능하고 만료를 걸 수 있다. 이 층은 선언을 읽고 대조할 뿐 만들지 않는다.
 *
 * **빈 집합은 «아무것도 안 덮는다» 다.** «전부» 가 아니다 — 비어 있는 것을 전부로 읽으면 선언을
 * 반쯤 적은 배치가 무제한 자격을 얻는다. 넓히려면 적어야 한다.
 */
data class Entitlement(
    val approverId: String,
    /** 이 자격이 덮는 조치 유형 — 계약의 스킬 이름이다. */
    val skillTypes: Set<String>,
    /** 이 자격이 덮는 기체. 범위(셀·기종)는 배치가 이 집합으로 옮겨 적는다. */
    val robotIds: Set<String>,
    /**
     * 이 시각부터는 자격이 없다. **널을 두지 않는다** — 만료 없는 선언은 다시 들여다볼 일이 없고,
     * 그러면 프로파일이 바뀌어도 계약이 올라가도 그 자격이 그대로 남는다.
     */
    val expiresAt: Instant,
)

/**
 * 자격 선언 목록 — **밖에서 선언되고 안에서 판정된다**(ADR 43).
 *
 * 값이 설정 파일에 있든 레지스트리에 있든 이 층의 코드는 같다. 그 선택은 미결이 아니라 배치의
 * 결정이므로, 이 층은 포트만 알고 구현체의 자리를 모른다.
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
