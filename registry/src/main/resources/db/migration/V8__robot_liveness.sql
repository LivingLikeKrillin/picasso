-- 기체가 살아 있다는 증거. §9.3의 두 조회가 이것을 먼저 본다.
--
-- **왜 필요한가.** V4의 원장은 관측선이 살아 있는지를 전역 워터마크
-- (`MAX(last_seen)`·`MAX(updated_at)`) 하나로 판정했다. 그러면 기체 열 대 중
-- 아홉이 보고하는 한 워터마크가 신선하고, **조용해진 한 대가 아직 돌리는
-- 능력이 "쓰는 사람 0명"으로 제거된다.** §15.41이 정해 둔 "틀리는 방향"이
-- 그 지점에서 정확히 반대로 뒤집힌다.
--
-- **별도 워터마크 표가 아니다.** V4의 KDoc이 경계한 것 — *"관측선이 살아
-- 있다는 사실을 관측 자신과 다른 곳에 적으면, 그 곳을 갱신하는 코드가 빠진 날
-- 원장이 살아 있다고 거짓말한다"* — 은 그대로 지킨다. 이 행을 만드는 것은
-- **기체가 살아 있어야만 오는 보고**이고, 보고가 멈추면 이 행이 늙는다.
-- 갱신하는 코드가 통째로 빠지면 전부 늙어서 축소가 막힌다. 안전한 쪽으로
-- 고장난다.
CREATE TABLE robot_liveness (
    robot_id         TEXT        PRIMARY KEY REFERENCES robot,

    -- **registry 가 받은 시각이다.** 발신자의 `occurred_at` 을 쓰면 §10.3의
    -- VIRTUAL 시계와 충돌한다 — 가상 시계는 `AdvanceClock` 으로만 전진하므로
    -- 하트비트가 임의로 낡거나 임의로 신선해 보인다. 워터마크 비교가 registry
    -- 쪽 `now()` 이므로 관측 시각도 같은 시계여야 한다.
    last_reported_at TIMESTAMPTZ NOT NULL,

    -- §4.7의 네 값. **HIBERNATING 이 여기 있어야 하는 이유가 이 표의 절반이다.**
    -- 하트비트를 상태 발행에 묶으면 `publish_interval` 이 ONLINE 일 때만 도므로
    -- 의도적으로 절전한 기체가 관측선에서 사라지고, 계약이 "침묵하지만 정상"을
    -- 표현하려고 만든 상태가 모든 축소를 영구히 막는 차단자가 된다.
    connection_state TEXT        NOT NULL,

    -- 마지막으로 보고된 capability_epoch. 축소 완료 검증이 이것을 짚는다.
    -- **채번자가 발신자이므로**(§8.2) 기체 사이에 비교할 수 없다. 비교는
    -- 언제나 같은 기체의 baseline 대비다.
    capability_epoch BIGINT      NOT NULL,

    -- 기체가 보고한 로봇 소프트웨어 식별자. 프로파일의 `derived_from` 과
    -- 대조한다. **NULL 은 "못 읽는 기종"이고 빈 문자열이 아니다** — 신원
    -- 질의가 아예 없는 실물이 있다(§2.3의 Unitree). 빈 문자열로 두면
    -- "버전이 비어 있다"로 읽힌다.
    robot_software   TEXT
);

-- 판정이 오래된 것부터 훑는다.
CREATE INDEX robot_liveness_stale ON robot_liveness (last_reported_at);
