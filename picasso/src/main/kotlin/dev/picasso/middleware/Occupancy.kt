package dev.picasso.middleware

/**
 * 셀 안 자리를 **누가 잡고 있는가**(§15.153).
 *
 * 상태 축이 파지 하나뿐이라 이 층은 «저 자리가 지금 쓰이는가» 를 몰랐다. 두 주문이 같은 슬롯을
 * 목적지로 삼아도 둘 다 접수됐고, 출발 자리가 비어 있어도 하달한 뒤 로봇이 빈 자리에서 집으려다 실패했다.
 *
 * **점유는 계약의 사전 조건이 될 수 없다.** 계약의 `PreconditionSubject` 는 *"조건이 보는 로봇 상태"* 이고
 * *"값이 느는 조건은 하나다: 그것을 **관측하는 어댑터**가 있어야 한다"* 고 적혀 있다. 슬롯 점유를 보는 것은
 * 어댑터가 아니라 셀 설비(`CellSignals`, E2)다. 그래서 이 축은 **이 층의 관문**이지 계약의 주어가 아니고,
 * 대안 탐색(선언된 능력 전이를 도는 것)의 상태 공간도 넓히지 않는다.
 *
 * @param material 그 자리에 놓일 자재. **아는 것을 놓는 자리만 점유다** — 아래 [SlotClaim] 을 만드는 쪽이 그것을 가른다
 */
data class SlotClaim(
    val location: String,
    val executionId: String,
    val jobOrderId: String,
    val material: String,
)

/**
 * 출발 자리를 하달 전에 본 결과.
 *
 * **셋으로 가른다.** 「비었다」와 「말이 없다」를 접으면 관측 경로가 죽었을 때 라인이 통째로 서고,
 * 「말이 없다」를 「있다」로 접으면 빈 자리로 로봇을 보낸다. `SlotSignal` 이 `null` 로 침묵을 말하는 것과
 * 같은 규율이다.
 */
sealed interface SourceCheck {

    /** 그 자재가 거기 있다. 또는 있다는 것까지는 설비가 말했다. */
    data object Ready : SourceCheck

    /** 설비가 말이 없다. **판정하지 않는다** — 없는 관측을 위반으로 세지 않는다. */
    data object Silent : SourceCheck

    /** 비었거나 다른 것이 있다. [observed] 가 널이면 빈 자리이고, 아니면 거기 있는 다른 신원이다. */
    data class Missing(val observed: String?) : SourceCheck

    /**
     * 단위가 관측을 요구했는데([SourceObservation.REQUIRED]) 판정할 관측이 없다. **결품이 아니다** — 없다는 관측도 없다.
     *
     * @param occupied 설비가 말한 것. 널이면 말이 없었고, 참이면 «있다» 고만 하고 무엇인지는 말하지 않았다
     */
    data class Unobserved(val occupied: Boolean?) : SourceCheck
}

/**
 * 출발 자리의 관측을 단위가 **요구하는가** — 임무 정의가 단위 노드마다 선언한다(`sourceObservation`).
 *
 * 기본을 뒤집지 않고 **암묵을 없앤다.** 말이 없는 셀에서 라인이 통째로 서면 이 관문은 곧 꺼지므로 기본은 그대로
 * [OPTIONAL] 이다. 다만 신원을 대는 출발 자리가 있는 정의는 어느 쪽인지 적어야 활성화된다(`MissionValidator`).
 * 코드 케이퍼빌리티는 검증기를 거치지 않으므로 [ExecutionUnit] 의 기본값 [OPTIONAL] 로 오늘의 거동을 유지한다.
 */
enum class SourceObservation {
    /** 관측이 판정에 모자라면 보내지 않고 확인을 요구한다([SourceUnobserved]). */
    REQUIRED,

    /** 설비가 말이 없으면 판정하지 않고 보낸다 — 오늘의 거동이다. */
    OPTIONAL,
}

