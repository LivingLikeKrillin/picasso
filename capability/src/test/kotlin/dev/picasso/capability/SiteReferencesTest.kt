package dev.picasso.capability

import dev.picasso.contracts.v1.SkillCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 어느 파라미터가 사이트 이름을 나르는가 — 카탈로그의 `is_site_reference` 가 정본이다.
 *
 * **여기서 목록을 다시 적지 않는다.** 기대값을 손으로 적으면 카탈로그가 느는 날 한쪽만 고쳐지고, 그때
 * 이 시험은 «카탈로그가 틀렸다» 가 아니라 «시험이 낡았다» 인데 증상이 같다. 그래서 대조 대상도
 * 디스크립터에서 뽑는다.
 */
class SiteReferencesTest {

    /** 카탈로그가 스스로 대는 목록. 시험이 손으로 적은 두 번째 목록을 갖지 않는다. */
    private fun fromDescriptor(): Map<String, List<String>> =
        SkillCatalog.getDescriptor().messageTypes
            .mapNotNull { message ->
                val name = message.options.getExtension(SkillCatalog.skillTypeName)
                if (name.isNullOrEmpty()) null
                else name to message.fields.filter { it.options.getExtension(SkillCatalog.isSiteReference) }.map { it.name }
            }
            .toMap()

    @Test
    fun `카탈로그가 표시한 파라미터를 그대로 낸다`() {
        fromDescriptor().forEach { (skillType, expected) ->
            assertEquals(expected, SiteReferences.of(skillType), "$skillType 의 자리 이름 파라미터가 카탈로그와 다르다")
        }
    }

    @Test
    fun `표시가 하나도 없으면 검사가 아무것도 안 보는 것이다`() {
        // 훑는 시험의 되풀이되는 실패 방식 — 잡을 것이 없는 것과 안 본 것이 같은 색이다.
        assertTrue(SiteReferences.skillTypes().isNotEmpty(), "카탈로그에 `is_site_reference` 가 하나도 없다")
    }

    @Test
    fun `이동은 자리 이름을 나른다`() {
        // 계약이 자리 이름을 나르는 대표 경로. 이것이 빠지면 판 검사가 통째로 무력해진다.
        assertTrue("location" in SiteReferences.of("navigate_to"), SiteReferences.of("navigate_to").toString())
    }

    @Test
    fun `카탈로그에 없는 단위는 빈 목록이다`() {
        // 플릿 계약의 `transport` 처럼 이 카탈로그 밖의 단위가 그렇다. **모르는 것을 «없다» 로 단정하는
        // 것이 아니라 이 카탈로그가 답할 것이 없다는 뜻**이고, 그래서 판 검사도 걸리지 않는다.
        assertEquals(emptyList(), SiteReferences.of("transport"))
    }

    @Test
    fun `자리 이름이 없는 파라미터는 안 센다`() {
        // 전부 자리 이름으로 읽으면 수치 파라미터 하나가 정본에 없다는 이유로 라인이 선다.
        assertTrue("grip_force" !in SiteReferences.of("pick_place"), SiteReferences.of("pick_place").toString())
    }
}
