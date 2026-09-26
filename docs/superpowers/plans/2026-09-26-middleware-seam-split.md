# Middleware.kt 접합부 분리 구현 계획서

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `Middleware.kt`(2,149줄)에서 나중에 붙은 두 접합부의 판정과 장부를 세 클래스로 떼어, 거의 안 바뀌는 핵심 상태기계와 공개 창구, 그리고 접합부를 핵심에 잇는 조율만 남긴다. 거동은 바뀌지 않는다.

**Architecture:** `Middleware` 는 공개 API·생성자 서명·`Execution`·`Submission` 을 그대로 둔 파사드로 남는다. 피어 시스템 접합부(배정·공간)의 판정은 `AdmissionGate` 가, 운영자·설명층 접합부의 장부는 `RemedyDesk`(제안·승인)와 `IncidentLog`(사건 번들)가 가진다. 떼어 낸 클래스는 `Middleware` 가 든 실행 표를 같은 인스턴스로 읽기만 하고, 조율(승인 뒤의 접수, 라운드 끝의 봉인)은 `Middleware` 에 남는다. 함수는 이름과 본문을 그대로 옮긴다.

**Tech Stack:** Kotlin 2.4.20 · JDK 21 · Gradle · JUnit 5(`kotlin.test`) · 기존 게이트(`:gate:test`, gate CLI)

**참고 스킬:** @superpowers:test-driven-development(작업 1·5·7·9의 시험), @superpowers:verification-before-completion(모든 작업의 끝), @claim-ledger(PR 본문의 검증 문장)

---

## 왜 이 선으로 나누나

2026-09-26 `git blame` 실측. 기준일 9/17 은 오케스트레이션·narrator 협업이 시작된 날이다.

| 절 | 줄 | 9/17 이후 쓰인 줄 |
|---|---:|---:|
| 구동(상태기계·미확정 해소) | 558 | 10 (1%) |
| 근거 결합 | 158 | 21 (13%) |
| 결과 통보 | 97 | 19 (19%) |
| 취소 | 44 | 0 |
| 접수(배정·점유·구역·바닥 관문) | 507 | 310 (61%) |
| 제안과 승인 | 324 | 238 (73%) |
| 사건 번들 | 205 | 56 (27%) |

핵심 857줄은 그 뒤 6% 만 바뀌었고, 새 접합부 1,036줄은 58% 가 그 뒤에 쓰였다. **크기가 아니라 바뀌는 속도로 나눈다.** 구동 루프는 쪼개지 않는다. 순서(라운드 끝의 봉인, 종착 뒤의 통보)가 불변식이고 거의 안 바뀐다.

**남기는 조율은 빨리 바뀌는 코드다 — 알고 남긴다.** 접합부를 핵심에 잇는 함수들은 `Middleware` 에 둔다: `adopt`·`assign`·`liveExecutionCount`·`isHolding`·`reassign`(91줄, 9/17 이후 100%), `approveRemedy`·`attemptApproval`(74줄, 90%), `commit`(18줄, 100%). `adopt`·`assign`·`reassign`·`approveRemedy`·`attemptApproval`·`commit` 은 `submit` 을 부르거나 실행의 상태(`robotId`·`assignedAt`)를 바꾸고, `liveExecutionCount`·`isHolding` 은 `assign` 이 비용 항으로 쓰는 읽기다(`reassign` 도 앞엣것을 쓴다). 떼면 떼어 낸 클래스가 핵심을 되부르는 순환이 생기므로, 순서를 바깥(파사드)에 두는 쪽을 골랐다. 작업 7 이 `commit` 의 여섯 줄을 장부로 옮기므로, 이 계획이 끝나도 `Middleware.kt` 에는 빨리 바뀌는 줄이 170여 줄(91 + 74 + 12) 남는다. 떼어 내는 것은 **판정과 장부**이고, 그것을 부르는 조율은 남는다.

## 지킬 것

1. **시험을 고치지 않는다.** `picasso` 시험 265개(27개 파일)는 `Middleware` 의 공개 API 와 `Execution`·`Submission` 만 부른다. 옮기다가 시험을 고쳐야 하는 자리가 나오면 거기서 멈추고 사람에게 묻는다. 시험을 고치면 이 리팩터링의 판정 기준이 사라진다. 이 계획이 **더하는** 시험(`SeamPlacementTest`)과 **조이는** 시험(작업 1)은 예외다.
2. **생성자 서명을 그대로 둔다.** 시험이 이름 붙은 인자로 부른다. 이름·순서·기본값을 안 바꾼다. `private val` 을 떼어 생성자 인자로만 두는 것은 서명 변경이 아니다.
3. **함수 이름을 안 바꾼다.** `docs/orchestration.md` §2 표와 `docs/superpowers/specs/2026-09-25-peer-systems-dispatch-and-space-design.md` 가 관문 함수를 이름으로 댄다.
4. **본문을 안 바꾼다.** 옮기는 줄은 들여쓰기와 가시성 한정자만 바뀐다. 공통 절차 C 가 센다.
5. **파사드를 남긴다.** 문서가 `Middleware.admits`·`Middleware.submit`·`Middleware.approveRemedy`·`Middleware.attemptApproval` 을 소속까지 붙여 댄다. 작업 1 이 게이트가 그 주인과 멤버를 같은 파일에서 찾게 한다.
6. **`inner class Execution` 은 옮기지 않는다.** 바깥의 `now()`·`stateTime()` 을 쓰고, 시험이 `approvedBy`·`active` 를 읽는다. 새 파일은 `import dev.picasso.middleware.Middleware.Execution` 으로 받아 쓰기만 한다. `internal` 은 모듈 단위라 같은 모듈의 새 파일에서 보인다.
7. **줄바꿈.** 작업 트리의 `.kt`·`.md` 는 CRLF 다(인덱스는 LF). 고치는 파일은 CRLF 를 유지하고 새 파일도 CRLF 로 만든다. 파이썬으로 고치면 `newline=''` 로 읽고 쓴다. 예외 하나: `tools/stamp.py` 는 LF 로 쓴다(`newline='\n'`). 도장을 찍은 문서가 작업 트리에서 LF 가 되는 것은 `core.autocrlf` 가 커밋 때 맞추고 도장 해시도 줄바꿈을 안 보므로 그대로 둔다. 손으로 되돌리지 않는다.
8. **`docs/` 아래 글에 정답표의 낱말을 쓰지 않는다.** `GroundTruthTest` 의 `정답의 말이 새는 문서가 늘지 않는다` 가 `docs/` 의 모든 `.md` 를 훑는다. 이 계획서도 대상이다. 낱말은 `handoff/narrator/ground-truth.jsonl` 에서 `narrowable` 이 `false` 인 행의 `cause`·`candidates` 에 있다. 옮기는 코드의 주석 가운데 그 낱말을 품은 줄이 있으니(`.kt` 는 대상 밖), 코드 주석을 문서로 인용하지 않는다. 설계 일지는 면제 목록에 있다.
9. **머지·push 는 사용자가 명시적으로 허락할 때만.** 저장소는 공개다.
10. **PR 하나에 접합부 하나, 일지 한 항목.** PR 은 앞 PR 이 머지된 `main` 에서 딴다. 쌓아서 올려야 하면 머지 커밋으로만 합치고(스쿼시 금지) base 를 하나씩 `main` 으로 다시 겨눈다. 브랜치는 전부 머지된 뒤에 지운다.

## 파일 지도

| 파일 | 책임 | 작업 |
|---|---|---|
| `gate/src/test/kotlin/dev/picasso/gate/DocumentClaimsTest.kt` | 점 기호(`Owner.member`)의 주인과 멤버를 같은 파일에서 대조 | 1 |
| `picasso/src/main/kotlin/dev/picasso/middleware/Canonical.kt` (새) | 결과 통보·사건 번들·이벤트 자취가 나눠 쓰는 정준 투영 셋 | 2 |
| `picasso/src/main/kotlin/dev/picasso/middleware/AdmissionGate.kt` (새) | 배정 관문: `admits` 와 술어 다섯, 보조 넷 | 5 |
| `picasso/src/main/kotlin/dev/picasso/middleware/RemedyDesk.kt` (새) | 제안·가림·진단·승인 장부와 승인 판정 | 7 |
| `picasso/src/main/kotlin/dev/picasso/middleware/IncidentLog.kt` (새) | 사건 번들 봉인·조회·검토 지표·운영자 판단 부착 | 9 |
| `picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt` | 핵심 상태기계, 공개 창구, 접합부를 잇는 조율(판정과 장부는 위 셋에 위임) | 2·3·5·7·9 |
| `picasso/src/test/kotlin/dev/picasso/middleware/SeamPlacementTest.kt` (새) | 떼어 낸 선언이 제 파일에만 있는가 | 5·7·9 |
| `picasso/README.md` | 핵심 컴포넌트 표 | 10 |
| `CLAUDE.md` · `docs/verification.md` | 자동화 시험 수 | 5 |
| `docs/superpowers/specs/2026-09-05-picasso-design.md` | 설계 일지 | 4·6·8·11 |
| `docs/limits.md` | «번호가 N 까지 갔고» | 4·6·8·11 |

남는 `Middleware.kt` 는 1,500줄 안팎으로 본다. 지금도 약 3분의 1 이 KDoc 이고 그 비율은 유지된다.

## 공통 절차

스크립트 넷은 **부록**에 있다. 세션 스크래치에 편집기(또는 Write 도구)로 저장한다. 셸 헤어독으로 만들면 역슬래시가 먹힐 수 있다(2026-09-23 실측). `.sh` 는 **LF 로** 저장한다 — 이 계획서가 CRLF 라 그대로 옮기면 bash 가 `do\r` 에서 멈춘다. 저장소에는 커밋하지 않는다. 아래 `$S` 는 그 스크래치 경로다. 명령은 전부 저장소 루트에서 돈다.

### A. 로컬 표준 빌드와 XML 판정

```bash
./gradlew cleanTest build -x :harness:test -x :registry:test
PYTHONIOENCODING=utf-8 python "$S/xml_verdict.py"
```

기대: `BUILD SUCCESSFUL`, 그리고 `failed 0`. `tests` 는 작업 0 에서 적은 «기준 시험 수»와 같고(작업 5 의 시험 수 갱신부터는 1 많다) `skipped` 는 작업 0 의 값과 같다. 스크립트는 하나도 못 세거나 실패가 있으면 0 이 아닌 값으로 끝난다. 그래도 Gradle 의 종료 코드로 판정하지 않는다(CLAUDE.md §2.3). `cleanTest` 를 빼면 예전 구동의 XML 이 섞인다. 2026-09-26 에 Docker 없이 돈 `registry` 의 실패 322건이 그렇게 남아 있었다. `harness`·`registry` 는 `picasso` 를 안 쓰므로 로컬에서 빼고 CI 가 돌린다.

### B. 번들 동치 (가장 강한 판정 기준)

```bash
rm -rf picasso/build/export
./gradlew :picasso:test --rerun --tests '*ExportFixtureTest'
bash "$S/bundle_equiv.sh"
```

기대: 12줄이 전부 `same`, 끝줄 `files=12 bad=0`, 종료 코드 0. 커밋된 인계본 네 벌(`handoff/narrator/run-1..4`)과 지금 코드가 낸 네 벌이, 실시계(`wallClockAt`·`writtenAt`)와 구동 식별자(`runId`)를 가리면 바이트까지 같다는 뜻이다. `rm -rf` 는 낡은 산출과 비교하는 것을 막는다. 2026-09-26 에 12개 파일이 전부 같았고, 한 칸을 바꾸면 `DIFFERS` 로 잡는 것까지 확인했다. `run-3/remedy-searches.jsonl` 은 원래 빈 파일이다.

`HandoffFixtureTest` 는 칸(키 집합)만 보고 값은 안 본다. 값까지 대는 것은 이 절차뿐이다.

★**공통 절차 A 의 판정(`xml_verdict.py`)은 B 보다 먼저 센다.** B 의 `--tests` 걸러 돌리기는 `picasso` 의 XML 을 그 한 클래스로 새로 쓰므로, 뒤에 세면 `picasso` 의 시험이 빠진 수가 나온다(2026-09-26 실측: 1,295 → 1,032).

### C. 옮긴 줄 대조

```bash
git add -N <새 파일들>
PYTHONIOENCODING=utf-8 python "$S/moved_lines.py"
```

