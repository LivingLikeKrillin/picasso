// 능력의 **투영과 판정** — 프로파일 문서를 계약 `Capability` 로 투영하고(§7.2), 소비자의 요구 집합을 그
// `Capability` 에 대고 판정한다(§5.4 의 핸드셰이크). **mimic 과 어댑터 호스트가 같은 함수를 쓴다.**
//
// ADR 29 가 `profile-model` 을 만든 이유와 같다 — 같은 투영을 두 모듈이 각자 들면 완료 기준 10
// (*"프로파일에서 파생한 값 == GetCapabilities 응답"*)이 두 벌이 되고, 같은 협상 판정을 두 벌로 들면 같은
// 요구 집합에 미믹이 수락하고 실물이 거절하는 일이 가능해진다. 그 순간 이 저장소의 중심 주장
// (*"소비자는 엔드포인트만 바꿔 둘을 오간다"*)이 거짓이 된다.
//
// **전송을 모른다.** 판정 불가는 `Negotiation.Unparseable` 이고 `INVALID_ARGUMENT` 로 옮기는 것은 서비스의
// 일이다 — 발행 추상이 MQTT 라이브러리를 모르는 것과 같은 규율.
//
// `profile-model` 에 넣지 않은 것은 그 모듈이 `contracts` 를 모르는 것이 설계 §3.2 의 결정이라서다
// (gate 가 그것을 통해 생성 코드를 끌어오지 않게). 2026-09-10 에 `profile-projection` 에서 이름이 넓어졌다.
//
// 기종을 모른다 — 게이트 7번의 대상이다.
dependencies {
    implementation(project(":contracts"))
    implementation(project(":profile-model"))

    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    // 투영 시험이 픽스처 프로파일을 읽는다. 없으면 프로파일을 고쳐도 UP-TO-DATE 로 넘어가 낡은 채 초록이다.
    inputs.files(rootProject.file("profile/fixtures"))
        .withPropertyName("profileInputs")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
}
