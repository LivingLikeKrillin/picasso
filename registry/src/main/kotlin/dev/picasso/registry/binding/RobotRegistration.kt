package dev.picasso.registry.binding

import dev.picasso.registry.store.Db
import java.sql.Connection
import java.time.Instant

/**
 * 기체를 원장에 들이는 두 문(ADR 37).
 *
 * ## 문이 곧 출처다
 *
 * **타입 필드를 본문에서 받지 않는다.** 어느 문으로 들어왔는가가 그 사실이며, 그래서 [declare]와 [discover]가 다른
 * 메서드다 — 하나에 `origin` 인자를 두면 부르는 쪽이 정하게 되고, 적재 토큰을 든 현장의 기체가 스스로 *"사람이
 * 선언했다"* 고 적을 수 있다. 그 승격은 `OperatorToken` 이 막으려던 바로 그것이다.
 *
 * | 출처 | 문 | 뜻 |
 * |---|---|---|
 * | **발견** — 어댑터가 플릿에 물어 올린다 | 적재(`IngestToken`) | *"기체가 자기 관측을 보고한다"* |
 * | **선언** — 사람이 화면에서 적는다 | 조작(`OperatorToken`) | *"사람이 판단을 기록한다"* |
 *
 * ## 사람의 말은 기체가 답할 때까지 CLAIMED다
 *
 * ADR 35가 사이트 이름에서 세운 규율을 그대로 쓴다 — 사람이 타이핑한 기체 식별자는 오타 하나를 아무도 못 잡고
 * 화면은 초록이다. 그래서 **선언된 기체는 [RobotStatus.CLAIMED]이고, 그 기체가 우리 계약으로 생존을 보고해야
 * [RobotStatus.CONFIRMED]가 된다.** 발견된 기체에는 그 단계가 없다: 어댑터가 플릿에서 본 것이므로 처음부터
 * 관측이다(그래도 계약으로 닿은 적 없는 것은 [RobotStatus.DISCOVERED]로 남는다 — 둘은 다른 사실이다).
 *
 * **확인을 따로 저장하지 않는다.** `robot_liveness` 행이 있으면 확인이다 — 상태를 열로 두면 두 번째 진실이 되고,
 * 생존 보고가 끊긴 기체를 누가 되돌려 놓을지가 정해져 있지 않다.
 *
 * ## 여기 없는 것
 *
 * **어댑터 인스턴스가 없다**(ADR 37 결정 2). 인스턴스는 *접속 설정을 갖는 것* 인데, 직결에서 그 설정은 기체의 것이라
 * [RobotRegistration]이 들고(`robot.endpoint`), 플릿 경유의 설정은 **플릿에 붙는 어댑터가 없어서** 들 것이 없다.
 * 아무도 안 쓰는 표를 먼저 만들지 않는다.
 */
class RobotRegistration(private val db: Db, private val now: () -> Instant = Instant::now) {

    /**
     * 사람이 기체를 **선언한다**(조작 문).
     *
     * @param endpoint 로봇의 네트워크 주소. 직결에서만 우리가 갖는다(결정 4). **자격증명은 안 받는다** — §6.3.
     *   비어 있는 것은 *플릿 경유* 와 *아직 안 적었다* 둘 다일 수 있고, 계약이 그 둘을 구별하지 않기로 했으므로
     *   (결정 1 — 붙은 층은 상류에 안 드러낸다) 우리도 여기서 구별하지 않는다.
     */
    fun declare(
        robotId: String,
        siteId: String,
        /** 선언에는 **요구한다** — 사람이 적는 경로에는 그것을 아는 사람이 있다(V13 의 CHECK 가 같이 든다). */
        serialNumber: String,
        displayName: String? = null,
        endpoint: String? = null,
        actor: String,
    ): RobotRegistrationOutcome = db.transaction { c ->
        register(c, robotId, siteId, serialNumber, displayName, endpoint, RobotOrigin.DECLARED, actor)
    }

