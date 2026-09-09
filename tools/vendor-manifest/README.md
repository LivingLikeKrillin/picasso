# 벤더 심볼 매니페스트

남쪽 포트가 *"벤더에게 이런 메시지가 이런 필드로 있다"* 고 짚은 것을 **벤더 원문과
대조**한다. 짚는 것은 `@VendorSurface`(adapter-core), 대조하는 것은 각 어댑터의
`*VendorSurfaceTest`, 대조 대상이 여기서 만드는 `vendor-manifest.txt` 다.

## 왜 생겼나

2026-09-08 에 같은 층에서 사고가 셋 났다. 전부 **사람이 벤더 문서를 읽고 KDoc 에
옮기는 단계**였고, 틀려도 아무것도 빨개지지 않았다.

| 무엇이 | 어떻게 틀렸나 |
|---|---|
| Digit 측정 | 제3자 래퍼를 원문으로 삼아 *"지속시간이 없다"* 등 셋을 만들어 냈다 |
| Spot `navigate_to` | `Waypoint.id`(로봇 생성)와 `annotations.name`(사람이 붙임)을 접었다 |
| Digit `ActionStatus` | 값이 넷인데 둘만 들어 **로봇이 신고한 실패에 도달할 수 없었다** |

## 원문을 저장소에 안 들인다

`adapter-boston-dynamics-spot/build.gradle.kts` 가 적어 둔 것을 지킨다 —
*"벤더 SDK가 여기 없고, 여기 말고는 어디에도 못 들어온다 … 남쪽이 포트라 SDK 없이
컴파일되고 시험이 돈다."* 들어오는 것은 **이름과 원본의 sha256** 뿐이다.

## 갱신 절차

원본을 손에 받아 놓고(저장소 밖) 해당 도구를 돌린다. 매니페스트는 **손으로 고치지
않는다** — 고치는 순간 그것이 다시 사람의 전사가 된다.

```bash
# Spot — protos/bosdyn/api 아래 .proto **전수**(152 개, 하위 디렉터리 포함)를 한 디렉터리로
#        모은 뒤. 골라 모으면 survey_scope 와 앞뒤가 안 맞는다(§15.75)
python tools/vendor-manifest/proto_symbols.py <원문디렉터리> \
  boston-dynamics "spot-sdk@<커밋>" \
  adapter-boston-dynamics-spot/src/test/resources/vendor-manifest.txt

# Digit — `json.py`(SDK)와 `user-manual.txt`(매뉴얼 본문)를 한 디렉터리에
python tools/vendor-manifest/agility_symbols.py <원문디렉터리> \
  agility-robotics "agility-sdk@<릴리스> + Digit User Manual" \
  adapter-agility-digit/src/test/resources/vendor-manifest.txt

# G1 — 원본이 저장소 둘이다. unitree_sdk2 에서 서비스 넷의 `*_api.hpp`·`*_error.hpp`
#      (loco·arm_action·agv·audio) + `common/terminations.hpp` + Cyclone DDS C++ IDL(`*_.hpp`),
#      unitree_sdk2_python 에서 `unitree_hg` IDL 파이썬을 한 디렉터리에. 서비스 하나만
#      넣으면 44 심볼로 조용히 통과한다(§15.82)
python tools/vendor-manifest/unitree_symbols.py <원문디렉터리> \
  unitree "unitree_sdk2 <릴리스>" \
  adapter-unitree-g1/src/test/resources/vendor-manifest.txt

# Orbit — 게시 스펙이 **파일로 배포되지 않는다.** 문서 페이지를 그대로 받고(스펙이 JS 객체로
#         인라인돼 있다) 벤더의 파이썬 클라이언트를 한 디렉터리에 둔다. 확장자가 원본의 종류다
#         (.html = 스펙, .py = 클라이언트).
#   curl -o <원문디렉터리>/orbit-docs.html https://dev.bostondynamics.com/docs/orbit/docs
#   curl -o <원문디렉터리>/client.py \
#     https://raw.githubusercontent.com/boston-dynamics/spot-sdk/<커밋>/python/bosdyn-orbit/src/bosdyn/orbit/client.py
python tools/vendor-manifest/openapi_symbols.py <원문디렉터리> \
  boston-dynamics "Orbit Web API <판> (게시본) + bosdyn-orbit@<커밋>" \
  adapter-boston-dynamics-orbit/src/test/resources/vendor-manifest.txt
```

