package dev.picasso.gate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **구성도가 빌드와 같은 것을 말하는가.**
 *
 * `docs/diagrams/components.svg` 는 손으로 그린 것이 아니라 각 `build.gradle.kts` 의 출하 의존에서
 * 뽑아 그린다(`tools/diagram-gen/components.mjs`). 그런데 **뽑은 뒤에는 그림이 파일로 굳는다** —
 * 의존이 바뀌어도 아무도 다시 뽑지 않으면 그림만 낡는다. 이 저장소가 반복해 물린 자리다.
 *
 * ★**그림이 자기가 그린 간선을 주석으로 싣는다**(`<!-- edge: a -> b -->`). 생성기가 같은 배열에서
 * 선과 주석을 함께 내므로 한 번의 생성 안에서는 둘이 못 갈린다. 이 시험은 그 주석을 읽어 빌드와 댄다.
 *
 * ★★**규칙을 한 벌도 안 옮겼다.** 주석은 그림의 **표시 라벨이 아니라 실제 모듈 이름**으로 나온다 —
 * 기종 어댑터 셋을 한 칸으로 묶은 것은 그림의 사정이고, 주석은 그 칸이 뜻하는 간선 셋을 각각 적는다.
 * 그래서 이 시험은 묶음도 숨김도 모른 채 «빌드에 있는가» 만 묻는다.
 *
 * ★**못 보는 것**: 주석이 아니라 선이 틀린 경우. 사람이 SVG 를 손으로 고치면 잡지 못한다(ADR 23 의 가족).
 */
class ComponentMapTest {

    private val svg by lazy { Repo.read("docs/diagrams/components.svg") }

    /** 각 모듈의 출하 의존. `DocumentClaimsTest` 와 같은 규칙이다. */
    private fun shipping(): Map<String, List<String>> {
        val modules = Regex(""""([a-z0-9-]+)"""")
            .findAll(Repo.read("settings.gradle.kts").substringAfter("rootProject.name"))
            .map { it.groupValues[1] }.distinct().toList()
        val dep = Regex("""(?m)^\s*(\w+)\s*\(\s*(?:testFixtures\s*\(\s*)?project\("[:]([a-z0-9-]+)"\)""")
        return modules.associateWith { m ->
            val build = Repo.path("$m/build.gradle.kts")
            if (!java.nio.file.Files.exists(build)) emptyList()
            else dep.findAll(Repo.read(build))
                .filter { it.groupValues[1] in SHIPPING }
                .map { it.groupValues[2] }
                .distinct().toList()
        }
    }

    @Test
    fun `구성도가 그린 간선이 전부 실재하는 의존이다`() {
        // ★지어낸 선을 막는다. 그림이 «쓴다» 고 그은 것은 빌드에 있어야 한다.
        val graph = shipping()
        val edges = Regex("""<!-- edge: (\S+) -> (\S+) -->""").findAll(svg)
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertTrue(edges.size >= 10, "구성도에서 간선을 못 읽었다 — 생성기가 주석을 안 싣나: ${edges.size}")

        val ghosts = edges
            .filterNot { (from, to) -> to in graph.getOrDefault(from, emptyList()) }
            .map { (from, to) -> "$from -> $to" }
        assertEquals(emptyList(), ghosts, "구성도가 빌드에 없는 의존을 그렸다")

        // ★**반대 방향도 막는다.** 위 단언만 있으면 «그린 것이 실재한다» 만 보고, **실재하는데 안 그린 것**은
        // 지나간다 — 결함 주입으로 확인했다(`gate` 에 의존을 하나 더해도 초록이었다). 주석이 실제 모듈
        // 이름이라 수로 막을 수 있다: 바닥으로 가지 않는 출하 간선은 전부 주석에 있어야 한다.
        val drawable = graph.values.flatten().count { it !in ROOTS }
        assertEquals(
            drawable, edges.size,
            "빌드의 간선과 구성도의 간선 수가 다르다 — 의존이 늘거나 줄었으면 그림을 다시 뽑아라: " +
                "node tools/diagram-gen/components.mjs docs/diagrams/components.svg 1000 .",
        )
    }

    @Test
    fun `구성도가 적은 수가 빌드와 같다`() {
        // ★**그림이 스스로 무엇을 숨겼는지 적는다.** 바닥 둘로 가는 간선은 안 그리는 대신 수로 적었고,
        // 의존이 늘거나 줄면 그 수가 먼저 틀어진다.
        val graph = shipping()
        val modules = graph.keys.size
        val hidden = graph.values.flatten().count { it in ROOTS }

        assertEquals(
            modules, claimed("""모듈 (\d+) 개"""),
            "구성도가 적은 모듈 수가 settings.gradle.kts 와 다르다",
        )
        assertEquals(
            hidden, claimed("""화살표 (\d+) 개는 안 그렸다"""),
            "바닥 둘로 가는 간선 수가 빌드와 다르다",
        )
    }

    @Test
    fun `구성도의 칸이 전부 실재하는 모듈이다`() {
        val graph = shipping()
        val drawn = Regex("""<!-- edge: (\S+) -> (\S+) -->""").findAll(svg)
            .flatMap { sequenceOf(it.groupValues[1], it.groupValues[2]) }
            .distinct().toList()
        val unknown = drawn.filterNot { it in graph.keys }
        assertEquals(emptyList(), unknown, "구성도가 없는 모듈을 그렸다")
    }

    private fun claimed(pattern: String): Int =
        Regex(pattern).find(svg)?.groupValues?.get(1)?.toInt()
            ?: error("구성도에서 이 수를 못 읽었다: $pattern")

    private companion object {
        val SHIPPING = setOf("api", "implementation", "compileOnly", "runtimeOnly")
        val ROOTS = setOf("contracts", "profile-model")
    }
}
