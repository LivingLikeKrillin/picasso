package dev.picasso.registry.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.servlet.HandlerInterceptor

/**
 * 운영자 조작 표면의 공유 토큰.
 *
 * ## 왜 적재 토큰을 그대로 쓰지 않는가
 *
 * **적재 토큰은 모든 기체가 들고 있다.** 어댑터마다 배포되고 현장에 나가
 * 있으며, 그것을 가진 쪽이 곧 로봇이다. 그 토큰으로 §8.5의 조작까지 되면
 * **현장의 기체 하나가 운영자 조작을 수행할 수 있다** — 사이트 이름을
 * 등록했다고 스스로 적는 것이 그 첫 예다.
 *
 * 둘은 신뢰 경계가 다르다. 적재는 *"기체가 자기 관측을 보고한다"* 이고 조작은
 * *"사람이 판단을 기록한다"* 다. 같은 문으로 들이면 앞의 것을 가진 쪽이 뒤의
 * 것을 할 수 있고, 그 승격은 코드 어디에도 안 적혀 있어 안 보인다.
 *
 * ## 이것은 인증이 아니다
 *
 * §6.3이 신원과 접근을 이번 범위 밖으로 두었다. 이 관문은 **역할 둘을
 * 가르는 거친 문**이지 신원이 아니다 — 누가 조작했는지는 여전히 요청 헤더의
 * 자기 신고이고 위조 가능하다(§15.3). 그래서 감사 로그는 부인방지 근거가
 * 아니라 조사 단서다.
 *
 * 나머지 성질은 [IngestToken]과 같다 — **빈 토큰은 전부 401**(설정을 빠뜨린
 * 것과 일부러 안 쓰는 것을 구별할 수 없고 전자가 흔하다), 상수 시간 비교,
 * 기동 시 ASCII 검사.
 */
class OperatorToken(expected: String) : BearerToken(expected, "운영")

/**
 * 조작 경로를 지키는 관문.
 *
 * [IngestTokenInterceptor]와 같은 이유로 **엔드포인트마다 검사하지 않고
 * 경로로 잡는다** — 엔드포인트가 자기 토큰을 확인하는 구조는 새 것을 더할 때
 * 잊으면 조용히 새는 문이 되고, 그 문은 시험이 그것을 따로 겨냥하기 전까지
 * 안 보인다.
 */
class OperatorTokenInterceptor(private val token: OperatorToken) : HandlerInterceptor {

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (token.matches(request.getHeader("Authorization"))) return true

        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = "application/json"
        // 왜 막혔는지는 말하되 **무엇이 맞는지는 말하지 않는다.**
        response.writer.write("""{"error":"조작에는 운영 Bearer 토큰이 필요하다"}""")
        return false
    }

    companion object {
        /** 관문이 덮는 경로. §8.5의 조작을 더하면 이 아래로 들어와야 한다. */
        val GUARDED = listOf("/operations/**")
    }
}
