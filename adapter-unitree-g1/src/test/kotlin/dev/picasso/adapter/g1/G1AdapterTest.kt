package dev.picasso.adapter.g1

import dev.picasso.adapter.core.Acceptance
import dev.picasso.adapter.core.AdapterIdentity
import dev.picasso.adapter.core.Applied
import dev.picasso.adapter.core.FaultObservation
import dev.picasso.adapter.core.Refusal
import dev.picasso.adapter.core.SiteNames
import dev.picasso.contracts.v1.TaskState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 어댑터가 **없는 것을 만들면서 만든 티를 내는지** 본다.
 *
 * 이 시험들이 붙드는 것은 계약 쪽 거동뿐이다. **"실물 G1이 이대로
 * 행동하는가"는 안 본다** — 그것이 §9.7 ④이고 C-3이며, 공식 시뮬레이터로도
 * 못 메운다(저수준만 흉내내므로 [SportService]를 아예 답하지 않는다).
 * 그 사실 자체를 [`고수준 서비스가 없으면 받지 않는다`]가 붙든다.
 */
class G1AdapterTest {

    private val t0: Instant = Instant.parse("2026-09-08T00:00:00Z")
    private val identity = AdapterIdentity(vendor = "unitree-robotics", model = "unitree-g1", robotId = "g1-01")
    private val fsm = FsmProfile(start = 501)

    private val move = mapOf(
        "forward_speed" to 0.4,
        "lateral_speed" to 0.0,
        "yaw_rate" to 0.0,
        "duration" to 2.0,
    )

    private fun adapter(
        sport: FakeSport? = FakeSport(),
        low: FakeLowLevel = FakeLowLevel(),
        identity: AdapterIdentity = this.identity,
        mode: FakeMode? = null,
    ) = G1Adapter(FakeLink(sport, low, mode), identity, fsm)

    // ── 받기 전에 거절하는 것들

    @Test
    fun `고수준 서비스가 없으면 받지 않는다`() {
        // **이 저장소가 겨냥하는 "조용한 통과"의 가장 비싼 형태다.**
        // 시뮬레이터를 상대로 띄운 어댑터가 태스크를 받아 놓고 아무것도 안
        // 하면, 스위트는 초록이고 로봇은 가만히 있는다. 거절해야 그 상태가
        // 보인다.
        val result = adapter(sport = null).accept("move_relative", move, t0)

        val refused = assertIs<Acceptance.Refused>(result)
        assertEquals(Refusal.VENDOR_SURFACE_ABSENT, refused.reason)
    }

    @Test
    fun `신원이 비어 있으면 받지 않는다`() {
        // G1은 자기가 누구인지 말하지 않는다. 설정이 유일한 출처이므로
        // 비어 있는 것은 기본값이 아니라 배선 실수다.
        val blank = AdapterIdentity(vendor = "", model = "unitree-g1", robotId = "g1-01")
        val refused = assertIs<Acceptance.Refused>(adapter(identity = blank).accept("move_relative", move, t0))
        assertEquals(Refusal.IDENTITY_UNSET, refused.reason)
    }

    @Test
    fun `드는 스킬이 아니면 받지 않는다`() {
        val refused = assertIs<Acceptance.Refused>(adapter().accept("navigate_to", move, t0))
        assertEquals(Refusal.UNSUPPORTED_SKILL, refused.reason)
    }

    @Test
    fun `필수 파라미터가 빠지면 값을 지어내지 않는다`() {
        // SDK의 `SetVelocity`는 `duration`에 기본값 1.0을 갖는다. 그것을
        // 어댑터가 흉내내면 호출자가 안 준 값을 우리가 정하는 것이 되고,
        // 계약이 그 파라미터를 필수로 둔 이유가 사라진다.
        val refused = assertIs<Acceptance.Refused>(
            adapter().accept("move_relative", move - "duration", t0),
        )
        assertEquals(Refusal.PARAMETER_MISSING, refused.reason)
        assertTrue("duration" in refused.detail, "무엇이 빠졌는지 말하지 않는다: ${refused.detail}")
    }

    @Test
    fun `이미 도는 태스크가 있으면 받지 않는다`() {
        // §4.9 — 실물은 예외 없이 배타적 제어 모델이다.
        val a = adapter()
        assertIs<Acceptance.Accepted>(a.accept("move_relative", move, t0))
        val refused = assertIs<Acceptance.Refused>(a.accept("move_relative", move, t0))
        assertEquals(Refusal.ALREADY_RUNNING, refused.reason)
    }

    // ── 받은 뒤

