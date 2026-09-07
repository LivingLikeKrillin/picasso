package dev.picasso.registry

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 바인딩 합법성의 한쪽 입력(§9.1).
 *
 * **파싱 실패를 조용히 0으로 접으면 안 된다.** 접으면 `x.y.z`가 아닌 값이
 * 언제나 가장 낮은 것으로 취급되고, 그러면 **아무 조합이나 통과한다** —
 * 실측으로 그 주입이 안 잡혔다. 여기가 그것을 잡는다.
 */
class SemverTest {

    @Test
    fun `형식이 아니면 던진다`() {
        // 조용히 접으면 어댑터가 모르는 스킬로 태스크를 걸 수 있게 된다.
        listOf("", "1.0", "1.0.0-rc1", "v1.0.0", "1.0.0+build", "abc", "1.0.0.0").forEach {
            assertFailsWith<IllegalStateException>(message = "'$it'을 받아들였다") {
                Semver.parse(it)
            }
        }
    }

    @Test
    fun `세 자리를 순서대로 비교한다`() {
        assertTrue(Semver.parse("0.3.0") > Semver.parse("0.2.9"))
        assertTrue(Semver.parse("1.0.0") > Semver.parse("0.99.99"))
        assertTrue(Semver.parse("0.3.1") > Semver.parse("0.3.0"))
        assertTrue(Semver.parse("0.3.0") == Semver.parse("0.3.0"))
    }

    @Test
    fun `문자열 비교가 아니다`() {
        // "10" < "9" 가 되는 그 함정. 숫자로 비교해야 한다.
        assertTrue(Semver.parse("0.10.0") > Semver.parse("0.9.0"))
        assertTrue(Semver.parse("10.0.0") > Semver.parse("9.0.0"))
    }

    @Test
    fun `앞뒤 공백은 봐준다`() {
        // DB에서 온 값에 공백이 섞이는 것은 흔하고, 그것 때문에 멀쩡한
        // 바인딩이 막히면 원인을 찾기 어렵다.
        assertTrue(Semver.parse(" 0.3.0 ") == Semver.parse("0.3.0"))
    }
}
