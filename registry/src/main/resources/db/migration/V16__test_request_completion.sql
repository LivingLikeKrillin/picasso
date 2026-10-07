-- **시험 요청에 «끝남» 이 없었다.**
--
-- V2 는 요청을 적재하고 집는 데까지만 지었다(폴링 고리는 3a-2 로 미뤘다). 그래서 집은 요청은 15분 만료가
-- 지나면 다시 집힌다. 폴러가 있었다면 결과를 보고한 요청도 끝없이 다시 돌았을 것이다. 만료는 죽은 실행기가
-- 붙든 요청을 풀려는 것이지 끝난 요청을 되살리려는 것이 아니다.
--
-- ## 불리언이 아니라 시각이다
--
-- V15 의 `retired_at` 과 같은 이유다. *"언제 끝났는가"* 는 이력을 볼 때 반드시 오는 질문이고, 널이 정상값이며
-- 그 뜻이 하나다 — 아직 안 끝났다.
--
-- ## 열린 요청은 개정판마다 하나
--
-- 같은 개정판에 열린 요청이 둘이면 실행기 둘이 같은 문서를 따로 돌리고, 결과가 경합한다. 요청 문이
-- *"열린 요청이 있으면 그것을 돌려준다"* 로 멱등이어도, 동시에 온 두 요청은 둘 다 «없음» 을 보고 넣을 수 있다.
-- 그 경합을 막는 것은 코드가 아니라 색인이다.
ALTER TABLE revision_test_request
    ADD COLUMN completed_at TIMESTAMPTZ;

CREATE UNIQUE INDEX revision_test_request_one_open
    ON revision_test_request (profile_revision_id)
    WHERE completed_at IS NULL;
