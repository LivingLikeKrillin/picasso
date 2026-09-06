package dev.picasso.mimic.transport

import dev.picasso.contracts.v1.EventServiceGrpc
import dev.picasso.contracts.v1.GetSnapshotRequest
import dev.picasso.contracts.v1.GetSnapshotResponse
import dev.picasso.contracts.v1.Rejection
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.ReplayEventsRequest
import dev.picasso.contracts.v1.ReplayEventsResponse
import io.grpc.stub.StreamObserver

/**
 * §4.4의 `GetSnapshot`·`ReplayEvents`. A-2의 재구성이 이 둘 위에 선다.
 */
class EventServiceImpl(
    private val registry: RobotRegistry,
) : EventServiceGrpc.EventServiceImplBase() {

    override fun getSnapshot(
        request: GetSnapshotRequest,
        observer: StreamObserver<GetSnapshotResponse>,
    ) = reply(observer) {
        val hosted = registry.require(request.header)
        val events = hosted.instance.events

        GetSnapshotResponse.newBuilder()
            .setHeader(hosted.headers.forResponse(GetSnapshotResponse.getDescriptor()))
            // **다음에 올 번호다.** "대응하는 번호"로 두면 0이 "0번까지
            // 반영했다"와 "아직 아무 이벤트도 없다" 둘을 뜻한다(§4.8이
            // sequence를 0부터라고 못박은 이상 다른 방법이 없다).
            .setSequence(events.nextSequence)
            .addAllSkills(events.skillSnapshots())
            .addAllTasks(events.taskSnapshots())
            .build()
    }

    /**
     * §4.8의 재생 버퍼를 요청하는 **유일한** 표면이다.
     *
     * 요청한 `from_sequence`가 버퍼를 벗어났으면 `SEQUENCE_EVICTED`로 답하고
     * 소비자는 `GetSnapshot`부터 다시 세운다.
     */
    override fun replayEvents(
        request: ReplayEventsRequest,
        observer: StreamObserver<ReplayEventsResponse>,
    ) {
        val hosted = try {
            registry.require(request.header)
        } catch (e: io.grpc.StatusRuntimeException) {
            observer.onError(e); return
        }

        val buffered = hosted.instance.events.buffered
        val oldest = buffered.firstOrNull()?.header?.sequence

        // 버퍼가 비어 있으면 축출이 아니다 — 아직 아무 일도 없었을 뿐이다.
        if (oldest != null && request.fromSequence < oldest) {
            observer.onNext(
                ReplayEventsResponse.newBuilder()
                    .setHeader(hosted.headers.forResponse(ReplayEventsResponse.getDescriptor()))
                    .setRejection(
                        Rejection.newBuilder()
                            .setCode(RejectionCode.REJECTION_CODE_SEQUENCE_EVICTED)
                            .setDetail(
                                "재생 버퍼를 벗어났다: 요청=${request.fromSequence}, " +
                                    "버퍼=[$oldest, ${buffered.last().header.sequence}] " +
                                    "— GetSnapshot부터 다시 세워라",
                            ),
                    )
                    .build(),
            )
            // **닫는다.** 안 닫으면 소비자의 블로킹 반복자가 영영 안 돌아오고,
            // 결함이 시험 실패가 아니라 **정지**로 나타난다.
            observer.onCompleted()
            return
        }

        buffered.filter { it.header.sequence >= request.fromSequence }.forEach { event ->
            observer.onNext(
                ReplayEventsResponse.newBuilder()
                    // 바깥 헤더는 **응답 열**이고 안쪽 이벤트의 헤더는
                    // **발행 열**이다. 한 메시지가 두 열을 함께 든다.
                    .setHeader(hosted.headers.forResponse(ReplayEventsResponse.getDescriptor()))
                    // **원본 그대로다.** 다시 찍으면 `event_id`가 바뀌어
                    // 소비자의 멱등 처리가 무너지고, `occurred_at`이 바뀌어
                    // 30초 전 사건이 방금 일어난 것으로 보인다.
                    .setEvent(event)
                    .build(),
            )
        }
        observer.onCompleted()
    }
}
