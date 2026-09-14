# ADR 30 — Protobuf 코드 생성의 Gradle 플러그인 위임

- **설계 문서 참조**: §3.2 (단서 조항), §15.20, §15.137, §15.138
- **결정 일자**: 2026-09-08
- **상태**: 결정됨 (코드베이스 반영 완료)

---

## 1. 맥락 및 배경

프로젝트 초기 단계에서 `buf` CLI는 Protobuf 린트(`lint`), 호환성 검증(`breaking`), 그리고 바이너리 디스크립터(`FileDescriptorSet`) 생성에 사용되었습니다. 코드 생성은 수행하지 않았으며, `buf.gen.yaml`은 비어 있는 상태였습니다.

가상 에뮬레이터(`mimic`)가 Protobuf 메시지(`Capability` 등)를 실제로 직렬화/역직렬화해야 함에 따라 생성된 Java/Kotlin 클래스가 필수적이게 되었습니다.

---

## 2. 핵심 결정 사항

**"Java Protobuf 코드 생성은 `com.google.protobuf` Gradle 플러그인을 통해 수행하고 protoc 바이너리는 Maven 저장소에서 자동 수신한다."**

`buf`는 린트 및 하위 호환성 검증, 바이너리 디스크립터 생성만을 전담하며, `buf.gen.yaml`의 `plugins` 목록은 비워 두고 해당 사유를 주석으로 명시합니다.

---

## 3. 결정 근거: Docker 의존성 격리

`tools/buf`는 Docker 래퍼 스크립트로 동작하므로, 코드 생성까지 `buf`에 위임할 경우 로컬 개발 머신에서 Docker 데몬이 실행 중이지 않으면 단순 `./gradlew compileKotlin`조차 실패하게 됩니다.

디스크립터 파일 부재는 테스트 실행 단계에서 친절한 안내 메시지와 함께 실패하므로 감수할 수 있지만, 소스 코드 컴파일 단계에서 `Unresolved reference` 수백 줄이 쏟아지는 치명적 개발 생산성 저하를 방지하기 위해 일반 컴파일 경로는 Docker 비의존적으로 유지해야 합니다.

---

## 4. 트레이드오프 및 관리 규칙

- **이중 컴파일러 운영**: Gradle 플러그인이 참조하는 protoc와 `buf` 내장 protoc 버전이 다를 수 있으나, 표준 메시지 직렬화 규격 내에서 동작하므로 상호 운용성 문제는 없습니다.
- **계약 다이제스트 일관성 검증**: `contracts` 바이너리 디스크립터의 SHA-256 해시가 헤더의 `contract_digest`로 쓰이므로, 빌드 도구 버전 업그레이드 시 디스크립터 바이트의 불변성을 대조 검증해야 합니다. (Protobuf 플러그인 0.10.0 및 Kotlin 2.4.20 업그레이드 완료로 deprecation 0건 달성).

---

## 5. 생성 클래스 네이밍 규칙

모든 proto 파일에 `java_multiple_files = true` 및 `java_package = "dev.picasso.contracts.v1"`이 명시되어 있으므로:
- 각 메시지는 최상위 클래스로 생성됩니다 (예: `SkillOuterClass.Capability`가 아닌 `Capability`).
- 불필요한 OuterClass 참조를 배제하고 최상위 심볼을 직접 임포트합니다.

> 마지막 대조: 2026-09-15 · sha256:6a8fb48be74a · 열림: 없음
