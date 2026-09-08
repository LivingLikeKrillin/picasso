package dev.picasso.registry.ledger

import dev.picasso.profile.Requirement
import dev.picasso.registry.store.Db
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** §8.3의 `consumer.kind`. */
enum class ConsumerKind { CLIENT, UPSTREAM_SYSTEM }

/** §8.3 결정 6의 `source`. **둘은 공존한다.** */
enum class RequirementSource { DECLARED, OBSERVED }

/** 원장의 한 줄. */
data class RequirementRow(
    val consumerId: String,
    val skillTypeName: String,
    val source: RequirementSource,
    val versionRange: String,
    val active: Boolean,
    val lastSeen: Instant,
)

/** 진단 5번의 답 — **이 능력을 지금 누가 쓰는가.** */
data class DependentsAnswer(
    val skillTypeName: String,
    val active: List<RequirementRow>,
    /**
     * 비활성이 된 행. **0이 된 이유를 운영자가 갈라 봐야 한다** —
     * "아무도 안 쓴다"와 "다들 조용하다"는 다른 사실이고, 후자는 계절성
     * 소비자가 돌아올 수 있다는 뜻이다.
     */
    val dormant: List<RequirementRow>,
)

/**
 * §9.2의 의존 원장.
 *
 * ## 이 클래스의 한 문장
 *
 * **등록하지 않은 소비자도 잡힌다.** 그것이 `source`를 둘로 나눈 이유이고
 * (§8.3 결정 6), 원장이 비어 있을 수 없는 이유다. *"지금 `pick_place@1`을
 * 쓰는 소비자는 누구인가"가 추측에서 조회로 바뀐다.*
 *
 * ## 판정은 여기서 하지 않는다
 *
 * 거절을 적을 때와 같다 — **협상이 성공했는지 정하는 것은 `mimic`의
 * `Negotiator`**이고 여기서는 받아 적는다. 요구 문자열을 다시 파싱해
 * "이건 성공이었겠지"를 계산하면 원장이 로봇과 다른 말을 하는 날이 오고,
 * **완료 기준 19의 축소 판정이 그 원장을 본다.**
 */
