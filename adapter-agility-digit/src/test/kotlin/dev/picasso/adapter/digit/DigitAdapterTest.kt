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
import kotlin.test.assertTrue

/**
 * **이름이 그대로 가는가**와 **어댑터가 시계를 안 드는가**를 붙든다.
 *
 * 이 파일은 한 번 크게 고쳐졌다. 첫 판은 *정지 워치독*을 시험했는데 그것은
 * 존재하지 말았어야 할 코드였다 — `action-duration`이 지속시간을 나르고
 * 로봇이 집행한다. 첫 판의 근거가 벤더 문서가 아니라 **제3자 래퍼**였고,
 * 래퍼가 API의 부분집합이라 없는 것을 만들어 냈다.
 *
 * 그래서 [`어댑터가 시계를 들지 않는다`]가 이 파일에서 가장 중요한 시험이다 —
 * 워치독이 다시 기어들어오면 거기서 빨개진다.
 *
 * 실물 없이 검증되는 범위는 앞의 둘과 같다(§9.7 ④·C-3). SDK 릴리스가
 * 2021년판이라는 한계가 하나 더 있다 — **"있다"는 확실하고 "없다"는 그 시점
 * 기준이다.**
 */
class DigitAdapterTest {

    private val t0: Instant = Instant.parse("2026-09-08T00:00:00Z")
    private val identity = AdapterIdentity("agility-robotics", "agility-digit", "digit-01")

    private val move = mapOf<String, Any>(
        "forward_speed" to 0.6, "lateral_speed" to 0.0, "yaw_rate" to 0.1, "duration" to 2.0,
    )
    private val navigate = mapOf<String, Any>("location" to "dock-3")
    private val pickPlace = mapOf<String, Any>("object_id" to "tote-7", "destination" to "shelf-b")

    private fun adapter(link: FakeLink = FakeLink(), identity: AdapterIdentity = this.identity) =
        DigitAdapter(link, identity)

    // ── 이름이 그대로 간다 (ADR 34·35)

    @Test
    fun `목적지 이름이 그대로 간다`() {
        // 옮기는 표가 **없는 것**이 요점이다(ADR 34). 그 이름을 로봇이 알게
        // 만드는 것은 `add-object` 로 하는 사이트 작업이다(ADR 35).
        val link = FakeLink()
        DigitAdapter(link, identity).accept("navigate_to", navigate, t0)
        assertEquals(listOf("dock-3"), link.gotos)
    }

    @Test
    fun `집을 것과 놓을 곳이 둘 다 이름으로 간다`() {
        // **조사한 셋 중 시맨틱 집기·놓기를 파는 유일한 기종이다.**
        // `action-pick{object: ObjectSelector}` · `action-place{reference_frame}`.
        val link = FakeLink()
        DigitAdapter(link, identity).accept("pick_place", pickPlace, t0)
        assertEquals(listOf("tote-7" to "shelf-b"), link.pickPlaces)
    }

    // ── 어댑터가 시계를 안 든다 (앞 판의 결함에 대한 회귀 방어)

    @Test
    fun `지속시간이 로봇으로 넘어간다`() {
        // `action-duration{action, duration}` 이 감싸므로 **로봇이 집행한다.**
        val link = FakeLink()
        DigitAdapter(link, identity).accept("move_relative", move, t0)
        assertEquals(listOf(Move(yawRate = 0.1, forward = 0.6, lateral = 0.0, duration = 2.0)), link.moves)
    }

    @Test
    fun `어댑터가 시계를 들지 않는다`() {
        // **이 파일에서 가장 중요한 시험이다.** 앞 판은 지속시간이 지나면
        // 어댑터가 스스로 정지를 보내고 성공으로 적었다. 그 코드는 존재하지
        // 말았어야 했다 — 지속시간은 로봇이 집행하므로 성공 판정은 **오직
        // 상태 스트림에서만** 와야 한다.
        //
        // 워치독이 다시 기어들어오면 여기서 빨개진다.
        val link = FakeLink()
        val a = DigitAdapter(link, identity)
        a.accept("move_relative", move, t0)

        assertEquals(TaskState.TASK_STATE_RUNNING, a.poll(t0.plusSeconds(9_999)))
        assertEquals(0, link.removed, "어댑터가 시계를 보고 액션을 건드렸다")

        link.reported = ActionStatus.SUCCESS
        assertEquals(TaskState.TASK_STATE_SUCCEEDED, a.poll(t0.plusSeconds(9_999)))
    }

