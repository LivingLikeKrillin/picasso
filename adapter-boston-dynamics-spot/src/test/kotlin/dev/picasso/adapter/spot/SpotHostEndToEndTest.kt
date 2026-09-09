package dev.picasso.adapter.spot

import dev.picasso.adapter.core.AdapterIdentity
import dev.picasso.adapter.host.AdapterHost
import dev.picasso.adapter.host.HostedRobot
import dev.picasso.client.PicassoClient
import dev.picasso.middleware.ClientRobotPort
import dev.picasso.middleware.EquipmentRequirement
import dev.picasso.middleware.EquipmentUse
import dev.picasso.middleware.Evidence
import dev.picasso.middleware.InspectAsset
import dev.picasso.middleware.JobOrder
import dev.picasso.middleware.Middleware
import dev.picasso.middleware.PhysicalState
import dev.picasso.profile.ProfileDocument
import io.grpc.ManagedChannel
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * ③이 **실물 어댑터 위에서 미들웨어까지 한 줄로** 이어진다 — 상류의 JobOrder → 미들웨어(`InspectAsset`) → 계약(gRPC)
 * → 어댑터 호스트 → Spot 어댑터 → (가짜) 벤더 표면, 그리고 결과 참조가 같은 길을 거슬러 `JobResponse.results` 에 닿는다.
 *
 * 지금까지 미들웨어는 미믹 위에서만 돌았고 `JobResponse.results` 는 비어 있었다(§15.93 — 미믹은 결과를 안 채운다). 이
 * 시험이 그 빈 칸을 채우는 첫 경로다: Spot 의 `DataAcquisition` 이 `DataIdentifier` 를 내고(§15.97), 호스트가 그것을
 * `partial_result` 로 올리고(§15.98), 미들웨어가 그것을 결과 참조로 든다. 벤더 표면은 가짜이고 그 사이 전부는 진짜다.
 *
 * 이 시험이 Spot 모듈에 있는 이유: 미들웨어·호스트 모듈은 기종을 모른다(게이트 7번). 기종을 아는 쪽이 조립한다.
 */
class SpotHostEndToEndTest {

    private data class Point(val location: String, val target: String)

    private val points = listOf(Point("PUMP-ROOM", "PUMP-01"), Point("SWITCHGEAR", "PANEL-3"))

