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
}
