package dev.picasso.gate.checks

import dev.picasso.gate.CheckResult
import dev.picasso.gate.Resource
import dev.picasso.gate.input.GateInput
import dev.picasso.gate.input.MalformedProfile
import dev.picasso.gate.model.ProfileDocument
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Check03ProfileSchemaTest {

    // CRLF 체크아웃이면 \n을 담은 치환이 전부 빗나가고 음성 시험이 조용히
    // 통과한다. .gitattributes가 새 클론을 막지만 기존 트리는 안 고쳐진다.
    private fun read(vararg parts: String): String {
        val p = Path.of("..", *parts).normalize()
        check(Files.exists(p)) { "파일이 없다: ${p.toAbsolutePath()}" }
        return Files.readString(p).replace("\r\n", "\n")
    }

    private val schemaJson: String by lazy {
        read("profile", "schema", "capability-profile.schema.json")
    }
    private val fixtureJson: String by lazy { read("profile", "fixtures", "minimal.json") }

    private fun doc(json: String, path: String = "t.json") =
        ProfileDocument.parse(path, json).getOrThrow()

    private fun run(vararg docs: ProfileDocument) =
        Check03ProfileSchema().run(GateInput(profiles = docs.toList(), schemaJson = schemaJson))

    /** 치환이 빗나가면 시험이 조용히 통과한다. 그것을 여기서 막는다. */
    private fun mutate(from: String, to: String): String {
        val out = fixtureJson.replace(from, to)
        check(out != fixtureJson) { "치환이 아무것도 바꾸지 못했다: '$from'" }
        return out
    }

    @Test
    fun `문서만 요구하고 스키마 부재는 부분 건너뜀이다`() {
        // 스키마를 요구하면, 스키마가 없을 때 러너가 이 검사를 통째로
        // 건너뛰고 깨진 프로파일을 아무도 말하지 않은 채 종료코드 0이 난다.
        assertEquals(setOf(Resource.PROFILE_DOCUMENT), Check03ProfileSchema().requires)
    }

    @Test
    fun `스키마가 없어도 깨진 문서는 말한다`() {
        val r = Check03ProfileSchema().run(
            GateInput(malformed = listOf(MalformedProfile("bad.json", "Unexpected character"))),
        )
        r as CheckResult.Failed
        assertTrue(r.findings.single().location!!.contains("bad.json"))
        assertEquals(setOf(Resource.PROFILE_SCHEMA), r.skippedParts, "스키마 부재가 출력에 안 남는다")
    }

    @Test
    fun `스키마가 없으면 검증을 건너뛴 사실을 남긴다`() {
        val r = Check03ProfileSchema().run(GateInput(profiles = listOf(doc(fixtureJson))))
        r as CheckResult.Passed
        assertEquals(setOf(Resource.PROFILE_SCHEMA), r.skippedParts)
    }

    @Test
    fun `픽스처는 통과한다`() {
        val r = run(doc(fixtureJson, "minimal.json"))
        assertTrue(r is CheckResult.Passed, "픽스처가 자기 스키마를 통과하지 못한다: $r")
    }

    @Test
    fun `깨진 문서를 소견으로 보고한다`() {
        // 게이트를 죽이는 대신 이 검사가 말해야 한다.
        val r = Check03ProfileSchema().run(
            GateInput(
                malformed = listOf(MalformedProfile("bad.json", "Unexpected character")),
                schemaJson = schemaJson,
            ),
        )
        r as CheckResult.Failed
        assertTrue(r.findings.single().location!!.contains("bad.json"))
    }

    @Test
    fun `어댑터 전용 error_type은 프로파일이 선언할 수 없다`() {
        // 스키마의 enum에서 빠져 있으므로 스키마 검증이 거절한다.
        val r = run(doc(mutate("\"LOCALIZATION_LOST\"", "\"TERMINAL_STATE_VIOLATED\"")))
        assertTrue(r is CheckResult.Failed, "어댑터 전용 error_type이 통과했다")
    }

    @Test
    fun `벤더 접두사 error_type은 통과한다`() {
        val r = run(doc(mutate("\"LOCALIZATION_LOST\"", "\"X_ACME_BATTERY_CRITICAL\"")))
        assertTrue(r is CheckResult.Passed, "X_ 접두사를 거절했다: $r")
    }

    @Test
    fun `뒤집힌 범위를 잡는다`() {
        // JSON Schema 2020-12에 필드 간 수치 비교가 없다(Chunk 4 실측).
        val r = run(
            doc(mutate("\"min_value\": 0, \"max_value\": 120", "\"min_value\": 120, \"max_value\": 0")),
        )
        r as CheckResult.Failed
        assertTrue(r.findings.any { it.message.contains("grip_force") })
    }

    @Test
    fun `뒤집힌 발행 간격을 잡는다`() {
        val r = run(
            doc(mutate("\"min_seconds\": 1, \"max_seconds\": 30", "\"min_seconds\": 30, \"max_seconds\": 1")),
        )
        r as CheckResult.Failed
        assertTrue(r.findings.any { it.message.contains("publish_interval") })
    }

    @Test
    fun `같은 skill_type과 major가 둘이면 잡는다`() {
        // §5.2가 이 쌍을 동일성으로 규정한다. uniqueItems로는 표현할 수 없다 —
        // minor가 다르면 항목 전체가 달라 통과해 버린다.
        val r = run(
            doc(
                """
                {
                  "schema_version": "1.0.0", "vendor": "v", "model": "m", "revision": 1,
                  "skills": [
                    { "skill_type": "pick_place", "major": 1, "minor": 0,
                      "pause_support": "YES", "cancel_support": "YES",
                      "parameters": [ { "key": "a", "value_type": "STRING", "optional": false } ] },
                    { "skill_type": "pick_place", "major": 1, "minor": 2,
                      "pause_support": "YES", "cancel_support": "YES",
                      "parameters": [ { "key": "a", "value_type": "STRING", "optional": false } ] }
                  ],
                  "publish_interval": { "min_seconds": 1, "max_seconds": 30 },
                  "protocol_limits": { "max_string_length": 256, "max_array_length": 32 },
                  "exclusive_control_required": false, "replay_buffer_size": 16
                }
                """.trimIndent(),
            ),
        )
        r as CheckResult.Failed
        assertTrue(r.findings.any { it.message.contains("pick_place") && it.message.contains("중복") })
    }

    @Test
    fun `한 스킬 안 파라미터 키 중복을 잡는다`() {
        val r = run(
            doc(
                """
                {
                  "schema_version": "1.0.0", "vendor": "v", "model": "m", "revision": 1,
                  "skills": [
                    { "skill_type": "pick_place", "major": 1, "minor": 0,
                      "pause_support": "YES", "cancel_support": "YES",
                      "parameters": [
                        { "key": "a", "value_type": "STRING", "optional": false },
                        { "key": "a", "value_type": "BOOL", "optional": true }
                      ] }
                  ],
                  "publish_interval": { "min_seconds": 1, "max_seconds": 30 },
                  "protocol_limits": { "max_string_length": 256, "max_array_length": 32 },
                  "exclusive_control_required": false, "replay_buffer_size": 16
                }
                """.trimIndent(),
            ),
        )
        r as CheckResult.Failed
        assertTrue(r.findings.any { it.message.contains("key") && it.message.contains("중복") })
    }

    @Test
    fun `소견은 어느 파일 어느 위치인지 말한다`() {
        // 게이트가 무엇을 막았는지 알려주지 못하면 사람이 게이트를 끈다.
        // revision 0은 스키마가 허용한다(minimum: 0) — -1을 써야 위반이다.
        val r = run(doc(mutate("\"revision\": 1", "\"revision\": -1"), "acme-x.json"))
        r as CheckResult.Failed
        assertTrue(r.findings.all { it.location != null })
        assertTrue(r.findings.any { it.location!!.startsWith("acme-x.json#") })
    }

    @Test
    fun `스키마 소견이 JVM 로케일에 흔들리지 않는다`() {
        // networknt는 소견을 로케일별로 번역한다. 못 박지 않으면 같은 위반이
        // 이 머신과 LANG=C인 CI에서 다른 문자열이 되고, 소견을 문자열로 다루는
        // 모든 것(음성 하네스 포함)이 흔들린다.
        val broken = doc(mutate("\"revision\": 1", "\"revision\": -1"))

        fun messagesUnder(locale: Locale): List<String> {
            val saved = Locale.getDefault()
            return try {
                Locale.setDefault(locale)
                (run(broken) as CheckResult.Failed).findings.map { it.message }
            } finally {
                Locale.setDefault(saved)
            }
        }

        assertEquals(messagesUnder(Locale.KOREAN), messagesUnder(Locale.ENGLISH))
    }

    @Test
    fun `한 문서의 여러 잘못을 한꺼번에 보고한다`() {
        // 하나씩 고치게 만들면 사람이 게이트를 싫어하게 된다.
        val both = fixtureJson
            .replace("\"min_value\": 0, \"max_value\": 120", "\"min_value\": 120, \"max_value\": 0")
            .replace("\"min_seconds\": 1, \"max_seconds\": 30", "\"min_seconds\": 30, \"max_seconds\": 1")
        check(both != fixtureJson) { "치환이 빗나갔다" }

        val r = run(doc(both))
        r as CheckResult.Failed
        assertTrue(r.findings.size >= 2, "잘못이 둘인데 소견이 ${r.findings.size}개다")
    }
}
