package dev.picasso.adapter.orbit

import dev.picasso.adapter.core.Acceptance
import dev.picasso.adapter.core.AdapterIdentity
import dev.picasso.adapter.core.Applied
import dev.picasso.adapter.core.FaultObservation
import dev.picasso.adapter.core.HoldObservation
import dev.picasso.adapter.core.ProgressObservation
import dev.picasso.adapter.core.Refusal
import dev.picasso.adapter.core.SiteNames
import dev.picasso.contracts.v1.FailureClass
import dev.picasso.contracts.v1.TaskState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 플릿 층에 붙은 어댑터가 **무엇을 하고 무엇을 못 하는가.**
 *
 * 가짜 Orbit 은 벤더의 모양만 흉내낸다 — 미션은 이름과 uuid, 실행은 `endTime` 과 자유 문자열 상태, 사건은 정수
 * 오류. 그 모양이 곧 이 측정의 결과이고, 어댑터가 그 위에서 무엇을 **못 하는지**가 이 시험의 절반이다.
 */
class OrbitAdapterTest {

    // ── 가짜 벤더 표면

    private class FakeMissions(var known: List<OrbitMission>, var failure: Throwable? = null) : MissionLayer {
        override fun missions(): Result<List<OrbitMission>> =
            failure?.let { Result.failure(it) } ?: Result.success(known)
    }

    private class FakeDispatch(var answer: DispatchResult = DispatchResult.Accepted("run-1")) : DispatchLayer {
        val sent = mutableListOf<Triple<String, String, String>>()
        override fun dispatch(nickname: String, missionUuid: String, driverId: String): DispatchResult {
            sent += Triple(nickname, missionUuid, driverId)
            return answer
        }
    }

    private class FakeRuns(
        var run: OrbitRun? = null,
        var events: List<OrbitRunEvent> = emptyList(),
        var eventsFailure: Throwable? = null,
    ) : RunLayer {
        override fun latestRun(nickname: String): Result<OrbitRun?> = Result.success(run)
        override fun events(runUuid: String): Result<List<OrbitRunEvent>> =
            eventsFailure?.let { Result.failure(it) } ?: Result.success(events)
    }

    private class FakeLink(
        override val fleet: FleetLayer? = null,
        override val missions: MissionLayer? = null,
        override val dispatch: DispatchLayer? = null,
        override val runs: RunLayer? = null,
    ) : OrbitLink

    private fun run(
        end: String? = null,
        status: String? = "unknown-to-us",
        actions: Int = 3,
        pending: Int = 0,
    ) = OrbitRun(
        uuid = "run-1", robotNickname = NICKNAME, robotSerial = "sn-1",
        missionName = "DOCK-3", missionStatus = status, endTime = end,
        actionCount = actions, pendingActionCount = pending,
    )

    private fun world(
        missions: FakeMissions = FakeMissions(listOf(OrbitMission("m-1", "DOCK-3"))),
        dispatch: FakeDispatch? = FakeDispatch(),
        runs: FakeRuns? = FakeRuns(),
    ): Triple<OrbitAdapter, FakeDispatch?, FakeRuns?> {
        val link = FakeLink(missions = missions, dispatch = dispatch, runs = runs)
        return Triple(OrbitAdapter(link, IDENTITY, NICKNAME, DRIVER), dispatch, runs)
    }

    // ── 드는 것

    @Test
    fun `계약의 사이트 이름을 저작된 미션의 이름으로 읽어 파견한다`() {
        val (adapter, dispatch, _) = world()
        val accepted = adapter.accept("t-1", "navigate_to", mapOf("location" to "DOCK-3"), NOW)

        assertEquals(Acceptance.Accepted("t-1"), accepted)
        // **별명으로 지목하고 uuid 로 시킨다.** 계약이 나르는 것은 이름이고 벤더가 받는 것은 uuid 다(ADR 35).
        assertEquals(listOf(Triple(NICKNAME, "m-1", DRIVER)), dispatch!!.sent)
        assertEquals(TaskState.TASK_STATE_RUNNING, adapter.state)
    }

    @Test
    fun `아는 이름은 플릿의 미션 이름이다`() {
        val (adapter, _, _) = world(FakeMissions(listOf(OrbitMission("m-2", "BAY-7"), OrbitMission("m-1", "DOCK-3"))))
        assertEquals(SiteNames.Known(listOf("BAY-7", "DOCK-3")), adapter.knownSiteNames())
    }

