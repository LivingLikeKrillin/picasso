package dev.picasso.registry.web

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 관문의 판정. **서버 없이 본다** — 배선은 [IngestEndpointTest]가 보고,
 * 여기서는 "무엇을 통과시키는가"만 본다.
 */
class IngestTokenTest {

    @Test
    fun `설정이 비면 무엇도 통과하지 못한다`() {
        // **"설정을 안 하면 열려 있다"가 최악의 기본값이다.** 배포에서
        // 토큰을 빠뜨린 것과 일부러 안 쓰는 것을 구별할 방법이 없다.
        val open = IngestToken("")

        assertTrue(!open.matches("Bearer anything"))
        assertTrue(!open.matches(""))
        assertTrue(!open.matches(null))
    }

    @Test
    fun `맞는 토큰은 통과한다`() {
        // 이것이 없으면 **언제나 막는 관문**이 나머지 시험을 전부 통과한다.
        assertTrue(IngestToken("s3cret").matches("Bearer s3cret"))
    }

    @Test
    fun `Bearer 없이 값만 줘도 통과한다`() {
        // 보고자가 여럿이고 그중 하나가 접두사를 안 붙이는 것은 흔하다.
        // 접두사를 강제하려면 그 사실이 401 하나로만 전달되는데, 그것은
        // 토큰이 틀린 것과 구별되지 않는다.
        assertTrue(IngestToken("s3cret").matches("s3cret"))
    }

    @Test
    fun `다른 토큰은 막는다`() {
        val token = IngestToken("s3cret")

        assertTrue(!token.matches("Bearer s3cre"), "접두사가 통과하면 한 글자씩 캘 수 있다")
        assertTrue(!token.matches("Bearer s3crets"), "더 긴 것도 막는다")
        assertTrue(!token.matches("Bearer "), "빈 값")
        assertTrue(!token.matches(null))
    }

    @Test
    fun `HTTP 헤더가 실을 수 없는 토큰은 기동에서 막는다`() {
        // 비ASCII 토큰을 설정하면 **아무도 인증할 수 없는데 그 사실이 401로만
        // 나타나** 관문이 옳게 막는 것과 구별되지 않는다. 실측으로 물렸다.
        assertFailsWith<IllegalArgumentException> { IngestToken("시험용-토큰") }
        assertFailsWith<IllegalArgumentException> { IngestToken("has space") }
        assertFailsWith<IllegalArgumentException> { IngestToken("tab\there") }
    }
}
