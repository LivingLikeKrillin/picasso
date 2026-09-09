package dev.picasso.harness

import dev.picasso.client.TaskFollower
import dev.picasso.contracts.v1.WatchTaskResponse
import java.time.Duration

/**
 * 스트림이 종착까지 오기를 기다린다 — 비동기 스텁이라 바로 안 와 있을 수 있다.
 *
 * **마감을 건다.** 안 걸면 결함이 시험 실패가 아니라 빌드 정지로 나타난다.
 */
fun TaskFollower.awaitTerminal(timeout: Duration = Duration.ofSeconds(5)): List<WatchTaskResponse> {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (System.nanoTime() < deadline) {
        if (completed) return updates
        Thread.sleep(20)
    }
    error("스트림이 종착하지 않았다: ${updates.map { it.state }}")
}

/**
 * 갱신이 [count]개 이상 쌓이기를 기다린다 — 종착이 아직 아닌 태스크의 중간을 볼 때.
 */
fun TaskFollower.awaitUpdates(count: Int, timeout: Duration = Duration.ofSeconds(5)): List<WatchTaskResponse> {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (System.nanoTime() < deadline) {
        if (updates.size >= count) return updates
        Thread.sleep(20)
    }
    error("갱신이 ${count}개까지 안 왔다: ${updates.map { it.state }}")
}
