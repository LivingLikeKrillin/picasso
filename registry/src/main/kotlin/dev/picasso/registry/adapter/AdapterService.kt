package dev.picasso.registry.adapter

import dev.picasso.registry.Semver
import dev.picasso.registry.store.Db
import java.sql.Connection

/** §9.7 ④의 적합성. **실행은 C-3이며 비목표지만 상태는 만든다.** */
enum class ConformanceStatus { UNTESTED, PASSED, FAILED }

sealed interface RegisterOutcome {
    data class Registered(val adapterVersionId: Long) : RegisterOutcome

    data class Rejected(val detail: String) : RegisterOutcome
}

/** P1 — 조작 문의 제품 선언 결과. 201(Created)과 200(Existing)을 가르려고 둔다. */
sealed interface AdapterDeclared {
    data class Created(val adapterId: Long) : AdapterDeclared

    data class Existing(val adapterId: Long) : AdapterDeclared

    data class Rejected(val detail: String) : AdapterDeclared
}

/** P1 — 조작 문의 빌드 선언 결과. 응답 코드 201·200·409·400·404 와 하나씩 맞는다. */
sealed interface VersionDeclared {
    data class Created(val adapterVersionId: Long) : VersionDeclared

    /** 같은 버전·같은 계약 semver 의 재요청. 재시도가 안전하도록 같은 id 를 돌려준다. */
    data class Existing(val adapterVersionId: Long) : VersionDeclared

    /** 같은 버전이 **다른** 계약 semver 로 이미 있다. 덮어쓰지 않는다. */
    data class Conflict(val existingContractSemver: String) : VersionDeclared

    data class BadSemver(val detail: String) : VersionDeclared

    data class UnknownAdapter(val adapterId: Long) : VersionDeclared

    /** 본문(`version`)이나 행위자(`actor`)가 비었다. */
    data class Rejected(val detail: String) : VersionDeclared
}

/** P1 — 제품 목록의 행. HTTP 응답 모양(snake_case)은 컨트롤러가 정한다 — 서비스는 HTTP 를 모른다. */
data class AdapterRow(
    val adapterId: Long,
    val vendor: String,
    val name: String,
    val versions: List<AdapterVersionRow>,
)

data class AdapterVersionRow(
    val adapterVersionId: Long,
    val version: String,
    val contractSemver: String,
    val conformance: String,
    val registeredAt: String,
    val registeredBy: String,
)

/**
 * §9.7 ①의 어댑터 등록과 ④의 적합성 상태.
 *
 * **④를 구현하지 않으면서 상태는 만든다**(§9.7이 그렇게 적었다) — 안 만들면
 * 나중에 워크플로우를 다시 짜야 한다. 그리고 `UNTESTED`가 **진단에 보이는
 * 것**이 완료 기준 16의 절반이다: 시험 안 된 어댑터가 조용히 돌면 운영자가
 * 그 사실을 모른다.
 */
class AdapterService(private val db: Db) {

    fun registerAdapter(vendor: String, name: String, actor: String): Long = db.transaction { c ->
        c.prepareStatement(
            "INSERT INTO adapter (vendor, name) VALUES (?, ?) " +
                "ON CONFLICT (vendor, name) DO NOTHING",
        ).use { it.setString(1, vendor); it.setString(2, name); it.executeUpdate() }

        val id = c.prepareStatement(
            "SELECT adapter_id FROM adapter WHERE vendor = ? AND name = ?",
        ).use { s ->
            s.setString(1, vendor); s.setString(2, name)
            s.executeQuery().use { rs -> check(rs.next()); rs.getLong(1) }
        }
        audit(c, actor, "ADAPTER_REGISTER", "$vendor/$name")
        id
    }

