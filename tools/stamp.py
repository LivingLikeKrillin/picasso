"""도장을 다시 찍는다 — 본문의 해시를 세어 도장 줄을 갱신하거나 새로 넣는다.

    python tools/stamp.py docs/architecture.md
    python tools/stamp.py docs/limits.md --open "§15.34" C-3
    python tools/stamp.py docs/limits.md --open          # 열림을 없음으로 지운다

규칙은 gate/src/test/kotlin/dev/picasso/gate/ClaimSurface.kt 와 한 벌이다:
도장은 펜스 밖에서만 도장이고, 본문은 펜스 밖 '## 15.' 앞에서 끊기며, LF 로 맞춰 sha256 앞 12자리.
"""
import argparse
import datetime
import hashlib
import io
import re

STAMP = re.compile(r'^> 마지막 대조: \d{4}-\d{2}-\d{2} · sha256:[0-9a-f]{12} · 열림: (.+)$')
JOURNAL = '## 15.'


def outside_fence(lines):
    """줄마다 '펜스 밖인가'. 펜스를 여닫는 줄 자체는 밖이 아니다 — Kotlin 의 outsideFence() 와 같다."""
    out, fenced = [], False
    for line in lines:
        if line.lstrip().startswith('```'):
            fenced = not fenced
            out.append(False)
        else:
            out.append(not fenced)
    return out


def _cut(lines, outside):
    return next((i for i, l in enumerate(lines) if outside[i] and l.startswith(JOURNAL)), None)


def body(lines, outside):
    end = _cut(lines, outside)
    end = len(lines) if end is None else end
    kept = [lines[i] for i in range(end) if not (outside[i] and STAMP.match(lines[i]))]
    return '\n'.join(kept).rstrip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('path')
    # ★**기본이 '없음' 이면 안 된다.** 그림만 고치고 다시 찍는 흔한 경우에 이 도구가
    # 열림 인용을 **조용히 지웠다**(실측 2026-09-11: 세 문서에서 11 개). 지워진 수는
    # limits.md 가 적은 수와 어긋나 시험이 잡아 주지만, 그때는 이미 근거가 사라진 뒤다.
    # 그래서 안 주면 **원래 도장의 열림을 그대로 옮긴다** — 지우려면 `--open` 을 빈 채로 준다.
    ap.add_argument('--open', nargs='*', default=None, dest='open_ids')
    a = ap.parse_args()

    lines = io.open(a.path, encoding='utf-8').read().replace('\r\n', '\n').split('\n')
    outside = outside_fence(lines)
    sha = hashlib.sha256(body(lines, outside).encode('utf-8')).hexdigest()[:12]
    if a.open_ids is None:
        prev = next((STAMP.match(l) for i, l in enumerate(lines) if outside[i] and STAMP.match(l)), None)
        ids = prev.group(1).strip() if prev else '없음'
    else:
        ids = ', '.join(a.open_ids) if a.open_ids else '없음'
    stamp = '> 마지막 대조: %s · sha256:%s · 열림: %s' % (datetime.date.today(), sha, ids)

    # ★펜스 안의 예시는 건드리지 않는다. 안 그러면 형식을 정의하는 문서가 자기 예시를 잃는다.
    keep = [l for i, l in enumerate(lines) if not (outside[i] and STAMP.match(l))]
    cut = _cut(keep, outside_fence(keep))
    at = len(keep) if cut is None else cut

    # ★앞의 빈 줄을 건너뛰지 말고 걷어낸다. 건너뛰기만 하면 재도장마다 빈 줄이 두 줄씩 자라고
    # (실측: 2 → 4 → 6 → 8), body() 의 rstrip() 이 그것을 흡수해 해시가 안 바뀌므로
    # 시험이 영원히 못 잡는다. 조용히 자라는 결함이라 여기서 막는다.
    start = at
    while start > 0 and keep[start - 1].strip() == '':
        start -= 1
    del keep[start:at]
    keep[start:start] = ['', stamp, '']
    io.open(a.path, 'w', encoding='utf-8', newline='\n').write('\n'.join(keep).rstrip() + '\n')
    print(stamp)


if __name__ == '__main__':
    main()
