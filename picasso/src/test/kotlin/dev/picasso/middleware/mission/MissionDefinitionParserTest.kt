package dev.picasso.middleware.mission

import dev.picasso.middleware.Evidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 임무 정의의 엄격한 읽기 — 모르는 키·빠진 칸·형이 틀린 값을 **모두 모아 한 번에** 알린다.
 *
 * 값의 판정(기한이 양수인가, 기한 뒤 상태가 허용된 값인가)은 여기서 안 본다 — 검증기 시험이 본다. 파서가 그것을
 * 거부하면 검증기의 기한 검사를 빼는 결함이 파서에 가려진다.
 */
class MissionDefinitionParserTest {

    private fun problems(text: String): List<String> =
        assertIs<MissionParse.Unreadable>(MissionDefinitionParser.parse(text)).problems

    @Test
    fun `코드 케이퍼빌리티를 옮긴 문서를 그대로 읽는다`() {
        val definition = MissionFixtures.parsed(MissionFixtures.withArrivalWait())

        assertEquals(1, definition.schemaVersion)
        assertEquals("PrepareSequencedRack", definition.workMasterId)
        assertEquals(Evidence.E2, definition.maxEvidence)
        assertEquals(mapOf("verify_grasp" to "true"), definition.preferredOptionals)
        assertEquals(listOf("rack-arrival", "place"), definition.steps.map { it.id })

        val wait = assertIs<WaitStep>(definition.steps[0])
        assertEquals(WaitStep("rack-arrival", "rack_present", "true", 120, "OPERATOR_HOLD"), wait)

        val unit = assertIs<UnitStep>(definition.steps[1])
        assertEquals(Pairing("source", "material"), unit.pairWith)
        assertEquals("NO_SOURCE_FOR_MATERIAL", unit.whenUnpaired)
        assertEquals(ValueSource(ValueFrom.PAIRED_ID, otherwise = ""), unit.parameters["object_id"])
        assertEquals(ValueSource(ValueFrom.ITEM_PROPERTY, property = "material"), unit.expectedIdentity)
    }

    @Test
    fun `모르는 키와 빠진 칸과 형이 틀린 값을 한 번에 모은다`() {
        val text = """
            {
              "schemaVersion": 1.5,
              "workMasterId": "PrepareSequencedRack",
              "maxEvidence": "E3",
              "preferredOptionals": { "verify_grasp": true },
              "colour": "red",
              "steps": [
                { "kind": "wait", "id": "w", "signal": "rack_present", "expect": "true",
                  "deadlineSeconds": "soon", "onDeadline": 7, "retry": 2 },
                { "kind": "unit", "id": "u", "skill": "pick_place",
                  "unitId": { "from": "ITEM_ID" }, "parameters": {} },
                { "kind": "jump", "id": "j" }
              ]
            }
        """.trimIndent()

        val found = problems(text)

        val expected = listOf(
            "$.schemaVersion: 정수여야",
            "$.maxEvidence: E0·E1·E2 중 하나",
            "$.preferredOptionals.verify_grasp: 문자열이어야",
            "$: 모르는 키 'colour'",
            "$.steps[0]: 모르는 키 'retry'",
            "$.steps[0].deadlineSeconds: 정수여야",
            "$.steps[0].onDeadline: 문자열이어야",
            "$.steps[1].forEach: 빠졌다",
            "$.steps[2].kind: unit 이나 wait 여야",
        )
        expected.forEach { needle ->
            assertTrue(found.any { it.startsWith(needle) }, "'$needle' 이 안 모였다: $found")
        }
        assertEquals(expected.size, found.size, "모은 것이 기대와 다르다: $found")
    }

    @Test
    fun `기한의 값은 판정하지 않는다 — 없거나 음수거나 허용 밖이어도 읽는다`() {
        // ★검증기의 기한 검사가 이 자리를 맡는다. 여기서 거부하면 그 검사를 빼는 결함이 안 보인다.
        val text = MissionFixtures.withArrivalWait(deadlineSeconds = -5)
            .replace("\"onDeadline\": \"OPERATOR_HOLD\"", "\"onDeadline\": \"RETRY\"")
        val wait = assertIs<WaitStep>(MissionFixtures.parsed(text).steps[0])
        assertEquals(-5, wait.deadlineSeconds)
        assertEquals("RETRY", wait.onDeadline)

        val bare = MissionFixtures.withArrivalWait()
            .replace(", \"deadlineSeconds\": 120", "")
            .replace(", \"onDeadline\": \"OPERATOR_HOLD\"", "")
        val missing = assertIs<WaitStep>(MissionFixtures.parsed(bare).steps[0])
        assertNull(missing.deadlineSeconds)
        assertNull(missing.onDeadline)
    }