    // ── §4.4 래치 — 종착이 안 래치되는 유일한 기종

    @Test
    fun `종착 뒤에 running 이 다시 오면 래치 위반을 낸다`() {
        val link = FakeLink(reported = ActionStatus.SUCCESS)
        val a = DigitAdapter(link, identity)
        a.accept("navigate_to", navigate, t0)
        a.poll(t0.plusSeconds(1))
        assertEquals(TaskState.TASK_STATE_SUCCEEDED, a.state)

        link.reported = ActionStatus.RUNNING
        a.poll(t0.plusSeconds(2))

        assertEquals(TaskState.TASK_STATE_SUCCEEDED, a.state, "상태를 되돌렸다 — 래치가 깨졌다")
        val observed = assertIs<FaultObservation.Observed>(a.faults())
        assertTrue("TERMINAL_STATE_VIOLATED" in observed.faults.map { it.errorType })
    }

    @Test
    fun `종착 전의 running 은 위반이 아니다`() {
        // 위 시험이 "running 이면 언제나 빨갛다" 로 통과하는 것을 막는다.
        val link = FakeLink(reported = ActionStatus.RUNNING)
        val a = DigitAdapter(link, identity)
        a.accept("navigate_to", navigate, t0)
        a.poll(t0.plusSeconds(1))

        val observed = assertIs<FaultObservation.Observed>(a.faults())
        assertEquals(emptyList(), observed.faults.map { it.errorType })
    }

    // ── 취소 = remove-action

    @Test
    fun `취소가 액션을 지운다`() {
        val link = FakeLink()
        val a = DigitAdapter(link, identity)
        a.accept("navigate_to", navigate, t0)

        assertEquals(Applied.Ok, a.cancel())
        assertEquals(1, link.removed)
        assertEquals(TaskState.TASK_STATE_CANCELLED, a.state)
    }

    @Test
    fun `지웠다는데 아직 돌면 취소됐다고 적지 않는다`() {
        // 매뉴얼이 `remove-action` 이 **컨테이너에 대해서는 성공한 것처럼
        // 보인다**고 적었다. `pick_place` 가 바로 그 컨테이너
        // (`action-sequential`)라 응답만 믿으면 걷고 있는 로봇을 취소됐다고
        // 적게 된다.
        val link = FakeLink(reported = ActionStatus.RUNNING)
        val a = DigitAdapter(link, identity)
        a.accept("pick_place", pickPlace, t0)

        val refused = assertIs<Applied.Refused>(a.cancel())
        assertEquals(Refusal.LINK_ERROR, refused.reason)
        assertEquals(TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED, a.state)
    }

    @Test
    fun `지우기가 실패하면 취소됐다고 적지 않는다`() {
        val link = FakeLink(removeFails = true)
        val a = DigitAdapter(link, identity)
        a.accept("navigate_to", navigate, t0)

        assertEquals(Refusal.LINK_ERROR, assertIs<Applied.Refused>(a.cancel()).reason)
        assertEquals(TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED, a.state)
    }

    // ── 권한 = §4.9

    @Test
    fun `권한이 없으면 받지 않는다`() {
        val refused = assertIs<Acceptance.Refused>(
            adapter(FakeLink(privilege = PrivilegeState.LOST)).accept("navigate_to", navigate, t0),
        )
        assertEquals(Refusal.CONTROL_AUTHORITY_LOST, refused.reason)
    }

    @Test
    fun `권한을 잃으면 하던 일이 사라진다`() {
        // **Spot 의 리스 거절보다 결과가 세다** — 로봇이 action-idle 로
        // 리셋되므로 태스크가 실패한 것이다.
        val link = FakeLink()
        val a = DigitAdapter(link, identity)
        a.accept("navigate_to", navigate, t0)

        link.privilege = PrivilegeState.LOST
        assertEquals(TaskState.TASK_STATE_FAILED, a.poll(t0.plusSeconds(1)))
        val observed = assertIs<FaultObservation.Observed>(a.faults())
        assertTrue("CONTROL_AUTHORITY_LOST" in observed.faults.map { it.errorType })
    }

    // ── 결함 분류를 포기하는 것

    @Test
    fun `분류하지 못하는 실패를 코어 어휘인 척하지 않는다`() {
        // 벤더가 주는 것이 자유 문자열 하나뿐이고 **SDK 전수를 읽고도
        // 그대로였다.** 코어 여덟 중 하나로 접으면 우리가 지어낸 분류가
        // 로봇의 판정으로 읽힌다.
        val link = FakeLink(error = "Left leg motor stalled during swing phase")
        val fault = assertIs<FaultObservation.Observed>(adapter(link).faults()).faults.single()

        assertEquals("X_AGILITYROBOTICS_UNCLASSIFIED", fault.errorType)
        assertEquals("Left leg motor stalled during swing phase", fault.errorHint, "원문을 그대로 안 실었다")
    }

