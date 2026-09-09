// 어댑터의 **북쪽** — 계약(§4.4·§4.8·§5.4)을 gRPC 로 내는 서버. 기종을 모른다.
//
// 어댑터 셋(Spot·Digit·G1)은 지금까지 남쪽(벤더 포트)과 계약 쪽 어휘만 있었고 계약 **서버**가 없었다
// (§15.77 "어댑터 인스턴스 없음"). 그래서 어댑터의 `result()`·`failure()`·`hold()` 가 `WatchTaskResponse` 로
// 올라가지 못했고, 미들웨어는 미믹 위에서만 돌았다. 이 모듈이 `RobotAdapter` 하나를 계약 뒤에 세운다.
//
// 의존: contracts(계약) · adapter-core(RobotAdapter) · profile-model + capability(프로파일을 Capability 로,
// 파라미터 검사)·capability(투영과 협상 판정) · uplink(발행·적재). 어댑터 모듈은 모른다 — 기종은 `RobotAdapter` 구현 뒤에 있고, 조립은 배치 쪽(시험·런처)이 한다.
// 게이트 7번의 대상이다.
dependencies {
    api(project(":contracts"))
    api(project(":adapter-core"))
    // 발행(§3.5)과 레지스트리 적재(§3.2) — 미믹과 같은 결선을 같은 코드로. `HostedRobot` 이 `Publisher` 를 생성자에 드러낸다.
    api(project(":uplink"))
    implementation(project(":profile-model"))
    implementation(project(":capability"))

    testImplementation(kotlin("test"))
    testImplementation(libs.grpc.inprocess)
    // **실 포트가 있어야 순서를 물을 수 있다** — `Server.getPort()` 는 기동 전에 던지고 기동 뒤에 답한다.
    // in-process 전송은 포트가 없어서 "포트가 열린 뒤에 ONLINE 이 나가는가" 를 물을 수 없다.
    testImplementation(libs.grpc.netty.shaded)
}
