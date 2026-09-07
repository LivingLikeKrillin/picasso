-- §9.2의 의존 원장.
--
-- *"원장 없이 능력을 지우는 것은 '아무도 안 쓰겠지'이고, 원장이 있으면
-- '쓰는 사람 0명임을 관측했다'이다. 전자는 사고가 나고 후자는 안 난다."*
--
-- 이 두 표가 §9.3의 조회 1을 가능하게 하고, 그 조회가 완료 기준 19의
-- 축소 거부를 떠받친다. 여기가 비면 거기가 거짓말을 한다.

-- `consumer_id`는 **헤더의 `client_id`와 같은 값이다**(§8.3). 매핑 표를
-- 두면 그 매핑이 세 번째 진실이 되고, 매핑이 어긋난 날 원장이 조용히
-- 빈다.
CREATE TABLE consumer (
    consumer_id  TEXT        PRIMARY KEY,
    -- CLIENT | UPSTREAM_SYSTEM
    kind         TEXT        NOT NULL,
    site         TEXT        NOT NULL,
    display_name TEXT        NOT NULL,
    -- **미등록 소비자도 원장에 있다.** 첫 OBSERVED 관측에서 자동 생성되며
    -- 그때 registered=false다. POST /requirements 가 오면 true가 된다.
    registered   BOOLEAN     NOT NULL DEFAULT false,
    first_seen   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- **PK에 `source`가 들어간다**(§8.3 결정 6). 안 들어가면 같은 소비자가
-- 등록도 하고 관측도 됐을 때 한 행이 다른 행을 덮고, 그러면 등록을
-- 지웠을 때 **관측 기록까지 함께 사라진다.**
--
-- §9.3의 조회 1은 source를 구분하지 않는다 — active인 행이 하나라도
-- 있으면 "사용 중"이다.
CREATE TABLE consumer_requirement (
    consumer_id     TEXT        NOT NULL REFERENCES consumer,
    skill_type_name TEXT        NOT NULL,
    -- DECLARED(등록) | OBSERVED(협상 성공)
    source          TEXT        NOT NULL,
    version_range   TEXT        NOT NULL,
    first_seen      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- **비활성화는 자동이지만 삭제는 하지 않는다**(§9.2). 계절성 소비자를
    -- 지워버리면 원장이 거짓말을 한다.
    active          BOOLEAN     NOT NULL DEFAULT true,
    PRIMARY KEY (consumer_id, skill_type_name, source)
);

-- §9.3의 조회 1이 이것을 짚는다. 스킬 이름으로 들어와 active만 센다.
CREATE INDEX consumer_requirement_by_skill
    ON consumer_requirement (skill_type_name) WHERE active;

-- 감쇠 잡이 오래된 것부터 훑는다.
CREATE INDEX consumer_requirement_stale
    ON consumer_requirement (last_seen) WHERE active;
