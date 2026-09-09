package dev.picasso.mimic.transport

import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.StartTaskRequest
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.WatchTaskRequest
import dev.picasso.mimic.engine.TaskMachineFixtures
import dev.picasso.mimic.engine.VirtualClock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 엔진이 정한 `HoldState`가 **계약 표면**(`WatchTaskResponse.hold`)으로 건너가는가.
 *
 * 엔진 시험([dev.picasso.mimic.engine.HoldStateTest])이 초록이어도 `TaskServiceImpl`이
 * 그 한 줄을 안 실으면 소비자는 영원히 `UNSPECIFIED`를 본다 — 그것이
 * `partial_result`가 겪은 일이다.
 */
class HoldOnWireTest {

    private val clock = VirtualClock(Instant.parse("2026-09-09T00:00:00Z"))
    private val fixture = GrpcFixture(mapOf("r1" to TaskMachineFixtures.document()), clock)

    @AfterTest fun close() = fixture.close()

    @Test
    fun `든 채와 빈손이 스트림에 그대로 실린다`() {
        fixture.tasks.startTask(
            StartTaskRequest.newBuilder()
                .setHeader(GrpcFixture.requestHeader("r1"))
                .setTaskId("t1").setRevision(1).setSkillType("pick_place")
                .addParameters(ParameterValue.newBuilder().setKey("object_id").setStringValue("BIN-A"))
                .addParameters(ParameterValue.newBuilder().setKey("destination").setStringValue("S01"))
                .build(),
        )
        // 접수 → RUNNING 은 tick 하나, RUNNING → 종착은 시간이 흐른 뒤의 tick 하나다.
        // watchTask 진입의 tick 이 뒤엣것을 맡으므로 앞엣것만 여기서 민다.
        fixture.registry.require(GrpcFixture.requestHeader("r1")).instance.tasks.tick()
        repeat(3) { clock.advance(Duration.ofSeconds(30)) } // 소요시간을 시험이 알면 지터가 붙는 날 빨개진다

        val updates = fixture.tasks
            .withDeadlineAfter(10, TimeUnit.SECONDS)
            .watchTask(
                WatchTaskRequest.newBuilder()
                    .setHeader(GrpcFixture.requestHeader("r1"))
                    .setHandle(TaskHandle.newBuilder().setTaskId("t1").setRevision(1).setRobotId("r1"))
                    .setFromUpdateIndex(0).build(),
            ).asSequence().toList()

        assertEquals(
            listOf(TaskState.TASK_STATE_ACCEPTED, TaskState.TASK_STATE_RUNNING, TaskState.TASK_STATE_SUCCEEDED),
            updates.map { it.state },
            "전제가 무너졌다 — 접수·실행·완주 셋이어야 한다",
        )
        assertEquals(
            listOf(HoldKind.HOLD_KIND_EMPTY, HoldKind.HOLD_KIND_HOLDING, HoldKind.HOLD_KIND_EMPTY),
            updates.map { it.hold.kind },
        )
        assertEquals("BIN-A", updates[1].hold.objectRef)
    }
}
