package dev.picasso.mimic.transport

import dev.picasso.contracts.v1.CancelTaskRequest
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.PauseTaskRequest
import dev.picasso.contracts.v1.Reference
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.ResumeTaskRequest
import dev.picasso.contracts.v1.RetryTaskRequest
import dev.picasso.contracts.v1.StartTaskRequest
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.mimic.engine.Resolution
import dev.picasso.mimic.engine.TaskMachineFixtures
import dev.picasso.mimic.engine.VirtualClock
import dev.picasso.profile.ProfileDocument
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import dev.picasso.mimic.engine.TaskState as EngineState

/**
 * §4.4의 여섯 RPC를 wire 너머로 본다.
 *
 * **Chunk 2가 엔진에서 같은 표를 돌았다. 중복이 아니다** — 엔진이 옳아도
 * 매핑이 틀릴 수 있고, 매핑이 소비자가 보는 전부다.
 */
class TaskRpcTest {

    private val clock = VirtualClock(Instant.parse("2026-09-06T00:00:00Z"))
    private val fixture = GrpcFixture(mapOf("r1" to TaskMachineFixtures.document()), clock)

    @AfterTest fun close() = fixture.close()

    private fun startRequest(
        taskId: String = "t1",
        revision: Int = 1,
        skillType: String = "navigate_to",
        parameters: List<ParameterValue> = listOf(string("location", "dock-3")),
    ) = StartTaskRequest.newBuilder()
        .setHeader(GrpcFixture.requestHeader("r1"))
        .setTaskId(taskId).setRevision(revision).setSkillType(skillType)
        .addAllParameters(parameters)
        .build()

    private fun start(
        taskId: String = "t1",
        revision: Int = 1,
        skillType: String = "navigate_to",
        parameters: List<ParameterValue> = listOf(string("location", "dock-3")),
    ): StartTaskResponse = fixture.tasks.startTask(startRequest(taskId, revision, skillType, parameters))

    private fun string(key: String, value: String): ParameterValue =
        ParameterValue.newBuilder().setKey(key).setStringValue(value).build()

    private fun handle(taskId: String = "t1", revision: Int = 1) =
        TaskHandle.newBuilder().setTaskId(taskId).setRevision(revision).setRobotId("r1").build()

    /**
     * 접수한 태스크를 집어 들게 한다. RPC 진입의 `tick()`과 같은 호출이다 —
     * 시험이 자기 나름의 경로를 만들지 않는다.
     */
    private fun settle(f: GrpcFixture = fixture) =
        f.registry.require(GrpcFixture.requestHeader("r1")).instance.tasks.tick()

    private fun log(taskId: String = "t1") =
        fixture.registry.require(GrpcFixture.requestHeader("r1")).instance.tasks.find(taskId)!!.log

    // ── StartTask: 접수와 시작

    @Test
    fun `StartTask는 접수까지이고 실행 개시는 그 다음이다`() {
        // §4.4 — "접수 응답이지 종착이 아니다". 여기서 START까지 해 버리면
        // ACCEPTED가 표면에서 **도달 불가능**해지고, §4.4의 갱신 표에서
        // ACCEPTED 행이 영원히 시험되지 않는다.
        val response = start()
        assertTrue(response.hasHandle(), "${response.rejection}")
        assertEquals("t1", response.handle.taskId)
        assertEquals(1, response.handle.revision)
        assertEquals("r1", response.handle.robotId)

        assertEquals(listOf(EngineState.ACCEPTED), log().from(0).map { it.state })

        // 다음 RPC의 tick()이 집어 든다.
        fixture.capabilitiesOf("r1")
        fixture.tasks.pauseTask(
            PauseTaskRequest.newBuilder()
                .setHeader(GrpcFixture.requestHeader("r1")).setHandle(handle()).build(),
        )
        assertEquals(
            listOf(EngineState.ACCEPTED, EngineState.RUNNING, EngineState.PAUSED),
            log().from(0).map { it.state },
        )
        assertEquals(listOf(0L, 1L, 2L), log().from(0).map { it.updateIndex })
    }

    // ── §4.4의 표 ① — 수신한 revision 네 케이스

