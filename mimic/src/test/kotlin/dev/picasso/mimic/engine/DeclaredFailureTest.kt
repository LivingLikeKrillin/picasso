package dev.picasso.mimic.engine

import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.mimic.profile.CapabilityProjection
import dev.picasso.profile.ProfileDocument
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 결함과 태스크 전이를 **한 목록에** 순서대로 모은다 — 둘의 순서가 시험 대상이다. */
private class FaultOrderListener : EngineListener {

    val reports = mutableListOf<String>()

    override fun onTaskTransition(
        taskId: String,
        skillType: String,
        from: TaskState?,
        to: TaskState,
        revision: Int,
        attempt: Int,
    ) {
        reports += "task ${from?.name ?: "NEW"}->$to"
    }

    override fun onFault(fault: Fault, cleared: Boolean) {
        reports += "fault ${fault.errorType} cleared=$cleared"
    }
}

/**
 * §10.4 ①의 선언된 실패 모드 추첨과 §12.1의 결정성.
 *
 * **추첨을 순수 함수로 뺐기 때문에 여기서 수천 번 돌릴 수 있다.** 하네스에서
 * 실행당 한 번 뽑아서는 시드 민감도를 보일 수 없다 — 실측으로
 * `java.util.Random`의 첫 추첨이 시드 0·1·2에서 0.7309~0.7311이고 픽스처의
 * 최대 `rate`가 0.05이므로 **어떤 시드로도 첫 추첨에서는 아무것도 안 걸린다.**
 */
class FailureDrawTest {

    private val document = TaskMachineFixtures.document()
    private val draw = FailureDraw(document)

    private fun run(seed: Long, n: Int = 200, skillType: String = "pick_place"): List<String?> {
        val random = Seeded(seed)
        return List(n) { draw.drawFor(skillType, random)?.errorType }
    }

    @Test
    fun `같은 시드로 두 번 돌리면 같다`() {
        assertEquals(run(7), run(7))
    }

    @Test
    fun `다른 시드로 돌리면 다르다`() {
        // **이것이 없으면 `rate`를 무시하고 아무것도 안 내는 구현이 통과하고,**
        // 시드를 아예 안 쓰는 구현도 통과한다.
        assertNotEquals(run(1), run(2))
        assertNotEquals(run(0), run(42))
    }

    @Test
    fun `선언된 모드가 전부 적어도 한 번은 걸린다`() {
        // **위 둘의 전제다.** 하나라도 영영 안 걸리면 그 모드는 시험되지
        // 않는다 — 픽스처의 마지막 모드는 0.001이라 표본이 작으면 조용히 빈다.
        val expected = draw.applicable("pick_place").map { it.errorType }.toSet()
        assertEquals(4, expected.size, "적용되는 모드가 넷이 아니다: $expected")

        val seen = run(3, n = 20_000).filterNotNull().toSet()
        assertEquals(expected, seen, "한 번도 안 걸린 모드가 있다")
    }

    @Test
    fun `해당하지 않는 모드는 뽑지 않는다`() {
        // **인출 수가 곧 §12.1의 지문이다.** 해당하지 않는 모드까지 뽑으면
        // 스킬이 무엇이냐에 따라 인출 수가 달라져 같은 시드가 다른 순서를 낸다.
        val nav = draw.applicable("navigate_to")
        assertEquals(2, nav.size, "픽스처의 로봇 수준 모드가 둘이 아니다: $nav")
        assertTrue(nav.all { it.skillType == null }, "스킬을 지목한 모드가 섞였다")

        val drawn = Seeded(11)
        assertNull(draw.drawFor("navigate_to", drawn), "전제가 무너졌다 — 걸려 버렸다")

        val counted = Seeded(11)
        repeat(nav.size) { counted.fraction() }
        assertEquals(counted.fraction(), drawn.fraction(), "인출 수가 해당 모드 수와 다르다")
    }

