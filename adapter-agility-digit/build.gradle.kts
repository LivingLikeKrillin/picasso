// Agility Robotics Digit 어댑터. 기종을 아는 자리다(ADR 33).
//
// 남쪽이 WebSocket JSON(`json-v1-agility`)이지만 **여기 클라이언트 라이브러리가
// 없다** — 포트가 인터페이스라 붙이는 날 구현 하나가 들어온다. 세 어댑터 중
// 유일하게 벤더가 SDK 를 공개 배포하지 않는 기종이다(§15.65). 남쪽 포트는 처음
// **제3자 래퍼 코드**에서 파생했다가 판정 넷이 틀린 것이 드러나(`f225cac`) 벤더
// SDK 원문(`agility/messages/json.py`, 2021.06.01)과 매뉴얼로 다시 쟀다 —
// `src/test/resources/vendor-manifest.txt` 가 그 원문의 이름과 해시다.
dependencies {
    implementation(project(":contracts"))
    implementation(project(":adapter-core"))
    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":adapter-core")))
}