    @Test
    fun `받으면 벤더 인자 순서대로 SetVelocity 를 부른다`() {
        // 순서가 어긋나면 로봇이 옆으로 걷는다. 계약의 키 이름과 SDK의
        // 자리 인자 사이를 옮기는 것이 어댑터의 일 그 자체다.
        val sport = FakeSport()
        val a = G1Adapter(FakeLink(sport, FakeLowLevel()), identity, fsm)

        a.accept("move_relative", mapOf(
            "forward_speed" to 0.4, "lateral_speed" to -0.1, "yaw_rate" to 0.2, "duration" to 3.0,
        ), t0)

        assertEquals(listOf(Velocity(0.4, -0.1, 0.2, 3.0)), sport.velocities)
    }

    @Test
    fun `시간이 지나면 성공으로 정착한다`() {
        val a = adapter()
        a.accept("move_relative", move, t0)

        assertEquals(TaskState.TASK_STATE_RUNNING, a.poll(t0.plusMillis(1_999)))
        assertEquals(TaskState.TASK_STATE_SUCCEEDED, a.poll(t0.plusMillis(2_000)))
    }

    @Test
    fun `취소가 속도 0으로 나간다`() {
        // 벤더에게 취소 프리미티브가 없다. `StopMove()`가 실은
        // `SetVelocity(0, 0, 0)`이며 프로파일의 `cancel_support: YES`는
        // 그래서 로봇의 선언이 아니라 어댑터가 만든 것이다.
        val sport = FakeSport()
        val a = G1Adapter(FakeLink(sport, FakeLowLevel()), identity, fsm)
        a.accept("move_relative", move, t0)

        assertEquals(Applied.Ok, a.cancel())
        assertEquals(Velocity(0.0, 0.0, 0.0, 0.0), sport.velocities.last())
        assertEquals(TaskState.TASK_STATE_CANCELLED, a.state)
    }

    @Test
    fun `멈추라는 명령이 실패하면 취소됐다고 적지 않는다`() {
        // 로봇이 아직 움직이고 있을 수 있다. `CANCELLED`로 넘기면 거짓말이고
        // `RUNNING`으로 되돌리면 취소를 시도한 사실이 사라진다.
        val sport = FakeSport(failFrom = 1)
        val a = G1Adapter(FakeLink(sport, FakeLowLevel()), identity, fsm)
        a.accept("move_relative", move, t0)

        val refused = assertIs<Applied.Refused>(a.cancel())
        assertEquals(Refusal.LINK_ERROR, refused.reason)
        assertEquals(TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED, a.state)
    }

    @Test
    fun `종착한 태스크는 취소를 거절한다`() {
        // §4.4의 래치. 종착에는 나가는 화살표가 없다.
        val a = adapter()
        a.accept("move_relative", move, t0)
        a.poll(t0.plusSeconds(3))

        val refused = assertIs<Applied.Refused>(a.cancel())
        assertEquals(Refusal.TERMINAL_LATCHED, refused.reason)
        assertEquals(TaskState.TASK_STATE_SUCCEEDED, a.state)
    }

    @Test
    fun `일시정지는 언제나 거절한다`() {
        // 프로파일이 `pause_support: NO` 라 선언했고 여기가 그것을 집행한다.
        // 선언과 거동이 갈리면 선언이 거짓말이 된다.
        val a = adapter()
        a.accept("move_relative", move, t0)

        val refused = assertIs<Applied.Refused>(a.pause())
        assertEquals(Refusal.NO_VENDOR_PRIMITIVE, refused.reason)
        assertEquals(TaskState.TASK_STATE_RUNNING, a.state, "거절이 상태를 건드렸다")
    }

    // ── 아는 이름을 답한다 (ADR 35)

    @Test
    fun `이름을 호스팅 못 한다고 답한다`() {
        // 세계 모델도 지도도 없다. **빈 목록으로 답하면** 운영자에게 없는
        // 자리에 등록하라고 요구하게 되고, 로봇이 답할 수 없는 요구를 띄우면
        // 그 화면 전체를 안 믿게 된다.
        assertEquals(SiteNames.Unsupported, adapter().knownSiteNames())
    }

    @Test
    fun `sport 가 떠 있어도 답이 같다`() {
        // **환경이 아니라 기종의 사실이다.** 고수준 서비스가 붙든 말든
        // 이름을 둘 자리는 생기지 않는다 — Spot 의 지도 계층과 다른 점이다.
        assertEquals(SiteNames.Unsupported, adapter(sport = null).knownSiteNames())
    }

    // ── 결함

