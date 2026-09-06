package dev.picasso.harness

import dev.picasso.contracts.v1.TaskState
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 공통 태스크가 **실제로 완주했는지**.
 *
 * `A1Test`와 `AllModelsTest`가 같은 것을 쓴다. 따로 쓰면 한쪽이 약해지는데,
 * 약해지는 쪽은 언제나 나중에 쓴 것이다.
 *
 * **`accepted`만 보면 안 된다.** 소요시간이 하네스의 전진 폭을 넘는 기종은
 * `RUNNING`인 채로 끝나고 그 단언을 통과한다 — "완주했다"가 "거절당하지
 * 않았다"로 조용히 약해진다.
 */
fun assertCompleted(label: String, outcome: ContractSuite.CommonTaskOutcome) {
    assertTrue(outcome.accepted, "$label 에서 거절됐다: ${outcome.rejection}")

    val follower = outcome.follower!!
    assertNull(follower.error, "$label: ${follower.error}")

    assertEquals(
        listOf(
            TaskState.TASK_STATE_ACCEPTED,
            TaskState.TASK_STATE_RUNNING,
            TaskState.TASK_STATE_SUCCEEDED,
        ),
        follower.updates.map { it.state },
        "$label 에서 완주하지 않았다",
    )
    assertEquals(0.0, follower.updates.first().progress, "$label: 시작 전인데 진행률이 있다")
    assertEquals(1.0, follower.updates.last().progress, "$label: 완주인데 진행률이 1이 아니다")
    assertTrue(follower.completed, "$label: 종착인데 스트림을 안 닫았다")
}
