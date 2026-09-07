package dev.picasso.registry.ledger

import dev.picasso.gate.input.LedgerAnswer
import dev.picasso.gate.input.LedgerQuery
import dev.picasso.profile.Requirement
import dev.picasso.registry.store.Db
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.Instant

/**
 * 게이트 검사 6번의 축소 판정에 **진짜 원장을 물린다**(§9.3).
 *
 * ## 여기가 이 저장소의 두 주장이 만나는 자리다
 *
 * 검사 6번은 3a까지 축소를 **분류만** 했다 — *"원장이 없어 §9.3의 두 조회를
 * 하지 못했다"*. 그 문장이 이 클래스로 바뀐다: *"소비자가 남아 있으므로
 * 막는다."* 같은 문서, 같은 검사, **다른 원장**이면 다른 판정이다.
 *
 * ## "0"과 "모른다"를 접으면 이 클래스는 해로워진다
 *
 * §3.2가 규정한 실패 방식이 이렇다 — 브로커 구독이 끊기면 관측 표가 비고,
 * **빈 표는 정확히 0을 돌려준다.** 그 0을 [LedgerAnswer.Observed]로 내면
 * 검사 6번은 §9.3의 진입 조건이 충족됐다고 읽고, **아직 쓰는 소비자가 살아
 * 있는 능력의 제거를 승인한다.**
 *
 * 그래서 개수를 세기 전에 **관측선이 살아 있는지부터 본다.** 살아 있다는
 * 증거는 표 자신이다:
 *
 *  - `consumer_requirement.last_seen`의 최댓값 — 협상이 성공할 때마다 갱신된다.
 *  - `task.updated_at`의 최댓값 — 태스크 상태가 움직일 때마다 갱신된다.
 *
 * **`active`를 안 가린다.** 워터마크가 재는 것은 소비자의 수가 아니라
 * **관측선이 살아 있는가**이고, 세려는 그 술어로 증거를 걸러 내면
 * 마지막 소비자가 감쇠한 순간 원장이 스스로를 못 보게 된다.
 *
 * 그 최댓값이 **없거나**([freshness]보다 오래됐으면) 답은
 * [LedgerAnswer.NotObservable]이다. 워터마크 표를 따로 두지 않은 것은
 * 의도다 — 관측선이 살아 있다는 사실을 관측 자신과 **다른 곳**에 적으면,
 * 그 다른 곳을 갱신하는 코드가 빠진 날 원장이 살아 있다고 거짓말한다.
 *
 * ## 틀리는 방향이 정해져 있다
 *
 * 창이 너무 짧아 생기는 오답은 "모른다"이고, 그것은 축소를 **막는다.**
 * 창이 너무 길어 생기는 오답은 죽은 구독을 살아 있다고 보는 것이고, 그것은
 * 축소를 **연다.** 그러므로 애매하면 짧은 쪽이다.
 */