    @Test
    fun `아무 일도 없으면 결함이 없다`() {
        val observed = assertIs<FaultObservation.Observed>(adapter().faults())
        assertEquals(emptyList(), observed.faults.map { it.errorType })
    }

    // ── 계약 규율

    @Test
    fun `일시정지는 언제나 거절한다`() {
        // **이 NO 는 근거가 있다** — SDK 메시지 전수에 일시정지가 없다.
        val a = adapter()
        a.accept("navigate_to", navigate, t0)
        assertEquals(Refusal.NO_VENDOR_PRIMITIVE, assertIs<Applied.Refused>(a.pause()).reason)
        assertEquals(TaskState.TASK_STATE_RUNNING, a.state, "거절이 상태를 건드렸다")
    }

    @Test
    fun `inspect 만 못 든다`() {
        // 셋은 들고 하나는 못 든다. 거리 측정과 같은 답이어야 한다.
        listOf("move_relative" to move, "navigate_to" to navigate, "pick_place" to pickPlace)
            .forEach { (skill, params) ->
                assertIs<Acceptance.Accepted>(adapter().accept(skill, params, t0), "$skill 을 못 들었다")
            }

        assertEquals(
            Refusal.UNSUPPORTED_SKILL,
            assertIs<Acceptance.Refused>(adapter().accept("inspect", mapOf("target" to "x"), t0)).reason,
        )
    }

    @Test
    fun `필수 파라미터가 빠지면 값을 지어내지 않는다`() {
        listOf(
            "move_relative" to move - "duration",
            "navigate_to" to emptyMap(),
            "pick_place" to pickPlace - "destination",
        ).forEach { (skill, params) ->
            assertEquals(
                Refusal.PARAMETER_MISSING,
                assertIs<Acceptance.Refused>(adapter().accept(skill, params, t0)).reason,
                "$skill 의 누락",
            )
        }
    }

    @Test
    fun `종착한 태스크는 조작을 거절한다`() {
        val a = adapter(FakeLink(reported = ActionStatus.SUCCESS))
        a.accept("navigate_to", navigate, t0)
        a.poll(t0.plusSeconds(1))

        assertEquals(Refusal.TERMINAL_LATCHED, assertIs<Applied.Refused>(a.cancel()).reason)
        assertEquals(TaskState.TASK_STATE_SUCCEEDED, a.state)
    }

    @Test
    fun `이미 도는 태스크가 있으면 받지 않는다`() {
        val a = adapter()
        assertIs<Acceptance.Accepted>(a.accept("navigate_to", navigate, t0))
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
            assertIs<Acceptance.Refused>(adapter(identity = blank).accept("navigate_to", navigate, t0)).reason,
        )
    }

    // ── 가짜 남쪽

    private data class Move(val yawRate: Double, val forward: Double, val lateral: Double, val duration: Double)

    private class FakeLink(
        override var privilege: PrivilegeState = PrivilegeState.HELD,
        var reported: ActionStatus? = null,
        private val error: String? = null,
        private val removeFails: Boolean = false,
    ) : DigitLink {
        val moves = mutableListOf<Move>()
        val gotos = mutableListOf<String>()
        val pickPlaces = mutableListOf<Pair<String, String>>()
        var removed = 0
        private var next = 0

        private fun issue(): Result<ActionRef> {
            next += 1
            return Result.success(ActionRef(next))
        }

        override fun moveFor(
            yawRate: Double,
            forward: Double,
            lateral: Double,
            durationSeconds: Double,
        ): Result<ActionRef> {
            moves += Move(yawRate, forward, lateral, durationSeconds)
            return issue()
        }

        override fun gotoNamed(name: String): Result<ActionRef> {
            gotos += name
            return issue()
        }

        override fun pickAndPlace(objectName: String, destinationName: String): Result<ActionRef> {
            pickPlaces += objectName to destinationName
            return issue()
        }

        override fun removeAction(ref: ActionRef): Result<Unit> {
            if (removeFails) return Result.failure(IllegalStateException("소켓 끊김"))
            removed += 1
            return Result.success(Unit)
        }

        override fun status(): ActionStatus? = reported
        override fun error(): String? = error
    }
}