    @Test
    fun `수신한 revision 네 케이스를 표면에서 통째로 확인한다`() {
        start(revision = 3)
        settle()
        val sizeAfterStart = log().size

        // 동일 → 같은 핸들, 로그 불변
        val same = start(revision = 3)
        assertTrue(same.hasHandle())
        assertEquals(3, same.handle.revision)
        assertEquals(
            sizeAfterStart, log().size,
            "멱등 재수신이 로그를 늘렸다 — WatchTask가 유령 갱신을 흘린다",
        )

        // 낮음 → OUTDATED_REVISION
        val lower = start(revision = 2)
        assertTrue(lower.hasRejection())
        assertEquals(RejectionCode.REJECTION_CODE_OUTDATED_REVISION, lower.rejection.code)
        assertEquals(sizeAfterStart, log().size, "거절했는데 로그가 늘었다")

        // 높음 → 갱신
        val higher = start(revision = 4)
        assertTrue(higher.hasRejection().not(), "${higher.rejection}")
        assertEquals(4, higher.handle.revision)
        assertEquals(sizeAfterStart + 1, log().size, "갱신이 로그에 안 남았다")
    }

    @Test
    fun `멱등 재수신은 파라미터도 안 덮는다`() {
        start(revision = 3, parameters = listOf(string("location", "dock-3")))
        start(revision = 3, parameters = listOf(string("location", "dock-9")))
        val task = fixture.registry.require(GrpcFixture.requestHeader("r1"))
            .instance.tasks.find("t1")!!
        assertEquals("dock-3", task.machine.parameters.single().stringValue)
    }

    @Test
    fun `RUNNING 갱신은 로그의 진행률을 0으로 되돌린다`() {
        start(revision = 1)
        settle()
        clock.advance(Duration.ofSeconds(10))
        start(revision = 2)
        assertEquals(0.0, log().last!!.progress, "갱신했는데 진행률이 이어졌다")
        assertEquals(2, log().last!!.revision)
    }

    // ── §4.4의 표 ② — 갱신을 받은 상태 열 개

    @Test
    fun `갱신을 받은 상태 열 개를 표면에서 통째로 확인한다`() {
        // **UpdateOutcome은 거절이 둘이다** — Outdated와 Rejected. 둘이 서로
        // 다른 wire 코드로 가야 하는데, 앞엣것만 시험하면
        // when(o){ Outdated, Rejected -> OUTDATED_REVISION } 가 초록이다.
        // 소비자는 "재전송하면 된다"와 "이 태스크는 끝났다"를 구분하지 못한다.
        val accepting = setOf(
            EngineState.RUNNING, EngineState.PAUSED,
            EngineState.RETRIABLE, EngineState.NEEDS_INTERVENTION,
        )
        assertEquals(4, accepting.size, "표가 비면 '전부 거절'만 확인하고 통과한다")

        // ACCEPTED는 뺀다 — 아래 시험이 그 이유를 적는다.
        EngineState.entries.filterNot { it == EngineState.ACCEPTED }.forEach { state ->
            val separate = GrpcFixture(mapOf("r1" to TaskMachineFixtures.document()), clock)
            separate.use { f ->
                f.tasks.startTask(
                    StartTaskRequest.newBuilder()
                        .setHeader(GrpcFixture.requestHeader("r1"))
                        .setTaskId("t1").setRevision(1).setSkillType("navigate_to")
                        .addParameters(string("location", "dock-3")).build(),
                )
                settle(f)
                val task = f.registry.require(GrpcFixture.requestHeader("r1"))
                    .instance.tasks.find("t1")!!
                driveTo(task, state)

                val response = f.tasks.startTask(
                    StartTaskRequest.newBuilder()
                        .setHeader(GrpcFixture.requestHeader("r1"))
                        .setTaskId("t1").setRevision(2).setSkillType("navigate_to")
                        .addParameters(string("location", "dock-9")).build(),
                )

                if (state in accepting) {
                    assertTrue(response.hasHandle(), "$state 에서 갱신을 거절했다: ${response.rejection}")
                } else {
                    assertTrue(response.hasRejection(), "$state 에서 갱신이 통과했다")
                    assertEquals(
                        RejectionCode.REJECTION_CODE_INVALID_TRANSITION,
                        response.rejection.code,
                        "$state 에서 잘못된 코드가 나왔다",
                    )
                }
            }
        }
    }

