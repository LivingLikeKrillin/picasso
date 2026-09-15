package dev.picasso.profile

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * §7.4의 두 기종. **차이의 종류를 의도적으로 갖춘다** — 일곱이 코드 변경
 * 없이 표현되어야 한다(완료 기준 9).
 *
 * **문서에서 읽어 확인한다.** 기대값을 리터럴로 박으면 프로파일을 고칠 때
 * 시험도 같이 고치게 되고, 그러면 "데이터로 표현됐다"가 증명되지 않는다.
 */
class ModelProfilesTest {

    private fun load(name: String): ProfileDocument {
        val path = Path.of("..", "profile", "profiles", "$name.json").normalize()
        return ProfileDocument.parse(
            path.toString(),
            Files.readString(path).replace("\r\n", "\n"),
        ).getOrThrow()
    }

    private val a = load("humanoid-a")
    private val b = load("quadruped-b")

    private fun skill(doc: ProfileDocument, type: String) =
        doc.skills.firstOrNull { it.skillType == type }

    @Test
    fun `두 문서가 서로 다른 기종이다`() {
        // 같은 (vendor, model)이면 게이트가 거절하고, 여기서도 "두 로봇"이
        // 하나가 된다.
        assertNotEquals(ProfileKey(a.vendor, a.model), ProfileKey(b.vendor, b.model))
    }

    @Test
    fun `§7-4의 일곱 차이가 전부 실재한다`() {
        val checks: Map<String, () -> Boolean> = mapOf(
            "공통 스킬이 같은 major.minor다" to {
                val x = skill(a, "navigate_to")!!
                val y = skill(b, "navigate_to")!!
                x.major == y.major && x.minor == y.minor
            },
            "한쪽에만 있는 스킬이 있다" to {
                skill(a, "pick_place") != null && skill(b, "pick_place") == null
            },
            "같은 스킬의 minor가 다르다" to {
                val x = skill(a, "inspect")!!
                val y = skill(b, "inspect")!!
                x.major == y.major && x.minor != y.minor
            },
            "cancel_support가 다르다" to {
                skill(a, "navigate_to")!!.cancelSupport != skill(b, "navigate_to")!!.cancelSupport
            },
            "pause_support가 다르고 한쪽이 UNKNOWN이다" to {
                val x = skill(a, "navigate_to")!!.pauseSupport
                val y = skill(b, "navigate_to")!!.pauseSupport
                x != y && (x == "UNKNOWN" || y == "UNKNOWN")
            },
            "프로토콜 한계가 다르다" to {
                a.maxStringLength != b.maxStringLength && a.maxArrayLength != b.maxArrayLength
            },
            "REQUIRED 선택 필드가 한쪽에만 있다" to {
                val required = { doc: ProfileDocument ->
                    doc.optionalFields.any { it.support == "REQUIRED" }
                }
                required(a) != required(b)
            },
        )
        // 표가 비면 아무것도 확인하지 않고 통과한다.
        assertEquals(7, checks.size)

        checks.forEach { (name, holds) ->
            assertTrue(holds(), "§7.4의 차이가 프로파일에 없다: $name")
        }
    }

    @Test
    fun `minor가 낮은 쪽이 선택 파라미터를 모른다`() {
        // §5.2 — minor 증가는 선택 파라미터 추가만이다. 그것이 실제로
        // 문서에 반영되어야 "몰라도 동작한다"가 시험 가능해진다.
        val high = skill(a, "inspect")!!
        val low = skill(b, "inspect")!!
        assertTrue(high.minor > low.minor)

        val extra = high.parameters.map { it.key }.toSet() - low.parameters.map { it.key }.toSet()
        assertTrue(extra.isNotEmpty(), "minor가 높은데 파라미터가 안 늘었다")
        extra.forEach { key ->
            assertTrue(
                high.parameters.single { it.key == key }.optional,
                "minor 증가로 **필수** 파라미터가 늘었다: $key — 그것은 major 증가다(§5.2)",
            )
        }
    }

    @Test
    fun `공통 스킬의 파라미터 선언이 같다`() {
        // "같은 코드로 양쪽 제어"의 전제다. 선언이 다르면 같은 파라미터가
        // 한쪽에서만 통과하고, 그것은 능력 차이가 아니라 계약 위반에 가깝다.
        val x = skill(a, "navigate_to")!!
        val y = skill(b, "navigate_to")!!
        assertEquals(
            x.parameters.map { it.key to it.valueType }.toSet(),
            y.parameters.map { it.key to it.valueType }.toSet(),
        )
    }

    @Test
    fun `한쪽에서만 통과하는 값이 존재한다`() {
        // §10.4 ③ — 한계 차이가 코드가 아니라 데이터에서 온다. 두 한계
        // 사이에 실제로 값이 들어갈 자리가 없으면 그 시험을 쓸 수 없다.
        val narrow = minOf(a.maxStringLength, b.maxStringLength)
        val wide = maxOf(a.maxStringLength, b.maxStringLength)
        assertTrue(wide > narrow + 1, "두 한계 사이에 값이 없다: $narrow, $wide")

        // 그리고 그 값이 파라미터 선언의 최대 길이에 먼저 걸리면 안 된다 —
        // 걸리면 양쪽 모두 거절이라 차이가 관측되지 않는다.
        val declared = listOf(a, b).map { doc ->
            doc.skills.single { it.skillType == "navigate_to" }
                .parameters.single { it.key == "location" }.maxLength
        }
        declared.forEach {
            assertTrue(it != null && it > narrow, "location의 최대 길이가 좁은 한계보다 작다: $it")
        }
    }

    @Test
    fun `참고 프로파일이 사전 조건을 선언한다`() {
        // 설계안 §5 — 이 설계의 유일한 실증 자리. 기구를 쓰는 프로파일이 하나는 있어야 시험이 선다
        // (`grip_force` 의 min_value 가 이 문서에만 있는 것과 같다).
        val nav = skill(a, "navigate_to")!!
        assertEquals(listOf(ProfileDocument.PreconditionEntry("HOLD", "EMPTY")), nav.preconditions)
    }

    @Test
    fun `실물 프로파일은 v1 에서 조건을 선언하지 않는다`() {
        // 설계안 §5 — 실물 넷 어느 것도 벤더 1차 자료로 "든 채로 이동 불가"가 확인되지 않았다.
        // UNKNOWN 은 선언하지 않는 것이다(CLAUDE.md §2-5). 그리고 파지를 관측하지 못하는 기종(G1)은
        // 조건을 적는 순간 영구 거절이 된다(설계안 §3.2). 실물에 조건이 들어가는 날은 벤더 조사가 먼저 갱신되는 날이다.
        val dir = Path.of("..", "profile", "profiles").normalize()
        val real = Files.list(dir).use { s -> s.filter { it.toString().endsWith(".json") }.toList() }
            .map { p -> ProfileDocument.parse(p.toString(), Files.readString(p).replace("\r\n", "\n")).getOrThrow() }
            .filter { it.vendor != "picasso-ref" }
        assertTrue(real.size >= 3, "실물 프로파일을 못 찾았다: ${real.map { it.model }}")
        real.forEach { doc ->
            doc.skills.forEach { s ->
                assertEquals(emptyList(), s.preconditions, "${doc.vendor}/${doc.model} 의 ${s.skillType} 가 조건을 선언했다")
            }
        }
    }
}