    @Test
    fun `같은 키가 둘이면 거부한다 — 어느 쪽을 뜻했는지 모른다`() {
        // `JsonFormat` 은 말없이 뒤엣것을 쓴다. 그러면 앞의 값이 조용히 사라진다.
        val text = MissionFixtures.withArrivalWait()
            .replace("\"deadlineSeconds\": 120", "\"deadlineSeconds\": 120, \"deadlineSeconds\": 5")
        assertEquals(listOf("$.steps[0]: 키 'deadlineSeconds' 가 두 번 나온다"), problems(text))
    }

    @Test
    fun `JSON 이 아닌 것과 뒤에 붙은 글자를 거부한다`() {
        assertTrue(problems("{ steps: [] }").single().startsWith("$: 키는 큰따옴표 문자열이어야 한다"))
        assertTrue(problems(MissionFixtures.PREPARE_SEQUENCED_RACK + " x").single().startsWith("$: JSON 값 뒤에 글자가 더 있다"))
        assertTrue(problems("[1, 2]").single().startsWith("$: JSON 객체가 아니다"))
    }

    @Test
    fun `짝 규칙과 실패 분류는 함께 오고 짝 없이 짝의 값을 못 쓴다`() {
        val noClass = MissionFixtures.PREPARE_SEQUENCED_RACK.replace("\"whenUnpaired\": \"NO_SOURCE_FOR_MATERIAL\",", "")
        assertTrue(problems(noClass).single().startsWith("$.steps[0]: pairWith 와 whenUnpaired 는 함께 온다"))

        // 짝 규칙을 통째로 빼면 짝의 값을 쓰는 두 칸(object_id 파라미터, source)이 각각 걸린다.
        val noPairing = noClass.replace("\"pairWith\": { \"equipmentUse\": \"source\", \"property\": \"material\" },", "")
        val found = problems(noPairing)
        assertEquals(2, found.count { "PAIRED_ID 인데 이 노드에 pairWith 가 없다" in it }, "$found")
        assertEquals(2, found.size, "$found")
    }

    @Test
    fun `단위 id 는 반복 중인 설비의 id 만 받는다`() {
        val text = MissionFixtures.PREPARE_SEQUENCED_RACK
            .replace("\"unitId\": { \"from\": \"ITEM_ID\" }", "\"unitId\": { \"from\": \"ITEM_PROPERTY\", \"property\": \"material\" }")
        assertTrue(problems(text).single().startsWith("$.steps[0].unitId.from: ITEM_ID 여야 한다"))
    }

    @Test
    fun `값 출처의 속성은 속성 출처에만 오고 모르는 출처는 거부한다`() {
        val stray = MissionFixtures.PREPARE_SEQUENCED_RACK
            .replace("\"destination\": { \"from\": \"ITEM_ID\" }\n}", "\"destination\": { \"from\": \"ITEM_ID\", \"property\": \"x\" }\n}")
        assertTrue(problems(stray).single().startsWith("$.steps[0].destination.property: ITEM_PROPERTY 에만 온다"))

        val unknown = MissionFixtures.PREPARE_SEQUENCED_RACK.replace("\"from\": \"PAIRED_ID\", \"otherwise\"", "\"from\": \"ORDER\", \"otherwise\"")
        assertTrue(problems(unknown).single().startsWith("$.steps[0].parameters.object_id.from: ITEM_ID·ITEM_PROPERTY·PAIRED_ID 중 하나"))
    }

    @Test
    fun `모르는 문서 버전과 빈 노드 목록을 거부한다`() {
        val v2 = MissionFixtures.PREPARE_SEQUENCED_RACK.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
        assertTrue(problems(v2).single().startsWith("$.schemaVersion: 모르는 문서 버전이다"))

        val empty = """{"schemaVersion":1,"workMasterId":"W","maxEvidence":"E0","preferredOptionals":{},"steps":[]}"""
        assertEquals(listOf("$.steps: 노드가 하나도 없다"), problems(empty))
    }
}
