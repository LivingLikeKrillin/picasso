package dev.picasso.middleware

import java.time.Duration

/**
 * 재할당을 막는 두 장치(설계안 §7).
 *
 * **라인을 멈추지 않는 것이 최적성보다 우선한다.** 더 싼 기체가 보일 때마다 옮기면 비용이 1 흔들릴 때마다
 * 두 기체 사이를 왕복하고, 그동안 아무 일도 진행되지 않는다. 최적해에 가까워지는 것과 일이 끝나는 것은
 * 다른 목표이고, 여기서는 뒤엣것이 이긴다.
 *
 * @param margin 이만큼 **더** 싸야 옮긴다. 같거나 덜 싸면 그대로 둔다 — 동점에서 움직이는 것이 진동의 시작이다
 * @param minHold 배정 뒤 이 시간 안에는 옮기지 않는다. 옮긴 직후 되돌아오는 것을 막는 장치이며,
 *   [margin] 만으로는 부족하다 — 일이 떠난 쪽의 부담이 줄어 반대 방향의 이득이 곧바로 생기기 때문이다
 */
data class ReassignPolicy(
    val margin: Int = 2,
    val minHold: Duration = Duration.ofSeconds(30),
)

/**
 * 재할당의 답.
 *
 * **안 옮긴 것도 결과다.** 사유가 없으면 진동을 막은 것인지 애초에 후보가 없었던 것인지 구별되지 않고,
 * 그러면 배정 정책이 무엇을 고쳐야 하는지 모른 채 같은 요청을 되풀이한다.
 */
sealed interface Reassignment {

    /** 옮겼다. [saved] 는 이 일을 뺀 두 기체의 부담 차이다. */
    data class Moved(val from: String, val to: String, val saved: Int) : Reassignment

    /** 그대로 뒀다. [reason] 이 왜인지를 말한다. */
    data class Kept(val on: String, val reason: String) : Reassignment
}
