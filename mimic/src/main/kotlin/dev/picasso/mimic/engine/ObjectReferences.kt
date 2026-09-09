package dev.picasso.mimic.engine

import dev.picasso.contracts.v1.SkillCatalog

/**
 * 어느 파라미터가 **대상의 이름**인가 — 계약이 말한다.
 *
 * `skill_catalog.proto`의 `is_object_reference`(설계 §15.78)를 생성된
 * 디스크립터에서 읽는다. 미믹이 프로파일이나 스킬 이름으로 그것을 짐작하면
 * 스킬 어휘를 알게 되고, 그 순간 프로파일 교체가 코드 교체가 된다(§1.1의
 * 첫 주장). `pick_place`라는 문자열이 이 모듈 어디에도 없는 것이 그 증거다.
 *
 * 게이트의 `ContractIndex`와 같은 사실을 다른 경로로 읽는다 — 저쪽은
 * `buf build`의 바이트에서, 여기는 생성 코드에서. 둘이 어긋나면 디스크립터가
 * 낡은 것이다(§15.22).
 *
 * 쓰는 곳: [TaskHost]의 잔여 물리 상태(`HoldState`) — 대상을 **쥐는** 스킬
 * ([grasps])만 무언가를 들고, 든 것의 이름은 [keysOf]의 파라미터에서 온다.
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

    private val grasping: Set<String> by lazy {
        SkillCatalog.getDescriptor().messageTypes
            .filter { it.options.getExtension(SkillCatalog.graspsObject) }
            .map { it.options.getExtension(SkillCatalog.skillTypeName) }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /**
     * 이 스킬이 수행하는 동안 대상을 **쥐는가**(`grasps_object`).
     *
     * [keysOf]가 비어 있지 않다고 쥐는 것이 아니다 — `inspect(target)`는 대상을
     * 참조만 한다. 앞 판이 그 둘을 접어 점검 중인 로봇을 든 채로 보고했다(§15.87).
     */
    fun grasps(skillType: String): Boolean = skillType in grasping
}
