package dev.picasso.gate

/**
 * 검사가 돌기 위해 필요한 자원.
 *
 * 설계 §3.1 — 검사는 요구 자원을 선언하고, 자원이 없으면 건너뛰되
 * 그 사실을 출력에 남긴다. 조용히 통과시키지 않는 것이 요점이다.
 *
 * 문서와 스키마를 나눈 이유: 검사 4번은 스키마가 필요 없고 검사 3번은
 * 문서가 0개여도 돌 수 있다. 하나로 묶으면 "문서를 멀쩡히 넘겼는데
 * 스키마가 없어서 전부 건너뛰고 초록불"이 나온다 — 이 클래스가
 * 막으려는 바로 그 조용한 무력화다.
 */
enum class Resource {
    /** 검사할 프로파일 문서들 */
    PROFILE_DOCUMENT,

    /** 능력 프로파일 JSON Schema */
    PROFILE_SCHEMA,

    /** contracts의 FileDescriptorSet */
    CONTRACT_DESCRIPTOR,

    /** 저장소 트리 (빌드 파일 읽기 등) */
    REPO,

    /** buf 실행기 */
    BUF,

    /** 파괴 검사의 기준선 */
    BASELINE,

    /** 이 PR이 바꾼 파일 목록. 검사 8번(2단계)이 쓴다 */
    CHANGED_FILES,

    /**
     * 의존 원장 조회. 검사 6번의 축소 판정이 쓴다(설계 §9.3).
     * CI에는 없고 registry 호출 지점에만 있다 — 그래서 6번은 CI에서
     * 부분 건너뜀이 된다.
     */
    REGISTRY,
}