    /** §4.5의 사건으로 태스크를 원하는 상태까지 몬다. `at()`처럼 문을 열지 않는다. */
    private fun driveTo(task: dev.picasso.mimic.engine.TaskRuntime, target: EngineState) {
        val m = task.machine
        when (target) {
            EngineState.ACCEPTED -> error("tick()이 이미 집어 들었다 — 따로 시험한다")
            EngineState.RUNNING -> Unit
            EngineState.PAUSED -> m.apply(dev.picasso.mimic.engine.TaskCommand.PAUSE)
            EngineState.CANCELLING -> m.apply(dev.picasso.mimic.engine.TaskCommand.CANCEL)
            EngineState.RETRIABLE -> m.onSkillHalted(Resolution.SELF_RETRIABLE)
            EngineState.NEEDS_INTERVENTION -> m.onSkillHalted(Resolution.NEEDS_INTERVENTION)
            EngineState.FAILED -> m.onSkillHalted(Resolution.TERMINAL)
            EngineState.SUCCEEDED -> m.onSkillComplete()
            EngineState.CANCELLED -> {
                m.apply(dev.picasso.mimic.engine.TaskCommand.CANCEL); m.onRecoveryComplete()
            }
            EngineState.CANCELLED_RECOVERY_FAILED -> {
                m.apply(dev.picasso.mimic.engine.TaskCommand.CANCEL)
                m.onSkillHalted(Resolution.TERMINAL)
            }
        }
        check(m.state == target) { "$target 로 몰지 못했다: ${m.state}" }
    }

    @Test
    fun `ACCEPTED는 로그에 남지만 wire 판정의 대상은 아니다`() {
        // `tick()`이 모든 RPC 진입에서 돌므로, 접수 직후 도착한 요청도
        // 집어 든 **뒤에** 판정된다. 그래서 §4.4의 갱신 표에서 ACCEPTED
        // 행은 wire로 시험할 수 없다 — 엔진 시험(Chunk 2)이 그 행을 본다.
        //
        // 감추지 않고 여기 적는다. §15의 한계다.
        start()
        assertEquals(listOf(EngineState.ACCEPTED), log().from(0).map { it.state })

        val response = start(revision = 2)
        assertTrue(response.hasHandle(), "${response.rejection}")
        assertEquals(
            EngineState.RUNNING,
            fixture.registry.require(GrpcFixture.requestHeader("r1"))
                .instance.tasks.find("t1")!!.machine.state,
            "판정 시점에 ACCEPTED였다 — tick()이 진입에서 돌지 않았다",
        )
    }

    // ── 파라미터 (§5.3·§10.4 ③)

    @Test
    fun `모르는 코어 키는 PARAMETER_INVALID다`() {
        // §5.3 — 코어는 fail-closed.
        val response = start(parameters = listOf(string("location", "dock-3"), string("speed", "9")))
        assertEquals(RejectionCode.REJECTION_CODE_PARAMETER_INVALID, response.rejection.code)
        assertTrue("speed" in response.rejection.detail, response.rejection.detail)
    }

    @Test
    fun `x- 접두 키는 무시하고 통과시킨다`() {
        // §5.3 — 벤더 확장은 fail-open. 코어는 x-를 읽지 않는다.
        val response = start(
            parameters = listOf(string("location", "dock-3"), string("x-acme.gait", "trot")),
        )
        assertTrue(response.hasHandle(), "${response.rejection}")
    }

    @Test
    fun `필수 파라미터가 없으면 PARAMETER_INVALID다`() {
        val response = start(parameters = emptyList())
        assertEquals(RejectionCode.REJECTION_CODE_PARAMETER_INVALID, response.rejection.code)
        assertTrue("location" in response.rejection.detail)
    }

