# -*- coding: utf-8 -*-
u"""`.proto` 원문에서 **이름만** 뽑는다.

## 왜 원문을 저장소에 안 들이나

`adapter-boston-dynamics-spot/build.gradle.kts` 가 적어 둔 것을 지킨다 —
*"벤더 SDK가 여기 없고, 여기 말고는 어디에도 못 들어온다 … 남쪽이 포트라
SDK 없이 컴파일되고 시험이 돈다."* 원문을 들이면 그 문장이 거짓이 되고
ADR 31 의 격리를 다시 논해야 한다.

들이는 것은 **이름과 그 원본의 해시**다. 이름은 API 에 대한 사실이지 벤더의
표현이 아니며, 조사 문서들이 이미 산문으로 하던 일과 같은 것을 기계가 읽을
수 있는 모양으로 옮길 뿐이다.

## 한계

매니페스트는 **마지막으로 뽑은 시점의 것**이다. 낡은 매니페스트는 낡은
코드와 사이좋게 초록이며, 그것을 막는 것은 이 파일이 아니라 기록된 커밋과
해시다. `README.md` 의 갱신 절차가 그 자리다.
"""
import hashlib
import io
import json
import os
import re

NL = chr(10)
import sys

_PACKAGE = re.compile(r"^\s*package\s+([\w.]+)\s*;")
_OPEN = re.compile(r"^\s*(message|enum|service)\s+(\w+)\s*\{?")
_FIELD = re.compile(
    r"^\s*(?:repeated\s+|optional\s+|required\s+)?[\w.<>,\s]+?\s+(\w+)\s*=\s*\d+\s*[;\[]")
_ENUM_VALUE = re.compile(r"^\s*(\w+)\s*=\s*\d+\s*[;\[]")
_RPC = re.compile(r"^\s*rpc\s+(\w+)\s*\(")


def symbols(text):
    u"""중괄호 깊이를 따라가며 정규화된 이름을 낸다.

    protoc 를 부르지 않는 것은 **이 검사가 오프라인이어야 하기 때문**이다.
    protoc 를 요구하면 시험이 도구 설치에 걸리고, 그러면 아무도 안 돌린다.
    대가는 이 파서가 `.proto` 문법의 부분집합만 안다는 것이며, 우리가 인용한
    이름을 못 찾으면 **없는 것이 아니라 못 읽은 것**일 수 있다 — 그래서
    시험의 실패 메시지가 그 가능성을 함께 말한다.

    ## 이름 있는 스코프는 깊이로 닫는다

    첫 판은 줄마다 여닫이 개수의 **합**만 보고 음수일 때 스코프를 닫았다.
    `oneof` 처럼 이름을 안 붙이는 블록이 그 계산을 어긋내서, 그 블록이 닫힐
    때 엉뚱한 스코프가 닫혔다 — `map.proto` 의 `Graph` 가 `Waypoint` 안으로
    들어가 `bosdyn.api.graph_nav.Waypoint.Graph.waypoints` 가 나왔다.
    **우리가 실제로 인용하는 이름 하나가 그것 때문에 안 보였고**, 그것이
    이 파서가 틀렸다는 첫 신호였다.

    이제 스코프마다 **열린 깊이**를 함께 들고, 실제 깊이가 그 아래로
    내려올 때 닫는다.
    """
    text = re.sub(r"/[*].*?[*]/", " ", text, flags=re.S)

    package, stack, depth = "", [], 0
    found = set()
    for raw in text.splitlines():
        line = raw.split("//", 1)[0]

        m = _PACKAGE.match(line)
        if m:
            package = m.group(1)

        opened = None
        m = _OPEN.match(line)
        if m:
            opened = (m.group(1), m.group(2))
            found.add(".".join([package] + [n for _, n, _ in stack] + [opened[1]]))
        elif stack:
            prefix = ".".join([package] + [n for _, n, _ in stack]) + "."
            m = _RPC.match(line)
            if m:
                found.add(prefix + m.group(1))
            elif stack[-1][0] == "enum":
                m = _ENUM_VALUE.match(line)
                if m:
                    found.add(prefix + m.group(1))
            else:
                m = _FIELD.match(line)
                if m:
                    found.add(prefix + m.group(1))

        for ch in line:
            if ch == "{":
                depth += 1
                if opened is not None:
                    stack.append((opened[0], opened[1], depth))
                    opened = None
            elif ch == "}":
                while stack and stack[-1][2] >= depth:
                    stack.pop()
                depth -= 1

    return found


def build(source_dir, vendor, release, out_path):
    u"""디렉터리를 **재귀로** 훑는다.

    처음에는 평평한 목록만 봤고, 그래서 매니페스트가 우리가 손으로 고른
    proto 12 개만 덮었다. `survey_scope` 에 *"서비스 54 개 전수"* 라고 적어
    놓고 대조 대상은 부분집합인 상태였고, 그 틈에서 **인용하려는 이름이
    매니페스트에 없어 못 붙이는** 일이 났다. 공개된 것 전부를 덮는다.
    """
    names, sources = set(), []
    for dirpath, _, files in os.walk(source_dir):
        for entry in sorted(files):
            if not entry.endswith(".proto"):
                continue
            path = os.path.join(dirpath, entry)
            blob = io.open(path, "rb").read()
            names |= symbols(blob.decode("utf-8"))
            rel = os.path.relpath(path, source_dir).replace(os.sep, "/")
            sources.append((rel, hashlib.sha256(blob).hexdigest()))
    sources.sort()

    write(out_path, vendor, release, "tools/vendor-manifest/proto_symbols.py", sources, names)
    return names, sources


def write(out_path, vendor, release, extractor, sources, names):
    u"""줄 단위 텍스트로 낸다.

    JSON 이 아닌 것은 **읽는 쪽에 파서 의존을 안 만들려는 것**이다. 그리고
    `#` 헤더의 출처 기록이 이 파일의 절반이다 — 이름만 있고 어디서 왔는지가
    없으면 매니페스트가 다시 산문이 된다.
    """
    out = [
        "# picasso vendor symbol manifest",
        "# vendor: %s" % vendor,
        "# release: %s" % release,
        "# extractor: %s" % extractor,
        "# 이 파일은 손으로 고치지 않는다. tools/vendor-manifest/README.md 참조.",
    ]
    out += ["# source: %s sha256=%s" % (f, h) for f, h in sources]
    out += sorted(names)
    io.open(out_path, "w", encoding="utf-8", newline=NL).write(NL.join(out) + NL)


if __name__ == "__main__":
    src, vendor, release, out = sys.argv[1:5]
    got, files = build(src, vendor, release, out)
    sys.stdout.write("%d symbols from %d files" % (len(got), len(files)) + NL)