    /**
     * 어댑터가 플릿에 물어 얻은 목록을 **올린다**(적재 문).
     *
     * @param siteId **어댑터가 배포된 사이트**다. ADR 37이 *"발견된 기체의 사이트 귀속을 누가 정하는가"* 를 미결로
     *   남겼고, 답은 이것이다 — 플릿은 우리 `site_id` 를 모르지만 **어댑터는 자기가 어느 사이트에 배포됐는지 안다.**
     *   핸드셰이크 보고가 `?site=` 를 싣는 것과 같은 자리이며, 그것이 어댑터의 신고라는 성질도 같다.
     *
     * **하나가 거절돼도 나머지는 들인다.** 목록 하나가 통째로 실패하면 플릿에 기체를 하나 더한 날 발견 전체가
     * 멈추고, 그 멈춤은 *"플릿에서 사라졌다"* 와 화면에서 구별되지 않는다.
     */
    /**
     * @param instanceId 올린 어댑터 인스턴스(ADR 37 결정 2). **자기 신고이고 선택이다** — 안 밝히면 널로 남고
     *   진단이 *"어느 어댑터인지 모른다"* 로 보여 준다. 밝히면 **실재해야 한다**: 모르는 이름이면 그 기체를
     *   거절한다. 그것이 ADR 37 의 절차(인스턴스를 먼저 등록하고 띄우면 로봇이 흘러 들어온다)를 표가 드는 방법이다.
     */
    fun discover(
        siteId: String,
        robots: List<DiscoveredRobot>,
        actor: String = INGEST_ACTOR,
        instanceId: String? = null,
    ): DiscoveryOutcome = db.transaction { c ->
        val recorded = mutableListOf<String>()
        val refused = linkedMapOf<String, String>()

        // **모르는 인스턴스가 올리면 목록 전체를 안 받는다.** 기체마다 거절하면 사유가 N 번 반복되고,
        // 원인이 기체가 아니라 발신자인 것이 안 보인다.
        if (instanceId != null) {
            val known = c.prepareStatement("SELECT 1 FROM adapter_instance WHERE instance_id = ?").use { s ->
                s.setString(1, instanceId)
                s.executeQuery().use { it.next() }
            }
            if (!known) {
                return@transaction DiscoveryOutcome(
                    emptyList(),
                    robots.associate { it.robotId to "등록되지 않은 어댑터 인스턴스가 올렸다: $instanceId" },
                )
            }
        }

        robots.forEach { robot ->
            // **발견된 기체의 접속 정보는 플릿이 갖는다**(결정 4). 조용히 버리지 않고 사유를 준다 — 조용히 버리면
            // 보낸 쪽은 우리가 그것을 안다고 믿는다.
            if (!robot.endpoint.isNullOrBlank()) {
                refused[robot.robotId] = "발견된 기체의 접속 정보는 플릿이 갖는다 — 여기로 보내지 않는다(ADR 37 결정 4)"
                return@forEach
            }
            when (val outcome = register(c, robot.robotId, siteId, robot.serialNumber, robot.displayName, null, RobotOrigin.DISCOVERED, actor, instanceId)) {
                is RobotRegistrationOutcome.Registered, is RobotRegistrationOutcome.Updated -> recorded += robot.robotId
                is RobotRegistrationOutcome.WrongDoor -> refused[robot.robotId] = outcome.detail
                // 퇴역한 기체가 아직 플릿에 보이는 것은 **정상이다** — 원장에서 내렸다고 현장에서 사라지지
                // 않는다. 사유를 주고 넘어가며, 그 어긋남은 진단이 따로 보여 준다.
                is RobotRegistrationOutcome.RetiredAlready -> refused[robot.robotId] = outcome.detail
                is RobotRegistrationOutcome.Rejected -> refused[robot.robotId] = outcome.detail
            }
        }
        DiscoveryOutcome(recorded, refused)
    }