지운 줄 가운데 새 자리에서 못 찾은 줄을 찍고(앞뒤 공백 무시, 개수까지 센다), 이어서 `--- 새로 생긴 줄` 아래에 더해지기만 한 줄을 찍는다. 작업마다 못 찾은 줄의 **기대 출력**을 적었다. 목록 밖의 줄이 나오면 본문을 바꾼 것이니 되돌린다. 새로 생긴 줄은 두 갈래뿐이어야 한다. ① 그 작업이 적은 새 코드(파일 머리, 필드, 파사드, 새 함수, 위임으로 바뀐 호출) ② 못 찾은 줄의 고친 짝(가시성이 바뀐 서명, `private val` 을 뗀 생성자 인자, 고친 KDoc 줄). 옮긴 본문 안에 무언가 더해졌으면 그 밖의 줄로 섞여 나온다. 개수를 적어 둔 작업은 그 수와도 대 본다(검토자가 계획서의 코드 그대로 모사해 얻은 값이다). `git add -N` 에는 새 파일의 경로를 하나하나 적는다 — 안 적으면 옮긴 줄이 전부 못 찾은 줄로 나온다. 사람 눈으로는 `git diff --color-moved=zebra --color-moved-ws=allow-indentation-change`.

### D. 게이트 검사 7 (출하 소스의 기종 좌표)

```bash
./gradlew :gate:installDist -q
./gate/build/install/gate/bin/gate --repo . --profiles profile/fixtures --profiles profile/profiles --require REPO,PROFILE_DOCUMENT,PROFILE_SCHEMA
```

기대: 출력에 `PASS  7` 이 있다. 새 파일도 `picasso/src/main` 이라 검사 대상이다. `SKIP  1`·`2`·`6`·`8` 은 buf·기준선·diff 가 없어서이고 CI 가 돌린다.

### E. 안 쓰이게 된 import

```bash
PYTHONIOENCODING=utf-8 python "$S/unused_imports.py" picasso/src/main/kotlin/dev/picasso/middleware/*.kt
```

작업마다 기대 출력을 적었다. 찍힌 import 는 지운다. Kotlin 컴파일러는 이것을 안 알려 준다. 2026-09-26 기준 이 패키지에는 한 줄도 없다.

### F. 일지·대장·도장

- 다음 번호: `grep -oE '^[0-9]+\. \*\*' docs/superpowers/specs/2026-09-05-picasso-design.md | grep -oE '^[0-9]+' | sort -n | tail -1` 에 1 을 더한다. 작성 시점의 마지막은 196 이다. 다른 작업이 먼저 들어왔으면 이 계획서의 번호(197~200)를 전부 그만큼 민다.
- 새 항목은 일지의 최신 블록 맨 위(작성 시점에는 `196.` 바로 위)에 같은 모양으로 넣는다. 첫 줄 `N. **요지.**`, 다음부터 네 칸 들여쓴 문단. 최신이 위다.
- `docs/limits.md` 의 `(번호가 196 까지 갔고)` 를 새 번호로 고치고 `python tools/stamp.py docs/limits.md`.
- `tools/stamp.py` 는 경로를 **하나만** 받는다. 문서가 여럿이면 문서마다 따로 부른다.
- 설계 문서의 도장은 일지(`## 15.` 아래)를 해시에 안 넣으므로 다시 찍지 않는다.
- 대는 시험은 `설계 일지의 마지막 번호를 한계 대장이 맞게 적는다` 다.

### G. 커밋·PR 형식

훅(`.claude/hooks/check-commit-pr-format.py`)이 막는 것:

- 제목 `type(scope): 명사구`, 서술형 종결 금지. 본문은 `- ` 명사형 불릿. em-dash·en-dash·겹화살괄호·낫표·굵게(`**`) 금지.
- 끝줄 `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`. 메시지는 `git commit -F - <<'EOF'` 로 준다.
- PR 본문은 `gh pr create ... --body-file - <<'EOF'` 로만 준다. 절은 `## 개요` · `## 주요 변경 사항` · `## 검증 결과` 순서, 서술형 종결 금지(합니다체나 명사형), 끝줄 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- `git add -A` 를 쓰지 않는다. 루트에 미추적 작업 문서가 있다. 파일을 이름으로 더한다.

---

## Chunk 1: 기준선과 준비 (PR 1)

### 작업 0: 지금의 사실을 받아 둔다 (커밋 없음)

- [ ] **1단계:** `git status --short | grep -v '^??'` → 빈 출력. `git log --oneline -1` 을 적어 둔다.
- [ ] **2단계:** 부록의 스크립트 넷을 `$S` 에 저장한다.
- [ ] **3단계:** 공통 절차 A. `tests` 값을 «기준 시험 수»로, `skipped` 값을 함께 적는다.
- [ ] **4단계:** 공통 절차 B. 하나라도 `DIFFERS`·`MISSING` 이면 **멈추고** 사람에게 알린다. 인계본이 이미 지금 코드와 어긋나 있으면 이 판정 기준을 못 쓴다.
- [ ] **5단계:** 공통 절차 D → `PASS  7`. 공통 절차 E → 출력 없음.
- [ ] **6단계:** `wc -l picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt` 를 적는다(작성 시점 2149).
- [ ] **7단계:** `git switch -c refactor/middleware-seam-prep`

### 작업 1: 게이트가 점 기호의 주인과 멤버를 같은 파일에서 찾게 한다

**Files:**
- Modify: `gate/src/test/kotlin/dev/picasso/gate/DocumentClaimsTest.kt` (`mainSource` 선언, 시험 `자원 소유 대장이 대는 관문이 코드에 실재한다`)

지금 검사는 `Middleware.admits` 를 `Middleware` 와 `admits` 로 쪼개, 각각이 출하 소스 **어딘가에** 선언돼 있는지만 본다. 작업 5 뒤에는 `admits` 가 `AdmissionGate.kt` 에도 있으므로, 파사드를 지워도 이 검사는 초록이다.

- [ ] **1단계: 구멍을 먼저 보인다.** `docs/orchestration.md` §2 표에서 `` `Middleware.submit` `` 한 곳(표에 한 번 나온다)을 `` `Middleware.dispatch` `` 로 잠시 바꾼다. `Middleware.kt` 에는 `dispatch` 선언이 없다. 선언은 `Ports.kt` 의 `AmrFleetPort` 와 Orbit 어댑터에 있다. 그래서 조각마다 «어딘가에 있다» 만 보는 지금의 검사는 이것을 통과시킨다.

- [ ] **2단계:** 시험을 돌린다. 도장 시험(`도장이 있는 문서는 해시가 본문과 같다`)은 `CompletionCriterionTest` 에 있으므로 함께 돌린다 — 주입한 문서를 게이트가 실제로 읽었다는 증거가 그것이다.

```bash
./gradlew :gate:test --tests '*DocumentClaimsTest*' --tests '*CompletionCriterionTest*'
```

XML(`gate/build/test-results/test/TEST-dev.picasso.gate.DocumentClaimsTest.xml`, `…CompletionCriterionTest.xml`)에서 본다:
  - `도장이 있는 문서는 해시가 본문과 같다()` 는 **실패**이고 메시지에 `docs/orchestration.md` 가 있다(문서를 읽었다).
  - `자원 소유 대장이 대는 관문이 코드에 실재한다()` 는 **통과**다. 이것이 구멍이다.

- [ ] **3단계: 검사를 조인다.** 기존 `private val mainSource by lazy { … }` 선언의 KDoc 은 그대로 두고, 그 KDoc **위에** 파일별 한 벌을 둔다. 규칙을 두 벌로 두지 않으려고 `mainSource` 는 그것을 잇게 한다(§15.115). `Path` 와 `repo`·`WORKTREES` 는 이 파일에 이미 있다.

```kotlin
    /**
     * **모든 모듈의** 출하 소스를 파일마다. 점 기호의 **소속**을 댈 때 쓴다 — 주인과 멤버가 같은 파일에
     * 선언돼 있는가. 훑는 범위의 규칙은 여기 한 벌이고 [mainSource] 는 이것을 잇는다.
     */
    private val mainFiles: Map<Path, String> by lazy {
        Files.walk(repo).use { paths ->
            paths.filter {
                val at = it.toString().replace('\\', '/')
                Files.isRegularFile(it) && at.endsWith(".kt") && "/src/main/" in at &&
                    "/build/" !in at && WORKTREES !in at
            }
                .toList()
                .associateWith { Files.readString(it) }
        }
    }
```

기존 `private val mainSource by lazy { … }` **선언 전체**(여는 줄부터 `by lazy` 의 닫는 괄호까지, KDoc 은 빼고)를 이 한 줄로 바꾼다.

```kotlin
    private val mainSource by lazy { mainFiles.values.joinToString("\n") }
```

시험 안에서는 `val source = mainSource` 부터 `assertEquals(emptyList(), broken, "자원 소유 대장이 대는 관문이 출하 소스에 없다")` 까지를 아래로 바꾼다. 바로 위의 `// 관문 칸의 백틱 기호.` 주석 줄은 그대로 둔다.

```kotlin
        // ★**점이 있으면 소속까지 본다.** 조각마다 «어딘가에 있다» 만 보면 멤버가 다른 클래스로 옮겨 가도
        //   초록이다 — 표가 틀린 주인을 댄 채로. 같은 파일에 주인과 멤버가 함께 선언돼야 통과한다.
        //   못 보는 자리: 한 파일에 클래스가 둘이면 멤버가 그중 어느 쪽 것인지까지는 안 본다.
        fun declares(text: String, name: String) =
            Regex("(fun|interface|class|object|val) " + name + "[^A-Za-z0-9_]").containsMatchIn(text)
        val broken = rows.flatMap { hit ->
            gateSymbol.findAll(hit.groupValues[3]).map { it.groupValues[1] }.filterNot { it in notSymbols }
        }.filterNot { symbol ->
            val parts = symbol.split(".")
            if (parts.size == 1) {
                declares(mainSource, parts[0])
            } else {
                mainFiles.values.any { declares(it, parts[0]) && declares(it, parts[1]) }
            }
        }
        assertEquals(emptyList(), broken, "자원 소유 대장이 대는 관문이 출하 소스에 없거나 다른 주인에 있다")
```

`gateSymbol` 은 점을 하나까지만 받으므로 `parts` 는 하나 아니면 둘이다.

- [ ] **4단계:** 2단계의 명령을 다시 돌린다. XML 에서 두 시험이 모두 **실패**이고, `자원 소유 대장이 대는 관문이 코드에 실재한다()` 의 메시지에 `Middleware.dispatch` 가 있어야 한다. 없으면 ①시험이 돌았나(XML 의 `timestamp`) ②주입이 들어갔나(`git diff docs/orchestration.md`) ③등가 변이인가 ④검사에 빈 구간이 있나 순서로 본다(CLAUDE.md §2.2).

- [ ] **5단계:** `git checkout -- docs/orchestration.md` 로 되돌린다. `git diff --stat docs/` → 빈 출력.

- [ ] **6단계:** 2단계의 명령 → 두 XML 모두 실패 0. `mainSource` 는 `용어집이 승인 표면에 대는 이름과 수가 코드와 같다` 도 먹으므로 `DocumentClaimsTest` 전체가 실패 0 인 것을 본다.

### 작업 2: 정준 투영 셋을 `Canonical.kt` 로

**Files:**
- Create: `picasso/src/main/kotlin/dev/picasso/middleware/Canonical.kt`
- Modify: `picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt`

분류(`canonicalClassOf`)는 결과 통보(`notify`)·사건 번들(`sealIncidents`)·이벤트 자취(`record(event, live)`)가 다 쓰고, 잔여 파지(`residualHoldOf`)는 통보와 번들이 쓴다. 결함 투영(`faultDetailOf`)은 번들만 쓰지만 분류를 `canonicalClassOf` 에서 받으므로 곁에 둔다. 사건 번들을 떼면 한쪽이 다른 클래스를 건너가 읽게 되므로, 먼저 둘의 바깥으로 뺀다. 셋 다 상태가 없다.

- [ ] **1단계:** 새 파일을 CRLF 로 만든다.

```kotlin
package dev.picasso.middleware

import dev.picasso.contracts.v1.FailureClass
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.middleware.Middleware.Companion.UNCLASSIFIED

// 결과 통보·사건 번들·이벤트 자취가 나눠 쓰는 정준 투영. 분류(canonicalClassOf)는 세 곳이 다 쓰고, 잔여 파지
// (residualHoldOf)는 통보와 번들이 쓴다. 결함 투영(faultDetailOf)은 번들만 쓰지만 분류를 canonicalClassOf 에서
// 받으므로 곁에 둔다. 한 클래스에 두면 다른 쪽이 그 클래스를 건너가 읽고, 두 벌로 두면 어느 날 한쪽만 는다(§15.115).
// 셋 다 상태가 없다.
```

