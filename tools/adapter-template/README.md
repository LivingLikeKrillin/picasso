# 어댑터 골격 — 새 기종을 시작할 자리

새 기종을 붙일 때 매번 남의 어댑터를 베꼈다. 베끼면 **그 기종의 사정까지 함께 온다** — Spot 의 리스
처리나 Digit 의 권한 게이트가 상관없는 기종에 남는다. 여기 있는 것은 **비어 있는 자리 목록**이다.

> 이 디렉터리의 `.kt.txt` 는 **컴파일되지 않는다.** 모듈이 되면 벤더도 프로파일도 없는 모듈이
> 빌드에 서고, 게이트 검사 여덟이 전부 그것을 대상으로 삼는다. 대신 **`RobotAdapter` 가 요구하는
> 자리를 빠짐없이 드는지를 시험이 지킨다**(`AdapterTemplateTest`) — 면이 늘면 골격이 빨개진다.

Open-RMF 의 `fleet_adapter_template` 이 `# IMPLEMENT YOUR CODE HERE #` 로 같은 일을 한다.
다른 점은 그쪽은 채울 자리만 주고, 여기는 **채우기 전에 재야 하는 것**을 먼저 가리킨다는 것이다.

---

## 순서 — 재는 것이 먼저다

**1~8 은 [`docs/vocabulary-distance.md`](../../docs/vocabulary-distance.md) 가 정한다.** 거기서 나온
`profile/distance/<기종>.json` 이 이 골격을 채우는 입력이다. 특히 두 칸이 그대로 코드가 된다.

| 거리 문서의 칸 | 골격의 어디로 가나 |
|---|---|
| `parameter_map[].kind` 가 `CONVERTED`·`SYNTHESIZED` 인 줄 | `accept` 안에서 옮기거나 조합하는 코드 |
| `adapter_must_own` | 그 어댑터가 떠안는 것 전부 — **코드량 견적이 여기 적혀 있다** |
| `execution_scope` | `poll` 과 종착 판정의 모양. `COMMAND` 면 시계로 적어야 한다 |
| `limitations` | 어느 `Refusal` 로 거절하는지 |

9 번부터가 이 문서다.

| | 하는 일 |
|---|---|
| 9 | 모듈을 만든다 — 아래 **파일 일곱**을 전부 둔다. `settings.gradle.kts` 에 `include` |
| 10 | `<기종>Link.kt` — 남쪽 면. 벤더가 실제로 주는 것만 적고 **`@VendorSurface` 로 원문을 짚는다** |
| 11 | `vendor-manifest.txt` — [`tools/vendor-manifest/`](../vendor-manifest/README.md) 로 뽑는다. **10 번의 인용이 여기 없으면 빨개진다** |
| 12 | `<기종>Adapter.kt` — `RobotAdapter` 의 **여덟 자리**를 채운다 |
| 13 | `profile/profiles/<기종>.json` — 거리 문서가 `YES` 로 잰 스킬만 선언한다 |
| 14 | 모듈 `README.md` — 이 기종이 계약에 얼마나 닿는지, 정본은 거리 문서를 가리킨다 |

## 파일 일곱

네 어댑터가 **전부** 가진 것이다. 하나라도 빠지면 `AdapterTemplateTest` 가 잡는다.

- `README.md`
- `build.gradle.kts`
- `src/test/resources/vendor-manifest.txt`
- `…Link.kt`
- `…Adapter.kt`
- `…AdapterTest.kt`
- `…VendorSurfaceTest.kt`

## 반드시 채우는 여덟

`RobotAdapter` 에 기본 구현이 **없는** 것들이다. 채우지 않으면 컴파일되지 않는다.

`state` · `accept` · `poll` · `pause` · `cancel` · `hold` · `faults` · `knownSiteNames`

## 안 채워도 되는 일곱 — 그리고 그것이 요점이다

`resume` · `update` · `retry` · `progress` · `failure` · `result` · `robotSoftware`

★**기본값은 *없다* 이지 *된다* 가 아니다.** 벤더에 그 프리미티브가 없는 것이 흔해서 기본 구현이
있는 것이고, **그대로 두는 것이 정직한 선택인 경우가 많다.** 재개·재시도는 조사한 셋 중 아무도
안 들고, 진행률은 지어낼 수 있는 유일한 값이라 기본이 *못 잰다* 다. 억지로 채우면 상류가 보는
숫자에 근거가 없어진다.

## 베끼지 말 것 — 기종의 사정이 따라온다

| 남의 어댑터에 있는 것 | 그 기종만의 사정 |
|---|---|
| 권한 게이트(`link.privilege`) | Digit 은 권한을 잃으면 로봇이 리셋된다 |
| 리스 상태 판정 | Spot 에만 기계적 근거(`LeaseUseResult.Status`)가 있다 |
| FSM 기대값 검사 | G1 이 명령을 받고도 그 모드에 없을 수 있다 |
| 시계로 종착 적기 | `execution_scope` 가 `COMMAND`·`NONE` 인 기종에서만 옳다 |

## 이 골격이 못 하는 것

**컴파일 안 된다.** 타입이 맞는지는 붙여 넣고 돌려 봐야 안다. 시험이 지키는 것은 *면의 자리가
빠짐없이 있는가* 까지이고, 그 자리를 **어떻게** 채웠는지는 안 본다 — 거리 문서의 `adapter_must_own`
과 같은 한계다.