    /**
     * 기체를 **퇴역시킨다** — 조작 문이다.
     *
     * ★**적재 문으로는 못 한다.** 어댑터가 *"플릿에서 안 보인다"* 고 해서 퇴역이 되면 **네트워크 단절이 퇴역이
     * 된다.** 안 보이는 것은 관측이고 떠난 것은 판단이며, 그 둘을 접으면 케이블 한 번 빠진 날 기체가 원장에서
     * 사라진다. ADR 37 이 들어오는 문을 가른 것과 같은 이유로 나가는 문은 하나다.
     *
     * **지우지 않는다.** 행은 남고 목록에서만 빠진다 — 태스크 관측·감사 로그·바인딩 이력이 이 기체에 매달려
     * 있고, 지난달 그 라인에서 무엇이 돌았는지 물으면 답이 있어야 한다.
     *
     * @param reason **요구한다.** 판단은 이유가 있어야 되짚을 수 있고, 감사 로그만 남기면 아무도 안 뒤진다.
     */
    fun retire(robotId: String, reason: String, actor: String): RetirementOutcome = db.transaction { c ->
        blank(robotId, "robot_id")?.let { return@transaction RetirementOutcome.Rejected(it.detail) }
        blank(reason, "reason")?.let { return@transaction RetirementOutcome.Rejected(it.detail) }
        blank(actor, "actor")?.let { return@transaction RetirementOutcome.Rejected(it.detail) }

        val existing = rowOf(c, robotId) ?: return@transaction RetirementOutcome.Unknown(robotId)
        // **이미 퇴역한 것을 다시 퇴역시켜도 첫 사유와 시각을 안 덮는다.** 덮으면 *"언제 떠났나"* 의 답이
        // 마지막으로 누른 버튼의 시각이 된다.
        if (existing.retiredAt != null) return@transaction RetirementOutcome.Retired(alreadyWas = true)

        c.prepareStatement(
            "UPDATE robot SET retired_at = ?, retired_by = ?, retired_reason = ? WHERE robot_id = ?",
        ).use { s ->
            s.setTimestamp(1, java.sql.Timestamp.from(now())); s.setString(2, actor)
            s.setString(3, reason); s.setString(4, robotId)
            s.executeUpdate()
        }
        audit(c, "ROBOT_RETIRED", actor, robotId, """{"reason":"${quoted(reason)}"}""")
        RetirementOutcome.Retired(alreadyWas = false)
    }

    /**
     * 퇴역을 **되돌린다** — 같은 조작 문이다.
     *
     * 되돌아온 기체는 실제로 있다. 다만 그것을 **어댑터가 정하게 두지 않는다**: 발견이 퇴역한 기체를 자동으로
     * 되살리면 운영자가 내린 판단을 현장의 프로세스가 매번 덮는다.
     */
    fun reinstate(robotId: String, actor: String): RetirementOutcome = db.transaction { c ->
        blank(robotId, "robot_id")?.let { return@transaction RetirementOutcome.Rejected(it.detail) }
        blank(actor, "actor")?.let { return@transaction RetirementOutcome.Rejected(it.detail) }

        val existing = rowOf(c, robotId) ?: return@transaction RetirementOutcome.Unknown(robotId)
        if (existing.retiredAt == null) return@transaction RetirementOutcome.Reinstated(wasRetired = false)

        c.prepareStatement(
            "UPDATE robot SET retired_at = NULL, retired_by = NULL, retired_reason = NULL WHERE robot_id = ?",
        ).use { s -> s.setString(1, robotId); s.executeUpdate() }
        audit(c, "ROBOT_REINSTATED", actor, robotId, """{"was_retired_at":"${existing.retiredAt}"}""")
        RetirementOutcome.Reinstated(wasRetired = true)
    }

    /** 이 기체의 등록 상태. 그런 기체가 없으면 `null` — 없는 것과 문 밖에서 들어온 것은 다르다. */
    fun statusOf(robotId: String): RobotStatus? = db.transaction { c -> rowOf(c, robotId)?.status }