돌린 뒤 `git diff` 로 **무엇이 사라졌는지**를 본다. 사라진 이름은 벤더가 개명했거나
없앤 것이고, 그것이 이 도구가 잡으라고 있는 그것이다.

## 이름 공간이 근거 등급을 나르기도 한다 (Orbit)

원본 하나가 벤더의 **약속**이고 다른 하나가 벤더의 **관행**일 때가 있다. Orbit 이 그렇다 —
게시 스펙은 공개 API 이고, 파이썬 클라이언트가 치는 경로 중 아홉은 **그 스펙에 없다.**
없는 쪽을 있는 쪽과 같은 이름으로 적으면 *"벤더가 공개 API 로 약속했다"* 가 거짓이 되므로,
클라이언트 쪽 이름에만 `bosdyn-orbit:` 접두사를 붙여 갈라 둔다. **벤더의 표기를 그대로 쓰는
원칙의 유일한 예외이고, 이유가 근거 등급이라 그렇다.**

## 원본이 기종마다 다르고, 그 차이가 곧 측정 결과다

| | 원본 | 덮는 것 | 못 덮는 것 |
|---|---|---|---|
| Spot | `.proto` 152 개(전수) | 메시지·필드·열거값·RPC | — |
| Digit | SDK `json.py` + 매뉴얼 본문 | 보내는 것(SDK) + 받는 것(매뉴얼) | 일반 오류 봉투의 모양(어디에도 없다) |
| G1 | C++ API ID 상수 + `UT_DECL_ERR` 에러 코드 + `terminations.hpp` 의 `inline bool` 종료 조건 + `Jsonize*` 의 JSON 키 + IDL(파이썬 생성본과 Cyclone DDS C++ 생성본 둘) | 무엇이 있는지, 요청 본문의 키, 에러 코드, 종료 조건, 상태 토픽의 필드 | **응답의 모양** — 어디에도 없다. 키에 타입·필수 여부가 안 딸려 온다 |
| Orbit | 게시 OpenAPI(문서 페이지에 **인라인된 JS 객체**) + 벤더의 파이썬 클라이언트 | 경로·메서드·스키마·필드·열거값, 그리고 **클라이언트만 아는 경로** | 응답 처리(클라이언트는 경로만 읽는다) · `type: object` 로 열린 자리 · **배포 인스턴스**(게시본보다 넓을 수 있다) |

## 이 검사가 못 하는 것

1. **거동을 안 본다.** 그 메시지를 보냈을 때 로봇이 무엇을 하는지는 전혀 안 본다.
   그것이 §9.7 ④·C-3 이고 열려 있다.
2. **있는 이름이면 통과한다.** `Waypoint.id` 대신 `Waypoint.snapshot_id` 를 짚어도
   둘 다 실재하므로 초록이다. **고른 것이 맞는지는 사람이 본다.**
3. **낡은 매니페스트는 낡은 코드와 사이좋게 초록이다.** 헤더의 릴리스와 해시가
   그것을 드러내는 유일한 장치다.
4. **검사할 타입 목록이 시험에 손으로 적혀 있다.** 새 남쪽 타입을 안 넣으면 안 본다.
5. **인용의 완전성은 못 본다.** 멤버가 **아무것도** 안 짚으면 잡지만, 다섯 개를 짚어야 할
   자리에 넷만 짚은 것은 통과한다 — 몇 개를 짚어야 맞는지는 이 검사가 알 수 없다.
   주입으로 확인했다(`JsonizeVelocityCommand.velocity` 를 지워도 초록).

## 못 덮는 것에는 세 종류가 있고, 섞으면 안 된다

| 종류 | 예 | 고칠 수 있나 |
|---|---|---|
| **추출기가 못 읽는다** | G1 의 요청 키(`json["velocity"]`)를 `const` 줄만 읽느라 놓쳤다 | **고쳤다.** 도구를 고치면 된다 |
| **벤더가 타입으로 안 적었다** | Digit 의 일반 오류 봉투 — 매뉴얼이 *"an error message"* 가 온다고 산문으로만 말하고 모양을 어디에도 안 적었다 | 못 고친다. 실물/시뮬레이터에서 받아 봐야 안다 |
| **검사의 성질** | 있는 이름이면 통과한다 · **인용이 빠진 것은 못 본다** · 거동을 안 본다 · 낡은 매니페스트 | 이 도구로는 못 고친다 |

