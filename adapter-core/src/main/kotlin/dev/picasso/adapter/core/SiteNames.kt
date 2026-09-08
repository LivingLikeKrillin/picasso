package dev.picasso.adapter.core

/**
 * 기체가 아는 **사이트 이름**의 답(ADR 35, 계약의 `GetKnownSiteNames`).
 *
 * ## 왜 어댑터가 이것을 답해야 하나
 *
 * 사이트 이름은 사이트가 저작해 로봇에 등록해 두는 것이고, 등록은 계약 밖의
 * 배포 작업이다. 그러면 **등록했는지를 무엇으로 아는가**가 남는다 — 여기
 * 오기 전까지는 운영자가 레지스트리에 *"했다"* 고 적는 것뿐이었고, 통째로
 * 빠뜨렸거나 엉뚱한 기체에 했어도 화면은 초록이었다.
 *
 * ## 셋으로 가른다 — 둘로 접으면 거짓이 된다
 *
 * | 상태 | 뜻 | 접었을 때 |
 * |---|---|---|
 * | [Unsupported] | 이 기체에 이름을 둘 자리가 **없다** | 없는 자리에 등록하라고 요구한다 |
 * | [Known] | 물어봤고 이만큼 안다 (빈 목록 포함) | — |
 * | [Unavailable] | **물어보지 못했다** | 0으로 답하면 *"등록을 안 했다"*로 읽힌다 |
 *
 * 마지막이 이 타입의 이유다. 그래프 서비스가 죽었거나 세계 모델 질의가
 * 실패했을 때 빈 목록을 내면, **고장난 관측이 사람의 태만처럼 보이고**
 * 원장이 `CONTRADICTED`를 띄운다 — 사실은 아무것도 관측되지 않았는데.
 * 계약의 응답에는 이 자리가 없으므로 전송이 이것을 오류로 옮긴다.
 * 원장의 `Observed`/`NotObservable`, [FaultObservation]과 같은 규율이다.
 */
sealed interface SiteNames {

    /**
     * 이름을 호스팅할 자리가 없다.
     *
     * G1이 그렇다 — 세계 모델도 지도도 없고 `sport` 서비스는 속도만 받는다.
     * Spot도 GraphNav 서비스가 없으면 여기로 온다: 기종이 못 하는 것이
     * 아니라 **이 기체에 그 표면이 안 떠 있는 것**이고, 결과는 같다.
     */
    data object Unsupported : SiteNames

    /** 물어봤다. 빈 목록은 *"아직 하나도 등록 안 됐다"* 이고 그것도 답이다. */
    data class Known(val names: List<String>) : SiteNames

    /** 물어보려다 실패했다. **0이 아니다.** */
    data class Unavailable(val reason: String) : SiteNames
}
