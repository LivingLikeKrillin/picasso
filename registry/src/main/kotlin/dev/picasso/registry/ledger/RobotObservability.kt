package dev.picasso.registry.ledger

import dev.picasso.registry.store.Db
import java.sql.SQLException
import java.time.Duration
import java.time.Instant

/**
 * 그 능력을 제공하는 기체들의 관측선 상태.
 *
 * **`Live`와 `Blind` 사이에 `NoProvider`가 따로 있는 것이 요점이다.** 제공하는
 * 기체가 하나도 없다는 것은 "관측선이 끊겼다"가 아니라 **판정할 대상이 없다**는
 * 뜻이고, 둘을 접으면 아직 아무 기체도 안 붙인 스킬의 축소가 영원히 막힌다.
 */
sealed interface Observability {

    /** @param asOf 가장 **뒤처진** 기체의 마지막 보고. 개수를 믿을 근거다. */
    data class Live(val asOf: Instant) : Observability

    data class Blind(val reason: String) : Observability

    /** 그 능력을 제공하는 활성 바인딩이 없다. 부르는 쪽이 자기 폴백을 쓴다. */
    data object NoProvider : Observability
}

/**
 * §9.3의 두 조회가 **세기 전에** 보는 것 — 그 능력을 아직 돌리는 기체들이
 * 지금도 보고하고 있는가.
 *
 * ## 왜 전역 워터마크로는 안 되는가
 *
 * `RegistryLedgerQuery`는 원래 `MAX(last_seen)`·`MAX(updated_at)` **전역
 * 하나**로 관측선을 판정했다. 그 판정은 기체가 여럿일 때 무너진다 — 열 대 중
 * 아홉이 보고하는 한 워터마크가 신선하고, **조용해진 한 대가 아직 돌리는
 * 능력이 "쓰는 사람 0명"으로 제거된다.**
 *
 * §15.41이 *"틀리는 방향을 정해 둔다"*고 했고 그 방향은 **막는 쪽**이었다.
 * 전역 워터마크는 정확히 그 규율이 새는 자리다.
 *
 * ## 최댓값이 아니라 최솟값이다
 *
 * [Observability.Live.asOf]가 가장 뒤처진 보고인 것이 이 클래스의 절반이다.
 * 최댓값을 실으면 조용한 기체가 있어도 방금 관측한 것처럼 보이고, 그러면
 * 이 클래스를 붙인 의미가 없다.
 *
 * ## `HIBERNATING`은 살아 있는 것으로 센다
 *
 * §4.7이 `HIBERNATING`을 *"연결됐지만 의도적으로 상태를 발행하지 않음"*이라
 * 정의했다. 그것을 침묵으로 읽으면 **계약이 "침묵하지만 정상"을 표현하려고
 * 만든 상태가 모든 축소를 영구히 막는 차단자**가 된다.
 *
 * 다만 상한을 둔다([hibernationFactor]). 어댑터가 `HIBERNATING`을 보고하고
 * 죽으면 그 기체가 영원히 면제되는데, 그 오답은 축소를 **여는** 쪽이라
 * §15.41의 보수성과 어긋난다.
 */