    @Test
    fun `선언 순서대로 뽑는다`() {
        // 순서가 §12.1의 "동일 이벤트 시퀀스"에 걸린다. 뒤집으면 같은 시드가
        // 다른 것을 낸다. **파싱된 값이 아니라 원문의 순서와 견준다** —
        // 파서로 만든 목록을 파서로 만든 목록과 비교하면 아무것도 안 본다.
        val block = TaskMachineFixtures.fixtureRaw
            .substringAfter("\"failure_modes\"")
            .substringBefore("\"replay_buffer_size\"")
        val inText = Regex("\"error_type\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(block).map { it.groupValues[1] }.toList()
        assertEquals(4, inText.size, "원문에서 모드를 못 읽었다: $inText")

        assertEquals(inText, draw.applicable("pick_place").map { it.errorType })
    }

    @Test
    fun `rate를 프로파일에서 읽는다`() {
        // 리터럴로 알면 프로파일이 선언하는 뜻이 없다.
        val raw = TaskMachineFixtures.fixtureRaw

        val always = raw.replace("\"rate\": 0.05", "\"rate\": 1.0")
        check(always != raw) { "치환이 아무것도 바꾸지 못했다" }
        assertEquals(
            "SKILL_EXECUTION_FAILED",
            FailureDraw(TaskMachineFixtures.document(always)).drawFor("pick_place", Seeded(0))?.errorType,
            "rate 1.0인데 안 걸렸다",
        )

        val never = ZERO_RATES.fold(raw) { text, from -> text.replace(from, "\"rate\": 0.0") }
        check(never != raw) { "치환이 아무것도 바꾸지 못했다" }
        val cold = FailureDraw(TaskMachineFixtures.document(never))
        val random = Seeded(0)
        assertTrue(
            List(2_000) { cold.drawFor("pick_place", random) }.all { it == null },
            "rate 0인데 걸렸다",
        )
    }

    @Test
    fun `모르는 resolution은 조용히 접히지 않는다`() {
        // 두 벌(스키마 문자열·엔진 enum)을 두는 이상 다리를 시험한다.
        // **스키마가 허용하는 값 전부를** 본다 — 리터럴 목록이면 스키마가
        // 늘어도 여기가 조용하다.
        val allowed = Regex("\"resolution\"[\\s\\S]{0,200}?\"enum\"\\s*:\\s*\\[([^\\]]+)\\]")
            .find(SCHEMA_TEXT)
            ?.groupValues?.get(1)
            ?.let { Regex("\"([A-Z_]+)\"").findAll(it).map { m -> m.groupValues[1] }.toList() }
        assertEquals(
            listOf("SELF_RETRIABLE", "NEEDS_INTERVENTION", "TERMINAL"),
            allowed,
            "스키마에서 resolution 값을 못 읽었다",
        )
        allowed!!.forEach { FailureDraw.resolutionOf(it) }

        val boom = runCatching { FailureDraw.resolutionOf("NOT_A_RESOLUTION") }
        assertTrue(boom.isFailure, "모르는 값을 조용히 접었다")
    }

    private companion object {
        val ZERO_RATES = listOf(
            "\"rate\": 0.05", "\"rate\": 0.01", "\"rate\": 0.002", "\"rate\": 0.001",
        )

        val SCHEMA_TEXT: String by lazy {
            java.nio.file.Files.readString(
                java.nio.file.Path.of("..", "profile", "schema", "capability-profile.schema.json").normalize(),
            )
        }
    }
}

/** 추첨이 §4.5의 전파 규칙 1을 타고 **태스크 결과**가 되는가 — 사슬을 끝까지. */
class DeclaredFailureChainTest {

    private val listener = FaultOrderListener()

    private fun location() =
        ParameterValue.newBuilder().setKey("location").setStringValue("dock-3").build()

    /** `navigate_to`에 반드시 걸리는 모드 하나만 남긴 프로파일. */
    private fun always(resolution: String): ProfileDocument {
        val raw = TaskMachineFixtures.fixtureRaw
        val head = raw.substringBefore("  \"failure_modes\": [")
        // **마지막 `],`다.** 첫 것을 잡으면 skills 배열이 잘려 나간다.
        val tail = raw.substringAfterLast("  ],\n\n")
        check(head != raw) { "픽스처의 failure_modes 블록을 못 찾았다" }
        check(tail.startsWith("  \"replay_buffer_size\"")) { "꼬리를 잘못 잘랐다: ${tail.take(40)}" }
        val text = head + """  "failure_modes": [
    {
      "error_type": "SKILL_EXECUTION_FAILED",
      "skill_type": "navigate_to",
      "rate": 1.0,
      "can_continue_current_task": false,
      "can_accept_new_task": true,
      "resolution": "$resolution",
      "active_until": "UNTIL_CLEARED"
    }
  ],

""" + tail
        return TaskMachineFixtures.document(text)
    }

    private fun host(
        document: ProfileDocument,
        clock: Clock,
        seed: Long = 0,
        on: EngineListener = listener,
    ) = TaskHost(
        CapabilityProjection.of(document), document, clock, on, FaultRegistry(clock), Seeded(seed),
    )

    /** `navigate_to`(20초, 지터 없음)를 접수 → 실행 → 완주까지 민다. */
    private fun runToEnd(tasks: TaskHost, clock: VirtualClock): TaskRuntime {
        tasks.start("t1", 1, "navigate_to", listOf(location()))
        tasks.tick()
        clock.advance(Duration.ofSeconds(20))
        tasks.tick()
        return tasks.find("t1")!!
    }

