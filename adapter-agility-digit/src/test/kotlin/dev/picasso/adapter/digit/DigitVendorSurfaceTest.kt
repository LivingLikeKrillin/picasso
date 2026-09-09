package dev.picasso.adapter.digit

import dev.picasso.adapter.core.VendorManifest
import kotlin.test.Test

/**
 * 남쪽 포트가 짚은 이름이 **벤더 원문에 실제로 있는가.**
 *
 * ## 이 기종에서 이 시험이 특히 값을 한다
 *
 * 이 어댑터는 한 번 통째로 뒤집혔다 — 제3자 래퍼를 원문으로 삼아 *"지속시간이
 * 없다"*, *"취소가 없다"*, *"목적지가 좌표뿐이다"* 를 만들어 냈고 셋 다 틀렸다.
 * 그때의 교훈이 산문이었고, 이 시험이 그것을 기계로 만든다.
 *
 * 붙이면서 둘이 더 나왔다.
 *
 * | 짚었던 것 | 실제 |
 * |---|---|
 * | `["error", {info}]` | 그런 메시지 표제가 없다 — 문서화된 것은 `action-status-changed.info` |
 * | `ActionStatus` 값 둘 | 매뉴얼의 열거는 넷이고, 빠진 `failure` 때문에 **로봇이 신고한 실패에 도달할 수 없었다** |
 *
 * ## 원본이 둘인 유일한 기종이다
 *
 * SDK 는 **보내는 메시지의 빌더만** 든다. 우리가 읽는 `privileges`·
 * `action-status-changed`·`action-status` 는 로봇이 보내는 쪽이라 거기 없고
 * 매뉴얼이 1차 출처다. 매니페스트 헤더에 원본 둘의 해시가 함께 적힌다.
 *
 * ## 무엇을 보증하지 않나
 *
 * **이름이 있다는 것만 본다.** 보냈을 때 Digit 이 무엇을 하는지는 안 본다
 * (§9.7 ④·C-3). 그리고 SDK 릴리스가 2021년판이라 *"있다"* 는 확실하고
 * *"없다"* 는 그 시점 기준이다.
 */
class DigitVendorSurfaceTest {

    @Test
    fun `짚은 벤더 이름이 원문에 있고 멤버마다 짚은 것이 있다`() {
        VendorManifest.load().verify(
            DigitLink::class.java,
            ActionStatus::class.java,
            ExecutionNode::class.java,
        )
    }
}
