package dev.picasso.registry

import dev.picasso.registry.adapter.AdapterDeclared
import dev.picasso.registry.adapter.AdapterService
import dev.picasso.registry.adapter.RegisterOutcome
import dev.picasso.registry.adapter.VersionDeclared
import dev.picasso.registry.store.Db
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P1 — 조작 문이 쓰는 결과. **새로 만들었는지 이미 있었는지를 가른다.**
 *
 * 옛 `registerAdapter` 는 `Long` 하나만 돌려줘 201 과 200 을 가를 수 없었고, 옛 `registerVersion` 은 형식 오류와
 * 중복을 `Rejected` 하나로 접었다. 표면이 400·404·409 를 따로 내려면 서비스가 먼저 갈라야 한다.
 */
class AdapterDeclarationTest {

    private lateinit var adapters: AdapterService

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        adapters = AdapterService(Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password))
    }

    private fun count(table: String): Int =
        PostgresSupport.queryOne("SELECT count(*) FROM $table") { it.getInt(1) }

    @Test
    fun `제품을 처음 선언하면 Created, 다시 선언하면 같은 id 로 Existing`() {
        val first = assertIs<AdapterDeclared.Created>(adapters.declareAdapter("acme", "drv", "op"))
        val again = assertIs<AdapterDeclared.Existing>(adapters.declareAdapter("acme", "drv", "op"))
        assertEquals(first.adapterId, again.adapterId)
        assertEquals(1, count("adapter"))
    }

    @Test
    fun `같은 제품의 재선언은 감사 기록을 다시 남기지 않는다`() {
        adapters.declareAdapter("acme", "drv", "op")
        adapters.declareAdapter("acme", "drv", "op")
        assertEquals(
            1,
            PostgresSupport.queryOne("SELECT count(*) FROM audit_log WHERE operation = 'ADAPTER_REGISTER'") { it.getInt(1) },
        )
    }

    @Test
    fun `vendor 나 name 이 비면 거절하고 저장하지 않는다`() {
        assertIs<AdapterDeclared.Rejected>(adapters.declareAdapter("", "drv", "op"))
        assertIs<AdapterDeclared.Rejected>(adapters.declareAdapter("acme", " ", "op"))
        assertIs<AdapterDeclared.Rejected>(adapters.declareAdapter("acme", "drv", " "))
        assertEquals(0, count("adapter"))
    }

    private fun adapterId(): Long =
        (adapters.declareAdapter("acme", "drv", "op") as AdapterDeclared.Created).adapterId

    @Test
    fun `빌드를 처음 선언하면 Created, 같은 내용이면 같은 id 로 Existing`() {
        val id = adapterId()
        val first = assertIs<VersionDeclared.Created>(adapters.declareVersion(id, "1.0.0", SEMVER, "op"))
        val again = assertIs<VersionDeclared.Existing>(adapters.declareVersion(id, "1.0.0", SEMVER, "op"))
        assertEquals(first.adapterVersionId, again.adapterVersionId)
        assertEquals(1, count("adapter_version"))
    }

    @Test
    fun `같은 빌드의 재선언과 충돌은 감사 기록을 다시 남기지 않는다`() {
        val id = adapterId()
        assertIs<VersionDeclared.Created>(adapters.declareVersion(id, "1.0.0", SEMVER, "op"))
        assertIs<VersionDeclared.Existing>(adapters.declareVersion(id, "1.0.0", SEMVER, "op"))
        assertIs<VersionDeclared.Conflict>(adapters.declareVersion(id, "1.0.0", "9.9.9", "op"))
        assertEquals(
            1,
            PostgresSupport.queryOne("SELECT count(*) FROM audit_log WHERE operation = 'ADAPTER_VERSION_REGISTER'") { it.getInt(1) },
        )
    }

    @Test
    fun `같은 버전에 다른 계약 semver 면 Conflict 이고 기존 값을 돌려준다`() {
        val id = adapterId()
        adapters.declareVersion(id, "1.0.0", SEMVER, "op")
        val conflict = assertIs<VersionDeclared.Conflict>(adapters.declareVersion(id, "1.0.0", "9.9.9", "op"))
        assertEquals(SEMVER, conflict.existingContractSemver)
        assertEquals(1, count("adapter_version"))
        // 덮어쓰지 않는다 — 저장된 값이 그대로다.
        assertEquals(SEMVER, PostgresSupport.queryOne("SELECT contract_semver FROM adapter_version") { it.getString(1) })
    }

    @Test
    fun `계약 semver 형식이 틀리면 BadSemver 이고 저장하지 않는다`() {
        val id = adapterId()
        assertIs<VersionDeclared.BadSemver>(adapters.declareVersion(id, "1.0.0", "not-a-semver", "op"))
        assertIs<VersionDeclared.Rejected>(adapters.declareVersion(id, " ", SEMVER, "op"))
        assertIs<VersionDeclared.Rejected>(adapters.declareVersion(id, "1.0.0", SEMVER, " "))
        assertEquals(0, count("adapter_version"))
    }

    @Test
    fun `모르는 제품이면 UnknownAdapter 이고 예외가 되지 않는다`() {
        val unknown = assertIs<VersionDeclared.UnknownAdapter>(adapters.declareVersion(424242, "1.0.0", SEMVER, "op"))
        assertEquals(424242, unknown.adapterId)
        assertEquals(0, count("adapter_version"))
    }

    @Test
    fun `옛 registerVersion 은 모르는 제품을 예외 대신 거절로 돌려준다`() {
        // 옛 경로는 FK 위반으로 예외를 냈다. 위임 뒤에는 거절이다 — 같은 SQL 경로 하나만 남기려고 위임했다.
        assertIs<RegisterOutcome.Rejected>(adapters.registerVersion(424242, "1.0.0", SEMVER, "op"))

        // 같은 버전을 다른 semver 로 다시 등록해도 옛 경로에서는 거절이다.
        val id = adapterId()
        assertIs<RegisterOutcome.Registered>(adapters.registerVersion(id, "1.0.0", SEMVER, "op"))
        assertIs<RegisterOutcome.Rejected>(adapters.registerVersion(id, "1.0.0", "9.9.9", "op"))
    }

    @Test
    fun `목록은 제품마다 빌드를 담고 빌드의 적합성은 UNTESTED 로 시작한다`() {
        val id = adapterId()
        // 등록 순으로 나온다 — 사전순이었다면 "1.10.0" 이 "1.2.0" 앞에 온다.
        adapters.declareVersion(id, "1.2.0", SEMVER, "op")
        adapters.declareVersion(id, "1.10.0", SEMVER, "op")
        adapters.declareAdapter("zeta", "bare", "op") // 빌드 없는 제품도 목록에 있다

        val rows = adapters.list()
        assertEquals(listOf("acme/drv", "zeta/bare"), rows.map { "${it.vendor}/${it.name}" })
        val drv = rows.first()
        assertEquals(listOf("1.2.0", "1.10.0"), drv.versions.map { it.version })
        assertTrue(drv.adapterId > 0)
        assertTrue(drv.versions.all { it.adapterVersionId > 0 && it.registeredAt.isNotEmpty() })
        assertTrue(drv.versions.all { it.conformance == "UNTESTED" && it.contractSemver == SEMVER && it.registeredBy == "op" })
        assertEquals(emptyList(), rows.last().versions)
    }

    private companion object {
        val SEMVER: String = dev.picasso.contracts.wire.ContractIdentity.semver
    }
}
