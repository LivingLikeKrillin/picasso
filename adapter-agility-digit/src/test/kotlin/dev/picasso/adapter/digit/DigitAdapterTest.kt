package dev.picasso.adapter.digit

import dev.picasso.adapter.core.Acceptance
import dev.picasso.adapter.core.AdapterIdentity
import dev.picasso.adapter.core.Applied
import dev.picasso.adapter.core.FaultObservation
import dev.picasso.adapter.core.Refusal
import dev.picasso.contracts.v1.TaskState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **정지 워치독과 래치**를 붙든다 — 이 기종에만 있는 둘이다.
 *
 * G1 시험은 *없는 것을 만들면서 만든 티를 내는지*를, Spot 시험은 *어느 스킬이
 * 어느 층에 올라타는지*를 봤다. 여기서 보는 것은 **어댑터가 진 빚**이다:
 * `action-move`가 지속시간을 안 받으므로 정지는 어댑터의 의무이고, 어댑터가
 * 죽으면 로봇이 계속 걷는다.
 *
 * 실물 없이 검증되는 범위는 앞의 둘과 같다. **"실물 Digit이 이대로
 * 행동하는가"는 안 본다** — §9.7 ④·C-3이며 열려 있다. 여기서는 근거 등급까지
 * 한 단계 낮다(§15.65).
 */
class DigitAdapterTest {

    private val t0: Instant = Instant.parse("2026-09-08T00:00:00Z")
    private val identity = AdapterIdentity("agility-robotics", "agility-digit", "digit-01")

    private val move = mapOf(
        "forward_speed" to 0.6, "lateral_speed" to 0.0, "yaw_rate" to 0.1, "duration" to 2.0,
    )

    private fun adapter(link: FakeLink = FakeLink(), identity: AdapterIdentity = this.identity) =
        DigitAdapter(link, identity)

    // ── 어댑터가 진 빚 (이 파일의 핵심)

    @Test
    fun `시간이 되면 어댑터가 정지를 보낸다`() {
        // **`action-move` 는 지속시간을 안 받는다.** 아무도 안 멈추면 계속
        // 걷는다 — 다른 두 기종에서는 로봇이 스스로 섰다.
        val link = FakeLink()
        val a = DigitAdapter(link, identity)
        a.accept("move_relative", move, t0)

        assertEquals(0, link.stands, "아직 멈출 때가 아닌데 멈췄다")
        assertEquals(TaskState.TASK_STATE_RUNNING, a.poll(t0.plusMillis(1_999)))
        assertEquals(0, link.stands)

        assertEquals(TaskState.TASK_STATE_SUCCEEDED, a.poll(t0.plusMillis(2_000)))
        assertEquals(1, link.stands, "시간이 됐는데 정지를 안 보냈다")
    }

    @Test
    fun `갚아야 할 정지 시각이 밖에서 보인다`() {
        // 어댑터가 죽으면 이 빚이 아무 데도 안 남는다. 고칠 수는 없고
        // **드러낼 수는 있다** — 감시자가 이 시각으로 어댑터의 생사를 본다.
        val a = adapter()
        assertNull(a.stopDueAt, "아무것도 안 들었는데 갚을 빚이 있다")

        a.accept("move_relative", move, t0)
        assertEquals(t0.plusSeconds(2), assertNotNull(a.stopDueAt))

        a.poll(t0.plusSeconds(3))
        assertNull(a.stopDueAt, "정지를 보냈는데 빚이 남아 있다")
    }

    @Test
    fun `정지가 실패하면 성공으로 적지 않는다`() {
        // **이 기종에서 가장 나쁜 거짓말이다** — 걷고 있는데 끝났다고 적는 것.
        val link = FakeLink(standFails = true)
        val a = DigitAdapter(link, identity)
        a.accept("move_relative", move, t0)

        assertEquals(TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED, a.poll(t0.plusSeconds(3)))
    }

    // ── §4.4 래치 — 이 기종에서 처음 실전이다