    /**
     * 진단 9번이 읽는다.
     *
     * @param includeRetired 기본은 **현역만**이다. 퇴역이 쌓이면 목록의 대부분이 지난 것이 되고, 그러면
     *   아무도 그 목록을 안 읽는다. 이력을 볼 때만 켠다.
     */
    fun list(siteId: String? = null, includeRetired: Boolean = false): List<RegisteredRobot> = db.open().use { c ->
        val sql = buildString {
            append(
                """
                SELECT r.robot_id, r.site_id, r.serial_number, r.display_name, r.origin, r.endpoint,
                       r.registered_at, r.registered_by, l.last_reported_at, r.discovered_by,
                       r.retired_at, r.retired_by, r.retired_reason
                FROM robot r
                LEFT JOIN robot_liveness l ON l.robot_id = r.robot_id
                """.trimIndent(),
            )
            val where = buildList {
                if (siteId != null) add("r.site_id = ?")
                if (!includeRetired) add("r.retired_at IS NULL")
            }
            if (where.isNotEmpty()) append("\nWHERE " + where.joinToString(" AND "))
            append("\nORDER BY r.robot_id")
        }
        c.prepareStatement(sql).use { s ->
            if (siteId != null) s.setString(1, siteId)
            s.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val origin = rs.getString(5)?.let(RobotOrigin::valueOf)
                        val reported = rs.getTimestamp(9)?.toInstant()
                        val retired = rs.getTimestamp(11)?.toInstant()
                        add(
                            RegisteredRobot(
                                robotId = rs.getString(1),
                                siteId = rs.getString(2),
                                serialNumber = rs.getString(3),
                                displayName = rs.getString(4),
                                origin = origin,
                                endpoint = rs.getString(6),
                                registeredAt = rs.getTimestamp(7)?.toInstant()?.toString(),
                                registeredBy = rs.getString(8),
                                lastReportedAt = reported?.toString(),
                                status = statusOf(origin, answered = reported != null, retired = retired != null),
                                discoveredBy = rs.getString(10),
                                retiredAt = retired?.toString(),
                                retiredBy = rs.getString(12),
                                retiredReason = rs.getString(13),
                                // **운영자가 시각 둘을 눈으로 비교하게 두지 않는다.** 이 어긋남이 이 목록에서
                                // 가장 알아야 할 사실이다 — 원장에서 내렸는데 현장에서는 아직 보고가 온다.
                                reportingAfterRetirement = retired != null && reported != null && reported > retired,
                            ),
                        )
                    }
                }
            }
        }
    }

    // ── 안쪽

    private fun register(
        c: Connection,
        robotId: String,
        siteId: String,
        /**
         * **널일 수 있다 — 플릿이 안 주는 벤더가 있다**(§15.103, Orbit 의 `Robot` 에 일련번호가 없다).
         * 선언 경로는 [declare] 의 시그니처가 널을 못 넣게 막고, 표는 V13 의 CHECK 로 같은 것을 막는다.
         */
        serialNumber: String?,
        displayName: String?,
        endpoint: String?,
        origin: RobotOrigin,
        actor: String,
        /** 발견이면 올린 인스턴스. 선언이면 널이며 표의 CHECK 가 그것을 든다. */
        instanceId: String? = null,
    ): RobotRegistrationOutcome {
        blank(robotId, "robot_id")?.let { return it }
        blank(siteId, "site_id")?.let { return it }
        // 널은 *안 준다* 이고 빈 문자열은 *줬는데 비었다* 이다. 뒤엣것만 거절한다.
        if (serialNumber != null) blank(serialNumber, "serial_number")?.let { return it }
        blank(actor, "actor")?.let { return it }

        val existing = rowOf(c, robotId)

        // **퇴역한 기체는 어느 문으로도 다시 안 들어온다.** 발견이 되살리면 운영자의 판단을 현장 프로세스가
        // 매번 덮고, 선언이 되살리면 *"복귀" 라는 사건이 등록과 구별되지 않는다.* 복귀는 따로 누른다.
        if (existing?.retiredAt != null) {
            return RobotRegistrationOutcome.RetiredAlready(
                "${'$'}robotId 은 ${'$'}{existing.retiredAt} 에 퇴역한 기체다 — 되돌리려면 복귀시킨다",
            )
        }

        if (existing != null && existing.origin != null && existing.origin != origin) {
            // **출처가 두 번째 진실이 되면 "이 기체가 왜 여기 있는가" 에 답할 수 없다.** 갱신하지 않고 거절한다 —
            // 정말로 출처가 바뀐 것이라면 그것은 등록이 아니라 사람이 판단할 일이다.
            return RobotRegistrationOutcome.WrongDoor(
                existing.origin,
                "$robotId 은 이미 ${existing.origin.name} 로 들어온 기체다 — ${origin.name} 문으로 다시 들이지 않는다",
            )
        }

        // 다른 기체가 같은 (site, serial) 을 쓰고 있으면 UNIQUE 위반이 난다. 예외로 터뜨리면 표면이 500 을 내고
        // 사유가 사라진다 — 운영자가 알아야 할 사실이다.
        // 일련번호가 없으면 겹칠 것도 없다 — 표의 UNIQUE 도 널끼리는 안 부딪친다.
        val clash = serialNumber?.let {
            c.prepareStatement(
                "SELECT robot_id FROM robot WHERE site_id = ? AND serial_number = ? AND robot_id <> ?",
            ).use { s ->
                s.setString(1, siteId); s.setString(2, it); s.setString(3, robotId)
                s.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }
        if (clash != null) {
            return RobotRegistrationOutcome.Rejected("같은 사이트에 같은 일련번호를 쓰는 기체가 이미 있다: $clash")
        }

        val at = now()
        c.prepareStatement(
            """
            INSERT INTO robot (robot_id, site_id, serial_number, display_name, origin, endpoint, registered_at, registered_by, discovered_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (robot_id) DO UPDATE SET
                site_id = EXCLUDED.site_id,
                serial_number = EXCLUDED.serial_number,
                display_name = EXCLUDED.display_name,
                -- **출처는 첫 등록의 것이다.** 위에서 다른 문을 이미 막았으므로 여기 오는 것은 같은 문이거나
                -- 문 밖에서 들어와 출처가 비어 있던 행이다. 뒤엣것은 이 등록으로 문을 갖는다.
                origin = EXCLUDED.origin,
                endpoint = EXCLUDED.endpoint,
                registered_at = EXCLUDED.registered_at,
                registered_by = EXCLUDED.registered_by,
                -- **안 밝힌 발견이 이미 아는 것을 지우지 않는다.** 옛 배포가 섞여 도는 동안 그 보고마다
                -- 출처가 "모른다" 로 되돌아가면 아무것도 확정되지 않는다(생존 보고의 COALESCE 와 같은 규율).
                discovered_by = COALESCE(EXCLUDED.discovered_by, robot.discovered_by)
            """.trimIndent(),
        ).use { s ->
            s.setString(1, robotId); s.setString(2, siteId); s.setString(3, serialNumber)
            s.setString(4, displayName); s.setString(5, origin.name); s.setString(6, endpoint)
            s.setTimestamp(7, java.sql.Timestamp.from(at)); s.setString(8, actor)
            s.setString(9, instanceId)
            s.executeUpdate()
        }

        // 감사 단서. 행위자는 자기 신고라 부인방지가 아니다(§15.3).
        c.prepareStatement(
            "INSERT INTO audit_log (operation, actor, subject, after) VALUES (?, ?, ?, ?::jsonb)",
        ).use { s ->
            s.setString(1, if (origin == RobotOrigin.DECLARED) "ROBOT_DECLARED" else "ROBOT_DISCOVERED")
            s.setString(2, actor)
            s.setString(3, robotId)
            s.setString(4, """{"site":"$siteId","origin":"${origin.name}"}""")
            s.executeUpdate()
        }

        val status = statusOf(origin, answered = existing?.answered == true, retired = false)
        return if (existing == null) {
            RobotRegistrationOutcome.Registered(status)
        } else {
            RobotRegistrationOutcome.Updated(status)
        }
    }

    private fun blank(value: String, what: String): RobotRegistrationOutcome.Rejected? =
        if (value.isBlank()) RobotRegistrationOutcome.Rejected("$what 가 비었다") else null

    /**
     * 감사 로그의 JSON 에 사람이 친 문자열을 넣는다. **따옴표와 역슬래시를 지운다**(코드 34·92) — 이 값은
     * 단서이지 데이터가 아니라 몇 글자 잃어도 되고, 안 지우면 깨진 JSON 이 `?::jsonb` 에서 터진다.
     */
    private fun quoted(value: String): String = value.filter { it.code != 34 && it.code != 92 }

    private class Row(val origin: RobotOrigin?, val answered: Boolean, val retiredAt: Instant?) {
        val status: RobotStatus get() = statusOf(origin, answered, retiredAt != null)
    }

    private fun rowOf(c: Connection, robotId: String): Row? = c.prepareStatement(
        "SELECT r.origin, l.robot_id IS NOT NULL, r.retired_at FROM robot r " +
            "LEFT JOIN robot_liveness l ON l.robot_id = r.robot_id WHERE r.robot_id = ?",
    ).use { s ->
        s.setString(1, robotId)
        s.executeQuery().use { rs ->
            if (rs.next()) {
                Row(rs.getString(1)?.let(RobotOrigin::valueOf), rs.getBoolean(2), rs.getTimestamp(3)?.toInstant())
            } else {
                null
            }
        }
    }

    private fun audit(c: Connection, operation: String, actor: String, subject: String, after: String) {
        c.prepareStatement(
            "INSERT INTO audit_log (operation, actor, subject, after) VALUES (?, ?, ?, ?::jsonb)",
        ).use { s ->
            s.setString(1, operation); s.setString(2, actor); s.setString(3, subject); s.setString(4, after)
            s.executeUpdate()
        }
    }

    private companion object {
        /** 적재 문의 행위자. **사람 이름이 아니다** — 그 문에는 신원이 없고, 있는 척하면 감사 로그가 거짓말한다. */
        const val INGEST_ACTOR = "adapter-discovery"

        fun statusOf(origin: RobotOrigin?, answered: Boolean, retired: Boolean): RobotStatus = when {
            // **퇴역이 이긴다.** 그래도 `lastReportedAt` 은 그대로 나가므로, 퇴역한 뒤에도 보고가 오는 것은
            // 목록에서 그대로 보인다 — 그것이 운영자가 알아야 할 사실이다(원장은 뺐는데 현장에는 있다).
            retired -> RobotStatus.RETIRED
            answered -> RobotStatus.CONFIRMED
            origin == RobotOrigin.DECLARED -> RobotStatus.CLAIMED
            origin == RobotOrigin.DISCOVERED -> RobotStatus.DISCOVERED
            else -> RobotStatus.UNREGISTERED
        }
    }
}

