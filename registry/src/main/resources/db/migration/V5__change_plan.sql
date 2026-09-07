-- §9.5의 변경 계획.
--
-- *"운영자가 의도를 선언하면 시스템이 절차를 만든다. 이것이 'SQL이 아니라
-- 운영에 적합한 체계'의 실체다."*
--
-- 완료 기준 19가 여기 선다 — **소비자가 남아 있거나 진행 중 태스크가 있으면
-- 제거 단계가 거부된다.** §12.2가 그것을 이 프로젝트의 두 주장 중 하나로
-- 꼽았다: 11번이 "소스 변경 0"을 CI 실패 조건으로 바꾸고, 19번이 "축소
-- 거부"를 조작 거부 조건으로 바꾼다.

CREATE TABLE change_plan (
    plan_id    BIGSERIAL   PRIMARY KEY,
    -- REMOVE_CAPABILITY | MIGRATE_MAJOR
    -- | RETIRE_ADAPTER_VERSION | RETIRE_PROFILE_REVISION
    intent     TEXT        NOT NULL,
    target     JSONB       NOT NULL,
    -- intent와 target을 정규화한 문자열. 경합 방지용이다.
    target_key TEXT        NOT NULL,
    site       TEXT        NOT NULL,
    -- DRAFT|ANNOUNCED|MIGRATING|DRAINING|APPLIED|ABANDONED
    status     TEXT        NOT NULL DEFAULT 'DRAFT',
    created_by TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    applied_at TIMESTAMPTZ
);

-- **같은 대상을 겨냥한 비종착 계획은 하나뿐이다**(§8.3). 둘이면 두 운영자가
-- 서로 모른 채 같은 능력을 지운다 — 그리고 각자 자기 계획의 전제 조건만
-- 본다.
CREATE UNIQUE INDEX change_plan_one_live
    ON change_plan (target_key, site)
    WHERE status NOT IN ('APPLIED', 'ABANDONED');

CREATE TABLE change_plan_step (
    plan_id      BIGINT      NOT NULL REFERENCES change_plan,
    seq          INT         NOT NULL,
    -- ANNOUNCE | OBSERVE_MIGRATION | DRAIN | APPLY
    kind         TEXT        NOT NULL,
    precondition JSONB       NOT NULL,
    -- **화면용 캐시다. 권위가 아니다**(§9.5). 실행 시점에는 언제나 다시
    -- 평가한다 — 그러지 않으면 "충족을 확인한 순간"과 "실행한 순간"
    -- 사이의 창이 사고가 된다.
    satisfied    BOOLEAN     NOT NULL DEFAULT false,
    satisfied_at TIMESTAMPTZ,
    executed_at  TIMESTAMPTZ,
    PRIMARY KEY (plan_id, seq)
);
