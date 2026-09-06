package dev.picasso.mimic.engine

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.Lifetime
import dev.picasso.contracts.v1.Reference
import dev.picasso.mimic.profile.CapabilityProjection
import dev.picasso.profile.ProfileDocument
import java.nio.file.Files
import java.nio.file.Path
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
    val faults = mutableListOf<Fault>()

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
        faults += fault
    }
}

/**
 * 픽스처의 `failure_modes`를 통째로 갈아 끼운다.
 *
 * **잔여를 검사한다.** 머리와 꼬리만 확인하면, `failure_modes`와
 * `replay_buffer_size` 사이에 새 블록이 생겼을 때 그 블록이 **통째로 사라진
 * 문서**로 시험이 돌면서 두 확인이 다 통과한다. 최상위 키 목록을 원문과
 * 견줘서 그 경우를 막는다.
 */
private object FixtureRewrite {

    private val mapper = ObjectMapper()

    fun withModes(vararg modes: String): ProfileDocument {
        val raw = TaskMachineFixtures.fixtureRaw
        val head = raw.substringBefore(HEAD_MARK)
        // **마지막 `],`다.** 첫 것을 잡으면 skills 배열이 잘려 나간다.
        val tail = raw.substringAfterLast(TAIL_MARK)
        check(head != raw) { "픽스처의 failure_modes 블록을 못 찾았다" }
        check(tail.startsWith("  \"replay_buffer_size\"")) { "꼬리를 잘못 잘랐다: ${tail.take(40)}" }

        val text = head + HEAD_MARK + "\n" + modes.joinToString(",\n") + "\n" + TAIL_MARK + tail

        val before = mapper.readTree(raw).fieldNames().asSequence().toList()
        val after = mapper.readTree(text).fieldNames().asSequence().toList()
        check(before == after) { "재작성이 최상위 키를 잃었다: ${before - after.toSet()}" }

        return TaskMachineFixtures.document(text)
    }

    /** 모드 하나. `skillType`이 `null`이면 로봇 수준이다. */
    fun mode(
        errorType: String,
        rate: Double,
        skillType: String? = "pick_place",
        resolution: String = "SELF_RETRIABLE",
    ): String {
        val skill = if (skillType == null) "" else "      \"skill_type\": \"$skillType\",\n"
        return "    {\n" +
            "      \"error_type\": \"$errorType\",\n" +
            skill +
            "      \"rate\": $rate,\n" +
            "      \"can_continue_current_task\": false,\n" +
            "      \"can_accept_new_task\": true,\n" +
            "      \"resolution\": \"$resolution\",\n" +
            "      \"active_until\": \"UNTIL_CLEARED\"\n" +
            "    }"
    }

