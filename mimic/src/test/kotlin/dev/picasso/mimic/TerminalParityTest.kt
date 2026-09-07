package dev.picasso.mimic

import dev.picasso.contracts.wire.TaskStates
import dev.picasso.contracts.wire.isTerminal
import dev.picasso.mimic.engine.TaskState
import dev.picasso.mimic.engine.toProto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import dev.picasso.contracts.v1.TaskState as ProtoTaskState

/**
 * **종착 집합이 두 벌이다. 이 시험이 둘을 붙들어 맨다.**
 *
 * `mimic`의 엔진 enum이 전이표의 주인이고(상태 기계가 거기 있다), 계약의
 * [TaskStates]는 `registry`가 §9.3의 드레인을 세려고 읽는다. 두 벌이 갈라지면
 * `registry`는 종착을 비종착으로 세고 **드레인이 영원히 0이 안 되어 축소가
 * 영원히 막힌다** — 사고는 안 나지만 기능이 죽고, 축소는 원래도 자주 막히므로
 * 죽은 것이 안 보인다.
 *
 * **이 시험이 `mimic`에 있는 이유는 여기만 두 enum을 다 알기 때문이다.**
 * `contracts`는 프로젝트 내 의존이 0이라 엔진 enum을 볼 수 없고,
 * `registry`는 `mimic`을 모른다(§3.2).
 *
 * **망라적이어야 한다.** 대표값 몇 개만 보면 새로 생긴 상태가 두 곳 중 한
 * 곳에만 들어간 것을 놓친다 — 그것이 정확히 이 시험이 막으려는 사고다.
 */
class TerminalParityTest {

    @Test
    fun `엔진의 모든 상태가 계약에서 같은 종착 판정을 받는다`() {
        val mismatched = TaskState.entries.filter { engine ->
            engine.isTerminal != engine.toProto().isTerminal
        }

        assertTrue(
            mismatched.isEmpty(),
            "종착 판정이 갈라졌다: " + mismatched.joinToString {
                "$it(엔진=${it.isTerminal}, 계약=${it.toProto().isTerminal})"
            },
        )
    }

    @Test
    fun `계약의 모든 값이 엔진에 대응한다 — UNSPECIFIED만 빼고`() {
        // 계약에 상태가 생겼는데 엔진이 모르면 **`registry`는 그것을 세지만
        // `mimic`은 그 상태로 못 간다.** 반대 방향의 드리프트라 위 시험이
        // 안 잡는다.
        val engineNames = TaskState.entries.map { it.toProto() }.toSet()
        val missing = ProtoTaskState.entries
            .filterNot { it == ProtoTaskState.TASK_STATE_UNSPECIFIED }
            .filterNot { it == ProtoTaskState.UNRECOGNIZED }
            .filterNot { it in engineNames }

        assertEquals(emptyList(), missing, "계약에만 있는 상태다")
    }

    @Test
    fun `UNSPECIFIED는 종착이 아니다`() {
        // 모르는 값을 종착으로 접으면 낯선 발신자의 태스크가 드레인에서
        // 사라지고, 그 위에서 축소가 열린다. 모르면 "아직 돌고 있다"가
        // 안전한 쪽이다.
        assertTrue(!ProtoTaskState.TASK_STATE_UNSPECIFIED.isTerminal)
        assertTrue(!ProtoTaskState.UNRECOGNIZED.isTerminal)
    }

    @Test
    fun `종착 집합이 비어 있지 않고 전부도 아니다`() {
        // 언제나 참·언제나 거짓인 구현이 위 시험들을 통과하는 것을 막는다.
        assertTrue(TaskStates.TERMINAL.isNotEmpty(), "종착이 하나도 없다")
        assertTrue(
            TaskState.entries.any { !it.toProto().isTerminal },
            "전부 종착이면 드레인이 언제나 0이다",
        )
    }
}
