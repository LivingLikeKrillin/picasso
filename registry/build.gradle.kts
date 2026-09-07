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
 *
 * ## `testFixtures`가 있는 이유
 *
 * 완료 기준 20(카나리)은 **레지스트리에서 바인딩하고 `mimic`이 당겨 헤더로
 * 관측되는 것까지** 한 줄로 봐야 성립한다. 그 시험은 `mimic`을 아는
 * `harness`에 있어야 하고(§3.2 — `registry`는 `mimic`을 모른다), 거기서도
 * 진짜 DB가 필요하다.
 *
 * 컨테이너 기동기를 복사하면 **시험이 DB를 얻는 방식에 두 번째 진실**이
 * 생기고 JVM마다 컨테이너가 하나씩 더 뜬다. 그래서 여기서 내보낸다.
 */
plugins {
    `java-test-fixtures`
}

dependencies {
    // **BOM만 쓰고 Spring Boot 플러그인은 안 붙인다.** 플러그인이 주는
    // 것은 `bootJar`와 의존 버전 정렬인데, 앞의 것은 아직 배포가 없어
    // 쓸 데가 없고 뒤의 것은 BOM으로 족하다. 붙이면 이 모듈만 빌드
    // 규약이 달라진다.
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter.web)

    implementation(project(":gate"))
    implementation(project(":contracts"))

    implementation(libs.jackson.databind)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)

    // **다른 모듈과 달리 `slf4j-nop`을 안 쓴다.** Spring Boot가 logback을
    // 끌고 오는데 nop이 함께 있으면 바인딩이 둘이 되고, 스프링은 그것을
    // 기동 실패로 다룬다("LoggerFactory is not a Logback LoggerContext").
    // 여기서는 logback이 그 자리를 맡는다 — 시험 로그는 `logback-test.xml`이
    // WARN으로 잠근다.

    // 픽스처는 게이트 입력(`RevisionValidator`)과 컨테이너를 함께 낸다.
    testFixturesApi(project(":gate"))
    testFixturesImplementation(libs.jackson.databind)
    testFixturesApi(libs.testcontainers.postgresql)
    testFixturesApi(libs.flyway.core)
    testFixturesRuntimeOnly(libs.flyway.postgresql)
    testFixturesRuntimeOnly(libs.postgresql)

    // 픽스처가 `/picasso.desc`를 클래스패스에서 읽는다.
    testFixturesRuntimeOnly(project(":contracts"))

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.test)
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
