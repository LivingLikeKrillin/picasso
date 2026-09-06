package dev.picasso.mimic.engine

import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.Lifetime
import dev.picasso.contracts.v1.Reference
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FaultRegistryTest {

    private val clock = VirtualClock(Instant.parse("2026-09-06T00:00:00Z"))
    private val registry = FaultRegistry(clock)

    private fun fault(
        errorType: String,
        kind: Lifetime.Kind = Lifetime.Kind.KIND_UNTIL_CLEARED,
        until: String = "",
        skillId: String = "",
        taskId: String = "",
        canContinue: Boolean = false,
        canAccept: Boolean = true,
    ): Fault = Fault.newBuilder()
        .setErrorType(errorType)
        .setCanContinueCurrentTask(canContinue)
        .setCanAcceptNewTask(canAccept)
        .setActiveUntil(Lifetime.newBuilder().setKind(kind).setUntil(until))
        .also { builder ->
            if (skillId.isNotEmpty()) {
                builder.addReferences(
                    Reference.newBuilder().setKey(Reference.Key.KEY_SKILL_ID).setValue(skillId),
                )
            }
            if (taskId.isNotEmpty()) {
                builder.addReferences(
                    Reference.newBuilder().setKey(Reference.Key.KEY_TASK_ID).setValue(taskId),
                )
            }
        }
        .build()

    // ── 수명 (§4.3)

    @Test
    fun `수명 종류를 통째로 확인한다`() {
        // 하나만 보면 나머지가 침묵한다. `Lifetime.Kind`의 값이 늘면
        // 여기서 걸린다.
        val kinds = Lifetime.Kind.entries.filterNot { it.name.endsWith("UNRECOGNIZED") }
        assertEquals(4, kinds.size, "$kinds")

        // UNSPECIFIED·UNTIL_CLEARED·UNTIL_NEW_TASK는 시간으로 안 사라진다.
        listOf(
            Lifetime.Kind.KIND_UNSPECIFIED,
            Lifetime.Kind.KIND_UNTIL_CLEARED,
            Lifetime.Kind.KIND_UNTIL_NEW_TASK,
        ).forEach { kind ->
            val local = FaultRegistry(VirtualClock(Instant.parse("2026-09-06T00:00:00Z")))
            local.raise(fault("LOCALIZATION_LOST", kind))
            local.expire()
            assertEquals(1, local.active().size, "$kind 가 시간으로 사라졌다")
        }
    }

    @Test
    fun `시각 수명은 거둘 때 사라진다`() {
        registry.raise(
            fault(
                "LOCALIZATION_LOST",
                Lifetime.Kind.KIND_UNTIL_TIMESTAMP,
                until = "2026-09-06T00:00:30Z",
            ),
        )
        assertEquals(1, registry.active().size)

        clock.advance(Duration.ofSeconds(29))
        assertEquals(emptyList(), registry.expire(), "아직 유효한데 거뒀다")
        assertEquals(1, registry.active().size)

        clock.advance(Duration.ofSeconds(2))
        assertEquals(listOf("LOCALIZATION_LOST"), registry.expire().map { it.errorType })
        assertEquals(emptyList(), registry.active(), "거뒀는데 남았다")
    }

    @Test
    fun `조회는 아무것도 지우지 않는다`() {
        // **지우면 소멸 시점이 누가 언제 보느냐에 달린다.** 발행·스냅샷·raise가
        // 각각 부르므로 관측이 늘면 소멸이 앞당겨지고 §12.1이 깨진다.
        // 게다가 해소 이벤트가 안 나가 소비자는 지워진 결함을 영원히 든다.
        registry.raise(
            fault(
                "LOCALIZATION_LOST",
                Lifetime.Kind.KIND_UNTIL_TIMESTAMP,
                until = "2026-09-06T00:00:30Z",
            ),
        )
        clock.advance(Duration.ofSeconds(31))

        repeat(5) { assertEquals(1, registry.active().size, "조회가 지웠다") }
        assertEquals(1, registry.expire().size)
        assertEquals(emptyList(), registry.active())
    }

    @Test
    fun `거둘 것이 없으면 아무것도 안 돌려준다`() {
        // 매번 무언가 돌려주면 호출자가 유령 해소를 발행한다.
        registry.raise(fault("LOCALIZATION_LOST"))
        repeat(3) { assertEquals(emptyList(), registry.expire()) }
        assertEquals(1, registry.active().size)
    }

    @Test
    fun `새 태스크 수명은 새 태스크로만 사라진다`() {
        registry.raise(fault("PAYLOAD_LOST", Lifetime.Kind.KIND_UNTIL_NEW_TASK))
        registry.raise(fault("LOCALIZATION_LOST", Lifetime.Kind.KIND_UNTIL_CLEARED))

        clock.advance(Duration.ofDays(1))
        registry.expire()
        assertEquals(2, registry.active().size, "시간으로 사라졌다")

        val gone = registry.onNewTask()
        assertEquals(listOf("PAYLOAD_LOST"), gone.map { it.errorType })
        assertEquals(listOf("LOCALIZATION_LOST"), registry.active().map { it.errorType })
    }

    // ── 동일성

    @Test
    fun `같은 결함을 두 번 내면 하나다`() {
        // 내면 소비자의 목록이 부풀고, 해소 이벤트 하나로 지워지지 않는
        // 유령이 남는다.
        assertNotNull(registry.raise(fault("LOCALIZATION_LOST")))
        assertNull(registry.raise(fault("LOCALIZATION_LOST")), "두 번째가 새 결함으로 잡혔다")
        assertEquals(1, registry.active().size)
    }

    @Test
    fun `스킬이 다르면 다른 결함이다`() {
        // error_type만으로 키를 잡으면 스킬 둘이 각각 실패한 것이 하나로 접힌다.
        assertNotNull(registry.raise(fault("SKILL_EXECUTION_FAILED", skillId = "pick_place")))
        assertNotNull(registry.raise(fault("SKILL_EXECUTION_FAILED", skillId = "navigate_to")))
        assertEquals(2, registry.active().size)
    }

    @Test
    fun `스킬 수준과 로봇 수준이 references로 갈린다`() {
        // §4.6 — "이동은 되는데 조작만 안 되는" 상태가 이렇게 표현된다.
        registry.raise(fault("SKILL_EXECUTION_FAILED", skillId = "pick_place"))
        registry.raise(fault("LOCALIZATION_LOST"))

        val active = registry.active()
        val skillLevel = active.filter { f ->
            f.referencesList.any { it.key == Reference.Key.KEY_SKILL_ID }
        }
        assertEquals(listOf("SKILL_EXECUTION_FAILED"), skillLevel.map { it.errorType })
        assertEquals(1, (active - skillLevel.toSet()).size)
    }

    // ── 해소

    @Test
    fun `해소하면 사라진다`() {
        registry.raise(fault("LOCALIZATION_LOST"))
        assertEquals(listOf("LOCALIZATION_LOST"), registry.clear("LOCALIZATION_LOST").map { it.errorType })
        assertEquals(emptyList(), registry.active())
    }

    @Test
    fun `없던 것을 해소하면 아무것도 안 나온다`() {
        // 유령 해소가 나가면 소비자가 있지도 않던 결함이 풀렸다고 믿는다.
        assertEquals(emptyList(), registry.clear("LOCALIZATION_LOST"))
    }

    @Test
    fun `스킬을 지목해 해소한다`() {
        registry.raise(fault("SKILL_EXECUTION_FAILED", skillId = "pick_place"))
        registry.raise(fault("SKILL_EXECUTION_FAILED", skillId = "navigate_to"))

        registry.clear("SKILL_EXECUTION_FAILED", skillId = "pick_place")
        assertEquals(1, registry.active().size)
        assertTrue(
            registry.active().single().referencesList.any { it.value == "navigate_to" },
        )
    }

    // ── 순서

    @Test
    fun `발생 순서를 지킨다`() {
        // 소비자가 이벤트로 본 순서와 스냅샷의 순서가 달라지면, 재구성한
        // 목록과 스냅샷을 비교할 수 없다.
        listOf("LOCALIZATION_LOST", "PAYLOAD_LOST", "INTERNAL_ERROR").forEach {
            registry.raise(fault(it))
        }
        assertEquals(
            listOf("LOCALIZATION_LOST", "PAYLOAD_LOST", "INTERNAL_ERROR"),
            registry.active().map { it.errorType },
        )
    }
}