/** 로봇 목록을 누가 만들었는가(ADR 37 결정 3). **본문이 아니라 문이 정한다.** */
enum class RobotOrigin { DECLARED, DISCOVERED }

/**
 * 이 기체가 원장에 어떻게 있는가.
 *
 * **CLAIMED 와 CONFIRMED 를 접으면 안 된다** — 앞은 사람이 그렇다고 말한 것이고 뒤는 기체가 우리 계약으로 답한
 * 것이다. 접으면 자기 신고가 관측인 척하고, 오타 난 `robot_id` 로 선언한 기체가 영영 초록으로 보인다(ADR 35와
 * 같은 규율).
 *
 * **DISCOVERED 와 CLAIMED 도 다르다** — 앞은 어댑터가 플릿에서 본 것이라 이미 관측이고, 뒤는 사람의 말뿐이다.
 */
enum class RobotStatus {
    /** 행은 있는데 **어느 문도 안 지났다.** 시험 픽스처가 SQL 로 넣은 행이 그렇다 — 보이는 편이 낫다. */
    UNREGISTERED,

    /** 사람이 선언했고 **기체는 아직 우리 계약으로 답한 적이 없다.** */
    CLAIMED,

    /** 어댑터가 플릿에서 발견해 올렸고, 아직 그 기체가 우리 계약으로 답한 적은 없다. */
    DISCOVERED,

