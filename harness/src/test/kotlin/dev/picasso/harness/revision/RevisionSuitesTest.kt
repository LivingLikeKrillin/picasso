package dev.picasso.harness.revision

import dev.picasso.harness.Suite
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 개정판 시험 3종(picasso-ops P2·S1d 스펙 §5.4, ADR 49). **기준 프로파일 둘이 셋 다 통과하고, 검사가 실제로 돈다.**
 *
 * 통과만 보면 검사 0개로도 초록이다. 그래서 검사 식별자를 함께 본다 — 프로파일이 못 한다고 적은 것마다 탐침이
 * 갔는지(NEGATIVE), 선언한 스킬마다 태스크가 돌았는지(CONTRACT·DETERMINISM).
 */
class RevisionSuitesTest {

    private val schema: Path = Path.of("..", "profile", "schema", "capability-profile.schema.json").normalize()
    private val suites = RevisionSuites(schema)

    private fun profile(name: String): Path = Path.of("..", "profile", "profiles", "$name.json").normalize()

    private fun byName(outcomes: List<SuiteOutcome>) = outcomes.associateBy { it.suite }

    @Test
    fun `humanoid-a 는 셋 다 통과한다`() {
        val outcomes = byName(suites.run(profile("humanoid-a")))

        Suite.entries.forEach { suite ->
            val outcome = outcomes.getValue(suite)
            assertTrue(outcome.passed, "$suite: ${outcome.detailJson()}")
        }
        assertEquals(5, outcomes.getValue(Suite.CONTRACT).checks, "협상·능력 조회 + 스킬 셋")
        assertEquals(3, outcomes.getValue(Suite.DETERMINISM).checks, "스킬 셋")
    }

    @Test
    fun `quadruped-b 는 셋 다 통과한다`() {
        val outcomes = byName(suites.run(profile("quadruped-b")))

        Suite.entries.forEach { suite ->
            val outcome = outcomes.getValue(suite)
            assertTrue(outcome.passed, "$suite: ${outcome.detailJson()}")
        }
        assertEquals(4, outcomes.getValue(Suite.CONTRACT).checks, "협상·능력 조회 + 스킬 둘")
        assertEquals(2, outcomes.getValue(Suite.DETERMINISM).checks, "스킬 둘")
    }

    @Test
    fun `NEGATIVE 는 프로파일이 못 한다고 적은 것마다 탐침을 보낸다`() {
        val checks = byName(suites.run(profile("humanoid-a"))).getValue(Suite.NEGATIVE).checked.map { it.removePrefix("NEGATIVE.") }

        listOf(
            "skill_absent:move_relative",
            "parameter_missing:navigate_to.location",
            "parameter_missing:pick_place.object_id",
            "parameter_above_max:pick_place.grip_force",
            "parameter_below_min:pick_place.grip_force",
            "parameter_too_long:pick_place.destination",
            "parameter_not_allowed:inspect.mode",
            "cancel_unsupported:pick_place",
            "required_optional_missing:task.parameters.verify_grasp",
        ).forEach { assertTrue(it in checks, "탐침이 없다: $it — 있는 것: $checks") }
        assertTrue(checks.none { it.startsWith("pause_unsupported") }, "pause_support 가 NO 인 스킬이 없는데 탐침이 갔다: $checks")
    }

    @Test
    fun `적재에서 거절된 문서는 셋 다 FAIL 이다`() {
        val broken = Files.createTempFile("broken-", ".json")
        Files.writeString(broken, """{"vendor":"x"}""")

        val outcomes = suites.run(broken)

        assertEquals(Suite.entries.toList(), outcomes.map { it.suite })
        outcomes.forEach {
            assertTrue(!it.passed, "${it.suite} 가 통과했다")
            assertEquals("LOAD", it.failures.single().check)
        }
    }
}