- [ ] **2단계:** `Middleware.kt` 에서 아래 셋을 KDoc 째 잘라 새 파일 끝에 이 순서로 붙인다. 바꾸는 것은 들여쓰기(네 칸 줄임)와 `private fun` → `internal fun` 뿐이다.
  - `private fun faultDetailOf(fault: Fault): FaultDetail = FaultDetail(`
  - `private fun canonicalClassOf(fault: Fault): String =`
  - `private fun residualHoldOf(units: List<ExecutionUnit>): HoldState =`

- [ ] **3단계:** `Middleware.kt` 의 `import dev.picasso.contracts.v1.FailureClass` 를 지운다. 남은 언급은 `companion object` 의 KDoc 뿐이다.

- [ ] **4단계:** `./gradlew :picasso:compileKotlin` → `BUILD SUCCESSFUL`.

- [ ] **5단계:** 공통 절차 C (`git add -N picasso/src/main/kotlin/dev/picasso/middleware/Canonical.kt`). 기대 출력:

```text
1x private fun canonicalClassOf(fault: Fault): String =
1x private fun faultDetailOf(fault: Fault): FaultDetail = FaultDetail(
1x private fun residualHoldOf(units: List<ExecutionUnit>): HoldState =
removed 29 added 38 unmatched 3
--- 새로 생긴 줄 12
1x // (residualHoldOf)는 통보와 번들이 쓴다. 결함 투영(faultDetailOf)은 번들만 쓰지만 분류를 canonicalClassOf 에서
1x // 결과 통보·사건 번들·이벤트 자취가 나눠 쓰는 정준 투영. 분류(canonicalClassOf)는 세 곳이 다 쓰고, 잔여 파지
1x // 받으므로 곁에 둔다. 한 클래스에 두면 다른 쪽이 그 클래스를 건너가 읽고, 두 벌로 두면 어느 날 한쪽만 는다(§15.115).
1x // 셋 다 상태가 없다.
1x import dev.picasso.contracts.v1.Fault
1x import dev.picasso.contracts.v1.HoldKind
1x import dev.picasso.contracts.v1.HoldState
1x import dev.picasso.middleware.Middleware.Companion.UNCLASSIFIED
1x internal fun canonicalClassOf(fault: Fault): String =
1x internal fun faultDetailOf(fault: Fault): FaultDetail = FaultDetail(
1x internal fun residualHoldOf(units: List<ExecutionUnit>): HoldState =
1x package dev.picasso.middleware
```

`FailureClass` import 는 지운 쪽과 더한 쪽에 모두 있어 어느 목록에도 안 나온다.

- [ ] **6단계:** 공통 절차 E → 출력 없음.

### 작업 3: 구동 보조 함수를 구동 절로 옮긴다 (같은 파일 안)

**Files:**
- Modify: `picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt`

`// ── 사건 번들` 절 안에 사건과 상관없는 구동 보조 다섯이 섞여 있다. 먼저 제자리로 보내야 작업 9 가 절 하나를 통째로 뗀다. 고른 기준: `markIncident` 는 구동과 근거 결합이 부르므로 구동 보조다. `within` 은 `sealIncidents` 만 부르므로 사건 절에 남기고 작업 9 에서 함께 옮긴다. `residualHoldOf` 는 작업 2 에서 이미 `Canonical.kt` 로 갔다.

- [ ] **1단계:** 아래 다섯을 KDoc 째 잘라 `    // ── 근거 결합 (보고서 12장)` 줄 **바로 위**(구동 절의 끝)에 이 순서로 붙인다. 본문과 들여쓰기는 그대로다.
  - `private fun Execution.markIncident(unit: ExecutionUnit) {`
  - `private fun judgeEffectMismatch(execution: Execution, unit: ExecutionUnit, completed: Boolean) {`
  - `private fun ExecutionUnit.observeHold(observed: HoldState) {`
  - `private fun Execution.observeCell(unit: ExecutionUnit): SlotSignal? {`
  - `private fun settleExecution(execution: Execution) {`

- [ ] **2단계:** 사건 번들 절에 남은 것이 `incidents`·`incident`·`reviewIncident`·(주인에게서 떨어진 KDoc 하나)·`reviewMetricsByApprover`·`reviewMetrics`·`sealIncidents`·`within` 뿐인지 본다.

- [ ] **3단계:** `./gradlew :picasso:compileKotlin` → 성공. 공통 절차 C → 작업 2 의 세 줄만 나오고 `unmatched 3`, 새로 생긴 줄도 작업 2 의 열두 줄 그대로(`--- 새로 생긴 줄 12`)여야 한다. 이 작업이 더한 것은 없다. `removed`·`added` 개수는 git 이 같은 파일 안의 이동을 맞추는 방식에 따라 달라지므로 대지 않는다.

- [ ] **4단계:** 공통 절차 A·B·D.

### 작업 4: 일지·대장·커밋·PR 1

- [ ] **1단계:** 공통 절차 F 로 일지 197 을 넣는다. 아래는 초안이다. 실측과 다르면 실측대로 고친다.

```text
197. **`Middleware.kt` 를 접합부로 나누기 전에 세 가지를 먼저 했다 — 게이트가 점 기호의 주인과 멤버를 같은 파일에서 찾게 했고, 여러 절이 나눠 쓰는 투영을 둘의 바깥으로 뺐고, 사건 절에 섞인 구동 보조를 구동 절로 옮겼다.**

    2026-09-26 에 절마다 줄이 언제 들어왔는지를 쟀다. 핵심 절(구동·근거 결합·취소·결과 통보, 857줄)은 9/17 이후 50줄만 바뀌었고, 새 접합부 절(접수 관문·제안과 승인·사건 번들, 1,036줄)은 604줄이 그 뒤에 쓰였다. 그래서 크기가 아니라 바뀌는 속도로 나눈다. 계획은 `docs/superpowers/plans/2026-09-26-middleware-seam-split.md` 에 있다.

    ★**게이트의 구멍이 먼저였다.** `orchestration.md` §2 가 `Middleware.admits` 를 대는데, 검사는 `Middleware` 와 `admits` 가 각각 어딘가에 있는지만 봤다. 관문을 떼고 나면 `admits` 가 새 클래스에도 있으므로 파사드를 지워도 초록이었을 것이다. 표의 `Middleware.submit` 을 `Middleware.dispatch` 로 바꿔(`dispatch` 는 `AmrFleetPort` 와 Orbit 어댑터에만 선언돼 있다) 초록인 것을 먼저 보였고, 주인과 멤버가 같은 파일에 선언돼야 통과하게 조인 뒤 같은 주입이 빨개지는 것을 봤다. 못 보는 자리는 검사의 주석에 적었다 — 한 파일에 선언이 여럿이면(`Ports.kt` 의 포트들) 멤버가 그중 어느 것의 것인지는 안 본다.

    정준 투영 셋(`canonicalClassOf`·`faultDetailOf`·`residualHoldOf`)을 `Canonical.kt` 로 뺐다. 분류는 통보·사건 번들·이벤트 자취가 다 쓰고 잔여 파지는 통보와 번들이 쓰며, 결함 투영은 번들만 쓰지만 분류를 `canonicalClassOf` 에서 받는다. 새 파일의 머리 주석은 처음에 셋을 세 곳이 다 쓰는 것처럼 적었다가 호출 자리를 세어 고쳤다.

    ★**절 머리를 절 끝 함수 위에 끼우면 그 함수가 조용히 새 절로 넘어간다.** 구동 보조 다섯 가운데 `settleExecution` 은 9/9 에 구동 절 끝에 쓰였는데, 그 위에 `// ── 근거 결합`(9/10)과 `// ── 사건 번들`(9/16) 머리가 차례로 들어오면서 두 번 딸려 갔다. 나머지 넷은 9/16 에 사건 절 안에 쓰였다. 다섯을 구동 절 끝으로 옮겼고, 이제 사건 절에는 사건의 것만 남아 사건 장부를 뗄 때 절을 통째로 옮길 수 있다.

    본문은 그대로다. 지운 줄 가운데 새 자리에서 못 찾은 것은 가시성이 바뀐 서명 셋뿐이고, 인계본 네 벌은 실시계와 구동 식별자를 가리면 바이트까지 같다.
```

- [ ] **2단계:** `docs/limits.md` 의 번호를 197 로 고치고 `python tools/stamp.py docs/limits.md`.

- [ ] **3단계:** 공통 절차 A → `failed 0`, `tests` = 기준 시험 수.

- [ ] **4단계: 커밋.**

```bash
git add gate/src/test/kotlin/dev/picasso/gate/DocumentClaimsTest.kt \
  picasso/src/main/kotlin/dev/picasso/middleware/Canonical.kt \
  picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt \
  docs/superpowers/specs/2026-09-05-picasso-design.md docs/limits.md \
  docs/superpowers/plans/2026-09-26-middleware-seam-split.md
git commit -F - <<'EOF'
refactor(middleware): 접합부 분리 준비로 관문 기호의 소속 대조와 정준 투영 분리

- DocumentClaimsTest: 자원 소유 대장의 점 기호를 같은 파일의 주인과 멤버로 대조. mainSource 는 파일별 한 벌에서 파생
- Canonical.kt 신설: canonicalClassOf, faultDetailOf, residualHoldOf. 통보, 사건 번들, 이벤트 자취가 나눠 쓰는 상태 없는 투영
- Middleware: 사건 절에 섞인 구동 보조 다섯을 구동 절로 이동. 본문 불변
- 계획서 수록: docs/superpowers/plans/2026-09-26-middleware-seam-split.md
- 설계 일지 15.197 수록

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

- [ ] **5단계: PR.** push 는 사용자의 허락을 받은 뒤에 한다.

```bash
git push -u origin refactor/middleware-seam-prep
gh pr create --title "refactor(middleware): 접합부 분리 준비로 관문 기호의 소속 대조와 정준 투영 분리" --body-file - <<'EOF'
## 개요
`Middleware.kt` 를 접합부 기준으로 나누기 위한 준비입니다. 게이트가 문서의 점 기호마다 주인과 멤버가 같은 파일에 선언됐는지 대조하도록 조이고, 여러 절이 나눠 쓰는 정준 투영 셋을 분리했습니다. 거동 변경은 없습니다.

## 주요 변경 사항
- DocumentClaimsTest: 자원 소유 대장의 점 기호(`Owner.member`)를 같은 파일의 주인과 멤버로 대조
- Canonical.kt 신설: `canonicalClassOf`, `faultDetailOf`, `residualHoldOf`
- Middleware: 사건 절에 섞인 구동 보조 다섯을 구동 절로 이동
- 계획서 수록, 설계 일지 15.197

## 검증 결과
- 로컬 표준 빌드 통과, XML 기준 실패 0
- 인계본 네 벌이 실시계와 구동 식별자를 가리면 바이트 동일(12개 파일)
- 새 자리에서 못 찾은 옮긴 줄은 가시성이 바뀐 서명 셋뿐
- 주입: 주인이 다른 점 기호를 표에 넣으면 조이기 전에는 통과, 조인 뒤에는 실패
- 게이트 검사 7 통과
- 이동 확인용: `git diff --color-moved=zebra --color-moved-ws=allow-indentation-change`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
```

---

## Chunk 2: 배정 관문 (PR 2)

시작: PR 1 이 머지된 뒤 `git switch main && git pull && git switch -c refactor/middleware-admission-gate`

### 작업 5: `AdmissionGate` 를 뗀다

**Files:**
- Create: `picasso/src/test/kotlin/dev/picasso/middleware/SeamPlacementTest.kt`
- Create: `picasso/src/main/kotlin/dev/picasso/middleware/AdmissionGate.kt`
- Modify: `picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt`
- Modify: `CLAUDE.md`, `docs/verification.md` (11단계, 주 세션)

- [ ] **1단계: 실패하는 시험을 먼저 쓴다.** 새 파일을 CRLF 로 만든다.

