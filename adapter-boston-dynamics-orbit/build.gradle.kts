// Boston Dynamics **Orbit** 어댑터 — 로봇이 아니라 **플릿**에 붙는다.
//
// 어댑터 모듈 셋(Spot·Digit·G1)은 전부 기체에 직결한다. 그래서 ADR 37 의 사분면 넷 중
// *플릿 경유* 가 표본이 없었고, 발견(어댑터가 플릿에 물어 기체 목록을 올린다) 경로를
// 실증할 수 없었다. 이 모듈이 그 자리다.
//
// **벤더 SDK 를 안 들인다 — 들일 것이 없다.** Orbit 은 REST 라 남쪽이 HTTP 클라이언트
// 하나이고, Spot proto·G1 DDS 처럼 라이선스가 걸린 산출물이 없다(ADR 31 의 격리가
// 여기서는 저절로 지켜진다). 그래도 규율은 같다: 벤더 원문은 저장소에 안 들이고
// `@VendorSurface` + `vendor-manifest.txt` 로 이름만 대조한다.
dependencies {
    implementation(project(":contracts"))
    implementation(project(":adapter-core"))

    // 발견을 레지스트리의 적재 문으로 올린다(ADR 37). **api 인 것은** `OrbitDiscovery` 가 그 타입
    // (`RobotDiscovery`)을 생성자에 드러내기 때문이다 — 조립하는 쪽이 무엇을 넣을지 보여야 한다.
    api(project(":uplink"))

    // 벤더의 REST 응답을 읽는다. **벤더 SDK 가 아니다** — Orbit 은 REST 라 들일 SDK 가 없고,
    // 이것은 JSON 파서일 뿐이다(다른 모듈이 쓰는 것과 같은 것).
    implementation(libs.jackson.databind)

    testImplementation(kotlin("test"))

    // 벤더 원문 대조 검사([VendorManifest]). 매니페스트는 게시 스펙과 벤더의 파이썬
    // 클라이언트에서 뽑은 이름 목록이다(tools/vendor-manifest/openapi_symbols.py).
    testImplementation(testFixtures(project(":adapter-core")))
}
