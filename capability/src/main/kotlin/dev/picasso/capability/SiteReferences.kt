package dev.picasso.capability

import dev.picasso.contracts.v1.SkillCatalog

/**
 * 어느 파라미터가 **사이트 이름**을 나르는가 — 계약 카탈로그의 `is_site_reference` 에서 읽는다.
 *
 * **손으로 적은 목록을 두지 않는다.** 카탈로그가 스킬을 하나 더하면 여기가 저절로 늘고, 아무도 목록을
 * 고치지 않는다 — `SiteNameRegistration` 이 등록 대상을 프로파일에서 유도한 것과 같은 규율이다(ADR 35).
 * 두 벌로 두면 어느 날 한쪽만 늘고, 그때 사이트 이름 하나가 조용히 검사 밖에 놓인다.
 *
 * **값을 해석하지 않는다.** 여기가 답하는 것은 «어느 칸이 자리 이름인가» 뿐이고, 그 이름이 무엇을
 * 가리키는지는 기체 세계 모델이나 상위 정본의 몫이다(ADR 34 §4).
 */
object SiteReferences {

    private val byName: Map<String, List<String>> by lazy {
        SkillCatalog.getDescriptor().messageTypes
            .mapNotNull { message ->
                val name = message.options.getExtension(SkillCatalog.skillTypeName)
                if (name.isNullOrEmpty()) null
                else name to message.fields.filter { it.options.getExtension(SkillCatalog.isSiteReference) }.map { it.name }
            }
            .toMap()
    }

    /**
     * 이 스킬에서 사이트 이름을 나르는 파라미터 키. 순서는 계약의 필드 순서다.
     *
     * **카탈로그에 없는 스킬은 빈 목록이다** — 플릿 계약의 `transport` 처럼 이 카탈로그 밖의 단위가 그렇고,
     * 모르는 것에 대해 «자리 이름이 없다» 고 단정하는 것이 아니라 **이 카탈로그가 답할 것이 없다**는 뜻이다.
     */
    fun of(skillType: String): List<String> = byName[skillType].orEmpty()

    /** 자리 이름을 하나라도 나르는 스킬 전부. 검사가 훑을 범위를 여기서 얻는다. */
    fun skillTypes(): Set<String> = byName.filterValues { it.isNotEmpty() }.keys
}
