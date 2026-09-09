package dev.picasso.mimic.engine

import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.HoldState
import java.time.Instant

/**
 * 태스크 갱신 하나. `WatchTaskResponse`가 담는 것이 이것이다.
 *
 * **`occurredAt`을 기록해 둔다.** 되짚어 보낼 때 지금 시각을 실으면 소비자의
 * 신선도 판정(§5.5의 `state_as_of`)이 거짓말을 한다 — 30초 전 전이가 방금
 * 일어난 것으로 보인다.
 */
data class TaskUpdate(
    val updateIndex: Long,
    val state: TaskState,
    val revision: Int,
    val attempt: Int,
    val progress: Double,
    val partialResult: String = "",
    val occurredAt: Instant,
    /** §4.4의 잔여 물리 상태. 기본값(`UNSPECIFIED`)은 "엔진이 정하지 않았다"이며 [TaskHost.record]는 언제나 정한다. */
    val hold: HoldState = HoldState.getDefaultInstance(),
    /**
     * 종착이 실패(`FAILED`·`RETRIABLE`·`NEEDS_INTERVENTION`)일 때 그 태스크를 보낸
     * 결함 — 계약의 `WatchTaskResponse.fault`(*"종착이 실패인 경우에만 채워진다"*).
     * 정준 분류(`failure_class`)가 여기 실려 상류에 닿는다. 다른 상태에서는 `null`.
     */
    val fault: Fault? = null,
)

/**
 * 태스크 하나의 갱신 로그. `WatchTask(from_update_index)`가 읽는다.
 *
 * **기체 단위 재생 버퍼(§4.8)와 다른 것이다.** 저쪽은 `sequence` 축이고
 * 크기 `N`으로 축출되며 벗어나면 `SEQUENCE_EVICTED`다. 이쪽은 태스크 단위이고
 * 축출이 없다 — 태스크가 끝나면 통째로 사라지므로 무한히 자라지 않는다.
 * 나누지 않으면 gRPC에만 나가는 진행률이 소비한 번호를 MQTT 소비자가 결손으로
 * 오탐한다(§3.5).
 */
class TaskLog {

    private val entries = mutableListOf<TaskUpdate>()

    val size: Int get() = entries.size

    val last: TaskUpdate? get() = entries.lastOrNull()

    /** 색인은 0부터 단조 증가한다. 호출자가 번호를 정하지 않는다. */
    fun record(
        state: TaskState,
        revision: Int,
        attempt: Int,
        progress: Double,
        occurredAt: Instant,
        partialResult: String = "",
        hold: HoldState = HoldState.getDefaultInstance(),
        fault: Fault? = null,
    ): TaskUpdate {
        val update = TaskUpdate(
            updateIndex = entries.size.toLong(),
            state = state,
            revision = revision,
            attempt = attempt,
            progress = progress,
            partialResult = partialResult,
            occurredAt = occurredAt,
            hold = hold,
            fault = fault,
        )
        entries += update
        return update
    }

    /**
     * `from` 이후를 준다.
     *
     * **범위를 넘으면 조용히 빈 목록을 주지 않는다.** 그러면 재접속한 소비자가
     * 오지 않을 갱신을 영원히 기다린다. 아직 없는 색인을 요구한 것은 요청을
     * 해석할 수 없는 경우이므로 호출자가 `OUT_OF_RANGE`로 옮긴다.
     */
    fun from(index: Long): List<TaskUpdate> {
        require(index >= 0) { "from_update_index가 음수다: $index" }
        require(index <= entries.size) {
            "아직 없는 갱신을 요구했다: from_update_index=$index, 로그 크기=${entries.size}"
        }
        return entries.drop(index.toInt())
    }
}
