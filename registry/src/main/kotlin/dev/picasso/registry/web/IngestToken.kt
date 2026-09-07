package dev.picasso.registry.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.servlet.HandlerInterceptor
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 적재 표면의 공유 토큰.
 *
 * ## 왜 진단과 다르게 다루는가
 *
 * §8.5의 승인 경계는 **조작**에 걸린다. 진단 여섯은 read-only라 그 경계
 * 밖이지만(§15.38), 적재는 원장에 쓰고 그 원장이 §9.3의 축소 판정을
 * 떠받친다 — **아무나 쓸 수 있으면 아무나 축소를 막거나 열 수 있다.**
 * §15.38이 *"조작 API가 붙는 순간 이것은 한계가 아니라 결함이 된다"*고
 * 적어 둔 그 순간이 여기다.
 *
 * ## 설정이 비면 열지 않는다
 *
 * **"설정을 안 하면 열려 있다"가 최악의 기본값이다.** 배포에서 토큰을
 * 빠뜨린 것과 토큰을 일부러 안 쓰는 것을 구별할 방법이 없고, 전자가
 * 압도적으로 흔하다. 그래서 빈 토큰은 전부 401이다 — 적재가 안 되면
 * 워터마크가 늙어 검사 6번이 `NotObservable`이 되고, 그것은 축소를 **막는**
 * 쪽이라 안전하게 실패한다.
 *
 * ## 상수 시간 비교
 *
 * `==`는 첫 다른 바이트에서 끊어 길이와 접두사를 흘린다. 토큰은 짧고
 * 재시도가 싸므로 실제로 캘 수 있다.
 */
class IngestToken(private val expected: String) {

    init {
        // **설정 실수를 기동에서 잡는다.** HTTP 헤더 값은 ASCII 밖을 싣지
        // 못하므로 비ASCII 토큰을 설정하면 **아무도 인증할 수 없는데 그
        // 사실이 401로만 나타난다** — 관문이 옳게 막는 것과 구별되지 않아
        // 원인을 찾는 데 오래 걸린다. 실측으로 물렸다.
        require(expected.all { it.code in 0x21..0x7E }) {
            "적재 토큰에 HTTP 헤더가 실을 수 없는 문자가 있다 " +
                "(ASCII 0x21~0x7E만 가능, 공백 불가)"
        }
    }

    fun matches(presented: String?): Boolean {
        if (expected.isEmpty()) return false
        val offered = presented?.removePrefix(BEARER)?.trim() ?: return false
        if (offered.isEmpty()) return false
        return MessageDigest.isEqual(
            offered.toByteArray(StandardCharsets.UTF_8),
            expected.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private companion object {
        const val BEARER = "Bearer "
    }
}

/**
 * 적재 경로(`/ingest` 이하)와 `/requirements`를 지키는 관문.
 *
 * 경로 패턴을 KDoc에 그대로 쓰지 않는 것은 **Kotlin 블록 주석이 중첩되기**
 * 때문이다 — 와일드카드 둘이 슬래시 뒤에 붙으면 그 자리에서 새 주석이
 * 열리고 파일 끝까지 주석이 된다. 실측으로 두 번 물렸다.
 *
 * **엔드포인트마다 검사하지 않고 경로로 잡는다.** 엔드포인트가 자기 토큰을
 * 확인하는 구조는 새 엔드포인트를 더할 때 잊으면 조용히 새는 문이 되고,
 * 그 문은 시험이 그 엔드포인트를 따로 겨냥하기 전까지 안 보인다.
 * [IngestPathsTest]가 등록된 쓰기 경로가 전부 이 관문 뒤에 있는지 본다.
 */
class IngestTokenInterceptor(private val token: IngestToken) : HandlerInterceptor {

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (token.matches(request.getHeader("Authorization"))) return true

        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = "application/json"
        // 왜 막혔는지는 말하되 **무엇이 맞는지는 말하지 않는다.**
        response.writer.write("""{"error":"적재에는 Bearer 토큰이 필요하다"}""")
        return false
    }

    companion object {
        /** 관문이 덮는 경로. 쓰기 표면을 더하면 여기에도 더해야 한다. */
        val GUARDED = listOf("/ingest/**", "/requirements")
    }
}
