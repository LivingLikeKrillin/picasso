// 프로파일 문서 → 계약 `Capability` 투영. **mimic 과 어댑터 호스트가 같은 함수를 쓴다.**
//
// ADR 29 가 `profile-model` 을 만든 이유와 같다 — 같은 투영을 두 모듈이 각자 들면 완료 기준 10
// (*"프로파일에서 파생한 값 == GetCapabilities 응답"*)이 두 벌이 되고, 그 둘이 어긋나는 날 어느
// 쪽이 계약인지 알 수 없다. `profile-model` 에 넣지 않은 것은 그 모듈이 `contracts` 를 모르는 것이
// 설계 §3.2 의 결정이라서다(gate 가 그것을 통해 생성 코드를 끌어오지 않게).
//
// 기종을 모른다 — 게이트 7번의 대상이다.
dependencies {
    implementation(project(":contracts"))
    implementation(project(":profile-model"))
}
