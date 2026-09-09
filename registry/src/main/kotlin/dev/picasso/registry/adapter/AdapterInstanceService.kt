package dev.picasso.registry.adapter

import dev.picasso.registry.store.Db
import java.time.Instant

/**
 * 배포된 어댑터 하나(ADR 37 결정 2).
 *
 * ## 축이 셋이고 셋 다 다른 것을 답한다
 *
 * | 축 | 답하는 것 |
 * |---|---|
 * | `adapter` | 누가 만든 무엇인가 |
 * | `adapter_version` | **어느 빌드가 어느 계약 semver 를 따르는가** — 능력 판정의 입력 |
 * | `adapter_instance` | **어디에 떠 있고 무엇에 붙는가** — 배포의 사실 |
 *
 * ## 바인딩은 여전히 **빌드**를 가리킨다
 *
 * ADR 37 의 미결 1(*"바인딩이 빌드를 가리키는가 인스턴스를 가리키는가"*)을 여기서 정한다 — **빌드 그대로 둔다.**
 *
 * 바인딩이 답하는 질문은 *"이 기체가 어느 능력으로 도는가"* 이고, 그것은 **빌드의 성질**이다(계약 semver·적합성).
 * 인스턴스는 **배포의 사실**이라 같은 빌드로 프로세스를 다시 띄우기만 해도 바뀐다. 둘을 한 열로 접으면 재배포마다
 * 바인딩 이력이 한 줄씩 늘고, 진단 1번이 그것을 *"능력이 바뀌었다"* 로 보여 준다 — **아무 능력도 안 바뀌었는데**.
 *
 * 대신 *"이 발견이 어느 어댑터의 것인가"* 는 기체 쪽에 적는다(`robot.discovered_by`). 그 질문의 주어가 바인딩이
 * 아니라 기체이기 때문이다.
 *
 * ## 접속 설정은 한 갈래만 여기 있다
 *
 * 플릿 경유의 주소만 든다(ADR 37 결정 4). 직결이면 로봇의 주소가 `robot.endpoint` 에 있고 여기는 비어 있다 —
 * 같은 사실을 두 곳에 두면 하나가 바뀐 날 어느 쪽이 맞는지 정해져 있지 않다. **자격증명은 어느 쪽도 안 받는다**(§6.3).
 */
class AdapterInstanceService(private val db: Db, private val now: () -> Instant = Instant::now) {

    /**
     * 배포를 기록한다 — **조작 문**이다(사람이 판단을 기록한다).
     *
     * 멱등이다. 같은 이름으로 다시 부르면 접속 설정과 빌드가 갱신된다 — 재배포가 그 모양이다.
     */
    fun register(
        instanceId: String,
        adapterVersionId: Long,
        siteId: String,
        fleetEndpoint: String? = null,
        actor: String,
    ): InstanceOutcome = db.transaction { c ->
        if (instanceId.isBlank()) return@transaction InstanceOutcome.Rejected("instance_id 가 비었다")
        if (siteId.isBlank()) return@transaction InstanceOutcome.Rejected("site_id 가 비었다")
        if (actor.isBlank()) return@transaction InstanceOutcome.Rejected("actor 가 비었다")

        val known = c.prepareStatement("SELECT 1 FROM adapter_version WHERE adapter_version_id = ?").use { s ->
            s.setLong(1, adapterVersionId)
            s.executeQuery().use { it.next() }
        }
        // **FK 위반을 그대로 터뜨리지 않는다.** 표면이 500 을 내면 사유가 사라지고, 운영자는 무엇이 틀렸는지 모른다.
        if (!known) return@transaction InstanceOutcome.Rejected("모르는 어댑터 빌드다: $adapterVersionId")

        val existed = c.prepareStatement("SELECT 1 FROM adapter_instance WHERE instance_id = ?").use { s ->
            s.setString(1, instanceId)
            s.executeQuery().use { it.next() }
        }

        c.prepareStatement(
            """
            INSERT INTO adapter_instance (instance_id, adapter_version_id, site_id, fleet_endpoint, registered_at, registered_by)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (instance_id) DO UPDATE SET
                adapter_version_id = EXCLUDED.adapter_version_id,
                site_id = EXCLUDED.site_id,
                fleet_endpoint = EXCLUDED.fleet_endpoint,
                registered_at = EXCLUDED.registered_at,
                registered_by = EXCLUDED.registered_by
            """.trimIndent(),
        ).use { s ->
            s.setString(1, instanceId)
            s.setLong(2, adapterVersionId)
            s.setString(3, siteId)
            s.setString(4, fleetEndpoint)
            s.setTimestamp(5, java.sql.Timestamp.from(now()))
            s.setString(6, actor)
            s.executeUpdate()
        }

        c.prepareStatement(
            "INSERT INTO audit_log (operation, actor, subject, after) VALUES (?, ?, ?, ?::jsonb)",
        ).use { s ->
            s.setString(1, if (existed) "ADAPTER_INSTANCE_UPDATED" else "ADAPTER_INSTANCE_REGISTERED")
            s.setString(2, actor)
            s.setString(3, instanceId)
            s.setString(4, """{"site":"$siteId","version":$adapterVersionId}""")
            s.executeUpdate()
        }

        if (existed) InstanceOutcome.Updated else InstanceOutcome.Registered
    }