    private const val HEAD_MARK = "  \"failure_modes\": ["
    private const val TAIL_MARK = "  ],\n\n"
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
        assertEquals(nav.size, drawsBetween(Seeded(11), drawn), "인출 수가 해당 모드 수와 다르다")
    }

    @Test
    fun `처음 걸린 것이 이기고 나머지는 안 뽑는다`() {
        // **조기 종료가 §12.1의 불변식이다.** 없애도(모든 적용 모드에서 인출한
        // 뒤 첫 히트를 반환) 결과는 같으므로, 결과를 보는 시험은 전부 초록이다
        // — 실측으로 그 구현이 스위트를 통과했다. **인출 수만이 그것을 본다.**
        val hot = FailureDraw(
            FixtureRewrite.withModes(
                FixtureRewrite.mode("SKILL_EXECUTION_FAILED", 1.0),
                FixtureRewrite.mode("PAYLOAD_LOST", 1.0),
            ),
        )
        assertEquals(2, hot.applicable("pick_place").size, "모드가 둘이 아니다")

        val drawn = Seeded(0)
        assertEquals("SKILL_EXECUTION_FAILED", hot.drawFor("pick_place", drawn)?.errorType)
        assertEquals(1, drawsBetween(Seeded(0), drawn), "걸리고도 나머지를 뽑았다")
    }

    @Test
    fun `선언 순서가 실제 추첨 순서다`() {
        // **`applicable()`을 원문과 견주는 것으로는 부족하다** — 그것은 헬퍼의
        // 투영이지 추첨의 거동이 아니다. 실측으로 `drawFor` 안에서
        // `applicable(...).reversed()`를 돌려도 스위트가 통째로 초록이었다.
        // 둘 다 반드시 걸리게 해 두고 **어느 쪽이 나오는지**를 본다.
        val first = FailureDraw(
            FixtureRewrite.withModes(
                FixtureRewrite.mode("SKILL_EXECUTION_FAILED", 1.0),
                FixtureRewrite.mode("PAYLOAD_LOST", 1.0),
            ),
        )
        assertEquals(
            "SKILL_EXECUTION_FAILED",
            first.drawFor("pick_place", Seeded(0))?.errorType,
            "선언상 앞엣것이 안 나왔다",
        )

        // 순서만 뒤집으면 결과도 뒤집혀야 한다. 안 그러면 이름으로 정렬하거나
        // 고정된 것을 고르는 구현이다.
        val second = FailureDraw(
            FixtureRewrite.withModes(
                FixtureRewrite.mode("PAYLOAD_LOST", 1.0),
                FixtureRewrite.mode("SKILL_EXECUTION_FAILED", 1.0),
            ),
        )
        assertEquals(
            "PAYLOAD_LOST",
            second.drawFor("pick_place", Seeded(0))?.errorType,
            "선언 순서를 안 본다",
        )
    }

    @Test
    fun `원문의 선언 순서를 그대로 읽는다`() {
        // 위 시험이 거동을 보고, 이것은 **파싱**을 본다. 원문에서 정규식으로
        // 훑은 순서와 견준다 — 파서로 만든 목록끼리 비교하면 아무것도 안 본다.
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
        assertEquals(
            "SKILL_EXECUTION_FAILED",
            FailureDraw(
                FixtureRewrite.withModes(FixtureRewrite.mode("SKILL_EXECUTION_FAILED", 1.0)),
            ).drawFor("pick_place", Seeded(0))?.errorType,
            "rate 1.0인데 안 걸렸다",
        )

        val never = FailureDraw(zeroRateDocument())
        val random = Seeded(0)
        assertTrue(
            List(2_000) { never.drawFor("pick_place", random) }.all { it == null },
            "rate 0인데 걸렸다",
        )
    }

    @Test
    fun `모르는 resolution은 조용히 접히지 않는다`() {
        // 두 벌(스키마 문자열·엔진 enum)을 두는 이상 다리를 시험한다.
        // **스키마가 허용하는 값 전부를** 본다 — 리터럴 목록이면 스키마가
        // 늘어도 여기가 조용하다. 못 읽으면 아래 단언이 빨갛게 난다.
        val allowed = Regex("\"resolution\"[\\s\\S]{0,200}?\"enum\"\\s*:\\s*\\[([^\\]]+)\\]")
            .find(schemaText())
            ?.groupValues?.get(1)
            ?.let { body -> Regex("\"([A-Z_]+)\"").findAll(body).map { it.groupValues[1] }.toList() }
        assertEquals(
            listOf("SELF_RETRIABLE", "NEEDS_INTERVENTION", "TERMINAL"),
            allowed,
            "스키마에서 resolution 값을 못 읽었다",
        )
        // 여기서는 "안 터진다"만 본다. 매핑이 **맞는지**는
        // DeclaredFailureChainTest 의 `resolution 셋이 각각 다른 종착을 만든다`가
        // 문자열로 키잉해 표를 돌면서 본다. 둘이 함께 있어야 닫힌다.
        allowed!!.forEach { FailureDraw.resolutionOf(it) }

        assertTrue(
            runCatching { FailureDraw.resolutionOf("NOT_A_RESOLUTION") }.isFailure,
            "모르는 값을 조용히 접었다",
        )
    }
}

/**
 * 프로파일이 선언한 것이 §4.6의 `Fault`로 **빠짐없이** 옮겨지는가.
 *
 * **이것이 없으면 `faultOf`가 통째로 거짓말을 해도 초록이다.** 실측으로 두
 * 불리언을 상수 `true`로, `error_hint`를 `""`로, `UNTIL_NEW_TASK`를
 * `UNTIL_CLEARED`로, `skill_id` reference를 삭제 — 넷을 **동시에** 망가뜨려도
 * `:mimic:test`와 `:harness:test`가 전부 통과했다. 어느 시험도 뽑힌 결함의
 * 내용을 본 적이 없었기 때문이다.
 */
class FaultProjectionTest {

    private val modes = TaskMachineFixtures.document().failureModes

    private fun faultFor(errorType: String): Fault {
        val mode = modes.single { it.errorType == errorType }
        return FailureDraw.faultOf(mode, skillType = "pick_place", taskId = "t1")
    }

    private fun reference(fault: Fault, key: Reference.Key): String? =
        fault.referencesList.firstOrNull { it.key == key }?.value

