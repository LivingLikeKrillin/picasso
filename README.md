# picasso — 이기종 로봇 표준 I/F 계약과 운영 변경 체계

이기종 모바일 로봇(휴머노이드·4족보행)을 공장 운영 시스템에 연계할 때 필요한 **표준 I/F 계약**을 정의하고, 그 계약을 **실물 없이 검증할 수 있는 상대**(`mimic`)를 만들고, **운영 중 변경을 계산 가능하게** 만든다.

주장은 둘이고, 둘 다 데모가 아니라 **CI 실패 조건 또는 조작 거부 조건**으로 만들어져 있다.

1. **이기종 대응은 코드가 아니라 프로파일 교체여야 한다.**
2. **운영 변경은 파급을 미리 계산할 수 있어야 한다.** 계산할 수 없으면 모든 변경이 도박이고, 도박이면 아무도 변경하지 않게 되어 시스템이 굳는다.

조직 원리 하나 — **추가는 안전하고 삭제는 위험하다.** 운영 변경 규칙 전부가 이 비대칭에서 나온다.

정본은 [설계 문서](docs/superpowers/specs/2026-09-05-picasso-design.md)다. 이 README 는 그 문서로 가는 입구이며, 둘이 어긋나면 설계 문서가 맞다.

## 무엇이 있나

```
contracts/                proto. 스킬·태스크·이벤트·결함. 프로젝트 내 의존 0
profile/
  schema/                 능력 프로파일 JSON Schema (+ 조사·거리·출처 스키마)
  profiles/               기종 프로파일 문서 (실물 셋 + 시험용 가상 기종)
  fixtures/ requirements/ 게이트·시험 전용 픽스처와 소비자 요구 집합
  vendors/                벤더 1차 자료 조사 — 무엇을 선언하고 무엇을 안 하는가
  distance/               계약 어휘와 벤더 표면의 거리 측정 (survey_scope 필수)
  provenance/             프로파일의 각 값이 어느 원문에서 왔는가
profile-model/            프로파일 문서의 읽기 전용 모델 — gate·mimic 공유
capability/               능력의 투영과 판정 — 프로파일 → Capability, 요구 집합 대 Capability 협상.
                          mimic·어댑터 호스트 공유. 전송을 모른다
uplink/                   발신자의 위쪽 결선 — 브로커 발행과 레지스트리 적재. mimic·어댑터 호스트 공유
gate/                     검증 규칙의 단일 구현. CI 와 registry 가 같은 코드를 호출
mimic/                    프로파일 주도 에뮬레이터 — "어댑터 + 로봇" 한 쌍을 대신
client/                   계약을 두드려 완료 기준을 증명하는 얇은 소비자
harness/                  계약 스위트의 주인. mimic 을 띄우고 client 로 돈다
registry/                 개정판·어댑터 수명주기, 바인딩, 의존 원장, 변경 계획, 카탈로그
picasso/                  미들웨어의 가운데 — 정준 모델(실행 상태 두 축·근거 등급·논리적 능력·취소 응답)과
                          공통 실행 구조. 접수 → 조합 → 실행 → 근거 결합 → 결과 통보. 기종을 모른다 (ADR 38)
adapter-core/             어댑터들이 공유하는 계약 쪽 어휘와 RobotAdapter. 기종을 모른다
adapter-host/             어댑터 하나를 계약의 gRPC 서비스 뒤에 세우는 서버. 기종을 모른다(ADR 39)
adapter-unitree-g1/       실물 어댑터 — 기종을 아는 유일한 자리 (기종마다 모듈 하나)
adapter-boston-dynamics-spot/
adapter-agility-digit/
adapter-boston-dynamics-orbit/
                          기체가 아니라 **플릿**에 붙는 어댑터 + 배치 런처. 발견이 여기서 실증된다(ADR 37)
tools/buf                 buf 를 Docker 로 실행하는 래퍼
tools/vendor-manifest/    벤더 원문에서 심볼 이름만 뽑아 남쪽 포트의 인용을 대조하는 도구
docs/adr/                 결정 기록
docs/environment-preconditions.md
                          이 일감을 시키려면 현장에 무엇이 있어야 하는가 — 설계 입력
docs/vendors/             로봇이 아닌 벤더 표면의 측정 노트 (플릿 관리 API)
```

의존 규칙과 각 모듈의 책임은 설계 §3 에 있다. 요점 둘 — **`contracts/` 는 프로젝트 내 의존이 0** 이고, **어댑터는 `contracts` 하나에만 의존**한다(`registry` 도 `profile-model` 도 모른다).