    /** **그 기체가 생존을 보고했다.** 출처와 무관하게 확인이다. */
    CONFIRMED,

    /**
     * **사람이 이 기체를 원장에서 내렸다.** 행은 남아 있고 목록에서만 빠진다.
     *
     * 이 상태가 다른 셋을 덮는다. 다만 `lastReportedAt` 은 안 지우므로 **퇴역 뒤에도 보고가 오는 것**이 목록에
     * 그대로 보인다 — 원장에서는 내렸는데 현장에는 아직 있다는 뜻이고, 그것은 운영자가 알아야 할 어긋남이다.
     */
    RETIRED,
}

/** 어댑터가 플릿에서 본 기체 하나. [endpoint]가 있으면 거절한다 — 그 정보는 플릿의 것이다(결정 4). */
data class DiscoveredRobot(
    val robotId: String,
    /** **널이면 플릿이 안 준 것이다**(§15.103). 지어내지 않는다 — 주소를 일련번호 자리에 넣으면 거짓말이 된다. */
    val serialNumber: String? = null,
    val displayName: String? = null,
    val endpoint: String? = null,
)

data class DiscoveryOutcome(val recorded: List<String>, val refused: Map<String, String>)

/** 진단 9번의 한 줄. */
data class RegisteredRobot(
    val robotId: String,
    val siteId: String,
    /** **널이면 플릿이 안 준 것이다**(§15.103). 선언된 기체는 언제나 있다 — V13 의 CHECK 가 그것을 든다. */
    val serialNumber: String?,
    val displayName: String?,
    val origin: RobotOrigin?,
    val endpoint: String?,
    val registeredAt: String?,
    val registeredBy: String?,
    val lastReportedAt: String?,
    val status: RobotStatus,
    /** 이 기체를 올린 어댑터 인스턴스. **널은 사람이 선언했거나 올린 쪽이 자기를 안 밝힌 것이다.** */
    val discoveredBy: String? = null,
    /** 널이면 현역이다. 그 뜻이 하나뿐이라 `origin` 의 널과 다르다. */
    val retiredAt: String? = null,
    val retiredBy: String? = null,
    val retiredReason: String? = null,
    /** **퇴역시킨 뒤에도 이 기체가 보고를 보내고 있다.** 원장과 현장이 어긋난 것이고 사람이 볼 일이다. */
    val reportingAfterRetirement: Boolean = false,
)

