package dev.picasso.adapter.spot

import dev.picasso.adapter.core.Acceptance
import dev.picasso.adapter.core.AdapterIdentity
import dev.picasso.adapter.core.Applied
import dev.picasso.adapter.core.HoldObservation
import dev.picasso.adapter.core.FaultObservation
import dev.picasso.adapter.core.Refusal
import dev.picasso.adapter.core.SiteNames
import dev.picasso.contracts.v1.TaskState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * **두 계층이 서로 다르게 답하는 것**을 붙든다.
 *
 * G1 시험은 "없는 것을 만들면서 만든 티를 내는지"를 봤다. 여기서는 벤더가 주는
 * 것이 훨씬 많고, 그래서 보는 것이 다르다 — **어느 스킬이 어느 층에 올라타고,
 * 그 층이 없을 때 무엇이 죽는가.**
 *
 * 실물 없이 검증되는 범위는 G1과 같다. **"실물 Spot이 이대로 행동하는가"는 안
 * 본다** — §9.7 ④·C-3이며 열려 있다.
 */
class SpotAdapterTest {

    private val t0: Instant = Instant.parse("2026-09-08T00:00:00Z")
    private val identity = AdapterIdentity("boston-dynamics", "spot-arm", "spot-01")

    private val move = mapOf<String, Any>(
        "forward_speed" to 0.5, "lateral_speed" to 0.0, "yaw_rate" to 0.0, "duration" to 2.0,
    )
    private val navigate = mapOf<String, Any>("location" to "dock-3")

    private fun adapter(
        command: FakeCommand? = FakeCommand(),
        mission: FakeMission? = FakeMission(),
        graph: FakeGraph? = mapped(),
        identity: AdapterIdentity = this.identity,
    ) = SpotAdapter(FakeLink(command, mission, graph), identity)

    /**
     * 픽스처의 [navigate] 가 갈 수 있는 지도.
     *
     * **기본값이 빈 그래프이면 안 된다** — 그러면 이 파일의 `navigate_to`
     * 시험이 전부 `SITE_NAME_UNKNOWN` 으로 죽고, 죽은 이유가 이름 해석이라는
     * 것이 다른 시험들의 실패에 묻힌다.
     */
    private fun mapped() = FakeGraph("wp-1" to "dock-3")

    // ── 층이 스킬을 가른다 (이 파일의 핵심)

    @Test
    fun `미션 계층이 없으면 navigate_to 만 죽고 move_relative 는 산다`() {
        // **G1과 갈리는 지점이다.** 거기서는 표면 하나가 없으면 전부 죽었다.
        // 여기서는 스킬마다 다른 층에 올라타므로 하나만 죽는다 — 그리고 그
        // 사실이 프로파일의 스킬 단위 선언과 맞아떨어진다.
        val onlyCommand = adapter(mission = null)

        val refused = assertIs<Acceptance.Refused>(onlyCommand.accept("navigate_to", navigate, t0))
        assertEquals(Refusal.VENDOR_SURFACE_ABSENT, refused.reason)

        assertIs<Acceptance.Accepted>(adapter(mission = null).accept("move_relative", move, t0))
    }

    @Test
    fun `명령 계층이 없으면 move_relative 가 죽는다`() {
        val refused = assertIs<Acceptance.Refused>(adapter(command = null).accept("move_relative", move, t0))
        assertEquals(Refusal.VENDOR_SURFACE_ABSENT, refused.reason)
    }

    @Test
    fun `일시정지의 답이 스킬마다 다르다`() {
        // §7.2가 `Support` 를 스킬 단위로 둔 것이 **한 로봇 안에서** 값을 하는
        // 첫 사례다. 프로파일이 `navigate_to: YES` · `move_relative: NO` 라
        // 선언했고 여기가 그것을 집행한다.
        val onMission = adapter()
        onMission.accept("navigate_to", navigate, t0)
        assertEquals(Applied.Ok, onMission.pause())
        assertEquals(TaskState.TASK_STATE_PAUSED, onMission.state)

        val onCommand = adapter()
        onCommand.accept("move_relative", move, t0)
        val refused = assertIs<Applied.Refused>(onCommand.pause())
        assertEquals(Refusal.NO_VENDOR_PRIMITIVE, refused.reason)
        assertEquals(TaskState.TASK_STATE_RUNNING, onCommand.state, "거절이 상태를 건드렸다")
    }

