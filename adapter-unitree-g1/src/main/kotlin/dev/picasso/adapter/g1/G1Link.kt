package dev.picasso.adapter.g1

/**
 * 남쪽 경계 — Unitree G1이 실제로 말하는 것.
 *
 * ## 이 분할은 우리 것이 아니라 벤더의 것이다
 *
 * `unitree_sdk2`는 두 층으로 갈라져 있다. 저수준(`rt/lowcmd`·`rt/lowstate`,
 * `unitree_hg` IDL)은 관절과 IMU를 직접 나르고, 고수준(`sport` 서비스,
 * API 7001~7111)은 `SetFsmId`·`SetVelocity` 같은 동작을 받는다. 우리가 편의로
 * 나눈 것이 아니라 벤더가 그렇게 갈라 두었고, 그래서 이 경계는 다음 조사에서
 * 벤더 문서와 그대로 맞대 볼 수 있다.
 *
 * ## `sport`가 널일 수 있는 것이 요점이다
 *
 * 공식 시뮬레이터(`unitree_mujoco`)는 **저수준만** 흉내낸다 — README가
 * *"Current version only supports low-level development"* 라 못박는다.
 * `ai_sport` 서비스는 실물 기체에서만 돈다.
 *
 * 그래서 시뮬레이터를 상대로 어댑터를 띄우면 상태는 읽히는데 **스킬은 하나도
 * 못 돈다.** 그 사실을 타입으로 드러내지 않으면 어댑터가 시뮬레이터에서
 * 아무것도 안 하면서 조용히 초록으로 보인다 — 이 저장소가 반복해 물린
 * "조용한 통과"의 모양이다. 널이면 [G1Adapter]가 태스크를 **거절한다.**
 */
interface G1Link {

    /** 고수준 서비스. **널이면 이 대상이 답하지 않는다**(시뮬레이터). */
    val sport: SportService?

    /** 저수준 채널. 시뮬레이터도 실물도 답한다. */
    val lowLevel: LowLevelChannel
}

/**
 * 고수준 서비스(`LOCO_SERVICE_NAME = "sport"`).
 *
 * **메서드 이름을 SDK 그대로 둔다.** 우리 어휘로 옮기면 다음 조사에서 벤더
 * 문서와 한 줄씩 맞대 보지 못하고, 그러면 §15.7이 방어책으로 든 파생 규율이
 * 확인 불가능해진다.
 *
 * **여기 없는 것이 여기 있는 것만큼 중요하다.** 진행률을 내는 메서드가 없고,
 * 진행 중인 동작을 취소하는 메서드가 없다(`g1_loco_client.py` 전수 확인,
 * 2026-09-08). 계약의 `CANCELLING`과 진행률은 그래서 **어댑터가 만드는
 * 것**이지 로봇에게서 받는 것이 아니다.
 */
interface SportService {

    /** `ROBOT_API_ID_LOCO_SET_FSM_ID = 7101`. */
    fun setFsmId(id: Int): Result<Unit>

    /** `ROBOT_API_ID_LOCO_GET_FSM_ID = 7001`. */
    fun getFsmId(): Result<Int>

    /**
     * `ROBOT_API_ID_LOCO_SET_VELOCITY = 7105`.
     *
     * SDK 시그니처가 `SetVelocity(vx, vy, omega, duration=1.0)`이다. 기본값을
     * 여기서 흉내내지 않는다 — 계약이 `duration`을 필수로 두었고(그 이유는
     * `skill_catalog.proto`에 있다) 기본값을 어댑터가 채우면 기종마다 다른
     * 안전 여유를 우리가 덮는다.
     */
    fun setVelocity(vx: Double, vy: Double, omega: Double, durationSeconds: Double): Result<Unit>
}

/** 저수준 채널(`rt/lowstate`). 읽기만 한다 — 어댑터는 관절을 직접 몰지 않는다. */
interface LowLevelChannel {

    /** 마지막으로 받은 상태. 아직 하나도 못 받았으면 널이다. */
    fun latestState(): LowState?
}

/**
 * `unitree_hg::msg::dds_::LowState_`에서 **우리가 쓰는 것만** 옮긴 것.
 *
 * 전부 옮기지 않는 이유는 §7.2의 투영 기준과 같다 — 소비자가 행동을 결정하는
 * 데 필요한 것만 위로 올린다. 원본 필드는 `version`·`mode_pr`·`mode_machine`·
 * `tick`·`imu_state`·`motor_state[35]`·`wireless_remote`·`reserve`·`crc` 이며
 * 필요해지면 여기에 더한다.
 */
data class LowState(

    /** `tick`. 같은 값이 계속 오면 발신이 멈춘 것이다. */
    val tick: Long,

    /** `mode_machine`. 기체 세대를 구분한다. */
    val modeMachine: Int,

    /**
     * `motor_state[].temperature`(`int16[2]`)에서 온 섭씨 값들.
     *
     * **G1은 결함을 보고하지 않는다.** 과열 판정은 클라이언트가 이 값으로
     * 스스로 하며(SDK 예제의 `terminations.hpp`가 그렇게 한다), 그래서
     * 판정하는 주체가 로봇이 아니라 **우리**라는 것이 출처 문서에 남아 있다.
     */
    val motorTemperaturesCelsius: List<Int>,

    /**
     * `motor_state[].dq`(관절 각속도)에서 온 값들.
     *
     * **§4.4의 래치 불변식을 지킬 유일한 재료다.** 계약은 종착한 태스크가
     * 다시 움직이면 `TERMINAL_STATE_VIOLATED`를 발행하라고 하는데, G1은
     * "동작이 끝났다"를 말해 주는 것이 없다 — `sport` 서비스에 진행률도
     * 완료 통지도 없다(조사 문서). 그래서 어댑터가 **관절이 아직 도는지**로
     * 판정한다. 임계값도 판정 주체도 우리 것이며 로봇의 것이 아니다.
     */
    val motorVelocities: List<Double>,
)
