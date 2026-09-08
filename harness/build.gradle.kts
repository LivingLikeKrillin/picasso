import org.gradle.api.tasks.PathSensitivity

// 지정한 프로파일로 mimic을 띄우고 client로 계약 스위트를 돌린다(§3.3).
// **계약 스위트의 주인이며** CI와 §8.4 ②가 같은 스위트를 실행한다(§12.1).
dependencies {
    implementation(project(":mimic"))
    api(project(":client"))

    // client가 api로 노출하지만 명시한다 — 여기서 계약 타입을 직접 쓴다.
    api(project(":contracts"))
    implementation(project(":profile-model"))

    // 적재 폴백이 계약 메시지를 protobuf JSON으로 적는다. 적재 표면이
    // 같은 규약으로 읽으므로 다른 규약을 쓰면 밀어 넣는 날 갈린다.
    implementation(libs.protobuf.java.util)
    // 재적재가 JSONL 한 줄을 열어 `kind`·`site`를 읽는다. 안쪽 계약
    // 메시지는 protobuf JSON 규약이라 위의 것이 맡는다.
    implementation(libs.jackson.databind)

    // 하네스가 직접 세운다 — 시험 전용이 아니라 main의 기능이다(§10.2의
    // 직접 실행 모드). 별도 프로세스 모드는 제어 채널과 함께 온다.
    implementation(libs.grpc.inprocess)

    // §3.2가 `harness ⇢ registry`를 **런타임 접근**으로 뒀다(시험 요청
    // 폴링·결과 보고). 시험에서만 in-process 로 세운다 — main 이 의존하면
    // 레지스트리 없이는 하네스가 안 도는 것이 되어 그 규칙이 깨진다.
    testImplementation(project(":registry"))

    // 완료 기준 20(카나리)은 **레지스트리에서 바인딩하고 `mimic`이 당겨
    // 헤더로 관측되는 것까지** 한 줄로 봐야 성립한다. 반으로 쪼개 한쪽은
    // 레지스트리에서, 한쪽은 하네스에서 보면 **두 표면을 서로 비교하는
    // 것**이 되고 그 사이에 낀 결함은 어느 쪽에도 안 보인다.
    //
    // 컨테이너 기동기는 `registry`의 `testFixtures`에서 온다 — 복사하면
    // 시험이 DB를 얻는 방식에 두 번째 진실이 생긴다.
    testImplementation(testFixtures(project(":registry")))

    // 실제 브로커를 띄워 §15.30의 "증명되지 않는 것"을 줄인다.
    // **여기 두는 것은 harness가 이미 Docker를 요구하기 때문이다**(§15.39) —
    // `:mimic:test`에 넣으면 in-process 결정성 스위트가 느려진다.
    testImplementation(libs.testcontainers.core)
    // 구독자 노릇을 하려면 시험도 MQTT 클라이언트가 필요하다.
    testImplementation(libs.paho.mqttv5)

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
        // **면제 목록이다.** 없으면 면제를 한 줄 더해 기종을 스위트에서
        // 빼도 :harness:test 가 UP-TO-DATE 로 넘어가 초록으로 남는다 —
        // 완료 기준 11을 무력화하는 가장 싼 방법이 그것이다.
        rootProject.file("profile/common-set-exemptions.json"),
    ).withPropertyName("profileInputs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
