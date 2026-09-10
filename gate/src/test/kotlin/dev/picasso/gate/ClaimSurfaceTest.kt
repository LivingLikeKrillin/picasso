package dev.picasso.gate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ClaimSurfaceTest {

    private val stampLine = "> 마지막 대조: 2026-09-10 · sha256:0123456789ab · 열림: §15.34, C-3"

    @Test
    fun `도장을 읽는다`() {
        val s = ClaimSurface.stampOf("머리말\n" + stampLine + "\n")!!
        assertEquals("2026-09-10", s.date)
        assertEquals("0123456789ab", s.sha)
        assertEquals(listOf("§15.34", "C-3"), s.openIds)
    }

    @Test
    fun `열림이 없으면 빈 목록이다`() {
        val s = ClaimSurface.stampOf("> 마지막 대조: 2026-09-10 · sha256:0123456789ab · 열림: 없음")!!
        assertEquals(emptyList(), s.openIds)
    }

    @Test
    fun `도장이 없으면 널이다`() = assertNull(ClaimSurface.stampOf("본문뿐"))

    @Test
    fun `펜스 안의 도장 예시는 도장이 아니다`() {
        // ★**이 시험이 이 계획의 함정을 막는다.** 도장 형식을 설명하는 문서는 예시를 품는다.
        // 스펙 자신이 그렇고, 이것을 놓치면 해시 검사가 첫날부터 빨개지고 도구가 그 예시를 지운다.
        val fenced = "머리말\n```\n" + stampLine + "\n```\n꼬리말"
        assertNull(ClaimSurface.stampOf(fenced))
        assertEquals(fenced, ClaimSurface.bodyOf(fenced))  // 본문에서 지워지지도 않는다
    }

    @Test
    fun `본문은 펜스 밖 도장 줄만 뺀다`() {
        assertEquals("가\n나", ClaimSurface.bodyOf("가\n" + stampLine + "\n나"))
    }

    @Test
    fun `본문은 일지 앞에서 끊긴다`() {
        // 설계 문서의 §15 는 일지라 주장이 아니다(스펙 §2). 일지를 고쳐도 도장이 안 빨개져야 한다.
        assertEquals("가", ClaimSurface.bodyOf("가\n## 15. 알려진 한계\n나\n다"))
    }

    @Test
    fun `펜스 안의 일지 제목은 경계가 아니다`() {
        val s = "가\n```\n## 15. 예시\n```\n나"
        assertEquals(s, ClaimSurface.bodyOf(s))
    }

    @Test
    fun `해시는 본문이 같으면 같고 다르면 다르다`() {
        assertEquals(ClaimSurface.shaOf("가\n나"), ClaimSurface.shaOf("가\n나"))
        assertNotEquals(ClaimSurface.shaOf("가\n나"), ClaimSurface.shaOf("가\n다"))
        assertEquals(12, ClaimSurface.shaOf("가").length)
    }

    @Test
    fun `줄바꿈이 달라도 해시가 같다`() {
        // 이 저장소는 Windows 에서 돌고 .gitattributes 가 CRLF 를 만든다. 그것으로 빨개지면 안 된다.
        assertEquals(ClaimSurface.shaOf("가\n나"), ClaimSurface.shaOf("가\r\n나"))
    }
}
