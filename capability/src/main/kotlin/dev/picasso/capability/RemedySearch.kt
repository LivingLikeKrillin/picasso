package dev.picasso.capability

import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.Precondition
import dev.picasso.contracts.v1.SkillDeclaration

/**
 * 제안의 한 걸음 — **기존 계약의 태스크 단위**로 말한다(설계안 §6.3). 자유 텍스트는 실행 가능성을
 * 검증할 수 없으므로, 걸음은 언제나 이 기체가 **선언한** 스킬 하나다.
 */
data class RemedyStep(
    val skillType: String,
    /** 이 걸음 자신의 전제. 운영자가 판단 경로를 따라갈 재료다. */
    val requires: List<Precondition>,
    /** 이 걸음이 **성공**으로 끝난 뒤의 파지. */
    val expectedHold: HoldKind,
    /**
     * 이 걸음이 **실패**했을 때 도달하는 파지. 이것이 없으면 승인은 곧 의례가 된다(설계안 §6.3).
     * 중단 시점의 기대와 같은 규칙을 쓴다 — 쥐는 스킬이 실패하면 든 채다.
     */
    val onFailureHold: HoldKind,
)

/**
 * 탐색의 답. **대안 없음도 결과다**(설계안 §6.3) — 빈 목록으로 접으면 "찾지 못했다" 와 "없다" 가
 * 구별되지 않는다.
 */
sealed interface Remedy {

    /** 여기까지 하면 목표 스킬을 보낼 수 있다. 걸음이 없으면 지금 이미 보낼 수 있다는 뜻이다. */
    data class Found(val steps: List<RemedyStep>) : Remedy

    /** 못 찾았다. [unmet] 이 끝내 못 채운 조건이고, [cause] 가 못 찾은 종류를 가른다. */
    data class None(val unmet: List<PreconditionViolation>, val cause: Cause) : Remedy {
        enum class Cause {
            /** 선언된 능력을 다 써도 그 상태에 못 간다. 진짜로 **없다**. */
            NO_CAPABILITY,

            /** 상한 안에서 못 찾았다. 더 깊이 가면 있을 수 있지만 **보지 않는다**(설계안 §6.2). */
            DEPTH_LIMIT,
        }
    }
}

/**
 * 깨진 전제를 충족시키는 다른 능력 조합을 계산한다(설계안 §6).
 *
 * **계획 시점 사슬 검사의 역방향이다.** 사슬 검사가 "이 순서로 보내면 조건이 깨지는가" 를 묻는다면,
 * 여기는 "이 조건을 참으로 만들려면 무엇을 해야 하나" 를 묻는다. 능력 하나가 상태 전이 하나이고,
 * 조건이 적용 가능성이고, 효과가 결과 상태다.
 *
 * **기종을 모른다.** 입력은 선언과 관측뿐이며 기체 식별자도 벤더 이름도 받지 않는다 — 게이트 7번이
 * 이 모듈에서 그것을 집행한다.
 *
 * **플래너 라이브러리를 들이지 않는다.** 이 탐색의 상태 공간은 파지 하나(4값)라 너비 우선으로 충분하고,
 * 라이브러리를 들이면 이 판단이 라이브러리의 판단으로 바뀐다.
 *
 * **셀 자원의 점유는 여기 안 들어온다**(§15.153). 이 탐색이 도는 것은 **계약이 선언한 능력 전이**인데,
 * 계약의 `PreconditionSubject` 는 *"조건이 보는 로봇 상태"* 이고 값이 늘려면 *"관측하는 어댑터"* 가 있어야
 * 한다. 슬롯 점유를 보는 것은 어댑터가 아니라 셀 설비이므로 그 축은 미들웨어의 관문이 든다
 * (`CellOccupancy`). 점유가 늘어도 이 상태 공간은 넷 그대로다.
 */
object RemedySearch {

    /** 설계안 §6.2 의 권고. 셋을 넘는 조치 열은 운영자가 승인 시점에 검토할 수 없다. */
    const val DEFAULT_MAX_DEPTH: Int = 3

    /**
     * @param target 보내고 싶은데 지금 전제가 깨진 스킬
     * @param declared 이 기체가 선언한 능력 전부. 여기 없는 스킬은 제안하지 않는다
     * @param observed 지금 관측한 파지
     * @param maxDepth 걸음 수 상한. 넘으면 [Remedy.None.Cause.DEPTH_LIMIT]
     */
    fun search(
        target: SkillDeclaration,
        declared: List<SkillDeclaration>,
        observed: HoldState,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
    ): Remedy {
        val start = observed.kind
        if (violations(target, start).isEmpty()) return Remedy.Found(emptyList())

        // **못 보는 것은 조치로 못 덮는다.** 효과는 파지를 바꾸지 관측 가능성을 바꾸지 못한다 — 여기서
        // 선언된 효과로 «이제 빈손이다» 라고 답하면 그것이 바로 선언으로 현실을 단정하는 일이다(§5.2).
        if (!start.isConcreteObservation) {
            return Remedy.None(violations(target, start), Remedy.None.Cause.NO_CAPABILITY)
        }

        // 너비 우선 — 같은 깊이면 걸음이 적은 쪽이 먼저 나온다. 파지 값이 넷뿐이라 방문 집합으로 충분하다.
        val seen = mutableSetOf(start)
        var frontier = listOf(start to emptyList<RemedyStep>())
        var depth = 0

        while (frontier.isNotEmpty() && depth < maxDepth) {
            val next = mutableListOf<Pair<HoldKind, List<RemedyStep>>>()
            for ((state, path) in frontier) {
                for (skill in declared) {
                    // **적용 가능성은 기존 조건 검사를 그대로 쓴다.** 모르는 주어는 유보다 — 판정은
                    // 발신자가 접수 때 하고, 여기서 거절하면 제안이 마이너 호환을 깬다.
                    if (violations(skill, state, PreconditionCheck.Unknown.DEFER).isNotEmpty()) continue
                    val after = HoldEffects.after(skill.skillType, holdOf(state)).kind
                    val step = RemedyStep(
                        skillType = skill.skillType,
                        requires = skill.preconditionsList.toList(),
                        expectedHold = after,
                        onFailureHold = HoldEffects
                            .expectedAtEnd(skill.skillType, everHeld = HoldEffects.grasps(skill.skillType), completed = false)
                            ?: state,
                    )
                    val walked = path + step
                    if (violations(target, after).isEmpty()) return Remedy.Found(walked)
                    if (seen.add(after)) next += after to walked
                }
            }
            frontier = next
            depth += 1
        }

        // 볼 것이 남았는데 멈췄으면 상한에 걸린 것이고, 다 보고 멈췄으면 없는 것이다. 둘을 접으면
        // 운영자가 «더 찾아봐라» 와 «이 기체로는 안 된다» 를 구별하지 못한다.
        val cause = if (frontier.isNotEmpty()) Remedy.None.Cause.DEPTH_LIMIT else Remedy.None.Cause.NO_CAPABILITY
        return Remedy.None(violations(target, start), cause)
    }

    private fun violations(
        skill: SkillDeclaration,
        state: HoldKind,
        unknown: PreconditionCheck.Unknown = PreconditionCheck.Unknown.DEFER,
    ): List<PreconditionViolation> = PreconditionCheck.check(skill, holdOf(state), unknown)

    private fun holdOf(kind: HoldKind): HoldState = HoldState.newBuilder().setKind(kind).build()
}
