package dev.picasso.adapter.spot

import dev.picasso.adapter.core.VendorManifest
import kotlin.test.Test

/**
 * 남쪽 포트가 짚은 이름이 **벤더 원문에 실제로 있는가.**
 *
 * ## 이 시험이 없었을 때 무슨 일이 났나
 *
 * `navigate_to` 가 계약의 `location` 을 `destination_waypoint_id` 로 그대로
 * 넘기고 있었다. 벤더는 둘을 나눈다 — `Waypoint.id` 는 *"Unique across all
 * maps"* 인 로봇 생성 id 이고 사람이 붙인 이름은 `Waypoint.Annotations.name`
 * 이다. **KDoc 이 그 둘을 접은 채로 초록이었다.**
 *
 * 이 시험을 붙이면서 둘이 더 나왔다 — `GetStateResponse.Status` 라는 이름은
 * 없고(`State.status` 다), `MissionStatus` 에 벤더의 `STATUS_UNKNOWN` 이
 * 빠져 있었다. **셋 다 사람이 읽고 옮기는 단계의 사고다.**
 *
 * ## 무엇을 보증하지 않나
 *
 * **이름이 있다는 것만 본다.** 그 메시지를 보냈을 때 Spot 이 무엇을 하는지는
 * 전혀 안 본다 — 그것이 §9.7 ④·C-3 이고 열려 있다. 그리고 매니페스트는
 * 헤더에 적힌 릴리스 시점의 것이므로, **낡은 매니페스트는 낡은 코드와
 * 사이좋게 초록이다.**
 *
 * ## 목록이 손으로 적혀 있다
 *
 * 아래 타입 목록이 이 시험의 남은 구멍이다 — 새 남쪽 타입을 여기 안 넣으면
 * 안 본다. [LeaseResult] 가 빠진 것은 의도이며, 그것은 우리가 만든 결과
 * 어휘이지 벤더의 메시지가 아니다.
 */
class SpotVendorSurfaceTest {

    @Test
    fun `짚은 벤더 이름이 원문에 있고 멤버마다 짚은 것이 있다`() {
        VendorManifest.load().verify(
            SpotLink::class.java,
            CommandLayer::class.java,
            MissionLayer::class.java,
            GraphLayer::class.java,
            GraphWaypoint::class.java,
            MissionState::class.java,
            MissionStatus::class.java,
            LeaseStatus::class.java,
        )
    }
}
