-- **플릿이 일련번호를 안 준다.**
--
-- ADR 37 의 발견 경로를 실제로 지으면서 드러났다(§15.103). Boston Dynamics Orbit 의
-- `Robot` 스키마는 `hostname`·`nickname`·`robotIndex`·`username` 뿐이고 일련번호가 없다.
-- Orbit 이 모르는 것은 아니다 — `Run.robotSerial` 에 있다. 다만 **기체 자원에 안 실린다.**
--
-- 그래서 `robot.serial_number NOT NULL` 이 발견 경로와 부딪쳤다. 고를 수 있는 것은 셋이었다.
--
--   ① 호스트명을 일련번호 자리에 넣는다 → **거짓말이다.** 주소는 바뀌고 일련번호는 안 바뀐다.
--   ② 일련번호 없는 발견을 거절한다 → 이 벤더에서는 발견이 아예 안 된다.
--   ③ **없을 수 있게 한다.**
--
-- ③ 을 골랐다. 일련번호는 **기체의 사실**이고 플릿은 그것을 나르지 않으며, 우리가 지어낼 수
-- 있는 것이 아니다. `robot_liveness` 가 *"안 물어봤다"* 와 *"못 한다더라"* 를 널로 가른 것과
-- 같은 규율이다 — **모르는 것은 모른다고 적는다.**
ALTER TABLE robot ALTER COLUMN serial_number DROP NOT NULL;

-- **선언에는 여전히 요구한다.** 사람이 화면에서 적는 경로에는 일련번호를 아는 사람이 있고,
-- 거기서까지 비워 두면 그 열은 아무 데서도 안 채워진다. 발견만 예외인 것이 요점이며 그 예외의
-- 근거는 벤더 표면이다(위 문단).
--
-- 출처가 비어 있는 행(문 밖에서 들어온 시험 픽스처)은 이 제약에 안 걸린다 — `IS DISTINCT FROM`
-- 이 널을 참으로 읽는다. 그 행들은 진단 9번에서 `UNREGISTERED` 로 보인다.
ALTER TABLE robot
    ADD CONSTRAINT robot_declared_has_serial
        CHECK (origin IS DISTINCT FROM 'DECLARED' OR serial_number IS NOT NULL);