    /** 이 인스턴스가 실재하는가, 그리고 어느 사이트의 것인가. 적재 문이 발견을 받을 때 본다. */
    fun siteOf(instanceId: String): String? = db.transaction { c ->
        c.prepareStatement("SELECT site_id FROM adapter_instance WHERE instance_id = ?").use { s ->
            s.setString(1, instanceId)
            s.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    /** 진단이 읽는다. */
    fun list(siteId: String? = null): List<AdapterInstanceRow> = db.open().use { c ->
        val sql = buildString {
            append(
                """
                SELECT i.instance_id, i.site_id, i.fleet_endpoint, i.registered_at, i.registered_by,
                       a.vendor, a.name, v.version, v.contract_semver, v.conformance_status,
                       (SELECT count(*) FROM robot r WHERE r.discovered_by = i.instance_id)
                FROM adapter_instance i
                JOIN adapter_version v ON v.adapter_version_id = i.adapter_version_id
                JOIN adapter a ON a.adapter_id = v.adapter_id
                """.trimIndent(),
            )
            if (siteId != null) append("\nWHERE i.site_id = ?")
            append("\nORDER BY i.instance_id")
        }
        c.prepareStatement(sql).use { s ->
            if (siteId != null) s.setString(1, siteId)
            s.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            AdapterInstanceRow(
                                instanceId = rs.getString(1),
                                siteId = rs.getString(2),
                                fleetEndpoint = rs.getString(3),
                                registeredAt = rs.getTimestamp(4).toInstant().toString(),
                                registeredBy = rs.getString(5),
                                adapter = "${rs.getString(6)}/${rs.getString(7)}",
                                version = rs.getString(8),
                                contractSemver = rs.getString(9),
                                conformance = rs.getString(10),
                                discoveredRobots = rs.getInt(11),
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 진단 10번의 한 줄.
 *
 * **적합성 상태를 함께 낸다.** `UNTESTED` 인 빌드가 현장에 떠 있다는 것은 운영자가 알아야 할 사실이고(§9.7 ④),
 * 인스턴스 목록은 그것이 *실제로 배포됐는지* 를 처음으로 보여 주는 자리다.
 */
data class AdapterInstanceRow(
    val instanceId: String,
    val siteId: String,
    val fleetEndpoint: String?,
    val registeredAt: String,
    val registeredBy: String,
    val adapter: String,
    val version: String,
    val contractSemver: String,
    val conformance: String,
    /** 이 인스턴스가 올린 기체 수. 0 이면 아직 아무것도 발견 못 했거나 직결이다. */
    val discoveredRobots: Int,
)

sealed interface InstanceOutcome {
    data object Registered : InstanceOutcome
    data object Updated : InstanceOutcome
    data class Rejected(val detail: String) : InstanceOutcome
}
