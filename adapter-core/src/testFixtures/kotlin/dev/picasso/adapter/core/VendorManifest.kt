package dev.picasso.adapter.core

import java.lang.reflect.Modifier
import kotlin.test.fail

/**
 * 벤더 원문에서 뽑은 **이름의 집합**과, 남쪽 포트가 그것을 벗어났는지 보는 검사.
 *
 * ## 원문이 아니라 이름만 들인다
 *
 * `adapter-boston-dynamics-spot/build.gradle.kts` 가 적어 둔 것을 지킨다 —
 * *"벤더 SDK가 여기 없고, 여기 말고는 어디에도 못 들어온다 … 남쪽이 포트라
 * SDK 없이 컴파일되고 시험이 돈다."* 원문을 들이면 그 문장이 거짓이 되고
 * ADR 31 의 격리를 다시 논해야 한다.
 *
 * 들이는 것은 **이름과 그 원본의 해시**다. 이름은 API 에 대한 사실이지 벤더의
 * 표현이 아니며, 조사 문서들이 이미 산문으로 하던 일을 기계가 읽을 수 있는
 * 모양으로 옮길 뿐이다. 형식이 JSON 이 아니라 줄 단위 텍스트인 것도 그래서다 —
 * 파서 의존이 없어야 이 검사가 어디서나 돈다.
 *
 * ## 검사가 둘인 것이 요점이다
 *
 * | | 막는 것 |
 * |---|---|
 * | 짚은 이름이 매니페스트에 있다 | 오독·오타·벤더의 개명 |
 * | **멤버마다 짚은 것이 있다** | 검사를 안 받는 새 주장이 조용히 느는 것 |
 *
 * 앞의 것만 있으면 애너테이션을 안 붙이는 것으로 언제나 통과한다. 뒤의 것이
 * 이 검사를 **살아 있게** 한다.
 *
 * ## 한계 넷
 *
 * ① 매니페스트는 **마지막으로 뽑은 시점의 것**이고, 낡은 매니페스트는 낡은
 * 코드와 사이좋게 초록이다. 그것을 막는 것은 이 파일이 아니라 헤더에 적힌
 * 릴리스와 해시, 그리고 `tools/vendor-manifest/README.md` 의 갱신 절차다.
 * ② **이름이 있다는 것만 본다** — 그 메시지를 보냈을 때 로봇이 무엇을 하는지는
 * 전혀 안 본다(§9.7 ④·C-3). ③ 어떤 타입을 검사에 넣을지는 시험이 손으로
 * 적은 목록이다. 새 타입을 목록에 안 넣으면 안 본다.
 * ④ **인용의 완전성은 못 본다** — 멤버가 *아무것도* 안 짚으면 잡지만, 다섯을
 * 짚어야 할 자리에 넷만 짚은 것은 통과한다. 몇 개가 맞는지는 알 도리가 없다.
 */
class VendorManifest private constructor(
    /** 매니페스트가 어느 벤더 릴리스에서 나왔는가. 실패 메시지가 이것을 말한다. */
    val release: String,
    private val symbols: Set<String>,
) {

    /**
     * [types] 의 멤버가 짚은 이름이 전부 매니페스트에 있는지, 그리고 **멤버마다
     * 짚은 것이 있는지** 본다.
     *
     * 실패를 모아서 한 번에 낸다. 하나씩 터뜨리면 매니페스트를 갱신할 때
     * 고칠 것을 하나씩만 보게 되고, 그러면 몇 바퀴를 돈다.
     */
    fun verify(vararg types: Class<*>) {
        val unknown = mutableListOf<String>()
        val unclaimed = mutableListOf<String>()

        for (type in types) {
            for ((member, surface) in membersOf(type)) {
                val where = "${type.simpleName}.$member"
                if (surface == null) {
                    unclaimed += where
                    continue
                }
                surface.symbols.filterNot { it in symbols }.forEach { unknown += "$where -> $it" }
            }
        }

        if (unknown.isEmpty() && unclaimed.isEmpty()) return

        val report = buildString {
            if (unknown.isNotEmpty()) {
                appendLine("매니페스트($release)에 없는 이름을 짚었다:")
                unknown.forEach { appendLine("  $it") }
                appendLine(
                    "  ** 벤더가 개명했거나, 우리가 오독했거나, 추출기가 그 줄을 못 읽은 것이다. " +
                        "셋째가 의심되면 tools/vendor-manifest 의 파서를 먼저 본다. **",
                )
            }
            if (unclaimed.isNotEmpty()) {
                appendLine("벤더 원문을 안 짚은 멤버가 있다 — 검사를 안 받는 주장이다:")
                unclaimed.forEach { appendLine("  $it") }
            }
        }
        fail(report)
    }

    /**
     * 타입의 모양마다 멤버를 다르게 센다.
     *
     * **합성 멤버를 빼는 것이 중요하다.** 코틀린이 만든 `component1`·`copy`·
     * 브리지가 섞이면 "짚은 것이 없다" 가 쏟아져 검사가 소음이 되고, 그러면
     * 애너테이션을 붙이지 않는 쪽으로 도망가게 된다.
     */
    private fun membersOf(type: Class<*>): List<Pair<String, VendorSurface?>> = when {
        type.isEnum -> type.enumConstants.map { constant ->
            val name = (constant as Enum<*>).name
            name to type.getField(name).getAnnotation(VendorSurface::class.java)
        }

        type.isInterface -> type.declaredMethods
            .filterNot { it.isSynthetic || it.isDefault }
            .map { it.name to it.getAnnotation(VendorSurface::class.java) }

        else -> type.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) || "$" in it.name }
            .map { it.name to it.getAnnotation(VendorSurface::class.java) }
    }.sortedBy { it.first }

    companion object {
        /**
         * 시험 리소스에서 읽는다.
         *
         * `#` 로 시작하는 줄은 사람이 읽는 출처 기록(릴리스·파일별 sha256)이며,
         * **그것이 이 파일의 절반이다** — 이름만 있고 어디서 왔는지 없으면
         * 매니페스트가 다시 산문이 된다.
         */
        fun load(resource: String = "/vendor-manifest.txt"): VendorManifest {
            val text = VendorManifest::class.java.getResource(resource)?.readText()
                ?: error("벤더 매니페스트가 없다: $resource")

            val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            val release = lines.firstOrNull { it.startsWith("# release:") }
                ?.removePrefix("# release:")?.trim()
                ?: error("매니페스트에 릴리스가 없다 — 어느 시점의 이름인지 모르면 값이 없다")

            return VendorManifest(release, lines.filterNot { it.startsWith("#") }.toSet())
        }
    }
}
