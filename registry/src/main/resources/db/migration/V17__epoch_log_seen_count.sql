-- **세대 이력이 접은 횟수를 센다**(§15.36).
--
-- V3 의 접기는 같은 기체·세대·`profile_ref`·사유를 한 줄로 두고 시각만 갱신한다. 그래서 같은 사실을
-- 몇 번 받았는지가 사라졌다. 세대는 유효 능력이 바뀔 때마다 오르므로 같은 열쇠로 다시 오는 것은 같은
-- 사실의 재보고이고, 이 칸은 그 횟수다. 처음 적을 때 1, 접을 때마다 1 씩 오른다.
ALTER TABLE capability_epoch_log
    ADD COLUMN seen_count INTEGER NOT NULL DEFAULT 1;