    // ── 계약 ↔ 벤더 변환

    @Test
    fun `상대 시간을 절대 시각으로 옮긴다`() {
        // **계약과 벤더가 어긋나는 유일한 자리다.** 계약은 `duration`, Spot은
        // `end_time`. 어긋나면 로봇이 일찍 서거나 안 선다.
        val command = FakeCommand()
        SpotAdapter(FakeLink(command, FakeMission(), mapped()), identity)
            .accept("move_relative", move, t0)

        assertEquals(listOf(t0.plusSeconds(2)), command.velocities.map { it.endTime })
        assertEquals(0.5, command.velocities.single().vx)
    }

    @Test
    fun `location 은 이름이고 로봇에 물어 id 로 옮긴다`() {
        // **이 시험이 한 번 정반대였다.** 앞 판은 `location` 이 그대로
        // 웨이포인트 id 로 간다고 단언했고, 벤더 원문이 그것을 뒤집었다 —
        // `Waypoint.id` 는 *"Unique across all maps"* 인 생성 id 이고 사람이
        // 붙인 이름은 `annotations.name` 에 따로 있다. 그대로 넘기면 상위
        // 시스템이 Spot 이 만든 id 를 알아야 하고, 그것이 A-1 위반이다.
        val mission = FakeMission()
        val graph = FakeGraph("wp-88" to "bay-7")
        SpotAdapter(FakeLink(FakeCommand(), mission, graph), identity)
            .accept("navigate_to", mapOf("location" to "bay-7"), t0)

        assertEquals(listOf("wp-88"), mission.loaded, "이름을 그대로 항법에 넘겼다")
        assertEquals(1, mission.played)
    }

    @Test
    fun `모르는 이름은 거절하고 미션을 올리지 않는다`() {
        // **받아 놓고 아무 데도 안 가는 것이 가장 나쁘다.** 그리고 이 거절이
        // 오타인지 등록 누락인지 어댑터는 모른다 — 알려면 올바른 이름의 표를
        // 어댑터가 가져야 하고, 그 순간 결속의 주인이 바뀐다(ADR 34).
        val mission = FakeMission()
        val refused = assertIs<Acceptance.Refused>(
            SpotAdapter(FakeLink(FakeCommand(), mission, FakeGraph("wp-1" to "dock-3")), identity)
                .accept("navigate_to", mapOf("location" to "dock-4"), t0),
        )

        assertEquals(Refusal.SITE_NAME_UNKNOWN, refused.reason)
        assertEquals(emptyList(), mission.loaded)
    }

    @Test
    fun `동명이 둘이면 하나를 고르지 않고 거절한다`() {
        // `annotations.name` 은 사람이 적는 문자열이고 유일성을 막는 것이
        // 없다. **임의로 고르면 로봇이 다른 자리로 가고 로그에는 성공이 남는다.**
        val mission = FakeMission()
        val refused = assertIs<Acceptance.Refused>(
            SpotAdapter(
                FakeLink(FakeCommand(), mission, FakeGraph("wp-1" to "dock-3", "wp-2" to "dock-3")),
                identity,
            ).accept("navigate_to", navigate, t0),
        )

        assertEquals(Refusal.SITE_NAME_AMBIGUOUS, refused.reason)
        assertEquals(emptyList(), mission.loaded)
    }

    @Test
    fun `지도 계층이 없으면 navigate_to 를 못 든다`() {
        // 미션 계층이 있어도 갈 곳의 이름을 옮길 데가 없다.
        val refused = assertIs<Acceptance.Refused>(
            adapter(graph = null).accept("navigate_to", navigate, t0),
        )
        assertEquals(Refusal.VENDOR_SURFACE_ABSENT, refused.reason)
    }

    // ── 프로파일이 전제한 하드웨어와 기체가 어긋났는가

    private fun withArm(arm: Boolean?, expectsArm: Boolean = true): SpotAdapter =
        SpotAdapter(FakeLink(FakeCommand(), FakeMission(), mapped(), arm), identity, expectsArm)

    private fun errorTypes(a: SpotAdapter) =
        assertIs<FaultObservation.Observed>(a.faults()).faults.map { it.errorType }