    @Test
    fun `진행률은 플릿이 세어 주는 액션 개수 둘에서 온다`() {
        // **벤더가 개수를 준다** — `Run.actionCount` 와 `Run.pendingActionCount`. 국면을 분수로 지어내는 것과
        // 다른 종류이며, 그래서 이 어댑터는 진행률을 낼 수 있다.
        val (adapter, _, runs) = world()
        adapter.accept("t-1", "navigate_to", mapOf("location" to "DOCK-3"), NOW)

        // 아직 실행을 못 봤다. **0 이 아니라 못 잰다다.**
        assertIs<ProgressObservation.NotObservable>(adapter.progress())

        runs!!.run = run(actions = 4, pending = 3)
        adapter.poll(NOW)
        val quarter = assertIs<ProgressObservation.Fraction>(adapter.progress())
        assertEquals(0.25, quarter.fraction, 1e-9)
        assertEquals("행동 1/4", quarter.basis, "무엇을 셌는지 안 적으면 그 숫자가 무슨 뜻인지 아무도 모른다")

        runs.run = run(actions = 4, pending = 0)
        adapter.poll(NOW)
        assertEquals(1.0, assertIs<ProgressObservation.Fraction>(adapter.progress()).fraction, 1e-9)
    }

    @Test
    fun `액션 개수가 0 이면 못 잰다고 한다`() {
        // 나눌 수 없다. 0 을 답하면 *아직 아무것도* 로 읽히고, 1 을 답하면 다 된 것으로 읽힌다.
        val (adapter, _, runs) = world()
        adapter.accept("t-1", "navigate_to", mapOf("location" to "DOCK-3"), NOW)
        runs!!.run = run(actions = 0, pending = 0)
        adapter.poll(NOW)
        assertIs<ProgressObservation.NotObservable>(adapter.progress())
    }

    // ── 안 드는 것 — 이것이 측정 결과다

    @Test
    fun `갱신은 첫 칸이 없어 안 든다 — 조사한 넷 중 여기뿐이다`() {
        val (adapter, _, _) = world()
        adapter.accept("t-1", "navigate_to", mapOf("location" to "DOCK-3"), NOW)

        val refused = assertIs<Applied.Refused>(adapter.update("t-1", "navigate_to", mapOf("location" to "BAY-7"), NOW))
        assertEquals(Refusal.NO_VENDOR_PRIMITIVE, refused.reason)
        // 파견만 다시 하면 앞 미션이 계속 도는 채로 새것이 붙는지 플릿이 거절하는지 알 방법이 없다.
        assertTrue("멈추는 문이 없어" in refused.detail, refused.detail)
    }

    @Test
    fun `스킬 넷 중 셋은 플릿 층에 프리미티브가 없다`() {
        val (adapter, dispatch, _) = world()
        listOf("pick_place", "inspect", "move_relative").forEach { skill ->
            val refused = assertIs<Acceptance.Refused>(adapter.accept("t-1", skill, mapOf("location" to "DOCK-3"), NOW))
            assertEquals(Refusal.UNSUPPORTED_SKILL, refused.reason, skill)
        }
        // **아무것도 안 보냈다.** 억지로 미션 하나를 골라 보내면 상류는 조작을 시켰다고 믿는다.
        assertEquals(emptyList(), dispatch!!.sent)
    }

    @Test
    fun `취소도 일시정지도 없고, 결함과 잔여 파지는 못 본다`() {
        val (adapter, _, _) = world()
        adapter.accept("t-1", "navigate_to", mapOf("location" to "DOCK-3"), NOW)

        assertEquals(Refusal.NO_VENDOR_PRIMITIVE, assertIs<Applied.Refused>(adapter.cancel()).reason)
        assertEquals(Refusal.NO_VENDOR_PRIMITIVE, assertIs<Applied.Refused>(adapter.pause()).reason)
        // **없다가 아니라 못 본다.** 접으면 결함 없는 로봇·빈손인 로봇으로 보인다.
        assertIs<FaultObservation.NotObservable>(adapter.faults())
        assertIs<HoldObservation.NotObservable>(adapter.hold())
        // 실행이 있어야 알 수 있는 것을 지금 값인 척하지 않는다.
        assertNull(adapter.robotSoftware())
    }

    @Test
    fun `파견 경로가 없는 배포본에서는 접수하지 않는다`() {
        val (adapter, _, _) = world(dispatch = null)
        val refused = assertIs<Acceptance.Refused>(adapter.accept("t-1", "navigate_to", mapOf("location" to "DOCK-3"), NOW))
        // 게시 스펙에 없는 경로다 — 있는 척하면 못 붙는 인스턴스에서 조용히 초록이 된다.
        assertEquals(Refusal.VENDOR_SURFACE_ABSENT, refused.reason)
    }

    // ── 이름의 주인은 사이트다

    @Test
    fun `모르는 이름과 겹치는 이름을 가른다`() {
        val (unknown, _, _) = world(FakeMissions(listOf(OrbitMission("m-9", "OTHER"))))
        assertEquals(
            Refusal.SITE_NAME_UNKNOWN,
            assertIs<Acceptance.Refused>(unknown.accept("t-1", "navigate_to", mapOf("location" to "DOCK-3"), NOW)).reason,
        )

        // **같은 이름이 둘이면 고르지 않는다.** 고르면 상류가 지목한 것과 다른 것이 돌 수 있다.
        val (ambiguous, dispatch, _) = world(FakeMissions(listOf(OrbitMission("m-1", "DOCK-3"), OrbitMission("m-2", "DOCK-3"))))
        assertEquals(
            Refusal.SITE_NAME_AMBIGUOUS,
            assertIs<Acceptance.Refused>(ambiguous.accept("t-1", "navigate_to", mapOf("location" to "DOCK-3"), NOW)).reason,
        )
        assertEquals(emptyList(), dispatch!!.sent)
    }