    @Test
    fun `저수준 상태가 없으면 결함 없음이 아니라 모른다`() {
        // 빈 목록으로 답하면 과열된 기체가 정상으로 보인다. 원장의
        // `Observed`/`NotObservable`이 갈라져 있는 것과 같은 이유다.
        val observation = adapter(low = FakeLowLevel(state = null)).faults()
        assertIs<FaultObservation.NotObservable>(observation)
    }

    @Test
    fun `과열이 벤더 확장 접두사로 나간다`() {
        // 계약의 코어 여덟에 과열이 없고 **없는 것이 맞다.** §4.6이 벤더
        // 확장에 `X_<VENDOR>_` 를 두었고 이것이 그 자리다.
        val hot = FakeLowLevel(state = lowState(temperatures = listOf(40, 95)))
        val observed = assertIs<FaultObservation.Observed>(adapter(low = hot).faults())

        assertEquals(listOf("X_UNITREE_MOTOR_OVERHEAT"), observed.faults.map { it.errorType })
        assertTrue(observed.faults.single().errorHint.isNotBlank(), "사람이 할 조치가 비어 있다")
    }

    @Test
    fun `종착 뒤에 관절이 돌면 래치 위반을 낸다`() {
        // **`sport` 서비스가 완료를 알려 주지 않는다.** 어댑터가 시계로
        // 성공을 적으므로 로봇이 실제로 멈췄는지는 별개이며, 그 간격을 이
        // 결함이 메운다. 둘 중 하나만 두면 "끝났다고 적었는데 계속 걷는"
        // 상태가 조용히 성공으로 남는다.
        val moving = FakeLowLevel(state = lowState(velocities = listOf(0.0, 0.9)))
        val a = adapter(low = moving)
        a.accept("move_relative", move, t0)
        a.poll(t0.plusSeconds(3))

        val observed = assertIs<FaultObservation.Observed>(a.faults())
        assertEquals(listOf("TERMINAL_STATE_VIOLATED"), observed.faults.map { it.errorType })
    }

    @Test
    fun `종착 전에는 관절이 돌아도 위반이 아니다`() {
        // 위 시험이 "관절이 돌면 언제나 빨갛다"로 통과하는 것을 막는다 —
        // 그러면 걷는 로봇이 전부 위반이 된다.
        val moving = FakeLowLevel(state = lowState(velocities = listOf(0.0, 0.9)))
        val a = adapter(low = moving)
        a.accept("move_relative", move, t0)

        val observed = assertIs<FaultObservation.Observed>(a.faults())
        assertEquals(emptyList(), observed.faults.map { it.errorType })
    }

    // ── 설정

    // ── 운동 상태 (2026-09-09)

    @Test
    fun `도는 중에 FSM 이 기대와 다르면 결함을 낸다`() {
        // **시계 판정의 반대쪽 구멍이다.** `SetVelocity` 가 받아들여져도 로봇이
        // damp·sit 같은 데 있으면 아무 일도 안 일어나는데, 이 어댑터는 성공을
        // 시계로 적으므로 **시간만 지나면 성공이 된다.** 그 조용한 통과를
        // 여기서 보이게 한다.
        val mode = FakeMode(SportModeState(fsmId = 1, fsmMode = 0, taskId = 0, taskTimeSeconds = 0.0))
        val a = adapter(low = FakeLowLevel(state = lowState()), mode = mode)
        a.accept("move_relative", move, t0)

        val observed = a.faults()
        assertTrue(observed is FaultObservation.Observed, "$observed")
        assertTrue(
            observed.faults.any { it.errorType == "X_UNITREE_FSM_UNEXPECTED" },
            "FSM 이 1(damp)인데 결함이 없다: ${observed.faults.map { it.errorType }}",
        )
    }

    @Test
    fun `FSM 이 기대와 같으면 결함이 아니다`() {
        // **한쪽만 보면 "언제나 결함을 내는" 판정이 통과한다.**
        val mode = FakeMode(SportModeState(fsmId = fsm.start, fsmMode = 0, taskId = 0, taskTimeSeconds = 0.0))
        val a = adapter(low = FakeLowLevel(state = lowState()), mode = mode)
        a.accept("move_relative", move, t0)

        val observed = a.faults()
        assertTrue(observed is FaultObservation.Observed, "$observed")
        assertEquals(
            emptyList(),
            observed.faults.filter { it.errorType == "X_UNITREE_FSM_UNEXPECTED" },
            "기대한 모드인데 결함을 냈다",
        )
    }