    @Test
    fun `종착 뒤에 running 이 다시 오면 래치 위반을 낸다`() {
        // 매뉴얼이 *"This status does not latch once reached"* 라 적은 그 전이다.
        // G1 에서는 관절 각속도로 추론했는데 **여기서는 벤더가 직접 보낸다.**
        val link = FakeLink()
        val a = DigitAdapter(link, identity)
        a.accept("move_relative", move, t0)
        a.poll(t0.plusSeconds(3))
        assertEquals(TaskState.TASK_STATE_SUCCEEDED, a.state)

        link.reported = ActionStatus.RUNNING
        a.poll(t0.plusSeconds(4))

        assertEquals(TaskState.TASK_STATE_SUCCEEDED, a.state, "상태를 되돌렸다 — 래치가 깨졌다")
        val observed = assertIs<FaultObservation.Observed>(a.faults())
        assertTrue("TERMINAL_STATE_VIOLATED" in observed.faults.map { it.errorType })
    }

    @Test
    fun `종착 전의 running 은 위반이 아니다`() {
        // 위 시험이 "running 이면 언제나 빨갛다" 로 통과하는 것을 막는다 —
        // 그러면 걷는 로봇이 전부 위반이 된다.
        val link = FakeLink(reported = ActionStatus.RUNNING)
        val a = DigitAdapter(link, identity)
        a.accept("move_relative", move, t0)
        a.poll(t0.plusMillis(500))

        val observed = assertIs<FaultObservation.Observed>(a.faults())
        assertEquals(emptyList(), observed.faults.map { it.errorType })
    }

    // ── 권한 = §4.9

    @Test
    fun `권한이 없으면 받지 않는다`() {
        val a = adapter(FakeLink(privilege = PrivilegeState.LOST))
        val refused = assertIs<Acceptance.Refused>(a.accept("move_relative", move, t0))
        assertEquals(Refusal.CONTROL_AUTHORITY_LOST, refused.reason)
    }

    @Test
    fun `권한을 잃으면 하던 일이 사라진다`() {
        // **Spot 의 리스 거절보다 결과가 세다.** 거기서는 요청이 거절될 뿐인데
        // 여기서는 로봇이 action-idle 로 리셋되므로 태스크가 실패한 것이다.
        val link = FakeLink()
        val a = DigitAdapter(link, identity)
        a.accept("move_relative", move, t0)

        link.privilege = PrivilegeState.LOST
        assertEquals(TaskState.TASK_STATE_FAILED, a.poll(t0.plusMillis(100)))

        val observed = assertIs<FaultObservation.Observed>(a.faults())
        assertTrue("CONTROL_AUTHORITY_LOST" in observed.faults.map { it.errorType })
    }

    // ── 결함 분류를 포기하는 것

    @Test
    fun `분류하지 못하는 실패를 코어 어휘인 척하지 않는다`() {
        // 벤더가 주는 것이 사람이 읽는 자유 문자열 하나뿐이다. 코어 여덟 중
        // 하나로 접으면 **우리가 지어낸 분류가 로봇의 판정으로 읽힌다.**
        val link = FakeLink(error = "Left leg motor stalled during swing phase")
        val observed = assertIs<FaultObservation.Observed>(adapter(link).faults())

        val fault = observed.faults.single()
        assertEquals("X_AGILITYROBOTICS_UNCLASSIFIED", fault.errorType)
        assertEquals("Left leg motor stalled during swing phase", fault.errorHint, "원문을 그대로 싣지 않았다")
    }

    @Test
    fun `아무 일도 없으면 결함이 없다`() {
        val observed = assertIs<FaultObservation.Observed>(adapter().faults())
        assertEquals(emptyList(), observed.faults.map { it.errorType })
    }

    // ── 계약 규율

    @Test
    fun `취소가 정지와 같은 액션으로 나간다`() {
        // 벤더에게 취소 프리미티브가 없고 중단은 덮어쓰기다.
        val link = FakeLink()
        val a = DigitAdapter(link, identity)
        a.accept("move_relative", move, t0)

        assertEquals(Applied.Ok, a.cancel())
        assertEquals(1, link.stands)
        assertEquals(TaskState.TASK_STATE_CANCELLED, a.state)
    }

