package dev.picasso.mimic.report

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.contracts.v1.CapabilityRequirement
import dev.picasso.contracts.v1.NegotiateRequest
import dev.picasso.contracts.v1.NegotiateResponse
import dev.picasso.contracts.v1.ProtocolLimits
import dev.picasso.mimic.engine.TaskMachineFixtures
import dev.picasso.mimic.transport.GrpcFixture
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import dev.picasso.uplink.report.FileHandshakeReporter
import dev.picasso.uplink.report.HandshakeReport
import dev.picasso.uplink.report.RecordingHandshakeReporter
import dev.picasso.uplink.report.FallbackHandshakeReporter

/**
 * §5.4 — *"결과는 성공·실패 모두 `registry`에 보고된다. **보고 실패는
 * 핸드셰이크 결과에 영향을 주지 않는다.**"*
 *
 * ## 두 문장이 서로를 붙든다
 *
 * 보고만 시험하면 **보고가 협상을 망가뜨리는 구현**이 통과한다. 협상만
 * 시험하면 **아무것도 안 보내는 구현**이 통과한다 — 그리고 그 구현은
 * 원장이 비는 것으로만 드러나는데, 빈 원장은 §9.3에서 "모른다"가 되어
 * 축소를 막을 뿐이라 아무도 원인을 안 찾는다.
 */
class HandshakeReportTest {

    private val reporter = RecordingHandshakeReporter()

    private val fixture = GrpcFixture(
        mapOf("r1" to TaskMachineFixtures.document()),
        site = "line-b",
        reporter = reporter,
    )

    @AfterTest fun close() = fixture.close()

    @Test
    fun `성공한 협상이 보고된다`() {
        val response = negotiate(satisfying())

        assertTrue(response.accepted, "픽스처가 만족하지 않으면 이 시험이 뜻을 잃는다")
        val report = reporter.reports.single()
        assertTrue(report.response.accepted)
    }

    @Test
    fun `거절된 협상도 보고된다`() {
        // **실패를 안 보내면 `handshake_rejection`이 영영 빈다.** 그 표는
        // "어느 소비자가 무엇을 요구하다 막혔나"의 유일한 근거다.
        val response = negotiate(satisfying().clearRequirements().addRequirements("pick_place@^9.9"))

        assertTrue(!response.accepted)
        assertEquals(1, reporter.reports.size)
        assertTrue(reporter.reports.single().response.rejectionsList.isNotEmpty())
    }

    @Test
    fun `보고는 응답을 요약하지 않는다`() {
        // 보고자가 줄여 보내면 그 요약이 두 번째 진실이 되고, 보고자가
        // 여럿이 되는 날 서로 다른 원장이 쌓인다.
        val response = negotiate(satisfying().clearRequirements().addRequirements("pick_place@^9.9"))

        val reported = reporter.reports.single().response
        assertEquals(response.accepted, reported.accepted)
        assertEquals(response.rejectionsList, reported.rejectionsList)
    }

    @Test
    fun `보고에 site가 실린다`() {
        // 헤더에 site가 없다. 받는 쪽이 `robot` 표에서 유추하면 등록되지
        // 않은 기체와 협상한 소비자를 적재할 수 없게 된다(§8.3).
        negotiate(satisfying())

        assertEquals("line-b", reporter.reports.single().site)
    }

    @Test
    fun `보고에 요구 전체가 실린다`() {
        negotiate(satisfying())

        assertEquals(
            listOf("pick_place@^1.2", "navigate_to@^1.0"),
            reporter.reports.single().request.requirement.requirementsList,
        )
    }

    // ── §5.4의 두 번째 문장

    @Test
    fun `보고자가 던져도 협상은 성립한다`() {
        val exploding = GrpcFixture(
            mapOf("r1" to TaskMachineFixtures.document()),
            reporter = { throw IllegalStateException("레지스트리가 없다") },
        )
        try {
            val response = exploding.skills.negotiate(
                NegotiateRequest.newBuilder()
                    .setHeader(GrpcFixture.requestHeader("r1"))
                    .setRequirement(satisfying())
                    .build(),
            )

            assertTrue(response.accepted, "보고 실패가 협상 결과를 바꿨다")
        } finally {
            exploding.close()
        }
    }

    // ── 폴백

    @Test
    fun `1차가 실패하면 2차가 받는다`() {
        // **삼키는 것과 잃는 것은 다르다.** 레지스트리가 잠깐 없는 동안의
        // 협상이 통째로 사라지면 원장은 그 소비자를 한 번도 못 본 채
        // 축소를 승인한다.
        val fallback = RecordingHandshakeReporter()
        val chain = FallbackHandshakeReporter({ throw IllegalStateException("끊겼다") }, fallback)

        chain.report(report())

        assertEquals(1, fallback.reports.size)
    }

    @Test
    fun `1차가 되면 2차는 안 받는다`() {
        // 언제나 둘 다 보내는 구현이면 레지스트리가 살아 있는데도 파일이
        // 무한히 자란다.
        val primary = RecordingHandshakeReporter()
        val fallback = RecordingHandshakeReporter()

        FallbackHandshakeReporter(primary, fallback).report(report())

        assertEquals(1, primary.reports.size)
        assertEquals(0, fallback.reports.size)
    }

    // ── 파일

    @Test
    fun `파일 보고자는 한 줄에 하나씩 적는다`() {
        // 하나의 JSON 배열로 적으면 프로세스가 죽은 시점이 곧 파일이 깨진
        // 시점이고, 그때 잃는 것은 마지막 한 줄이 아니라 전부다.
        val path = Files.createTempDirectory("picasso-report").resolve("handshake.jsonl")
        val file = FileHandshakeReporter(path)

        file.report(report())
        file.report(report())

        val lines = Files.readAllLines(path)
        assertEquals(2, lines.size)
        lines.forEach { line ->
            val node = ObjectMapper().readTree(line)
            assertEquals("line-b", node.get("site").asText())
            assertTrue(node.has("request") && node.has("response"), line)
        }
    }

    @Test
    fun `파일 보고자는 디렉터리를 만든다`() {
        val path = Files.createTempDirectory("picasso-report").resolve("nested/handshake.jsonl")

        FileHandshakeReporter(path).report(report())

        assertEquals(1, Files.readAllLines(path).size)
    }

    // ── 씨앗

    private fun satisfying(): CapabilityRequirement.Builder = CapabilityRequirement.newBuilder()
        .setClientId("line-controller")
        .setRobotId("r1")
        .addRequirements("pick_place@^1.2")
        .addRequirements("navigate_to@^1.0")
        .addOptionalFieldsUsed("task.parameters.verify_grasp")
        .setLimitsNeeded(ProtocolLimits.newBuilder().setMaxStringLength(64).setMaxArrayLength(8))

    private fun negotiate(requirement: CapabilityRequirement.Builder): NegotiateResponse =
        fixture.skills.negotiate(
            NegotiateRequest.newBuilder()
                .setHeader(GrpcFixture.requestHeader("r1"))
                .setRequirement(requirement)
                .build(),
        )

    private fun report() = HandshakeReport(
        site = "line-b",
        request = NegotiateRequest.newBuilder()
            .setHeader(GrpcFixture.requestHeader("r1"))
            .setRequirement(satisfying())
            .build(),
        response = NegotiateResponse.newBuilder().setAccepted(true).build(),
    )
}
