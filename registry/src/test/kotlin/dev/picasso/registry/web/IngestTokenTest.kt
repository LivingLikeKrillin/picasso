package dev.picasso.registry.web

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun `접두사만 맞아도 막는다`() {
        // **주입이 이것을 못 잡았다.** `startsWith` 로 바꿔도 아무 시험이
        // 안 빨개졌다 — 맞는 토큰과 완전히 틀린 토큰만 봤기 때문이다.
        // 상수 시간 비교를 두는 이유가 정확히 이 공격이며, 그것을 지키는
        // 시험이 없으면 비교를 바꿔도 조용하다.
        val token = IngestToken("operator-secret")
        assertFalse(token.matches("Bearer operator"), "접두사가 통과했다")
        assertFalse(token.matches("Bearer operator-secret-and-more"), "접미사가 통과했다")
        assertTrue(token.matches("Bearer operator-secret"))
    }

    @Test
    fun `조작 토큰도 같은 것을 지킨다`() {
        // **둘이 한 벌이 됐다는 것을 붙든다.**
        //
        // 두 벌이던 시절에는 `OperatorToken` 의 빈 토큰 처리와 상수 시간
        // 비교를 망가뜨려도 **아무 시험도 안 빨개졌다** — 단위 시험이 한쪽에만
        // 있었기 때문이다. 주입이 그것을 드러냈고 공용 [BearerToken] 으로 뽑았다.
        //
        // **하위 클래스가 갈라지는 것은 이제 구조가 막는다** — `matches` 가
        // `open` 이 아니라 재정의할 수 없다. 그래서 이 시험이 지키는 것은
        // "갈라짐을 잡는다" 가 아니라 **"조작 토큰이 정말 그 구현을 지난다"**
        // 이다. 상속을 끊고 다른 타입으로 바꾸면 여기서 빨개진다.
        val operator = OperatorToken("operator-secret")
        assertFalse(OperatorToken("").matches("Bearer anything"), "빈 토큰이 통과했다")
        assertFalse(operator.matches("Bearer operator"), "접두사가 통과했다")
        assertTrue(operator.matches("Bearer operator-secret"))
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