    @Test
    fun `픽스처의 모드 넷을 통째로 옮긴다`() {
        // 대표 하나만 보면 나머지 셋이 침묵한다. **표 크기를 먼저 못박는다.**
        assertEquals(4, modes.size, "픽스처의 모드가 넷이 아니다: ${modes.map { it.errorType }}")

        // (error_type, 계속 가능, 새 태스크 가능, 수명 종류, 스킬 수준인가)
        val expected = listOf(
            Row("SKILL_EXECUTION_FAILED", false, true, Lifetime.Kind.KIND_UNTIL_NEW_TASK, true),
            Row("PAYLOAD_LOST", false, true, Lifetime.Kind.KIND_UNTIL_CLEARED, true),
            Row("LOCALIZATION_LOST", false, false, Lifetime.Kind.KIND_UNTIL_CLEARED, false),
            // active_until 이 없다 — 그것이 UNTIL_CLEARED 로 와야 한다.
            Row("X_FIXTURE_SIMULATED_HARDWARE_FAULT", false, false, Lifetime.Kind.KIND_UNTIL_CLEARED, false),
        )
        assertEquals(modes.map { it.errorType }, expected.map { it.errorType }, "표가 픽스처와 다르다")

        // 표가 두 불리언·수명 종류·등급 **각각에서** 실제로 갈리는지 본다.
        // 한 값으로 채워진 열은 아무것도 시험하지 않는다.
        assertEquals(2, expected.map { it.canAcceptNewTask }.distinct().size, "새 태스크 가능 열이 안 갈린다")
        assertEquals(2, expected.map { it.kind }.distinct().size, "수명 종류 열이 안 갈린다")
        assertEquals(2, expected.map { it.skillLevel }.distinct().size, "등급 열이 안 갈린다")

        expected.forEach { row ->
            val fault = faultFor(row.errorType)
            assertEquals(row.errorType, fault.errorType)
            assertEquals(row.canContinue, fault.canContinueCurrentTask, "${row.errorType}: 계속 가능")
            assertEquals(row.canAcceptNewTask, fault.canAcceptNewTask, "${row.errorType}: 새 태스크 가능")
            assertEquals(row.kind, fault.activeUntil.kind, "${row.errorType}: 수명")
        }
    }

    @Test
    fun `스킬 수준 모드만 references를 단다`() {
        // §4.6 — 스키마가 "`skill_type`이 있으면 스킬 수준, 없으면 로봇 수준"을
        // 못박았다. 무조건 달면 소비자가 그 구분을 **거꾸로** 읽고,
        // `FaultRegistry`의 키가 스킬마다 갈려 같은 로봇 수준 결함이 부푼다.
        val skillLevel = faultFor("PAYLOAD_LOST")
        assertEquals("pick_place", reference(skillLevel, Reference.Key.KEY_SKILL_ID))
        assertEquals("t1", reference(skillLevel, Reference.Key.KEY_TASK_ID))

        val robotLevel = faultFor("LOCALIZATION_LOST")
        assertEquals(
            emptyList(), robotLevel.referencesList,
            "로봇 수준 결함에 그때 마침 돌던 스킬의 이름이 실렸다",
        )
    }

    @Test
    fun `error_hint를 프로파일에서 읽는다`() {
        // 상수 `""`면 §4.6이 "사람이 취할 조치"라고 둔 필드가 죽는다.
        // 스키마가 NEEDS_INTERVENTION 에만 조건부 필수로 걸었으므로
        // **있는 것과 없는 것을 둘 다** 본다.
        assertTrue(
            faultFor("PAYLOAD_LOST").errorHint.isNotBlank(),
            "선언된 hint가 사라졌다",
        )
        assertEquals(
            modes.single { it.errorType == "PAYLOAD_LOST" }.errorHint,
            faultFor("PAYLOAD_LOST").errorHint,
        )
        assertEquals("", faultFor("SKILL_EXECUTION_FAILED").errorHint, "없던 hint가 생겼다")
    }

    @Test
    fun `스키마가 허용하는 active_until을 전부 옮긴다`() {
        // 스키마의 enum 둘 + 부재. 리터럴 목록이면 스키마가 늘어도 조용하다.
        val allowed = Regex("\"active_until\"[\\s\\S]{0,400}?\"enum\"\\s*:\\s*\\[([^\\]]+)\\]")
            .find(schemaText())
            ?.groupValues?.get(1)
            ?.let { body -> Regex("\"([A-Z_]+)\"").findAll(body).map { it.groupValues[1] }.toList() }
        assertEquals(
            listOf("UNTIL_CLEARED", "UNTIL_NEW_TASK"), allowed,
            "스키마에서 active_until 값을 못 읽었다",
        )

        assertEquals(
            Lifetime.Kind.KIND_UNTIL_NEW_TASK,
            faultFor("SKILL_EXECUTION_FAILED").activeUntil.kind,
        )
        assertEquals(
            Lifetime.Kind.KIND_UNTIL_CLEARED,
            faultFor("PAYLOAD_LOST").activeUntil.kind,
        )
    }

    @Test
    fun `모르는 active_until은 조용히 접히지 않는다`() {
        // 조용히 접으면 §4.3의 수명이 미정의가 되고 소비자가 지워진 결함을
        // 영원히 든다.
        val bogus = modes.first().copy(activeUntil = "UNTIL_THE_COWS_COME_HOME")
        assertTrue(
            runCatching { FailureDraw.faultOf(bogus, "pick_place", "t1") }.isFailure,
            "모르는 수명을 조용히 접었다",
        )
    }

