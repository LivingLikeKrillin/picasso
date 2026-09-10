# 아키텍처 — 경계가 왜 거기에 있나

모듈이 무엇인지는 [README 의 나무](../README.md)가 적는다. 이 문서는 **왜 그렇게 갈랐는가**를 적는다.
설계 문서 §3 의 요약이 아니라, 처음 읽는 사람이 30 분에 방향을 잡기 위한 지도다.

---

## 1. 층 넷 (ADR 36)

이 저장소의 가장 중요한 구분이다. **어휘가 배정과 실행을 겸업하면 안 된다**는 것이 ADR 36 이고, 그래서 층을 넷으로 가른다.

```
 ①  사이트의 일감        "랙 204 를 생산 순서 17 로 채워라"        ← 상류(MES·WMS)가 말한다
     ─────────────────────────────────────────────────────────────
 ②  능력·자격 어휘       "부품 시퀀싱" 이라는 논리적 능력          ← 이름은 상류의 것, 스키마는 우리 것
 ③  배정과 결정          슬롯 넷을 태스크 넷으로, 근거를 결합       ← picasso (미들웨어의 가운데)
     ─────────────────────────────────────────────────────────────
 ④  실행 계약            pick_place(object_id, destination)       ← contracts (gRPC + MQTT)
     ─────────────────────────────────────────────────────────────
 ⑤  벤더 조합            StopMission → LoadMission → PlayMission   ← 어댑터 안. **상류에 안 보인다**
```

**⑤가 ④ 위로 새면 안 된다**(ADR 36 결정 5). 어댑터가 벤더의 어느 층에 붙었는지 — 플릿 관리자냐 미션이냐 명령이냐 —
는 계약 쪽에서 보이지 않아야 하고, 그것이 이 저장소가 반복해 지키는 선이다. §15.77 이 그 선을 한 번 넘으려다
되물렸다.

**②의 이름은 우리가 못 짓는다.** 논리적 능력의 이름(`WorkMasterID` 자리에 오는 것)은 실제 상류를 만나야 안다.
지금 셋은 보고서가 *프로젝트 정의* 로 표시한 것을 그대로 쓴 것이다.

---

## 2. 데이터가 어떻게 흐르나

### 내려가는 길 — 일감이 움직임이 되기까지

```
JobOrder(작업 SEQ-204, 버전 17, 요구 등급 E2)
   │                                   상류가 원하는 결과. 슬롯도 태스크도 모른다
   ▼  picasso : LogicalCapability.plan()
ExecutionUnit × 4   (슬롯마다 하나)
   │                                   조합은 여기서 끝난다. 배차·라우팅은 밖(ADR 36)
   ▼  계약 : StartTask(task_id, revision, skill_type, parameters)
TaskHandle
   │                                   여기부터 기체 하나의 이야기다
   ▼  어댑터 : RobotAdapter.accept()
벤더 호출     (Orbit: POST dispatch · Spot: LoadMission+PlayMission · Digit: add-sequential-actions · G1: SetVelocity)
```

**단위 하나가 태스크 하나다.** 슬롯마다 태스크 하나이므로 *"어느 단위까지 끝났는가"* 를 태스크 자체가 답한다 —
계약에 부분 완료를 나르는 자리가 없어도 되는 이유다.

### 올라오는 길 — 두 갈래이고 둘 다 필요하다

```
로봇/벤더
   │
   ├─ 질의 : WatchTask · GetSnapshot · ReplayEvents      (gRPC — 소비자가 당긴다)
   │        └→ picasso 가 실행 상태를 민다
   │
   └─ 발행 : state · event · connection                  (MQTT — 기체가 민다)
            ├→ 소비자가 구독한다
            └→ IngestBridge 가 감싸 registry 로 적재한다   ★ 지금은 발신자가 in-process 로 민다
```

**스냅샷이 권위이고 이벤트는 그 사이다.** 둘 중 하나만 쓰면 신규 소비자가 놓친 전이를 못 세우거나(이벤트만)
그 사이에 무슨 일이 있었는지 모른다(스냅샷만). 버퍼를 벗어나면 스냅샷부터 다시 세우고, **못 보낸 구간을 버렸으면
세션이 바뀐다**(§15.107).

---

## 3. 상태기계가 둘이고 하나가 다른 하나를 대신하지 않는다

| | 실행 상태 (`picasso`) | 태스크 상태 (계약) |
|---|---|---|
| **무엇의 상태인가** | 논리적 능력 하나 = 상류의 일감 하나 | 원자적 태스크 하나 = 기체 하나의 스킬 한 번 |
| **값** | `REQUESTED`·`RUNNING`·`PARTIAL`·`IN_DOUBT`·`OPERATOR_HOLD`·`PHYSICALLY_DONE`·`UNVERIFIED`·`FAILED`·`CANCELING`·`ABORTED` | `ACCEPTED`·`RUNNING`·`PAUSED`·`SUCCEEDED`·`FAILED`·`RETRIABLE`·`NEEDS_INTERVENTION`·`CANCELLING`·`CANCELLED`·`CANCELLED_RECOVERY_FAILED`·`CONTROL_AUTHORITY_LOST` |
| **축이 하나 더** | `upstream_ack`(통보가 갔는가) — **물리와 독립이다** | 없다 |
| **누가 정하나** | 계약에서 온 것 + **설비 신호**(계약 밖) | 로봇이 말한 것 |