    // ── 종착의 판정

    @Test
    fun `종착은 endTime 이 정하고 자유 문자열은 판정에 안 쓴다`() {
        val (adapter, _, runs) = world()
        adapter.accept("t-1", "navigate_to", mapOf("location" to "DOCK-3"), NOW)

        // 벤더가 "Completed" 라 말해도 endTime 이 없으면 아직 도는 중이다. 그 문자열의 뜻을 우리가 정할 수 없다.
        runs!!.run = run(end = null, status = "Completed")
        assertEquals(TaskState.TASK_STATE_RUNNING, adapter.poll(NOW))

        // 반대로 상태 문자열을 몰라도 endTime 이 있으면 끝난 것이다.
        runs.run = run(end = "2026-09-10T00:05:00Z", status = "무엇인지-모르는-값")
        assertEquals(TaskState.TASK_STATE_SUCCEEDED, adapter.poll(NOW))
        assertNull(adapter.failure())
        // 판정에 안 쓴 원문은 버리지 않고 진단에 남긴다.
        assertEquals("무엇인지-모르는-값", adapter.vendorStatus())
    }

    @Test
    fun `사건의 오류 코드가 실패를 정하고, 분류는 UNCLASSIFIED 를 벗어나지 않는다`() {
        val (adapter, _, runs) = world()
        adapter.accept("t-1", "navigate_to", mapOf("location" to "DOCK-3"), NOW)

        runs!!.run = run(end = "2026-09-10T00:05:00Z")
        runs.events = listOf(
            OrbitRunEvent("e-1", "thermal-1", error = 0, time = null),
            OrbitRunEvent("e-2", "dock", error = 42, time = null),
        )
        assertEquals(TaskState.TASK_STATE_FAILED, adapter.poll(NOW))

        val fault = adapter.failure()!!
        // **정수 하나로는 분류가 안 선다.** 지어내면 상류가 그 분류로 분기한다(§15.91).
        assertEquals(FailureClass.FAILURE_CLASS_UNCLASSIFIED, fault.failureClass)
        assertTrue("dock#42" in fault.vendorDetail, fault.vendorDetail)
        // 오류 0 은 오류가 아니다 — 접으면 성공한 실행이 전부 실패로 보인다.
        assertTrue("thermal-1" !in fault.vendorDetail, fault.vendorDetail)
    }

    @Test
    fun `사건을 못 읽으면 성공으로 접지 않는다`() {
        val (adapter, _, runs) = world()
        adapter.accept("t-1", "navigate_to", mapOf("location" to "DOCK-3"), NOW)

        runs!!.run = run(end = "2026-09-10T00:05:00Z")
        runs.eventsFailure = IllegalStateException("502")
        // 끝났는데 성패를 모른다. **틀리는 방향을 막는 쪽으로 둔다**(§15.41) — 사람이 본다.
        assertEquals(TaskState.TASK_STATE_NEEDS_INTERVENTION, adapter.poll(NOW))
        assertEquals("ORBIT_RUN_EVENTS_UNREADABLE", adapter.failure()!!.errorType)
    }

    @Test
    fun `플릿의 거절과 못 닿음을 가른다`() {
        val (refusing, _, _) = world(dispatch = FakeDispatch(DispatchResult.Refused("robot is busy")))
        val refused = assertIs<Acceptance.Refused>(refusing.accept("t-1", "navigate_to", mapOf("location" to "DOCK-3"), NOW))
        assertEquals(Refusal.VENDOR_REJECTED, refused.reason)
        assertEquals("robot is busy", refused.vendorDetail)

        val (unreachable, _, _) = world(dispatch = FakeDispatch(DispatchResult.Unreachable("timeout")))
        // **거절과 못 닿음은 다르다** — 앞은 답이고 뒤는 답이 없는 것이다. 계약에서 뒤엣것은 gRPC 상태로 간다.
        assertEquals(
            Refusal.LINK_ERROR,
            assertIs<Acceptance.Refused>(unreachable.accept("t-1", "navigate_to", mapOf("location" to "DOCK-3"), NOW)).reason,
        )
    }

    private companion object {
        const val NICKNAME = "spot-a"
        const val DRIVER = "picasso-adapter"
        val IDENTITY = AdapterIdentity("boston-dynamics", "orbit", "orbit-spot-a")
        val NOW: Instant = Instant.parse("2026-09-10T00:00:00Z")
    }
}
