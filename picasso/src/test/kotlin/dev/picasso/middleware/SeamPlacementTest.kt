package dev.picasso.middleware

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **떼어 낸 선언이 제 파일에만 있다.** 자주 바뀌는 접합부의 판정과 장부는 `Middleware.kt` 에서 떼어 각자의
 * 파일로 옮긴다(`docs/superpowers/plans/2026-09-26-middleware-seam-split.md`). `Middleware.kt` 에는 핵심
 * 상태기계와 공개 창구, 그리고 접합부를 핵심에 잇는 조율이 남는다 — 그 조율도 빨리 바뀌는 코드다.
 * 여러 절이 나눠 쓰는 정준 투영은 `Canonical.kt` 에 한 벌만 둔다(§15.115) — 같은 이름의 멤버를 클래스 안에
 * 다시 두면 그 클래스의 호출은 조용히 그 멤버를 부른다.
 *
 * 다시 한 파일로 모아도 다른 시험은 전부 초록이다. 그래서 갈라 둔 것이 조용히 사라진다(§15.193 이 내보내기
 * 세 벌에서 본 모양). 여기서는 출하 소스를 훑어 **선언이 어느 파일에 있는지**를 댄다. 이 파일은 `src/test`
 * 에 있어 제 바늘에 안 걸린다.
 */
class SeamPlacementTest {

    @Test
    fun `떼어 낸 선언이 제 파일에만 있다`() {
        val homes = mapOf(
            // 정준 투영 — 나눠 쓰는 쪽들의 바깥에 한 벌
            "fun canonicalClassOf(" to "Canonical.kt",
            "fun faultDetailOf(" to "Canonical.kt",
            "fun residualHoldOf(" to "Canonical.kt",
            // 배정 관문 — 피어 시스템 접합부
            "fun inconsistent(" to "AdmissionGate.kt",
            "fun chainRefusal(" to "AdmissionGate.kt",
            "fun occupancyViolation(" to "AdmissionGate.kt",
            "fun unownedFloor(" to "AdmissionGate.kt",
            "fun workspaceViolation(" to "AdmissionGate.kt",
        )
        val sources = Files.walk(Path.of("src", "main")).use { paths ->
            paths.asSequence()
                .filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                .map { it.fileName.toString() to Files.readString(it) }
                .toList()
        }
        assertTrue(sources.size > 10, "이 모듈의 출하 소스를 못 훑었다: ${sources.size}")
        homes.forEach { (needle, home) ->
            val found = sources.filter { needle in it.second }.map { it.first }.toSortedSet()
            assertEquals(sortedSetOf(home), found, "'$needle' 이 제 파일($home) 밖에 있거나 사라졌다")
        }
    }
}