    @Test
    fun `팔을 전제했는데 기체가 팔을 안 들면 결함으로 낸다`() {
        assertEquals(listOf("X_BOSTONDYNAMICS_ARM_ABSENT"), errorTypes(withArm(arm = false)))
    }

    @Test
    fun `팔이 있으면 아무 말도 안 한다`() {
        assertEquals(emptyList(), errorTypes(withArm(arm = true)))
    }

    @Test
    fun `팔을 안 전제한 배포는 묻지도 않는다`() {
        // 팔 없는 기체에 팔 없는 프로파일을 묶는 것은 정상이다. 경보를 내면
        // **정상 배포가 빨갛게 보이고**, 그러면 운영자가 이 화면을 안 믿는다.
        val link = FakeLink(FakeCommand(), FakeMission(), mapped(), arm = false)
        val a = SpotAdapter(link, identity, expectsArm = false)

        assertEquals(emptyList(), errorTypes(a))
        assertEquals(0, link.armAsks, "안 쓸 답을 물어봤다")
    }

    @Test
    fun `못 물어본 것을 팔 없음으로 보고하지 않는다`() {
        // **이 시험이 이 묶음의 이유다.** 접으면 관측 실패가 결속 오류로 보이고
        // 운영자가 멀쩡한 기체의 배포를 뒤진다.
        assertEquals(listOf("X_BOSTONDYNAMICS_HARDWARE_UNKNOWN"), errorTypes(withArm(arm = null)))
    }

    @Test
    fun `한 번만 묻는다`() {
        // 폴마다 남쪽 호출이 늘면 신선도 창이 좁은 배치에서 그것이 비용이 된다.
        val link = FakeLink(FakeCommand(), FakeMission(), mapped(), arm = false)
        val a = SpotAdapter(link, identity, expectsArm = true)

        repeat(5) { a.faults() }
        assertEquals(1, link.armAsks)
    }

    @Test
    fun `하드웨어 결함이 리스 결함을 가리지 않는다`() {
        // 결함이 목록인 이유다. 하나만 내면 먼저 발견한 것이 나머지를 덮는다.
        val link = FakeLink(FakeCommand(reject = LeaseStatus.STATUS_REVOKED), FakeMission(), mapped(), arm = false)
        val a = SpotAdapter(link, identity, expectsArm = true)
        a.accept("move_relative", move, t0)

        assertEquals(
            listOf("X_BOSTONDYNAMICS_ARM_ABSENT", "CONTROL_AUTHORITY_LOST"),
            errorTypes(a),
        )
    }

    // ── 아는 이름을 답한다 (ADR 35)

    @Test
    fun `그래프의 사람이 붙인 이름만 답한다`() {
        // **id 를 답하면 안 된다.** 운영자가 등록한 것은 이름이고, 원장이
        // 세는 것도 이름이다. id 를 답하면 개수는 맞는데 뜻이 다르다.
        val known = adapter(graph = FakeGraph("wp-1" to "dock-3", "wp-2" to "shelf-b"))
            .knownSiteNames()

        assertEquals(SiteNames.Known(listOf("dock-3", "shelf-b")), known)
    }

    @Test
    fun `이름 없는 웨이포인트는 세지 않는다`() {
        // 지도 녹화가 이름을 요구하지 않아 대부분의 그래프에 섞여 있다.
        // **세면 개수가 부풀고 확인이 그대로 통과한다.**
        val known = adapter(graph = FakeGraph("wp-1" to "dock-3", "wp-2" to "", "wp-3" to "   "))
            .knownSiteNames()

        assertEquals(SiteNames.Known(listOf("dock-3")), known)
    }

    @Test
    fun `그래프를 못 받으면 빈 목록이 아니라 못 물어봤다고 답한다`() {
        // **이 파일에서 가장 중요한 시험이다.** 0 으로 답하면 관측 실패가
        // 사람의 태만처럼 보이고, 원장이 `CONTRADICTED` 를 띄운다 — 사실은
        // 아무것도 관측되지 않았는데.
        val known = adapter(graph = FakeGraph(fail = IllegalStateException("연결 끊김")))
            .knownSiteNames()

        assertIs<SiteNames.Unavailable>(known)
    }

