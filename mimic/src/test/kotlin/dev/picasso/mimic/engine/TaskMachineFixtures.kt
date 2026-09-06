package dev.picasso.mimic.engine

import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.SkillDeclaration
import dev.picasso.contracts.v1.Support
import dev.picasso.mimic.profile.CapabilityProjection
import dev.picasso.profile.ProfileDocument
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * 시험용 팩토리. **프로덕션 코드에 두지 않는다** — `at()`은 임의 상태에서
 * 기체를 만드는 문이라 실제 경로를 도는 시험을 대신할 수 없다.
 *
 * §4.5의 대응표를 **실제로 세운다.** 계획 초안은 모든 상태에서 스킬을
 * `READY`로 두었고, 그래서 Chunk 2의 시험 전부가 §4.5상 존재할 수 없는
 * 기체를 대상으로 돌았다 — 두 상태머신을 만든 청크에서 둘의 관계가 하나도
 * 시험되지 않았다.
 */
object TaskMachineFixtures {

    val fixtureRaw: String by lazy {
        Files.readString(Path.of("..", "profile", "fixtures", "minimal.json").normalize())
            .replace("\r\n", "\n")
    }

    fun document(raw: String = fixtureRaw): ProfileDocument =
        ProfileDocument.parse("fixture", raw).getOrThrow()

    fun skillOf(skillType: String, doc: ProfileDocument = document()): SkillDeclaration =
        CapabilityProjection.of(doc).skillsList.single { it.skillType == skillType }

    fun durationOf(skillType: String, doc: ProfileDocument = document()): Double =
        doc.durations.single { it.skillType == skillType }.seconds

    /** 프로파일의 스킬 하나로 기체를 만든다. 상태는 `ACCEPTED`다. */
    fun forSkill(
        skillType: String,
        doc: ProfileDocument = document(),
        clock: Clock = VirtualClock(Instant.EPOCH),
    ): TaskMachine = TaskMachine(
        skill = skillOf(skillType, doc),
        clock = clock,
        durationSeconds = durationOf(skillType, doc),
    )

    /** `pause_support`·`cancel_support`를 직접 정한 기체. 완료 기준 7이 쓴다. */
    fun withSupport(
        pause: Support = Support.SUPPORT_YES,
        cancel: Support = Support.SUPPORT_YES,
        clock: Clock = VirtualClock(Instant.EPOCH),
    ): TaskMachine = TaskMachine(
        skill = permissiveSkill(pause, cancel),
        clock = clock,
        durationSeconds = SYNTHETIC_DURATION,
    )

    /**
     * 전이표 시험이 쓰는 합성 스킬. **둘 다 `YES`여야 한다** — 픽스처의
     * `pick_place`는 `cancel_support`가 `NO`라 그것으로 전이표를 돌리면
     * `CANCEL` 행 여섯이 전부 `CANCEL_UNSUPPORTED`가 되어 표를 시험하지
     * 못한다. 지원 여부 판정은 [withSupport]가 따로 본다.
     */
    private fun permissiveSkill(
        pause: Support = Support.SUPPORT_YES,
        cancel: Support = Support.SUPPORT_YES,
    ): SkillDeclaration = SkillDeclaration.newBuilder()
        .setSkillType("probe")
        .setMajor(1)
        .setPauseSupport(pause)
        .setCancelSupport(cancel)
        .build()

    /**
     * 임의 상태의 기체를 만든다. **§4.5의 대응표대로 스킬 FSM을 세운다.**
     *
     * | 태스크 | 스킬 |
     * |---|---|
     * | `ACCEPTED` | 없음 |
     * | `RUNNING` | `RUNNING` |
     * | `PAUSED` | `SUSPENDED` |
     * | `CANCELLING` | `RUNNING` (복구 수행 중) |
     * | 나머지 | `READY` (`HALTED` → 즉시 `Reset`) |
     */
    fun at(
        state: TaskState,
        clock: Clock = VirtualClock(Instant.EPOCH),
        revision: Int = 1,
        parameters: List<ParameterValue> = emptyList(),
    ): TaskMachine {
        val machine = TaskMachine(
            skill = permissiveSkill(),
            clock = clock,
            durationSeconds = SYNTHETIC_DURATION,
            initial = state,
            initialRevision = revision,
        )
        machine.seed(state, skillFor(state), parameters)
        return machine
    }

    /** 합성 기체의 소요시간. 프로파일에서 오는 것은 [forSkill]이 본다. */
    const val SYNTHETIC_DURATION = 45.0

    /** 시험용 파라미터 값. 계약 타입이 하나뿐인 표현이다. */
    fun param(key: String, value: String): ParameterValue =
        ParameterValue.newBuilder().setKey(key).setStringValue(value).build()

    private fun skillFor(state: TaskState): SkillMachine? = when (state) {
        TaskState.ACCEPTED -> null
        TaskState.RUNNING -> SkillMachine().apply { apply(SkillCommand.START) }
        TaskState.PAUSED -> SkillMachine().apply {
            apply(SkillCommand.START)
            apply(SkillCommand.SUSPEND)
        }
        TaskState.CANCELLING -> SkillMachine().apply { apply(SkillCommand.START) }
        // HALTED → 즉시 Reset → READY
        TaskState.RETRIABLE, TaskState.NEEDS_INTERVENTION,
        TaskState.SUCCEEDED, TaskState.FAILED,
        TaskState.CANCELLED, TaskState.CANCELLED_RECOVERY_FAILED,
        -> SkillMachine()
    }
}
