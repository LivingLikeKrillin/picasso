package dev.picasso.mimic

/**
 * 지금 이 기체가 써야 하는 프로파일 개정판.
 *
 * `profile_revision_id`가 §5.5의 `profile_ref`가 가리키는 좌표이며,
 * `revision`은 문서가 스스로 선언한 번호다(§7.2).
 */
data class RegistryBinding(
    val profileRevisionId: Long,
    val revision: Int,
    val documentJson: String,
)

/**
 * `mimic`이 레지스트리에서 **당겨 오는** 것(§10.3).
 *
 * **인터페이스인 것이 요점이다.** §3.2가 `mimic ⇢ registry`를 **런타임
 * 접근**으로 두었다 — 빌드 의존이 아니며 상대가 없어도 모듈이 빌드·동작해야
 * 한다. `mimic`이 `registry`에 컴파일 의존하면 그 규칙이 깨지고, 에뮬레이터
 * 하나 띄우는 데 DB가 필요해진다.
 *
 * **미는 것이 아니라 당기는 것도 요점이다**(§3.2의 순환 회피 규칙 2).
 * 레지스트리가 `mimic`에 알리면 레지스트리가 `mimic`을 알아야 하고, 그러면
 * "레지스트리는 어느 모듈에도 직접 밀지 않는다"가 깨진다.
 */
fun interface RegistrySource {

    /**
     * @return 이 기체의 지금 바인딩. `null`이면 레지스트리가 이 기체를
     *   모르는 것이며 **파일 모드로 계속 돈다**(§3.2의 "없을 때").
     */
    fun binding(robotId: String): RegistryBinding?

    companion object {
        /** 레지스트리가 없는 모드. 파일로 기동한 프로파일을 그대로 쓴다. */
        val NONE = RegistrySource { null }
    }
}