    @Test
    fun `선언한 값 범위를 어기면 PARAMETER_INVALID다`() {
        // §10.4 ③ — 프로파일이 선언한 것을 그대로 강제한다. grip_force는 0..120.
        val response = start(
            skillType = "pick_place",
            parameters = listOf(
                string("object_id", "box-1"), string("destination", "dock-3"),
                ParameterValue.newBuilder().setKey("grip_force").setNumberValue(500.0).build(),
            ),
        )
        assertEquals(RejectionCode.REJECTION_CODE_PARAMETER_INVALID, response.rejection.code)
        assertTrue("grip_force" in response.rejection.detail, response.rejection.detail)
    }

    @Test
    fun `하한과 상한을 둘 다 본다`() {
        // 상한만 어기는 값으로 시험하면 **하한 검사를 지운 구현이 통과한다**
        // (주입 실측). grip_force는 0..120이다.
        listOf(-1.0, 500.0).forEach { value ->
            val response = start(
                taskId = "t-$value",
                skillType = "pick_place",
                parameters = listOf(
                    string("object_id", "box-1"), string("destination", "dock-3"),
                    ParameterValue.newBuilder().setKey("grip_force").setNumberValue(value).build(),
                ),
            )
            assertEquals(
                RejectionCode.REJECTION_CODE_PARAMETER_INVALID, response.rejection.code,
                "grip_force=$value 를 통과시켰다",
            )
        }
    }

    @Test
    fun `범위 안의 값은 통과한다`() {
        // "언제나 거절"하는 구현이 위 시험을 통과한다.
        val response = start(
            skillType = "pick_place",
            parameters = listOf(
                string("object_id", "box-1"), string("destination", "dock-3"),
                ParameterValue.newBuilder().setKey("grip_force").setNumberValue(60.0).build(),
            ),
        )
        assertTrue(response.hasHandle(), "${response.rejection}")
    }

    @Test
    fun `선언한 최대 길이를 어기면 PARAMETER_INVALID다`() {
        val response = start(parameters = listOf(string("location", "x".repeat(65))))
        assertEquals(RejectionCode.REJECTION_CODE_PARAMETER_INVALID, response.rejection.code)
    }

    @Test
    fun `어긴 키를 전부 열거한다`() {
        // 하나만 알려주면 클라이언트가 고칠 때마다 한 번씩 왕복한다.
        val response = start(
            skillType = "pick_place",
            parameters = listOf(string("object_id", "x".repeat(65)), string("nope", "1")),
        )
        val detail = response.rejection.detail
        listOf("object_id", "nope", "destination").forEach {
            assertTrue(it in detail, "'$it' 가 소견에 없다: $detail")
        }
        assertEquals(
            setOf("object_id", "nope", "destination"),
            response.rejection.referencesList
                .filter { it.key == Reference.Key.KEY_PARAMETER_KEY }.map { it.value }.toSet(),
        )
    }