`openapi_symbols.py` 가 그 셋을 한 번에 다 보여 줬다(2026-09-10). ① **정규식이 못 읽었다** —
클라이언트가 쿼리까지 한 문자열에 담아 쓰는 파견 경로를 놓쳤고, 동시에 `application/json` 을
경로로 읽었다(못 읽음과 만들어 냄이 한 정규식에서 같이 났다). AST 로 바꿔 닫았다.
② **벤더가 안 적은 것**은 `Run.missionStatus` 의 값 집합이다 — `enum` 이 없어 자유 문자열이다.
③ **검사의 성질**은 그대로다.

**첫째를 둘째로 적는 것이 이 저장소가 반복해 물린 실수다.** G1 문단이 한 번
*"스키마가 없어 인자 이름을 못 덮는다"* 고 적혀 있었는데, 벤더는 주고 있었고
우리가 안 읽고 있었다. §15.65 — 근거 등급이 낮으면 `NO` 가 아니라 `UNKNOWN`.

2026-09-09 에 **같은 파일에서 셋 더** 있었다 — `UT_DECL_ERR` 매크로(에러 코드 11개),
`inline bool`(종료 조건 7개), Cyclone DDS C++ IDL(`SportModeState_`). 넷 다
`unitree_symbols.py` 하나에서 났고, 첫 사례가 그 머리말에 이미 적혀 있었는데도 그랬다.
**규칙: 새 원문에서 0 개가 나오면 판정을 적기 전에 결함 주입으로 추출기가 그 파일을
실제로 읽는지부터 확인한다.** §15.82.

## 추출이 틀리는 두 방향은 위험이 다르다

이름을 **빠뜨리면** 시험이 빨개진다 — 시끄럽지만 안전하다. 이름을 **더 만들면**
없는 것을 짚어도 통과한다 — 조용히 검사가 약해진다. 그래서 규칙을 빡빡하게 둔다.
실제로 그 방향으로 두 번 틀렸다: `.proto` 의 `oneof` 가 이름 있는 스코프를 조기에
닫아 `Graph.waypoints` 가 사라졌고, `_CONST` 에 `re.M` 이 없어 G1 의 API 상수가
**통째로** 안 나왔다. 둘 다 시험이 빨개져서 드러났다.

**빠뜨림이 시끄러운 것은 누군가 그 이름을 짚고 있을 때뿐이다.** 2026-09-09 의 침묵
셋은 아무 시험도 안 짚은 표면에서 났으므로 아무것도 빨개지지 않았다. 빠뜨림에도
조용한 종류가 있고, 그것은 시험이 아니라 **범위 대조**(매니페스트 머리말의 원본
목록 ↔ 벤더 저장소의 실제 파일 목록 ↔ 거리 문서의 `survey_scope`)와 주입으로만 잡힌다.

## 부재는 벤더의 낱말로 다시 묻는다

매니페스트에서 **우리** 낱말을 찾아 0 건이면 그것은 부재가 아니라 검색 실패다.
Digit 에서 `nogo`·`no-go` 가 0 건이라 *출입 금지 구역이 없다* 고 적을 뻔했고, 벤더는
그것을 `keep-out` 이라 부른다. 부재를 적으려면 벤더 문서의 용어로 한 번 더 찾고,
그 낱말을 근거에 함께 적는다 — 다음 사람이 같은 검색을 다시 하지 않도록.

## 추출기의 시험은 **다시 뽑아 대조하는 것**이다

Gradle 이 추출기를 안 돌린다 — 원문이 저장소 밖이다. 그래서 추출기를 고칠 때의 시험은
*원본을 손에 놓고 다시 뽑아 체크인된 매니페스트와 diff 하는 것* 이고, 주입도 그렇게 잰다.

2026-09-10 에 `openapi_symbols.py` 로 일곱을 주입했다. 중첩 필드 상실 · 열거값 상실 ·
클라이언트 POST 경로 누락 · 호스트 접두사 잔류 · 자리표시자만인 경로 · 스펙 검색 실패 ·
문자열을 문자열로 안 봄. **일곱 다 잡혔고**, 그중 셋은 남쪽 시험(`OrbitVendorSurfaceTest`)이
함께 빨개졌으며 둘은 매니페스트 diff 로만 보였다 — *아무도 안 짚는 이름* 이라 그렇다.

★ 그 라운드가 **죽은 가지 하나**를 드러냈다. 열거값을 두 곳에서 모으고 있었는데 재귀가 이미
같은 이름을 만들고 있어, 한쪽을 지워도 매니페스트가 그대로였다. **주입이 안 잡히면 주입을
의심하되, 코드가 죽었을 수도 있다** — 검사가 검사하는 척하는 자리다.
