package dev.picasso.mimic.transport

import dev.picasso.contracts.v1.GetCapabilitiesRequest
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.SkillServiceGrpc
import dev.picasso.contracts.v1.EventServiceGrpc
import dev.picasso.contracts.v1.TaskServiceGrpc
import dev.picasso.contracts.wire.ContractIdentity
import dev.picasso.contracts.wire.RequestHeaders
import dev.picasso.mimic.RobotInstance
import dev.picasso.mimic.engine.Clock
import dev.picasso.mimic.engine.VirtualClock
import dev.picasso.profile.ProfileDocument
import io.grpc.ManagedChannel
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.time.Instant

/**
 * in-process 전송으로 표면을 세운다. **[MimicServer]를 그대로 쓴다** — 시험이
 * 자기 조립을 하면 CLI가 세우는 것과 달라져 표면을 증명하지 못한다.
 */
class GrpcFixture(
    documents: Map<String, ProfileDocument>,
    val clock: Clock = VirtualClock(Instant.parse("2026-09-06T00:00:00Z")),
    val publisher: RecordingPublisher = RecordingPublisher(),
    site: String = "default",
    reporter: dev.picasso.mimic.report.HandshakeReporter =
        dev.picasso.mimic.report.HandshakeReporter.NONE,
) : AutoCloseable {

    val registry = RobotRegistry(
        documents.map { (id, doc) ->
            RobotInstance(id, doc, clock, publisher = publisher, site = site)
        },
    )

    private val name: String = InProcessServerBuilder.generateName()

    val server = MimicServer(
        registry,
        InProcessServerBuilder.forName(name).directExecutor(),
        reporter,
    ).start()

    private val channel: ManagedChannel =
        InProcessChannelBuilder.forName(name).directExecutor().build()

    val skills: SkillServiceGrpc.SkillServiceBlockingStub = SkillServiceGrpc.newBlockingStub(channel)

    val tasks: TaskServiceGrpc.TaskServiceBlockingStub = TaskServiceGrpc.newBlockingStub(channel)

    val eventsService: EventServiceGrpc.EventServiceBlockingStub =
        EventServiceGrpc.newBlockingStub(channel)

    /** 비동기 스텁 — 열려 있는 WatchTask 스트림을 보는 시험이 쓴다. */
    val tasksAsync: TaskServiceGrpc.TaskServiceStub = TaskServiceGrpc.newStub(channel)

    /** 시계를 흘리고 그것이 만든 전이를 열린 스트림까지 민다. */
    fun advance(duration: java.time.Duration) = server.advance(duration)

    fun capabilitiesOf(robotId: String) = skills.getCapabilities(
        GetCapabilitiesRequest.newBuilder().setHeader(requestHeader(robotId)).build(),
    )

    override fun close() {
        channel.shutdownNow()
        server.shutdown()
    }

    companion object {
        const val SCHEMA_ID = "picasso.v1.GetCapabilitiesRequest"

        fun requestHeader(robotId: String, clientId: String = "line-controller"): MessageHeader =
            RequestHeaders.build(SCHEMA_ID, robotId, clientId)

        /**
         * **응답 열의 금지 단언은 이것 없이는 공허하다.** 요청이 깨끗하면
         * 응답 헤더를 요청의 복사로 만드는 구현이 빈 헤더를 돌려주고 "실어야
         * 하는 것"에서 걸린다 — 우연히 잡히는 것이다. 금지 필드를 실제로
         * 실어 보내야 금지 단언이 일한다.
         */
        fun poisonedHeader(robotId: String): MessageHeader =
            requestHeader(robotId).toBuilder()
                .setSequence(9_999)
                .setCapabilityEpoch(777)
                .setSessionId("poison-session")
                .setUpdateIndex(555)
                .setEventId("poison-event")
                .setOccurredAt("1999-01-01T00:00:00Z")
                .setStateAsOf("1999-01-01T00:00:00Z")
                .setProfileRef(
                    dev.picasso.contracts.v1.ProfileRef.newBuilder()
                        .setProfileId("poison/profile").setRevision(999),
                )
                .build()

        /** 계약 개정판을 일부러 어긋나게 만든 요청 헤더. */
        fun headerWithSemver(robotId: String, semver: String): MessageHeader =
            RequestHeaders.buildWithContract(
                SCHEMA_ID, robotId, "line-controller", semver, ContractIdentity.digest,
            )
    }
}