    /**
     * P1 — 조작 문의 제품 선언. [registerAdapter] 와 달리 **새로 만들었는지 이미 있었는지를 돌려준다.**
     *
     * 감사 기록은 새로 만들 때만 남긴다 — 같은 선언의 재시도가 감사 로그를 불리지 않게.
     * [registerAdapter] 는 시험·하네스 호출이 많아 그대로 둔다(반환형을 바꾸면 하네스 시험이 로컬 표준 빌드
     * 밖에서만 깨진다).
     */
    fun declareAdapter(vendor: String, name: String, actor: String): AdapterDeclared {
        if (vendor.isBlank() || name.isBlank()) return AdapterDeclared.Rejected("vendor 와 name 은 비울 수 없다")
        if (actor.isBlank()) return AdapterDeclared.Rejected("actor 가 비었다")
        return db.transaction { c ->
            val inserted = c.prepareStatement(
                "INSERT INTO adapter (vendor, name) VALUES (?, ?) ON CONFLICT (vendor, name) DO NOTHING",
            ).use { it.setString(1, vendor); it.setString(2, name); it.executeUpdate() } == 1

            val id = c.prepareStatement(
                "SELECT adapter_id FROM adapter WHERE vendor = ? AND name = ?",
            ).use { s ->
                s.setString(1, vendor); s.setString(2, name)
                s.executeQuery().use { rs -> check(rs.next()); rs.getLong(1) }
            }
            if (inserted) {
                audit(c, actor, "ADAPTER_REGISTER", "$vendor/$name")
                AdapterDeclared.Created(id)
            } else {
                AdapterDeclared.Existing(id)
            }
        }
    }

    /**
     * §9.7 ①의 "빌드된 계약 semver"를 함께 받는다.
     *
     * **형식을 여기서 확인한다.** 바인딩까지 미루면 등록은 됐는데 아무 기체에도
     * 못 붙는 어댑터 버전이 남고, 운영자는 바인딩을 시도해야 그것을 안다.
     */
    fun registerVersion(
        adapterId: Long,
        version: String,
        contractSemver: String,
        actor: String,
    ): RegisterOutcome = when (val declared = declareVersion(adapterId, version, contractSemver, actor)) {
        // **옛 계약을 지킨다** — 같은 버전 재등록은 이 경로에서 여전히 거절이다(`AdapterLifecycleTest`).
        // 멱등은 조작 문(`declareVersion`)의 성질이고, 옛 호출자는 그 변화를 모른다.
        is VersionDeclared.Created -> RegisterOutcome.Registered(declared.adapterVersionId)
        is VersionDeclared.Existing, is VersionDeclared.Conflict -> RegisterOutcome.Rejected("이미 등록된 버전이다: $version")
        is VersionDeclared.BadSemver -> RegisterOutcome.Rejected(declared.detail)
        is VersionDeclared.UnknownAdapter -> RegisterOutcome.Rejected("모르는 어댑터다: ${declared.adapterId}")
        is VersionDeclared.Rejected -> RegisterOutcome.Rejected(declared.detail)
    }