    @Test
    fun `지도 계층이 없으면 호스팅 못 한다고 답한다`() {
        // 빈 목록이 아니다 — 없는 자리에 등록하라고 요구하게 된다.
        assertEquals(SiteNames.Unsupported, adapter(graph = null).knownSiteNames())
    }

    // ── 미션 상태 → 계약 상태

    @Test
    fun `미션 상태를 계약 상태로 옮긴다`() {
        listOf(
            MissionStatus.SUCCESS to TaskState.TASK_STATE_SUCCEEDED,
            MissionStatus.FAILURE to TaskState.TASK_STATE_FAILED,
            MissionStatus.ERROR to TaskState.TASK_STATE_FAILED,
            MissionStatus.STOPPED to TaskState.TASK_STATE_CANCELLED,
            MissionStatus.PAUSED to TaskState.TASK_STATE_PAUSED,
            MissionStatus.RUNNING to TaskState.TASK_STATE_RUNNING,
        ).forEach { (reported, expected) ->
            val mission = FakeMission(MissionState(reported))
            val a = SpotAdapter(FakeLink(FakeCommand(), mission, mapped()), identity)
            a.accept("navigate_to", navigate, t0)
            assertEquals(expected, a.poll(t0.plusSeconds(1)), "$reported 의 옮김")
        }
    }

    @Test
    fun `미션이 사람에게 물으면 RUNNING 이 아니라 NEEDS_INTERVENTION 이다`() {
        // **`AnswerQuestion` 을 가진 유일한 실물이다.** 미션은 물어보는 동안에도
        // 스스로를 RUNNING 이라 답하는데, 사람이 와야 진행되는 것은 계약에서
        // RUNNING 이 아니다. 그대로 옮기면 아무도 사람을 부르지 않는다.
        val mission = FakeMission(MissionState(MissionStatus.RUNNING, question = "문을 열까요?"))
        val a = SpotAdapter(FakeLink(FakeCommand(), mission, mapped()), identity)
        a.accept("navigate_to", navigate, t0)

        assertEquals(TaskState.TASK_STATE_NEEDS_INTERVENTION, a.poll(t0.plusSeconds(1)))

        val observed = assertIs<FaultObservation.Observed>(a.faults())
        assertEquals(listOf("X_BOSTONDYNAMICS_MISSION_QUESTION"), observed.faults.map { it.errorType })
        assertTrue("문을 열까요?" in observed.faults.single().errorHint)
    }

    @Test
    fun `명령 계층 태스크는 시계로 성공한다`() {
        // `StopCommand` 조차 "provides no feedback" 이라 물어볼 데가 없다.
        // G1과 같은 처지이며, **같은 로봇 안에서 층에 따라 갈린다.**
        val a = adapter()
        a.accept("move_relative", move, t0)

        assertEquals(TaskState.TASK_STATE_RUNNING, a.poll(t0.plusMillis(1_999)))
        assertEquals(TaskState.TASK_STATE_SUCCEEDED, a.poll(t0.plusMillis(2_000)))
    }

    // ── 리스 = §4.9

    @Test
    fun `리스가 거절되면 제어 권한 상실이 값으로 온다`() {
        // **Spot 에서 처음으로 §4.9 가 기계적 근거를 갖는다.** G1 은 lease id 가
        // 있어도 인증이 없어 이 결함을 낼 근거 자체가 없었다.
        val a = adapter(command = FakeCommand(reject = LeaseStatus.STATUS_REVOKED))

        val refused = assertIs<Acceptance.Refused>(a.accept("move_relative", move, t0))
        assertEquals(Refusal.CONTROL_AUTHORITY_LOST, refused.reason)

        val observed = assertIs<FaultObservation.Observed>(a.faults())
        assertEquals(listOf("CONTROL_AUTHORITY_LOST"), observed.faults.map { it.errorType })
    }

    @Test
    fun `권한을 잃은 채 취소하면 취소됐다고 적지 않는다`() {
        val mission = FakeMission()
        val a = SpotAdapter(FakeLink(FakeCommand(), mission, mapped()), identity)
        a.accept("navigate_to", navigate, t0)
        mission.reject = LeaseStatus.STATUS_OLDER

        val refused = assertIs<Applied.Refused>(a.cancel())
        assertEquals(Refusal.CONTROL_AUTHORITY_LOST, refused.reason)
        assertEquals(TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED, a.state)
    }

