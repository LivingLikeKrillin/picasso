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

    private val ledgerSample = """
        ## 1. 의도적 밖
        | 출처 | 무엇 | 왜 |
        |---|---|---|
        | §1.3 B-1 | 집행 | 비목표 |
        | ADR 32 · 시나리오 5 | 안전 기능 | 자체 계통 |
        ## 2. 열림 — 이 저장소가 지으면 닫힌다
        | 출처 | 무엇 | 무엇이 있어야 |
        |:---|---|---|
        | §15.34 | 구독기 없음 | 구독기 |
        ## 3. 열림 — 밖에서만 닫힌다
        | 출처 | 무엇 | 무엇이 있어야 |
        |---|---|---|
        | **C-3** | 실물 미검증 | 실물 |
        | §15.15 · §15.16 | CI 전용 검사 | 환경 |
    """.trimIndent()

    @Test
    fun `한계 대장을 갈래별로 읽는다`() {
        val byBranch = LimitsLedger.parse(ledgerSample)
        assertEquals(setOf("§1.3 B-1", "ADR 32 · 시나리오 5", "ADR 32", "시나리오 5"), byBranch[1])
        assertEquals(setOf("§15.34"), byBranch[2])
        assertEquals(true, byBranch[3]!!.containsAll(setOf("C-3", "§15.15", "§15.16")))
    }

    @Test
    fun `복합 칸의 조각도 댈 수 있다`() {
        // ★감사자가 `§15.16` 을 열림 근거로 댄다. 칸을 통째로만 등록하면 그것이 유령으로 판정된다.
        assertEquals(true, "§15.16" in LimitsLedger.parse(ledgerSample).getValue(3))
    }

    @Test
    fun `표 머리와 구분선은 id 가 아니다`() {
        // `|:---|` 꼴도 걸러야 한다 — 지금 파일엔 없지만 모양이 바뀌면 구분선이 id 가 된다.
        val ids = LimitsLedger.parse(ledgerSample).values.flatten()
        assertEquals(emptyList(), ids.filter { it == "출처" || it.trim(':', '-').isEmpty() })
    }
}