class RegistryLedgerQuery(
    private val db: Db,
    /**
     * **기체 단위 관측선.** 전역 워터마크보다 먼저 본다 — 아래 KDoc의
     * "관측선이 살아 있는가"는 옳지만 그 범위가 전역 하나였고, 그러면
     * 열 대 중 하나가 조용해져도 원장이 신선하다고 답한다.
     */
    private val robots: RobotObservability = RobotObservability(db),
    /**
     * 관측선이 살아 있다고 인정하는 최대 침묵.
     *
     * 24시간인 이유는 **가동 중인 라인이 하루 한 번도 협상하지 않거나 태스크
     * 하나 안 움직이는 일은 없기** 때문이다. 더 짧게 잡으면 야간 정지에도
     * "모른다"가 나오는데, 그 오답은 축소를 막을 뿐이라 안전한 쪽이다 —
     * 그래도 매번 막히면 사람이 창을 늘리는 대신 검사를 끄게 된다.
     */
    private val freshness: Duration = DEFAULT_FRESHNESS,
    private val now: () -> Instant = Instant::now,
) : LedgerQuery {

    override fun activeConsumers(skillType: String, major: Int): LedgerAnswer =
        gated(skillType, major, "consumer_requirement", "MAX(last_seen) FROM consumer_requirement") { c ->
            countConsumers(c, skillType, major)
        }

    override fun inflightTasks(skillType: String, major: Int): LedgerAnswer =
        gated(skillType, major, "task", "MAX(updated_at) FROM task") { c ->
            countInflight(c, skillType, major)
        }

    /**
     * **기체 관측선을 먼저 보고, 그것이 답하지 못할 때만 전역 워터마크로
     * 내려간다.**
     *
     * 전역 규칙을 지우지 않은 것은 [Observability.NoProvider] 때문이다 —
     * 그 능력을 제공하는 활성 바인딩이 하나도 없으면 볼 기체가 없고, 그때는
     * 표 자신이 유일한 증거다. 둘을 접으면 아직 아무 기체도 안 붙인 스킬의
     * 축소가 영원히 막힌다.
     */
    private fun gated(
        skillType: String,
        major: Int,
        stream: String,
        watermarkSql: String,
        count: (Connection) -> Int,
    ): LedgerAnswer = when (val seen = robots.of(skillType, major)) {
        is Observability.Blind -> LedgerAnswer.NotObservable(seen.reason)

        // **개수는 세되 근거 시각은 가장 뒤처진 기체의 것을 쓴다.**
        is Observability.Live -> try {
            db.open().use { c -> LedgerAnswer.Observed(count(c), seen.asOf) }
        } catch (e: SQLException) {
            LedgerAnswer.NotObservable("원장에 닿지 못했다: ${e.message}")
        }

        Observability.NoProvider -> answer(stream, watermarkSql, count)
    }

    /**
     * 워터마크를 먼저 보고, 살아 있을 때만 센다.
     *
     * `asOf`가 **지금이 아니라 워터마크**인 것이 중요하다. 개수는 방금 셌지만
     * 그 개수를 믿을 수 있는 근거는 마지막 관측 시각이다. 지금 시각을 실으면
     * 하루 묵은 원장이 방금 관측한 것처럼 보인다.
     */
    private fun answer(
        stream: String,
        watermarkSql: String,
        count: (Connection) -> Int,
    ): LedgerAnswer = try {
        db.open().use { c ->
            val watermark = watermark(c, watermarkSql)
            val age = watermark?.let { Duration.between(it, now()) }
            when {
                watermark == null ->
                    LedgerAnswer.NotObservable(
                        "$stream 에 관측이 하나도 없다 — 0이 아니라 구독이 없는 것이다",
                    )

                age!! > freshness ->
                    LedgerAnswer.NotObservable(
                        "$stream 의 마지막 관측이 ${age.toHours()}시간 전이다 " +
                            "(허용 ${freshness.toHours()}시간) — 구독이 끊겼을 수 있다",
                    )

                else -> LedgerAnswer.Observed(count(c), watermark)
            }
        }
    } catch (e: SQLException) {
        // 닿지 못한 것을 0으로 접지 않는다. **이 catch가 이 클래스의 요점이다.**
        LedgerAnswer.NotObservable("원장에 닿지 못했다: ${e.message}")
    }

    private fun watermark(c: Connection, sql: String): Instant? =
        c.prepareStatement("SELECT $sql").use { s ->
            s.executeQuery().use { rs ->
                check(rs.next())
                rs.getTimestamp(1)?.toInstant()
            }
        }

    /**
     * 그 능력을 요구하는 **서로 다른 소비자** 수. `source`를 안 가린다 —
     * 등록이든 관측이든 하나라도 살아 있으면 사용 중이다(§8.3 결정 6).
     *
     * major는 원장이 아니라 **여기서** 푼다. §9.2가 *"원문 그대로
     * `version_range`에 남긴다 — 해석은 게이트가 하고 원장은 적는다"*고
     * 정했고, 이 클래스는 원장이 아니라 게이트 쪽 어댑터다.
     *
     * **못 읽는 범위는 센다.** 문법이 깨진 요구를 조용히 빼면 그 소비자만
     * 모르는 채로 능력이 사라진다 — 파싱 실패는 "안 쓴다"의 증거가 아니다.
     */
    private fun countConsumers(c: Connection, skillType: String, major: Int): Int =
        c.prepareStatement(
            """
            SELECT consumer_id, version_range FROM consumer_requirement
            WHERE skill_type_name = ? AND active
            """.trimIndent(),
        ).use { s ->
            s.setString(1, skillType)
            s.executeQuery().use { rs ->
                buildSet {
                    while (rs.next()) {
                        val range = rs.getString(2)
                        val wanted = try {
                            Requirement.parse("$skillType@$range").major == major
                        } catch (_: IllegalArgumentException) {
                            true
                        }
                        if (wanted) add(rs.getString(1))
                    }
                }.size
            }
        }

    /**
     * 비종착 태스크 수. **`skill_type`의 `major`까지 짚는다** — 이름만 보면
     * 다른 major의 태스크가 축소를 막고, 그러면 아무도 못 옮긴다.
     */
    private fun countInflight(c: Connection, skillType: String, major: Int): Int =
        c.prepareStatement(
            """
            SELECT count(*) FROM task t
            JOIN skill_type s ON s.skill_type_id = t.skill_type_id
            WHERE s.name = ? AND s.major = ? AND NOT t.terminal
            """.trimIndent(),
        ).use { s ->
            s.setString(1, skillType)
            s.setInt(2, major)
            s.executeQuery().use { rs -> check(rs.next()); rs.getInt(1) }
        }

    companion object {
        val DEFAULT_FRESHNESS: Duration = Duration.ofHours(24)
    }
}