★**`UNVERIFIED` 가 실행 상태에만 있는 것이 요점이다.** *로봇은 끝났다는데 설비가 말이 없다* 는 계약이 답할 일이
아니다 — 계약은 기체 하나만 알고, 그 대조는 층 ③ 의 일이다.

그리고 계약 안에도 상태기계가 둘이다 — **태스크 상태**와 **스킬 FSM**(§4.5). 태스크는 스킬 호출의 나열이고,
`Halt → Reset → Start` 는 스킬 쪽 전이다.

---

## 4. 의존 규칙 — 무엇이 무엇을 모르는가

```
contracts        ← 프로젝트 내 의존 0.        게이트 5번이 강제한다
profile-model    ← gate 와 mimic 이 공유(ADR 29)
capability       ← mimic 과 adapter-host 가 공유. 투영과 협상 판정이 한 벌이어야 한다
uplink           ← mimic 과 adapter-host 가 공유. 발행과 적재
adapter-core     ← 어댑터 넷이 공유. RobotAdapter 의 북쪽 모양
adapter-host     ← 어댑터 하나를 계약 뒤에 세운다. 기종을 모른다(ADR 39)
adapter-<기종>   ← 기종을 아는 유일한 자리(ADR 33). contracts 하나에만 의존한다
picasso          ← 미들웨어의 가운데. 기종을 모른다(ADR 38)
registry         ← 어느 모듈에도 직접 밀지 않는다. 갱신은 mimic 이 당긴다(§3.2)
```

**기종을 알아도 되는 곳은 `adapter-<기종>` 뿐이다.** 게이트 7번이 나머지 여덟 모듈의 소스에 벤더·모델 문자열이
들어오면 실패시킨다 — 그것이 *"능력 집합이 다른 두 로봇을 같은 클라이언트 코드로"* 를 문장이 아니라 **CI 실패
조건**으로 만드는 장치다.

**`registry` 가 아무에게도 안 미는 것**도 규칙이다. 밀기 시작하면 원장이 런타임 의존이 되고, 레지스트리가 죽으면
로봇이 멈춘다. 대신 `mimic` 이 5 초마다 당긴다 — 그 지연이 §15.5 이고 받아들인 대가다.

---

## 5. 여기 없는 것

| 없는 것 | 왜 |
|---|---|
| 배차·라우팅·자원 중재·다중 로봇 경합 | 비목표. 조합과 실행 상태는 안이지만 그 위는 미션 서비스의 일이다 |
| 안전 기능(비상정지·보호정지·kill) | ADR 32. 안전은 자체 계통이며 소프트웨어 계약 위에 얹으면 **얹은 만큼 안전해 보인다** |
| 실제 상위 시스템 어댑터(ACL), MES·WMS Mock | 상류는 **예상 소비자**다. 통합 시험이 그 역할까지 |
| 무선 단절 대응의 **집행** | 계약은 단절을 **보이게** 하고 대응은 배치가 정한다(B-1) |
| 벤더 SDK | 라이선스가 막는 기종이 있고, JVM 바인딩이 없는 기종이 있다. 들어오는 것은 **이름과 sha256 뿐**이다 |

---

## 6. 어디부터 읽나

| 알고 싶은 것 | 읽을 것 |
|---|---|
| 무엇을 만들었나 | [README](../README.md) |
| **어디까지가 진짜인가** | [`verification.md`](verification.md) ★ 이 저장소에서 가장 먼저 읽을 문서 |
| 지금 무엇이 열려 있나 | [`limits.md`](limits.md) |
| 왜 이렇게 정했나 | [ADR 색인](adr/README.md) — 특히 33·36·37·38·39 |
| 계약이 실물에 얼마나 닿나 | [`profile/distance/`](../profile/distance) |
| 이 일감을 시키려면 현장에 무엇이 있어야 하나 | [`environment-preconditions.md`](environment-preconditions.md) |
| 전부, 순서대로 | [설계 문서](superpowers/specs/2026-09-05-picasso-design.md) — §15 는 일지이므로 뒤에서부터 읽어도 된다 |

모듈 안으로 들어가려면 각 모듈의 `README.md` 가 그 문 앞에 있다 —
[`picasso`](../picasso/README.md) · [`registry`](../registry/README.md) · [`mimic`](../mimic/README.md) ·
[`adapter-host`](../adapter-host/README.md) · [`gate`](../gate/README.md).