/**
 * 출발 자리를 **확인하지 못해** 보내지 않았다 — 관측을 요구한 단위([SourceObservation.REQUIRED])의 답이다.
 *
 * **결품([RemedyOutcome.SourceMissing])과 접지 않는다.** 결품은 «없다» 는 관측이고 다음 행동이 주문을 고치는 것(대체
 * 자리)이다. 이것은 관측이 없다는 것이고 다음 행동이 **사람의 확인**이다 — 접으면 운영자가 재고를 의심하고 멀쩡한 자재를
 * 두고 주문을 고친다. 그래서 대체 자리를 계산하지 않는다.
 *
 * **확인은 이 층의 덮어쓰기 칸으로 들어오지 않는다.** 상위 시스템의 확인, 인계 시점 점검, 기록된 수동 확인 중 무엇이든
 * 셀 포트([CellSignals.observe])가 그 자리의 신원을 답하는 것으로 들어오고, 같은 작업 요청을 다시 내면 관문이 그 관측으로
 * 판정한다. 일반 운영 해제로 이 답을 넘기는 길은 이 층에 없다.
 *
 * **조치 탐색 대장에 적지 않는다.** 대장의 답([RemedyOutcome])에 갈래를 더하면 내보내기 줄의 `outcome` 이 바뀐다.
 *
 * @param material 그 자리에 있어야 할 것. 단위가 자재를 지정하지 않으면 널이다
 * @param source 확인하지 못한 출발 자리
 * @param occupied 설비가 말한 것. 널이면 말이 없었고, 참이면 «있다» 고만 했다(무엇인지는 모른다)
 */
data class SourceUnobserved(
    val material: String?,
    val source: String,
    val occupied: Boolean?,
)

/**
 * 목적지(자재를 둘 자리)를 하달 전에 **설비 관측으로 볼지** — 임무 정의가 단위 노드마다 선언한다(`destinationObservation`).
 *
 * 출발 관측 선언([SourceObservation])을 본뜬 것이지만 **기본이 뜻하는 것이 다르다.** 출발 자리는 선언과 무관하게 결품을 보고
 * 선언은 침묵만 가른다. 목적지는 [OPTIONAL] 이면 **아예 보지 않는다.** 보고서 12.1 과 근거 윈도우([EvidenceWindow])는 작업 전
 * 목적지에 남은 «있다» 를 옛 신호(옛 용기·옛 부품)로 다루기 때문이다. 그 신호로 거부하면 래치·지연으로 옛 값을 남기는 셀에서
 * 멀쩡한 작업 지시가 서고, 그 전제로 목적지를 제출 전에 «있다» 로 프로그램하는 시험들과도 부딪친다.
 *
 * [REQUIRED] 는 그 현장이 **작업 전 목적지 «있다» 를 물리적 점유로 본다**고 선언하는 것이다. 근거 윈도우의 판정(완료 뒤 어느
 * 신호를 이 완료의 근거로 세는가)은 그대로다 — 게이트는 하달 전에 비었는지를 보고, 윈도우는 완료 뒤에 무엇이 들어왔는지를 본다.
 *
 * 기본을 뒤집지 않고 **암묵을 없앤다.** 식별 정보와 목적지를 둘 다 대는 정의는 어느 쪽인지 적어야 활성화된다(`MissionValidator`).
 * 코드 케이퍼빌리티는 검증기를 거치지 않으므로 [ExecutionUnit] 의 기본값 [OPTIONAL] 로 오늘의 동작을 유지한다.
 */
enum class DestinationObservation {
    /** 목적지가 비었다는 관측이 있어야 보낸다. 차 있으면 «목적지 점유», 말이 없으면 «목적지 미관측» 으로 거부한다. */
    REQUIRED,

    /** 목적지를 설비 관측으로 보지 않는다 — 오늘의 동작이다. 진행 중인 실행의 예약만 본다. */
    OPTIONAL,
}

