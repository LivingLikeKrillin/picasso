package dev.picasso.mimic.profile

import dev.picasso.profile.projection.CapabilityProjection
import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.OptionalFieldSupport
import dev.picasso.contracts.v1.Support
import dev.picasso.contracts.v1.ValueType
import dev.picasso.profile.ProfileDocument
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CapabilityProjectionTest {

    private val raw: String by lazy {
        val p = Path.of("..", "profile", "fixtures", "minimal.json").normalize()
        // CRLF 체크아웃이면 치환이 빗나간다(1단계 실측).
        Files.readString(p).replace("\r\n", "\n")
    }

    private val document: ProfileDocument by lazy {
        ProfileDocument.parse("minimal.json", raw).getOrThrow()
    }

    private val capability: Capability by lazy { CapabilityProjection.of(document) }

    // ── §7.2 "투영에 들어간다" 표의 행마다 하나씩

    @Test
    fun `기종 좌표`() {
        assertEquals("fixture", capability.vendor)
        assertEquals("minimal", capability.model)
        assertEquals(1, capability.profileRevision)
    }

    @Test
    fun `지원 스킬과 major minor`() {
        val byType = capability.skillsList.associateBy { it.skillType }
        assertEquals(setOf("pick_place", "navigate_to"), byType.keys)
        assertEquals(1, byType.getValue("pick_place").major)
        assertEquals(2, byType.getValue("pick_place").minor)
    }

    @Test
    fun `스킬별 플래그는 Support 3값이고 둘 다 필수다`() {
        val pp = capability.skillsList.single { it.skillType == "pick_place" }
        assertEquals(Support.SUPPORT_YES, pp.pauseSupport)
        assertEquals(Support.SUPPORT_NO, pp.cancelSupport)

        val nav = capability.skillsList.single { it.skillType == "navigate_to" }
        assertEquals(Support.SUPPORT_UNKNOWN, nav.pauseSupport)
        assertEquals(Support.SUPPORT_YES, nav.cancelSupport)
    }

    @Test
    fun `제어 소유권`() {
        assertEquals(true, capability.exclusiveControlRequired)
    }

    @Test
    fun `파라미터 선언과 제약`() {
        val pp = capability.skillsList.single { it.skillType == "pick_place" }
        val byKey = pp.parametersList.associateBy { it.key }

        val grip = byKey.getValue("grip_force")
        assertEquals(ValueType.VALUE_TYPE_NUMBER, grip.valueType)
        assertEquals(true, grip.optional)
        assertEquals(0.0, grip.minValue)
        assertEquals(120.0, grip.maxValue)
        assertEquals("N", grip.unit)

        val objectId = byKey.getValue("object_id")
        assertEquals(ValueType.VALUE_TYPE_STRING, objectId.valueType)
        assertEquals(false, objectId.optional)
        assertEquals(64, objectId.maxLength)
    }

    @Test
    fun `필수 선택 필드`() {
        val byPath = capability.optionalFieldsList.associateBy { it.parameterPath }
        assertEquals(2, byPath.size)
        assertEquals(
            OptionalFieldSupport.OPTIONAL_FIELD_SUPPORT_REQUIRED,
            byPath.getValue("task.parameters.verify_grasp").support,
        )
        assertEquals(
            OptionalFieldSupport.OPTIONAL_FIELD_SUPPORT_SUPPORTED,
            byPath.getValue("task.parameters.grip_force").support,
        )
    }

    @Test
    fun `상태 발행 간격`() {
        assertEquals(1, capability.publishInterval.minSeconds)
        assertEquals(30, capability.publishInterval.maxSeconds)
    }

    @Test
    fun `프로토콜 한계`() {
        assertEquals(256, capability.protocolLimits.maxStringLength)
        assertEquals(32, capability.protocolLimits.maxArrayLength)
    }

    @Test
    fun `폐기 예고는 선택이고 없으면 비어 있다`() {
        assertTrue(capability.skillsList.all { it.deprecatedAfter.isEmpty() })
    }

    @Test
    fun `ENUM 파라미터의 허용 값 목록`() {
        // 허용 값이 없으면 송신 직전 검사가 ENUM에 아무것도 강제하지 못한다(§7.2).
        // 픽스처에 ENUM 파라미터가 하나도 없어 인라인 프로파일로 본다 —
        // 픽스처에 넣으면 게이트 검사 4번이 계약 카탈로그에 없는 키라며 막는다.
        val enumProfile = ProfileDocument.parse("enum.json", INLINE_ENUM_PROFILE).getOrThrow()
        val mode = CapabilityProjection.of(enumProfile)
            .skillsList.single().parametersList.single { it.key == "grasp_mode" }

        assertEquals(ValueType.VALUE_TYPE_ENUM, mode.valueType)
        assertEquals(listOf("PINCH", "POWER"), mode.allowedValuesList)
    }

    // ── 하드코딩을 죽이는 셋
    //
    // 픽스처 한 장에 그 픽스처의 값을 리터럴로 적어 비교하면 "파생했다"와
    // "그 값을 상수로 적었다"가 구분되지 않는다. 리뷰가 프로파일 인자를
    // 통째로 무시하는 구현으로 위 시험들을 전부 통과시켰다.

    @Test
    fun `프로파일을 고치면 투영이 따라 바뀐다`() {
        // 기대값을 리터럴로 쓰지 않으므로 "시험이 곧 두 번째 구현"이 되지도 않는다.
        val mutations = mapOf(
            "\"vendor\": \"fixture\"" to "\"vendor\": \"other\"",
            "\"revision\": 1" to "\"revision\": 7",
            "\"max_string_length\": 256" to "\"max_string_length\": 512",
            "\"max_array_length\": 32" to "\"max_array_length\": 64",
            "\"exclusive_control_required\": true" to "\"exclusive_control_required\": false",
            "\"pause_support\": \"YES\"" to "\"pause_support\": \"UNKNOWN\"",
            "\"cancel_support\": \"NO\"" to "\"cancel_support\": \"UNKNOWN\"",
            "\"max_value\": 120" to "\"max_value\": 60",
            "\"unit\": \"N\"" to "\"unit\": \"kgf\"",
            "\"max_length\": 64" to "\"max_length\": 32",
            "\"min_seconds\": 1" to "\"min_seconds\": 2",
            "\"max_seconds\": 30" to "\"max_seconds\": 60",
            "\"support\": \"REQUIRED\"" to "\"support\": \"SUPPORTED\"",
            "\"minor\": 2" to "\"minor\": 1",
        )

        mutations.forEach { (from, to) ->
            require(from in raw) { "픽스처가 바뀌어 변형이 빗나갔다: $from" }
            val mutated = ProfileDocument.parse("mut.json", raw.replace(from, to)).getOrThrow()
            assertNotEquals(
                capability,
                CapabilityProjection.of(mutated),
                "프로파일의 '$from'를 고쳤는데 투영이 그대로다 — 이 값은 파생되지 않았다",
            )
        }
    }

    @Test
    fun `스킬과 파라미터 집합이 프로파일과 정확히 일치한다`() {
        // 빠뜨림이 침묵하지 않게. 리뷰의 하드코딩은 세 파라미터를 통째로
        // 빠뜨렸는데도 아무 시험이 말하지 않았다.
        assertEquals(document.skills.size, capability.skillsCount)
        assertEquals(document.optionalFields.size, capability.optionalFieldsCount)

        document.skills.forEach { s ->
            val projected = capability.skillsList.single { it.skillType == s.skillType }
            assertEquals(
                s.parameters.map { it.key },
                projected.parametersList.map { it.key },
                "${s.skillType}의 파라미터 집합이 프로파일과 다르다",
            )
        }
    }

    @Test
    fun `선언하지 않은 제약은 존재하지 않는다`() {
        // BOOL 파라미터에는 수치 제약이 없다. 0으로 접히면 소비자가
        // "0 이하만 허용"으로 읽는다. 계약이 명시적 존재인 이유다.
        val byKey = capability.skillsList.single { it.skillType == "pick_place" }
            .parametersList.associateBy { it.key }

        val verify = byKey.getValue("verify_grasp")
        assertFalse(verify.hasMinValue())
        assertFalse(verify.hasMaxValue())
        assertFalse(verify.hasMaxLength())
        assertFalse(verify.hasUnit())

        // 반대로 0을 선언한 것은 존재한다.
        assertTrue(byKey.getValue("grip_force").hasMinValue())
        assertEquals(0.0, byKey.getValue("grip_force").minValue)

        // 문자열에는 수치 제약이 없다.
        assertFalse(byKey.getValue("object_id").hasMinValue())
        assertTrue(byKey.getValue("object_id").hasMaxLength())
    }

    // ── §7.2 "투영에 들어가지 않는다"

    @Test
    fun `Capability에 비투영 항목의 자리가 없다`() {
        // 자리가 없으면 하드코딩할 수도 없다. §7.2의 "투영에 들어가지 않는다"
        // 넷(schema_version·소요시간·실패 모드·재생 버퍼)이 계약에 새로
        // 생기면 이 시험이 말한다.
        assertEquals(
            setOf(
                "vendor", "model", "profile_revision", "skills",
                "optional_fields", "protocol_limits", "publish_interval",
                "exclusive_control_required",
            ),
            Capability.getDescriptor().fields.map { it.name }.toSet(),
        )
    }

    @Test
    fun `NON_PROJECTION 집합을 게이트와 공유한다`() {
        // 같은 상수를 게이트 6번과 이 투영이 함께 쓴다. 한쪽만 고치면 어긋난다.
        assertEquals(
            setOf(
                "schema_version", "durations", "failure_modes", "replay_buffer_size",
                "derived_from",
            ),
            ProfileDocument.NON_PROJECTION,
        )
    }

    private companion object {
        val INLINE_ENUM_PROFILE = """
            {
              "schema_version": "1.0.0", "vendor": "v", "model": "m", "revision": 1,
              "skills": [
                { "skill_type": "pick_place", "major": 1, "minor": 0,
                  "pause_support": "YES", "cancel_support": "YES",
                  "parameters": [
                    { "key": "grasp_mode", "value_type": "ENUM", "optional": true,
                      "allowed_values": ["PINCH", "POWER"] }
                  ] }
              ],
              "publish_interval": { "min_seconds": 1, "max_seconds": 30 },
              "protocol_limits": { "max_string_length": 256, "max_array_length": 32 },
              "exclusive_control_required": false, "replay_buffer_size": 16
            }
        """.trimIndent()
    }
}
