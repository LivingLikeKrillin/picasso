package dev.picasso.registry.revision

import dev.picasso.registry.store.Db

/** 스킬 종류 한 행. [siteReferenceKeys] 는 계약이 사이트 명칭으로 표시한 파라미터다(ADR 35). */
data class SkillTypeRow(
    val name: String,
    val major: Int,
    val introducedInSemver: String,
    val siteReferenceKeys: List<String>,
)

/** 기동 동기화의 결과. */
sealed interface BootSync {
    /** @param inserted 새로 들어온 스킬 종류 수 */
    data class Synced(val inserted: Int) : BootSync

    /** 스키마가 없다. 마이그레이션은 런처의 몫이라 여기서 돌리지 않는다. */
    data object NoSchema : BootSync
}

/**
 * 계약이 소유한 스킬 종류(§8.1). 기동 동기화와 조회를 든다.
 *
 * ## 기동 때 동기화한다(설계 §8.3 ④)
 *
 * [SkillTypeSync] 를 부르는 곳이 시험뿐이어서 운영 registry 의 `skill_type` 이 비어 있었다. 비면 개정판 제출이 선언
 * 스킬을 조용히 건너뛰고([RevisionService]), 사이트 명칭 요구 집합도 바인딩의 계약 semver 검사도 비교할 것이 없다.
 *
 * 계약 기술자를 못 읽으면 **기동을 거부한다**. 빈 카탈로그로 뜨는 것이 바로 위의 상태다. 스키마가 없으면 건너뛴다 —
 * 그 registry 는 어느 조작도 못 하므로 조용히 건너뛸 스킬도 없다.
 */
class SkillTypeCatalog(private val db: Db) {

    fun syncAtBoot(descriptor: ByteArray?, contractSemver: String): BootSync {
        checkNotNull(descriptor) { "계약 기술자(/picasso.desc)를 읽지 못했다 — 빈 카탈로그로 뜨면 제출이 스킬을 조용히 건너뛴다" }
        if (!hasSchema()) return BootSync.NoSchema
        return BootSync.Synced(SkillTypeSync(db).sync(descriptor, contractSemver, ACTOR))
    }

    fun list(): List<SkillTypeRow> = db.open().use { c ->
        c.prepareStatement(
            """
            SELECT t.name, t.major, t.introduced_in_semver,
                   COALESCE(array_agg(p.key ORDER BY p.key) FILTER (WHERE p.site_reference), '{}')
            FROM skill_type t
            LEFT JOIN skill_type_param p ON p.skill_type_id = t.skill_type_id
            GROUP BY t.skill_type_id, t.name, t.major, t.introduced_in_semver
            ORDER BY t.name, t.major
            """.trimIndent(),
        ).use { s ->
            s.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        @Suppress("UNCHECKED_CAST")
                        val keys = (rs.getArray(4).array as Array<String>).toList()
                        add(SkillTypeRow(rs.getString(1), rs.getInt(2), rs.getString(3), keys))
                    }
                }
            }
        }
    }

    private fun hasSchema(): Boolean = db.open().use { c ->
        c.prepareStatement("SELECT to_regclass('skill_type') IS NOT NULL").use { s ->
            s.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
        }
    }

    companion object {
        /** 기동 동기화의 감사 행위자. 사람이 아니라 registry 자신이다. */
        const val ACTOR = "registry"
    }
}