/**
 * 목적지를 하달 전에 본 결과. 목적지 관측을 요구한 단위([DestinationObservation.REQUIRED])만 이 판정을 지난다.
 *
 * **셋으로 가른다.** 「차 있다」와 「말이 없다」를 접으면 운영자가 치울 것이 없는 자리를 찾아가고, 「말이 없다」를 「비었다」로
 * 접으면 선언한 뜻이 사라진다. [SourceCheck] 와 같은 규율이다.
 */
sealed interface DestinationCheck {

    /** 설비가 비었다고 말했다. */
    data object Free : DestinationCheck

    /**
     * 무엇이 있다. **식별 정보가 맞아도 점유다** — 출발 자리와 달리 목적지는 무엇이 있든 둘 자리가 없다. 같은 부품이 있으면
     * 앞선 작업 지시가 이미 둔 것일 수 있고, 거기에 또 두면 겹친다.
     *
     * @param observed 설비가 말한 식별 정보. 널이면 «있다» 고만 했다
     */
    data class Occupied(val observed: String?) : DestinationCheck

    /** 설비가 말이 없다. **비었다고 세지 않는다** — 없다는 관측도 없다. 다음 행동은 사람의 확인이다. */
    data object Unobserved : DestinationCheck
}

/** 점유 판정의 순수한 부분. 관측을 받아 답만 내며, 어디에 물을지는 부르는 쪽이 정한다. */
object CellOccupancy {

    /**
     * 이 출발 자리에서 집을 수 있는가.
     *
     * **요구가 없으면 판정하지 않는다** — 자재를 지정하지 않은 단위에 «다른 것이 있다» 를 말할 근거가 없다.
     * 설비가 «있다» 고만 하고 무엇인지 말하지 않은 경우도 같다: 있다는 것은 관측이고, 무엇인지는 아니다.
     *
     * [observation] 이 [SourceObservation.REQUIRED] 면 말이 없는 것과 신원 없는 «있다» 가 통과가 아니라
     * [SourceCheck.Unobserved] 다. 비었거나 다른 것이 있으면 선언과 무관하게 결품이다.
     */
    fun sourceCheck(
        signal: SlotSignal?,
        material: String?,
        observation: SourceObservation = SourceObservation.OPTIONAL,
    ): SourceCheck = when {
        // **관측을 요구한 단위만 침묵을 답으로 바꾼다.** 결품으로 세지 않는다 — 없다는 관측도 없다.
        signal == null -> if (observation == SourceObservation.REQUIRED) SourceCheck.Unobserved(null) else SourceCheck.Silent
        !signal.occupied -> SourceCheck.Missing(null)
        material.isNullOrEmpty() -> SourceCheck.Ready
        signal.identity == null -> if (observation == SourceObservation.REQUIRED) SourceCheck.Unobserved(true) else SourceCheck.Ready
        signal.identity != material -> SourceCheck.Missing(signal.identity)
        else -> SourceCheck.Ready
    }

    /**
     * 이 목적지에 둘 수 있는가. **부르는 쪽이 선언을 먼저 가른다** — [DestinationObservation.OPTIONAL] 인 단위는 여기 오지 않고
     * 설비에 묻지도 않는다. 그래서 침묵을 통과로 바꾸는 갈래가 이 함수에는 없다.
     */
    fun destinationCheck(signal: SlotSignal?): DestinationCheck = when {
        signal == null -> DestinationCheck.Unobserved
        signal.occupied -> DestinationCheck.Occupied(signal.identity)
        else -> DestinationCheck.Free
    }

    /**
     * 제시할 다른 자리. **이미 누가 잡은 자리는 빼고** 낸다 — 제시하자마자 관문에 걸릴 자리를 내면
     * 운영자가 같은 거절을 두 번 받는다.
     *
     * 셀이 답하지 않으면(`null`) 빈 목록이 아니라 **모른다**가 그대로 흐른다. 빈 목록으로 접으면
     * «그 자재를 든 자리가 하나도 없다» 로 읽힌다.
     */
    fun alternatives(known: List<String>?, taken: Set<String>, exclude: String): List<String>? =
        known?.filterNot { it == exclude || it in taken }
}