class LedgerService(
    private val db: Db,
    /**
     * **주입 가능해야 한다.** 30일 감쇠를 시험하려고 30일을 기다리거나
     * DB를 손으로 고치게 만들면, 그 시험은 결국 안 쓰이게 된다.
     */
    private val now: () -> Instant = Instant::now,
) {

    /**
     * §9.2의 `POST /requirements` — 소비자가 자기 의존을 등록한다.
     *
     * **`registered=true`가 되고 `kind`·`display_name`이 갱신된다**(§8.3).
     * 관측으로 먼저 자동 생성된 소비자가 나중에 등록하는 것이 정상 경로다.
     *
     * @param requires `pick_place@^1.2` 형태의 요구 문자열들. **원문 그대로
     *   `version_range`에 남긴다** — 해석은 게이트가 하고 원장은 적는다.
     */
    fun declare(
        consumerId: String,
        kind: ConsumerKind,
        site: String,
        displayName: String,
        requires: List<String>,
    ): Int = db.transaction { c ->
        c.prepareStatement(
            """
            INSERT INTO consumer (consumer_id, kind, site, display_name, registered)
            VALUES (?, ?, ?, ?, true)
            ON CONFLICT (consumer_id) DO UPDATE
              SET kind = EXCLUDED.kind,
                  site = EXCLUDED.site,
                  display_name = EXCLUDED.display_name,
                  registered = true
            """.trimIndent(),
        ).use { s ->
            s.setString(1, consumerId); s.setString(2, kind.name)
            s.setString(3, site); s.setString(4, displayName)
            s.executeUpdate()
        }

        val written = requires.sumOf {
            upsertRequirement(c, consumerId, it, RequirementSource.DECLARED)
        }
        audit(c, consumerId, "REQUIREMENTS_DECLARE", requires.joinToString(","))
        written
    }

    /**
     * §5.4의 성공 보고 — **협상에 성공한 요구를 `OBSERVED`로 잡는다.**
     *
     * 미등록 소비자는 여기서 자동 생성된다: `kind=CLIENT`,
     * `site=토픽의 site`, `display_name=client_id`, `registered=false`(§8.3).
     * 그래서 **원장이 비어 있을 수 없다.**
     */
    fun observe(
        consumerId: String,
        site: String,
        requires: List<String>,
    ): Int = db.transaction { c ->
        // **등록 정보를 덮어쓰지 않는다.** 관측이 등록을 지우면 사람이
        // 적어 넣은 kind·display_name이 client_id로 되돌아간다.
        c.prepareStatement(
            """
            INSERT INTO consumer (consumer_id, kind, site, display_name, registered)
            VALUES (?, 'CLIENT', ?, ?, false)
            ON CONFLICT (consumer_id) DO NOTHING
            """.trimIndent(),
        ).use { s ->
            s.setString(1, consumerId); s.setString(2, site); s.setString(3, consumerId)
            s.executeUpdate()
        }

        requires.sumOf { upsertRequirement(c, consumerId, it, RequirementSource.OBSERVED) }
    }

    /**
     * 같은 (소비자, 스킬, source)면 **`last_seen`만 갱신한다.**
     *
     * 행을 새로 만들면 30일 감쇠가 언제나 갓 만들어진 행을 보고 아무것도
     * 비활성화하지 않는다 — 그러면 원장이 "다 살아 있다"고만 답한다.
     *
     * **다시 관측되면 되살아난다**(`active = true`). 비활성은 사형이
     * 아니라 조용함의 표시다.
     */
    private fun upsertRequirement(
        c: Connection,
        consumerId: String,
        requirement: String,
        source: RequirementSource,
    ): Int {
        val (skill, range) = split(requirement)
        return c.prepareStatement(
            """
            INSERT INTO consumer_requirement
                   (consumer_id, skill_type_name, source, version_range, first_seen, last_seen)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (consumer_id, skill_type_name, source) DO UPDATE
              SET last_seen = EXCLUDED.last_seen,
                  version_range = EXCLUDED.version_range,
                  active = true
            """.trimIndent(),
        ).use { s ->
            val at = now().atOffset(ZoneOffset.UTC)
            s.setString(1, consumerId); s.setString(2, skill)
            s.setString(3, source.name); s.setString(4, range)
            s.setObject(5, at); s.setObject(6, at)
            s.executeUpdate()
        }
    }

    /**
     * 요구 문자열을 스킬 이름과 범위로 가른다.
     *
     * **`@`가 없으면 통째로 스킬 이름이고 범위는 빈 문자열이다.** 던지지
     * 않는 이유는 원장이 관측을 **잃는 것보다 덜 정확한 채 남는 것**이
     * 낫기 때문이다 — 파싱 실패로 적재를 포기하면 §9.3의 조회가 그
     * 소비자를 0으로 세고, 그 위에서 축소가 승인된다.
     */
    private fun split(requirement: String): Pair<String, String> {
        val at = requirement.indexOf('@')
        return if (at < 0) {
            requirement to ""
        } else {
            requirement.substring(0, at) to requirement.substring(at + 1)
        }
    }

    /**
     * §9.2의 감쇠 — 기본 30일. **`active=false`로 내리고 삭제하지 않는다.**
     *
     * *"계절성 소비자를 지워버리면 원장이 거짓말을 한다."*
     *
     * **`DECLARED`도 감쇠한다.** 등록해 놓고 안 쓰는 것과 쓰는 것은 다르고,
     * 등록을 영구 면제로 두면 한 번 등록한 소비자가 영원히 축소를 막는다 —
     * 그러면 §9.3의 진입 조건이 관측이 아니라 서류가 된다.
     *
     * @return 이번에 내려간 행 수.
     */
    fun decay(olderThan: Duration = Duration.ofDays(30)): Int = db.transaction { c ->
        c.prepareStatement(
            "UPDATE consumer_requirement SET active = false WHERE active AND last_seen < ?",
        ).use { s ->
            s.setObject(1, now().minus(olderThan).atOffset(ZoneOffset.UTC))
            s.executeUpdate()
        }
    }

    /**
     * §9.3의 조회 1 — **이 능력을 요구하는 `active` 소비자 수.**
     *
     * `source`를 구분하지 않는다(§8.3 결정 6). 등록한 것과 관측된 것 중
     * 하나라도 살아 있으면 "사용 중"이다. 그래서 **소비자 단위로 센다** —
     * 같은 소비자가 두 source로 있다고 둘로 세면 축소가 더 막히는 쪽으로
     * 틀리지만, 화면의 숫자가 실제 소비자 수와 달라진다.
     */
    fun activeConsumerCount(skillTypeName: String): Int = activeConsumerCount(skillTypeName, null)

    /**
     * @param major `null`이면 major를 가리지 않는다.
     *
     * **major를 짚는 것이 축소 판정에서는 필수다.** 이름만 보면 다른 major를
     * 쓰는 소비자가 축소를 막고, 그러면 아무도 옮기지 못한다 — `pick_place@2`로
     * 옮기라고 예고해 놓고 `pick_place@1`의 제거가 `@2` 소비자 때문에 막히는
     * 모양이 된다.
     *
     * **major는 원장이 아니라 여기서 푼다.** §9.2가 *"원문 그대로
     * `version_range`에 남긴다 — 해석은 게이트가 하고 원장은 적는다"*고 정했고,
     * 이 메서드는 그 해석을 두 호출 지점(게이트 6번·변경 계획)에 **한 벌로**
     * 준다. 두 벌이면 CI가 통과시킨 축소를 레지스트리가 거부하는 날이 온다.
     *
     * **못 읽는 범위는 센다.** 문법이 깨진 요구를 조용히 빼면 그 소비자만
     * 모르는 채로 능력이 사라진다 — 파싱 실패는 "안 쓴다"의 증거가 아니다.
     */
    fun activeConsumerCount(skillTypeName: String, major: Int?): Int = db.open().use { c ->
        c.prepareStatement(
            "SELECT consumer_id, version_range FROM consumer_requirement " +
                "WHERE skill_type_name = ? AND active",
        ).use { s ->
            s.setString(1, skillTypeName)
            s.executeQuery().use { rs ->
                buildSet {
                    while (rs.next()) {
                        val consumerId = rs.getString(1)
                        if (major == null) {
                            add(consumerId)
                            continue
                        }
                        val range = rs.getString(2)
                        val wanted = try {
                            Requirement.parse("$skillTypeName@$range").major == major
                        } catch (_: IllegalArgumentException) {
                            true
                        }
                        if (wanted) add(consumerId)
                    }
                }.size
            }
        }
    }

    /**
     * §9.3의 조회 2 — 드레인. 그 `skill_type`의 **비종착** 태스크 수.
     *
     * [activeConsumerCount]와 같은 이유로 두 호출 지점이 이것을 공유한다.
     * `major`를 안 짚으면 다른 major의 태스크가 드레인을 영영 0이 안 되게
     * 만든다.
     */
    fun inflightTaskCount(skillTypeName: String, major: Int?): Int = db.open().use { c ->
        val sql = buildString {
            append(
                """
                SELECT count(*) FROM task t
                JOIN skill_type s ON s.skill_type_id = t.skill_type_id
                WHERE s.name = ? AND NOT t.terminal
                """.trimIndent(),
            )
            if (major != null) append(" AND s.major = ?")
        }
        c.prepareStatement(sql).use { s ->
            s.setString(1, skillTypeName)
            if (major != null) s.setInt(2, major)
            s.executeQuery().use { rs -> check(rs.next()); rs.getInt(1) }
        }
    }

    /** 진단 5번 — `GET /diag/dependents?skill=`. */
    fun dependents(skillTypeName: String): DependentsAnswer {
        val rows = db.open().use { c ->
            c.prepareStatement(
                """
                SELECT consumer_id, skill_type_name, source, version_range, active, last_seen
                FROM consumer_requirement
                WHERE skill_type_name = ?
                ORDER BY consumer_id, source
                """.trimIndent(),
            ).use { s ->
                s.setString(1, skillTypeName)
                s.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                RequirementRow(
                                    consumerId = rs.getString(1),
                                    skillTypeName = rs.getString(2),
                                    source = RequirementSource.valueOf(rs.getString(3)),
                                    versionRange = rs.getString(4),
                                    active = rs.getBoolean(5),
                                    lastSeen = rs.getTimestamp(6).toInstant(),
                                ),
                            )
                        }
                    }
                }
            }
        }
        return DependentsAnswer(
            skillTypeName,
            active = rows.filter { it.active },
            dormant = rows.filterNot { it.active },
        )
    }

    private fun audit(c: Connection, actor: String, operation: String, subject: String) =
        c.prepareStatement(
            "INSERT INTO audit_log (operation, actor, subject) VALUES (?, ?, ?)",
        ).use {
            it.setString(1, operation); it.setString(2, actor); it.setString(3, subject)
            it.executeUpdate()
        }
}
