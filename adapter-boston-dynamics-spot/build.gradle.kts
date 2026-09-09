// Boston Dynamics Spot 어댑터. 기종을 아는 자리다(ADR 33).
//
// **벤더 SDK가 여기 없고, 여기 말고는 어디에도 못 들어온다.** 라이선스
// `20191101-BDSDK-SL` §2(c)가 BD 하드웨어 전용이고 §2(b)가 재라이선스를
// 금하므로, Spot proto나 생성 스텁을 공통 계층에 두면 위반이다(ADR 31).
// 남쪽이 포트라 SDK 없이 컴파일되고 시험이 돈다.
dependencies {
    implementation(project(":contracts"))
    implementation(project(":adapter-core"))
    testImplementation(kotlin("test"))

    // 벤더 원문 대조 검사([VendorManifest]). **원문이 아니라 이름만** 들어온다 —
    // 매니페스트는 `src/test/resources` 의 텍스트 한 장이고 위 문단이 그대로 유지된다.
    testImplementation(testFixtures(project(":adapter-core")))
    // 끝에서 끝까지 — 미들웨어 → 계약 → 호스트 → 이 어댑터. 기종을 아는 쪽이 조립한다(게이트 7번 밖).
    testImplementation(project(":adapter-host"))
    testImplementation(project(":picasso"))
    testImplementation(project(":client"))
    testImplementation(project(":profile-model"))
    testImplementation(libs.grpc.inprocess)
}