class RobotObservability(
    private val db: Db,
    private val freshness: Duration = DEFAULT_FRESHNESS,
    /** `HIBERNATING`에 허용하는 침묵 = [freshness] × 이 값. */
    private val hibernationFactor: Long = DEFAULT_HIBERNATION_FACTOR,
    private val now: () -> Instant = Instant::now,
) {

    /**
     * @param major null이면 major를 가리지 않는다. `Preconditions`의 두 조회가
     *   스킬 이름만 보기 때문이며, 그 차이를 여기서 없애지 않는다 — 판정
     *   범위를 바꾸는 것은 이 클래스의 일이 아니다.
     */
    fun of(skillType: String, major: Int? = null): Observability = try {
        db.open().use { c ->
            val sql = buildString {
                append(
                    """
                    SELECT r.robot_id, l.last_reported_at, l.connection_state
                    FROM robot_binding b
                    JOIN robot r ON r.robot_id = b.robot_id
                    JOIN profile_skill ps ON ps.profile_revision_id = b.profile_revision_id
                    JOIN skill_type s ON s.skill_type_id = ps.skill_type_id
                    LEFT JOIN robot_liveness l ON l.robot_id = r.robot_id
                    WHERE s.name = ? AND b.unbound_at IS NULL
                    """.trimIndent(),
                )
                if (major != null) append(" AND s.major = ?")
            }

            val rows = c.prepareStatement(sql).use { s ->
                s.setString(1, skillType)
                if (major != null) s.setInt(2, major)
                s.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                Row(
                                    rs.getString(1),
                                    rs.getTimestamp(2)?.toInstant(),
                                    rs.getString(3),
                                ),
                            )
                        }
                    }
                }
            }

            judge(rows)
        }
    } catch (e: SQLException) {
        // 닿지 못한 것을 "제공자 없음"으로 접지 않는다. 그렇게 접으면 부르는
        // 쪽이 전역 폴백으로 흘러 축소가 열린다.
        // **사유를 원장 쪽과 같은 말로 낸다.** 같은 원인(DB 불통)에 두 가지
        // 말이 나오면 운영자가 서로 다른 고장으로 읽는다.
        Observability.Blind("원장에 닿지 못했다: ${e.message}")
    }

    private data class Row(val robotId: String, val at: Instant?, val state: String?)

    private fun judge(rows: List<Row>): Observability {
        if (rows.isEmpty()) return Observability.NoProvider

        val never = rows.filter { it.at == null }.map { it.robotId }
        if (never.isNotEmpty()) {
            return Observability.Blind(
                "바인딩됐는데 한 번도 보고한 적 없는 기체가 있다: ${never.sorted()}",
            )
        }

        val dead = rows.filter { it.state in DEAD_STATES }.map { it.robotId }
        if (dead.isNotEmpty()) {
            return Observability.Blind("연결이 끊긴 기체가 있다: ${dead.sorted()}")
        }

        val at = now()
        val hibernationWindow = freshness.multipliedBy(hibernationFactor)

        val stale = rows.filter { row ->
            val age = Duration.between(row.at, at)
            val window = if (row.state == HIBERNATING) hibernationWindow else freshness
            age > window
        }
        if (stale.isNotEmpty()) {
            // **어느 기체인지 적는다.** 사유가 없으면 운영자가 원장이 아니라
            // 엉뚱한 것을 뒤진다(§15.45가 폴백 파일에 사유를 남긴 것과 같은 판단).
            val names = stale.map { it.robotId }.sorted()
            return Observability.Blind(
                "보고가 끊긴 기체가 있다: $names " +
                    "(허용 ${freshness.toHours()}시간, 절전은 ${hibernationWindow.toHours()}시간)",
            )
        }

        // **가장 뒤처진 것이 근거다.**
        return Observability.Live(rows.minOf { it.at!! })
    }

    companion object {
        val DEFAULT_FRESHNESS: Duration = Duration.ofHours(24)

        /**
         * 절전에 허용하는 침묵의 배수. **크게 잡지 않는다** — 이 창이 길수록
         * `HIBERNATING`을 보고하고 죽은 어댑터가 오래 면제되고, 그 오답은
         * 축소를 여는 쪽이다.
         */
        const val DEFAULT_HIBERNATION_FACTOR: Long = 3

        private const val HIBERNATING = "CONNECTION_STATE_HIBERNATING"

        private val DEAD_STATES = setOf(
            "CONNECTION_STATE_OFFLINE",
            "CONNECTION_STATE_CONNECTION_BROKEN",
            "CONNECTION_STATE_UNSPECIFIED",
        )
    }
}
