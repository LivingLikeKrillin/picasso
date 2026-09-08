package dev.picasso.adapter.g1

import dev.picasso.adapter.core.VendorManifest
import kotlin.test.Test

/**
 * 남쪽 포트가 짚은 이름이 **벤더 원문에 실제로 있는가.**
 *
 * ## 셋 중 가장 약한 원본이다
 *
 * Spot 은 proto, Digit 은 SDK + 매뉴얼인데 여기엔 **스키마 언어가 없다.**
 * 고수준은 C++ 헤더의 API ID 상수이고 저수준은 IDL 생성 클래스의 필드다.
 * 그래서 이 검사가 덮는 것이 다른 둘보다 좁다 — **무엇이 있는지는 보지만
 * 어떤 인자를 받는지는 못 본다.** `SetVelocity(vx, vy, omega, duration)`
 * 같은 시그니처는 파이썬 클라이언트 코드에만 있고 스키마가 아니다.
 *
 * 그 좁음이 이 기종의 거리 측정과 같은 방향을 가리킨다 — 벤더가 계약처럼
 * 다룰 표면을 덜 판다.
 *
 * ## 무엇을 보증하지 않나
 *
 * **이름이 있다는 것만 본다.** 보냈을 때 G1 이 무엇을 하는지는 안 본다 —
 * 이 기종은 공식 시뮬레이터가 저수준만 흉내내므로(`unitree_mujoco`) 그
 * 확인이 **실물 없이는 아예 안 선다**(§9.7 ④·C-3).
 */
class G1VendorSurfaceTest {

    @Test
    fun `짚은 벤더 이름이 원문에 있고 멤버마다 짚은 것이 있다`() {
        VendorManifest.load().verify(
            G1Link::class.java,
            SportService::class.java,
            LowLevelChannel::class.java,
            LowState::class.java,
        )
    }
}