    @Test
    fun `resolution 셋이 각각 다른 종착을 만든다`() {
        // §4.5의 전파 규칙 1. 하나만 보면 나머지 둘이 침묵한다.
        val table = mapOf(
            "SELF_RETRIABLE" to TaskState.RETRIABLE,
            "NEEDS_INTERVENTION" to TaskState.NEEDS_INTERVENTION,
            "TERMINAL" to TaskState.FAILED,
        )
        assertEquals(Resolution.entries.size, table.size, "표가 Resolution 전수가 아니다")

        table.forEach { (resolution, expected) ->
            val clock = VirtualClock(Instant.EPOCH)
            val tasks = host(always(resolution), clock, on = EngineListener.NONE)
            assertEquals(expected, runToEnd(tasks, clock).machine.state, "resolution=$resolution")
        }
    }

    @Test
    fun `결함이 태스크 상태에 닿는다`() {
        // **결함만 발행하고 태스크는 안 건드리면 §4.5의 전파 규칙 1이
        // 통째로 빠져도 초록이다.** 사슬을 끝까지 본다.
        val clock = VirtualClock(Instant.EPOCH)
        val task = runToEnd(host(always("SELF_RETRIABLE"), clock), clock)

        assertEquals(TaskState.RETRIABLE, task.machine.state)
        assertTrue(
            "task RUNNING->RETRIABLE" in listener.reports,
            "전이가 안 나갔다: ${listener.reports}",
        )
    }

    @Test
    fun `원인이 결과보다 먼저 나간다`() {
        // 결함 → 태스크 종착. 이벤트를 접는 소비자가 **원인 없는 실패**를
        // 보면 왜 실패했는지 답할 수 없다.
        val clock = VirtualClock(Instant.EPOCH)
        runToEnd(host(always("TERMINAL"), clock), clock)

        val cause = listener.reports.indexOfFirst { it.startsWith("fault SKILL_EXECUTION_FAILED") }
        val effect = listener.reports.indexOf("task RUNNING->FAILED")
        assertTrue(cause >= 0 && effect >= 0, "둘 중 하나가 안 나갔다: ${listener.reports}")
        assertTrue(cause < effect, "결과가 원인보다 먼저다: ${listener.reports}")
    }

    @Test
    fun `실패하지 않으면 결함도 없다`() {
        // rate 1.0 시험만 있으면 **언제나 실패하는** 구현이 통과한다.
        val raw = TaskMachineFixtures.fixtureRaw
        val never = listOf("\"rate\": 0.05", "\"rate\": 0.01", "\"rate\": 0.002", "\"rate\": 0.001")
            .fold(raw) { text, from -> text.replace(from, "\"rate\": 0.0") }
        check(never != raw) { "치환이 아무것도 바꾸지 못했다" }

        val clock = VirtualClock(Instant.EPOCH)
        val task = runToEnd(host(TaskMachineFixtures.document(never), clock), clock)

        assertEquals(TaskState.SUCCEEDED, task.machine.state)
        assertTrue(listener.reports.none { it.startsWith("fault") }, "${listener.reports}")
    }

    @Test
    fun `관측을 늘려도 결과가 같다`() {
        // **`tick`은 모든 RPC 진입에서 돈다.** 추첨이 완주 판정 밖에 있으면
        // 뽑는 횟수가 **관측 횟수**에 달리고, 소비자가 보는 것이 결과를 바꾼다
        // — §12.1이 깨지는 자리다.
        //
        // 종착 상태만 비교하면 부족하다(둘 다 SUCCEEDED로 끝날 수 있다).
        // **다 돌린 뒤 난수의 다음 값**이 지금까지 몇 번 뽑았는지의 지문이다.
        fun fingerprint(extraTicks: Int): Pair<TaskState, Double> {
            val clock = VirtualClock(Instant.EPOCH)
            val document = TaskMachineFixtures.document()
            val random = Seeded(5)
            val tasks = TaskHost(
                CapabilityProjection.of(document), document, clock,
                EngineListener.NONE, FaultRegistry(clock), random,
            )
            tasks.start(
                "t1", 1, "pick_place",
                listOf(
                    TaskMachineFixtures.param("object_id", "b"),
                    TaskMachineFixtures.param("destination", "d"),
                ),
            )
            repeat(extraTicks + 1) { tasks.tick() }
            clock.advance(Duration.ofSeconds(60))
            repeat(extraTicks + 1) { tasks.tick() }
            return tasks.find("t1")!!.machine.state to random.fraction()
        }
        assertEquals(fingerprint(0), fingerprint(7), "관측 횟수가 추첨을 바꿨다")
    }
}
