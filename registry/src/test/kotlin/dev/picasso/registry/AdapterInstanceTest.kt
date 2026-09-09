package dev.picasso.registry

import dev.picasso.registry.adapter.AdapterInstanceService
import dev.picasso.registry.adapter.AdapterService
import dev.picasso.registry.adapter.InstanceOutcome
import dev.picasso.registry.adapter.RegisterOutcome
import dev.picasso.registry.binding.DiscoveredRobot
import dev.picasso.registry.binding.RobotRegistration
import dev.picasso.registry.binding.RobotRegistrationOutcome
import dev.picasso.registry.store.Db
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 배포된 어댑터(ADR 37 결정 2)와, **이 발견이 누구의 것인가.**
 *
 * §15.103 이 *"어댑터 인스턴스가 없어서 이 발견이 어느 어댑터의 것인지 원장이 못 답한다"* 로 남긴 자리다.
 *
 * 이 파일에서 가장 중요한 시험은 [`모르는 인스턴스가 올리면 목록 전체를 안 받는다`] 다. 그것이 ADR 37 의 절차
 * (인스턴스를 먼저 등록하고, 띄우면 로봇이 흘러 들어온다)를 **표가 드는** 방법이다.
 */
class AdapterInstanceTest {

    private lateinit var db: Db
    private lateinit var instances: AdapterInstanceService
    private lateinit var robots: RobotRegistration
    private var versionId: Long = 0

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        instances = AdapterInstanceService(db)
        robots = RobotRegistration(db)

        val adapters = AdapterService(db)
        val version = adapters.registerVersion(
            adapters.registerAdapter("boston-dynamics", "orbit", "op"),
            "1.0.0",
            dev.picasso.contracts.wire.ContractIdentity.semver,
            "op",
        )
        versionId = assertIs<RegisterOutcome.Registered>(version).adapterVersionId
    }

    private fun register(id: String = INSTANCE, site: String = "line-a", endpoint: String? = "https://orbit.line-a") =
        instances.register(id, versionId, site, endpoint, actor = "operator-1")

    // ── 축이 셋이다

    @Test
    fun `배포는 빌드와 다른 축이고, 다시 띄우면 갱신이다`() {
        assertIs<InstanceOutcome.Registered>(register())
        // 같은 이름으로 다시 = 재배포. 새 행이 아니라 갱신이다 — 배포는 이력이 아니라 현재 사실이다.
        assertIs<InstanceOutcome.Updated>(register(endpoint = "https://orbit-2.line-a"))

        val row = instances.list().single()
        assertEquals("boston-dynamics/orbit", row.adapter)
        assertEquals("1.0.0", row.version)
        assertEquals("https://orbit-2.line-a", row.fleetEndpoint)
        // **적합성이 함께 보인다.** UNTESTED 인 빌드가 실제로 떠 있다는 것이 운영자가 알아야 할 사실이다(§9.7 ④).
        assertEquals("UNTESTED", row.conformance)
        assertEquals(0, row.discoveredRobots)
    }

    @Test
    fun `모르는 빌드로는 배포를 기록하지 못한다`() {
        val rejected = assertIs<InstanceOutcome.Rejected>(instances.register(INSTANCE, 9999, "line-a", null, "op"))
        // FK 예외를 그대로 터뜨리면 표면이 500 을 내고 사유가 사라진다.
        assertTrue("9999" in rejected.detail, rejected.detail)
    }

    @Test
    fun `직결 배포는 플릿 주소가 없다`() {
        // ADR 37 결정 4 — 직결이면 로봇의 주소가 robot.endpoint 에 있고 여기는 비어 있다.
        assertIs<InstanceOutcome.Registered>(register(endpoint = null))
        assertNull(instances.list().single().fleetEndpoint)
    }

    // ── 이 발견이 누구의 것인가

    @Test
    fun `발견이 자기 인스턴스를 신고하면 기체에 남는다`() {
        register()
        val outcome = robots.discover("line-a", listOf(DiscoveredRobot("spot-a")), instanceId = INSTANCE)
        assertEquals(listOf("spot-a"), outcome.recorded)

        assertEquals(INSTANCE, robots.list().single().discoveredBy)
        assertEquals(1, instances.list().single().discoveredRobots)
    }

    @Test
    fun `모르는 인스턴스가 올리면 목록 전체를 안 받는다`() {
        val outcome = robots.discover("line-a", listOf(DiscoveredRobot("spot-a"), DiscoveredRobot("spot-b")), instanceId = "ghost")

        // **기체마다 거절하지 않는다.** 사유가 N 번 반복되면 원인이 기체가 아니라 발신자인 것이 안 보인다.
        assertEquals(emptyList(), outcome.recorded)
        assertEquals(setOf("spot-a", "spot-b"), outcome.refused.keys)
        assertTrue("ghost" in outcome.refused.getValue("spot-a"), outcome.refused.toString())
        assertEquals(0, PostgresSupport.queryOne("SELECT count(*) FROM robot") { it.getInt(1) })
    }

    @Test
    fun `안 밝히고 올려도 받되, 이미 아는 것을 지우지 않는다`() {
        register()
        robots.discover("line-a", listOf(DiscoveredRobot("spot-a")), instanceId = INSTANCE)

        // 옛 배포가 섞여 돈다 — 자기를 안 밝힌다. **받되** 이미 앉은 출처를 되돌리지 않는다.
        robots.discover("line-a", listOf(DiscoveredRobot("spot-a")))
        assertEquals(INSTANCE, robots.list().single().discoveredBy, "안 밝힌 보고가 아는 것을 지웠다")

        // 처음부터 안 밝힌 기체는 널이고, 그것은 진단에서 "어느 어댑터인지 모른다" 로 보인다.
        robots.discover("line-a", listOf(DiscoveredRobot("spot-b")))
        assertNull(robots.list().first { it.robotId == "spot-b" }.discoveredBy)
    }

    @Test
    fun `선언된 기체에는 올린 어댑터가 없다`() {
        register()
        assertIs<RobotRegistrationOutcome.Registered>(robots.declare("spot-c", "line-a", "sn-c", actor = "operator-1"))
        assertNull(robots.list().single().discoveredBy)

        // 표도 같은 것을 막는다 — 서비스를 한 줄 고쳐 새는 것을 CHECK 가 잡는다.
        val violated = runCatching {
            PostgresSupport.execute("UPDATE robot SET discovered_by = '$INSTANCE' WHERE robot_id = 'spot-c'")
        }
        assertTrue(violated.isFailure, "선언된 기체가 올린 어댑터를 갖는 것을 표가 안 막았다")
    }

    private companion object {
        const val INSTANCE = "orbit-line-a"
    }
}
