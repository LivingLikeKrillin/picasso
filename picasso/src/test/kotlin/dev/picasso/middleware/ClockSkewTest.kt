package dev.picasso.middleware

import dev.picasso.harness.Harness
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **발신자 시계가 어긋나면 트레이스에 남긴다** — 환경 전제 K(`docs/environment-preconditions.md`)가 깨진 현장을 사람이 알아보게 한다.
 *
 * 근거 시간 윈도우의 완료 시각 `t_r` 은 로봇이 아니라 발신자 프로세스(어댑터 호스트·에뮬레이터)의 시계가 찍은 헤더 값이다. 그 시계가 미들웨어
 * 시계와 윈도우 폭만큼 벌어지면 신호가 윈도우 밖으로 읽혀 `UNVERIFIED` 가 나는데, 사람에게는 «설비가 말이 없다» 와 같아 보인다. 미들웨어는
 * 기체 동기화의 스냅숏 왕복마다 응답 헤더의 `occurred_at` 과 보낸·받은 시각으로 발신자 시계 차의 구간을 재고, 구간 전체가 임계 밖이면 그
 * 기체의 진행 중인 실행에 `CLOCK_SKEW` 를 남긴다. **판정은 바꾸지 않는다** — 보정은 계약 §4 가 하지 않는다고 한 일이다.
 *
 * 시험 세계는 한 가상 시계로 돈다. [ClockShiftRobotPort] 가 발신자 시계의 오차를 두 자리에 같이 싣는다.
 */
class ClockSkewTest {

