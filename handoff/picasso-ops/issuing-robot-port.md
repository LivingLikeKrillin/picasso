# IssuingRobotPort 결선 안내

## 무엇이 바뀌었나
출발(`StartTask`)을 소비자 잠금 밖으로 보내는 포트 경로가 구현되었다. `RobotPort`에 선택 메서드 `issue`와 반환형 `Issue`(`Pending`, `Answered`, `NotSent`, `InDoubt`의 넷)가 추가되었고, 새 데코레이터 `IssuingRobotPort`는 받은 출발을 자기 스레드에서 위임 포트의 `start`로 보내며 기체마다 차선을 하나 두어 한 출발이 차선을 쥔 동안 같은 기체의 다른 출발은 걸지 않고 `NotSent`로 답한다. 결과는 한 번만 주고 받아 간 뒤 같은 키로 다시 물으면 새 송신이며, 미들웨어의 첫 출발, 개정 갱신, 미확정 조회 세 자리는 `Pending`을 받으면 라운드를 끝내고 다음 라운드에 결과를 받는다. 보내는 중에 걸린 취소와 개정은 결과를 받은 뒤로 미루어 처리하며, 취소(`cancel`·취소 재전송)와 플릿 포트는 동기 경로 그대로이다.

## 결선
기본 결선 `MissionHostApplication.robotPort`를 `IssuingRobotPort(ClientRobotPort(...), now = 미들웨어와 같은 시계)`로 감싸고 종료 때 `close`를 호출하면 된다. `PrefetchingRobotPort`와 함께 쓰면 두 데코레이터 모두 나머지 호출을 위임하므로 어느 쪽으로 겹쳐 감싸도 된다.

## 확인할 것
출발의 결과는 다음 pump에서 받으므로 출발 하나에 pump가 최소 한 번 더 필요하다. 하네스 기반 `ClientRobotPort`를 쓰는 `MockRunner`를 이 데코레이터로 감쌀 때 `PrefetchingRobotPort` 안내에 적힌 하네스 잠금 순서 제약을 만나는지는 picasso에서 확인하지 않았다(picasso의 시험은 송신을 시험 스레드에서 손으로 돌렸다). 답하지 않는 기체를 둔 채 HTTP 엔드포인트가 대기하지 않는지 보는 picasso-ops 쪽 통합 시험의 추가 여부와 구성은 picasso-ops가 정한다. `IssuingRobotPort`의 차선은 결과를 받아 가야 풀리고 결과를 버리고 차선을 풀면 같은 출발이 두 번 나갈 수 있어 풀지 않았으므로, 담는 쪽이 송신 중에 미들웨어 인스턴스를 버리고 포트를 계속 쓰면 같은 키로 다시 묻기 전까지 그 기체의 차선이 막힌다. 따라서 미들웨어 인스턴스를 새로 만들 때는 포트도 새로 만드는 편이 차선이 막히지 않는다.

## 남는 것
취소(`cancel`, 취소 재전송, 키로 만든 핸들의 취소)와 플릿 포트는 여전히 소비자 잠금 아래 동기이며, 적정 기한 예산은 현장 실측 전까지 UNKNOWN이다. 이 데코레이터의 반입 여부와 시점은 picasso-ops가 정한다.
