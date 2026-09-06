// 계약을 두드려 완료 기준을 증명하는 얇은 소비자(§3.3).
// **오케스트레이터가 아니다** — 재시도 정책·태스크 순서·자원 배분은 미션
// 계층 몫이고 비목표다(§1.3).
dependencies {
    // **api다.** 공개 API가 계약 타입을 그대로 돌려주므로 implementation으로
    // 두면 harness가 응답을 열어 볼 수 없다.
    api(project(":contracts"))

    // 요구 집합 파서(§5.4). client → mimic 은 §3.2가 금지하므로 공유 모델에 둔다.
    //
    // **ProfileDocument는 쓰지 않는다** — 그것은 기종 저작 형식이고, 얇은
    // 소비자가 그것을 읽기 시작하면 게이트 7번이 막으려는 바로 그것이 된다.
    // ClientBoundaryTest가 그 선을 지킨다.
    implementation(project(":profile-model"))

    runtimeOnly(libs.grpc.netty.shaded)

    testImplementation(kotlin("test"))
    testImplementation(libs.grpc.inprocess)
}