    @Test
    fun `기종 A에서 통과한 값이 기종 B에서 거절된다`() {
        // §10.4 ③의 요점 — 기종 차이가 코드가 아니라 데이터다.
        val tight = ProfileDocument.parse(
            "tight",
            TaskMachineFixtures.fixtureRaw
                .replace(""""model": "minimal"""", """"model": "tight"""")
                .replace(
                    """{ "key": "location", "value_type": "STRING", "optional": false, "max_length": 64 }""",
                    """{ "key": "location", "value_type": "STRING", "optional": false, "max_length": 4 }""",
                ),
        ).getOrThrow()

        GrpcFixture(mapOf("a" to TaskMachineFixtures.document(), "b" to tight)).use { f ->
            val parameters = listOf(string("location", "dock-3"))
            fun send(robot: String) = f.tasks.startTask(
                StartTaskRequest.newBuilder()
                    .setHeader(GrpcFixture.requestHeader(robot))
                    .setTaskId("t1").setRevision(1).setSkillType("navigate_to")
                    .addAllParameters(parameters).build(),
            )
            assertTrue(send("a").hasHandle(), "기종 A에서 거절됐다")
            assertEquals(RejectionCode.REJECTION_CODE_PARAMETER_INVALID, send("b").rejection.code)
        }
    }

    @Test
    fun `갱신이 거절되면 파라미터가 안 바뀐다`() {
        // 교체한 뒤에 검사하면 거절하면서도 상태를 바꿔 놓게 된다.
        start(revision = 1)
        val task = fixture.registry.require(GrpcFixture.requestHeader("r1"))
            .instance.tasks.find("t1")!!

        val response = start(revision = 2, parameters = listOf(string("location", "x".repeat(65))))
        assertEquals(RejectionCode.REJECTION_CODE_PARAMETER_INVALID, response.rejection.code)
        assertEquals("dock-3", task.machine.parameters.single().stringValue)
        assertEquals(1, task.machine.revision, "거절했는데 revision이 올랐다")
    }

    // ── 스킬 부재

    @Test
    fun `모르는 skill_type은 SKILL_ABSENT다`() {
        val response = start(skillType = "weld")
        assertEquals(RejectionCode.REJECTION_CODE_SKILL_ABSENT, response.rejection.code)
    }

    // ── 완료 기준 7

    @Test
    fun `cancel_support가 NO면 CANCEL_UNSUPPORTED가 페이로드로 온다`() {
        // 픽스처의 pick_place가 cancel_support=NO다. **선언이 프로파일에서
        // 투영을 거쳐 온다** — 엔진 시험은 SkillDeclaration을 직접 합성했다.
        start(
            skillType = "pick_place",
            parameters = listOf(string("object_id", "box-1"), string("destination", "dock-3")),
        )
        val response = fixture.tasks.cancelTask(
            CancelTaskRequest.newBuilder()
                .setHeader(GrpcFixture.requestHeader("r1")).setHandle(handle()).build(),
        )
        assertTrue(response.hasRejection(), "취소가 통과했다: ${response.state}")
        assertEquals(RejectionCode.REJECTION_CODE_CANCEL_UNSUPPORTED, response.rejection.code)
    }

    @Test
    fun `pause_support가 NO면 PAUSE_UNSUPPORTED가 페이로드로 온다`() {
        val noPause = ProfileDocument.parse(
            "no-pause",
            Files.readString(Path.of("..", "profile", "fixtures", "no-pause.json").normalize())
                .replace("\r\n", "\n"),
        ).getOrThrow()
        // 전제를 확인한다 — 픽스처가 NO를 선언하지 않으면 이 시험이 공허하다.
        assertTrue(noPause.skills.all { it.pauseSupport == "NO" }, "픽스처가 NO를 선언하지 않는다")

        GrpcFixture(mapOf("r1" to noPause), clock).use { f ->
            f.tasks.startTask(
                StartTaskRequest.newBuilder()
                    .setHeader(GrpcFixture.requestHeader("r1"))
                    .setTaskId("t1").setRevision(1).setSkillType("navigate_to")
                    .addParameters(string("location", "dock-3")).build(),
            )
            val response = f.tasks.pauseTask(
                PauseTaskRequest.newBuilder()
                    .setHeader(GrpcFixture.requestHeader("r1")).setHandle(handle()).build(),
            )
            assertTrue(response.hasRejection(), "일시정지가 통과했다: ${response.state}")
            assertEquals(RejectionCode.REJECTION_CODE_PAUSE_UNSUPPORTED, response.rejection.code)
        }
    }

    @Test
    fun `UNKNOWN이면 시도가 허용된다`() {
        // §7.4 — navigate_to의 pause_support가 UNKNOWN이다.
        start()
        val response = fixture.tasks.pauseTask(
            PauseTaskRequest.newBuilder()
                .setHeader(GrpcFixture.requestHeader("r1")).setHandle(handle()).build(),
        )
        assertEquals(TaskState.TASK_STATE_PAUSED, response.state)
    }

    // ── 거절의 자리와 품질

    @Test
    fun `상태머신 거절은 gRPC 에러가 아니라 응답 oneof다`() {
        // FAILED_PRECONDITION으로 던지는 구현은 "거절이 온다"는 시험을
        // 통과할 수 있고, 소비자는 RPC마다 다른 분기를 쓰게 된다.
        start()
        val response = fixture.tasks.resumeTask(
            ResumeTaskRequest.newBuilder()
                .setHeader(GrpcFixture.requestHeader("r1")).setHandle(handle()).build(),
        )
        assertTrue(response.hasRejection())
        assertEquals(RejectionCode.REJECTION_CODE_INVALID_TRANSITION, response.rejection.code)
    }

    @Test
    fun `모든 거절에 detail과 태스크 참조가 붙는다`() {
        // TaskTransition.Rejected(code, reason)에서 reason을 흘리는 것이
        // 매핑의 자연스러운 실수다.
        start()
        val rejections = listOf(
            fixture.tasks.resumeTask(
                ResumeTaskRequest.newBuilder()
                    .setHeader(GrpcFixture.requestHeader("r1")).setHandle(handle()).build(),
            ).rejection,
            fixture.tasks.retryTask(
                RetryTaskRequest.newBuilder()
                    .setHeader(GrpcFixture.requestHeader("r1")).setHandle(handle()).build(),
            ).rejection,
            start(skillType = "weld", taskId = "t9").rejection,
        )
        rejections.forEach { rejection ->
            assertTrue(rejection.detail.isNotBlank(), "${rejection.code} 에 이유가 없다")
            assertTrue(
                rejection.referencesList.any { it.key == Reference.Key.KEY_TASK_ID },
                "${rejection.code} 가 어느 태스크인지 안 가리킨다",
            )
        }
    }

    @Test
    fun `핸들의 revision이 낮으면 OUTDATED_REVISION이다`() {
        start(revision = 5)
        val response = fixture.tasks.cancelTask(
            CancelTaskRequest.newBuilder()
                .setHeader(GrpcFixture.requestHeader("r1"))
                .setHandle(handle(revision = 4)).build(),
        )
        assertEquals(RejectionCode.REJECTION_CODE_OUTDATED_REVISION, response.rejection.code)
    }

    @Test
    fun `모르는 task_id는 NOT_FOUND다`() {
        val error = assertFailsWith<StatusRuntimeException> {
            fixture.tasks.cancelTask(
                CancelTaskRequest.newBuilder()
                    .setHeader(GrpcFixture.requestHeader("r1"))
                    .setHandle(handle(taskId = "nope")).build(),
            )
        }
        assertEquals(Status.Code.NOT_FOUND, error.status.code)
    }

    @Test
    fun `핸들의 robot_id가 헤더와 다르면 IDENTITY_MISMATCH다`() {
        // 판정이므로 oneof다 — Negotiate와 같은 코드를 쓴다. 같은 사실을
        // 표면마다 다른 기제로 알리면 소비자가 두 분기를 쓰게 된다.
        start()
        val response = fixture.tasks.cancelTask(
            CancelTaskRequest.newBuilder()
                .setHeader(GrpcFixture.requestHeader("r1"))
                .setHandle(handle().toBuilder().setRobotId("r9")).build(),
        )
        assertEquals(RejectionCode.REJECTION_CODE_IDENTITY_MISMATCH, response.rejection.code)
    }

    // ── 취소와 재시도

    @Test
    fun `취소는 CANCELLING을 돌려주고 종착은 그 뒤에 온다`() {
        start()
        val response = fixture.tasks.cancelTask(
            CancelTaskRequest.newBuilder()
                .setHeader(GrpcFixture.requestHeader("r1")).setHandle(handle()).build(),
        )
        assertEquals(TaskState.TASK_STATE_CANCELLING, response.state)
        assertTrue(
            log().from(0).map { it.state }.contains(EngineState.CANCELLING),
            "CANCELLING이 로그에 안 남았다",
        )
    }

    @Test
    fun `전이가 로그에 남는다`() {
        // 응답만 주고 로그에 안 적으면 WatchTask가 전이를 통째로 놓친다.
        start()
        settle()
        val before = log().size
        fixture.tasks.pauseTask(
            PauseTaskRequest.newBuilder()
                .setHeader(GrpcFixture.requestHeader("r1")).setHandle(handle()).build(),
        )
        assertEquals(before + 1, log().size)
        assertEquals(EngineState.PAUSED, log().last!!.state)
    }
}
