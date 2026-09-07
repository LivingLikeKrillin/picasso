import org.gradle.api.tasks.PathSensitivity

// 지정한 프로파일로 mimic을 띄우고 client로 계약 스위트를 돌린다(§3.3).
// **계약 스위트의 주인이며** CI와 §8.4 ②가 같은 스위트를 실행한다(§12.1).
dependencies {
    implementation(project(":mimic"))
    api(project(":client"))

    // client가 api로 노출하지만 명시한다 — 여기서 계약 타입을 직접 쓴다.
    api(project(":contracts"))
    implementation(project(":profile-model"))

    // 하네스가 직접 세운다 — 시험 전용이 아니라 main의 기능이다(§10.2의
    // 직접 실행 모드). 별도 프로세스 모드는 제어 채널과 함께 온다.
    implementation(libs.grpc.inprocess)

    // §3.2가 `harness ⇢ registry`를 **런타임 접근**으로 뒀다(시험 요청
    // 폴링·결과 보고). 시험에서만 in-process 로 세운다 — main 이 의존하면
    // 레지스트리 없이는 하네스가 안 도는 것이 되어 그 규칙이 깨진다.
    testImplementation(project(":registry"))
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    // 프로파일을 고쳐도 시험이 안 돌면 조용히 낡는다(1단계 실측).
    inputs.files(
        rootProject.file("profile/profiles"),
        rootProject.file("profile/requirements"),
        rootProject.file("profile/schema"),
        // 완료 기준 8이 `TERMINAL` resolution 을 보는데 실기종 셋 중 어느
        // 것도 그것을 선언하지 않아 픽스처로 돈다. 없으면 픽스처를 고쳐도
        // :harness:test 가 UP-TO-DATE 로 넘어가 낡은 채 초록이다.
        rootProject.file("profile/fixtures"),
    ).withPropertyName("profileInputs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
