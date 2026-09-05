package dev.picasso.gate.input

import dev.picasso.gate.Resource
import dev.picasso.gate.model.ProfileDocument
import java.nio.file.Path

/** 파싱조차 되지 않은 프로파일. 검사 3번이 소견으로 보고한다. */
data class MalformedProfile(val path: String, val message: String)

/**
 * 검사가 읽는 것 전부. 자원마다 있을 수도 없을 수도 있고,
 * 없으면 그 자원을 요구하는 검사가 건너뛴다(설계 §3.1).
 *
 * CI와 registry가 같은 구현을 부르되 채우는 자원이 다르다 —
 * registry는 REPO/BUF/CHANGED_FILES 없이 문서와 디스크립터를 주고,
 * CI는 REGISTRY 없이 나머지를 준다.
 */
data class GateInput(
    val profiles: List<ProfileDocument> = emptyList(),

    /** 파싱 실패한 문서들. 있으면 그것도 "문서가 있다"로 센다. */
    val malformed: List<MalformedProfile> = emptyList(),

    /** 능력 프로파일 JSON Schema 원문 */
    val schemaJson: String? = null,

    /** contracts의 FileDescriptorSet 바이트 */
    val descriptor: ByteArray? = null,

    /** 저장소 루트 */
    val repoRoot: Path? = null,

    /** buf를 부를 수 있는가 */
    val bufAvailable: Boolean = false,

    /**
     * 파괴 검사의 기준선. 프로파일 경로 → 기준선 문서 원문.
     * CI는 기본 브랜치의 파일, registry는 직전 ACTIVE 개정판의 document다
     * (설계 §11.1).
     *
     * **null과 빈 맵의 뜻이 다르다.** null은 기준선 출처에 닿지 못한 것이라
     * 검사가 건너뛴다. 빈 맵이거나 그 프로파일의 키가 없는 것은 **신규**라
     * 파괴 검사가 통과로 처리한다(§11.1).
     */
    val baseline: Map<String, String>? = null,

    /** 이 PR이 바꾼 파일 목록. 검사 8번(2단계)이 쓴다. */
    val changedFiles: List<String>? = null,

    /** 의존 원장 조회기. 검사 6번의 축소 판정이 쓴다(설계 §9.3). */
    val registry: LedgerQuery? = null,
) {
    fun available(): Set<Resource> = buildSet {
        if (profiles.isNotEmpty() || malformed.isNotEmpty()) add(Resource.PROFILE_DOCUMENT)
        if (schemaJson != null) add(Resource.PROFILE_SCHEMA)
        if (descriptor != null) add(Resource.CONTRACT_DESCRIPTOR)
        if (repoRoot != null) add(Resource.REPO)
        if (bufAvailable) add(Resource.BUF)
        if (baseline != null) add(Resource.BASELINE)
        if (changedFiles != null) add(Resource.CHANGED_FILES)
        if (registry != null) add(Resource.REGISTRY)
    }

    // data class가 ByteArray에 대해 만드는 equals/hashCode는 참조 비교라
    // 쓸모가 없다. 이 타입은 값 비교 대상이 아니므로 명시적으로 막는다.
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * 의존 원장 조회. 설계 §9.3의 두 조회를 검사 6번에 노출한다.
 * 3b에서 registry가 구현하고, 그전까지는 언제나 null이라 6번이 부분 건너뜀이 된다.
 */
interface LedgerQuery {
    /** 이 능력을 요구하는 active 소비자 수 */
    fun activeConsumers(skillType: String, major: Int): Int

    /** 이 능력을 쓰는 비종착 태스크 수 */
    fun inflightTasks(skillType: String, major: Int): Int
}
