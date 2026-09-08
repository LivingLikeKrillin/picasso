// `java-test-fixtures` 는 [VendorSurface] 검사기 하나 때문에 있다. 그것을
// 본 소스에 두면 시험 기계장치가 배포물에 실리고, 어댑터마다 베끼면 ADR 29·33
// 이 금한 중복이 된다.
plugins { `java-test-fixtures` }

// 어댑터들이 공유하는 계약 쪽 어휘. **기종을 모른다** — 그것이 이 모듈의
// 조건이고 게이트 7번이 그것을 지킨다(ADR 33이 예고한 대가가 실현된 자리).
//
// 의존이 `contracts` 하나인 이유는 어휘가 계약 타입(`Fault`)을 싣기 때문이다.
// 벤더 SDK는 여기 절대 못 들어온다 — 들어오면 라이선스가 모든 어댑터로 샌다.
dependencies {
    implementation(project(":contracts"))
    testImplementation(kotlin("test"))
    testFixturesImplementation(kotlin("test"))
}
