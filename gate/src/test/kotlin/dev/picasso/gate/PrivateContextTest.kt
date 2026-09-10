package dev.picasso.gate

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **이 저장소는 저자의 사적 맥락을 담지 않는다.**
 *
 * picasso 의 설계 판단 중에는 저장소 밖의 사적 문서를 읽고 내린 것이 있다. 규율은 처음부터 하나였다 —
 * *판단은 그대로 하되 저장소에는 벤더 1차 자료로 독립 확인되는 근거만 적는다.* 확인이 안 되면 **틀린
 * 단정을 지우고 비워 둔다.** 없는 근거를 지어내지도, 사적 근거를 끌어오지도 않는다.
 *
 * 그 규율이 2026-09-10 까지 **한 번 새었다.** 설계 일지 §15.91 의 한 줄과 같은 커밋의 메시지가 사적
 * 문서를 근거로 댔다. 히스토리에서 걷어냈고, **사람이 훑어 막는 일을 그만두려고** 이 시험을 둔다.
 *
 * ## 무엇을 훑나
 *
 * `:gate:test` 의 **선언된 입력 전부**다(`Repo.declaredFiles`). 산문이 사는 자리를 이미 다 덮고 있고,
 * 새 파일이 생기면 저절로 들어온다 — **목록을 손에서 뺀 것**이 이 저장소의 다른 검사들과 같은 규율이다.
 *
 * ## 정직한 한계 — 표지는 절반만 적혀 있다
 *
 * ★**조직과 사업장의 이름은 여기 안 적는다. 적는 순간 그것이 유출이다.** 그래서 이 시험이 잡는 것은
 * **사적 문서의 갈래를 가리키는 낱말**뿐이고, 조직 이름이 그대로 들어오면 **못 잡는다.** 그 목록은
 * 저장소 밖에 있으며, 이 시험은 그 자리를 대신하지 않는다.
 *
 * 표지를 반쪽으로 나눠 이어 붙이는 것도 같은 이유다 — 온전한 낱말로 적으면 **이 파일 자신이 걸리고**,
 * 그 전에 금지하려는 문자열을 저장소에 들여놓게 된다.
 */
class PrivateContextTest {

    @Test
    fun `저장소 어디에도 사적 맥락의 표지가 없다`() {
        val hits = mutableListOf<String>()
        for (file in Repo.declaredFiles()) {
            val bytes = Files.readAllBytes(file)
            // NUL 이 있으면 텍스트가 아니다. **확장자 목록으로 거르지 않는다** — 그 목록이 낡으면
            // 새 확장자의 파일이 조용히 안 훑힌다.
            if (bytes.any { it == 0.toByte() }) continue
            String(bytes, Charsets.UTF_8).lineSequence().forEachIndexed { index, line ->
                MARKERS.filter { it in line }.forEach { marker ->
                    hits += "${Repo.root.relativize(file)}:${index + 1} — '$marker'"
                }
            }
        }

        assertEquals(
            emptyList(), hits,
            "사적 맥락의 표지가 저장소에 있다. 판단은 그대로 두되 근거를 벤더 1차 자료로 바꾸거나, " +
                "확인이 안 되면 단정을 지우고 비워라.",
        )
    }

    @Test
    fun `훑을 파일이 실제로 있다`() {
        // **빈 목록을 훑으면 위 시험이 아무것도 안 보고 초록이 된다.** 이 저장소가 되풀이해 물린 자리이고
        // (§15.115), 훑는 시험은 특히 그렇다 — 잡을 것이 없는 것과 안 본 것이 같은 색이다.
        val scanned = Repo.declaredFiles().size
        assertTrue(scanned > 200, "선언된 입력이 $scanned 개다 — 훑는 시험이 빈 목록을 보고 있다")
    }

    private companion object {
        /**
         * 사적 문서의 갈래를 가리키는 낱말들. **반쪽으로 적어 이어 붙인다**(위 KDoc 의 이유).
         *
         * 이 저장소의 산문에 이 낱말들이 쓰일 자리는 없다 — 2026-09-10 전수에서 전부 0 이었고,
         * 유일한 예외가 걷어낸 그 한 줄이었다.
         */
        val MARKERS = listOf(
            "직" + "무",
            "채" + "용",
            "공" + "고",
            "면" + "접",
            "이력" + "서",
            "경력" + "기술",
            "지원" + "동기",
            "자기" + "소개",
        )
    }
}
