package dev.picasso.mimic.transport

import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.StateMessage
import dev.picasso.contracts.wire.ContractIdentity
import dev.picasso.contracts.wire.HeaderColumns
import dev.picasso.mimic.RobotInstance
import dev.picasso.mimic.engine.TaskMachineFixtures
import dev.picasso.mimic.engine.VirtualClock
import dev.picasso.profile.ProfileDocument
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventStreamTest {

    private val clock = VirtualClock(Instant.parse("2026-09-06T00:00:00Z"))
    private val publisher = RecordingPublisher()

    private fun instance(
        robotId: String = "r1",
        document: ProfileDocument = TaskMachineFixtures.document(),
        sink: Publisher = publisher,
    ): RobotInstance = RobotInstance(robotId, document, clock, publisher = sink, site = "line-a")

    private fun location(value: String = "dock-3") =
        ParameterValue.newBuilder().setKey("location").setStringValue(value).build()

    private fun runTask(robot: RobotInstance, taskId: String = "t1") {
        robot.tasks.start(taskId, 1, "navigate_to", listOf(location()))
        robot.tasks.tick()
        clock.advance(Duration.ofSeconds(20))
        robot.tasks.tick()
    }

    // ── sequence (§4.8)

    @Test
    fun `sequence가 0부터 단조 증가한다`() {
        val robot = instance()
        runTask(robot)

        val sequences = publisher.publications.map { it.sequence }
        assertTrue(sequences.isNotEmpty(), "발행이 하나도 없다")
        assertEquals((0L until sequences.size).toList(), sequences)
    }

    @Test
    fun `기체마다 독립이다`() {
        // 전역 카운터를 쓰면 소비자가 자기 기체의 번호에서 구멍을 본다.
        val a = instance("r1")
        val b = instance("r2")
        runTask(a, "ta")
        runTask(b, "tb")

        val byRobot = publisher.publications.groupBy { it.message.let { m ->
            (m as Event).header.robotId
        } }
        assertEquals(2, byRobot.size)
        byRobot.forEach { (robot, published) ->
            assertEquals(
                (0L until published.size).toList(), published.map { it.sequence },
                "$robot 의 번호가 0부터 안 센다",
            )
        }
    }

    @Test
    fun `WatchTask의 진행률은 sequence를 안 먹는다`() {
        // §3.5의 두 축. 같은 카운터를 쓰면 gRPC에만 나가는 진행률이 소비한
        // 번호를 MQTT 소비자가 결손으로 오탐한다.
        val robot = instance()
        robot.tasks.start("t1", 1, "navigate_to", listOf(location()))
        robot.tasks.tick()
        val afterStart = publisher.publications.size

        // 로그에는 갱신이 쌓이지만 발행은 늘지 않는다.
        repeat(3) { robot.tasks.record(robot.tasks.find("t1")!!) }
        assertEquals(afterStart, publisher.publications.size, "로그 기록이 sequence를 먹었다")
        assertTrue(robot.tasks.find("t1")!!.log.size > afterStart)
    }

    // ── 헤더 (§5.5의 발행 열)

    @Test
    fun `발행 헤더가 §5-5의 발행 열을 따른다`() {
        val robot = instance()
        runTask(robot)

        // **0번은 따로 본다.** sequence가 0부터라 proto3 암묵 존재로는
        // 미설정과 구별되지 않는다(§15.25). 값으로 확인한다.
        val first = (publisher.publications.first().message as Event).header
        (HeaderColumns.PUBLISH - "sequence").forEach {
            assertTrue(HeaderColumns.isSet(first, it), "$it 가 비었다")
        }
        assertEquals(0L, first.sequence)

        publisher.publications.drop(1).forEach { publication ->
            val header = (publication.message as Event).header
            HeaderColumns.PUBLISH.forEach {
                assertTrue(HeaderColumns.isSet(header, it), "$it 가 비었다")
            }
            (HeaderColumns.ALL - HeaderColumns.PUBLISH).forEach {
                assertFalse(HeaderColumns.isSet(header, it), "$it 를 발행에 실었다")
            }
        }
    }

    @Test
    fun `event_id가 발행마다 새것이다`() {
        val robot = instance()
        runTask(robot)
        val ids = publisher.publications.map { (it.message as Event).header.eventId }
        assertEquals(ids.size, ids.toSet().size, "event_id가 겹쳤다 — 소비자의 멱등 키다")
    }

    @Test
    fun `계약 신원과 세션이 헤더에 실린다`() {
        val robot = instance()
        runTask(robot)
        val header = (publisher.publications.first().message as Event).header
        assertEquals(ContractIdentity.semver, header.contractSemver)
        assertEquals(robot.sessionId, header.sessionId)
        assertEquals("fixture/minimal", header.profileRef.profileId)
    }

    // ── 토픽 (§5.5)

    @Test
    fun `토픽이 §5-5의 형식이다`() {
        val robot = instance()
        runTask(robot)
        assertEquals(
            "picasso/${ContractIdentity.major}/line-a/robot/r1/event",
            publisher.publications.first().topic,
        )
    }

    @Test
    fun `state와 event가 다른 스트림이다`() {
        val robot = instance()
        val stream = robot.events
        runTask(robot)
        stream.publishState()

        val topics = publisher.publications.map { it.topic }.toSet()
        assertEquals(
            setOf(
                "picasso/${ContractIdentity.major}/line-a/robot/r1/event",
                "picasso/${ContractIdentity.major}/line-a/robot/r1/state",
            ),
            topics,
        )
        assertTrue(publisher.publications.last().message is StateMessage)
    }

    @Test
    fun `토픽으로 쓸 수 없는 값을 미리 막는다`() {
        // 브로커를 안 붙였으므로 이것이 유일한 방어다. 와일드카드가 섞인
        // robot_id는 실제 브로커가 거절하고, 여기서 안 막으면 3단계에
        // 붙이는 순간 드러난다.
        listOf("", "r+1", "r#1", "a/b", "r 1").forEach { bad ->
            assertFailsWith<IllegalArgumentException>("'$bad' 를 받아들였다") {
                Topics.robot(0, "line-a", bad, Topics.Stream.event)
            }
        }
        listOf("", "line+a", "line#a").forEach { bad ->
            assertFailsWith<IllegalArgumentException>("site '$bad' 를 받아들였다") {
                Topics.robot(0, bad, "r1", Topics.Stream.event)
            }
        }
    }

    @Test
    fun `스트림 값 집합이 셋이다`() {
        // §3.5 — state·event·connection. 늘면 여기서 걸린다.
        assertEquals(
            setOf("state", "event", "connection"),
            Topics.Stream.entries.map { it.name }.toSet(),
        )
    }

    // ── 재생 버퍼 (§4.8)

    @Test
    fun `버퍼가 프로파일의 N을 따르고 기체마다 다르다`() {
        // N이 코드 상수면 프로파일이 선언하는 뜻이 없다.
        val small = ProfileDocument.parse(
            "small",
            TaskMachineFixtures.fixtureRaw.replace("\"replay_buffer_size\": 256", "\"replay_buffer_size\": 3"),
        ).getOrThrow()
        val robot = instance("r9", small)
        val stream = robot.events
        runTask(robot)

        assertTrue(
            publisher.publications.size > 3,
            "발행이 N보다 적어 축출이 안 일어난다: ${publisher.publications.size}",
        )
        assertEquals(3, stream.buffered.size, "버퍼가 N을 안 지킨다")
    }

    @Test
    fun `실제 기종의 N이 서로 다르다`() {
        // 프로파일에서 온다는 것을 실제 문서로 확인한다.
        val sizes = listOf("humanoid-a", "quadruped-b", "quadruped-c").map { name ->
            val path = Path.of("..", "profile", "profiles", "$name.json").normalize()
            ProfileDocument.parse(path.toString(), Files.readString(path).replace("\r\n", "\n"))
                .getOrThrow().replayBufferSize
        }
        assertEquals(sizes.distinct(), sizes, "세 기종의 N이 같다: $sizes")
    }

    @Test
    fun `버퍼가 오래된 것부터 버린다`() {
        val small = ProfileDocument.parse(
            "small",
            TaskMachineFixtures.fixtureRaw.replace("\"replay_buffer_size\": 256", "\"replay_buffer_size\": 2"),
        ).getOrThrow()
        val robot = instance("r9", small)
        val stream = robot.events
        runTask(robot)

        val all = publisher.publications.map { (it.message as Event).header.sequence }
        assertEquals(all.takeLast(2), stream.buffered.map { it.header.sequence })
    }

    @Test
    fun `발행이 막혀도 버퍼에는 남는다`() {
        // §10.6 — 단절 중 쌓았다가 재연결 시 재생한다. 발행 뒤에 넣으면
        // 장애 주입이 버퍼까지 비운다.
        val blocked = Publisher { error("발행 불통") }
        val robot = instance("r1", sink = blocked)

        assertFailsWith<IllegalStateException> {
            robot.tasks.start("t1", 1, "navigate_to", listOf(location()))
        }
        assertEquals(1, robot.events.buffered.size, "발행이 막히자 버퍼도 비었다")
    }

    // ── 스냅샷의 키 (§4.5의 실제 모습)

    @Test
    fun `스킬 스냅샷이 task_id로 갈린다`() {
        // 같은 스킬 타입의 태스크가 둘 돌면 skill_type만으로는 키가 되지
        // 않는다 — 소비자의 맵에서 어느 쪽이 이겼는지 미정의가 된다.
        val robot = instance()
        val stream = robot.events
        robot.tasks.start("t1", 1, "navigate_to", listOf(location()))
        robot.tasks.start("t2", 1, "navigate_to", listOf(location("dock-9")))
        robot.tasks.tick()

        val skills = stream.skillSnapshots()
        assertEquals(2, skills.size, "$skills")
        assertEquals(setOf("t1", "t2"), skills.map { it.taskId }.toSet())
        assertEquals(1, skills.map { it.skillType }.toSet().size, "전제가 무너졌다")
    }
}
