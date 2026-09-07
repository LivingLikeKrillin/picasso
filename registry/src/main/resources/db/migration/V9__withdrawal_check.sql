-- 축소의 **시작 조건만 있고 끝 조건이 없던 것**을 닫는다.
--
-- §9.3은 진입 조건 둘을 엄격하게 정했지만, `APPLY` 이후 그 축소가 실제로
-- 어댑터까지 내려갔는지 판정하는 장치가 없었다. §15.5의 폴링 지연과 §10.6의
-- "registry 불통 중에는 마지막 능력을 유지" 때문에 **카탈로그에서는 사라졌는데
-- 로봇은 여전히 그 스킬을 받는** 중간 상태가 실제로 만들어진다.

-- ── 충족 안 됨의 두 이유를 가른다 ──────────────────────────────────────

-- `satisfied = false` 에는 뜻이 둘이다 — **정말 소비자가 남아 있는 것**과
-- **관측선이 끊겨 알 수 없는 것.** 운영자가 할 일은 전혀 다르다. 앞은
-- 기다리거나 옮기게 하는 것이고, 뒤는 왜 기체가 조용한지 찾는 것이다.
--
-- **`satisfied` 를 세 값으로 바꾸지 않는다.** "모른다"는 충족 안 됨이고
-- (축소가 안 열린다) 그 하위 분류일 뿐이다. 불리언을 바꾸면 그것을 읽는
-- 모든 자리가 흔들리고, 얻는 것은 이 한 컬럼으로 얻을 수 있는 것과 같다.
ALTER TABLE change_plan_step
    ADD COLUMN observability TEXT NOT NULL DEFAULT 'OBSERVED';
-- OBSERVED | NOT_OBSERVABLE

-- ── 축소가 어디까지 내려갔는가 ────────────────────────────────────────

-- `APPLY` 가 실행된 순간 각 기체가 보고하고 있던 epoch. 축소가 그 기체까지
-- 내려갔다는 것은 **이 값보다 큰 epoch 로 보고했다**는 뜻이다.
--
-- **전역 임계를 둘 수 없다.** §8.2가 `capability_epoch` 의 채번자를 발신자로
-- 정했으므로 기체 A의 7과 기체 B의 7은 비교할 수 없다. 비교는 언제나 같은
-- 기체의 baseline 대비다.
--
-- 행이 `APPLY` 시점에 박히는 것이 요점이다. 나중에 세면 그 사이에 새로
-- 바인딩된 기체가 baseline 없이 들어와 "이미 반영됨"으로 읽힌다.
CREATE TABLE withdrawal_baseline (
    change_plan_id BIGINT      NOT NULL REFERENCES change_plan,
    robot_id       TEXT        NOT NULL REFERENCES robot,
    epoch_at_apply BIGINT      NOT NULL,
    recorded_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (change_plan_id, robot_id)
);
