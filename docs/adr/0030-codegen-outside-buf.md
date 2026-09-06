# ADR 30 — proto 코드 생성은 `buf`가 아니라 Gradle protobuf 플러그인이 한다

설계 §14에 없던 결정이다. 2단계 Chunk 1에서 내렸다.

## 맥락

1단계는 `buf`를 세 가지에 썼다 — `lint`, `breaking`, 그리고 `build`로 만드는 `FileDescriptorSet`. 코드 생성은 하지 않았고 `buf.gen.yaml`에 "2단계에서 활성화한다"고 주석만 있었다.

2단계는 `mimic`이 `Capability` 메시지를 실제로 만들어야 하므로 생성 코드가 필요하다.

## 결정

**`com.google.protobuf` Gradle 플러그인이 protoc를 Maven에서 받아 코드를 생성한다.** `buf`는 lint·breaking·디스크립터를 계속 맡는다.

`buf.gen.yaml`은 `plugins: []`로 비워 두고, **왜 비어 있는지를 그 파일에 적었다** — 그 파일을 여는 사람이 반드시 묻는 질문이기 때문이다.

## 근거

`buf`로 생성하면 **`./gradlew build`가 Docker 없이는 컴파일조차 되지 않는다.** `tools/buf`가 Docker 래퍼이기 때문이다.

디스크립터와 생성 코드는 같은 상황이 아니다.

| | 디스크립터 | 생성 코드 |
|---|---|---|
| 무엇의 입력인가 | **시험** 입력 | **컴파일** 입력 |
| 없으면 | 시험이 만드는 법을 찍고 실패한다 | `Unresolved reference`가 수백 줄 쏟아진다 |
| 사람이 원인을 읽는가 | 읽는다 | 못 읽는다 |

1단계에서 디스크립터를 이미 그 방식으로 두었고(§3.2의 단서), 그 대가를 감수할 만했던 이유는 실패 메시지가 친절하기 때문이다. 생성 코드에는 같은 논리가 적용되지 않는다.

## 대가

**같은 proto를 두 도구가 컴파일한다.** Gradle 플러그인이 받는 protoc 버전과 `buf` 내장본이 달라 생성 코드와 디스크립터가 미세하게 다를 수 있다. 우리가 쓰는 것은 메시지 구성이므로 실질 영향이 없다.

**protobuf-gradle-plugin 0.9.4는 Gradle 9.7.1에서 동작하지만 Gradle 10에서 깨진다.** legacy `Usage` 속성과 다중 문자열 의존 표기 경고가 플러그인 내부에서 나오므로 우리가 못 고친다. Gradle 10 이전에 플러그인 버전을 올려야 하며, 이것을 §15.20에 남겼다.

## 함께 알게 된 것

생성 결과를 실제로 확인해야 했다. 여섯 proto 전부에 `java_multiple_files = true`와 `java_package = "dev.picasso.contracts.v1"`이 **이미 있었다.** 그래서:

- 메시지가 최상위 클래스다 — `SkillOuterClass.Capability`가 아니라 그냥 `Capability`
- **`SkillOuterClass`라는 이름은 어디에도 생성되지 않는다.** `skill.proto`의 outer class는 동명 메시지가 없어 `Skill`이 된다

계획 초안이 `import picasso.v1.SkillOuterClass.Capability`라고 적었고 전부 틀렸다. **생성물을 보지 않고 이름을 짐작한 대가다.**

## 관련

- 설계 §3.2의 단서(`gate → contracts` 의존 없음), §15.20
- [ADR 29](0029-shared-profile-model.md) — 같은 청크에서 내린 모듈 결정
