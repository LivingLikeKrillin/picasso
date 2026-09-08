// Unitree G1 어댑터. **기종을 아는 유일한 자리다**(ADR 33).
//
// 의존이 `contracts` 하나인 것이 이 모듈의 성격이다 — `mimic`도 `gate`도
// `registry`도 모른다. 벤더 SDK도 **여기 없다**: CycloneDDS에 JVM 바인딩이
// 없어 지금 붙일 방법 자체가 없고, 남쪽이 포트(`G1Link`)이므로 SDK 없이
// 컴파일되고 시험이 돈다. 붙이는 날 그 구현이 이 모듈 안에 들어오며,
// 라이선스가 이 모듈 밖으로 새지 않는 것도 그래서다(ADR 31의 대가).
dependencies {
    implementation(project(":contracts"))

    // 계약 쪽 어휘는 어댑터끼리 공유한다(ADR 33). 두 벌로 두면 이 저장소가
    // 막으려는 바로 그 드리프트를 우리가 낸다 — ADR 29가 `profile-model`을
    // 뽑은 것과 같은 이유다.
    implementation(project(":adapter-core"))

    // 루트 build.gradle.kts가 junit-jupiter만 넣는다. kotlin.test는 별도다.
    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":adapter-core")))
}
