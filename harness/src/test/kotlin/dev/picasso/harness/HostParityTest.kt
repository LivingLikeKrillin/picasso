package dev.picasso.harness

import dev.picasso.adapter.core.Acceptance
import dev.picasso.adapter.core.Applied
import dev.picasso.adapter.core.FaultObservation
import dev.picasso.adapter.core.HoldObservation
import dev.picasso.adapter.core.Refusal
import dev.picasso.adapter.core.RobotAdapter
import dev.picasso.adapter.core.SiteNames
import dev.picasso.adapter.host.AdapterHost
import dev.picasso.adapter.host.HostedRobot
import dev.picasso.client.PicassoClient
import dev.picasso.contracts.v1.NegotiateResponse
import dev.picasso.contracts.v1.Rejection
import dev.picasso.contracts.v1.TaskState
import dev.picasso.profile.LimitsNeeded
import dev.picasso.profile.ProfileDocument
import dev.picasso.profile.Requirement
import dev.picasso.profile.RequirementSet
import io.grpc.ManagedChannel
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **미믹과 어댑터 호스트가 같은 요구 집합에 같은 답을 낸다.**
 *
 * 이 저장소의 중심 주장은 *"소비자는 엔드포인트만 바꿔 미믹과 실물을 오간다"* 이고, 협상은 소비자가 **가장 먼저**
 * 두드리는 표면이다(§5.4). 판정이 두 벌이면 같은 설정 파일을 들고 미믹에서는 통과하고 실물에서는 거절당하는 일이
 * 가능해진다. 판정 함수를 `capability` 모듈 하나로 둔 이유가 그것이고, 이 시험이 그 하나임을 밖에서 확인한다.
 *
 * **여기 있는 이유**: `harness` 만 둘을 동시에 안다. 미믹도 어댑터 호스트도 서로를 모르는 것이 §3.2 이고, 그래서
 * 둘을 나란히 세워 보는 자리는 계약 스위트의 주인뿐이다.
 *
 * 비교는 `accepted` 와 거절 목록 전체다 — 코드만 대면 사유가 갈리는 것을 놓치고, 사유는 운영자가 읽는 것이다.
 * 헤더는 안 댄다(세션·시각이 다른 것이 정상이다).
 */
class HostParityTest {

    /** 기종이 없는 어댑터 — 협상은 프로파일의 투영에 대고 하므로 어댑터가 무엇을 들든 상관없다. */
    private class PlainAdapter : RobotAdapter {
        override val state: TaskState get() = TaskState.TASK_STATE_UNSPECIFIED
        override fun accept(taskId: String, skillType: String, parameters: Map<String, Any>, startedAt: Instant) =
            Acceptance.Accepted(taskId)
        override fun poll(now: Instant): TaskState = TaskState.TASK_STATE_RUNNING
        override fun pause(): Applied = Applied.Refused(Refusal.NO_VENDOR_PRIMITIVE, "없다")
        override fun cancel(): Applied = Applied.Refused(Refusal.NO_VENDOR_PRIMITIVE, "없다")
        override fun hold(): HoldObservation = HoldObservation.Empty
        override fun faults(): FaultObservation = FaultObservation.Observed(emptyList())
        override fun knownSiteNames(): SiteNames = SiteNames.Known(emptyList())
    }

    private val mimic = Harness(mapOf(ROBOT to PROFILE))

    private val robot = HostedRobot(ROBOT, ProfileDocument.parse("minimal", Files.readString(PROFILE)).getOrThrow(), PlainAdapter())
    private val name = InProcessServerBuilder.generateName()
    private val host = AdapterHost(robot, InProcessServerBuilder.forName(name).directExecutor()).start()
    private val channel: ManagedChannel = InProcessChannelBuilder.forName(name).directExecutor().build()
    private val hostClient = PicassoClient(channel, CLIENT)

    @AfterTest
    fun close() {
        channel.shutdownNow()
        host.shutdown()
        mimic.close()
    }

    private fun base(): RequirementSet = RequirementSet
        .parse("minimal", Files.readString(Path.of("..", "profile", "requirements", "minimal.json").normalize()))
        .copy(clientId = CLIENT)

    private fun both(requirements: RequirementSet): Pair<NegotiateResponse, NegotiateResponse> =
        mimic.client(CLIENT).negotiate(ROBOT, requirements) to hostClient.negotiate(ROBOT, requirements)

    private fun assertSame(label: String, requirements: RequirementSet, expected: Boolean) {
        val (fromMimic, fromHost) = both(requirements)
        assertEquals(expected, fromMimic.accepted, "$label: 미믹의 답이 시험의 전제와 다르다 — 픽스처가 바뀌었다")
        assertEquals(fromMimic.accepted, fromHost.accepted, "$label: 수락 여부가 갈렸다")
        assertEquals(readable(fromMimic.rejectionsList), readable(fromHost.rejectionsList), "$label: 거절이 갈렸다")
    }

    private fun readable(rejections: List<Rejection>): List<String> = rejections
        .map { "${it.code.name}|${it.detail}|${it.referencesList.joinToString { r -> "${r.key.name}=${r.value}" }}" }
        .sorted()

    @Test
    fun `만족하는 요구를 둘 다 수락한다`() {
        assertSame("만족", base(), expected = true)
    }

    @Test
    fun `선언 안 한 스킬을 둘 다 같은 말로 거절한다`() {
        assertSame("스킬 없음", base().let { it.copy(requirements = it.requirements + Requirement("fly", 1, 0)) }, expected = false)
    }

    @Test
    fun `버전이 안 맞으면 둘 다 같은 말로 거절한다`() {
        assertSame("major", base().copy(requirements = listOf(Requirement("pick_place", 9, 0))), expected = false)
        // minor 부족도 같은 코드다(이름이 실제보다 좁다 — detail 이 무엇이 부족한지 말한다). 그 말까지 같아야 한다.
        assertSame("minor", base().copy(requirements = listOf(Requirement("pick_place", 1, 9))), expected = false)
    }

    @Test
    fun `로봇이 요구하는 선택 필드를 안 쓰면 둘 다 거절한다`() {
        assertSame("필수 선택 필드", base().copy(optionalFieldsUsed = emptyList()), expected = false)
    }

    @Test
    fun `한계를 넘으면 둘 다 거절한다`() {
        assertSame("한계", base().copy(limitsNeeded = LimitsNeeded(maxStringLength = 99999, maxArrayLength = 8)), expected = false)
    }

    @Test
    fun `여럿을 한꺼번에 어겨도 둘의 답이 같다`() {
        // **판정이 갈리는 것을 잡으려면 하나씩 어겨서는 부족하다** — 첫 거절에서 끊는 구현이 한쪽에만 있으면
        // 한 가지씩 어기는 시험은 전부 통과하고 여기서만 갈린다.
        val broken = base().let {
            it.copy(
                requirements = listOf(Requirement("fly", 1, 0), Requirement("pick_place", 9, 0)),
                optionalFieldsUsed = emptyList(),
                limitsNeeded = LimitsNeeded(maxStringLength = 99999, maxArrayLength = 99999),
            )
        }
        assertSame("전부", broken, expected = false)
        val (fromMimic, _) = both(broken)
        assertTrue(fromMimic.rejectionsList.size >= 4, "전제가 무너졌다 — 여럿을 어겼는데 거절이 ${fromMimic.rejectionsList.size} 건이다")
    }

    private companion object {
        const val ROBOT = "r1"
        const val CLIENT = "line-controller"
        val PROFILE: Path = Path.of("..", "profile", "fixtures", "minimal.json").normalize()
    }
}