## 어떻게 돌아가나

**계약.** 명령과 질의는 gRPC, 발행(상태·이벤트·연결)은 MQTT 다. 태스크는 `(task_id, revision)` 으로 멱등하고, 생명주기에 취소·일시정지·재시도·복구 실패·래치 위반·제어권 상실이 들어 있고, 갱신마다 **로봇이 무엇을 들고 있는지**(`hold`)를 나른다 — 실물 조사에서 나온 것들이다. 계약은 **값이나 제약을 담지 않는다.** 그것은 프로파일의 몫이다.

**프로파일.** 기종이 무엇을 드는지를 선언하는 JSON 문서. `Support` 가 3값(`YES`/`NO`/`UNKNOWN`)인 것은 *"지원하지 않음"* 과 *"벤더 문서에 근거가 없어 모름"* 을 구분하지 못하면 작성자가 거짓말을 하게 되기 때문이다.

**게이트.** 계약과 프로파일이 어긋나면 PR 이 막힌다. 검사 여덟이 있고 CI 와 `registry` 가 기준선만 달리해 같은 코드를 부른다. 음성 케이스는 코드가 아니라 **데이터**로 보관한다.

**미믹.** 프로파일을 읽어 계약을 구현한다. 시드와 가상 시계를 고정하면 이벤트 시퀀스가 같다 — 결정성이 시험 규율의 최우선이다. 제어 채널로 전송 결함·침묵·결함·래치 위반을 주입할 수 있다.

**어댑터.** 북쪽은 계약, 남쪽은 벤더 포트다. **벤더 SDK 는 저장소에 없다** — 라이선스가 막는 기종이 있고, JVM 바인딩이 없는 기종이 있고, 남쪽이 포트라 SDK 없이 컴파일되고 시험이 돈다. 남쪽 포트가 벤더의 무엇을 짚고 있는지는 `@VendorSurface` 로 표시하고, `vendor-manifest.txt`(벤더 원문에서 뽑은 **이름과 해시만**)와 시험이 대조한다. 어댑터가 벤더의 어느 층(플릿 관리자·미션·명령)에 붙든 계약 쪽에서는 보이지 않아야 한다.

**레지스트리.** Spring Boot + PostgreSQL. 개정판과 어댑터의 수명주기, 바인딩, 의존 원장, 변경 계획, 사이트 카탈로그, 진단 표면 열. `mimic` 도 `harness` 도 모르며 어느 모듈에도 직접 밀지 않는다. 설정값은 환경변수로만 받는다(`PICASSO_DB_URL`·`PICASSO_DB_USER`·`PICASSO_DB_PASSWORD`·`PICASSO_PROFILE_SCHEMA`) — 기본값을 적으면 그것이 조용히 운영에 쓰인다.

## 빌드와 시험

필요한 것: **JDK 21**, **Docker**(buf 래퍼, Testcontainers 의 PostgreSQL·mosquitto). 전부 Kotlin, Gradle.

```bash
./gradlew build -Dpicasso.negative.strict=true -Dpicasso.buf="$PWD/tools/buf"
```

`gate` 는 `contracts` 에 빌드 의존을 걸지 않고 **디스크립터 바이트**를 런타임 입력으로 읽는다. 그 바이트를 만드는 것은 `protoc`(Gradle) 이고 **런타임 신원(`picasso.desc`)과 같은 파일**이며, `:gate:test` 가 그 태스크에 매달려 있다 — 손으로 먼저 돌릴 명령이 없다. 예전에는 `tools/buf build` 를 손으로 돌려야 했고 **안 돌리면 낡은 디스크립터가 낡은 코드와 사이좋게 초록이었다**(§15.22 → §15.111 에서 닫힘).

CI 는 [`.github/workflows/ci.yml`](.github/workflows/ci.yml) 이 같은 순서로 돌리고, PR 에서는 기준선을 뽑아 파괴 변경 검사(2·6번)와 소스 변경 없는 기종 추가 검사(8번)까지 요구한다.

미믹과 클라이언트는 CLI 다.

```
mimic  --robot <id>=<profile.json> [--robot ...] --schema <path> [--port 0] [--clock real|virtual] [--seed N]
client --target <host:port> --robot <id> --requirements <file> --skill <type> [--param k=v ...]
```

