import org.gradle.api.tasks.PathSensitivity

// 미들웨어의 가운데 — 정준 모델과 공통 실행 구조.
// ADR 38 · docs/superpowers/specs/2026-09-09-middleware-core-design.md
//
// **기종을 모른다.** 게이트 7번(기종 분기 금지)의 대상이며, 여기에 `if robot == …`
// 가 생기면 게이트가 막는다. 벤더 코드에서 정준 분류로 옮기는 일은 어댑터의
// 것이고(ADR 33), 이 모듈은 계약(④)이 나르는 정준 어휘만 읽는다.
//
// 의존이 계약과 계약 소비자뿐인 것이 성격이다 — `registry`(개정판·원장)도
// `mimic`(로봇 더블)도 모른다. 시험만 하네스로 mimic 을 띄운다.
dependencies {
    api(project(":contracts"))
    implementation(project(":capability"))
    implementation(project(":client"))

    // 계약 메시지를 protobuf JSON 으로 적는다(`LedgerExport`). 기존 적재 표면이 이미 그 규약이라
    // 다른 규약을 쓰면 읽는 쪽이 갈린다. **전송이 아니라 인코딩이다** - 이 모듈은 여전히 파일도
    // 소켓도 모른다.
    implementation(libs.protobuf.java.util)

    testImplementation(project(":harness"))
    testImplementation(project(":mimic"))
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    inputs.files(
        rootProject.file("profile/fixtures"),
        rootProject.file("profile/schema"),
    ).withPropertyName("profileInputs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
