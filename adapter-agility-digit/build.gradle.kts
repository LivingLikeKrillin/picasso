// Agility Robotics Digit 어댑터. 기종을 아는 자리다(ADR 33).
//
// 남쪽이 WebSocket JSON(`json-v1-agility`)이지만 **여기 클라이언트 라이브러리가
// 없다** — 포트가 인터페이스라 붙이는 날 구현 하나가 들어온다. 세 어댑터 중
// 유일하게 벤더가 공개 SDK 를 안 내놓은 기종이며(§15.65), 그래서 남쪽 포트가
// 벤더 문서가 아니라 **원시 JSON 을 쓰는 제3자 코드**에서 파생했다.
dependencies {
    implementation(project(":contracts"))
    implementation(project(":adapter-core"))
    testImplementation(kotlin("test"))
}
