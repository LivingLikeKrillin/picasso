package dev.picasso.gate

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **고정한 언어 판이 플러그인 판을 따라오는가.**
 *
 * 언어 판을 안 적으면 `KotlinVersion.DEFAULT` 를 따르고 그것은 **플러그인 판과 함께 움직인다** —
 * 2.0.21 → 2.4.20 에서 실제로 2.0 → 2.4 로 옮겨 갔다(§15.138 의 실측). 아무것도 안 깨졌지만
 * 그것은 운이 좋았다는 뜻이고, **정한 적 없는 것이 조용히 바뀌는 자리**였다.
 *
 * 그래서 `build.gradle.kts` 에 적었다. 그런데 **적기만 하면 이번에는 반대 방향으로 낡는다** —
 * 플러그인을 올려도 이 줄이 그대로면 새 컴파일러로 옛 언어 판을 쓰게 되고, 그것도 아무도 안 본다.
 *
 * ★**그래서 둘을 묶는다.** 이 시험이 빨개지는 것은 *언어 판이 틀렸다* 가 아니라 ***플러그인을
 * 올렸으니 언어 판을 어떻게 할지 정하라*** 는 뜻이다. 도장의 해시가 문서에 대해 하는 일과 같다 —
 * 막는 것이 아니라 **사람이 봐야 하는 순간에 빨개지는 것**이 전부다.
 *
 * ★**텍스트로 읽는다.** `gate` 는 Kotlin 플러그인의 API 를 안 쓰고 두 파일을 문자열로 읽는다 —
 * 게이트 5·7 번과 같은 가족이고 같은 한계를 갖는다(ADR 23).
 */
class KotlinLanguageVersionTest {

    @Test
    fun `고정한 언어 판이 플러그인 판과 같다`() {
        val catalog = Repo.read("gradle/libs.versions.toml")
        val plugin = Regex("""(?m)^kotlin\s*=\s*"(\d+)\.(\d+)\.\d+"""").find(catalog)
            ?: error("libs.versions.toml 에서 kotlin 판을 못 읽었다 — 이름이 바뀌었나")
        val expected = "KOTLIN_${plugin.groupValues[1]}_${plugin.groupValues[2]}"

        val root = Repo.read("build.gradle.kts")
        val pinned = Regex("""KotlinVersion\.(KOTLIN_\d+_\d+)""").findAll(root)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        assertEquals(
            listOf(expected), pinned,
            "플러그인은 ${plugin.value.trim()} 인데 build.gradle.kts 가 고정한 언어 판이 다르다 — " +
                "올린 김에 언어 판을 어떻게 할지 정하고 그 줄을 함께 고쳐라",
        )
    }

    @Test
    fun `언어 판과 API 판을 둘 다 적는다`() {
        // `apiVersion` 을 빼면 그것만 기본값을 따라 다시 움직인다 — 고정의 절반이 새는 자리다.
        val root = Repo.read("build.gradle.kts")
        val missing = listOf("languageVersion", "apiVersion").filterNot { root.contains("$it.set(") }
        assertEquals(emptyList(), missing, "build.gradle.kts 가 둘 중 하나만 적었다")
    }
}
