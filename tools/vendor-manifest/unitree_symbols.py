# -*- coding: utf-8 -*-
u"""Unitree SDK 에서 **이름만** 뽑는다.

원본이 셋과 다르다 — 여기엔 스키마 언어가 없다. 고수준은 C++ 헤더의 **API ID
상수**(`g1_loco_api.hpp`)이고, 저수준은 IDL 에서 생성된 파이썬 클래스의
필드다. 그래서 이름의 모양도 그 둘을 그대로 쓴다:

- `ROBOT_API_ID_LOCO_SET_VELOCITY` — 고수준 서비스가 받는 것
- `unitree_hg.LowState_.motor_state` — 저수준 토픽이 나르는 것

**여기가 셋 중 가장 약한 원본이다.** proto 나 매뉴얼과 달리 요청의 인자
이름이 어디에도 스키마로 없고, `SetVelocity(vx, vy, omega, duration)` 같은
시그니처는 파이썬 클라이언트 코드에만 있다. 그래서 G1 의 매니페스트는
**무엇이 있는지**를 덮지 **어떤 인자를 받는지**는 못 덮는다.
"""
import hashlib
import io
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from proto_symbols import write  # noqa: E402

NL = chr(10)

_CONST = re.compile(r"^\s*const\s+(?:int32_t|std::string)\s+(\w+)\s*=", re.M)
_PY_CLASS = re.compile(r"^class\s+(\w+)")
_PY_FIELD = re.compile(r"^\s*(?:self\._)?(\w+)\s*:\s*[\w\[\]'\".,\- ]+\s*(?:=|$)")


def header_symbols(text):
    return set(_CONST.findall(text))


def idl_symbols(text, module="unitree_hg"):
    found, current = set(), None
    for line in text.splitlines():
        m = _PY_CLASS.match(line)
        if m:
            current = "%s.%s" % (module, m.group(1))
            found.add(current)
            continue
        if current is None:
            continue
        m = _PY_FIELD.match(line)
        if m and not m.group(1).startswith("__"):
            found.add(current + "." + m.group(1))
    return found


def build(source_dir, vendor, release, out_path):
    names, sources = set(), []
    for entry in sorted(os.listdir(source_dir)):
        reader = header_symbols if entry.endswith((".hpp", ".h")) else (
            idl_symbols if entry.endswith(".py") else None)
        if reader is None:
            continue
        blob = io.open(os.path.join(source_dir, entry), "rb").read()
        names |= reader(blob.decode("utf-8", "replace"))
        sources.append((entry, hashlib.sha256(blob).hexdigest()))

    write(out_path, vendor, release, "tools/vendor-manifest/unitree_symbols.py", sources, names)
    return names, sources


if __name__ == "__main__":
    src, vendor, release, out = sys.argv[1:5]
    got, files = build(src, vendor, release, out)
    sys.stdout.write("%d symbols from %d files" % (len(got), len(files)) + NL)