    /**
     * P1 — 조작 문의 빌드 선언. 같은 버전·같은 계약값의 재요청은 [VersionDeclared.Existing](멱등)이고,
     * 같은 버전에 다른 계약값은 [VersionDeclared.Conflict] 다 — 덮어쓰면 이미 붙은 바인딩의 뜻이 바뀐다.
     *
     * **FK 위반을 터뜨리지 않는다**([AdapterInstanceService.register] 와 같은 이유). 동시 삽입은
     * `ON CONFLICT DO NOTHING` 뒤 다시 읽어 가른다.
     */
    fun declareVersion(adapterId: Long, version: String, contractSemver: String, actor: String): VersionDeclared {
        if (version.isBlank()) return VersionDeclared.Rejected("version 은 비울 수 없다")
        if (actor.isBlank()) return VersionDeclared.Rejected("actor 가 비었다")
        runCatching { Semver.parse(contractSemver) }.onFailure {
            return VersionDeclared.BadSemver(it.message ?: "계약 semver가 형식이 아니다")
        }
        return db.transaction { c ->
            val known = c.prepareStatement("SELECT 1 FROM adapter WHERE adapter_id = ?").use { s ->
                s.setLong(1, adapterId)
                s.executeQuery().use { it.next() }
            }
            if (!known) return@transaction VersionDeclared.UnknownAdapter(adapterId)

            val inserted = c.prepareStatement(
                "INSERT INTO adapter_version (adapter_id, version, contract_semver, registered_by) " +
                    "VALUES (?, ?, ?, ?) ON CONFLICT (adapter_id, version) DO NOTHING RETURNING adapter_version_id",
            ).use { s ->
                s.setLong(1, adapterId); s.setString(2, version)
                s.setString(3, contractSemver); s.setString(4, actor)
                s.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
            }
            if (inserted != null) {
                audit(c, actor, "ADAPTER_VERSION_REGISTER", "$adapterId@$version")
                return@transaction VersionDeclared.Created(inserted)
            }

            val (existingId, existingSemver) = c.prepareStatement(
                "SELECT adapter_version_id, contract_semver FROM adapter_version WHERE adapter_id = ? AND version = ?",
            ).use { s ->
                s.setLong(1, adapterId); s.setString(2, version)
                s.executeQuery().use { rs -> check(rs.next()); rs.getLong(1) to rs.getString(2) }
            }
            if (existingSemver == contractSemver) VersionDeclared.Existing(existingId)
            else VersionDeclared.Conflict(existingSemver)
        }
    }

    /**
     * §9.7 ④의 결과를 기입한다. **실행은 여기가 아니다** — 실물 어댑터를
     * 돌리는 것은 C-3이고 비목표다. 여기서는 그 결과를 받아 적을 뿐이다.
     */
    fun recordConformance(adapterVersionId: Long, status: ConformanceStatus, actor: String) =
        db.transaction { c ->
            c.prepareStatement(
                "UPDATE adapter_version SET conformance_status = ? WHERE adapter_version_id = ?",
            ).use { it.setString(1, status.name); it.setLong(2, adapterVersionId); it.executeUpdate() }
            audit(c, actor, "ADAPTER_CONFORMANCE", "$adapterVersionId=$status")
        }

    /**
     * P1 — 제품과 빌드 목록. **아직 아무 인스턴스에도 안 쓰인 빌드도 보인다** — `/diag/adapter-instances` 는
     * 인스턴스가 있어야 빌드가 보여서, 인스턴스 등록 화면이 고를 빌드를 찾을 길이 없었다.
     */
    fun list(): List<AdapterRow> = db.open().use { c ->
        // 빌드를 먼저 읽고 제품을 나중에 읽는다 — 순서를 바꾸면 그 사이에 생긴 제품의 빌드가 빠진다.
        val versions = c.prepareStatement(
            "SELECT adapter_id, adapter_version_id, version, contract_semver, conformance_status, registered_at, registered_by " +
                "FROM adapter_version ORDER BY adapter_id, adapter_version_id",
        ).use { s ->
            s.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            rs.getLong(1) to AdapterVersionRow(
                                adapterVersionId = rs.getLong(2),
                                version = rs.getString(3),
                                contractSemver = rs.getString(4),
                                conformance = rs.getString(5),
                                registeredAt = rs.getTimestamp(6).toInstant().toString(),
                                registeredBy = rs.getString(7),
                            ),
                        )
                    }
                }
            }
        }.groupBy({ it.first }, { it.second })

        c.prepareStatement("SELECT adapter_id, vendor, name FROM adapter ORDER BY vendor, name").use { s ->
            s.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val id = rs.getLong(1)
                        add(AdapterRow(id, rs.getString(2), rs.getString(3), versions[id].orEmpty()))
                    }
                }
            }
        }
    }

    private fun audit(c: Connection, actor: String, operation: String, subject: String) =
        c.prepareStatement(
            "INSERT INTO audit_log (operation, actor, subject) VALUES (?, ?, ?)",
        ).use {
            it.setString(1, operation); it.setString(2, actor); it.setString(3, subject)
            it.executeUpdate()
        }
}
