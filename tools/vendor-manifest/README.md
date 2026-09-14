# 벤더 심볼 매니페스트 추출 및 검증 도구 (Vendor Manifest Tooling)

하드웨어 어댑터의 남쪽 통신 포트가 인용하는 벤더 API 명세(메시지, 구조체, RPC, 필드)의 실재 여부를 **공식 벤더 1차 원문과 정밀 대조**하기 위한 정적 검증 도구입니다.

어댑터 코드는 `@VendorSurface` 어노테이션으로 인용 심볼을 명시하고, 각 어댑터의 `*VendorSurfaceTest` 단위 테스트가 본 도구로 생성된 `vendor-manifest.txt` 매니페스트와 전수 대조를 수행합니다.

---

## 1. 도입 배경

2026-09-08 수동 문서 전사 과정에서 발생한 3대 인적 오류를 계기로 도입되었습니다:
1. **Digit 지속시간 오판**: 비공식 제3자 래퍼 라이브러리를 참조하여 "동작 지속시간 파라미터 부재"로 잘못 판정
2. **Spot 위치 식별자 혼동**: 로봇 내부 생성 `Waypoint.id`와 엔지니어 저작 `annotations.name`을 단일 개념으로 축약
3. **Digit 실패 코드 누락**: `ActionStatus` 4개 상태 중 2개만 수용하여 로봇의 실제 실패 상태 처리에 도달하지 못함

---

## 2. 저장소 내 벤더 바이너리 SDK 배제 원칙

저장소의 순수성과 라이선스 준수를 위해 공식 벤더 SDK 바이너리는 저장소 내에 커밋하지 않습니다 (`adapter-boston-dynamics-spot/build.gradle.kts` 명시). 저장소에는 오직 **추출된 벤더 심볼 명칭과 원본 파일의 SHA-256 해시값**만이 텍스트 매니페스트로 체크인됩니다. 남쪽 통신 계층은 인터페이스로 추상화되어 SDK 설치 없이도 빌드와 단위 테스트가 수행됩니다.

---

## 3. 벤더별 매니페스트 추출 절차

공식 원문 아티팩트를 로컬 디렉터리에 다운로드한 후 해당 스크립트를 실행합니다. 매니페스트 파일은 수동 편집을 금지하며 스크립트 실행을 통해서만 갱신합니다.

```bash
# Spot — protos/bosdyn/api 아래 .proto 전수(152개)를 단일 디렉터리에 수집 후 실행 (§15.75)
python tools/vendor-manifest/proto_symbols.py <원문디렉터리> \
  boston-dynamics "spot-sdk@<커밋>" \
  adapter-boston-dynamics-spot/src/test/resources/vendor-manifest.txt

# Digit — json.py (공식 SDK) 및 user-manual.txt (공식 매뉴얼 텍스트) 수집 후 실행
python tools/vendor-manifest/agility_symbols.py <원문디렉터리> \
  agility-robotics "agility-sdk@<릴리스> + Digit User Manual" \
  adapter-agility-digit/src/test/resources/vendor-manifest.txt

# G1 — unitree_sdk2 (4대 서비스 *_api.hpp, *_error.hpp, terminations.hpp, Cyclone DDS IDL) 
#      및 unitree_sdk2_python IDL 수집 후 실행 (§15.82, 161개 심볼)
python tools/vendor-manifest/unitree_symbols.py <원문디렉터리> \
  unitree "unitree_sdk2 <릴리스>" \
  adapter-unitree-g1/src/test/resources/vendor-manifest.txt

# Orbit — 공식 Web API 게시본 문서 및 공식 파이썬 클라이언트(client.py) 수집 후 실행
python tools/vendor-manifest/openapi_symbols.py <원문디렉터리> \
  boston-dynamics "Orbit Web API <판> (게시본) + bosdyn-orbit@<커밋>" \
  adapter-boston-dynamics-orbit/src/test/resources/vendor-manifest.txt
```

---

## 4. 네임스페이스 기반 근거 등급 분리 (Orbit)

Orbit의 경우 공식 게시 OpenAPI 스펙과 공식 파이썬 클라이언트가 사용하는 엔드포인트 간에 괴리가 존재합니다. 파이썬 클라이언트가 호출하는 경로 중 9개는 공식 게시 스펙에 명시되어 있지 않습니다. 비공식 관행 경로를 공식 공개 API와 동일하게 취급할 경우 신뢰도가 왜곡되므로, 클라이언트 전용 경로에는 `bosdyn-orbit:` 접두사를 명시하여 근거 등급을 명확히 분리합니다.

---

## 5. 기종별 원본 소스 및 커버리지 분석

| 기종 | 1차 원문 데이터 | 커버리지 영역 | 구조적 사각지대 |
|---|---|---|---|
| **Spot** | `.proto` 152개 파일 (전수) | 메시지, 필드, Enum, RPC 정의 | 없음 |
| **Digit** | SDK `json.py` + 공식 매뉴얼 텍스트 | 발신 페이로드(SDK) 및 수신 응답(매뉴얼) | 범용 에러 응답 엔벨로프 구조 (자연어로만 기술됨) |
| **G1** | C++ API ID, `UT_DECL_ERR` 매크로, `terminations.hpp`, JSON 키, Cyclone DDS IDL | RPC 식별자, 요청 키, 에러 코드, 종료 조건, 토픽 필드 | **응답 데이터 구조 부재** (타입 및 필수 여부 미정의) |
| **Orbit** | 인라인 OpenAPI 명세 + 벤더 파이썬 클라이언트 | REST 경로, HTTP 메서드, 스키마, Enum, 클라이언트 전용 경로 | 응답 페이로드 처리 로직, `type: object` 미지정 영역 |

---

## 6. 매니페스트 정합성 검증의 한계 및 주의 사항

- **런타임 물리 거동 미검증**: 심볼 실재 여부만을 검증하며, 해당 메시지 송신 시 기체의 실제 물리 동작 여부(C-3, §9.7 ④)는 보증하지 않습니다.
- **의미적 심볼 선택의 인간 검토 필요**: 실재하는 유효 심볼 중 엉뚱한 필드를 인용하더라도 정적 검사는 통과하므로 엔지니어의 의미적 검토가 수반되어야 합니다.
- **부재 여부 판정 시 벤더 도메인 어휘 준수**: 자체 고유 어휘로 검색하여 결과가 나오지 않는 것을 '기능 부재'로 속단해서는 안 되며 (예: `no-go` 대신 벤더 표준 어휘 `keep-out` 사용), 반드시 벤더 공식 어휘로 재검색해야 합니다.

> 마지막 대조: 2026-09-15 · sha256:877102eccc27 · 열림: C-3, §15.1