    @Test
    fun `종착한 태스크는 조작을 거절한다`() {
        val a = adapter()
        a.accept("move_relative", move, t0)
        a.poll(t0.plusSeconds(3))

        assertEquals(Refusal.TERMINAL_LATCHED, assertIs<Applied.Refused>(a.cancel()).reason)
        assertEquals(TaskState.TASK_STATE_SUCCEEDED, a.state)
    }

    @Test
    fun `일시정지는 언제나 거절한다`() {
        val a = adapter()
        a.accept("move_relative", move, t0)
        assertEquals(Refusal.NO_VENDOR_PRIMITIVE, assertIs<Applied.Refused>(a.pause()).reason)
        assertEquals(TaskState.TASK_STATE_RUNNING, a.state, "거절이 상태를 건드렸다")
    }

    @Test
    fun `드는 스킬이 아니면 받지 않는다`() {
        // 거리 측정이 셋을 PARTIAL·NO 로 적었고 어댑터가 같은 답을 낸다.
        listOf("navigate_to", "pick_place", "inspect").forEach {
            assertEquals(
                Refusal.UNSUPPORTED_SKILL,
                assertIs<Acceptance.Refused>(adapter().accept(it, emptyMap(), t0)).reason,
                "$it 의 거절",
            )
        }
    }

    @Test
    fun `필수 파라미터가 빠지면 값을 지어내지 않는다`() {
        // **`duration` 이 특히 그렇다.** 벤더가 안 받는 값이라 어댑터가 정해
        // 버리기 가장 쉬운 자리이고, 정하는 순간 로봇이 언제 서는지를 우리가
        // 몰래 결정하게 된다.
        val refused = assertIs<Acceptance.Refused>(adapter().accept("move_relative", move - "duration", t0))
        assertEquals(Refusal.PARAMETER_MISSING, refused.reason)
        assertTrue("duration" in refused.detail)
    }

    @Test
    fun `속도 셋을 벤더 자리에 맞게 넘긴다`() {
        val link = FakeLink()
        DigitAdapter(link, identity).accept("move_relative", move, t0)
        assertEquals(listOf(Velocity(yawRate = 0.1, forward = 0.6, lateral = 0.0)), link.moves)
    }

    @Test
    fun `이미 도는 태스크가 있으면 받지 않는다`() {
        val a = adapter()
        assertIs<Acceptance.Accepted>(a.accept("move_relative", move, t0))
        assertEquals(
            Refusal.ALREADY_RUNNING,
            assertIs<Acceptance.Refused>(a.accept("move_relative", move, t0)).reason,
        )
    }

    @Test
    fun `신원이 비어 있으면 받지 않는다`() {
        val blank = AdapterIdentity("agility-robotics", "agility-digit", "")
        assertEquals(
            Refusal.IDENTITY_UNSET,
            assertIs<Acceptance.Refused>(adapter(identity = blank).accept("move_relative", move, t0)).reason,
        )
    }

    // ── 가짜 남쪽

    private data class Velocity(val yawRate: Double, val forward: Double, val lateral: Double)

    private class FakeLink(
        override var privilege: PrivilegeState = PrivilegeState.HELD,
        var reported: ActionStatus? = null,
        private val error: String? = null,
        private val standFails: Boolean = false,
    ) : DigitLink {
        val moves = mutableListOf<Velocity>()
        var stands = 0

        override fun move(yawRate: Double, forward: Double, lateral: Double): Result<Unit> {
            moves += Velocity(yawRate, forward, lateral)
            return Result.success(Unit)
        }

        override fun stand(): Result<Unit> {
            if (standFails) return Result.failure(IllegalStateException("소켓 끊김"))
            stands += 1
            return Result.success(Unit)
        }

        override fun status(): ActionStatus? = reported
        override fun error(): String? = error
    }
}