    // ── 계약 규율

    @Test
    fun `취소가 미션을 멈춘다`() {
        val mission = FakeMission()
        val a = SpotAdapter(FakeLink(FakeCommand(), mission, mapped()), identity)
        a.accept("navigate_to", navigate, t0)

        assertEquals(Applied.Ok, a.cancel())
        assertEquals(1, mission.stopped)
        assertEquals(TaskState.TASK_STATE_CANCELLED, a.state)
    }

    @Test
    fun `종착한 태스크는 조작을 거절한다`() {
        val a = adapter(mission = FakeMission(MissionState(MissionStatus.SUCCESS)))
        a.accept("navigate_to", navigate, t0)
        a.poll(t0.plusSeconds(1))

        assertEquals(Refusal.TERMINAL_LATCHED, assertIs<Applied.Refused>(a.cancel()).reason)
        assertEquals(Refusal.TERMINAL_LATCHED, assertIs<Applied.Refused>(a.pause()).reason)
        assertEquals(TaskState.TASK_STATE_SUCCEEDED, a.state)
    }

    @Test
    fun `이미 도는 태스크가 있으면 받지 않는다`() {
        val a = adapter()
        assertIs<Acceptance.Accepted>(a.accept("navigate_to", navigate, t0))
        assertEquals(Refusal.ALREADY_RUNNING, assertIs<Acceptance.Refused>(a.accept("move_relative", move, t0)).reason)
    }

    @Test
    fun `신원이 비어 있으면 받지 않는다`() {
        val blank = AdapterIdentity("boston-dynamics", "", "spot-01")
        assertEquals(
            Refusal.IDENTITY_UNSET,
            assertIs<Acceptance.Refused>(adapter(identity = blank).accept("move_relative", move, t0)).reason,
        )
    }

    @Test
    fun `드는 스킬이 아니면 받지 않는다`() {
        // **`pick_place` 와 `inspect` 가 여기로 온다.** 거리 측정이 둘을
        // NO·PARTIAL 로 적었고, 어댑터가 그 판정과 같은 답을 낸다.
        listOf("pick_place", "inspect").forEach {
            assertEquals(
                Refusal.UNSUPPORTED_SKILL,
                assertIs<Acceptance.Refused>(adapter().accept(it, emptyMap(), t0)).reason,
                "$it 의 거절",
            )
        }
    }

    @Test
    fun `필수 파라미터가 빠지면 값을 지어내지 않는다`() {
        val refused = assertIs<Acceptance.Refused>(adapter().accept("move_relative", move - "duration", t0))
        assertEquals(Refusal.PARAMETER_MISSING, refused.reason)
        assertTrue("duration" in refused.detail)

        val noLocation = assertIs<Acceptance.Refused>(adapter().accept("navigate_to", emptyMap(), t0))
        assertEquals(Refusal.PARAMETER_MISSING, noLocation.reason)
    }

    @Test
    fun `아무것도 안 들었으면 결함이 없다`() {
        // 위 결함 시험들이 "언제나 무언가 낸다"로 통과하는 것을 막는다.
        val observed = assertIs<FaultObservation.Observed>(adapter().faults())
        assertEquals(emptyList(), observed.faults.map { it.errorType })
    }

    // ── 가짜 남쪽

    private data class Velocity(val vx: Double, val vy: Double, val omega: Double, val endTime: Instant)

    private class FakeCommand(var reject: LeaseStatus? = null) : CommandLayer {
        val velocities = mutableListOf<Velocity>()
        var stopped = 0

        override fun se2Velocity(vx: Double, vy: Double, omega: Double, endTime: Instant): LeaseResult {
            reject?.let { return LeaseResult.Rejected(it) }
            velocities += Velocity(vx, vy, omega, endTime)
            return LeaseResult.Ok
        }

        override fun stop(): LeaseResult {
            reject?.let { return LeaseResult.Rejected(it) }
            stopped += 1
            return LeaseResult.Ok
        }
    }

