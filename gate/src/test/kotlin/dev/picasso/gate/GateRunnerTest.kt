package dev.picasso.gate

import dev.picasso.gate.input.GateInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GateRunnerTest {

    private fun check(
        checkId: String,
        needs: Set<Resource>,
        result: (GateInput) -> CheckResult,
    ) = object : GateCheck {
        override val id = checkId
        override val name = "test-$checkId"
        override val requires = needs
        override fun run(input: GateInput) = result(input)
    }

    @Test
    fun `요구 자원이 없으면 돌리지 않고 건너뛴다`() {
        var ran = false
        val c = check("4", setOf(Resource.CONTRACT_DESCRIPTOR)) {
            ran = true
            CheckResult.Passed("4")
        }

        val report = GateRunner(listOf(c)).run(GateInput())

        assertFalse(ran, "자원이 없는데 검사가 돌았다")
        val r = report.results.single()
        assertTrue(r is CheckResult.Skipped)
        assertEquals(setOf(Resource.CONTRACT_DESCRIPTOR), (r as CheckResult.Skipped).missing)
    }

    @Test
    fun `건너뛴 것은 통과가 아니다`() {
        val c = check("4", setOf(Resource.CONTRACT_DESCRIPTOR)) { CheckResult.Passed("4") }
        val report = GateRunner(listOf(c)).run(GateInput())

        assertFalse(report.allClean, "건너뛴 검사가 깨끗한 것으로 집계됐다")
        assertEquals(1, report.skipped.size)
    }

    @Test
    fun `건너뜀만으로는 CI를 실패시키지 않되 출력에는 남는다`() {
        // 설계 §11.1 — CI에는 DB가 없어 6번의 축소 조회는 상시 건너뜀이다.
        // 그것으로 CI를 빨갛게 만들면 게이트를 꺼버리게 된다.
        val c = check("6", setOf(Resource.REGISTRY)) { CheckResult.Passed("6") }
        val report = GateRunner(listOf(c)).run(GateInput())

        assertEquals(0, report.exitCode)
        val out = report.render()
        assertTrue(out.contains("SKIP"), "건너뜀이 출력에 없다")
        assertTrue(out.contains("REGISTRY"), "무엇이 없어서 건너뛰었는지 출력에 없다")
    }

    @Test
    fun `부분 건너뜀도 출력에 남는다`() {
        val c = check("6", emptySet()) {
            CheckResult.Passed("6", skippedParts = setOf(Resource.REGISTRY))
        }
        val out = GateRunner(listOf(c)).run(GateInput()).render()

        assertTrue(out.contains("PASS"))
        assertTrue(out.contains("REGISTRY"), "부분 건너뜀이 출력에 없다")
    }

    @Test
    fun `실패는 소견과 위치를 출력한다`() {
        val c = check("3", emptySet()) {
            CheckResult.Failed(
                "3",
                listOf(Finding("3", Severity.ERROR, "미등록 error_type", "x.json#/failure_modes/0")),
            )
        }
        val report = GateRunner(listOf(c)).run(GateInput())
        val out = report.render()

        assertEquals(1, report.exitCode)
        assertTrue(out.contains("FAIL"))
        assertTrue(out.contains("미등록 error_type"))
        assertTrue(out.contains("x.json#/failure_modes/0"))
    }

    @Test
    fun `하나가 실패해도 나머지를 마저 돌린다`() {
        // 첫 실패에서 멈추면 한 번에 하나씩만 고치게 된다.
        var secondRan = false
        val a = check("3", emptySet()) {
            CheckResult.Failed("3", listOf(Finding("3", Severity.ERROR, "나쁨")))
        }
        val b = check("5", emptySet()) {
            secondRan = true
            CheckResult.Passed("5")
        }

        val report = GateRunner(listOf(a, b)).run(GateInput())

        assertTrue(secondRan, "첫 실패에서 멈췄다")
        assertEquals(1, report.failed.size)
    }

    @Test
    fun `검사가 하나도 없는 게이트는 만들 수 없다`() {
        // 빈 목록이면 실패도 건너뜀도 없어 exitCode 0에 출력이 비어 있다.
        assertFailsWith<IllegalArgumentException> { GateRunner(emptyList()) }
    }

    @Test
    fun `검사 id가 중복이면 만들 수 없다`() {
        val a = check("3", emptySet()) { CheckResult.Passed("3") }
        assertFailsWith<IllegalArgumentException> { GateRunner(listOf(a, a)) }
    }

    @Test
    fun `반드시 있어야 하는 자원이 없으면 건너뜀이 아니라 실패다`() {
        // 글롭 오타나 빈 디렉터리로 프로파일이 0장이면 검사 3·4·6이 전부
        // 건너뛰고 종료코드 0이 났다. 초록 빌드의 로그는 아무도 읽지 않는다.
        val c = check("3", setOf(Resource.PROFILE_DOCUMENT)) { CheckResult.Passed("3") }
        val report = GateRunner(listOf(c), required = setOf(Resource.PROFILE_DOCUMENT))
            .run(GateInput())

        assertEquals(1, report.exitCode, "입력이 통째로 비었는데 초록불이 났다")
        assertEquals(setOf(Resource.PROFILE_DOCUMENT), report.absentRequired)
        assertTrue(report.render().contains("PROFILE_DOCUMENT"))
    }

    @Test
    fun `상시 건너뜀은 required에 넣지 않으므로 실패시키지 않는다`() {
        // §11.1이 인정하는 상시 건너뜀은 검사 6번의 REGISTRY 하나뿐이다.
        val c = check("6", setOf(Resource.REGISTRY)) { CheckResult.Passed("6") }
        val report = GateRunner(listOf(c), required = setOf(Resource.PROFILE_DOCUMENT))
            .run(GateInput(profiles = emptyList(), malformed = listOf(
                dev.picasso.gate.input.MalformedProfile("x.json", "깨짐"),
            )))

        assertEquals(0, report.exitCode)
        assertTrue(report.render().contains("SKIP"))
    }

    @Test
    fun `검사가 딴 id의 결과를 내면 실패로 접는다`() {
        val c = check("3", emptySet()) { CheckResult.Passed("999") }
        val report = GateRunner(listOf(c)).run(GateInput())

        assertEquals(1, report.exitCode, "결과의 checkId가 달랐는데 통과했다")
        assertTrue(report.failed.single().findings.single().message.contains("999"))
    }

    @Test
    fun `없는 자원이 비어 있어도 건너뜀은 집계에 남는다`() {
        // anySkipped를 skippedParts로만 세면 missing이 빈 Skipped가
        // 집계에서 통째로 사라지고 "건너뛴 검사가 있다" 각주도 안 찍힌다.
        val report = GateReport(listOf(CheckResult.Skipped("6", emptySet(), "바뀐 프로파일이 없다")))

        assertTrue(report.anySkipped)
        assertFalse(report.allClean)
        assertTrue(report.render().contains("통과가 아니다"))
    }

    @Test
    fun `실패한 검사의 부분 건너뜀도 출력에 남는다`() {
        // §11.1대로 검사 6번은 실패하면서 동시에 REGISTRY를 건너뛸 수 있다.
        // 어느 검사가 건너뛰었는지 없으면 각주가 무의미하다.
        val c = check("6", emptySet()) {
            CheckResult.Failed(
                "6",
                listOf(Finding("6", Severity.ERROR, "축소 거부")),
                skippedParts = setOf(Resource.REGISTRY),
            )
        }
        val out = GateRunner(listOf(c)).run(GateInput()).render()
        assertTrue(out.contains("FAIL"))
        assertTrue(out.contains("REGISTRY"), "실패한 검사의 부분 건너뜀이 출력에 없다")
    }

    @Test
    fun `통과의 경고도 위치를 출력한다`() {
        // 어느 스킬인지 없으면 사람이 고칠 수 없다.
        val c = check("6", emptySet()) {
            CheckResult.Passed("6", listOf(Finding("6", Severity.WARNING, "축소 후보", "p.json#/skills/0")))
        }
        assertTrue(GateRunner(listOf(c)).run(GateInput()).render().contains("p.json#/skills/0"))
    }

    @Test
    fun `검사가 던지면 실패로 잡는다`() {
        // 게이트가 예외로 죽으면 CI 배선에 따라 초록불이 날 수 있다.
        val c = check("6", emptySet()) { error("터짐") }
        val report = GateRunner(listOf(c)).run(GateInput())

        assertEquals(1, report.exitCode)
        assertTrue(report.failed.single().findings.single().message.contains("터짐"))
    }
}
