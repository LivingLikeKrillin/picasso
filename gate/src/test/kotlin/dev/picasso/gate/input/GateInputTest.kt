package dev.picasso.gate.input

import dev.picasso.gate.Resource
import dev.picasso.gate.model.ProfileDocument
import kotlin.test.Test
import kotlin.test.assertEquals

class GateInputTest {

    private fun doc() = ProfileDocument
        .parse("x.json", """{"vendor":"v","model":"m","revision":1}""")
        .getOrThrow()

    @Test
    fun `빈 입력은 아무 자원도 갖지 않는다`() {
        assertEquals(emptySet(), GateInput().available())
    }

    @Test
    fun `문서와 스키마는 서로 다른 자원이다`() {
        // 문서를 넘겼는데 스키마가 없다고 전부 건너뛰면
        // 이 프레임이 막으려는 조용한 무력화가 그대로 일어난다.
        assertEquals(
            setOf(Resource.PROFILE_DOCUMENT),
            GateInput(profiles = listOf(doc())).available(),
        )
        assertEquals(
            setOf(Resource.PROFILE_SCHEMA),
            GateInput(schemaJson = "{}").available(),
        )
    }

    @Test
    fun `깨진 문서도 자원으로 친다`() {
        // 검사 3번이 소견으로 보고해야 하므로 "문서가 있다"로 세야 한다.
        assertEquals(
            setOf(Resource.PROFILE_DOCUMENT),
            GateInput(malformed = listOf(MalformedProfile("bad.json", "깨짐"))).available(),
        )
    }

    @Test
    fun `기준선은 파일 경로가 아니라 기종 좌표로 키잉한다`() {
        // 경로로 키잉하면 파일 이름을 바꾸면서 스킬을 지우는 PR이
        // "기준선이 없으니 신규"로 분류되어 파괴 검사를 통째로 지나간다.
        // §8.3은 문서의 동일성을 (vendor, model)로 규정한다.
        val json = """{"vendor":"acme","model":"r1"}"""
        val before = ProfileDocument.parse("acme-r1.json", json).getOrThrow()
        val renamed = ProfileDocument.parse("acme-r1-v2.json", json).getOrThrow()

        assertEquals(
            ProfileKey(before.vendor, before.model),
            ProfileKey(renamed.vendor, renamed.model),
            "이름만 바꾼 같은 기종이 다른 키가 됐다",
        )
    }

    @Test
    fun `기준선의 null과 빈 맵은 뜻이 다르다`() {
        // null = 출처에 못 닿음(건너뜀), 빈 맵 = 신규(통과).
        assertEquals(emptySet(), GateInput(baseline = null).available())
        assertEquals(setOf(Resource.BASELINE), GateInput(baseline = emptyMap()).available())
    }

    @Test
    fun `채워진 것만 가용 자원으로 센다`() {
        val input = GateInput(
            profiles = listOf(doc()),
            schemaJson = "{}",
            descriptor = byteArrayOf(1, 2, 3),
        )
        assertEquals(
            setOf(Resource.PROFILE_DOCUMENT, Resource.PROFILE_SCHEMA, Resource.CONTRACT_DESCRIPTOR),
            input.available(),
        )
    }
}
