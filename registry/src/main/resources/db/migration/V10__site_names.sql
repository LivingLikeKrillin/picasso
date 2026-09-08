-- ADR 35 — 사이트 이름은 로봇 안에 산다. 그 결정의 **셋째 항목**을 만든다:
-- `registry`는 이름을 갖지 않고 **그 사실을 상태로만** 갖는다.
--
-- §15.68이 이것을 열어 두고 있었다. 미뤘던 이유는 *"등록의 형태가 기종마다
-- 다른 것이 아직 확인되지 않아서"* 였고, 실물 셋을 다 재면서 해소됐다 —
-- Spot은 지도 녹화 시의 웨이포인트 명명, Digit은 add-object·add-landmarks,
-- G1은 자리 자체가 없다.
--
-- 남았던 문제는 **무엇을 등록했다고 적을 것인가**였다. 기종마다 집합이
-- 다르므로 불리언 하나로는 "등록했다"가 기종마다 다른 것을 뜻하게 된다.
-- 답은 **유도**다: 그 기체가 바인딩한 프로파일이 선언한 스킬들의
-- 시맨틱 파라미터가 곧 등록해야 할 것이며, 손으로 적는 목록이 없다.

-- ── V1이 미뤄 둔 표. 그 기준이 이제 생겼다 ─────────────────────────────

-- V1의 머리주석이 `skill_type_param`을 *"요구하는 기준이 생길 때"* 만들기로
-- 미뤘다. 그 기준이 이것이다 — 어느 파라미터가 사이트 이름인지 알아야
-- 바인딩마다 등록 대상을 유도할 수 있다.
--
-- **`skill_type`과 같이 읽기 전용이다**(§8.1). 계약이 소유하고
-- `SkillTypeSync`가 채운다. 여기서 손으로 넣으면 계약과 두 번째 진실이
-- 생기고, 그때 스킬을 더하는 날 등록 대상이 갈린다.
CREATE TABLE skill_type_param (
    skill_type_id  BIGINT  NOT NULL REFERENCES skill_type,
    key            TEXT    NOT NULL,
    optional       BOOLEAN NOT NULL,
    since_minor    INT     NOT NULL,
    -- 계약의 `is_site_reference`. 값이 좌표나 수치가 아니라 **사이트가
    -- 저작하고 로봇에 등록해 둔 이름**이라는 표시다.
    site_reference BOOLEAN NOT NULL,
    PRIMARY KEY (skill_type_id, key)
);

CREATE INDEX skill_type_param_site_reference
    ON skill_type_param (skill_type_id) WHERE site_reference;

-- ── 바인딩이 그 이름들을 아는가 ────────────────────────────────────────

-- **불리언이 아니라 시각과 행위자다.** §9.7 ④의 `conformance_status`와 같은
-- 자리이되, 여기서는 "누가 언제 했다"가 감사 단서로 남아야 한다(§8.5).
-- 비어 있는 것이 곧 "아직 안 했다"이며 **그것이 기본값인 것이 요점이다** —
-- 등록하지 않고 바인딩하는 것은 허용하되 진단에 보인다.
--
-- 등록 대상이 빈 기종(G1처럼 시맨틱 스킬을 하나도 안 드는 경우)에서는
-- 이 열이 비어 있어도 `NOT_REQUIRED`로 읽힌다. 그 판정은 유도이므로
-- 여기 적지 않는다 — 적으면 프로파일이 바뀌는 날 낡는다.
ALTER TABLE robot_binding
    ADD COLUMN site_names_registered_at TIMESTAMPTZ,
    ADD COLUMN site_names_registered_by TEXT;
