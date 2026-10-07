# ADR 49 — 개정판 시험 3종의 뜻을 정하고 실행기를 harness 에 둔다

- 상태: 확정 (2026-10-08)
- 관련: [ADR 9](0009-no-declaration-without-consumer.md) 소비자 존재 원칙, 설계 §8.4 · §3.2 · §12
- 변경 이력: 설계 문서 §15.207

## 맥락

프로파일 개정판은 `DRAFT → VALIDATED → TESTED → ACTIVE` 를 지난다(설계 §8.3). 활성화 조건은 상태가 `TESTED`·`SUPERSEDED` 이고 스위트 3개(`CONTRACT`·`NEGATIVE`·`DETERMINISM`)의 최신 실행이 모두 `PASS` 인 것이다(설계 §8.4 ③). 설계 §8.4 ② 는 «시험 요청 적재 → harness 폴링 인출 → 가상화 계약 검증 스위트 완주 → TESTED» 를 적었다. 설계 §3.2 순환 방지 규칙 1 은 registry 가 harness 를 부르지 않고, harness 가 폴링으로 집어 가서 돌리고 결과를 보고하게 정했다.

그 고리는 지어지지 않았다. 마이그레이션 `V2__testing.sql` 주석이 «폴링 고리(harness 쪽)는 3a-2다» 로 미뤘다. `harness` 의 `Suite` 열거형(`ContractSuite.kt`)을 쓰는 코드가 없었다. 요청을 집는 `TestRequestService.claim` 과 결과를 적는 `BindingService.recordTestRun` 은 시험 소스만 불렀다. 시험 요청에 «끝남» 칸이 없어 집은 요청은 15분 만료 뒤 다시 집혔다. `NEGATIVE`·`DETERMINISM` 이 개정판에서 무엇을 확인하는지 정한 문서가 없었다. 용어집의 «네거티브 테스트» 는 게이트 역검증(`gate/negative/`)이다.

바깥 첫 소비자는 picasso-ops(운영 화면 PoC)다. picasso-ops P2·S1d 설계 스펙(`docs/superpowers/specs/2026-10-08-p2-s1d-runner-binding-commissioning-design.md`, picasso-ops 저장소)이 화면에서 개정판을 시험 요청하고 진짜 실행기가 시험하게 정했다. 2026-10-07 사용자 결정은 사람이 PASS 를 적는 길이 아니라 진짜 실행기다. 실행기는 picasso-ops 의 가짜 현장(`site/`) 프로세스가 띄운다. 소비자가 생겨서 고리를 짓는다(ADR 9).

## 결정

**시험 3종의 뜻을 정한다.**

