package dev.picasso.adapter.g1

/**
 * `SetFsmId`가 받는 매직 넘버.
 *
 * ## 상수로 박지 않는 이유
 *
 * 같은 뜻의 상태가 기체마다 다른 번호다. 조사에서 확인한 것만도 "보통 모드"가
 * **500(허리 1자유도) · 501(허리 3자유도) · 801** 로 갈리고, 벤더 문서가
 * *"`Start()` 호출을 `SetFsmId(501)`이나 `SetFsmId(801)`로 바꾸라"* 고 권한다.
 * 어느 번호가 맞는지는 우리가 아는 것이 아니라 **그 기체가 아는 것**이다.
 *
 * 그래서 [start]에 기본값을 두지 않는다. 기본값을 두면 잘못된 기체에서 조용히
 * 다른 상태로 들어가고, 그 뒤의 모든 판정이 틀린 전제 위에 선다. 값을 대라고
 * 요구하는 쪽이 낫다.
 *
 * [damp]와 [lockedStand]는 기본값을 둔다 — 조사한 세대에서 일관됐고, 무엇보다
 * [damp]는 **취소 경로**라 값이 없어 못 부르는 것이 더 나쁘다.
 */
data class FsmProfile(

    /** `Damp()` = `SetFsmId(1)`. 힘을 빼고 선 자세를 유지한다. */
    val damp: Int = 1,

    /** `SetFsmId(4)` = Locked Stand. */
    val lockedStand: Int = 4,

    /**
     * "보통 모드". **기본값이 없다** — 위 설명 참조. `Start()`가 부르는 값이며
     * 조사한 것은 200 · 500 · 501 · 801 이다.
     */
    val start: Int,
) {
    init {
        // 번호가 음수인 조합은 SDK에 없다. 잘못 배선된 설정이 런타임 깊은
        // 곳에서 터지는 것보다 여기서 터지는 편이 싸다.
        require(damp >= 0 && lockedStand >= 0 && start >= 0) {
            "FSM 번호가 음수다: damp=$damp lockedStand=$lockedStand start=$start"
        }
    }
}

/**
 * 기체의 신원.
 *
 * **G1은 자기가 누구인지 말하지 않는다** — 신원을 묻는 질의가 SDK에 없다
 * (조사 문서의 `model_identity: NONE`). 그래서 어댑터가 설정으로 받고, 잘못
 * 설정된 어댑터는 잘못된 기종으로 등록되며 **아무것도 그것을 막지 못한다.**
 * 그 사실이 출처 문서에 `ADAPTER`로 남아 있다.
 */
data class AdapterIdentity(val vendor: String, val model: String, val robotId: String) {

    /** 비어 있는 좌표로는 등록도 보고도 의미가 없다. */
    val complete: Boolean
        get() = vendor.isNotBlank() && model.isNotBlank() && robotId.isNotBlank()
}
