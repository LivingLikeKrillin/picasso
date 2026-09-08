package dev.picasso.registry.binding

import dev.picasso.registry.store.Db
import java.time.Instant

/**
 * 바인딩된 기체가 **사이트의 이름들을 아는가**(ADR 35의 결정 3).
 *
 * ## `registry`는 이름을 갖지 않는다
 *
 * ADR 34가 결속의 표를 어댑터에서 뺐고, ADR 35가 *"사이트 이름은 로봇 안에
 * 산다"* 로 주인을 정했다. `registry`가 그 표를 들면 §3.2가 깨진다 — 누군가
 * 로봇에 밀어야 하는데 *"어느 모듈에도 직접 밀지 않는다"* 가 그것을 막는다.
 *
 * 그래서 **데이터를 안 갖고 상태만 갖는다.** 이 클래스가 그 상태다.
 *
 * ## 무엇을 등록해야 하는지는 **유도한다**
 *
 * §15.68이 이것 때문에 열려 있었다. 기종마다 등록할 집합이 다르므로
 * (Spot은 장소, Digit은 장소와 집을 물체와 놓을 곳, G1은 없음) 불리언 하나로는
 * *"등록했다"* 가 기종마다 다른 것을 뜻하게 된다.
 *
 * 답은 손으로 적는 목록이 아니라 유도다 — **그 기체가 바인딩한 프로파일이
 * 선언한 스킬들의 시맨틱 파라미터**가 곧 등록 대상이다. 계약이
 * `is_site_reference`로 어느 파라미터가 사이트 이름인지 말하고
 * (`skill_type_param.site_reference`), 프로파일이 어느 스킬을 드는지 말한다.
 *
 * 그래서 프로파일이 스킬을 하나 더하면 등록 대상이 저절로 늘고, **아무도
 * 목록을 고치지 않는다.**
 *
 * ## §9.7 ④와 같은 판단이다
 *
 * 등록하지 않고 바인딩하는 것을 **막지 않는다.** 대신 진단에 보인다 —
 * *"우리는 아직 안 했다가 화면에 보여야 정직하다."* 막으면 이름이 필요 없는
 * 조작(`move_relative`만 쓰는 라인)까지 세운다.
 */
class SiteNameRegistration(private val db: Db, private val now: () -> Instant = Instant::now) {

    /**
     * 이 기체가 알아야 하는 사이트 이름 파라미터들.
     *
     * 활성 바인딩이 없으면 빈 집합이다 — 바인딩이 없으면 등록할 대상도 없다.
     */
    fun required(robotId: String): Set<String> = db.transaction { c ->
        c.prepareStatement(
            """
            SELECT DISTINCT p.key
            FROM robot_binding b
            JOIN profile_skill s ON s.profile_revision_id = b.profile_revision_id
            JOIN skill_type_param p ON p.skill_type_id = s.skill_type_id
            WHERE b.robot_id = ? AND b.unbound_at IS NULL AND p.site_reference
            ORDER BY p.key
            """.trimIndent(),
        ).use { st ->
            st.setString(1, robotId)
            st.executeQuery().use { rs ->
                buildSet { while (rs.next()) add(rs.getString(1)) }
            }
        }
    }

    /** 이 기체의 등록 상태. */
    fun statusOf(robotId: String): SiteNameStatus {
        // **요구 집합을 먼저 본다.** 비어 있으면 등록 여부를 묻는 것 자체가
        // 뜻이 없다 — G1처럼 시맨틱 스킬을 하나도 안 드는 기종이 그렇다.
        if (required(robotId).isEmpty()) return SiteNameStatus.NOT_REQUIRED

        val recorded = db.transaction { c ->
            c.prepareStatement(
                "SELECT site_names_registered_at IS NOT NULL FROM robot_binding " +
                    "WHERE robot_id = ? AND unbound_at IS NULL",
            ).use { st ->
                st.setString(1, robotId)
                st.executeQuery().use { rs -> if (rs.next()) rs.getBoolean(1) else null }
            }
        } ?: return SiteNameStatus.NOT_REQUIRED

        return if (recorded) SiteNameStatus.REGISTERED else SiteNameStatus.UNREGISTERED
    }

    /**
     * 등록했다고 기록한다.
     *
     * **`registry`가 등록을 하는 것이 아니다.** 사람이 사이트 작업으로 하고
     * 여기에 그 사실만 적는다 — 이 클래스가 로봇에 무언가 밀면 §3.2가 깨진다.
     *
     * **요구 집합이 비어 있으면 거절한다.** 등록할 것이 없는데 등록했다고
     * 적으면 그 기록이 나중에 "이 기종은 이름을 안다"로 읽힌다.
     *
     * @return 기록했으면 true. 활성 바인딩이 없거나 요구 집합이 비면 false.
     */
    fun record(robotId: String, actor: String): Boolean {
        if (required(robotId).isEmpty()) return false

        return db.transaction { c ->
            c.prepareStatement(
                "UPDATE robot_binding SET site_names_registered_at = ?, site_names_registered_by = ? " +
                    "WHERE robot_id = ? AND unbound_at IS NULL",
            ).use { st ->
                st.setTimestamp(1, java.sql.Timestamp.from(now()))
                st.setString(2, actor)
                st.setString(3, robotId)
                st.executeUpdate()
            } > 0
        }
    }
}

/**
 * 등록 상태 셋.
 *
 * **`NOT_REQUIRED`와 `UNREGISTERED`를 접으면 안 된다.** 접으면 이름을 쓸 일이
 * 없는 기종이 영원히 "안 했다"로 보이고, 그러면 화면이 언제나 빨개서 아무도
 * 안 본다 — §15.47이 `liveness`를 다섯으로 나눈 것과 같은 이유다.
 */
enum class SiteNameStatus {
    /** 이 기체가 드는 스킬 중 사이트 이름을 쓰는 것이 없다. */
    NOT_REQUIRED,

    /** 써야 하는데 등록했다는 기록이 없다. **기본값이며 그것이 요점이다.** */
    UNREGISTERED,

    /** 사람이 등록했다고 기록했다. */
    REGISTERED,
}
