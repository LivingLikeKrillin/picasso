package dev.picasso.capability

import dev.picasso.contracts.v1.SkillCatalog

/**
 * 어느 파라미터가 **대상의 이름**인가 — 계약이 말한다(`is_object_reference`, 설계 §15.78).
 *
 * 생성된 디스크립터에서 읽는다. 스킬 이름으로 짐작하면 그 순간 프로파일 교체가 코드 교체가 되고,
 * `pick_place` 라는 문자열이 이 모듈에 생긴다.
 *
 * **[HoldEffects] 옆에 두는 이유.** 둘은 같은 카탈로그의 같은 질문을 나눠 답한다 — 저쪽이 *"이 스킬이
 * 파지를 어떻게 바꾸나"* 이고 여기가 *"그 대상의 이름이 어느 칸에 있나"* 다. 떨어뜨려 두면 한쪽만 아는
 * 소비자가 생기고, 실제로 그랬다: 승인이 «무엇을» 채울 때 이 사실이 필요한데 그것이 미믹 안에 있었다.
 *
 * 쓰는 곳 둘 — 미믹이 잔여 파지에 **싣고**(`TaskHost.holdOf`), 미들웨어가 자동 승인에서 그 반대로
 * **읽는다**. 방향이 반대라 두 벌로 두면 어느 날 한쪽만 늘고, 그때 «든 것» 과 «놓을 것» 이 갈린다.
 */
object ObjectReferences {

    private val byName: Map<String, Set<String>> by lazy {
        val out = mutableMapOf<String, MutableSet<String>>()
        for (message in SkillCatalog.getDescriptor().messageTypes) {
            val name = message.options.getExtension(SkillCatalog.skillTypeName)
            if (name.isNullOrEmpty()) continue
            val keys = message.fields
                .filter { it.options.getExtension(SkillCatalog.isObjectReference) }
                .map { it.name }
            // 같은 이름의 major 가 여럿이면 합친다 — 어느 major 를 도는지 여기서는 모른다.
            out.getOrPut(name) { mutableSetOf() } += keys
        }
        out
    }

    /** 이 스킬 타입에서 대상의 이름을 나르는 파라미터 키들. 없으면 빈 집합. */
    fun keysOf(skillType: String): Set<String> = byName[skillType].orEmpty()
}
