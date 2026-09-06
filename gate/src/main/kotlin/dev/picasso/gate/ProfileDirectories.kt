package dev.picasso.gate

/**
 * 게이트가 프로파일을 찾는 곳. **한 군데서만 정한다.**
 *
 * 이 목록은 세 곳이 합의해야 한다 — CLI의 기본값, CI의 기준선 추출 경로,
 * 음성 하네스가 복사본에서 읽는 경로. 세 곳에 따로 쓰면 하나만 고쳤을 때
 * **나머지가 조용히 다른 것을 검사한다.** §7.4가 픽스처와 실제 기종을 나눠
 * 두었고 Chunk 5가 세 번째 기종을 더하므로 목록은 또 는다.
 */
object ProfileDirectories {

    /** 게이트·시험 전용 프로파일과 음성 케이스의 변형들. */
    const val FIXTURES = "profile/fixtures"

    /** §7.4의 실제 기종. 완료 기준 11의 세 번째 기종도 여기로 온다. */
    const val MODELS = "profile/profiles"

    val ALL: List<String> = listOf(FIXTURES, MODELS)
}
