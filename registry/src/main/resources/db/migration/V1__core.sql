-- 설계 §8.3의 관계형 스키마 중 **완료 기준 15·17이 요구하는 것만**.
--
-- 23개를 다 만들지 않는다. 지금 만들면 아무 시험도 안 지나는 테이블이
-- 생기고, 그것은 스키마가 아니라 주석이다. 언제 넣기로 했는지는 계획
-- 문서의 청크 표에 있다.
--
-- 아직 없는 것과 그것을 만드는 청크:
--   consumer, consumer_requirement          → 3b-1 (완료 기준 18)
--   change_plan, change_plan_step           → 3b-2 (완료 기준 19)
--   handshake_rejection                     → 3a-3 (완료 기준 13의 나머지)
--   revision_test_request, revision_test_run → 3a-2 (완료 기준 16)
--   capability_epoch_log                    → 3a-3 (진단 3)
--   skill_type_deprecation, skill_type_param,
--   profile_optional_field,
--   runtime_capability_override             → 요구하는 기준이 생길 때

-- ── 계약 축 ─────────────────────────────────────────────────────────────

-- `skill_type`은 읽기 전용이다(§8.1) — 계약이 소유하고 동기화 잡이 채운다.
-- 여기서 편집하면 계약과 두 번째 진실이 생긴다.
CREATE TABLE skill_type (
    skill_type_id         BIGSERIAL PRIMARY KEY,
    name                  TEXT   NOT NULL,
    major                 INT    NOT NULL,
    -- 이 스킬을 담은 최초 계약 semver. **바인딩 검사의 입력이다**(§9.1) —
    -- 어댑터 버전이 그보다 낮은 계약으로 빌드됐으면 그 조합은 거부된다.
    introduced_in_semver  TEXT   NOT NULL,
    contract_revision     TEXT,
    synced_at             TIMESTAMPTZ,
    removed_from_contract BOOLEAN NOT NULL DEFAULT false,
    UNIQUE (name, major)
);

-- ── 프로파일 축 ─────────────────────────────────────────────────────────

CREATE TABLE capability_profile (
    profile_id BIGSERIAL PRIMARY KEY,
    vendor     TEXT NOT NULL,
    model      TEXT NOT NULL,
    UNIQUE (vendor, model)
);

-- `revision`은 프로파일 문서가 스스로 선언한 값을 그대로 쓴다(§7.2의 기종
-- 좌표). 레지스트리는 **채번하지 않고** profile_id 안에서 단조 증가만
-- 강제한다 — 채번하면 문서의 좌표와 DB의 좌표가 둘이 된다.
CREATE TABLE profile_revision (
    profile_revision_id BIGSERIAL PRIMARY KEY,
    profile_id          BIGINT      NOT NULL REFERENCES capability_profile,
    revision            INT         NOT NULL,
    document            JSONB       NOT NULL,
    document_hash       TEXT        NOT NULL,
    schema_version      TEXT        NOT NULL,
    -- DRAFT|VALIDATED|TESTED|ACTIVE|SUPERSEDED|REVOKED
    status              TEXT        NOT NULL,
    -- 검증 실패의 사유. **DRAFT에 머무르며 여기 붙는다**(§8.4 ①) —
    -- 지우면 편집 후 재제출이라는 경로가 사라진다.
    validation_detail   JSONB,
    created_by          TEXT        NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_by        TEXT,
    activated_at        TIMESTAMPTZ,
    UNIQUE (profile_id, revision)
);

CREATE TABLE profile_skill (
    profile_revision_id BIGINT NOT NULL REFERENCES profile_revision,
    skill_type_id       BIGINT NOT NULL REFERENCES skill_type,
    minor               INT    NOT NULL,
    pause_support       TEXT   NOT NULL,
    cancel_support      TEXT   NOT NULL,
    deprecated_after    TIMESTAMPTZ,
    PRIMARY KEY (profile_revision_id, skill_type_id)
);

CREATE TABLE profile_skill_param (
    profile_revision_id BIGINT NOT NULL,
    skill_type_id       BIGINT NOT NULL,
    key                 TEXT   NOT NULL,
    value_type          TEXT   NOT NULL,
    optional            BOOLEAN NOT NULL,
    min_value           DOUBLE PRECISION,
    max_value           DOUBLE PRECISION,
    unit                TEXT,
    allowed_values      JSONB,
    max_length          INT,
    PRIMARY KEY (profile_revision_id, skill_type_id, key),
    FOREIGN KEY (profile_revision_id, skill_type_id)
        REFERENCES profile_skill (profile_revision_id, skill_type_id)
);

