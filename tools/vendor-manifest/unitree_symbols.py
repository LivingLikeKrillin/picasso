# -*- coding: utf-8 -*-
u"""Unitree SDK 에서 **이름만** 뽑는다.

원본이 셋과 다르다 — 여기엔 스키마 언어가 없다. 고수준은 C++ 헤더의 **API ID
상수**(`g1_loco_api.hpp`)이고, 저수준은 IDL 에서 생성된 파이썬 클래스의
필드다. 그래서 이름의 모양도 그 둘을 그대로 쓴다:

- `ROBOT_API_ID_LOCO_SET_VELOCITY` — 고수준 서비스가 받는 것
- `unitree_hg.LowState_.motor_state` — 저수준 토픽이 나르는 것

셋째 종류가 하나 더 있다 — **요청 본문의 JSON 키**. 스키마 파일이 아니라
헤더의 `Jsonize*` 클래스가 `json["velocity"]` 처럼 들고 있고, 그것이 이
기종에서 인자 이름의 1차 출처다. 모양은 `JsonizeVelocityCommand.velocity`.

> **이 문단은 한 번 틀려 있었다.** 처음에는 *"요청의 인자 이름이 어디에도
> 스키마로 없다"* 고 적었는데, 그것은 벤더가 안 준 것이 아니라 **이 추출기가
> `const` 줄만 읽어서 못 본 것**이었다. 추출기의 한계를 벤더의 부재로 적은
> 셈이고, §15.65 가 경고한 그 실수다 — 근거 등급이 낮으면 `NO` 가 아니라
> `UNKNOWN` 이다.

여전히 proto 나 매뉴얼보다 약하다. 키가 **코드 안에** 있어서 타입도 필수
여부도 안 딸려 오고, 응답의 모양은 어디에도 없다.
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

# **에러 코드는 상수가 아니라 매크로다.** `UT_DECL_ERR(NAME, 7303, "Invalid task id.")`
# 꼴이며, `const` 줄만 읽던 앞 판은 `*_error.hpp` 넷에서 심볼을 **하나도** 못
# 냈다. 그 침묵이 "이 벤더는 에러 어휘가 없다" 로 읽혔을 것이고, 그것이 이
# 파일 머리말에 이미 한 번 적힌 실수(§15.65)의 두 번째 사례다.
_DECL_ERR = re.compile(r"UT_DECL_ERR\(\s*(\w+)\s*,")

# **종료 조건은 함수다.** `terminations.hpp` 가 `inline bool bad_orientation(...)`
# 꼴로 "언제 수동 모드로 내려야 하는가" 를 준다. 상수도 클래스도 아니라
# 앞 판이 3374 바이트짜리 파일에서 0 개를 냈다.
_INLINE_BOOL = re.compile(r"^\s*inline\s+bool\s+(\w+)\s*\(", re.M)
_PY_CLASS = re.compile(r"^class\s+(\w+)")
_JSONIZE = re.compile(r"^\s*class\s+(Jsonize\w+)")
_JSON_KEY = re.compile(r'json\["(\w+)"\]')
_PY_FIELD = re.compile(r"^\s*(?:self\._)?(\w+)\s*:\s*[\w\[\]'\".,\- ]+\s*(?:=|$)")


def header_symbols(text):
    u"""API ID 상수 · **에러 코드 매크로** · **종료 조건 함수** · 요청 본문의 JSON 키.

    키는 `Jsonize*` 클래스 안의 `json["velocity"]` 로 나타나므로, 지금 어느
    클래스 안인지를 따라가며 `JsonizeVelocityCommand.velocity` 로 낸다.
    클래스 밖의 키는 어디 딸린 것인지 모르므로 버린다 — 이름을 더 만드는
    쪽이 조용히 검사를 약하게 하는 쪽이다.
    """
    found = set(_CONST.findall(text))
    found |= set(_DECL_ERR.findall(text))
    found |= set(_INLINE_BOOL.findall(text))

    current = None
    for line in text.splitlines():
        m = _JSONIZE.match(line)
        if m:
            current = m.group(1)
            found.add(current)
            continue
        if current is None:
            continue
        for key in _JSON_KEY.findall(line):
            found.add(current + "." + key)
        if line.startswith("}"):
            current = None

    return found


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
