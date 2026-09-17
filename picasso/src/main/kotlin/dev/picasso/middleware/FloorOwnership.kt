package dev.picasso.middleware

/**
 * 이 자리의 바닥은 **누구 것인가**(§15.154).
 *
 * ## 무소유는 결함이다
 *
 * 걷는 기체가 셀을 나가면 그 바닥의 소유자가 없다. 플릿은 자기 AMR 만 승인하므로, 이 층이 내는 명령이
 * **아무 관문도 통과하지 않고** 물리 세계로 나간다. 선택지는 둘뿐이다 — 소유자를 만들거나, 그 자원을
 * 쓰지 않거나. 무승인 통행을 허용하는 셋째는 없으므로, 소유자가 정해지기 전까지의 올바른 상태는
 * **그 구역으로 명령을 내지 않는 것**이다.
 *
 * ## 기하가 아니라 이름이다
 *
 * 이 저장소에는 좌표도 반경도 없다(그것은 어댑터 경계 밖의 지식이다). 그래서 바닥을 **구역 이름**으로
 * 다룬다 — 맞대 볼 토큰이라 어긋나게 만들 수 있고, 그래서 시뮬레이션에서 진짜로 검증된다. 좌표였다면
 * 미믹에 기하가 없어 항상 통과하는 시험이 됐을 것이다.
 */
sealed interface FloorOwner {

    /** 소유자가 있다. 그 소유자의 관문을 지나면 된다. */
    data class Declared(val owner: String) : FloorOwner

    /**
     * 대장에 있는 자리인데 **소유자 칸이 비었다.** 여기로는 명령을 내지 않는다 — 빈 칸이 무승인 경로다.
     */
    data object Unowned : FloorOwner

    /**
     * 이 배치에 소유 대장이 붙어 있지 않다. **검사하지 않는다.**
     *
     * [Unowned] 와 가르는 이유는 둘의 뜻이 반대이기 때문이다. 앞은 «자리는 아는데 주인이 없다» 이고
     * 이것은 «대장 자체가 없다» 다. 접으면 대장을 안 붙인 현장이 통째로 멈추고, 그러면 이 관문이 곧 꺼진다.
     */
    data object NotDeclared : FloorOwner
}

/**
 * 자원 소유 대장에 묻는 자리. **대장은 현장의 것이고 이 저장소 밖에 산다** — 여기 있는 것은 묻는 면뿐이다.
 *
 * 붙이지 않은 배치에서는 모든 자리가 [FloorOwner.NotDeclared] 이고 관문이 아무것도 막지 않는다.
 * 그것이 지금 이 저장소의 기본값이며, **안 붙였다는 사실 자체가 대장의 빈 칸**이다.
 */
interface FloorOwnership {

    fun ownerOf(location: String): FloorOwner

    /** 대장을 안 붙인 배치. 기본값이며, 그것이 결정임을 [FloorOwner.NotDeclared] 가 말한다. */
    object None : FloorOwnership {
        override fun ownerOf(location: String): FloorOwner = FloorOwner.NotDeclared
    }
}