- `CONTRACT`(설계 §12.1 계약 테스트, §9.7 ③). 선언 스킬 전부와 `REQUIRED` 선택 필드로 협상하면 수락된다. 능력 조회(`GetCapabilities`)가 `CapabilityProjection.of(문서)` 와 같다(§12.2 #10). 선언 스킬마다 새 기체에서 태스크가 수락되고 멈춘다. 멈춘 결과는 성공이거나 프로파일이 선언한 결함으로 멈춘 것(`FAILED`·`RETRIABLE`·`NEEDS_INTERVENTION` 이고 결함 종류가 그 스킬에 선언됨)이어야 한다. 태스크마다 가상 시간 상한은 3600초다.
- `NEGATIVE`(설계 §10.4 ③ 프로토콜 한계 집행, §12.2 #7). 프로파일이 못 한다고 적은 것마다 탐침 1개를 보내 정해진 거절 코드가 오는지 본다. 선언 안 한 카탈로그 스킬은 `SKILL_ABSENT`, 필수 키 누락·선언 범위 밖 숫자·`max_length` 초과·`allowed_values` 밖 ENUM 은 `PARAMETER_INVALID`, `cancel_support: NO` 는 `CANCEL_UNSUPPORTED`, `pause_support: NO` 는 `PAUSE_UNSUPPORTED`, `REQUIRED` 선택 필드 없이 협상은 `REQUIRED_OPTIONAL_MISSING` 이다. `UNKNOWN` 지원은 시도를 허용하므로 탐침이 없다. 필수 키 탐침은 어느 프로파일에나 붙어 검사 0개로 통과하는 일이 없다.
- `DETERMINISM`(설계 §12.1 결정론적 검증). `CONTRACT` 의 태스크 시나리오를 같은 시드로 두 번, 각각 새 `Harness` 에서 돈다. 발행 전부(상태·이벤트·연결)와 태스크 갱신이 같아야 한다. 이벤트의 종류와 순서, 가상 시각, 진행률, 멈춘 상태를 댄다. `session_id`·`event_id` 는 JVM 전역 카운터를 품어 같은 시드로도 실행마다 다르므로 비교 전에 지운다.

**스위트가 검사를 하나도 안 돌렸으면 통과가 아니다.** 상세는 JSON `{"checks": <돌린 검사 수>, "failures": [{"check", "expected", "observed"}]}` 이고 `revision_test_run.detail` 에 그대로 들어간다. 검사 식별자는 `CONTRACT.capabilities`, `NEGATIVE.cancel_unsupported:pick_place` 꼴이다.

**파라미터는 선언에서 만든다.** `MinimalParameters` 가 선언(키·형·범위·길이·허용 값)에서 최소 유효값과 어긴 값을 만든다. 생성기는 기종 이름도 스킬 이름도 보지 않는다.

**실행기는 `harness` 의 운영 코드(`dev.picasso.harness.revision`)에 둔다.** `RevisionTestRunner` 가 요청을 집어 후보 문서를 임시 파일로 써서 기존 `Harness`(파일 경로만 받아 스키마 검사를 거침)에 넘기고, 가상 시계·고정 시드 in-process mimic 으로 3종을 돌려 보고한다. registry 에는 HTTP 로만 닿는다(`HttpTestDesk`, JDK `HttpClient`). `harness` 운영 코드는 여전히 `:registry` 에 의존하지 않고 `:capability` 의존이 하나 는다. picasso 에는 실행기를 상주시키는 `main` 이 없다. 첫 소비자가 프로세스 안에서 `start(interval)` 로 띄운다.

**registry 에 문 3개를 둔다.**

- `POST /operations/profile-revisions/{profileRevisionId}/test-requests`(운영자 토큰, `X-Actor` 필수). 201 새 요청. 200 끝나지 않은 요청이 이미 있음(그 요청, 멱등). 404 없는 개정판. 409 `DRAFT`·`REVOKED`(본문 `status`). 감사 `TEST_REQUEST` 는 새로 만들 때만 남는다.
- `POST /ingest/test-requests/claim`(적재 토큰, 본문 `worker`). 200 요청 id·개정판 id·집은 시각(`claimed_at`, DB 에 적힌 값)·후보 문서(문자열). 204 집을 것 없음. 끝난 요청은 집지 않는다.
- `POST /ingest/test-requests/{requestId}/results`(적재 토큰, 본문 `worker`·`claimed_at`·`results` 3개). 200 보고 뒤 상태. 400 스위트가 셋이 아니거나 모르는 결과·시각 형식 오류. 404 없는 요청. 409 는 본문 `reason` 으로 가른다(`COMPLETED` 이미 끝남, `NOT_CLAIMER` 집은 실행기나 집은 시각이 다름). 실행 3행(요청 id·상세 포함), 요청의 «끝남», 승격이 한 트랜잭션이다.

**스키마와 서비스를 맞춘다.** `V16__test_request_completion.sql` 이 `revision_test_request.completed_at` 칸과 부분 유일 색인 `revision_test_request_one_open`(개정판마다 끝나지 않은 요청 1개)을 더한다. 요청은 개정판 행을 잠그고 진행해 동시 요청의 둘째가 첫째의 요청을 본다. `TestRequestService` 에 `requestTest`(결과 타입 `TestRequested`)·`report`(결과 타입 `ReportOutcome`)를 더하고, `claim` 이 끝난 요청을 빼며 집은 시각을 돌려준다. 옛 `request` 는 `requestTest` 에 위임해 SQL 경로를 하나로 했다. `BindingService` 의 실행 기록과 승격을 `insertRun`·`promoteIfAllPass` 로 갈라 개별 기록(`recordTestRun`)과 보고가 같은 규칙을 지난다.

**실행기의 오류 처리를 정한다.** 문서가 적재(스키마)에서 거절되면 세 스위트를 모두 FAIL(`LOAD`)로 보고한다. 스위트 안의 예외는 그 스위트의 FAIL 이다. 집기에서 무응답·5xx 면 다음 폴링에 다시 집는다. 401 이면 토큰 설정 오류로 로그에 남기고 폴링을 계속한다. 보고의 응답을 못 받으면 같은 보고를 최대 3번 다시 보내고, 다시 보낸 보고가 409 `COMPLETED` 면 앞 보고가 반영된 것으로 본다. 3번 모두 실패하면 포기하고 요청은 만료 뒤 다시 집힌다.

게이트 검사 11(아웃바운드 닫힌 목록)에 `harness/src/main/kotlin/dev/picasso/harness/revision/RevisionTestRunner.kt` 를 더했다. 모듈 의존 그림(`docs/diagrams/components.svg`)에 `harness → capability` 간선을 그렸다.

## 왜 이 모양인가

- 토큰을 둘로 가른다. 요청은 조작 문이다 — 사람이 «이 개정판을 시험하라» 고 적으므로 운영자 토큰이다. 집기·보고는 적재 문이다 — 기계가 관측한 것을 올리므로 적재 토큰이다. 운영자 토큰만 쥔 쪽(운영 화면)은 시험 결과를 적을 길이 없다.
- 보고에 집은 시각까지 댄다. 실행기 이름은 같은 이름으로 다시 뜰 수 있어 이름만으로는 죽은 실행기의 늦은 보고를 못 가른다. 만료가 지났어도 다른 실행기가 다시 집기 전이면 보고를 받는다. 결과는 실제로 돌린 것이고 만료는 막힌 요청을 풀려는 것이다. 집은 시각은 DB 값(마이크로초)을 그대로 돌려준다. 메모리 값(나노초)을 내주면 모든 보고가 거절된다.
- 적재 거절도 보고한다. 보고하지 않으면 같은 요청이 15분마다 다시 집히고 화면에 FAIL 이 끝내 안 보인다.
- «끝남» 칸을 둔다. 칸이 없으면 끝난 요청이 만료 뒤 다시 집힌다. 부분 유일 색인이 개정판마다 열린 요청을 1개로 묶어 요청 문이 멱등해진다.
- 생성기는 선언을 보고 값을 고른다. `ContractSuite` 의 «능력을 보고 값을 고르기 시작하면 그것이 곧 기종 분기» 는 클라이언트의 원칙이다(같은 클라이언트 코드로 이기종, 완료 기준 A-1). 생성기는 클라이언트가 아니라 처음 보는 프로파일의 시험 데이터를 만드는 쪽이다.
- 실행기는 registry 에 HTTP 로만 닿는다. 설계 §3.2 규칙 1 대로 registry 가 harness 를 부르지 않고, 모듈 의존도 생기지 않는다.

## 고르지 않은 것

- 사람이 화면에서 PASS 를 적는 길. 운영자 토큰을 쥔 쪽이 실행 없이 PASS 를 보낼 수 있다. 2026-10-07 사용자 결정은 진짜 실행기다.
- 실행기 결과 보고를 운영자 토큰 문에 두는 길. 운영 화면이 실행 없이 결과를 적을 수 있게 된다.
- 실행기를 registry 안에 두는 길. 설계 §3.2 위반이고, 시험 도구 없이 registry 가 안 뜬다.
- `NEGATIVE` 를 빼고 2종으로 줄이는 길. 설계 §8.4 를 고쳐야 한다.

## 대가

- `harness` 시험 의존에 Spring Boot(starter-web)가 든다. harness 시험 클래스패스에서 registry(Spring)를 띄우면 slf4j 구현 충돌(mimic 쪽 NOP 와 Spring 의 logback)로 기동이 거부된다. 시험이 Spring 의 로깅 초기화만 끈다(`org.springframework.boot.logging.LoggingSystem=none`).
- `harness` 운영 코드의 모듈 의존이 `:capability` 하나 는다.
- 옛 `request` 의 동작이 둘 바뀐다. 열린 요청이 있으면 그 id 를 돌려주고, 없는 개정판·`DRAFT`·`REVOKED` 는 예외다.
- 실행기를 상주시키는 `main` 이 picasso 에 없다. 띄우는 것은 소비자 몫이다.
- 시험 요청의 취소가 없다. 만료만 있다.
- mimic 의 후보 개정판 검증 모드(설계 §10.2 `--registry --profile-revision`)는 짓지 않았다.
- `java.util.Random` 은 이웃한 작은 시드의 첫 난수가 거의 같다. 시드 0~6 으로 humanoid-a `pick_place`(작업 시간 45초, 지터 비율 0.1)를 새 기체에서 돌리면 모두 48초째 성공하고 자취가 같다. 지터를 선언하지 않은 `quadruped-b` 는 시드를 바꿔도 같은 자취다. `DETERMINISM` 의 결함 주입은 먼 시드(987654321)로 넣었다.

## 대는 것

| 주장 | 시험 |
|---|---|
| `humanoid-a` 는 `CONTRACT`·`NEGATIVE`·`DETERMINISM` 을 모두 통과한다 | `RevisionSuitesTest` · `humanoid-a 는 셋 다 통과한다` |
| `quadruped-b` 는 3종을 모두 통과한다 | `RevisionSuitesTest` · `quadruped-b 는 셋 다 통과한다` |
| `NEGATIVE` 는 프로파일이 못 한다고 적은 것마다 탐침 1개를 보낸다 | `RevisionSuitesTest` · `NEGATIVE 는 프로파일이 못 한다고 적은 것마다 탐침을 보낸다` |
| 적재에서 거절된 문서는 세 스위트가 모두 FAIL(`LOAD`)이다 | `RevisionSuitesTest` · `적재에서 거절된 문서는 셋 다 FAIL 이다` |
| 보고의 응답을 못 받으면 다시 보내고, 409 `COMPLETED` 면 앞 보고가 반영된 것으로 본다 | `RevisionTestRunnerTest` · `보고의 응답을 못 받으면 다시 보내고, 다시 보낸 보고가 이미 끝남이면 거기서 멈춘다` |
| 보고는 최대 3번 다시 보내고 포기한다 | `RevisionTestRunnerTest` · `보고를 최대 3번 다시 보내고 포기한다` |
| 요청 → 집기 → 3종 → 보고로 개정판이 `TESTED` 가 된다 | `RevisionRunnerEndToEndTest` · `humanoid-a 와 quadruped-b 가 요청 → 집기 → 3종 → 보고로 TESTED 가 된다` |
| 운영자 토큰으로는 집지 못한다 | `RevisionRunnerEndToEndTest` · `운영자 토큰으로는 집지 못한다` |
| 끝난 요청은 만료가 지나도 다시 집히지 않는다 | `RevisionTestRequestTest` · `끝난 요청은 만료가 지나도 다시 집히지 않는다` |
| 같은 이름으로 다시 집은 요청에 옛 집은 시각의 보고는 `NOT_CLAIMER` 다 | `RevisionTestRequestTest` · `같은 이름으로 다시 집은 요청에 옛 집은 시각의 보고는 받지 않는다` |
| 하나라도 FAIL 이면 `VALIDATED` 에 머문다 | `RevisionTestRequestTest` · `하나라도 FAIL 이면 VALIDATED 에 머문다` |
| 보고의 409 는 본문 `reason` 으로 갈린다 | `TestRequestEndpointTest` · `보고의 409 는 reason 으로 갈린다` |

이 표는 게이트가 이름으로 대조하지 않는다. 이름은 시험과 손으로 맞춘다.

> 마지막 대조: 2026-10-08 · sha256:f56d9885cbfc · 열림: 없음
