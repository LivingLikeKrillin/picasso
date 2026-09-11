# 그림 생성기 — 폭을 인자로 받는다

```
node tools/diagram-gen/<이름>.mjs docs/diagrams/<이름>.svg 1000   # 저장소 (docs/*.md 열 1012)
node tools/diagram-gen/<이름>.mjs <다른경로>/<이름>.svg 1180    # 더 넓은 열에 쓸 때
```

**왜 생성기인가.** 같은 그림이 두 폭으로 필요할 수 있고, 손으로 두 벌 두면 어느 날 한쪽만 고쳐진다.
넓은 캔버스를 저장소에 그대로 넣으면 글자가 바닥(9.5px) 밑으로 내려가므로 복사로는 안 된다.

다시 뽑은 뒤에는 **반드시** 둘을 돌린다.

```
node ~/.claude/skills/arch-diagram/scripts/validate-svg.mjs docs/diagrams/<이름>.svg --column 1012
node docs/diagrams/make-dark.mjs docs/diagrams/<이름>.svg
```

★**검증기가 못 잡는 것이 있다.** 설명한 선을 안 그린 것, 라벨을 정의만 하고 안 그린 것 — 둘 다
`task-states` 를 옮기다 실제로 냈고 렌더를 눈으로 보고서야 잡혔다(§15.119 · §15.140). 뽑았으면 본다.
