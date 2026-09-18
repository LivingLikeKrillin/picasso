package dev.picasso.mimic.engine

import dev.picasso.capability.HoldEffects

/**
 * 어느 파라미터가 **대상의 이름**인가 — 계약이 말한다.
 *
 * `skill_catalog.proto`의 `is_object_reference`(설계 §15.78)를 읽는 것은
 * `capability.ObjectReferences` 다. 미믹이 프로파일이나 스킬 이름으로 그것을
 * 짐작하면 스킬 어휘를 알게 되고, 그 순간 프로파일 교체가 코드 교체가 된다
 * (§1.1의 첫 주장). `pick_place`라는 문자열이 이 모듈 어디에도 없는 것이 그 증거다.
 *
 * 쓰는 곳: [TaskHost]의 잔여 물리 상태(`HoldState`) — 대상을 **쥐는** 스킬
 * ([grasps])만 무언가를 들고, 든 것의 이름은 [keysOf]의 파라미터에서 온다.
 */
object ObjectReferences {

    // **카탈로그는 한 곳에서만 읽는다**(리뷰 R1). 효과가 그랬듯 이 사실도 소비자가 둘이 됐고
    // (미믹은 싣고 미들웨어는 읽는다), 두 벌로 두면 어느 날 한쪽만 는다.
    fun keysOf(skillType: String): Set<String> =
        dev.picasso.capability.ObjectReferences.keysOf(skillType)

    /**
     * 이 스킬이 수행하는 동안 대상을 **쥐는가**(`grasps_object`).
     *
     * [keysOf]가 비어 있지 않다고 쥐는 것이 아니다 — `inspect(target)`는 대상을
     * 참조만 한다. 앞 판이 그 둘을 접어 점검 중인 로봇을 든 채로 보고했다(§15.87).
     */
    fun grasps(skillType: String): Boolean = HoldEffects.grasps(skillType)
}
