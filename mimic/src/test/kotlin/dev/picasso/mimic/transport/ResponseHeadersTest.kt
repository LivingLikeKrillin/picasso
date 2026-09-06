package dev.picasso.mimic.transport

import dev.picasso.contracts.v1.GetCapabilitiesResponse
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.NegotiateResponse
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.WatchTaskResponse
import dev.picasso.mimic.RobotInstance
import dev.picasso.mimic.engine.Clock
import dev.picasso.mimic.engine.TaskMachineFixtures
import dev.picasso.mimic.engine.VirtualClock
import java.io.ByteArrayInputStream
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContractIdentityTest {

    @Test
    fun `계약 신원을 클래스패스에서 읽는다`() {
        assertEquals("0.1.0", ContractIdentity.semver)
        assertTrue(
            ContractIdentity.digest.matches(Regex("[0-9a-f]{64}")),
            "다이제스트가 SHA-256 16진수가 아니다: ${ContractIdentity.digest}",
        )
    }

    @Test
    fun `리소스가 없으면 기동하지 않는다`() {
        // 조용히 빈 문자열을 싣는 구현은 "계약이 무엇인지 모른다"를
        // "계약이 없다"로 보이게 한다. §6.2의 메시지마다 판정이 죽는다.
        assertFailsWith<IllegalStateException> { ContractIdentity.from(null) }
    }

    @Test
    fun `키가 비어도 기동하지 않는다`() {
        // 파일은 있는데 굽는 태스크가 반쯤 돈 경우다. 파일 존재만 보는
        // 구현은 이것을 통과시킨다.
        listOf("", "semver=0.1.0\n", "digest=abc\n", "semver=\ndigest=\n").forEach { text ->
            assertFailsWith<IllegalStateException>("'$text' 를 받아들였다") {
                ContractIdentity.from(ByteArrayInputStream(text.toByteArray()))
            }
        }
    }
}

/** §5.5의 gRPC 응답 열이 이 시험의 명세다. */
class ResponseHeadersTest {

    /** 응답에 **실어야 하는** 것. */
    private val required = listOf(
        "schema_id", "contract_digest", "contract_semver", "robot_id",
        "capability_epoch", "session_id", "profile_ref",
        "event_id", "occurred_at", "state_as_of",
    )

    /** 응답에 **실으면 안 되는** 것. `sequence`는 MQTT, `client_id`는 요청 전용. */
    private val forbidden = listOf("sequence", "client_id")

    /** `WatchTask` 스트림에서만 실린다. */
    private val watchOnly = listOf("update_index")

    @Test
    fun `표가 비어 있지 않고 자기모순이 아니다`() {
        assertEquals(10, required.size)
        assertEquals(2, forbidden.size)
        // 겹치면 아래 시험이 스스로 모순인 채로 통과한다.
        assertTrue(required.intersect(forbidden.toSet()).isEmpty())

        // MessageHeader의 필드를 하나도 빠짐없이 분류했는가. 필드가 늘면
        // 여기서 걸린다 — 게터를 직접 부르는 시험은 새 필드를 못 본다.
        assertEquals(
            MessageHeader.getDescriptor().fields.map { it.name }.toSet(),
            (required + forbidden + watchOnly).toSet(),
            "MessageHeader에 분류되지 않은 필드가 있다 — §5.5의 표를 갱신하라",
        )
    }

    @Test
    fun `응답 종류 넷 모두가 §5-5의 표를 따른다`() {
        // 한 응답만 보면 다른 셋이 침묵한다. 실제로 실수는 RPC 하나를
        // 다르게 짜면서 생긴다.
        listOf(
            GetCapabilitiesResponse.getDescriptor(),
            NegotiateResponse.getDescriptor(),
            StartTaskResponse.getDescriptor(),
            WatchTaskResponse.getDescriptor(),
        ).forEach { descriptor ->
            val header = headers().forResponse(descriptor)
            required.forEach {
                assertTrue(isSet(header, it), "${descriptor.name}: $it 가 비었다 — §5.5는 응답에 싣는다")
            }
            forbidden.forEach {
                assertFalse(isSet(header, it), "${descriptor.name}: $it 를 실었다 — §5.5는 싣지 않는다")
            }
        }
    }

    @Test
    fun `update_index는 WatchTask에서만 실린다`() {
        assertFalse(isSet(headers().forResponse(GetCapabilitiesResponse.getDescriptor()), "update_index"))
        assertTrue(
            isSet(headers().forResponse(WatchTaskResponse.getDescriptor(), updateIndex = 3), "update_index"),
        )
        assertEquals(
            3L,
            headers().forResponse(WatchTaskResponse.getDescriptor(), updateIndex = 3).updateIndex,
        )
    }

