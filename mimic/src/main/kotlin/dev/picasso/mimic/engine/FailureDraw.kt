package dev.picasso.mimic.engine

import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.Lifetime
import dev.picasso.contracts.v1.Reference
import dev.picasso.profile.ProfileDocument

/**
 * §10.4 ①의 선언된 실패 모드 추첨.
 *
 * **순수 함수로 뺀 것이 요점이다.** 하네스에서 실행당 한 번 뽑아서는 시드
 * 민감도를 보일 수 없다 — `java.util.Random`의 첫 추첨이 시드 0·1·2에서
 * 0.7309~0.7311로 거의 같고(실측), 최대 `rate`가 0.05이므로 **어떤 시드로도
 * 첫 추첨에서는 아무것도 안 걸린다.** 시드 1과 2는 여섯 번째에야 갈린다.
 * 여기서 200번 뽑아 보면 그 전부가 값싸게 시험된다.
 *
 * ## 추첨 규약 (§12.1의 "동일 이벤트 시퀀스"가 이것에 걸린다)
 *
 * - **선언 순서대로** 본다. 프로파일의 배열 순서가 곧 추첨 순서다.
 * - **해당하는 모드마다 한 번씩** 뽑는다. 해당한다 = 그 스킬을 지목했거나
 *   `skill_type`이 없어 모든 스킬에 걸린다.
 * - **처음 걸린 것이 이긴다.** 나머지는 그 실행에서 뽑지 않는다.
 * - 추첨 지점은 **실행당 한 번**이다 — `tick()`이 완주를 판정하는 그 자리.
 *   다른 데 두면 뽑는 횟수가 **관측 횟수에 달리고**(모든 RPC가 `tick`을
 *   부른다) 소비자가 보는 것이 결과를 바꾼다.
 *
 * 이 넷 중 하나라도 바뀌면 같은 시드가 다른 것을 낸다. 골든이 통째로 흔들리는
 * 자리이므로 여기 적어 둔다.
 *
 * ## 아직 이 스트림을 쓰는 것이 여기뿐이다
 *
 * §10.3은 시드에서 **둘**이 나온다고 했다 — 실패 추첨과 **소요시간 지터**다.
 * 지터는 아직 배선되지 않았다(`Seeded.jitter`의 프로덕션 호출자가 0이다).
 * **배선하는 순간 같은 `Seeded`에 끼어들어 추첨 스트림이 통째로 밀린다.**
 * 그때 이 파일의 골든과 하네스의 시드 가정이 전부 흔들리므로, 지터를 붙이는
 * 사람은 그 둘을 같은 커밋에서 고쳐야 한다.
 */
class FailureDraw(private val document: ProfileDocument) {

    /**
     * @return 걸린 모드, 없으면 `null`.
     *
     * **해당하지 않는 모드는 뽑지 않는다.** 뽑으면 스킬이 무엇이냐에 따라
     * 난수 인출 수가 달라져 같은 시드가 다른 순서를 낸다.
     */
    fun drawFor(skillType: String, random: Seeded): ProfileDocument.FailureModeEntry? {
        applicable(skillType).forEach { mode ->
            if (random.fraction() < mode.rate) return mode
        }
        return null
    }

    /** 선언 순서를 지킨다. */
    fun applicable(skillType: String): List<ProfileDocument.FailureModeEntry> =
        document.failureModes.filter { it.skillType == null || it.skillType == skillType }

    companion object {

        /**
         * 선언된 모드를 §4.6의 `Fault`로 옮긴다.
         *
         * **`active_until`이 없으면 `UNTIL_CLEARED`다.** 미정으로 내보내면
         * 소비자가 "이 결함이 아직 유효한가"를 추측하게 되는데, §4.3의
         * `Lifetime`이 그 추측을 없애려고 있는 필드다.
         */
        fun faultOf(
            mode: ProfileDocument.FailureModeEntry,
            skillType: String,
            taskId: String,
        ): Fault = Fault.newBuilder()
            .setErrorType(mode.errorType)
            .setCanContinueCurrentTask(mode.canContinueCurrentTask)
            .setCanAcceptNewTask(mode.canAcceptNewTask)
            .setErrorHint(mode.errorHint)
            .setActiveUntil(
                Lifetime.newBuilder().setKind(
                    when (mode.activeUntil) {
                        "UNTIL_NEW_TASK" -> Lifetime.Kind.KIND_UNTIL_NEW_TASK
                        "UNTIL_CLEARED", null -> Lifetime.Kind.KIND_UNTIL_CLEARED
                        // 스키마가 둘만 허용한다. 다른 값이 오면 게이트 3번이
                        // 놓친 것이므로 조용히 접지 않는다.
                        else -> error("프로파일의 active_until 값을 모른다: '${mode.activeUntil}'")
                    },
                ),
            )
            // **`skill_type`이 있는 모드만 스킬 수준이다.** 스키마가 그렇게
            // 못박았다 — "있으면 스킬 수준 결함, 없으면 로봇 수준 결함이다".
            //
            // 무조건 붙이면 셋이 깨진다. ① 소비자가 §4.6이 표현하라고 만든
            // 구분을 **거꾸로** 읽는다 — `quadruped-b`의 `LOCALIZATION_LOST`는
            // `can_accept_new_task=false`인 로봇 수준 결함인데 그때 마침 돌던
            // 스킬의 이름이 실려 나간다. ② `FaultRegistry`의 키가
            // `(errorType, skillId, taskId)`라 같은 로봇 수준 결함이 스킬 수만큼
            // 부푼다 — "같은 결함을 두 번 내지 않는다"가 무력해진다.
            // ③ §10.5의 `ForceFault`는 `task_id`를 비우면 로봇 수준이라고
            // 정했다. 두 경로가 같은 사실을 다르게 표현하게 된다.
            .also { builder ->
                if (mode.skillType != null) {
                    builder.addReferences(
                        Reference.newBuilder()
                            .setKey(Reference.Key.KEY_SKILL_ID).setValue(skillType),
                    )
                    builder.addReferences(
                        Reference.newBuilder()
                            .setKey(Reference.Key.KEY_TASK_ID).setValue(taskId),
                    )
                }
            }
            .build()

        /**
         * 프로파일의 `resolution` 문자열을 엔진의 [Resolution]으로.
         *
         * 두 벌을 두는 이상 다리를 시험한다(Chunk 2의 규율). 미지의 값을
         * 조용히 접으면 §4.5 전파 규칙 1의 입력이 미정의가 되고 태스크
         * 종착 판정이 무너진다.
         */
        fun resolutionOf(value: String): Resolution = when (value) {
            "SELF_RETRIABLE" -> Resolution.SELF_RETRIABLE
            "NEEDS_INTERVENTION" -> Resolution.NEEDS_INTERVENTION
            "TERMINAL" -> Resolution.TERMINAL
            else -> error("프로파일의 resolution 값을 엔진으로 옮길 수 없다: '$value'")
        }
    }
}
