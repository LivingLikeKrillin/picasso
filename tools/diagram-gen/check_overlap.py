"""칸과 영역이 서로 붙거나 걸치는지 좌표로 본다. usage: python check_overlap.py a.svg [...]
- 영역(fill none) 안에 든 사각형은 네 변 모두 영역 경계에서 8px 이상 떨어져야 한다.
- 두 사각형은 서로 떨어져 있거나 한쪽이 다른 쪽을 품어야 한다(걸치면 안 된다).
반전 칸의 안쪽 테두리(stroke-opacity)와 범례 칸(높이 10)은 뺀다."""
import re, sys

MIN = 8


def rects(path):
    out = []
    for m in re.finditer(r"<rect ([^>]*)/>", open(path, encoding="utf-8").read()):
        a = dict(re.findall(r'([\w-]+)="([^"]*)"', m.group(1)))
        if "stroke-opacity" in a or float(a["height"]) <= 10:
            continue
        x, y, w, h = (float(a[k]) for k in ("x", "y", "width", "height"))
        out.append((x, y, x + w, y + h, a.get("fill") == "none"))
    return out


def contains(o, i):
    return o[0] <= i[0] and o[1] <= i[1] and o[2] >= i[2] and o[3] >= i[3]


def disjoint(a, b):
    return a[2] <= b[0] or b[2] <= a[0] or a[3] <= b[1] or b[3] <= a[1]


bad = 0
for p in sys.argv[1:]:
    R = rects(p)
    probs = []
    for i, a in enumerate(R):
        for j, b in enumerate(R):
            if i >= j:
                continue
            if disjoint(a, b):
                continue
            outer, inner = (a, b) if contains(a, b) else (b, a) if contains(b, a) else (None, None)
            if outer is None:
                probs.append(f"걸침 {a[:4]} / {b[:4]}")
                continue
            gap = min(inner[0] - outer[0], inner[1] - outer[1], outer[2] - inner[2], outer[3] - inner[3])
            if gap < MIN:
                probs.append(f"경계에 붙음({gap:g}px) 영역 {outer[:4]} 안 {inner[:4]}")
    print(f"== {p}: {len(R)} rects, {len(probs)} problems")
    for q in probs:
        print("  " + q)
    bad += len(probs)
sys.exit(1 if bad else 0)
