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
    ): RegisterOutcome = db.transaction { c ->
        runCatching { Semver.parse(contractSemver) }.onFailure {
            return@transaction RegisterOutcome.Rejected(it.message ?: "계약 semver가 형식이 아니다")
        }

        val exists = c.prepareStatement(
            "SELECT 1 FROM adapter_version WHERE adapter_id = ? AND version = ?",
        ).use { s ->
            s.setLong(1, adapterId); s.setString(2, version)
            s.executeQuery().use { it.next() }
        }
        if (exists) {
            return@transaction RegisterOutcome.Rejected("이미 등록된 버전이다: $version")
        }

        val id = c.prepareStatement(
            "INSERT INTO adapter_version " +
                "(adapter_id, version, contract_semver, registered_by) " +
                "VALUES (?, ?, ?, ?) RETURNING adapter_version_id",
        ).use { s ->
            s.setLong(1, adapterId); s.setString(2, version)
            s.setString(3, contractSemver); s.setString(4, actor)
            s.executeQuery().use { rs -> check(rs.next()); rs.getLong(1) }
        }
        audit(c, actor, "ADAPTER_VERSION_REGISTER", "$adapterId@$version")
        RegisterOutcome.Registered(id)
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

    private fun audit(c: Connection, actor: String, operation: String, subject: String) =
        c.prepareStatement(
            "INSERT INTO audit_log (operation, actor, subject) VALUES (?, ?, ?)",
        ).use {
            it.setString(1, operation); it.setString(2, actor); it.setString(3, subject)
            it.executeUpdate()
        }
}
