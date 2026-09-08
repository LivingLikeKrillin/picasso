package dev.picasso.registry.ingest

import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.registry.store.Db
import java.time.Instant

sealed interface LivenessOutcome {
    data object Recorded : LivenessOutcome

    /** **사유를 남긴다.** 개수만 남기면 "왜 축소가 막혔나"에 답할 수 없다. */
    data class Rejected(val reason: String) : LivenessOutcome
}

/**
 * 기체가 살아 있다는 사실을 적재한다 — §9.3의 두 조회가 세기 전에 보는 것.
 *
 * ## 왜 이것이 따로 필요한가
 *
 * `task.updated_at`도 `consumer_requirement.last_seen`도 **기체가 살아 있다는
 * 증거가 못 된다.** 태스크를 한 번도 안 받은 기체는 행이 없고, 협상은
 * 소비자가 올 때만 일어난다. 정상일 때 **반드시** 갱신되는 것이어야
 * 관측선이다.
 *
 * ## 부르는 쪽이 아직 어댑터다
 *
 * §4.7의 `connection` 스트림이 같은 사실을 나르지만 브로커를 안 붙였다
 * (§15.30의 결정성). 입력을 [MessageHeader]와 [ConnectionState] — **계약
 * 타입** — 로 못박아 두면 브로커가 붙는 날 구독기가 이 서비스를 그대로
 * 부르면 되고, `registry`는 자기가 어댑터에게서 받았는지 브로커에게서
 * 받았는지 몰라도 된다(§3.2).
 *
 * ## 시각의 주인은 registry다
 *
 * 헤더의 `occurred_at`을 쓰지 않는다. §10.3이 `VIRTUAL` 시계를
 * `AdvanceClock`으로만 전진시키므로 그 값을 믿으면 하트비트가 임의로 낡거나
 * 임의로 신선해 보인다. 신선도 판정이 registry 쪽 `now()`로 이뤄지는 이상
 * 관측 시각도 같은 시계여야 한다.
 *
 * **그래서 이 관측은 폴백 파일에 남기지 않는다.** 되밀면 registry가 그것을
 * 지금 시각으로 적어 **죽은 기체를 살아 있다고 거짓말한다.** §15.45가 태스크
 * 관측을 남긴 것과 성질이 반대다 — 태스크 전이는 사실이라 늦게 와도 참이고,
 * 생존은 시점이 곧 내용이다. 실패하면 버리고 다음 발행에서 다시 온다.
 */
class LivenessService(
    private val db: Db,
    private val now: () -> Instant = Instant::now,
) {

    /**
     * @param software 기체가 보고한 로봇 소프트웨어 식별자. **못 읽는 기종이면
     *   `null`이고 빈 문자열이 아니다** — 신원 질의가 아예 없는 실물이 있다.
     */
    /**
     * @param siteNames 기체가 아는 사이트 이름의 요약(ADR 35). `null`이면
     *   **안 물어본 것**이고, 이미 받아 둔 값을 지우지 않는다.
     */
    fun record(
        header: MessageHeader,
        state: ConnectionState,
        software: String?,
        siteNames: SiteNameReport? = null,
    ): LivenessOutcome {
        val robotId = header.robotId
        if (robotId.isBlank()) return LivenessOutcome.Rejected("헤더에 robot_id가 없다")

        return db.transaction { c ->
            // **FK 위반을 그대로 터뜨리지 않는다.** 오타 난 기체는 운영자가
            // 알아야 할 사실이고, 예외로 나가면 적재 표면이 500을 내며
            // 사유가 사라진다.
            val known = c.prepareStatement("SELECT 1 FROM robot WHERE robot_id = ?").use { s ->
                s.setString(1, robotId)
                s.executeQuery().use { it.next() }
            }
            if (!known) {
                return@transaction LivenessOutcome.Rejected("등록되지 않은 기체다: $robotId")
            }

            c.prepareStatement(
                """
                INSERT INTO robot_liveness
                    (robot_id, last_reported_at, connection_state, capability_epoch, robot_software,
                     site_names_unsupported, site_names_count, site_names_reported_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (robot_id) DO UPDATE SET
                    last_reported_at = EXCLUDED.last_reported_at,
                    connection_state = EXCLUDED.connection_state,
                    -- **뒤로 안 간다.** 늦게 온 옛 epoch 가 새 값을 덮으면
                    -- 축소 완료 검증이 되돌아간다.
                    capability_epoch = GREATEST(
                        robot_liveness.capability_epoch, EXCLUDED.capability_epoch
                    ),
                    -- **못 읽는 보고가 이미 읽은 값을 지우지 않는다.**
                    robot_software = COALESCE(
                        EXCLUDED.robot_software, robot_liveness.robot_software
                    ),
                    -- **안 물어본 보고가 이미 받은 답을 지우지 않는다.**
                    -- 옛 어댑터가 섞여 도는 동안 그 보고마다 상태가 "모른다"로
                    -- 되돌아가면 등록 확인이 영원히 안 선다.
                    site_names_unsupported = COALESCE(
                        EXCLUDED.site_names_unsupported, robot_liveness.site_names_unsupported
                    ),
                    site_names_count = COALESCE(
                        EXCLUDED.site_names_count, robot_liveness.site_names_count
                    ),
                    site_names_reported_at = COALESCE(
                        EXCLUDED.site_names_reported_at, robot_liveness.site_names_reported_at
                    )
                """.trimIndent(),
            ).use { s ->
                s.setString(1, robotId)
                s.setTimestamp(2, java.sql.Timestamp.from(now()))
                s.setString(3, state.name)
                s.setLong(4, header.capabilityEpoch)
                s.setString(5, software)
                if (siteNames == null) {
                    s.setNull(6, java.sql.Types.BOOLEAN)
                    s.setNull(7, java.sql.Types.INTEGER)
                    s.setNull(8, java.sql.Types.TIMESTAMP)
                } else {
                    s.setBoolean(6, siteNames.unsupported)
                    s.setInt(7, siteNames.count)
                    s.setTimestamp(8, java.sql.Timestamp.from(now()))
                }
                s.executeUpdate()
            }
            LivenessOutcome.Recorded
        }
    }
}

/**
 * 기체가 보고한 사이트 이름 요약(ADR 35).
 *
 * **이름이 아니라 요약이다.** `registry`는 사이트 이름의 주인이 아니므로
 * 목록을 저장하지 않는다 — 저장하면 그 표가 두 번째 진실이 되고, 사이트에서
 * 이름을 바꾼 날 어느 쪽이 맞는지 정해져 있지 않다.
 *
 * [unsupported]와 `count == 0`을 접으면 안 된다. 앞은 등록할 자리가 없는
 * 기종이고 뒤는 자리는 있는데 비어 있는 것이다.
 */
data class SiteNameReport(val unsupported: Boolean, val count: Int)