    private class FakeMission(
        private val reported: MissionState? = null,
        var reject: LeaseStatus? = null,
    ) : MissionLayer {
        val loaded = mutableListOf<String>()
        var played = 0
        var paused = 0
        var stopped = 0

        private fun gate(body: () -> Unit): LeaseResult {
            reject?.let { return LeaseResult.Rejected(it) }
            body()
            return LeaseResult.Ok
        }

        override fun loadNavigateTo(waypointId: String) = gate { loaded += waypointId }
        override fun play() = gate { played += 1 }
        override fun pause() = gate { paused += 1 }
        override fun stop() = gate { stopped += 1 }
        override fun state(): MissionState? = reported
    }

    /**
     * 페어를 받는 것이 요점이다 — **id 와 이름이 다른 값이라는 것**을 페이크의
     * 모양이 강제한다. 문자열 목록으로 두면 둘을 접은 앞 판이 그대로 돌아온다.
     */
    private class FakeGraph(
        private vararg val waypoints: Pair<String, String>,
        private val fail: Throwable? = null,
    ) : GraphLayer {
        override fun downloadGraph(): Result<List<GraphWaypoint>> {
            fail?.let { return Result.failure(it) }
            return Result.success(waypoints.map { GraphWaypoint(it.first, it.second) })
        }
    }

    // ── 잔여 물리 상태 (§4.4) — 벤더가 불리언을 준다

    @Test
    fun `그리퍼가 비어 있으면 빈손이다`() {
        val a = SpotAdapter(FakeLink(FakeCommand(), FakeMission(), mapped(), gripper = false), identity)
        assertEquals(HoldObservation.Empty, a.hold())
    }

    @Test
    fun `팔이 없으면 빈손이다 — 쥘 것이 없다`() {
        val a = SpotAdapter(FakeLink(FakeCommand(), FakeMission(), mapped(), gripper = null), identity)
        assertEquals(HoldObservation.Empty, a.hold())
    }

    @Test
    fun `쥐고 있으면 든 채이고 무엇인지는 말하지 않는다`() {
        val a = SpotAdapter(FakeLink(FakeCommand(), FakeMission(), mapped(), gripper = true), identity)
        assertEquals(HoldObservation.Holding(objectRef = null), a.hold())
    }

    @Test
    fun `못 읽었으면 빈손이 아니라 모른다`() {
        val a = SpotAdapter(FakeLink(FakeCommand(), FakeMission(), mapped(), gripperFails = true), identity)
        assertIs<HoldObservation.NotObservable>(a.hold())
    }

    @Test
    fun `쥔 채 멈추면 취소됐다고 적지 않는다`() {
        // StopMission 은 멈추는 것이지 내려놓는 것이 아니다. §4.4 의 불변식 —
        // CANCELLED 는 복구까지 마쳤다는 뜻이고 든 채는 그것이 아니다.
        val mission = FakeMission()
        val a = SpotAdapter(FakeLink(FakeCommand(), mission, mapped(), gripper = true), identity)
        a.accept("navigate_to", navigate, t0)

        assertEquals(Applied.Ok, a.cancel())
        assertEquals(1, mission.stopped)
        assertEquals(TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED, a.state)
    }

    @Test
    fun `못 봤으면 취소됐다고 적되 모른다고 함께 말한다`() {
        val a = SpotAdapter(FakeLink(FakeCommand(), FakeMission(), mapped(), gripperFails = true), identity)
        a.accept("navigate_to", navigate, t0)

        assertEquals(Applied.Ok, a.cancel())
        assertEquals(TaskState.TASK_STATE_CANCELLED, a.state)
        assertIs<HoldObservation.NotObservable>(a.hold())
    }

    private class FakeLink(
        override val command: CommandLayer?,
        override val mission: MissionLayer?,
        override val graph: GraphLayer?,
        /** 널이 아니면 그 답을, 널이면 읽기 실패를 낸다 — **"없다" 와 "못 물어봤다" 를 가른다.** */
        private val arm: Boolean? = true,
        /** 그리퍼 — `true`/`false`, 팔 없음은 `null`. [gripperFails]면 읽기 실패. */
        private val gripper: Boolean? = false,
        private val gripperFails: Boolean = false,
    ) : SpotLink {
        var armAsks = 0

        override fun gripperHoldingItem(): Result<Boolean?> =
            if (gripperFails) Result.failure(IllegalStateException("상태를 못 받았다")) else Result.success(gripper)

        override fun armAttached(): Result<Boolean> {
            armAsks += 1
            return arm?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("상태를 못 받았다"))
        }
    }
}