    private data class Row(
        val errorType: String,
        val canContinue: Boolean,
        val canAcceptNewTask: Boolean,
        val kind: Lifetime.Kind,
        val skillLevel: Boolean,
    )
}

/** 추첨이 §4.5의 전파 규칙 1을 타고 **태스크 결과**가 되는가 — 사슬을 끝까지. */
class DeclaredFailureChainTest {

    private val listener = FaultOrderListener()

    private fun location() =
        TaskMachineFixtures.param("location", "dock-3")

    /** `navigate_to`에 반드시 걸리는 모드 하나만 남긴 프로파일. */
    private fun always(resolution: String): ProfileDocument = FixtureRewrite.withModes(
        FixtureRewrite.mode(
            "SKILL_EXECUTION_FAILED", rate = 1.0,
            skillType = "navigate_to", resolution = resolution,
        ),
    )

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
        assertEquals(3, table.values.distinct().size, "종착이 안 갈리는 표다")

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
    fun `발행된 결함이 그 모드를 그대로 싣는다`() {
        // 사슬 시험이 상태만 보면 **결함의 내용**은 무엇이든 될 수 있다.
        val clock = VirtualClock(Instant.EPOCH)
        runToEnd(host(always("SELF_RETRIABLE"), clock), clock)

        val fault = listener.faults.single()
        assertEquals("SKILL_EXECUTION_FAILED", fault.errorType)
        assertEquals("navigate_to", fault.referencesList.single { it.key == Reference.Key.KEY_SKILL_ID }.value)
        assertEquals("t1", fault.referencesList.single { it.key == Reference.Key.KEY_TASK_ID }.value)
        assertEquals(Lifetime.Kind.KIND_UNTIL_CLEARED, fault.activeUntil.kind)
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
        val clock = VirtualClock(Instant.EPOCH)
        val task = runToEnd(host(zeroRateDocument(), clock), clock)

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

        val quiet = fingerprint(0)
        // **지문이 공허하지 않은지 먼저 본다.** 태스크가 완주하지 않으면
        // 양쪽 다 인출 0이라 자명하게 같다 — 실측으로 픽스처의 소요시간을
        // 45초에서 6000초로만 바꿔도 이 시험이 그대로 초록이었다.
        assertTrue(quiet.first.isTerminalish(), "태스크가 끝나지 않았다: ${quiet.first}")
        assertNotEquals(Seeded(5).fraction(), quiet.second, "추첨이 한 번도 안 일어났다")

        assertEquals(quiet, fingerprint(7), "관측 횟수가 추첨을 바꿨다")
    }

    private fun TaskState.isTerminalish(): Boolean = this in setOf(
        TaskState.SUCCEEDED, TaskState.FAILED, TaskState.RETRIABLE,
        TaskState.NEEDS_INTERVENTION, TaskState.CANCELLED, TaskState.CANCELLED_RECOVERY_FAILED,
    )
}

// ── 파일 공용 ────────────────────────────────────────────────────────────

/**
 * 선언된 모드의 `rate`를 **전부** 0으로 만든 프로파일.
 *
 * **결과를 단언한다.** `치환이 무언가 바꿨다`만 보면 픽스처에 다섯 번째
 * rate가 생겼을 때 그것을 놓치고도 통과한다 — 규율 2번의 정확한 표적이다.
 */
private fun zeroRateDocument(): ProfileDocument {
    val text = Regex("\"rate\"\\s*:\\s*[0-9.]+")
        .replace(TaskMachineFixtures.fixtureRaw, "\"rate\": 0.0")
    val left = Regex("\"rate\"\\s*:\\s*(?!0\\.0\\b)[0-9.]+").find(text)
    check(left == null) { "0으로 못 바꾼 rate가 남았다: ${left?.value}" }
    val document = TaskMachineFixtures.document(text)
    check(document.failureModes.isNotEmpty()) { "모드가 통째로 사라졌다" }
    check(document.failureModes.all { it.rate == 0.0 }) { "파싱된 rate가 0이 아니다" }
    return document
}

/** 시작 시점의 [Seeded]와 [used]를 견줘 인출 횟수를 센다. */
private fun drawsBetween(fresh: Seeded, used: Seeded, limit: Int = 32): Int {
    val target = used.fraction()
    repeat(limit) { n ->
        if (fresh.fraction() == target) return n
    }
    error("인출 수를 $limit 안에서 못 셌다")
}

private fun schemaText(): String = Files.readString(
    Path.of("..", "profile", "schema", "capability-profile.schema.json").normalize(),
)
