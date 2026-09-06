// 프로파일 문서의 읽기 전용 모델. gate와 mimic이 공유한다.
//
// 두 벌로 두면 이 프로젝트가 막으려는 바로 그 드리프트를 우리가 낸다.
// 프로젝트 내 의존은 없다 — contracts도 모른다. 프로파일은 계약을 참조만
// 할 뿐 이 모델은 계약 타입을 쓰지 않는다.
dependencies {
    implementation(libs.jackson.databind)

    // 루트 build.gradle.kts가 junit-jupiter만 넣는다. kotlin.test는 별도다.
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    // 픽스처를 고쳐도 시험이 안 돌면 조용히 낡는다(1단계 실측).
    inputs.files(
        rootProject.file("profile/fixtures"),
        rootProject.file("profile/requirements"),
        rootProject.file("profile/profiles"),
    ).withPropertyName("profileFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