```kotlin
package dev.picasso.middleware

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **떼어 낸 선언이 제 파일에만 있다.** 자주 바뀌는 접합부의 판정과 장부는 `Middleware.kt` 에서 떼어 각자의
 * 파일로 옮긴다(`docs/superpowers/plans/2026-09-26-middleware-seam-split.md`). `Middleware.kt` 에는 핵심
 * 상태기계와 공개 창구, 그리고 접합부를 핵심에 잇는 조율이 남는다 — 그 조율도 빨리 바뀌는 코드다.
 * 여러 절이 나눠 쓰는 정준 투영은 `Canonical.kt` 에 한 벌만 둔다(§15.115) — 같은 이름의 멤버를 클래스 안에
 * 다시 두면 그 클래스의 호출은 조용히 그 멤버를 부른다.
 *
 * 다시 한 파일로 모아도 다른 시험은 전부 초록이다. 그래서 갈라 둔 것이 조용히 사라진다(§15.193 이 내보내기
 * 세 벌에서 본 모양). 여기서는 출하 소스를 훑어 **선언이 어느 파일에 있는지**를 댄다. 이 파일은 `src/test`
 * 에 있어 제 바늘에 안 걸린다.
 */
class SeamPlacementTest {

    @Test
    fun `떼어 낸 선언이 제 파일에만 있다`() {
        val homes = mapOf(
            // 정준 투영 — 나눠 쓰는 쪽들의 바깥에 한 벌
            "fun canonicalClassOf(" to "Canonical.kt",
            "fun faultDetailOf(" to "Canonical.kt",
            "fun residualHoldOf(" to "Canonical.kt",
            // 배정 관문 — 피어 시스템 접합부
            "fun inconsistent(" to "AdmissionGate.kt",
            "fun chainRefusal(" to "AdmissionGate.kt",
            "fun occupancyViolation(" to "AdmissionGate.kt",
            "fun unownedFloor(" to "AdmissionGate.kt",
            "fun workspaceViolation(" to "AdmissionGate.kt",
        )
        val sources = Files.walk(Path.of("src", "main")).use { paths ->
            paths.asSequence()
                .filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                .map { it.fileName.toString() to Files.readString(it) }
                .toList()
        }
        assertTrue(sources.size > 10, "이 모듈의 출하 소스를 못 훑었다: ${sources.size}")
        homes.forEach { (needle, home) ->
            val found = sources.filter { needle in it.second }.map { it.first }.toSortedSet()
            assertEquals(sortedSetOf(home), found, "'$needle' 이 제 파일($home) 밖에 있거나 사라졌다")
        }
    }
}
```

- [ ] **2단계:** `./gradlew :picasso:test --tests '*SeamPlacementTest'` → XML 에서 `떼어 낸 선언이 제 파일에만 있다()` 가 실패이고, 메시지가 `expected: <[AdmissionGate.kt]> but was: <[Middleware.kt]>` 꼴이다. 정준 투영 셋은 PR 1 에서 이미 제자리라 여기서는 안 걸린다. `src/main` 을 훑는 것은 이미 선언된 입력이다(`picasso/build.gradle.kts` 의 `project.file("src")`).

- [ ] **3단계:** `AdmissionGate.kt` 를 CRLF 로 만든다. 클래스 몸통은 4단계가 채운다.

```kotlin
package dev.picasso.middleware

import dev.picasso.capability.HoldEffects
import dev.picasso.capability.PreconditionCheck
import dev.picasso.capability.RemedySearch
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.middleware.Middleware.Execution
import dev.picasso.middleware.Middleware.Submission

/**
 * 배정 관문(설계안 §7) — 피어 시스템 접합부(배정·공간)의 판정이 모이는 자리.
 *
 * **상태를 소유하지 않는다.** [executions] 는 [Middleware] 가 든 표를 같은 인스턴스로 받아 읽기만 한다 —
 * 사본을 받으면 관문은 생성 때 뜬 빈 표만 보고, 자리 경쟁·작업 구역·사슬 검사는 아무것도 막지 않는다.
 * 기체의 선언은 [robots], 자리의 관측은 [cell], 바닥과 구역의 소유는 [floors]·[workspace] 가 답한다.
 */
internal class AdmissionGate(
    private val robots: RobotPort,
    private val cell: CellSignals,
    private val floors: FloorOwnership,
    private val workspace: Workspace,
    private val executions: Map<String, Execution>,
) {
}
```

- [ ] **4단계:** `Middleware.kt` 의 접수 절에서 아래 열을 KDoc 째 잘라 클래스 몸통에 이 순서로 붙인다. 들여쓰기는 같다(둘 다 클래스 멤버). 바꾸는 것은 둘뿐이다: `private fun chainRefusal` → `fun chainRefusal`(`revise` 가 부른다), `private fun liveHold` → `fun liveHold`(`isHolding` 과 `judge` 가 부른다).
  1. `fun admits(order: JobOrder, robotId: String, planned: List<ExecutionUnit>): Admission {`
  2. `private fun workspaceViolation(robotId: String, planned: List<ExecutionUnit>): Submission.Rejected? {`
  3. `private fun zoneOf(unit: ExecutionUnit): String? = unit.destination?.let { workspace.zoneOf(it) }`
  4. `private fun liveZones(excluding: String): Map<String, ZoneUse> = executions.values`
  5. `private fun unownedFloor(planned: List<ExecutionUnit>): Submission.Rejected? {`
  6. `private fun chainRefusal(robotId: String, planned: List<ExecutionUnit>): Submission.Rejected? {`
  7. `private fun occupancyViolation(planned: List<ExecutionUnit>): Admission.Refused? {`
  8. `private fun liveClaims(): Map<String, SlotClaim> = executions.values`
  9. `private fun liveHold(robotId: String): HoldState? {`
  10. `private fun inconsistent(order: JobOrder, planned: List<ExecutionUnit>): String? {`

  접수 절에 남는 것: `submit` 둘, `record`, `adopt`, `assign`, `liveExecutionCount`, `isHolding`, `reassign`, `revise`.

  옮긴 `admits` 의 KDoc 에 있는 `[record]` 는 작업 7 까지 풀리지 않는 링크로 남는다. 작업 7 의 9단계가 고친다.

- [ ] **5단계:** `Middleware` 에 필드와 파사드를 둔다. `private val views = mutableMapOf<String, RobotView>()` 바로 아래에:

```kotlin
    /** 배정 관문(설계안 §7). 실행 표를 같은 인스턴스로 넘긴다 — 관문은 이 층이 지금 든 것을 읽는다. */
    private val gate = AdmissionGate(robots, cell, floors, workspace, executions)
```

`admits` 가 있던 자리(비공개 `submit` 바로 아래)에:

```kotlin
    /**
     * **배정 관문**(설계안 §7)의 공개 창구. 판정은 [AdmissionGate.admits] 가 한다.
     *
     * 이름을 여기 두는 이유: `orchestration.md` §2 와 ADR 42 가 `Middleware.admits` 로 대고,
     * 게이트는 §2 표의 점 기호마다 주인과 멤버가 한 파일에 선언돼 있는지 본다.
     */
    fun admits(order: JobOrder, robotId: String, planned: List<ExecutionUnit>): Admission =
        gate.admits(order, robotId, planned)
```

- [ ] **6단계:** 호출 셋을 관문으로 돌리고, 죽는 KDoc 링크 하나를 고친다.
  - `isHolding` 의 KDoc: `/** 지금 든 채인가. [liveHold] 의 답을 비용이 읽는 모양으로 줄인 것이다. */` 에서 `[liveHold]` → `[AdmissionGate.liveHold]`
  - `isHolding`: `liveHold(robotId)?.kind == HoldKind.HOLD_KIND_HOLDING` → `gate.liveHold(robotId)?.kind == HoldKind.HOLD_KIND_HOLDING`
  - `revise`: `chainRefusal(execution.robotId, toPlan)?.let {` → `gate.chainRefusal(execution.robotId, toPlan)?.let {` (줄의 나머지는 그대로)
  - `judge`: `RemedyValues.resolve(steps, declared, entitlement, liveHold(robotId))` → `RemedyValues.resolve(steps, declared, entitlement, gate.liveHold(robotId))`

- [ ] **7단계:** 생성자에서 `floors`·`workspace` 의 `private val` 을 뗀다. 이름·타입·기본값·KDoc 은 그대로다. 이제 `Middleware` 는 관문을 만들 때만 그 둘을 쓴다.
  - `private val floors: FloorOwnership = FloorOwnership.None,` → `floors: FloorOwnership = FloorOwnership.None,`
  - `private val workspace: Workspace = Workspace.None,` → `workspace: Workspace = Workspace.None,`

- [ ] **8단계:** `./gradlew :picasso:compileKotlin` → 성공. 공통 절차 E 의 기대 출력(파일 속 import 순서대로 찍힌다. 둘 다 지우고 E 를 다시 돌려 출력이 없는 것을 본다. `HoldEffects` 는 `judgeEffectMismatch` 가 써서 남는다):

```text
picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt: import dev.picasso.capability.RemedySearch
picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt: import dev.picasso.capability.PreconditionCheck
```

- [ ] **9단계:** 공통 절차 C. `git add -N picasso/src/main/kotlin/dev/picasso/middleware/AdmissionGate.kt picasso/src/test/kotlin/dev/picasso/middleware/SeamPlacementTest.kt` 후 돌린다. 못 찾은 줄의 기대 출력:

```text
1x /** 지금 든 채인가. [liveHold] 의 답을 비용이 읽는 모양으로 줄인 것이다. */
1x chainRefusal(execution.robotId, toPlan)?.let { return record(execution.robotId, order, it) }
1x liveHold(robotId)?.kind == HoldKind.HOLD_KIND_HOLDING
1x parameters = when (val filled = RemedyValues.resolve(steps, declared, entitlement, liveHold(robotId))) {
1x private fun chainRefusal(robotId: String, planned: List<ExecutionUnit>): Submission.Rejected? {
1x private fun liveHold(robotId: String): HoldState? {
1x private val floors: FloorOwnership = FloorOwnership.None,
1x private val workspace: Workspace = Workspace.None,
removed … added … unmatched 8
```

새로 생긴 줄은 `--- 새로 생긴 줄 39` 다. `AdmissionGate.kt` 의 머리와 클래스 선언(시험 파일은 `src/test` 라 대조 범위 밖이다), `gate` 필드, `admits` 파사드, 그리고 위 여덟 줄의 고친 짝(`fun chainRefusal(`, `fun liveHold(`, `floors:`·`workspace:` 인자, `[AdmissionGate.liveHold]` KDoc, `gate.` 호출 셋)이다.

- [ ] **10단계:** `./gradlew :picasso:test --tests '*SeamPlacementTest'` → XML 에서 통과. 이어서 주입 둘로 두 검사가 실제로 무는지 본다(CLAUDE.md §2.1). 먼저 `Middleware.kt` 를 스크래치에 복사해 두고, 주입마다 그 사본으로 되돌린 뒤 바이트가 같은지 본다.
  - **투영의 두 벌:** `gate` 필드 아래에 `    private fun residualHoldOf(units: List<ExecutionUnit>): HoldState = HoldState.getDefaultInstance()` 한 줄을 더한다(멤버가 최상위 함수를 가리는 모양이고 컴파일된다). 같은 명령 → XML 에서 실패이고 메시지에 `fun residualHoldOf(` 와 `Middleware.kt` 가 있다. 되돌리고 다시 돌려 통과를 본다.
  - **파사드 삭제:** `admits` 파사드를 KDoc 째 지운다. `./gradlew :gate:test --tests '*DocumentClaimsTest*'` → XML 에서 `자원 소유 대장이 대는 관문이 코드에 실재한다()` 가 실패이고 메시지에 `Middleware.admits` 가 있다. 게이트는 `picasso` 를 컴파일하지 않으므로 파사드가 없어도 돈다. 작업 1 이 조인 검사가 잡으려던 것이 바로 이 경우다. 되돌리고 다시 돌려 통과를 본다.

- [ ] **11단계: 시험 수를 먼저 맞춘다.** 시험이 하나 늘었으므로, 이것을 안 하면 12단계의 공통 절차 A 가 `자동화 시험의 수를 대외 문서가 맞게 적는다` 에서 멈춘다(빌드가 첫 실패에서 서서 `picasso` XML 이 안 나올 수 있다). 이 단계는 실행을 맡은 **주 세션이** 한다(`CLAUDE.md` 를 고친다).
  - `./gradlew :gate:test --tests '*DocumentClaimsTest*'` → 그 시험이 실패하고 메시지에 실측값이 있다(작성 시점 기준 1,822).
  - 그 값으로 `CLAUDE.md` 의 `총 1,821개 테스트` 와 `docs/verification.md` 의 `1,821개 자동화 테스트` 를 고친다.
  - `python tools/stamp.py CLAUDE.md` 와 `python tools/stamp.py docs/verification.md` 를 **따로** 부른다(경로를 하나만 받는다).
  - 같은 명령에 `--tests '*CompletionCriterionTest*'` 를 더해 다시 돌린다 → 두 XML 모두 실패 0.