    @Test
    fun `schema_id는 응답 메시지마다 다르다`() {
        // 상수로 박으면 §6.2의 "메시지마다 판정"이 통째로 무의미해진다.
        val h = headers()
        assertEquals(
            "picasso.v1.GetCapabilitiesResponse",
            h.forResponse(GetCapabilitiesResponse.getDescriptor()).schemaId,
        )
        assertEquals(
            "picasso.v1.NegotiateResponse",
            h.forResponse(NegotiateResponse.getDescriptor()).schemaId,
        )
    }

    @Test
    fun `event_id는 응답마다 새것이다`() {
        // 소비자 측 멱등 처리 키다(§5.5). 겹치면 소비자가 새 메시지를 버린다.
        val h = headers()
        val ids = (1..50).map { h.forResponse(GetCapabilitiesResponse.getDescriptor()).eventId }
        assertEquals(50, ids.toSet().size, "event_id가 중복됐다")
    }

    @Test
    fun `event_id는 기체마다 갈린다`() {
        // 카운터를 전역으로 두면 위 시험은 통과하고 기체 구분만 사라진다.
        val a = headers(robotId = "r1").forResponse(GetCapabilitiesResponse.getDescriptor()).eventId
        val b = headers(robotId = "r2").forResponse(GetCapabilitiesResponse.getDescriptor()).eventId
        assertTrue(a.startsWith("r1-"), a)
        assertTrue(b.startsWith("r2-"), b)
    }

    @Test
    fun `occurred_at과 state_as_of가 시계에서 온다`() {
        // Instant.now()를 쓰면 가상 시계가 무의미해지고 §12.1이 깨진다.
        val clock = VirtualClock(Instant.parse("2026-09-06T00:00:00Z"))
        val h = ResponseHeaders(instance(clock = clock))

        val first = h.forResponse(GetCapabilitiesResponse.getDescriptor())
        assertEquals("2026-09-06T00:00:00Z", first.occurredAt)
        assertEquals("2026-09-06T00:00:00Z", first.stateAsOf)

        clock.advance(Duration.ofSeconds(90))
        assertEquals(
            "2026-09-06T00:01:30Z",
            h.forResponse(GetCapabilitiesResponse.getDescriptor()).occurredAt,
        )
    }

    @Test
    fun `profile_ref가 프로파일에서 온다`() {
        // registry가 이벤트를 적재할 때 개정판 귀속에 쓴다 — 카나리 중 두
        // 개정판이 동시에 도는 것을 관측하는 유일한 수단이다(§5.5).
        val header = headers().forResponse(GetCapabilitiesResponse.getDescriptor())
        assertEquals("fixture/minimal", header.profileRef.profileId)
        assertEquals(1, header.profileRef.revision)
    }

    @Test
    fun `robot_id와 session_id가 기체에서 온다`() {
        val instance = instance(robotId = "r7")
        val header = ResponseHeaders(instance).forResponse(GetCapabilitiesResponse.getDescriptor())
        assertEquals("r7", header.robotId)
        assertEquals(instance.sessionId, header.sessionId)
    }

    @Test
    fun `capability_epoch는 1에서 시작한다`() {
        // 0에서 시작하면 proto3 암묵 존재 때문에 첫 세대가 헤더에서 사라진다.
        assertEquals(
            1L,
            headers().forResponse(GetCapabilitiesResponse.getDescriptor()).capabilityEpoch,
        )
    }

    // ── 헬퍼

    private fun instance(
        robotId: String = "r1",
        clock: Clock = VirtualClock(Instant.parse("2026-09-06T00:00:00Z")),
    ) = RobotInstance(robotId, TaskMachineFixtures.document(), clock)

    private fun headers(
        robotId: String = "r1",
        clock: Clock = VirtualClock(Instant.parse("2026-09-06T00:00:00Z")),
    ) = ResponseHeaders(instance(robotId, clock))

    /**
     * 필드를 **이름으로** 조회한다. 게터를 직접 부르면 필드가 늘 때 시험이
     * 컴파일은 되고 새 필드를 조용히 안 보게 된다.
     */
    private fun isSet(header: MessageHeader, field: String): Boolean {
        val fd = requireNotNull(MessageHeader.getDescriptor().findFieldByName(field)) {
            "MessageHeader에 '$field' 필드가 없다"
        }
        // proto3의 암묵 존재 스칼라는 hasField가 던진다. 메시지 필드만 존재를 갖는다.
        return if (fd.hasPresence()) header.hasField(fd) else header.getField(fd) != fd.defaultValue
    }
}
