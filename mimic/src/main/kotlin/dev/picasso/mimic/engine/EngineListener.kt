package dev.picasso.mimic.engine

/**
 * 엔진이 **실제로 일어난 전이**를 보고한다(§4.7의 이벤트 넷 중 둘).
 *
 * **로그에서 되짚어 만들지 않는 이유가 이것이다.** 태스크 로그는 상태의
 * 연속이라 `from`을 추론할 수는 있지만 **스킬 전이는 로그에 아예 없고**,
 * 되짚기는 "실제로 일어난 것"과 "일어났다고 우리가 믿는 것" 사이에 두 번째
 * 진실을 만든다. §4.7이 스킬 전이를 별도 `kind`로 둔 이상 엔진이 말해야 한다.
 *
 * **`sequence`도 헤더도 여기 없다.** 그것은 발행 축이고 §3.5가 `update_index`와
 * **다른 축**이라고 못박았다. 엔진은 발행을 모른다.
 *
 * **거절된 전이는 보고하지 않는다.** 안 일어난 일을 보고하면 소비자가 유령
 * 전이를 보고, 그것으로 세운 상태는 내부 상태와 어긋난다.
 */
interface EngineListener {

    fun onSkillTransition(
        taskId: String,
        skillType: String,
        from: SkillState,
        to: SkillState,
    ) = Unit

    fun onTaskTransition(
        taskId: String,
        skillType: String,
        from: TaskState,
        to: TaskState,
        revision: Int,
        attempt: Int,
    ) = Unit

    companion object {
        /** 아무것도 안 한다. 엔진 시험이 리스너를 몰라도 돌아야 한다. */
        val NONE: EngineListener = object : EngineListener {}
    }
}