    @Test
    fun `종착한 태스크에는 FSM 결함을 안 낸다`() {
        // **결함 주입이 이 시험을 요구했다.** 앞 판에는 이것이 없어서
        // `running.state == RUNNING` 검사를 통째로 지워도 스물세 개가 전부
        // 초록이었다. 걷기가 끝나면 로봇이 서는 모드로 돌아가는 것이 정상이고,
        // 그때 *"FSM 이 기대와 다르다"* 를 내면 **정상 종료마다 결함이 뜬다.**
        val mode = FakeMode(SportModeState(fsmId = 1, fsmMode = 0, taskId = 0, taskTimeSeconds = 0.0))
        val a = adapter(low = FakeLowLevel(state = lowState()), mode = mode)
        a.accept("move_relative", move, t0)
        a.poll(t0.plusSeconds(3))

        assertEquals(TaskState.TASK_STATE_SUCCEEDED, a.state, "시계가 아직 종착으로 안 옮겼다")
        val observed = a.faults()
        assertTrue(observed is FaultObservation.Observed, "$observed")
        assertEquals(
            emptyList(),
            observed.faults.filter { it.errorType == "X_UNITREE_FSM_UNEXPECTED" },
            "끝난 태스크에 FSM 결함을 냈다",
        )
    }

    @Test
    fun `운동 상태 채널이 없으면 이 결함을 안 낸다`() {
        // **없는 것과 어긋난 것은 다르다.** 토픽을 못 받는 대상(시뮬레이터)에서
        // 결함을 내면 "모른다" 가 "틀렸다" 로 보고된다 — 이 저장소가
        // `Unavailable` 과 0 을 가른 것과 같은 규율이다.
        val a = adapter(low = FakeLowLevel(state = lowState()), mode = null)
        a.accept("move_relative", move, t0)

        val observed = a.faults()
        assertTrue(observed is FaultObservation.Observed, "$observed")
        assertEquals(
            emptyList(),
            observed.faults.filter { it.errorType == "X_UNITREE_FSM_UNEXPECTED" },
            "채널이 없는데 어긋났다고 적었다",
        )
    }

    @Test
    fun `태스크가 없으면 FSM 이 달라도 결함이 아니다`() {
        // 아무것도 안 시켰는데 로봇이 damp 에 있는 것은 정상이다.
        val mode = FakeMode(SportModeState(fsmId = 1, fsmMode = 0, taskId = 0, taskTimeSeconds = 0.0))
        val observed = adapter(low = FakeLowLevel(state = lowState()), mode = mode).faults()

        assertTrue(observed is FaultObservation.Observed, "$observed")
        assertEquals(
            emptyList(),
            observed.faults.filter { it.errorType == "X_UNITREE_FSM_UNEXPECTED" },
            "태스크가 없는데 어긋났다고 적었다",
        )
    }

    @Test
    fun `FSM 번호가 음수면 기동에서 막는다`() {
        // 잘못 배선된 설정이 런타임 깊은 곳에서 터지는 것보다 여기서 터지는
        // 편이 싸다.
        assertFailsWith<IllegalArgumentException> { FsmProfile(start = -1) }
    }

    // ── 가짜 남쪽

    private fun lowState(
        temperatures: List<Int> = listOf(35, 36),
        velocities: List<Double> = listOf(0.0, 0.0),
    ) = LowState(tick = 1, modeMachine = 4, motorTemperaturesCelsius = temperatures, motorVelocities = velocities)

    private data class Velocity(val vx: Double, val vy: Double, val omega: Double, val duration: Double)

    private class FakeSport(private val failFrom: Int = Int.MAX_VALUE) : SportService {
        val velocities = mutableListOf<Velocity>()
        var fsmId = 0

        override fun setFsmId(id: Int): Result<Unit> {
            fsmId = id
            return Result.success(Unit)
        }

        override fun getFsmId(): Result<Int> = Result.success(fsmId)

        override fun setVelocity(vx: Double, vy: Double, omega: Double, durationSeconds: Double): Result<Unit> {
            if (velocities.size >= failFrom) return Result.failure(IllegalStateException("링크 끊김"))
            velocities += Velocity(vx, vy, omega, durationSeconds)
            return Result.success(Unit)
        }
    }

    private class FakeLowLevel(private val state: LowState? = null) : LowLevelChannel {
        override fun latestState(): LowState? = state
    }

    private class FakeMode(var mode: SportModeState? = null) : SportModeChannel {
        override fun latestSportMode(): SportModeState? = mode
    }

    private class FakeLink(
        override val sport: SportService?,
        override val lowLevel: LowLevelChannel,
        override val sportMode: SportModeChannel? = null,
    ) : G1Link
}