계약 스위트 자체는 `harness/` 의 시험이며 `./gradlew :harness:test` 로 돈다. 게이트는 `./gradlew :gate:installDist` 뒤 `gate/build/install/gate/bin/gate --repo . ...` 로 부른다 — 인자는 CI 파일이 가장 정확한 예다.

## 문서 지도

| 무엇을 알고 싶은가 | 어디 |
|---|---|
| **어디까지가 진짜인가** | [`docs/verification.md`](docs/verification.md) — 구간마다 실물·실 와이어·전송 없음·대역을 가르고, **계약을 누가 지었는지**까지 적는다. 이 저장소에서 가장 먼저 읽을 문서 |
| 경계가 왜 거기에 있나 | [`docs/architecture.md`](docs/architecture.md) — 층 넷, 데이터의 두 방향, 상태기계 둘, 의존 규칙 |
| **계약이 무엇을 약속하나** | [`docs/contract.md`](docs/contract.md) — 무엇이 이 면에 들어오는가(관문 둘), 무엇이 '아직' 이 아니라 '여기가 아님' 인가, **담보마다 그것을 지키는 시험**, 그리고 어댑터를 쓰기 전에 기종을 재는 절차 |
| 실물로 바꾸려면 어디를 고치나 | [`docs/seams.md`](docs/seams.md) — 교체 지점 아홉. 자리마다 인터페이스·지금 꽂힌 것·바꾸려면·안 고치는 것 |
| 상류 모델이 어디서 왔나 | [`docs/isa95.md`](docs/isa95.md) — 필드마다 표준의 것인지 우리가 지은 것인지. 정본이 유료라 못 짚은 칸은 `UNKNOWN` 으로 남긴다 |
| **현장에 넣고 나서 무엇을 바꾸나** | [`docs/commissioning.md`](docs/commissioning.md) — 마스터 데이터와 런타임을 가르고, 처음 적용하는 순서 열 단계와 **설정 표면 전부**를 적는다 |
| 모듈 안으로 | **모듈마다 `README.md` 가 있다** — 그 모듈의 규칙 하나 · 경계 · 없는 것 · 어느 시험이 무엇을 증명하나. 시작점은 [`contracts`](contracts/README.md) → [`picasso`](picasso/README.md) → [`adapter-host`](adapter-host/README.md) |
| 왜 이렇게 지었나, 전부 | [설계 문서](docs/superpowers/specs/2026-09-05-picasso-design.md) — §1 목적과 **비목표**, §3 아키텍처, §4 계약, §7 프로파일, §9 운영 변경, §11 게이트 |
| **무엇이 틀렸었고 무엇이 아직 안 되나** | 설계 문서 **§15 알려진 한계** — 항목을 지우지 않고 정정을 덧쓰는 운행 기록. 뒤집힌 판정에는 취소선과 정정 포인터가 남아 있다 |
| 일감 셋이 계약의 어디에 닿나 — 그리고 AMR 의 경계 | [`docs/scenarios.md`](docs/scenarios.md) — 용기 공급(AMR, 계약 밖) · 부품 시퀀싱(`pick_place`) · 설비 점검(`inspect`); 완료 세 계층과 근거 등급; 계약이 아직 못 주는 것 다섯 |
| 미들웨어의 가운데 — 정준 모델과 공통 실행 구조 | [`docs/superpowers/specs/2026-09-09-middleware-core-design.md`](docs/superpowers/specs/2026-09-09-middleware-core-design.md) — 실행 상태 두 축, 논리적 능력, 근거 등급, 정준 실패 분류, 취소 응답, 상류는 예상 소비자(통합 시험이 그 역할) |
| 내린 결정 | [ADR 색인](docs/adr/README.md) — 특히 9(소비 표면 없는 선언 금지), 31·33(어댑터 소유와 자리), 32(안전 기능은 안 나른다), 34·35(시맨틱 결속의 주인), 36(배정 어휘와 실행 계약을 가른다), 37(등록은 발견 아니면 선언), **38(미션 계층의 스키마와 PoC 엔진은 우리 것)** |
| 실물 넷이 계약에 얼마나 닿나 | [`profile/distance/`](profile/distance) — 기종별, 스킬별, 근거 등급과 조사 범위 포함 |
| 벤더가 무엇을 선언하나 | [`profile/vendors/`](profile/vendors) · [`docs/vendors/orbit.md`](docs/vendors/orbit.md) |
| 이 일감을 시키려면 현장에 무엇이 있어야 하나 | [`docs/environment-preconditions.md`](docs/environment-preconditions.md) — 로봇 쓰는 공장·창고를 짓는 쪽이 읽는 제약이자 제안 |
| 남쪽 포트의 벤더 인용을 어떻게 대조하나 | [`tools/vendor-manifest/README.md`](tools/vendor-manifest/README.md) |
| 어떤 순서로 지었나 | [`docs/superpowers/plans/`](docs/superpowers/plans) |
| **지금 무엇이 열려 있나** | [`docs/limits.md`](docs/limits.md) — 한계 대장. 의도적 밖 / 안에서 닫는다 / 밖에서 닫는다 로 갈리고, 열린 것마다 **무엇이 있어야 닫히나** 가 한 줄 |

