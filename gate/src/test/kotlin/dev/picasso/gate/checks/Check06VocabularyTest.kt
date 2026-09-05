package dev.picasso.gate.checks

import dev.picasso.gate.CheckResult
import dev.picasso.gate.Resource
import dev.picasso.gate.Severity
import dev.picasso.gate.input.GateInput
import dev.picasso.gate.input.LedgerAnswer
import dev.picasso.gate.input.LedgerQuery
import dev.picasso.gate.input.ProfileKey
import dev.picasso.gate.model.ProfileDocument
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Check06VocabularyTest {

    /** 한 스킬짜리 최소 문서. 바꾸고 싶은 것만 인자로 준다. */
    private fun profile(
        skills: String = PICK_PLACE_1_0,
        optionalFields: String = "[]",
        exclusive: Boolean = false,
        durationSeconds: Int = 45,
    ): String = """
        {
          "schema_version": "1.0.0", "vendor": "acme", "model": "r1", "revision": 1,
          "skills": [ $skills ],
          "optional_fields": $optionalFields,
          "durations": [ { "skill_type": "pick_place", "seconds": $durationSeconds } ],
          "publish_interval": { "min_seconds": 1, "max_seconds": 30 },
          "protocol_limits": { "max_string_length": 256, "max_array_length": 32 },
          "exclusive_control_required": $exclusive, "replay_buffer_size": 16
        }
    """.trimIndent()

    private val key = ProfileKey("acme", "r1")

    private fun doc(json: String) = ProfileDocument.parse("acme-r1.json", json).getOrThrow()

    private fun run(
        base: String?,
        head: String?,
        registry: LedgerQuery? = null,
    ): CheckResult = Check06Vocabulary().run(
        GateInput(
            profiles = if (head == null) emptyList() else listOf(doc(head)),
            baseline = if (base == null) emptyMap() else mapOf(key to base),
            registry = registry,
        ),
    )

    private fun ledger(consumers: Int, inflight: Int) = object : LedgerQuery {
        override fun activeConsumers(skillType: String, major: Int) =
            LedgerAnswer.Observed(consumers, Instant.EPOCH)
        override fun inflightTasks(skillType: String, major: Int) =
            LedgerAnswer.Observed(inflight, Instant.EPOCH)
    }

    private val blindLedger = object : LedgerQuery {
        override fun activeConsumers(skillType: String, major: Int) =
            LedgerAnswer.NotObservable("브로커 구독이 끊겼다")
        override fun inflightTasks(skillType: String, major: Int) =
            LedgerAnswer.NotObservable("브로커 구독이 끊겼다")
    }

    @Test
    fun `문서와 기준선을 요구한다`() {
        assertEquals(
            setOf(Resource.PROFILE_DOCUMENT, Resource.BASELINE),
            Check06Vocabulary().requires,
        )
    }

    @Test
    fun `기준선에 없는 신규 프로파일은 통과한다`() {
        // §11.1 — 기준선이 없으면(신규) 파괴 검사는 통과로 처리한다.
        assertTrue(run(base = null, head = profile()) is CheckResult.Passed)
    }

    @Test
    fun `변화가 없으면 통과한다`() {
        val r = run(profile(), profile())
        r as CheckResult.Passed
        assertTrue(r.findings.isEmpty(), "변화가 없는데 소견을 냈다: ${r.findings.map { it.message }}")
    }

    // ── 규칙 1: 선언된 버전 증가가 분류와 맞는가

    @Test
    fun `선택 파라미터 추가는 minor 증가로 충분하다`() {
        assertTrue(run(profile(), profile(PICK_PLACE_1_1_PLUS_OPTIONAL)) is CheckResult.Passed)
    }

    @Test
    fun `선택 파라미터를 추가하고 minor를 안 올리면 실패한다`() {
        val head = profile(PICK_PLACE_1_1_PLUS_OPTIONAL.replace("\"minor\": 1", "\"minor\": 0"))
        val r = run(profile(), head)
        r as CheckResult.Failed
        assertTrue(r.findings.any { it.message.contains("minor") })
    }

    @Test
    fun `키를 지우고 minor만 올리면 실패한다`() {
        // §11.2 6번 규칙 1의 대표 사례. 음성 케이스이기도 하다.
        val head = profile(
            """
            { "skill_type": "pick_place", "major": 1, "minor": 1,
              "pause_support": "YES", "cancel_support": "YES",
              "parameters": [ { "key": "object_id", "value_type": "STRING", "optional": false } ] }
            """,
        )
        val r = run(profile(), head)
        r as CheckResult.Failed
        assertTrue(r.findings.any { it.message.contains("grip_force") && it.message.contains("major") })
    }

    @Test
    fun `타입 변경은 major를 요구한다`() {
        val head = profile(
            PICK_PLACE_1_0.replace("\"minor\": 0", "\"minor\": 1").replace("\"NUMBER\"", "\"INTEGER\""),
        )
        val r = run(profile(), head)
        r as CheckResult.Failed
        assertTrue(r.findings.any { it.message.contains("value_type") })
    }

    @Test
    fun `단위 변경은 major를 요구한다`() {
        // §5.2 — 파라미터 키는 불변이다. 단위가 바뀌면 새 키를 만든다.
        val head = profile(
            PICK_PLACE_1_0.replace("\"minor\": 0", "\"minor\": 1")
                .replace("\"unit\": \"N\"", "\"unit\": \"kgf\""),
        )
        val r = run(profile(), head)
        r as CheckResult.Failed
        assertTrue(r.findings.any { it.message.contains("unit") || it.message.contains("단위") })
    }

    @Test
    fun `필수 파라미터 추가는 major를 요구한다`() {
        val head = profile(
            """
            { "skill_type": "pick_place", "major": 1, "minor": 1,
              "pause_support": "YES", "cancel_support": "YES",
              "parameters": [
                { "key": "object_id", "value_type": "STRING", "optional": false },
                { "key": "grip_force", "value_type": "NUMBER", "optional": true, "unit": "N" },
                { "key": "destination", "value_type": "STRING", "optional": false }
              ] }
            """,
        )
        val r = run(profile(), head)
        r as CheckResult.Failed
        assertTrue(r.findings.any { it.message.contains("destination") })
    }

    @Test
    fun `선택을 필수로 바꾸는 것도 major를 요구한다`() {
        val head = profile(
            PICK_PLACE_1_0.replace("\"minor\": 0", "\"minor\": 1")
                .replace(
                    "\"key\": \"grip_force\", \"value_type\": \"NUMBER\", \"optional\": true",
                    "\"key\": \"grip_force\", \"value_type\": \"NUMBER\", \"optional\": false",
                ),
        )
        assertTrue(run(profile(), head) is CheckResult.Failed)
    }

    @Test
    fun `pause_support 철회를 침묵하지 않는다`() {
        // compareProjection이 skills를 빼고 inPlace는 parameters만 보므로
        // 어느 쪽도 보지 않던 자리다(실측: YES → NO가 완전 침묵).
        val head = profile(PICK_PLACE_1_0.replace("\"pause_support\": \"YES\"", "\"pause_support\": \"NO\""))
        val r = run(profile(), head)
        r as CheckResult.Passed
        assertTrue(r.findings.any { it.message.contains("pause_support") })
    }

    @Test
    fun `cancel_support 철회를 침묵하지 않는다`() {
        val head = profile(
            PICK_PLACE_1_0.replace("\"cancel_support\": \"YES\"", "\"cancel_support\": \"UNKNOWN\""),
        )
        val r = run(profile(), head)
        r as CheckResult.Passed
        assertTrue(r.findings.any { it.message.contains("cancel_support") })
    }

    // ── optional_fields

    @Test
    fun `optional_fields의 SUPPORTED에서 REQUIRED로의 변경은 필수화다`() {
        // §5.3 — 보내지 않는 클라이언트가 핸드셰이크에서 걸린다.
        val base = profile(optionalFields = supported("task.parameters.grip_force"))
        val head = profile(optionalFields = required("task.parameters.grip_force"))
        val r = run(base, head)
        r as CheckResult.Failed
        assertTrue(r.findings.any { it.message.contains("grip_force") })
    }

    @Test
    fun `스킬 파라미터와 대응하지 않는 경로의 필수화도 잡는다`() {
        // §5.3의 대표 예시가 하필 이것이다 — task.parameters.approach_vector는
        // 스킬 파라미터가 아니라 태스크 메시지 필드를 가리킨다.
        val base = profile(optionalFields = supported("task.parameters.approach_vector"))
        val head = profile(optionalFields = required("task.parameters.approach_vector"))
        val r = run(base, head)
        r as CheckResult.Failed
        assertTrue(r.findings.any { it.message.contains("approach_vector") })
    }

    @Test
    fun `optional_fields 선언 삭제를 침묵하지 않는다`() {
        val base = profile(optionalFields = supported("task.parameters.grip_force"))
        val r = run(base, profile())
        r as CheckResult.Passed
        assertTrue(r.findings.any { it.message.contains("grip_force") })
    }

    // ── 규칙 2: 축소 판정과 원장 조회

    @Test
    fun `스킬 제거는 축소이고 원장이 없으면 부분 건너뜀이다`() {
        // CI에는 DB가 없어 상시 이 상태다(§11.1). 이것으로 CI를 빨갛게 만들면
        // 사람이 게이트를 꺼버린다. 대신 출력에 남는다.
        val r = run(profile(), profile(NAVIGATE_TO_1_0))
        r as CheckResult.Passed
        assertEquals(setOf(Resource.REGISTRY), r.skippedParts)
        assertTrue(r.findings.any { it.severity == Severity.WARNING && it.message.contains("축소") })
    }

    @Test
    fun `major 증가는 축소다`() {
        val head = profile(
            """
            { "skill_type": "pick_place", "major": 2, "minor": 0,
              "pause_support": "YES", "cancel_support": "YES",
              "parameters": [ { "key": "object_id", "value_type": "STRING", "optional": false } ] }
            """,
        )
        val r = run(profile(), head)
        r as CheckResult.Passed
        assertTrue(r.findings.any { it.message.contains("축소") })
    }

    @Test
    fun `옛 major를 남긴 채 새 major를 더하는 것은 확장이다`() {
        // §9.3의 확장 단계다. 여기서 소견을 내면 정상 절차를 막는다.
        val head = profile(
            PICK_PLACE_1_0 + """,
            { "skill_type": "pick_place", "major": 2, "minor": 0,
              "pause_support": "YES", "cancel_support": "YES",
              "parameters": [ { "key": "object_id", "value_type": "STRING", "optional": false } ] }
            """,
        )
        val r = run(profile(), head)
        r as CheckResult.Passed
        assertTrue(r.findings.isEmpty(), "확장을 막았다: ${r.findings.map { it.message }}")
    }

    @Test
    fun `프로파일 파일이 사라지면 축소로 잡는다`() {
        // input.profiles만 순회하면 완전히 침묵한다(실측: Passed, 소견 0).
        // 프로파일 삭제는 그 기종의 능력이 전부 소멸하는 최대 규모의 축소다.
        val r = run(profile(), head = null)
        r as CheckResult.Passed
        assertTrue(
            r.findings.any { it.message.contains("프로파일 제거") },
            "프로파일 삭제가 조용히 통과했다: ${r.findings.map { it.message }}",
        )
        assertEquals(setOf(Resource.REGISTRY), r.skippedParts)
    }

    @Test
    fun `사라진 프로파일도 원장이 막는다`() {
        val r = run(profile(), head = null, registry = ledger(consumers = 1, inflight = 0))
        assertTrue(r is CheckResult.Failed, "소비자가 남았는데 프로파일 삭제를 승인했다")
    }

    @Test
    fun `소비자가 남아 있으면 축소를 거부한다`() {
        // §9.3 — 운영자가 판단하지 않는다.
        val r = run(profile(), profile(NAVIGATE_TO_1_0), registry = ledger(consumers = 3, inflight = 0))
        r as CheckResult.Failed
        assertTrue(r.findings.any { it.message.contains("3") })
    }

    @Test
    fun `비종착 태스크가 남아 있으면 축소를 거부한다`() {
        val r = run(profile(), profile(NAVIGATE_TO_1_0), registry = ledger(consumers = 0, inflight = 2))
        assertTrue(r is CheckResult.Failed, "드레인이 안 됐는데 축소를 승인했다")
    }

    @Test
    fun `둘 다 0이면 축소를 허용하되 흔적을 남긴다`() {
        // §9.3이 "추측에서 조회로"를 요점으로 삼은 만큼 관측했다는 사실
        // 자체가 산출물이다. PASS 한 줄만 남으면 무엇을 승인했는지 모른다.
        val r = run(profile(), profile(NAVIGATE_TO_1_0), registry = ledger(consumers = 0, inflight = 0))
        r as CheckResult.Passed
        assertEquals(emptySet(), r.skippedParts, "조회했는데 건너뜀으로 남았다")
        assertTrue(r.findings.any { it.message.contains("승인") })
    }

    @Test
    fun `관측 불가를 0으로 읽지 않는다`() {
        // 브로커 구독이 끊긴 동안 테이블이 비면 조회가 정확히 0을 돌려준다.
        // 그것을 승인으로 읽으면 아직 쓰는 소비자가 있는 능력을 지운다.
        val r = run(profile(), profile(NAVIGATE_TO_1_0), registry = blindLedger)
        r as CheckResult.Passed
        assertEquals(setOf(Resource.REGISTRY), r.skippedParts, "관측 불가를 승인으로 읽었다")
        assertTrue(r.findings.any { it.message.contains("관측") })
    }

    // ── 안전망과 잡음 억제

    @Test
    fun `배타 제어 요구가 켜지면 경고한다`() {
        // §4.9 — 소비자는 이제 자신이 유일한 명령자임을 전제해야 한다.
        val r = run(profile(exclusive = false), profile(exclusive = true))
        r as CheckResult.Passed
        assertTrue(r.findings.any { it.message.contains("exclusive_control_required") })
    }

    @Test
    fun `비투영 필드만 바뀌면 아무 말도 하지 않는다`() {
        // durations는 투영에 안 들어간다. 소요시간 조정에 major 증가를
        // 요구하면 사람이 게이트를 싫어하게 된다.
        val r = run(profile(durationSeconds = 45), profile(durationSeconds = 50))
        r as CheckResult.Passed
        assertTrue(r.findings.isEmpty(), "비투영 변경에 소견을 냈다: ${r.findings.map { it.message }}")
    }

    @Test
    fun `기준선 문서가 깨졌으면 소견을 낸다`() {
        // 조용히 "기준선 없음 = 신규"로 넘기면 파괴 검사가 통째로 사라진다.
        val r = Check06Vocabulary().run(
            GateInput(profiles = listOf(doc(profile())), baseline = mapOf(key to "{ 깨진 JSON")),
        )
        r as CheckResult.Failed
        assertTrue(r.findings.any { it.message.contains("기준선") })
    }

    private companion object {
        const val PICK_PLACE_1_0 = """
            { "skill_type": "pick_place", "major": 1, "minor": 0,
              "pause_support": "YES", "cancel_support": "YES",
              "parameters": [
                { "key": "object_id", "value_type": "STRING", "optional": false },
                { "key": "grip_force", "value_type": "NUMBER", "optional": true, "unit": "N" }
              ] }
        """

        const val PICK_PLACE_1_1_PLUS_OPTIONAL = """
            { "skill_type": "pick_place", "major": 1, "minor": 1,
              "pause_support": "YES", "cancel_support": "YES",
              "parameters": [
                { "key": "object_id", "value_type": "STRING", "optional": false },
                { "key": "grip_force", "value_type": "NUMBER", "optional": true, "unit": "N" },
                { "key": "verify_grasp", "value_type": "BOOL", "optional": true }
              ] }
        """

        const val NAVIGATE_TO_1_0 = """
            { "skill_type": "navigate_to", "major": 1, "minor": 0,
              "pause_support": "YES", "cancel_support": "YES",
              "parameters": [ { "key": "location", "value_type": "STRING", "optional": false } ] }
        """

        fun supported(path: String) = """[ { "parameter_path": "$path", "support": "SUPPORTED" } ]"""
        fun required(path: String) = """[ { "parameter_path": "$path", "support": "REQUIRED" } ]"""
    }
}