    private fun order() = JobOrder(
        jobOrderId = "PATROL-9",
        workMasterId = InspectAsset.WORK_MASTER,
        version = 1,
        requiredEvidence = Evidence.E0,
        equipmentRequirements = points.map {
            EquipmentRequirement(it.target, EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to it.location, EquipmentUse.PROP_ITEM to "thermal"))
        },
    )

    // ── 가짜 벤더 표면 — 성공 경로만. 거절·실패 경로는 SpotAdapterTest 가 든다.

    private class FakeMission : MissionLayer {
        var played = 0
        override fun loadNavigateTo(waypointId: String): LeaseResult = LeaseResult.Ok
        override fun play(): LeaseResult { played += 1; return LeaseResult.Ok }
        override fun pause(): LeaseResult = LeaseResult.Ok
        override fun stop(): LeaseResult = LeaseResult.Ok
        override fun state(): MissionState? = if (played > 0) MissionState(MissionStatus.SUCCESS) else null
    }

    private class FakeGraph(private val waypoints: Map<String, String>) : GraphLayer {
        override fun downloadGraph(): Result<List<GraphWaypoint>> = Result.success(waypoints.map { (id, name) -> GraphWaypoint(id, name) })
        override fun navigationFeedback(): Result<NavigationStatus?> = Result.success(NavigationStatus.STATUS_REACHED_GOAL)
    }

    private class FakeWorld(private val names: List<String>) : WorldLayer {
        override fun listObjects(): Result<List<WorldObjectRef>> = Result.success(names.mapIndexed { i, n -> WorldObjectRef(100 + i, n) })
    }

    private class FakeAcquisition : AcquisitionLayer {
        val acquired = mutableListOf<Pair<String, String>>()
        private var next = 40
        override fun imageSources(): Result<List<ImageSourceRef>> = Result.success(listOf(ImageSourceRef("spot-cam", "ptz")))
        override fun acquire(actionName: String, groupName: String, captures: List<ImageSourceRef>): AcquireResult {
            acquired += actionName to groupName
            next += 1
            return AcquireResult.Accepted(next)
        }
        override fun status(requestId: Int): Result<AcquisitionStatus> {
            val (action, group) = acquired.last()
            return Result.success(AcquisitionStatus(AcquisitionState.STATUS_COMPLETE, listOf(DataRef(action, group, "spot-cam-ptz", "ptz.jpg", "img-$requestId")), emptyList()))
        }
        override fun cancel(requestId: Int): Result<CancelAcquisitionStatus> = Result.success(CancelAcquisitionStatus.STATUS_OK)
    }

    private class FakeLink(
        override val mission: MissionLayer?,
        override val graph: GraphLayer?,
        override val world: WorldLayer?,
        override val acquisition: AcquisitionLayer?,
    ) : SpotLink {
        override val command: CommandLayer? = null
        override fun armAttached(): Result<Boolean> = Result.success(true)
        override fun gripperHoldingItem(): Result<Boolean?> = Result.success(false)
        override fun behaviorFaults(): Result<List<BehaviorFaultCause>> = Result.success(emptyList())
    }

    private class World : AutoCloseable {
        var now: Instant = Instant.parse("2026-09-10T00:00:00Z")
        val acquisition = FakeAcquisition()
        val adapter = SpotAdapter(
            FakeLink(FakeMission(), FakeGraph(mapOf("wp-1" to "PUMP-ROOM", "wp-2" to "SWITCHGEAR")), FakeWorld(listOf("PUMP-01", "PANEL-3")), acquisition),
            AdapterIdentity("boston-dynamics", "spot-arm", ROBOT),
        )
        val robot = HostedRobot(ROBOT, ProfileDocument.parse("spot-arm", Files.readString(PROFILE)).getOrThrow(), adapter) { now }
        private val name = InProcessServerBuilder.generateName()
        val host = AdapterHost(robot, InProcessServerBuilder.forName(name).directExecutor()).start()
        private val channel: ManagedChannel = InProcessChannelBuilder.forName(name).directExecutor().build()
        val mw = Middleware(ClientRobotPort(PicassoClient(channel, "maintenance-system")), now = { now })

        fun drive(until: () -> Boolean) {
            repeat(60) {
                mw.pump()
                if (until()) return
                now = now.plus(Duration.ofSeconds(5))
            }
            error("조건에 못 미쳤다: ${robot.tasks.map { it.taskId to it.last.state }}")
        }

        override fun close() { channel.shutdownNow(); host.shutdown() }
    }

    @Test
    fun `점검 순회가 미들웨어에서 Spot 어댑터까지 이어지고, 결과 참조가 JobResponse 에 닿는다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { exec.physicalState.isSettled }

            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            val expected = points.flatMap { listOf("${it.target}${InspectAsset.TRAVEL_SUFFIX}", it.target) }
            assertEquals(expected, exec.completedUnits)

            // 호스트에 태스크 넷 — 이동 둘은 미션으로, 점검 둘은 취득으로 갔다.
            assertEquals(4, w.robot.tasks.size)
            assertTrue(w.robot.tasks.all { it.last.state == dev.picasso.contracts.v1.TaskState.TASK_STATE_SUCCEEDED })
            assertEquals(listOf("PUMP-01", "PANEL-3"), w.acquisition.acquired.map { it.first }, "취득이 대상의 이름으로 묶여야 한다")

            // **여기가 빈 칸이 채워지는 자리다.** 점검 둘의 결과 참조 — DataIdentifier 가 대상의 이름을 달고 상류까지 왔다.
            val response = w.mw.pending().single()
            assertEquals(setOf("PUMP-01", "PANEL-3"), response.results.keys)
            response.results.forEach { (target, ref) ->
                assertTrue(ref.contains("@$target/PATROL-9#$target"), "$target 의 참조가 대상과 태스크를 안 단다: $ref")
                assertTrue(ref.startsWith("spot-cam-ptz/ptz.jpg#img-"), ref)
            }
            assertEquals(Evidence.E0, response.reachedEvidence)
            assertTrue(response.autoResolvesInDoubt)
            assertEquals("CONNECTION_STATE_ONLINE", response.connection)
        }
    }

    private companion object {
        const val ROBOT = "spot-01"
        val PROFILE: Path = Path.of("..", "profile", "profiles", "spot-arm.json").normalize()
    }
}
