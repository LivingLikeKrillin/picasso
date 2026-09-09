// 발신자의 **위쪽 결선** — 브로커로 나가는 발행(§3.5 · §4.7)과 레지스트리로 가는 적재(§3.2 의 런타임 접근
// `⇢ registry`: 핸드셰이크 보고·태스크 관측·생존 보고·파일 폴백·재적재).
//
// 미믹만 갖고 있던 것을 2026-09-10 에 뺐다. 어댑터 호스트(ADR 39)가 같은 결선을 필요로 하고, 두 벌로 두면
// 토픽 형식·발행 열 헤더·적재 경로가 갈라진다 — `profile-projection` 을 뺀 이유와 같다(ADR 29). 기체가
// 미믹인지 실물 어댑터인지는 여기서 안 보인다: 받는 것은 계약 메시지뿐이다.
//
// 기종을 모른다 — 게이트 7번의 대상이다. `registry` 도 모른다(§3.2 — 이 방향은 빌드 의존이 아니라 HTTP 다).
dependencies {
    // `Publisher`·`Publication` 이 계약 메시지를 나르고, `MqttPublisher.connect` 가 `MessageHeader` 를 받는다.
    api(project(":contracts"))

    // 적재는 계약 메시지를 protobuf JSON 으로 적어 보낸다. 적재 표면이 같은 규약으로 읽는다.
    implementation(libs.protobuf.java.util)

    // §3.5 의 MQTT 발행. **추상은 이 라이브러리를 모른다** — `Publisher` 가 인터페이스이고 구현 하나가 이것을 쓴다.
    implementation(libs.paho.mqttv5)

    // 폴백 재적재가 JSONL 한 줄을 파싱한다.
    implementation(libs.jackson.databind)

    testImplementation(kotlin("test"))
}
