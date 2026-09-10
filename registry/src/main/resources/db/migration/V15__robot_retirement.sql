-- **기체가 현장을 떠나는 길이 없었다.**
--
-- ADR 37 이 등록의 두 문을 세웠는데 나가는 문은 안 세웠다. 그래서 지금까지 원장은 **한 번 들어온 기체가
-- 영원히 있는 곳**이었다 — 팔린 기체도, 폐기된 기체도, 다른 사이트로 옮긴 기체도 목록에 남고 진단 9번이
-- 그것을 계속 보여 준다. 현장에 처음 적용할 때는 안 보이는 구멍이고 **두 번째 해부터 보인다.**
--
-- ## 지우지 않는다
--
-- `DELETE FROM robot` 이 아니다. 행을 지우면 그 기체에 매달린 것이 전부 갈 곳을 잃는다 — 태스크 관측,
-- 감사 로그, 바인딩 이력. 그리고 **그 기체가 있었다는 사실 자체**가 사라져서, 지난달 그 라인에서 무엇이
-- 돌았는지 물으면 답이 없다. `consumer_requirement.active` 가 같은 이유로 불리언이었다.
--
-- ## 불리언이 아니라 시각이다
--
-- 이 표는 이미 `registered_at`·`registered_by` 로 *"언제 누가 들였는가"* 를 적는다. 나가는 쪽도 같은 모양이면
-- 두 사건이 같은 어휘로 읽힌다. 불리언은 *"언제부터 없는가"* 에 못 답하고, 그 질문은 이력을 볼 때 반드시 온다.
--
-- **널이 정상값이고 그 뜻이 하나다** — 현역이다. `origin` 의 널(문 밖에서 들어온 행)과 달리 여기는 모호하지 않다.
ALTER TABLE robot
    ADD COLUMN retired_at     TIMESTAMPTZ,
    ADD COLUMN retired_by     TEXT,

    -- **사유를 요구한다.** 퇴역은 사람의 판단이고, 판단은 이유가 있어야 나중에 되짚을 수 있다. 감사 로그에도
    -- 남지만 목록에서 바로 보이는 편이 낫다 — 운영자가 *"이건 왜 없어졌지"* 를 물을 때 감사 로그를 뒤지게 하면
    -- 아무도 안 뒤진다.
    ADD COLUMN retired_reason TEXT;

-- 셋이 같이 있거나 같이 없어야 한다. 하나만 있는 행은 코드의 결함이고, 그것이 화면에서는 *"퇴역했는데
-- 누가 했는지 모른다"* 로 보인다 — 표가 그런 행을 아예 못 만들게 한다.
ALTER TABLE robot
    ADD CONSTRAINT robot_retirement_is_whole
        CHECK ((retired_at IS NULL AND retired_by IS NULL AND retired_reason IS NULL)
            OR (retired_at IS NOT NULL AND retired_by IS NOT NULL AND retired_reason IS NOT NULL));

-- 진단 9번이 기본으로 현역만 본다. 퇴역한 기체가 많아지면 목록의 대부분이 지난 것이 되고, 그러면 아무도
-- 그 목록을 안 읽는다.
CREATE INDEX robot_active ON robot (site_id) WHERE retired_at IS NULL;