sealed interface RobotRegistrationOutcome {
    data class Registered(val status: RobotStatus) : RobotRegistrationOutcome
    data class Updated(val status: RobotStatus) : RobotRegistrationOutcome

    /** 다른 문으로 이미 들어온 기체다. **갱신하지 않는다** — 출처가 두 벌이 되면 "왜 여기 있는가" 에 못 답한다. */
    data class WrongDoor(val origin: RobotOrigin, val detail: String) : RobotRegistrationOutcome

    /** 퇴역한 기체다. **되살리는 것은 등록이 아니라 복귀이고, 복귀는 사람이 따로 누른다.** */
    data class RetiredAlready(val detail: String) : RobotRegistrationOutcome

    data class Rejected(val detail: String) : RobotRegistrationOutcome
}

/** 퇴역·복귀의 답. **모르는 기체와 이미 그 상태인 것을 안 접는다** — 앞은 운영자가 잘못 친 것이고 뒤는 성공이다. */
sealed interface RetirementOutcome {
    /** @param alreadyWas 이미 퇴역해 있었다. 첫 사유와 시각을 안 덮었다. */
    data class Retired(val alreadyWas: Boolean) : RetirementOutcome

    /** @param wasRetired 정말로 퇴역 상태였다. `false` 면 아무것도 안 바뀌었다. */
    data class Reinstated(val wasRetired: Boolean) : RetirementOutcome

    data class Unknown(val robotId: String) : RetirementOutcome
    data class Rejected(val detail: String) : RetirementOutcome
}
