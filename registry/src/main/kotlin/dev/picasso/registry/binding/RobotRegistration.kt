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
                is RobotRegistrationOutcome.Rejected -> refused[robot.robotId] = outcome.detail
            }
        }
        DiscoveryOutcome(recorded, refused)
    }

    /** 이 기체의 등록 상태. 그런 기체가 없으면 `null` — 없는 것과 문 밖에서 들어온 것은 다르다. */
    fun statusOf(robotId: String): RobotStatus? = db.transaction { c -> rowOf(c, robotId)?.status }

    /** 진단 9번이 읽는다. */
    fun list(siteId: String? = null): List<RegisteredRobot> = db.open().use { c ->
        val sql = buildString {
            append(
                """
                SELECT r.robot_id, r.site_id, r.serial_number, r.display_name, r.origin, r.endpoint,
                       r.registered_at, r.registered_by, l.last_reported_at, r.discovered_by
                FROM robot r
                LEFT JOIN robot_liveness l ON l.robot_id = r.robot_id
                """.trimIndent(),
            )
            if (siteId != null) append("\nWHERE r.site_id = ?")
            append("\nORDER BY r.robot_id")
        }
        c.prepareStatement(sql).use { s ->
            if (siteId != null) s.setString(1, siteId)
            s.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val origin = rs.getString(5)?.let(RobotOrigin::valueOf)
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
                                lastReportedAt = rs.getTimestamp(9)?.toInstant()?.toString(),
                                status = statusOf(origin, answered = rs.getTimestamp(9) != null),
                                discoveredBy = rs.getString(10),
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

        val status = statusOf(origin, answered = existing?.answered == true)
        return if (existing == null) {
            RobotRegistrationOutcome.Registered(status)
        } else {
            RobotRegistrationOutcome.Updated(status)
        }
    }

    private fun blank(value: String, what: String): RobotRegistrationOutcome.Rejected? =
        if (value.isBlank()) RobotRegistrationOutcome.Rejected("$what 가 비었다") else null

    private class Row(val origin: RobotOrigin?, val answered: Boolean) {
        val status: RobotStatus get() = statusOf(origin, answered)
    }

    private fun rowOf(c: Connection, robotId: String): Row? = c.prepareStatement(
        "SELECT r.origin, l.robot_id IS NOT NULL FROM robot r " +
            "LEFT JOIN robot_liveness l ON l.robot_id = r.robot_id WHERE r.robot_id = ?",
    ).use { s ->
        s.setString(1, robotId)
        s.executeQuery().use { rs ->
            if (rs.next()) Row(rs.getString(1)?.let(RobotOrigin::valueOf), rs.getBoolean(2)) else null
        }
    }

    private companion object {
        /** 적재 문의 행위자. **사람 이름이 아니다** — 그 문에는 신원이 없고, 있는 척하면 감사 로그가 거짓말한다. */
        const val INGEST_ACTOR = "adapter-discovery"

        fun statusOf(origin: RobotOrigin?, answered: Boolean): RobotStatus = when {
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
)

sealed interface RobotRegistrationOutcome {
    data class Registered(val status: RobotStatus) : RobotRegistrationOutcome
    data class Updated(val status: RobotStatus) : RobotRegistrationOutcome

    /** 다른 문으로 이미 들어온 기체다. **갱신하지 않는다** — 출처가 두 벌이 되면 "왜 여기 있는가" 에 못 답한다. */
    data class WrongDoor(val origin: RobotOrigin, val detail: String) : RobotRegistrationOutcome

    data class Rejected(val detail: String) : RobotRegistrationOutcome
}
