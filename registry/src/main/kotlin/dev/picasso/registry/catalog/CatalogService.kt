package dev.picasso.registry.catalog

import dev.picasso.registry.store.Db

/**
 * 카탈로그의 한 줄 — 기체 하나가 지금 무엇으로 도는가.
 *
 * **`conformanceStatus`가 여기 있는 것이 완료 기준 16의 절반이다.** 시험 안
 * 된 어댑터가 조용히 돌면 운영자가 그 사실을 모른다.
 */
data class CatalogEntry(
    val robotId: String,
    val siteId: String,
    val vendor: String,
    val model: String,
    val profileRevision: Int,
    val adapterName: String,
    val adapterVersion: String,
    val contractSemver: String,
    val conformanceStatus: String,
    val skills: List<String>,
)

/**
 * §8.5의 읽기 표면 — 진단 1번(`GET /diag/bindings`)이 서는 바닥.
 *
 * **활성 바인딩만 본다.** 해제된 것은 이력이라 여기 안 온다(§9.1) — 그것을
 * 섞으면 "이 기체가 지금 무엇으로 도는가"에 답이 여럿이 된다. 이력 조회는
 * 진단이 따로 낸다.
 *
 * HTTP 표면은 3a-3이다. 여기서는 **무엇을 답하는가**만 정한다.
 */
class CatalogService(private val db: Db) {

    fun entries(site: String? = null): List<CatalogEntry> = db.open().use { c ->
        c.prepareStatement(
            """
            SELECT r.robot_id, r.site_id, cp.vendor, cp.model, pr.revision,
                   a.name, av.version, av.contract_semver, av.conformance_status
            FROM robot_binding b
            JOIN robot r              ON r.robot_id = b.robot_id
            JOIN profile_revision pr  ON pr.profile_revision_id = b.profile_revision_id
            JOIN capability_profile cp ON cp.profile_id = pr.profile_id
            JOIN adapter_version av   ON av.adapter_version_id = b.adapter_version_id
            JOIN adapter a            ON a.adapter_id = av.adapter_id
            WHERE b.unbound_at IS NULL AND (? IS NULL OR r.site_id = ?)
            ORDER BY r.robot_id
            """.trimIndent(),
        ).use { s ->
            s.setString(1, site); s.setString(2, site)
            s.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            CatalogEntry(
                                robotId = rs.getString(1),
                                siteId = rs.getString(2),
                                vendor = rs.getString(3),
                                model = rs.getString(4),
                                profileRevision = rs.getInt(5),
                                adapterName = rs.getString(6),
                                adapterVersion = rs.getString(7),
                                contractSemver = rs.getString(8),
                                conformanceStatus = rs.getString(9),
                                skills = emptyList(),
                            ),
                        )
                    }
                }
            }
        }
    }.map { it.copy(skills = skillsOf(it.robotId)) }

    /**
     * 그 기체가 지금 쓰는 개정판이 선언한 스킬들.
     *
     * **관계에서 읽는다.** 문서(JSONB)를 파싱하면 파싱이 두 곳에 생기고,
     * 그때 카탈로그가 보는 것과 바인딩 검사가 보는 것이 갈릴 수 있다.
     */
    private fun skillsOf(robotId: String): List<String> = db.open().use { c ->
        c.prepareStatement(
            """
            SELECT t.name FROM robot_binding b
            JOIN profile_skill ps ON ps.profile_revision_id = b.profile_revision_id
            JOIN skill_type t     ON t.skill_type_id = ps.skill_type_id
            WHERE b.robot_id = ? AND b.unbound_at IS NULL
            ORDER BY t.name
            """.trimIndent(),
        ).use { s ->
            s.setString(1, robotId)
            s.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }
}
