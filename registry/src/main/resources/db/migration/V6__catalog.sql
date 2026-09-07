-- §9.6의 사이트 카탈로그가 필요로 하는 **계약 축의 폐기 예고**.
--
-- V1이 미룬 표 중 하나다. 지금 만드는 이유는 §9.3이 폐기 시각을 "계약 축과
-- 프로파일 축 **두 값 중 이른 쪽**"으로 규정했는데, 프로파일 축만 있으면
-- 그 규칙에 값을 넣을 자리가 하나뿐이라 규칙이 규칙이 아니게 되기 때문이다.

-- `skill_type`이 (name, major) 단위이므로 예고도 그 단위다. 한 major 전체를
-- 접는 것이 계약 축의 폐기다 — minor 단위 폐기는 §5.2가 금지한다(minor 증가는
-- 선택 파라미터 추가뿐).
CREATE TABLE skill_type_deprecation (
    skill_type_id    BIGINT      PRIMARY KEY REFERENCES skill_type,
    deprecated_after TIMESTAMPTZ NOT NULL,
    announced_by     TEXT        NOT NULL,
    announced_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    note             TEXT
);
