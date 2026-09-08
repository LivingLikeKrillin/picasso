# -*- coding: utf-8 -*-
u"""Agility SDK 의 메시지 정의에서 **이름만** 뽑는다.

Spot 과 달리 원본이 파이썬이다(`agility/messages/json.py` 계열). 각 클래스가
`super().__init__('action-goto', ...)` 로 **전선의 메시지 이름**을 들고,
클래스 본문의 애너테이션이 필드 이름을 든다. 구조체(`Struct`)는 메시지 이름이
없으므로 클래스 이름을 케밥으로 옮긴다 — 매뉴얼이 `object-selector` 라고
부르는 그것이다.

`agility/messages/json.py` 는 **보내는 메시지의 빌더만** 든다. 우리가 남쪽에서
읽는 것들 — `privileges`, `action-status-changed`, `robot-info`, `action-status`
열거 — 은 로봇이 보내는 쪽이라 거기 없고, 매뉴얼이 그것들의 1차 출처다.
그래서 이 도구가 원본 둘을 확장자로 갈라 읽는다.

## 추출이 틀리는 두 방향은 위험이 다르다

| 틀리는 방향 | 결과 |
|---|---|
| 이름을 **빠뜨린다** | 검사가 빨개진다 — 시끄럽지만 **안전하다**, 사람이 본다 |
| 이름을 **더 만든다** | 없는 이름을 짚어도 통과한다 — **조용히 검사가 약해진다** |

그래서 매뉴얼 쪽 필드 규칙은 `¶` 를 요구할 만큼 빡빡하다. 못 읽은 필드는
시험이 빨개지면서 드러나고, 그때 이 파일을 고치면 된다.

`proto_symbols.py` 와 같은 이유로 이름과 해시만 저장소에 들어온다.
"""
import ast
import hashlib
import io
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from proto_symbols import write  # noqa: E402

_HEADING = re.compile(r"^\s*(?:message|struct|enum)\s+([a-z0-9-]+)\s*\S?\s*$")
_OPTION = re.compile(r"^\s*option\s+([a-z0-9-]+)\s*¶")
_OPTIONAL = re.compile(r"^\s*optional\s*<.+>\s*([a-z0-9-]+)\s*¶")
_TYPED = re.compile(r"^\s*[a-z0-9-]+(?:\s*<[^>]*>)?\s+([a-z0-9-]+)\s*¶")


def manual_symbols(text):
    found, current = set(), None
    for line in text.splitlines():
        m = _HEADING.match(line)
        if m:
            current = m.group(1)
            found.add(current)
            continue
        if current is None:
            continue
        for pattern in (_OPTION, _OPTIONAL, _TYPED):
            m = pattern.match(line)
            if m:
                found.add(current + "." + m.group(1))
                break
    return found


NL = chr(10)


def _kebab(name):
    return re.sub(r"(?<!^)(?=[A-Z])", "-", name).lower()


def symbols(source):
    u"""클래스마다 전선 이름과 필드를 낸다.

    `ast` 를 쓰는 것은 정규식으로 파이썬 클래스 본문을 세는 것이 파서를 두 번
    쓰는 일이기 때문이다. 원본이 파이썬이므로 파이썬이 읽는 것이 가장 정확하다.
    """
    found = set()
    for node in ast.walk(ast.parse(source)):
        if not isinstance(node, ast.ClassDef):
            continue

        wire = None
        for call in ast.walk(node):
            if (isinstance(call, ast.Call)
                    and isinstance(call.func, ast.Attribute)
                    and call.func.attr == "__init__"
                    and call.args
                    and isinstance(call.args[0], ast.Constant)
                    and isinstance(call.args[0].value, str)):
                wire = call.args[0].value
                break

        name = wire or _kebab(node.name)
        found.add(name)
        for stmt in node.body:
            if isinstance(stmt, ast.AnnAssign) and isinstance(stmt.target, ast.Name):
                found.add(name + "." + stmt.target.id.replace("_", "-"))

    return found


def build(source_dir, vendor, release, out_path):
    u"""원본이 둘이라 확장자로 가른다 — `.py` 는 SDK, `.txt` 는 매뉴얼."""
    names, sources = set(), []
    for entry in sorted(os.listdir(source_dir)):
        reader = symbols if entry.endswith(".py") else (
            manual_symbols if entry.endswith(".txt") else None)
        if reader is None:
            continue
        blob = io.open(os.path.join(source_dir, entry), "rb").read()
        names |= reader(blob.decode("utf-8", "replace"))
        sources.append((entry, hashlib.sha256(blob).hexdigest()))

    write(out_path, vendor, release, "tools/vendor-manifest/agility_symbols.py", sources, names)
    return names, sources


if __name__ == "__main__":
    src, vendor, release, out = sys.argv[1:5]
    got, files = build(src, vendor, release, out)
    sys.stdout.write("%d symbols from %d files" % (len(got), len(files)) + NL)
