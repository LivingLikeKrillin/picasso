package dev.picasso.harness

import dev.picasso.client.PicassoClient
import dev.picasso.client.TaskFollower
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.profile.RequirementSet
import java.time.Duration

/**
 * 시나리오 하나의 결과.
 *
 * **`id`가 안정 키다.** `name`은 사람이 읽는 것이라 바뀌지만, §8.4 ②가
 * 결과를 `revision_test_run`에 적재하므로 이름으로 키잉하면 시나리오를
 * 고칠 때 이력이 고아가 된다.
 *
 * `seed`와 `clockMode`를 함께 싣는다 — §12.1의 결정성은 강제만 하는 것이
 * 아니라 **보고되어야** 재현할 수 있다.
 */
data class ScenarioResult(
    val suite: Suite,
    val id: String,
    val name: String,
    val passed: Boolean,
    val detail: String = "",
    val seed: Long = 0,
    val clockMode: String = "VIRTUAL",
)

/** §12.1의 `revision_test_run.suite` 값 셋. */
enum class Suite { CONTRACT, NEGATIVE, DETERMINISM }

/**
 * 계약 스위트. **`harness`가 소유하며 CI와 §8.4 ②가 같은 것을 실행한다**(§12.1).
 *
 * 시나리오가 `main`에 있는 것이 요점이다 — 시험 소스에 두면 "같은 클라이언트
 * 코드"가 "같은 **시험** 코드"가 되고, 운영 경로(§8.4 ②)는 다른 것을 돌리게 된다.
 */
object ContractSuite {

    /** 두 기종이 **같은 major.minor로** 선언한 스킬. §7.4의 첫 축이다. */
    const val COMMON_SKILL = "navigate_to"

    /**
     * 공통 태스크 하나를 완주시킨다.
     *
     * **기종을 식별해 분기하지 않는다.** 인자는 `robotId`와 요구 집합뿐이고
     * 파라미터는 호출자가 준다 — 능력을 보고 값을 고르기 시작하면 그것이
     * 곧 기종 분기다.
     */
    fun runCommonTask(
        harness: Harness,
        client: PicassoClient,
        robotId: String,
        requirements: RequirementSet,
        parameters: List<ParameterValue>,
        taskId: String = "common",
    ): CommonTaskOutcome {
        val negotiated = client.negotiate(robotId, requirements)
        if (!negotiated.accepted) {
            return CommonTaskOutcome(
                negotiated.rejectionsList.joinToString { "${it.code}: ${it.detail}" },
                null,
            )
        }

        val started = client.start(robotId, taskId, 1, COMMON_SKILL, parameters)
        if (started.hasRejection()) {
            return CommonTaskOutcome(
                "${started.rejection.code}: ${started.rejection.detail}",
                null,
            )
        }

        val follower = client.follow(robotId, started.handle)
        // 소요시간을 모르는 채 민다 — 아는 순간 그것이 기종 지식이 된다.
        // 두 번인 이유는 tick이 한 걸음씩 가기 때문이다(ACCEPTED → RUNNING,
        // 그 다음 RUNNING → 종착).
        repeat(TICKS) { harness.advance(STEP) }
        return CommonTaskOutcome(null, follower)
    }

    /**
     * 스킬 하나를 걸고 종착까지 민다. 협상은 하지 않는다 — 능력 차이를
     * 보는 시나리오가 쓴다.
     */
    fun runTask(
        harness: Harness,
        client: PicassoClient,
        robotId: String,
        skillType: String,
        parameters: List<ParameterValue>,
        taskId: String,
    ): CommonTaskOutcome {
        val started = client.start(robotId, taskId, 1, skillType, parameters)
        if (started.hasRejection()) {
            return CommonTaskOutcome("${started.rejection.code}: ${started.rejection.detail}", null)
        }
        val follower = client.follow(robotId, started.handle)
        repeat(TICKS) { harness.advance(STEP) }
        return CommonTaskOutcome(null, follower)
    }

    /** 넉넉히 민다. 소요시간을 시나리오가 알면 그것이 기종 지식이 된다. */
    private val STEP: Duration = Duration.ofSeconds(60)
    private const val TICKS = 3

    class CommonTaskOutcome(val rejection: String?, val follower: TaskFollower?) {
        val accepted: Boolean get() = rejection == null
    }
}
