package dev.picasso.registry.catalog

import dev.picasso.registry.store.Db
import java.time.Instant

/**
 * 사이트 하나가 지금 할 수 있는 일 하나.
 *
 * **기체가 아니라 능력이 단위다**(§9.6). 상위 시스템은 "3번 로봇"이 아니라
 * "이 공장에서 `pick_place`가 되는가"를 알아야 하고, 기체 단위로 주면 그
 * 판단을 상위가 매번 다시 하게 된다 — 그러면 벤더 중립이 상위 코드에서
 * 무너진다.
 */
data class SiteCapability(
    val site: String,
    val skillType: String,
    val major: Int,
    /** 지금 이 능력을 실제로 제공하는 기체 수. **0이면 이 줄 자체가 없다.** */
    val availableRobots: Int,
    /** 계약 축과 프로파일 축 **두 값 중 이른 쪽**(§9.3). 없으면 null. */
    val deprecatedAfter: Instant?,
)

/**
 * §9.6의 사이트 카탈로그 집계.
 *
 * ## 한 대가 빠졌다고 능력이 사라지면 안 된다
 *
 * 카탈로그는 상위 시스템이 **라우팅 판단**에 쓰는 것이라, 흔들리면 상위가
 * 없는 장애에 반응한다. 그래서 이 표면의 단위는 기체가 아니라 능력이고,
 * 능력은 **가용 기체 수가 0이 될 때만** 사라진다. 세 대 중 한 대가 빠지면
 * 줄은 그대로 있고 [SiteCapability.availableRobots]만 3에서 2가 된다.
 *
 * ## 발행은 여기 없다
 *
 * §9.6은 이것을 `picasso/{major}/{site}/site/catalog`의 retain 스트림으로
 * 규정하지만 **브로커가 없다**(§15.30). 집계까지가 여기고, 발행은 안
 * 만들었다 — 만들면 아무 시험도 안 지나는 발행자가 생기고, 그것은 코드가
 * 아니라 주석이다.
 */
class SiteCatalog(private val db: Db) {

    /**
     * 그 사이트의 능력 목록.
     *
     * **활성 바인딩만 본다**(§9.1) — 해제된 것은 이력이라, 세면 이미 라인에서
     * 뺀 기체가 능력을 제공하는 것처럼 보인다.
     *
     * 폐기 예고의 프로파일 축은 **모든 제공 기체가 예고했을 때만** 값이 있고,
     * 그때 값은 그중 **가장 늦은** 것이다. 한 대라도 예고 없이 제공하고 있으면
     * 사이트는 그 날짜 이후에도 능력을 갖는다 — 여기서 이른 쪽을 취하면
     * 상위에게 아직 되는 능력을 안 된다고 알리게 된다.
     *
     * 계약 축은 사이트와 무관하게 전부에 걸리므로, 두 축을 합칠 때만
     * §9.3의 "이른 쪽"이 적용된다(`LEAST`는 NULL을 건너뛴다).
     */
    fun capabilities(site: String): List<SiteCapability> = db.open().use { c ->
        c.prepareStatement(
            """
            SELECT s.name, s.major,
                   count(DISTINCT b.robot_id) AS available,
                   LEAST(
                       CASE WHEN bool_or(ps.deprecated_after IS NULL) THEN NULL
                            ELSE max(ps.deprecated_after) END,
                       max(std.deprecated_after)
                   ) AS deprecated_after
            FROM robot_binding b
            JOIN robot r          ON r.robot_id = b.robot_id
            JOIN profile_skill ps ON ps.profile_revision_id = b.profile_revision_id
            JOIN skill_type s     ON s.skill_type_id = ps.skill_type_id
            LEFT JOIN skill_type_deprecation std ON std.skill_type_id = s.skill_type_id
            WHERE b.unbound_at IS NULL AND r.site_id = ?
            GROUP BY s.name, s.major
            ORDER BY s.name, s.major
            """.trimIndent(),
        ).use { st ->
            st.setString(1, site)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            SiteCapability(
                                site = site,
                                skillType = rs.getString(1),
                                major = rs.getInt(2),
                                availableRobots = rs.getInt(3),
                                deprecatedAfter = rs.getTimestamp(4)?.toInstant(),
                            ),
                        )
                    }
                }
            }
        }
    }
}