-- ── 어댑터 축 (§9.1) ────────────────────────────────────────────────────

CREATE TABLE adapter (
    adapter_id BIGSERIAL PRIMARY KEY,
    vendor     TEXT NOT NULL,
    name       TEXT NOT NULL,
    UNIQUE (vendor, name)
);

CREATE TABLE adapter_version (
    adapter_version_id BIGSERIAL PRIMARY KEY,
    adapter_id         BIGINT      NOT NULL REFERENCES adapter,
    version            TEXT        NOT NULL,
    -- 이 빌드가 따르는 계약 semver. 바인딩 합법성의 한쪽 입력이다.
    contract_semver    TEXT        NOT NULL,
    -- UNTESTED|PASSED|FAILED. **실행은 C-3이며 비목표지만 상태는 만든다**
    -- (§9.7 ④) — 안 만들면 나중에 워크플로우를 다시 짜야 한다.
    conformance_status TEXT        NOT NULL DEFAULT 'UNTESTED',
    registered_by      TEXT        NOT NULL,
    registered_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (adapter_id, version)
);

-- ── 바인딩 축 ───────────────────────────────────────────────────────────

CREATE TABLE robot (
    robot_id      TEXT PRIMARY KEY,
    site_id       TEXT NOT NULL,
    serial_number TEXT NOT NULL,
    display_name  TEXT,
    UNIQUE (site_id, serial_number)
);

-- **해제는 삭제가 아니라 `unbound_at`이다** — 이력이 남아야 진단 1번이
-- "이 기체는 어느 어댑터·개정판이었는가"에 답할 수 있다.
CREATE TABLE robot_binding (
    robot_binding_id   BIGSERIAL PRIMARY KEY,
    robot_id           TEXT        NOT NULL REFERENCES robot,
    adapter_version_id BIGINT      NOT NULL REFERENCES adapter_version,
    profile_revision_id BIGINT     NOT NULL REFERENCES profile_revision,
    bound_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    unbound_at         TIMESTAMPTZ,
    bound_by           TEXT        NOT NULL,
    reason             TEXT
);

-- 기체당 활성 바인딩은 **하나**다. 부분 유니크 인덱스가 그것을 DB에서
-- 강제한다 — 응용 계층에만 두면 동시 요청 둘이 지나간다.
CREATE UNIQUE INDEX robot_binding_one_active
    ON robot_binding (robot_id) WHERE unbound_at IS NULL;

-- ── 태스크 (pinning) ────────────────────────────────────────────────────

-- **`profile_revision_id`가 최초 접수 시점 값을 유지한다**(§8.4의 pinning).
-- 이것이 없으면 활성화가 진행 중인 로봇의 발밑을 바꾼다.
CREATE TABLE task (
    task_id             TEXT        NOT NULL,
    robot_id            TEXT        NOT NULL REFERENCES robot,
    profile_revision_id BIGINT      NOT NULL REFERENCES profile_revision,
    skill_type_id       BIGINT      NOT NULL REFERENCES skill_type,
    revision            INT         NOT NULL,
    attempt             INT         NOT NULL DEFAULT 0,
    state               TEXT        NOT NULL,
    terminal            BOOLEAN     NOT NULL DEFAULT false,
    accepted_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (robot_id, task_id)
);

-- §9.3의 드레인 판정이 이것을 센다.
CREATE INDEX task_inflight ON task (skill_type_id) WHERE NOT terminal;

-- ── 감사 (§8.5) ─────────────────────────────────────────────────────────

-- **API 한 번 = 트랜잭션 한 번 = 감사 로그 한 줄.** 조작 단위가 테이블
-- 행이 아니라 의도여야 한다.
CREATE TABLE audit_log (
    audit_id  BIGSERIAL PRIMARY KEY,
    operation TEXT        NOT NULL,
    actor     TEXT        NOT NULL,
    subject   TEXT        NOT NULL,
    before    JSONB,
    after     JSONB,
    at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
