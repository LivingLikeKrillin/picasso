package dev.picasso.adapter.g1

import dev.picasso.adapter.core.VendorManifest
import kotlin.test.Test

/**
 * 남쪽 포트가 짚은 이름이 **벤더 원문에 실제로 있는가.**
 *
 * ## 셋 중 가장 약한 원본이다
 *
 * Spot 은 proto, Digit 은 SDK + 매뉴얼인데 여기엔 **스키마 언어가 없다.**
 * 원본이 셋으로 흩어져 있다 — 고수준 API ID 상수, 요청 본문의 JSON 키
 * (헤더의 `Jsonize*` 클래스), 저수준 IDL 생성 클래스의 필드.
 *
 * ## 좁다고 적은 것이 한 번 틀렸다
 *
 * 앞 판은 *"인자 이름은 못 덮는다 — 스키마가 없다"* 고 적었다. **벤더는
 * 주고 있었고 우리 추출기가 `const` 줄만 읽고 있었다.** 추출기의 한계를
 * 벤더의 부재로 적은 것이며, §15.65 가 경고한 그 실수다 — 근거 등급이
 * 낮으면 `NO` 가 아니라 `UNKNOWN` 이다.
 *
 * 그래도 다른 둘보다는 약하다. 키가 **코드 안에** 있어 타입도 필수 여부도
 * 안 딸려 오고, **응답의 모양은 어디에도 없다** — `getFsmId` 가 무엇을
 * 돌려주는지는 이 검사가 못 본다.
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
            UnitreeError::class.java,
        )
    }
}
