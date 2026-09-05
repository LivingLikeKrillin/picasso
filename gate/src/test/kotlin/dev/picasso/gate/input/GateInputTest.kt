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
