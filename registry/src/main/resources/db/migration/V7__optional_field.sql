-- §7.2의 `optional_fields`를 관계로 편다.
--
-- V1이 미룬 표다. 지금 만드는 이유는 §9.6의 상위 표면이 **필수 선택 필드**를
-- 실어야 하기 때문이다 — 소비자가 "이 사이트에서 pick_place를 쓰려면 무엇을
-- 반드시 보내야 하는가"를 알아야 협상 전에 자기 요구를 맞출 수 있다.
--
-- 문서(JSONB)를 파싱해 답할 수도 있지만 그러면 **파싱이 두 곳에 생기고**
-- (§8.1), 카탈로그가 보는 것과 협상이 보는 것이 갈릴 수 있다.

CREATE TABLE profile_optional_field (
    profile_revision_id BIGINT NOT NULL REFERENCES profile_revision,
    -- 점표기 경로. 예: `task.parameters.verify_grasp`
    parameter_path      TEXT   NOT NULL,
    -- SUPPORTED | REQUIRED | UNSUPPORTED (§7.2)
    --
    -- **SUPPORTED → REQUIRED는 축소다**(§11.2의 6번). 소비자가 안 보내던 것을
    -- 보내야 하게 되므로 기존 소비자가 깨진다.
    support             TEXT   NOT NULL,
    PRIMARY KEY (profile_revision_id, parameter_path)
);

-- 상위 표면이 개정판별로 REQUIRED만 훑는다.
CREATE INDEX profile_optional_field_required
    ON profile_optional_field (profile_revision_id) WHERE support = 'REQUIRED';
