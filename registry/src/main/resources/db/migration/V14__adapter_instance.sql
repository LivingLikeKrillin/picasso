-- ADR 37 **결정 2** — 어댑터 인스턴스를 모델에 들인다.
--
-- 축이 셋이 된다. 앞의 둘은 V1 에 있었고 셋째가 없어서, *"이 발견이 어느 어댑터의 것인가"* 와
-- *"플릿 접속 정보를 어디에 두는가"* 에 원장이 답할 수 없었다(§15.103 의 정직 항목).
--
--   `adapter`         제품 — 벤더와 이름
--   `adapter_version` 빌드 — 버전과 그 빌드가 따르는 계약 semver, 적합성 상태
--   `adapter_instance` **배포된 것 — 접속 설정을 갖는 것**
--
-- ## 왜 이제야 만드나
--
-- §15.101 이 이 표를 미뤘고 이유를 적어 뒀다 — *"인스턴스는 접속 설정을 갖는 것인데, 직결의
-- 설정은 기체의 것이라 `robot.endpoint` 가 들고 플릿 경유의 설정은 **들 어댑터가 없다**. 아무도
-- 안 쓰는 표를 먼저 만들지 않는다."* 이제 플릿에 붙는 어댑터가 있고(§15.102~§15.104), 그것이
-- 붙으려면 플릿 주소가 어딘가 있어야 한다. **소비자가 생겨서 만든다.**
CREATE TABLE adapter_instance (
    -- **사람이 정하는 이름이다.** 배포에 붙이는 이름이며(`orbit-line-a`), 어댑터가 발견을 올릴 때
    -- 자기를 이 이름으로 신고한다. 생성 id 로 두면 사람이 그것을 설정 파일에 옮겨 적어야 하고,
    -- 그러면 ADR 37 이 *"벤더의 주소 공간을 사람이 손으로 옮긴다"* 고 적어 둔 그 비용이 는다.
    instance_id        TEXT PRIMARY KEY,

    adapter_version_id BIGINT      NOT NULL REFERENCES adapter_version,

    -- 이 인스턴스가 배포된 사이트. 발견이 올리는 `?site=` 와 같은 것이어야 하며, 다르면 그 발견은
    -- 다른 사이트의 기체를 이 인스턴스의 것으로 적는 셈이다.
    site_id            TEXT        NOT NULL,

    -- **플릿 경유일 때만 채운다**(ADR 37 결정 4). 직결이면 로봇의 주소가 `robot.endpoint` 에 있고
    -- 여기는 비어 있다 — 같은 사실을 두 곳에 두지 않는다.
    --
    -- **주소만이다. 자격증명은 여기 안 둔다** — §6.3 이 신원·접근을 범위 밖에 뒀고, 비밀을 어디
    -- 둘지 정하기 전까지 이 표를 자격증명 저장소로 만들지 않는다(`robot.endpoint` 와 같은 규율).
    fleet_endpoint     TEXT,

    registered_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    registered_by      TEXT        NOT NULL
);

CREATE INDEX adapter_instance_site ON adapter_instance (site_id);

-- **이 기체를 누가 올렸는가.**
--
-- §15.103 이 *"어댑터 인스턴스가 없어서 이 발견이 어느 어댑터의 것인지 원장이 못 답한다"* 로 남긴
-- 자리다. 발견이 자기 인스턴스를 신고하면 여기 앉는다.
--
-- **널이 정상값이다.** 선언된 기체는 사람이 적은 것이라 올린 어댑터가 없고, 인스턴스를 안 밝히고
-- 올린 발견도 있을 수 있다(옛 배포). 그것은 진단에서 *"어느 어댑터인지 모른다"* 로 보인다 —
-- 기본값으로 아무 인스턴스나 넣으면 그 거짓이 화면에서 진짜와 구별되지 않는다.
--
-- **FK 다.** 신고한 인스턴스가 실재해야 한다 — 실재하지 않으면 적재가 거절한다. 그것이 ADR 37 의
-- 절차(③ 어댑터 추가 화면에 접속 정보를 입력한다 → ④ 인스턴스를 띄우면 로봇이 흘러 들어온다)를
-- 표가 드는 방법이다.
ALTER TABLE robot
    ADD COLUMN discovered_by TEXT REFERENCES adapter_instance;

-- 선언된 기체는 올린 어댑터가 없다. 있으면 그 기록이 서로 모순이다.
ALTER TABLE robot
    ADD CONSTRAINT robot_declared_has_no_discoverer
        CHECK (origin IS DISTINCT FROM 'DECLARED' OR discovered_by IS NULL);