## 지금 상태

설계 §13 의 네 단계(계약과 게이트 → 미믹·클라이언트·하네스 → 레지스트리 코어 → 원장과 변경 계획)가 구현돼 있고, 그 위에 미들웨어의 가운데(ADR 38)와 어댑터의 북쪽(ADR 39)이 섰다. 실물 어댑터가 넷 있다 — 기체 셋과 **플릿 하나**(발견 경로가 거기서만 실증된다). 계약 개정판은 **0.8.0** 이다.

정직하게 적어 둘 것.

- **어댑터 넷 중 어느 것도 실물에 붙여 보지 못했다.** 이유가 기종마다 다르다 — 라이선스, JVM 바인딩 부재, 공개 시뮬레이터가 저수준만 흉내냄, 그리고 플릿 하나는 붙일 인스턴스가 없음. 어댑터가 검증되는 범위는 계약 쪽 거동까지이며 **적합성 역검증(C-3)은 열려 있다.**
- **계약은 벤더의 명령 계층이 아니라 그 위에 있다.** 벤더 표면 넷을 재니 계약이 올라탈 층이 달랐고(없음 / 명령 / 미션 / 플릿), 못 닿는 이유는 대부분 **시맨틱 신원**이었다 — 계약은 사이트 이름을 나르고 벤더는 웨이포인트 id·픽셀·3D 점을 받는다. 그래서 ADR 34·35 가 있다.
- **결과 어휘는 0.6.0 에서 닫혔다** — `Fault.failure_class`(정준 실패 분류 열다섯)와 `Fault.vendor_detail`(벤더 원문, 진단 동반). 벤더 코드를 옮기는 것은 어댑터이고 상류는 분류로만 분기한다. 남은 것은 **발신자가 아직 없는 값 둘**(`PERCEPTION_FAILED`·`GRASP_PLANNING_FAILED` — 벤더는 내지만 우리 어댑터가 그 표면을 아직 안 읽는다)과, 옮김이 맞는지를 **실물에서 확인한 적이 없다**는 것이다.
- **능력은 기체의 성질이 아니라 (기체 × 현장)의 성질이다**(§15.81). 배정은 *일감이 요구하는 것 / 기체가 제공하는 것 / 현장이 보증하는 것* 의 세 쪽 맞춤이고, 셋째가 지금 계약 어디에도 없다.

## 이 저장소가 지키는 규율

- **근거 등급이 낮으면 `NO` 가 아니라 `UNKNOWN` 이다.** 제3자 래퍼를 근거로 *"없다"* 를 적었다가 판정 넷이 뒤집힌 적이 있다(§15.65).
- **범위를 먼저 적고 판정한다.** 서비스 54 개 중 3 개만 읽고 잰 적이 있고, 서비스 넷 중 하나만 읽고 잰 적이 있다. 거리 문서의 `survey_scope` 는 그래서 필수다(§15.75·§15.82).
- **추출기의 침묵을 벤더의 부재로 읽지 않는다.** 새 원문에서 0 개가 나오면 결함 주입으로 도구가 그 파일을 읽는지부터 확인한다. **부재는 벤더의 낱말로 다시 묻는다.**
- **벤더 원문은 저장소에 들이지 않는다.** 들어오는 것은 이름과 sha256 뿐이다.
- **결함 주입으로 시험을 시험한다.** 못 잡으면 시험 집합의 구멍이고, 주입이 시끄럽지 않았다면 주입부터 의심한다.
- **조용히 통과하는 것이 실패하는 것보다 나쁘다.** 게이트가 아무 검사도 안 돌리고 종료코드 0 을 낸 적이 있다. 요구 목록(`--require`)과 strict 음성 하네스가 그 대가다.
