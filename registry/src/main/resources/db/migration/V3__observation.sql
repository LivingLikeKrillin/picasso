-- §8.3의 관측 두 표. 진단 3번과 4번이 여기 선다.
--
-- **`registry`는 여기에 아무것도 판정해 넣지 않는다.** 세대도 거절도
-- `mimic`이 정한 것을 받아 적을 뿐이다 — 여기서 다시 판정하면 두 번째
-- 진실이 생기고, 그때 화면과 로봇이 다른 말을 한다.
--
-- **브로커가 없으므로 구독기도 없다**(§15.30). 적재 표면만 만든다 —
-- 브로커가 붙는 날 구독기가 `ObservationService`를 부르면 된다.

-- 세대 이력. §8.3의 `cause` 넷을 그대로 쓴다.
--   BINDING_CHANGED | RUNTIME_DEGRADED | OPERATOR_BLOCKED | RESTORED
--
-- `profile_ref`가 JSONB인 것은 §8.3의 규정이다. 두 컬럼으로 펴면 헤더의
-- `ProfileRef`와 모양이 갈리고, 그 순간 "헤더에 실려 온 것을 그대로
-- 적었다"가 아니라 "우리가 해석해 적었다"가 된다.
CREATE TABLE capability_epoch_log (
    epoch_log_id BIGSERIAL PRIMARY KEY,
    robot_id     TEXT        NOT NULL REFERENCES robot,
    epoch        BIGINT      NOT NULL,
    cause        TEXT        NOT NULL,
    profile_ref  JSONB       NOT NULL,
    detail       JSONB,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 진단 3번이 기체별 최신 줄부터 거슬러 읽는다.
CREATE INDEX capability_epoch_log_latest
    ON capability_epoch_log (robot_id, occurred_at DESC);

-- **같은 기체·세대·`profile_ref`는 한 줄이다.** 폴링과 발행이 같은 사실을
-- 반복해 주므로 그대로 쌓으면 이력이 같은 줄 수천 개가 되고 사유가 묻힌다.
-- 접는 것은 응용 계층이지만, 유니크 인덱스가 그것을 DB에서도 강제한다 —
-- 응용에만 두면 동시 요청 둘이 지나간다.
--
-- **세대가 같아도 `profile_ref`가 다르면 새 줄이다.** 그것이 카나리 전환의
-- 관측 지점이고, 접으면 완료 기준 20이 볼 것을 잃는다.
CREATE UNIQUE INDEX capability_epoch_log_fold
    ON capability_epoch_log (robot_id, epoch, profile_ref, cause);

-- 핸드셰이크 거절. 완료 기준 13의 **보고 절반**이 여기 선다.
--
-- `robot_id`에 FK를 걸지 않는다. 거절은 등록되지 않은 기체에도 날아올 수
-- 있고(그것 자체가 운영자가 알아야 할 사실이다), FK를 걸면 그 줄이
-- **적재에 실패해 조용히 사라진다.**
CREATE TABLE handshake_rejection (
    rejection_id BIGSERIAL PRIMARY KEY,
    robot_id     TEXT        NOT NULL,
    client_id    TEXT        NOT NULL,
    requirement  JSONB       NOT NULL,
    reason_code  TEXT        NOT NULL,
    detail       JSONB,
    at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX handshake_rejection_recent ON handshake_rejection (at DESC);
