-- §8.4 ②의 시험 요청과 보고.
--
-- **Task 3이 이것을 먼저 만드는 이유**는 활성화(③)의 승인 조건이 여기를
-- 읽기 때문이다 — *"status가 TESTED 또는 SUPERSEDED, 세 스위트 각각의 최신
-- 실행이 PASS"*. 이 표 없이 활성화를 만들면 그 조건을 빼거나 흉내내게 되고,
-- 그러면 **시험을 안 지난 개정판이 활성화되는 경로**가 열린다.
--
-- 요청을 집어가는 폴링 고리(harness 쪽)는 3a-2다. 여기서는 적재와 판정만.

CREATE TABLE revision_test_request (
    request_id          BIGSERIAL PRIMARY KEY,
    profile_revision_id BIGINT      NOT NULL REFERENCES profile_revision,
    requested_by        TEXT        NOT NULL,
    requested_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 클레임은 기본 15분 뒤 만료된다. 만료된 요청은 다른 harness가 다시
    -- 집어간다 — 그러지 않으면 harness가 죽었을 때 요청이 영구히 잡힌다.
    claimed_by          TEXT,
    claimed_at          TIMESTAMPTZ,
    claim_expires_at    TIMESTAMPTZ
);

CREATE TABLE revision_test_run (
    run_id              BIGSERIAL PRIMARY KEY,
    profile_revision_id BIGINT      NOT NULL REFERENCES profile_revision,
    request_id          BIGINT      REFERENCES revision_test_request,
    -- CONTRACT|NEGATIVE|DETERMINISM (§8.4 ③의 세 스위트)
    suite               TEXT        NOT NULL,
    -- PASS|FAIL
    result              TEXT        NOT NULL,
    ran_by              TEXT        NOT NULL,
    ran_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    detail              JSONB
);

-- **최신 실행만 본다.** 옛 FAIL이 남아 있어도 그 뒤 PASS가 있으면 통과다 —
-- 그러지 않으면 한 번 실패한 개정판을 영영 못 살린다.
CREATE INDEX revision_test_run_latest
    ON revision_test_run (profile_revision_id, suite, ran_at DESC);