    private fun order() = JobOrder(
        jobOrderId = "SEQ-SKEW",
        workMasterId = PrepareSequencedRack.WORK_MASTER,
        version = 1,
        requiredEvidence = Evidence.E2,
        equipmentRequirements = listOf(
            EquipmentRequirement(SLOT, EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to PART)),
            EquipmentRequirement(BIN, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to PART)),
        ),
    )

    private class World(
        shift: Duration,
        roundTrip: Duration = Duration.ZERO,
        timings: SiteTimings? = null,
    ) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to Path.of("..", "profile", "fixtures", "minimal.json").normalize()))
        val cell = CellMimic(now = { harness.clock.now() })
        val port = ClockShiftRobotPort(ClientRobotPort(harness.client()), shift)
        private val slow = if (roundTrip.isZero) port else SlowSnapshotRobotPort(port) { harness.advance(roundTrip) }
        val mw = Middleware(slow, cell, now = { harness.clock.now() }, siteTimings = { timings })
        val now get() = harness.clock.now()

        fun submit(order: JobOrder): Middleware.Execution = assertIs<Middleware.Submission.Accepted>(mw.submit(order, ROBOT)).execution

        fun drive(rounds: Int = 80, step: Duration = Duration.ofSeconds(5), until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(step)
                Thread.sleep(30)
                mw.pump()
                if (until()) return
            }
            error("조건에 못 미쳤다")
        }

        override fun close() = harness.close()
    }

    private fun Middleware.Execution.unit() = units.single()
    private fun Middleware.Execution.settled() = physicalState.isSettled && active == null
    private fun Middleware.Execution.skews() = eventTrail.filter { it.kind == "CLOCK_SKEW" }

    // ── 지금 동작 — 발신자 시계가 늦으면 첫 확인에서 UNVERIFIED 다

    @Test
    fun `발신자 시계가 뒤 폭보다 늦으면 첫 확인에서 UNVERIFIED 다`() {
        World(Duration.ofSeconds(-20)).use { w ->
            w.cell.program(SLOT, PART)
            val exec = w.submit(order())
            w.drive { exec.settled() }

            // 설비는 처음부터 부품을 보고 있다. 발신자 시계가 20초 늦어 t_r 이 앞당겨지고 뒤 폭(15초)이 첫 확인에서 이미 지났다.
            assertEquals(UnitState.UNVERIFIED, exec.unit().state)
            assertEquals(1, exec.unit().rechecks, "기다리지 않고 첫 확인에서 닫혀야 한다")
            assertTrue(exec.unit().note!!.contains("stale"), exec.unit().note)
        }
    }

    // ── 감지 — 트레이스와 단위 메모

    @Test
    fun `발신자 시계 차가 허용 폭을 넘으면 트레이스에 한 번 남긴다`() {
        World(Duration.ofSeconds(-20)).use { w ->
            w.cell.program(SLOT, PART)
            val exec = w.submit(order())
            w.drive { exec.settled() }

            val lines = exec.skews()
            assertEquals(1, lines.size, exec.eventTrail.toString())
            val detail = lines.single().detail
            assertTrue("robot=$ROBOT" in detail, detail)
            assertTrue("늦" in detail, detail)
            assertTrue("20" in detail, detail)
            assertTrue(lines.single().local, "이 층이 낸 줄이어야 한다")
        }
    }

    @Test
    fun `시계 차로 창 밖이 된 UNVERIFIED 는 단위 메모에 그 의심을 싣는다`() {
        World(Duration.ofSeconds(-20)).use { w ->
            w.cell.program(SLOT, PART)
            val exec = w.submit(order())
            w.drive { exec.settled() }

            assertEquals(UnitState.UNVERIFIED, exec.unit().state)
            val note = assertNotNull(exec.unit().note)
            assertTrue("시계 차 의심" in note, note)
            assertTrue("늦" in note, note)
            assertTrue("20" in note, note)
        }
    }

    @Test
    fun `발신자 시계가 앞 폭보다 빠르면 트레이스에 남기고 창 앞쪽 밖이 된 UNVERIFIED 의 메모에 싣는다`() {
        World(Duration.ofSeconds(40)).use { w ->
            // 부품은 처음부터 있었다. 발신자 시계가 40초 빨라 t_r 이 앞서고, 신호 시각이 앞 폭(30초) 앞쪽 밖이 된다.
            w.cell.programAt(SLOT, PART, at = w.now)
            val exec = w.submit(order())
            w.drive { exec.settled() }

            assertEquals(UnitState.UNVERIFIED, exec.unit().state)
            val lines = exec.skews()
            assertEquals(1, lines.size, exec.eventTrail.toString())
            assertTrue("빠" in lines.single().detail, lines.single().detail)
            val note = assertNotNull(exec.unit().note)
            assertTrue("시계 차 의심" in note && "빠" in note, note)
        }
    }

    @Test
    fun `발신자 시계가 빨라도 신호가 창 안이면 판정은 그대로 완료고 트레이스에만 남는다`() {
        World(Duration.ofSeconds(20)).use { w ->
            w.cell.program(SLOT, PART)
            val exec = w.submit(order())
            w.drive { exec.settled() }

            // 20초 빠른 것은 앞 폭(30초) 안이다 — 보정하지도 막지도 않는다. 사람이 볼 줄만 남는다.
            assertEquals(UnitState.DONE, exec.unit().state)
            assertEquals(Evidence.E2, exec.unit().reached)
            assertEquals(1, exec.skews().size, exec.eventTrail.toString())
            assertFalse("시계 차 의심" in exec.unit().note.orEmpty(), exec.unit().note)
        }
    }

    // ── 가드 — 허용 안이거나 구간이 임계에 걸치면 남기지 않는다

    @Test
    fun `시계 차가 허용 폭 안이면 남기지 않는다`() {
        World(Duration.ofSeconds(-5)).use { w ->
            w.cell.program(SLOT, PART)
            val exec = w.submit(order())
            w.drive { exec.settled() }

            assertEquals(UnitState.DONE, exec.unit().state)
            assertEquals(0, exec.skews().size, exec.eventTrail.toString())
        }
    }

    @Test
    fun `시계가 맞는 채 창 밖이 된 UNVERIFIED 의 메모에는 시계 차 의심이 없다`() {
        World(Duration.ZERO).use { w ->
            w.cell.programAt(SLOT, PART, at = w.now.minus(Duration.ofMinutes(10)))
            val exec = w.submit(order())
            w.drive { exec.settled() }

            assertEquals(UnitState.UNVERIFIED, exec.unit().state)
            assertTrue("stale" in exec.unit().note!!, exec.unit().note)
            assertFalse("시계 차 의심" in exec.unit().note!!, exec.unit().note)
            assertEquals(0, exec.skews().size)
        }
    }

    @Test
    fun `신호가 아예 없어 닫힌 UNVERIFIED 의 메모에는 시계 차 의심을 싣지 않는다`() {
        World(Duration.ofSeconds(-20)).use { w ->
            val exec = w.submit(order())
            w.drive { exec.settled() }

            // 설비가 말이 없는 것은 윈도우의 기준 시계 탓이 아니다. 시계 차는 트레이스에 남아도 이 단위의 닫힘 사유로 싣지 않는다.
            assertEquals(UnitState.UNVERIFIED, exec.unit().state)
            assertTrue("no signal" in exec.unit().note!!, exec.unit().note)
            assertFalse("시계 차 의심" in exec.unit().note!!, exec.unit().note)
            assertEquals(1, exec.skews().size)
        }
    }

    @Test
    fun `왕복이 길어 구간이 임계에 걸치면 남기지 않는다 - 늦은 쪽`() {
        // 발신자는 5초 늦고 왕복에 10초가 걸렸다. 구간은 [-15, -5] 라 임계(-7.5)를 넘을 수도 안 넘을 수도 있다 — 단정하지 않는다.
        World(Duration.ofSeconds(-5), roundTrip = Duration.ofSeconds(10)).use { w ->
            val exec = w.submit(order())
            repeat(3) { w.mw.pump() }

            assertEquals(0, exec.skews().size, exec.eventTrail.toString())
        }
    }

    @Test
    fun `왕복이 길어 구간이 임계에 걸치면 남기지 않는다 - 빠른 쪽`() {
        // 발신자는 10초 빠르고 왕복에 10초가 걸렸다. 구간은 [0, 10] 이다.
        World(Duration.ofSeconds(10), roundTrip = Duration.ofSeconds(10)).use { w ->
            val exec = w.submit(order())
            repeat(3) { w.mw.pump() }

            assertEquals(0, exec.skews().size, exec.eventTrail.toString())
        }
    }

    @Test
    fun `왕복이 길어도 구간 전체가 임계 밖이면 남긴다`() {
        World(Duration.ofSeconds(-30), roundTrip = Duration.ofSeconds(10)).use { late ->
            val exec = late.submit(order())
            late.mw.pump()
            assertEquals(1, exec.skews().size, exec.eventTrail.toString())
        }
        World(Duration.ofSeconds(20), roundTrip = Duration.ofSeconds(10)).use { early ->
            val exec = early.submit(order())
            early.mw.pump()
            assertEquals(1, exec.skews().size, exec.eventTrail.toString())
            assertTrue("빠" in exec.skews().single().detail, exec.skews().single().detail)
        }
    }

    // ── 되풀이 — 같은 방향·같은 크기 등급이면 한 번, 허용 안으로 들어오면 표시를 지운다

    @Test
    fun `같은 방향과 크기 등급이면 되풀이하지 않고 등급이 바뀌면 다시 남긴다`() {
        World(Duration.ofSeconds(-20)).use { w ->
            val exec = w.submit(order())
            repeat(4) { w.mw.pump() }
            assertEquals(1, exec.skews().size, "같은 차를 라운드마다 되풀이했다")

            // 20초에서 25초는 같은 등급이다(임계 7.5초의 두 배 이상 네 배 미만).
            w.port.shift = Duration.ofSeconds(-25)
            w.mw.pump()
            assertEquals(1, exec.skews().size, exec.eventTrail.toString())

            // 40초는 한 등급 위다.
            w.port.shift = Duration.ofSeconds(-40)
            w.mw.pump()
            assertEquals(2, exec.skews().size, exec.eventTrail.toString())

            // 방향이 뒤집히면 같은 크기라도 다른 줄이다.
            w.port.shift = Duration.ofSeconds(40)
            w.mw.pump()
            assertEquals(3, exec.skews().size, exec.eventTrail.toString())
            assertTrue("빠" in exec.skews().last().detail, exec.skews().last().detail)
        }
    }

    @Test
    fun `허용 안으로 돌아오면 표시를 지워 다시 벗어날 때 다시 남긴다`() {
        World(Duration.ofSeconds(-20)).use { w ->
            val exec = w.submit(order())
            w.mw.pump()
            assertEquals(1, exec.skews().size)

            w.port.shift = Duration.ZERO
            repeat(2) { w.mw.pump() }
            assertEquals(1, exec.skews().size, "돌아온 것은 줄로 남기지 않는다")

            w.port.shift = Duration.ofSeconds(-20)
            w.mw.pump()
            assertEquals(2, exec.skews().size, exec.eventTrail.toString())
        }
    }

    @Test
    fun `돌아온 뒤에는 창 밖이 된 UNVERIFIED 의 메모에 옛 의심을 싣지 않는다`() {
        World(Duration.ofSeconds(-20)).use { w ->
            val exec = w.submit(order())
            w.mw.pump()
            assertEquals(1, exec.skews().size)

            // 시계가 맞은 뒤의 옛 신호는 시계 탓이 아니다.
            w.port.shift = Duration.ZERO
            w.cell.programAt(SLOT, PART, at = w.now.minus(Duration.ofMinutes(10)))
            w.drive { exec.settled() }

            assertEquals(UnitState.UNVERIFIED, exec.unit().state)
            assertFalse("시계 차 의심" in exec.unit().note.orEmpty(), exec.unit().note)
        }
    }

    // ── 임계 — 근거 시간 윈도우의 지금 값에서

    @Test
    fun `현장 뒤 폭을 좁히면 임계도 따라 좁아진다`() {
        // 케이퍼빌리티 기본(앞 30초·뒤 15초)이면 임계가 7.5초라 3초는 넘지 않는다. 현장이 뒤 폭을 5초로 좁히면 임계는 2.5초다.
        World(Duration.ofSeconds(-3)).use { w ->
            val exec = w.submit(order())
            repeat(2) { w.mw.pump() }
            assertEquals(0, exec.skews().size, exec.eventTrail.toString())
        }
        val narrowed = SiteTimings(1, Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(60), Duration.ofSeconds(300))
        World(Duration.ofSeconds(-3), timings = narrowed).use { w ->
            val exec = w.submit(order())
            w.mw.pump()
            assertEquals(1, exec.skews().size, exec.eventTrail.toString())
        }
    }

    private companion object {
        const val ROBOT = "hum-02"
        const val SLOT = "RACK-205.S01"
        const val BIN = "SEQ-IN-02.BIN-A"
        const val PART = "ENGINE-COVER-A"
    }
}
