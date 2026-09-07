/**
 * `registry` — 개정판·어댑터·바인딩의 수명주기와 의존 원장(설계 §8).
 *
 * **의존은 `gate`와 `contracts`뿐이다**(§3.2). `mimic`도 `harness`도 모른다 —
 * 시험은 `harness`가 수행하고 여기서는 요청 행을 적재만 하며, 갱신 반영은
 * `mimic`이 당긴다. 그 두 규칙이 순환을 막는다.
 *
 * **검증을 다시 구현하지 않고 게이트를 부른다**(§11.1). CI에는 DB가 없으므로
 * 입력을 문서로 통일해야 CI와 여기 두 호출이 같은 답을 낸다. 따로 만들면
 * CI가 통과시킨 프로파일을 레지스트리가 거부하는 날이 온다.
 */
dependencies {
    implementation(project(":gate"))
    implementation(project(":contracts"))

    implementation(libs.jackson.databind)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)

    // gate가 networknt를 끌고 오고 그것이 slf4j-api를 끌고 온다. 구현이
    // 없으면 경고가 출력 앞에 붙는다 — 다른 모듈과 같은 이유다.
    runtimeOnly(libs.slf4j.nop)

    testImplementation(kotlin("test"))
    testImplementation(libs.testcontainers.postgresql)
}

tasks.withType<Test>().configureEach {
    // 게이트를 부르는 시험이 프로파일 문서를 읽는다. 없으면 프로파일을
    // 고쳐도 :registry:test 가 UP-TO-DATE 로 넘어가 낡은 채 초록이다 —
    // 이 저장소에서 네 번 물린 자리다.
    inputs.files(
        rootProject.file("profile/schema"),
        rootProject.file("profile/fixtures"),
        rootProject.file("profile/profiles"),
    ).withPropertyName("profileInputs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
