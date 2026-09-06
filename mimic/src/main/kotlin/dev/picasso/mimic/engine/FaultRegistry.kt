package dev.picasso.mimic.engine

import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.Lifetime
import dev.picasso.contracts.v1.Reference
import java.time.Instant

/**
 * 기체가 지금 안고 있는 결함들(§4.6).
 *
 * **결함은 기체 단위다.** 스킬 수준인지 로봇 수준인지는 `references`가 말한다 —
 * `skill_id`가 담기면 그 스킬만의 문제이고, "이동은 되는데 조작만 안 되는"
 * 상태가 그렇게 표현된다. 별도 컬렉션으로 나누지 않는 이유는 §4.6이 등급을
 * **두 불리언**으로 결정 가능하게 만들었기 때문이다 — 나누는 순간 그 판단이
 * 다시 우리 몫이 된다.
 *
 * 수명(§4.3)이 지난 결함은 조회 시점에 사라진다. 남겨 두면 소비자가
 * "이 결함이 아직 유효한가"를 추측하게 되는데, `Lifetime`이 그 추측을
 * 없애려고 있는 필드다.
 */
class FaultRegistry(private val clock: Clock) {

    /** 발생 순서를 지킨다 — 소비자가 본 순서와 스냅샷의 순서가 달라지면 안 된다. */
    private val raised = LinkedHashMap<Key, Fault>()

    /**
     * 같은 결함의 동일성. `error_type`만으로는 부족하다 — 스킬 둘이 각각
     * 실패하면 서로 다른 결함이다.
     */
    private data class Key(val errorType: String, val skillId: String, val taskId: String)

    private fun keyOf(fault: Fault) = Key(
        fault.errorType,
        fault.referencesList.firstOrNull { it.key == Reference.Key.KEY_SKILL_ID }?.value.orEmpty(),
        fault.referencesList.firstOrNull { it.key == Reference.Key.KEY_TASK_ID }?.value.orEmpty(),
    )

    /**
     * 활성 결함. **읽기만 한다.**
     *
     * 조회하면서 지우면 소멸 시점이 **누가 언제 보느냐**에 달린다 — 발행,
     * `GetSnapshot`, `raise`가 각각 부르므로 관측이 늘면 소멸이 앞당겨지고,
     * §12.1의 "시드 + 가상 시계 고정 = 동일 이벤트 시퀀스"가 깨진다.
     * 게다가 해소 이벤트가 안 나가 이벤트를 접는 소비자는 지워진 결함을
     * 영원히 든다(완료 기준 2의 비교가 거기서 어긋난다).
     *
     * 거두는 것은 [expire] 하나이며 `tick()`이 부른다.
     */
    fun active(): List<Fault> = raised.values.toList()

    /**
     * 수명이 지난 것을 거둔다. **`tick()`만 부른다** — 소멸 시점이 시계에만
     * 달리도록.
     *
     * @return 거둬진 것들. 호출자가 각각 해소 이벤트를 낸다.
     */
    fun expire(): List<Fault> {
        val now = clock.now()
        val gone = raised.values.filter { expired(it, now) }
        raised.entries.removeIf { expired(it.value, now) }
        return gone
    }

    /**
     * @return 새로 생긴 결함이면 그것, 이미 있던 것이면 `null`.
     *
     * **같은 결함을 두 번 내지 않는다.** 내면 소비자의 목록이 부풀고,
     * 해소 이벤트 하나로 지워지지 않는 유령이 남는다.
     */
    fun raise(fault: Fault): Fault? {
        val key = keyOf(fault)
        if (key in raised) return null
        raised[key] = fault
        return fault
    }

    /** @return 실제로 지워진 것들. 없던 것을 지웠다고 알리면 유령 해소가 나간다. */
    fun clear(errorType: String, skillId: String = "", taskId: String = ""): List<Fault> {
        val key = Key(errorType, skillId, taskId)
        return listOfNotNull(raised.remove(key))
    }

    /** §4.3의 `UNTIL_NEW_TASK`. 새 태스크가 접수되면 그 수명의 결함이 사라진다. */
    fun onNewTask(): List<Fault> {
        val gone = raised.entries
            .filter { it.value.activeUntil.kind == Lifetime.Kind.KIND_UNTIL_NEW_TASK }
            .map { it.value }
        raised.entries.removeIf { it.value.activeUntil.kind == Lifetime.Kind.KIND_UNTIL_NEW_TASK }
        return gone
    }

    private fun expired(fault: Fault, now: Instant): Boolean =
        when (fault.activeUntil.kind) {
            Lifetime.Kind.KIND_UNTIL_TIMESTAMP ->
                fault.activeUntil.until.isNotBlank() &&
                    !now.isBefore(Instant.parse(fault.activeUntil.until))

            // 나머지는 시간으로 안 사라진다. `else`를 쓰지 않는 것이 요점이다 —
            // 수명 종류가 늘면 컴파일이 깨져야지 조용히 "안 사라짐"으로
            // 접히면 안 된다.
            Lifetime.Kind.KIND_UNTIL_CLEARED,
            Lifetime.Kind.KIND_UNTIL_NEW_TASK,
            Lifetime.Kind.KIND_UNSPECIFIED,
            Lifetime.Kind.UNRECOGNIZED,
            -> false
        }
}