- [ ] **12단계:** 공통 절차 A·B·D. 관문을 대는 시험 클래스(`AssignmentTest`·`OccupancyTest`·`WorkspaceTest`·`FloorOwnershipTest`·`ReassignmentTest`·`SequencingRackTest`·`ScenarioChainTest`)가 XML 에 있고 실패가 0 인지, `DocumentClaimsTest` 의 `자원 소유 대장이 대는 관문이 코드에 실재한다` 가 통과인지 본다(파사드가 `Middleware.admits` 를 지킨다).

### 작업 6: 일지·대장·커밋·PR 2

- [ ] **1단계:** 공통 절차 F 로 일지 198 을 넣는다. 초안:

```text
198. **배정 관문을 `AdmissionGate` 로 뗐다 — 피어 시스템 설계가 넓힐 관문의 판정이 이제 핵심 파일 밖에 있다.**

    `admits` 와 그 아래 술어 다섯(`inconsistent`·`chainRefusal`·`occupancyViolation`·`unownedFloor`·`workspaceViolation`), 보조 넷(`liveZones`·`zoneOf`·`liveClaims`·`liveHold`)을 옮겼다. 관문은 상태를 소유하지 않는다. 실행 표를 `Middleware` 가 든 **같은 인스턴스**로 받아 읽기만 한다. 사본을 받으면 관문은 생성 때 뜬 빈 표만 보고, 자리 경쟁·작업 구역·사슬 검사는 아무것도 막지 않는다. 재할당(`reassign`)의 판정과 채택(`adopt`·`assign`)의 순서는 조율이라 `Middleware` 에 남았다. 피어 시스템 설계가 들어오면 그쪽도 는다.

    `Middleware.admits` 는 파사드로 남았다. `orchestration.md` §2 와 ADR 42 가 그 이름으로 대고, §15.197 에서 조인 게이트가 §2 표의 그 이름을 주인과 같은 파일에서 찾는다. 이름과 본문은 그대로다. 새 자리에서 못 찾은 줄은 가시성이 바뀐 서명 둘, 관문으로 돌린 호출 셋, 생성자에서 `private val` 을 뗀 둘, 죽는 KDoc 링크 하나뿐이고, 인계본 네 벌은 실시계와 구동 식별자를 가리면 바이트까지 같다. 옮긴 `admits` 의 KDoc 에 있는 `[record]` 는 `record` 가 제안 장부(`RemedyDesk`)로 옮겨 갈 때까지 풀리지 않는다.

    ★**떼어 낸 것이 다시 모이는 것을 막는 시험을 먼저 세웠다.** 다시 한 파일로 합쳐도 다른 시험은 전부 초록이라 갈라 둔 것이 조용히 사라진다(§15.193 이 내보내기 세 벌에서 본 모양). `SeamPlacementTest` 가 출하 소스를 훑어 관문 다섯의 선언이 `AdmissionGate.kt` 에만, 정준 투영 셋이 `Canonical.kt` 에만 있는지를 댄다. 옮기기 전에 이 시험이 빨개지는 것을 보고 옮겼고, 같은 이름의 투영을 `Middleware` 에 다시 두는 주입과 `admits` 파사드를 지우는 주입으로 두 검사가 무는 것을 봤다. 못 보는 자리: 이 대조는 `picasso` 의 출하 소스만 훑고 `fun 이름(` 을 철자 그대로 찾는다. 제네릭·확장 함수·함수형 `val` 로 다시 적은 같은 이름은 못 보고, 보조 넷은 바늘에 없다.
```

- [ ] **2단계:** `docs/limits.md` 번호 198, `python tools/stamp.py docs/limits.md`.

- [ ] **3단계:** 공통 절차 A → `failed 0`, `tests` = 기준 시험 수 + 1.

- [ ] **4단계: 커밋.**

```bash
git add picasso/src/test/kotlin/dev/picasso/middleware/SeamPlacementTest.kt \
  picasso/src/main/kotlin/dev/picasso/middleware/AdmissionGate.kt \
  picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt \
  CLAUDE.md docs/verification.md \
  docs/superpowers/specs/2026-09-05-picasso-design.md docs/limits.md \
  docs/superpowers/plans/2026-09-26-middleware-seam-split.md
git commit -F - <<'EOF'
refactor(middleware): 배정 관문의 AdmissionGate 분리

- AdmissionGate 신설: admits 와 술어 다섯(inconsistent, chainRefusal, occupancyViolation, unownedFloor, workspaceViolation), 보조 넷. 실행 표는 Middleware 의 인스턴스를 읽기만 함
- Middleware: admits 는 파사드로 유지. isHolding, revise, judge 가 관문의 liveHold, chainRefusal 을 호출. floors, workspace 는 생성자 인자로만 유지
- SeamPlacementTest 신설: 관문 다섯은 AdmissionGate.kt 에만, 정준 투영 셋은 Canonical.kt 에만 선언됐는지 출하 소스로 대조
- 시험 총수 1,822 로 갱신, 설계 일지 15.198 수록
- 계획서: 시험과 관문 머리 주석의 문구 정정, 일지 초안 동기화

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

- [ ] **5단계: PR.** push 는 허락을 받은 뒤에 한다.

```bash
git push -u origin refactor/middleware-admission-gate
gh pr create --title "refactor(middleware): 배정 관문의 AdmissionGate 분리" --body-file - <<'EOF'
## 개요
`Middleware.kt` 의 접수 절에서 배정 관문을 `AdmissionGate` 로 분리했습니다. 피어 시스템 설계가 넓힐 판정이 이제 핵심 파일 밖에 있습니다. 거동 변경은 없습니다.

## 주요 변경 사항
- AdmissionGate: `admits` 와 술어 다섯, 보조 넷. 실행 표는 `Middleware` 가 든 인스턴스를 그대로 읽기만 함
- Middleware: `admits` 파사드 유지, 호출 셋을 관문으로 위임, `floors`·`workspace` 는 생성자 인자로만 유지
- SeamPlacementTest: 관문 다섯은 `AdmissionGate.kt` 에만, 정준 투영 셋은 `Canonical.kt` 에만 선언됐는지 대조
- 시험 총수 1,822, 설계 일지 15.198
- 계획서: 시험과 관문 머리 주석의 문구 정정, 일지 초안 동기화

## 검증 결과
- 로컬 표준 빌드 통과, XML 기준 실패 0
- 인계본 네 벌이 실시계와 구동 식별자를 가리면 바이트 동일(12개 파일)
- 새 자리에서 못 찾은 옮긴 줄 여덟, 전부 계획서의 예상 목록과 일치
- SeamPlacementTest 는 옮기기 전 실패, 옮긴 뒤 통과
- 주입: `admits` 파사드를 지우면 게이트의 자원 소유 대장 대조가 실패, 정준 투영과 같은 이름의 멤버를 `Middleware` 에 두면 SeamPlacementTest 실패
- 게이트 검사 7 통과
- 이동 확인용: `git diff --color-moved=zebra --color-moved-ws=allow-indentation-change`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
```

---

## Chunk 3: 제안과 승인 장부 (PR 3)

시작: PR 2 가 머지된 뒤 `git switch main && git pull && git switch -c refactor/middleware-remedy-desk`

### 작업 7: `RemedyDesk` 를 뗀다

**Files:**
- Modify: `picasso/src/test/kotlin/dev/picasso/middleware/SeamPlacementTest.kt`
- Create: `picasso/src/main/kotlin/dev/picasso/middleware/RemedyDesk.kt`
- Modify: `picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt`, `AdmissionGate.kt`(KDoc 한 줄)

장부만 옮기고 조율은 남긴다. `approveRemedy`·`attemptApproval`·`commit` 은 `Middleware` 에 남아, 장부에 판정을 묻고(`desk.judge`) 접수를 내고(`submit`) 접수가 된 뒤에만 승인을 장부에 적는다(`desk.settle`). 장부가 접수를 부르게 하면 떼어 낸 클래스가 핵심을 되부르는 순환이 생긴다. 주의: 승인 뒤의 접수가 관문에 걸려도 `submit` 은 지금처럼 그 거절을 `desk.record` 에 넘긴다(계산된 답이 있으면 장부에 적힌다). «접수가 된 뒤에만» 인 것은 `settle` 하나다.

- [ ] **1단계: 실패하는 시험.** `SeamPlacementTest` 의 `homes` 에서 배정 관문 묶음 끝에 하나, 그 아래에 셋을 더한다. 관문의 `liveHold` 는 장부가 함수형 `val` 로 받아 쓰므로, 같은 이름의 `fun` 이 장부에 끼면 호출이 조용히 그쪽으로 간다 (함수가 `invoke` 를 가진 속성보다 먼저 풀린다).

```kotlin
            // 장부가 함수로 받아 쓰는 관문의 조회 — 같은 이름의 fun 이 끼면 그쪽이 이긴다
            "fun liveHold(" to "AdmissionGate.kt",
            // 제안과 승인 장부 — 운영자 접합부
            "var proposalsMade" to "RemedyDesk.kt",
            "val remedyLog" to "RemedyDesk.kt",
            "fun scopeRefusal(" to "RemedyDesk.kt",
```

- [ ] **2단계:** `./gradlew :picasso:test --tests '*SeamPlacementTest'` → XML 에서 실패, 메시지에 `var proposalsMade` 와 `but was: <[Middleware.kt]>`.

- [ ] **3단계:** `RemedyDesk.kt` 를 CRLF 로 만든다. `settle` 의 몸통은 7단계가 `commit` 에서 잘라 온 여섯 줄로 채운다. 지금은 안내 줄 하나만 둔다.

```kotlin
package dev.picasso.middleware

import dev.picasso.capability.Remedy
import dev.picasso.capability.RemedyStep
import dev.picasso.contracts.v1.HoldState
import dev.picasso.middleware.Middleware.Submission
import java.time.Instant

/**
 * 제안과 승인의 장부(설계안 §6.4, ADR 43·44·45) — 운영자·설명층 접합부.
 *
 * **장부와 판정만 든다.** 승인이 서면 실제로 내리는 것(접수)은 [Middleware] 가 하고, 승인의 기록([settle])은
 * 접수가 된 뒤에만 한다. 관문이 거절하면 [settle] 을 안 부르므로 서 있던 제안을 지우지 않는다. 관문의 거절은
 * 접수가 [record] 에 넘기고, 조치 열·못 찾은 사유·출발 결품 판정이 실려 있으면 여기 적힌다.
 */
internal class RemedyDesk(
    private val robots: RobotPort,
    private val entitlements: Entitlements,
    private val withholdEvery: Int,
    private val now: () -> Instant,
    private val wallClock: () -> Instant,
    /** 기체에서 지금 도는 단위들의 파지 관측([AdmissionGate.liveHold]). 자동 승인의 값을 채울 때 다시 본다. */
    private val liveHold: (String) -> HoldState?,
) {

    /**
     * 승인된 주문이 **접수된 뒤에만** 부른다 — 서 있던 제안을 내리고 같은 조치의 승인 횟수를 센다.
     */
    fun settle(go: Judgment.Go) {
        // ← 7단계: Middleware.commit 의 여섯 줄을 여기로 잘라 붙이고 이 안내 줄을 지운다
    }
}
```

- [ ] **4단계:** 아래를 KDoc 째 잘라 클래스 몸통(`settle` 위)에 이 순서로 붙인다. 들여쓰기는 같다. 바꾸는 것은 표시한 넷뿐이다.
  1. 제안 절 머리의 상태 묶음: `private val proposals = mutableMapOf<String, Proposal>()` 부터 `private var remedySeq = 0` 까지(`Proposal`·`withheld`·`diagnoses`·`proposalsMade`·`approvals`·`remedyLog` 포함)
  2. 접수 절의 `private fun record(` → **`fun record(`** (`submit`·`revise` 가 부른다). 구동 절의 `private fun record(event: Event, live: List<Execution>)` 는 다른 함수이고 **남는다.**
  3. `private fun note(robotId: String, jobOrderId: String, outcome: RemedyOutcome) {` — KDoc 의 `관문([admits])` 을 **`관문([AdmissionGate.admits])`** 로
  4. `fun remedySearches(): List<RemedySearchRecord> = remedyLog.toList()`
  5. `private fun proposalKey(robotId: String, jobOrderId: String) = "$robotId|$jobOrderId"`
  6. `fun proposal(robotId: String, jobOrderId: String): Remedy.Found? {`
  7. `fun withheldProposal(robotId: String, jobOrderId: String): Boolean = proposalKey(robotId, jobOrderId) in withheld`
  8. `fun diagnose(robotId: String, jobOrderId: String, cause: String): Boolean {`
  9. `fun diagnosis(robotId: String, jobOrderId: String): String? = diagnoses[proposalKey(robotId, jobOrderId)]`
  10. `fun repeatedRemedies(atLeast: Int = 2): List<RepeatedRemedy> = approvals`
  11. `private sealed interface Judgment {` → **`sealed interface Judgment {`** (`Middleware` 가 `Go`·`No` 를 받는다)
  12. `private fun judge(` → **`fun judge(`**, 그리고 몸통의 `gate.liveHold(robotId)` → **`liveHold(robotId)`**(생성자로 받은 함수)
  13. `private fun scopeRefusal(declared: Entitlement, robotId: String, steps: List<RemedyStep>): Judgment.No? {`

- [ ] **5단계:** `Middleware` 에 필드·import·파사드를 둔다.
  - 파일 머리의 import 에 `import dev.picasso.middleware.RemedyDesk.Judgment` 를 더한다. `approveRemedy`·`attemptApproval`·`commit` 의 `Judgment.No`·`Judgment.Go` 가 그대로 풀린다.
  - `gate` 필드 바로 아래:

```kotlin
    /** 제안과 승인의 장부(설계안 §6.4). 승인 뒤의 접수는 [commit] 에 남고, 승인의 기록([RemedyDesk.settle])은 접수가 된 뒤에만 한다. */
    private val desk = RemedyDesk(robots, entitlements, withholdEvery, now, wallClock, gate::liveHold)
```

  - 제안 절(옮긴 조회들이 있던 자리)에 파사드 여섯:

```kotlin
    /** 탐색이 답한 것들, 답한 순서대로 — [RemedyDesk.remedySearches]. */
    fun remedySearches(): List<RemedySearchRecord> = desk.remedySearches()

    /** 이 (기체, 주문)에 서 있는 제안. 없거나 가려져 있으면 널 — [RemedyDesk.proposal]. */
    fun proposal(robotId: String, jobOrderId: String): Remedy.Found? = desk.proposal(robotId, jobOrderId)

    /** 가려 둔 제안이 있는가 — [RemedyDesk.withheldProposal]. */
    fun withheldProposal(robotId: String, jobOrderId: String): Boolean = desk.withheldProposal(robotId, jobOrderId)

    /** 사람이 먼저 진단한다 — [RemedyDesk.diagnose]. */
    fun diagnose(robotId: String, jobOrderId: String, cause: String): Boolean = desk.diagnose(robotId, jobOrderId, cause)

    /** 사람이 먼저 적은 진단 — [RemedyDesk.diagnosis]. */
    fun diagnosis(robotId: String, jobOrderId: String): String? = desk.diagnosis(robotId, jobOrderId)

    /** 같은 조치가 거듭 승인된 것 — [RemedyDesk.repeatedRemedies]. */
    fun repeatedRemedies(atLeast: Int = 2): List<RepeatedRemedy> = desk.repeatedRemedies(atLeast)
```

- [ ] **6단계:** 호출 넷을 장부로 돌린다.
  - 비공개 `submit`: `is Admission.Refused -> return record(robotId, order, admission.rejection, admission.sourceMissing)` → `is Admission.Refused -> return desk.record(robotId, order, admission.rejection, admission.sourceMissing)`
  - `revise`: `gate.chainRefusal(execution.robotId, toPlan)?.let { return record(execution.robotId, order, it) }` → `gate.chainRefusal(execution.robotId, toPlan)?.let { return desk.record(execution.robotId, order, it) }`
  - `approveRemedy`: `return when (val judgment = judge(robotId, jobOrderId, approver, given = parameters, saw = null)) {` → `return when (val judgment = desk.judge(robotId, jobOrderId, approver, given = parameters, saw = null)) {`
  - `attemptApproval`: `val judgment = judge(` → `val judgment = desk.judge(`

- [ ] **7단계:** `commit` 에서 `proposals.remove(go.key)` 부터 `approvals[signature] = (approvals[signature] ?: 0) + 1` 까지 **여섯 줄**(`proposals.remove`, `// **승인자 종류로` 로 시작하는 주석 세 줄, `val signature`, `approvals[…]`)을 잘라, `settle` 의 안내 줄 자리에 붙이고 안내 줄을 지운다. 새로 쓰지 않는다. `commit` 의 그 자리에는 `desk.settle(go)` 한 줄을 둔다. `commit` 은 이렇게 된다:

```kotlin
    private fun commit(go: Judgment.Go, approver: Approver): Submission {
        val submission = submit(go.order, go.robotId, go.prefix, approvedBy = approver)
        if (submission !is Submission.Accepted) return submission
        desk.settle(go)
        return submission
    }
```

- [ ] **8단계:** 생성자에서 `withholdEvery`·`entitlements` 의 `private val` 을 뗀다(이름·타입·기본값·KDoc 그대로).
  - `private val withholdEvery: Int = 0,` → `withholdEvery: Int = 0,`
  - `private val entitlements: Entitlements = Entitlements.None,` → `entitlements: Entitlements = Entitlements.None,`

- [ ] **9단계:** `AdmissionGate.admits` 의 KDoc 한 줄에서 `기록은 [record] 가 맡고` → `기록은 [RemedyDesk.record] 가 맡고`.

- [ ] **10단계:** `./gradlew :picasso:compileKotlin` → 성공. 공통 절차 E 의 기대 출력(지우고 E 를 다시 돌려 출력이 없는 것을 본다):

```text
picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt: import dev.picasso.capability.RemedyStep
```

안내 줄이 남지 않았는지 본다: `grep -n "←" picasso/src/main/kotlin/dev/picasso/middleware/RemedyDesk.kt` → 출력 없음. 주석이라 컴파일러도 공통 절차 C 도 못 잡는다.

- [ ] **11단계:** 공통 절차 C. `git add -N picasso/src/main/kotlin/dev/picasso/middleware/RemedyDesk.kt` 후 돌린다. 못 찾은 줄의 기대 출력(파이썬 정렬 순. 첫 줄은 4단계 3번 항목의 `note` KDoc, 둘째 줄은 9단계의 `admits` KDoc 고침이다):

```text
1x * **묻기만 한 것은 안 적는다.** 부르는 자리가 [record] 뿐인 것이 그 규율이다 — 관문([admits])은 순수
1x * 기록은 [record] 가 맡고, 채택을 시도한 쪽만 그것을 부른다.
1x gate.chainRefusal(execution.robotId, toPlan)?.let { return record(execution.robotId, order, it) }
1x is Admission.Refused -> return record(robotId, order, admission.rejection, admission.sourceMissing)
1x parameters = when (val filled = RemedyValues.resolve(steps, declared, entitlement, gate.liveHold(robotId))) {
1x private fun judge(
1x private fun record(
1x private sealed interface Judgment {
1x private val entitlements: Entitlements = Entitlements.None,
1x private val withholdEvery: Int = 0,
1x return when (val judgment = judge(robotId, jobOrderId, approver, given = parameters, saw = null)) {
1x val judgment = judge(
removed … added … unmatched 12
```

새로 생긴 줄은 `--- 새로 생긴 줄 55` 다. `RemedyDesk.kt` 의 머리·KDoc·생성자와 `settle` 서명·닫는 괄호, `desk` 필드와 `Judgment` import, 파사드 여섯, `desk.` 호출과 `desk.settle(go)`, 그리고 위 열두 줄의 고친 짝이다. 옮긴 본문의 줄(잘라 붙인 `settle` 의 여섯 줄 포함)은 여기 안 나와야 한다.

- [ ] **12단계:** `./gradlew :picasso:test --tests '*SeamPlacementTest'` → XML 에서 통과. 그 뒤 공통 절차 A·B·D. 장부를 대는 시험 클래스(`RemedyApprovalTest`·`RemedyLedgerTest`·`RemedyValuesTest`·`WithholdingTest`·`EntitlementTest`·`ApprovalHostTest`·`ApprovalWireTest`·`FileEntitlementsTest`, 그리고 탐색 대장을 내보내는 `LedgerExportTest`·`ExportFixtureTest`·`HandoffFixtureTest`)가 XML 에서 실패 0 인지 본다.

### 작업 8: 일지·대장·커밋·PR 3

- [ ] **1단계:** 공통 절차 F 로 일지 199. 초안:

```text
199. **제안과 승인의 장부를 `RemedyDesk` 로 뗐다 — 장부만 옮기고 조율은 남겼다.**

    상태 일곱(`proposals`·`withheld`·`diagnoses`·`proposalsMade`·`approvals`·`remedyLog`·`remedySeq`)과, 접수의 거절 경로에서 제안을 적던 `record` 를 함께 옮겼다. 그 일곱을 제안 절 밖에서 쓰는 자리가 `record` 하나뿐이었다.

    ★**승인 뒤의 접수는 `Middleware` 에 남겼다.** `approveRemedy`·`attemptApproval` 은 장부에 판정(`judge`)을 묻고, 서면 접수를 내고, 접수가 된 뒤에만 `settle` 로 장부에 적는다. 장부가 접수를 부르게 하면 떼어 낸 클래스가 핵심을 되부르는 순환이 생긴다. 콜백을 넘기는 대신 순서를 바깥에 둔 이유다. 관문이 거절하면 `settle` 을 안 부르므로 서 있던 제안을 지우지 않는 성질(ADR 44)도 그 순서에서 나온다. `settle` 이 기대는 것은 **주문의** 접수다 — 개정 경로는 조치 열 없이도 받아 주므로(§15.175, v1 도달 불가) 머리 주석을 «조치 열이 접수된 뒤» 에서 «주문이 접수된 뒤» 로 고쳤다.

    ★**장부는 관문의 `liveHold` 를 함수형 `val` 로 받는다 — 같은 이름의 `fun` 이 장부에 끼면 호출이 조용히 그쪽으로 간다.** 함수가 `invoke` 를 가진 속성보다 먼저 풀리기 때문이다. 늘 널을 돌려주는 `fun liveHold` 를 장부에 끼워 넣는 주입에서 `EntitlementTest` 는 자동 승인 갈래의 열 건이 빨개졌지만 `RemedyApprovalTest` 는 여섯 건 전부 초록이었다 — 사람의 승인은 값을 들고 오므로 관측을 다시 보지 않는다. 어느 갈래를 시험하든 잡도록 `SeamPlacementTest` 에 `fun liveHold(` 의 자리(`AdmissionGate.kt`)를 더했고, 같은 주입에서 그것이 빨개지는 것을 봤다.

    본문은 그대로다. 새 자리에서 못 찾은 줄은 계획서가 적어 둔 열둘이고, 인계본 네 벌은 실시계와 구동 식별자를 가리면 바이트까지 같다.
```

- [ ] **2단계:** `docs/limits.md` 번호 199, `python tools/stamp.py docs/limits.md`.

- [ ] **3단계:** 공통 절차 A → `failed 0`, `tests` = 기준 시험 수 + 1.

- [ ] **4단계: 커밋.**

```bash
git add picasso/src/test/kotlin/dev/picasso/middleware/SeamPlacementTest.kt \
  picasso/src/main/kotlin/dev/picasso/middleware/RemedyDesk.kt \
  picasso/src/main/kotlin/dev/picasso/middleware/AdmissionGate.kt \
  picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt \
  docs/superpowers/specs/2026-09-05-picasso-design.md docs/limits.md \
  docs/superpowers/plans/2026-09-26-middleware-seam-split.md
git commit -F - <<'EOF'
refactor(middleware): 제안과 승인 장부의 RemedyDesk 분리

- RemedyDesk 신설: 상태 일곱과 record, note, 조회, judge, scopeRefusal. 접수가 된 뒤의 기록은 settle 로 분리
- Middleware: approveRemedy, attemptApproval, commit 의 조율은 유지. 판정은 장부에 위임, 공개 창구 여섯은 파사드
- withholdEvery, entitlements 는 생성자 인자로만 유지
- SeamPlacementTest: 장부의 상태와 scopeRefusal, 관문의 liveHold 대조 추가
- 설계 일지 15.199 수록
- 계획서: 관문 조회 바늘 추가와 장부 머리 주석의 문구 정정, 일지 초안 동기화

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

- [ ] **5단계: PR.** push 는 허락을 받은 뒤에 한다.

```bash
git push -u origin refactor/middleware-remedy-desk
gh pr create --title "refactor(middleware): 제안과 승인 장부의 RemedyDesk 분리" --body-file - <<'EOF'
## 개요
제안·가림·진단·승인 장부를 `RemedyDesk` 로 분리했습니다. 승인 뒤의 접수는 `Middleware` 에 남아 장부에 판정을 묻고, 접수가 된 뒤에만 승인을 장부에 기록합니다(`settle`). 관문 거절은 지금처럼 `record` 로 넘어갑니다. 거동 변경은 없습니다.

## 주요 변경 사항
- RemedyDesk: 상태 일곱, `record`, `note`, 조회, `judge`, `scopeRefusal`, 접수 뒤 기록용 `settle`
- Middleware: `approveRemedy`·`attemptApproval`·`commit` 조율 유지, 공개 창구 여섯은 파사드
- `withholdEvery`·`entitlements` 는 생성자 인자로만 유지
- SeamPlacementTest: 장부의 상태와 `scopeRefusal`, 관문의 `liveHold` 대조 추가
- 설계 일지 15.199
- 계획서: 관문 조회 바늘 추가와 장부 머리 주석의 문구 정정, 일지 초안 동기화

## 검증 결과
- 로컬 표준 빌드 통과, XML 기준 실패 0
- 인계본 네 벌이 실시계와 구동 식별자를 가리면 바이트 동일(12개 파일)
- 새 자리에서 못 찾은 옮긴 줄 열둘, 전부 계획서의 예상 목록과 일치
- SeamPlacementTest 는 옮기기 전 실패, 옮긴 뒤 통과
- 게이트 검사 7 통과

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
```

---

## Chunk 4: 사건 번들 장부와 마무리 (PR 4)

시작: PR 3 이 머지된 뒤 `git switch main && git pull && git switch -c refactor/middleware-incident-log`

### 작업 9: `IncidentLog` 를 뗀다

**Files:**
- Modify: `picasso/src/test/kotlin/dev/picasso/middleware/SeamPlacementTest.kt`
- Create: `picasso/src/main/kotlin/dev/picasso/middleware/IncidentLog.kt`
- Modify: `picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt`

봉인은 여전히 `pump` 가 라운드 끝에 부른다. 순서는 핵심에 남는다.

- [ ] **1단계: 실패하는 시험.** `homes` 에 둘을 더한다.

```kotlin
            // 사건 번들 장부 — 운영자 접합부
            "var incidentSeq" to "IncidentLog.kt",
            "fun sealIncidents(" to "IncidentLog.kt",
```

- [ ] **2단계:** `./gradlew :picasso:test --tests '*SeamPlacementTest'` → XML 에서 실패, 메시지에 `var incidentSeq`.

- [ ] **3단계:** `IncidentLog.kt` 를 CRLF 로 만든다.

```kotlin
package dev.picasso.middleware

import dev.picasso.contracts.wire.ContractIdentity
import dev.picasso.middleware.Middleware.Execution
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * 사건 번들의 장부(설계안 §4) — 흩어진 사실을 한 사건으로 묶고, 사람의 검토와 판단을 그 위에 싣는다.
 *
 * **이 층의 거동을 바꾸지 않는다.** 봉인은 [Middleware] 가 라운드 끝에 부르고([sealIncidents]), 여기서는
 * 실행을 읽기만 한다. 실행에 쓰는 것은 단 하나, 봉인한 단위의 표시(`pendingIncidents`)를 비우는 일이다.
 */
internal class IncidentLog(
    private val robots: RobotPort,
    private val now: () -> Instant,
    private val wallClock: () -> Instant,
) {

    /**
     * 운영자의 판단을 **아직 판단이 안 실린 가장 최근의** 사건에 붙인다. 부르는 쪽은 [Middleware.resolve] 다.
     */
    fun noteResolution(executionId: String, unitId: String, decision: OperatorDecision) {
        // ← Middleware.resolve 의 `val opened = incidentLog.indexOfLast {` 부터 `if (opened >= 0) { … }` 끝까지를 여기로 옮긴다
    }
}
```

- [ ] **4단계:** 아래를 KDoc 째 잘라 클래스 몸통(`noteResolution` 위)에 이 순서로 붙인다. 들여쓰기는 같다. 바꾸는 것은 표시한 하나뿐이다.
  1. 머리 절의 `/** 열린 사건들(설계안 §4). …` KDoc 과 `private val incidentLog = mutableListOf<IncidentBundle>()`, `private var incidentSeq = 0`
  2. `fun incidents(): List<IncidentBundle> = incidentLog.toList()`
  3. `fun incident(incidentId: String): IncidentBundle? = incidentLog.firstOrNull { it.incidentId == incidentId }`
  4. `fun reviewIncident(incidentId: String, verdict: ReviewVerdict, cause: String): Boolean {`
  5. `fun reviewMetricsByApprover(since: Instant? = null): Map<ApproverKind, ReviewMetrics> = incidentLog`
  6. `fun reviewMetrics(since: Instant? = null): ReviewMetrics {` — 주인에게서 떨어져 `reviewMetricsByApprover` 의 KDoc 위에 붙어 있던 KDoc(`@param since` 를 든 것)을 이 함수 바로 위로 옮긴다. 줄은 그대로라 대조에 안 걸린다.
  7. `private fun sealIncidents(execution: Execution) {` → **`fun sealIncidents(execution: Execution) {`**
  8. `private fun within(event: ObservedEvent, from: Instant, to: Instant): Boolean {`

- [ ] **5단계:** `resolve` 에서 `val opened = incidentLog.indexOfLast {` 부터 `if (opened >= 0) { … }` 의 닫는 괄호까지 일곱 줄을 잘라 `noteResolution` 몸통으로 옮기고(안내 줄은 지운다), 그 자리에 한 줄을 둔다. 그 위의 주석 세 줄(`// ★**사람의 걸음을 사건에 남긴다.**` 로 시작)은 `resolve` 에 남긴다.

```kotlin
        incidentLog.noteResolution(executionId, unitId, decision)
```

- [ ] **6단계:** `Middleware` 의 필드와 파사드. `desk` 필드 바로 아래에 둔다 — 떼어 낸 셋(관문·장부 둘)이 한 자리에 선언된다. 옮긴 목록이 있던 자리(`private var responseSeq = 0` 아래)는 빈 줄 하나만 남긴다:

```kotlin
    /** 사건 번들의 장부(설계안 §4). 봉인은 [pump] 가 라운드 끝에 부른다. */
    private val incidentLog = IncidentLog(robots, now, wallClock)
```

사건 번들 절(옮긴 조회들이 있던 자리)에 파사드 다섯:

```kotlin
    /** 열린 순서대로 — [IncidentLog.incidents]. */
    fun incidents(): List<IncidentBundle> = incidentLog.incidents()

    fun incident(incidentId: String): IncidentBundle? = incidentLog.incident(incidentId)

    /** 사람이 사건을 읽고 판정을 남긴다 — [IncidentLog.reviewIncident]. */
    fun reviewIncident(incidentId: String, verdict: ReviewVerdict, cause: String): Boolean =
        incidentLog.reviewIncident(incidentId, verdict, cause)

    /** 승인자 종류별 검토 지표 — [IncidentLog.reviewMetricsByApprover]. */
    fun reviewMetricsByApprover(since: Instant? = null): Map<ApproverKind, ReviewMetrics> =
        incidentLog.reviewMetricsByApprover(since)

    /** 검토가 실제로 일어나는가 — [IncidentLog.reviewMetrics]. */
    fun reviewMetrics(since: Instant? = null): ReviewMetrics = incidentLog.reviewMetrics(since)
```

`pump(execution: Execution)` 의 `sealIncidents(execution)` → `incidentLog.sealIncidents(execution)`.

죽는 KDoc 링크 하나를 고친다. `markIncident`(작업 3 에서 구동 절로 옮긴 것)의 KDoc `/** 이 단위가 이번 라운드에 닫혔다. 봉하는 것은 [sealIncidents] 다. */` 에서 `[sealIncidents]` → `[IncidentLog.sealIncidents]`.

- [ ] **7단계:** 생성자에서 `wallClock` 의 `private val` 을 뗀다. 이제 `Middleware` 는 장부 둘을 만들 때만 쓴다. `private val wallClock: () -> Instant = { Instant.now() },` → `wallClock: () -> Instant = { Instant.now() },`

- [ ] **8단계:** `./gradlew :picasso:compileKotlin` → 성공. 공통 절차 E 의 기대 출력(지우고 E 를 다시 돌려 출력이 없는 것을 본다):

```text
picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt: import dev.picasso.contracts.wire.ContractIdentity
```

안내 줄이 남지 않았는지 본다: `grep -n "←" picasso/src/main/kotlin/dev/picasso/middleware/IncidentLog.kt` → 출력 없음.

- [ ] **9단계:** 공통 절차 C. `git add -N picasso/src/main/kotlin/dev/picasso/middleware/IncidentLog.kt` 후 돌린다. 못 찾은 줄의 기대 출력(첫 줄은 6단계의 KDoc 링크 고침):

```text
1x /** 이 단위가 이번 라운드에 닫혔다. 봉하는 것은 [sealIncidents] 다. */
1x private fun sealIncidents(execution: Execution) {
1x private val wallClock: () -> Instant = { Instant.now() },
1x sealIncidents(execution)
removed … added … unmatched 4
```

새로 생긴 줄은 `--- 새로 생긴 줄 39` 다. `IncidentLog.kt` 의 머리·KDoc·생성자와 `noteResolution` 서명, `incidentLog` 필드, 파사드 다섯(두 줄로 된 것은 본문 줄까지), `pump`·`resolve` 의 `incidentLog.` 호출, 그리고 위 네 줄의 고친 짝이다(`incidentLog.sealIncidents(execution)` 은 호출이자 짝이다). 개수가 맞는지가 먼저다.

- [ ] **10단계:** `./gradlew :picasso:test --tests '*SeamPlacementTest'` → XML 에서 통과.

- [ ] **11단계: 재발 주입(CLAUDE.md §2.1).** `Middleware.kt` 의 `incidentLog` 필드 아래에 `    private var incidentSeq = 0` 한 줄을 잠시 더한다. 같은 명령 → XML 에서 `떼어 낸 선언이 제 파일에만 있다()` 가 실패이고 메시지에 `var incidentSeq` 와 `Middleware.kt` 가 있다. 줄을 지우고 다시 돌려 통과를 본다. `grep -n incidentSeq picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt` → 출력 없음. (`git diff` 로는 못 본다 — 옮긴 선언이 지운 줄과 더한 줄로 늘 나온다.)

- [ ] **12단계:** 공통 절차 A·B·D. 사건을 대는 시험 클래스(`IncidentBundleTest`·`IncidentReviewTest`·`LedgerExportTest`·`ExportFixtureTest`·`HandoffFixtureTest`·`GroundTruthTest`·`EffectMismatchTest`)가 XML 에서 실패 0 인지 본다. `wc -l picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt` 를 적는다(이하 N).

### 작업 10: 모듈 README 의 컴포넌트 표

**Files:**
- Modify: `picasso/README.md` (§1 핵심 컴포넌트 구성)

- [ ] **1단계:** `Middleware.kt` 행의 설명은 지금 `결정론적 전이` 로 마침표 없이 끝난다. 그 뒤에 `. 공개 API 와 접합부를 잇는 조율은 여기 남고, 배정 관문의 판정과 제안·사건 두 장부는 아래 셋에 위임합니다` 를 붙인다(`Canonical.kt` 는 공개 API 가 없으므로 «셋»이다. «판정과 장부» 로만 적으면 넓다 — 재할당의 판정은 조율과 함께 남는다). 그 행 바로 아래에 넷을 더한다.

```markdown
| `AdmissionGate.kt` | 배정 관문(설계안 §7): `admits` 와 그 아래 술어 다섯. 피어 시스템 접합부(배정·공간)의 판정 |
| `RemedyDesk.kt` | 탐색 기록·제안·가림·진단·승인 장부와 승인 판정(설계안 §6.4, ADR 43·44·45). 승인 뒤의 접수는 `Middleware` 가 조율 |
| `IncidentLog.kt` | 사건 번들의 봉인·조회, 사후 검토의 기록과 지표, 운영자 판단 부착(설계안 §4) |
| `Canonical.kt` | 결과 통보·사건 번들·이벤트 자취가 나눠 쓰는 정준 투영 셋 |
```

- [ ] **2단계:** `python tools/stamp.py picasso/README.md`. `./gradlew :gate:test --tests '*DocumentClaimsTest*' --tests '*CompletionCriterionTest*'` → 통과(도장과 열림 표기 총합).

### 작업 11: 일지·대장·커밋·PR 4

- [ ] **1단계:** 공통 절차 F 로 일지 200. 초안(`N` 은 작업 9 의 `wc -l`):

```text
200. **사건 번들의 장부를 `IncidentLog` 로 뗐다 — 이로써 이 계획이 떼기로 한 판정과 장부는 다 뗐고, 남은 것은 핵심 상태기계와 공개 창구, 그리고 그것을 접합부에 잇는 조율이다.**

    봉인(`sealIncidents`)·조회·검토 지표와, `resolve` 가 사건에 운영자의 판단을 붙이던 줄(`noteResolution`)을 옮겼다. 봉인은 여전히 `pump` 가 라운드 끝에 부른다. 순서는 핵심에 남는다. 주인에게서 떨어져 있던 KDoc 하나를 `reviewMetrics` 위로 돌려놓았다. 떼어 낸 셋(`gate`·`desk`·`incidentLog`)은 `Middleware` 에서 한 자리에 선언된다.

    `SeamPlacementTest` 에 사건 장부를 더한 뒤, `Middleware.kt` 에 `incidentSeq` 를 다시 선언하는 주입으로 그 시험이 이름을 대며 빨개지는 것을 봤다.

    결과: `Middleware.kt` 2,149줄에서 1,512줄. ★**남은 것이 다 조용한 코드는 아니다.** §15.197 에서 잰 대로 구동·근거 결합·취소·통보는 9/17 이후 6% 만 바뀌었지만, 남긴 조율(`adopt`·`assign`·`liveExecutionCount`·`isHolding`·`reassign`·`approveRemedy`·`attemptApproval`·`commit`, KDoc 째 170여 줄)은 거의 전부 그 뒤에 쓰였다. 여섯은 `submit` 을 부르거나 실행의 상태를 바꾸고, 둘(`liveExecutionCount`·`isHolding`)은 `assign` 이 쓰는 비용 항이다(`reassign` 도 앞엣것을 쓴다). 앞의 여섯을 떼면 떼어 낸 클래스가 핵심을 되부른다. 그래서 순서를 파사드에 두었다. 재할당의 판정도 그 조율 안에 남았다. 피어 시스템 설계가 구현되면 다음 성장은 관문(`AdmissionGate`)과 이 조율 양쪽에 온다. 구동 루프는 쪼개지 않았다. 순서가 불변식이고 거의 안 바뀐다.
```

- [ ] **2단계:** `docs/limits.md` 번호 200, `python tools/stamp.py docs/limits.md`.

- [ ] **3단계:** 공통 절차 A → `failed 0`, `tests` = 기준 시험 수 + 1. 공통 절차 B·D.

- [ ] **4단계: 커밋.**

```bash
git add picasso/src/test/kotlin/dev/picasso/middleware/SeamPlacementTest.kt \
  picasso/src/main/kotlin/dev/picasso/middleware/IncidentLog.kt \
  picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt \
  picasso/README.md \
  docs/superpowers/specs/2026-09-05-picasso-design.md docs/limits.md \
  docs/superpowers/plans/2026-09-26-middleware-seam-split.md
git commit -F - <<'EOF'
refactor(middleware): 사건 번들 장부의 IncidentLog 분리

- IncidentLog 신설: 봉인, 조회, 검토 지표. resolve 가 사건에 판단을 붙이던 줄은 noteResolution 으로 이동
- Middleware: 라운드 끝 봉인 호출 유지, 공개 창구 다섯은 파사드. wallClock 은 생성자 인자로만 유지, 떼어 낸 셋의 필드를 한 자리에 선언
- SeamPlacementTest: 사건 장부의 상태와 봉인 대조 추가
- picasso README: 핵심 컴포넌트 표에 떼어 낸 넷 추가
- 설계 일지 15.200 수록
- 계획서: 장부 머리 주석과 README 문장의 문구 정정, 일지 초안 동기화

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

- [ ] **5단계: PR.** push 는 허락을 받은 뒤에 한다.

```bash
git push -u origin refactor/middleware-incident-log
gh pr create --title "refactor(middleware): 사건 번들 장부의 IncidentLog 분리" --body-file - <<'EOF'
## 개요
사건 번들의 봉인·조회·검토 지표를 `IncidentLog` 로 분리했습니다. 이로써 이 계획이 분리하기로 한 판정과 장부는 모두 분리되었고, `Middleware.kt` 에는 핵심 상태기계와 공개 창구, 접합부를 핵심에 잇는 조율(재할당의 판정 포함)이 남습니다. 거동 변경은 없습니다.

## 주요 변경 사항
- IncidentLog: 봉인, 조회, 검토 지표, 운영자 판단 부착(`noteResolution`)
- Middleware: 라운드 끝 봉인 호출 유지, 공개 창구 다섯은 파사드, `wallClock` 은 생성자 인자로만 유지
- SeamPlacementTest: 사건 장부의 상태와 봉인 대조 추가
- picasso README 컴포넌트 표, 설계 일지 15.200
- 계획서: 장부 머리 주석과 README 문장의 문구 정정, 일지 초안 동기화

## 검증 결과
- 로컬 표준 빌드 통과, XML 기준 실패 0
- 인계본 네 벌이 실시계와 구동 식별자를 가리면 바이트 동일(12개 파일)
- 새 자리에서 못 찾은 옮긴 줄 넷, 전부 계획서의 예상 목록과 일치
- 재발 주입: `Middleware.kt` 에 장부 상태를 다시 선언하면 SeamPlacementTest 실패
- 게이트 검사 7 통과
- `Middleware.kt` 2,149줄에서 1,512줄
- 이동 확인용: `git diff --color-moved=zebra --color-moved-ws=allow-indentation-change`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
```

- [ ] **6단계: 브랜치 정리.** 원격 브랜치를 지우는 것도 공개 저장소에 대한 push 이므로 사용자의 허락을 받은 뒤에 한다. 네 PR 이 **모두** 머지된 뒤에만 지운다(쌓아 올린 경우 머지 도중에 지우면 뒤 PR 이 닫힌다).
  - `git push origin --delete refactor/middleware-seam-prep refactor/middleware-admission-gate refactor/middleware-remedy-desk refactor/middleware-incident-log`
  - `git branch -d` 로 같은 넷을 로컬에서도 지운다.
  - `git fetch --prune` 후 `git branch -r` → `origin/main` 하나.

---

## 부록: 스크립트 (세션 스크래치에 저장, 커밋하지 않는다)

### A. `xml_verdict.py`

```python
"""로컬 표준 빌드의 JUnit XML 을 센다 — 종료 코드가 아니라 실패한 시험의 이름으로 판정한다(CLAUDE.md 2-3).

저장소 루트에서 돌린다. 상대 글로브만 쓴다 — 저장소 경로의 `[projects]` 가 글로브에서는 문자 집합이다.
먼저 `./gradlew cleanTest build -x :harness:test -x :registry:test` 로 결과를 새로 쓴다 — 안 지우면 예전 구동의
XML(예: Docker 없이 돈 registry 의 실패 322건)이 같이 세어진다(2026-09-26 실측).
읽는 것은 이 기계의 Gradle 이 방금 쓴 보고서뿐이라 표준 파서로 읽는다.
하나도 못 세면 실패로 끝난다 — 엉뚱한 자리에서 돌리면 0 이 기준 값으로 적힌다.
"""
import glob
import sys
import xml.etree.ElementTree as ET

total, skipped, failed = 0, 0, []
for path in sorted(glob.glob('*/build/test-results/test/*.xml')):
    suite = ET.parse(path).getroot()
    total += int(suite.get('tests', 0))
    skipped += int(suite.get('skipped', 0))
    for case in suite.iter('testcase'):
        if case.find('failure') is not None or case.find('error') is not None:
            failed.append(suite.get('name') + ' > ' + case.get('name'))
print('tests', total, 'skipped', skipped, 'failed', len(failed))
for name in failed:
    print('  FAILED', name)
sys.exit(0 if total > 0 and not failed else 1)
```

### B. `bundle_equiv.sh`

```bash
#!/usr/bin/env bash
# 인계본 네 벌과 지금 코드가 낸 네 벌을 대조한다. 실시계(wallClockAt, writtenAt)와 구동 식별자(runId)만 가린다.
# 저장소 루트에서 돌린다. 먼저: rm -rf picasso/build/export && ./gradlew :picasso:test --rerun --tests '*ExportFixtureTest'
set -u
mask='s/"(wallClockAt|writtenAt|runId)":"[^"]*"/"\1":"~"/g'
n=0
bad=0
for r in 1 2 3 4; do
  for f in incidents.jsonl remedy-searches.jsonl manifest.json; do
    a="handoff/narrator/run-$r/$f"
    b="picasso/build/export/run-$r/$f"
    # 빈 파일은 정상이다(run-3 은 탐색이 없다). 어느 쪽이든 없는 것만 센다.
    if [ ! -f "$a" ] || [ ! -f "$b" ]; then
      echo "run-$r/$f MISSING"
      bad=$((bad + 1))
      continue
    fi
    n=$((n + 1))
    if diff -q <(sed -E "$mask" "$a") <(sed -E "$mask" "$b") >/dev/null; then
      echo "run-$r/$f same"
    else
      echo "run-$r/$f DIFFERS"
      bad=$((bad + 1))
    fi
  done
done
echo "files=$n bad=$bad"
[ "$bad" -eq 0 ]
```

### C. `moved_lines.py`

```python
"""이번 변경에서 지운 줄 가운데 새 자리에서 못 찾은 줄을 찍고, 이어서 새로 생긴 줄을 찍는다(앞뒤 공백은 무시).

옮기기만 했으면 못 찾은 줄은 계획서가 작업마다 적어 둔 몇 줄(가시성이 바뀐 서명, 위임으로 바뀐 호출)뿐이다.
그 밖의 줄이 나오면 본문을 바꾼 것이다. 새로 생긴 줄은 두 갈래여야 한다 — 계획서가 적은 새 코드(파일 머리,
필드, 파사드, 새 함수, 위임으로 바뀐 호출)와 못 찾은 줄의 고친 짝(가시성, 생성자 인자, KDoc). 옮긴 본문 안에
무언가 더해졌으면 그 밖의 줄로 섞여 나온다.
새 파일은 먼저 `git add -N <파일>` 로 알린다. 저장소 루트에서 돌린다.
"""
import collections
import subprocess

diff = subprocess.run(
    ['git', 'diff', '--no-color', '--no-ext-diff', '-U0', 'HEAD', '--',
     'picasso/src/main/kotlin/dev/picasso/middleware/'],
    capture_output=True, check=True,
).stdout.decode('utf-8')

removed, added = collections.Counter(), collections.Counter()
for line in diff.splitlines():
    if line.startswith(('---', '+++')):
        continue
    text = line[1:].strip()
    if not text:
        continue
    if line.startswith('-'):
        removed[text] += 1
    elif line.startswith('+'):
        added[text] += 1

lost = removed - added
for text, count in sorted(lost.items()):
    print(f'{count}x {text}')
print('removed', sum(removed.values()), 'added', sum(added.values()), 'unmatched', sum(lost.values()))

extra = added - removed
print('--- 새로 생긴 줄', sum(extra.values()))
for text, count in sorted(extra.items()):
    print(f'{count}x {text}')
```

### D. `unused_imports.py`

```python
"""파일에서 import 한 이름이 코드에 더 안 나오면 찍는다. Kotlin 컴파일러는 이것을 안 알려 준다.

주석 줄(`*`·`/*`·`//` 로 시작)은 안 센다 — KDoc 에만 이름이 남은 import 도 안 쓰이는 것이다.
"""
import re
import sys

COMMENT = ('*', '/*', '//')

for path in sys.argv[1:]:
    lines = open(path, encoding='utf-8').read().splitlines()
    code = '\n'.join(
        line for line in lines
        if not line.startswith('import ') and not line.strip().startswith(COMMENT)
    )
    for line in lines:
        m = re.match(r'import ([\w.]+)$', line.strip())
        if not m:
            continue
        name = m.group(1).rsplit('.', 1)[-1]
        if not re.search(r'\b' + re.escape(name) + r'\b', code):
            print(f'{path}: {line.strip()}')
```
