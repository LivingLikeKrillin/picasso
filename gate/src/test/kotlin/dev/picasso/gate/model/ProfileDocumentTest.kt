package dev.picasso.gate.model

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileDocumentTest {

    private fun fixture(): ProfileDocument {
        // Gradle 테스트의 작업 디렉터리는 모듈 디렉터리(gate/)다.
        // 가정이 깨지면 아래 check가 경로를 찍고 죽으므로 조용히 통과하지 않는다.
        val p = Path.of("..", "profile", "fixtures", "minimal.json").normalize()
        check(Files.exists(p)) { "픽스처가 없다: ${p.toAbsolutePath()}" }
        return ProfileDocument.parse(p.toString(), Files.readString(p)).getOrThrow()
    }

    @Test
    fun `기종 좌표와 최상위 값을 읽는다`() {
        val d = fixture()
        assertEquals("fixture", d.vendor)
        assertEquals("minimal", d.model)
        assertEquals(1, d.revision)
        assertEquals(true, d.exclusiveControlRequired)
        assertEquals(256, d.replayBufferSize)
    }

    @Test
    fun `스킬과 파라미터를 읽는다`() {
        val d = fixture()
        assertEquals(setOf("pick_place", "navigate_to"), d.skills.map { it.skillType }.toSet())

        val pp = assertNotNull(d.skills.firstOrNull { it.skillType == "pick_place" })
        assertEquals(1, pp.major)
        assertEquals(2, pp.minor)
        assertEquals("YES", pp.pauseSupport)
        assertEquals("NO", pp.cancelSupport)
        assertNull(pp.deprecatedAfter)
        assertEquals(5, pp.parameters.size)
    }

    @Test
    fun `검사 6번이 축소 판정에 쓰는 제약을 전부 읽는다`() {
        // unit·allowed_values·max_length가 없으면 단위 변경·허용값 축소·
        // 길이 축소를 감지할 수 없다. 셋 다 §11.2 6번 규칙 1의 입력이다.
        val pp = assertNotNull(fixture().skills.firstOrNull { it.skillType == "pick_place" })
        val byKey = pp.parameters.associateBy { it.key }

        val grip = byKey.getValue("grip_force")
        assertEquals("NUMBER", grip.valueType)
        assertEquals(0.0, grip.minValue)
        assertEquals(120.0, grip.maxValue)
        assertEquals("N", grip.unit)

        val objectId = byKey.getValue("object_id")
        assertEquals(64, objectId.maxLength)
        assertNull(objectId.unit)
        assertTrue(objectId.allowedValues.isEmpty())
    }

    @Test
    fun `필수 선택 필드를 읽는다`() {
        // SUPPORTED → REQUIRED 변경은 축소다. 감지하려면 노출되어야 한다.
        val d = fixture()
        assertEquals(2, d.optionalFields.size)
        val byPath = d.optionalFields.associateBy { it.parameterPath }
        assertEquals("SUPPORTED", byPath.getValue("task.parameters.grip_force").support)
        assertEquals("REQUIRED", byPath.getValue("task.parameters.verify_grasp").support)
    }

    @Test
    fun `실패 모드를 읽는다`() {
        val d = fixture()
        assertEquals(4, d.failureModes.size)
        assertTrue(d.failureModes.any { it.errorType == "X_FIXTURE_SIMULATED_HARDWARE_FAULT" })
        assertTrue(d.failureModes.any { it.resolution == "NEEDS_INTERVENTION" })
        assertTrue(d.failureModes.any { it.skillType == null })
    }

    @Test
    fun `발행 간격과 프로토콜 한계를 읽는다`() {
        val d = fixture()
        assertEquals(1, d.publishIntervalMinSeconds)
        assertEquals(30, d.publishIntervalMaxSeconds)
        assertEquals(256, d.maxStringLength)
        assertEquals(32, d.maxArrayLength)
    }

    @Test
    fun `원문을 그대로 들고 있다`() {
        // 검사 3번의 스키마 검증이 원문을 쓴다.
        assertTrue(fixture().raw.contains("\"schema_version\""))
    }

    @Test
    fun `깨진 JSON은 던지지 않고 실패를 돌려준다`() {
        // 던지면 GateRunner의 예외 포획 밖이라 게이트가 소견 없이 죽는다.
        val r = ProfileDocument.parse("broken.json", "{ this is not json")
        assertTrue(r.isFailure)
    }

    @Test
    fun `중복 멤버 이름을 거절한다`() {
        // Jackson 기본 설정은 조용히 마지막 값으로 접는다. 스키마 검증기도
        // 이미 파싱된 트리를 보므로 못 잡는다 — 두 검증기 어느 쪽도 못 잡는
        // 우회로다. 아래 문서는 min_value 10을 버리고 1을 남겨
        // 검사 3번의 min ≤ max 규칙을 통과해 버린다.
        val json = """
            {"vendor":"v","model":"m",
             "skills":[{"skill_type":"s","major":1,"minor":0,
               "pause_support":"YES","cancel_support":"YES",
               "parameters":[{"key":"k","value_type":"NUMBER","optional":true,
                              "min_value":10,"min_value":1,"max_value":5}]}]}
        """.trimIndent()
        assertTrue(ProfileDocument.parse("dup.json", json).isFailure)
    }

    @Test
    fun `객체가 아닌 루트를 거절한다`() {
        // 빈 파일·배열·스칼라가 전부 readTree를 통과한다. 막지 않으면
        // 0바이트 프로파일이 "스킬 0개인 정상 문서"가 되어 검사 4·6번이
        // 대조할 게 없다며 PASS를 낸다.
        listOf("", "   ", "[]", "123", "\"hi\"", "null").forEach { json ->
            assertTrue(
                ProfileDocument.parse("x.json", json).isFailure,
                "객체가 아닌 루트를 받아들였다: '$json'",
            )
        }
    }

    @Test
    fun `명시적 null을 값으로 접지 않는다`() {
        // unit: null이 "null" 문자열이 되면 검사 6번의 단위 변경 판정이
        // 거짓 양성을 낸다.
        val json = """
            {"vendor":"v","model":"m",
             "skills":[{"skill_type":"s","major":1,"minor":0,
               "pause_support":"YES","cancel_support":"YES",
               "parameters":[{"key":"k","value_type":"NUMBER","optional":true,
                              "unit":null,"min_value":null,"max_length":null}]}]}
        """.trimIndent()
        val p = ProfileDocument.parse("n.json", json).getOrThrow()
            .skills.single().parameters.single()
        assertNull(p.unit)
        assertNull(p.minValue)
        assertNull(p.maxLength)
    }

    @Test
    fun `중복 선언을 접지 않고 List로 보존한다`() {
        // 검사 3번의 규칙 둘((skill_type,major) 중복, 스킬 내 key 중복)이
        // 이 보존에 전적으로 기댄다. Map으로 접는 리팩터링이 들어오면
        // 두 규칙이 그 순간 침묵하므로 여기서 못을 박는다.
        val json = """
            {"vendor":"v","model":"m",
             "skills":[
               {"skill_type":"s","major":1,"minor":0,
                "pause_support":"YES","cancel_support":"YES",
                "parameters":[{"key":"a","value_type":"BOOL","optional":true},
                              {"key":"a","value_type":"STRING","optional":true}]},
               {"skill_type":"s","major":1,"minor":2,
                "pause_support":"YES","cancel_support":"YES","parameters":[]}]}
        """.trimIndent()
        val d = ProfileDocument.parse("d.json", json).getOrThrow()
        assertEquals(2, d.skills.size, "같은 (skill_type, major)가 접혔다")
        assertEquals(listOf("a", "a"), d.skills.first().parameters.map { it.key })
    }

    @Test
    fun `투영은 비투영 필드를 뺀다`() {
        // §5.2의 버전 규칙은 투영 필드에만 걸린다. 통째로 diff하면
        // durations의 소요시간 조정에 major 증가를 요구하게 된다.
        val projection = fixture().projection()
        ProfileDocument.NON_PROJECTION.forEach {
            assertTrue(projection.get(it) == null, "비투영 필드가 남았다: $it")
        }
        assertTrue(projection.get("skills") != null, "투영 필드가 사라졌다: skills")
        // 원본은 그대로여야 한다 — 검사 3번이 원문을 쓴다.
        assertEquals(4, fixture().failureModes.size)
    }
}
