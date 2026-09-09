# -*- coding: utf-8 -*-
u"""게시된 OpenAPI 스펙과 벤더의 파이썬 클라이언트에서 **이름만** 뽑는다 (Boston Dynamics Orbit).

`proto_symbols.py`·`agility_symbols.py` 와 같은 자리이고, 원본의 성질만 다르다.

## 원본이 파일로 배포되지 않는다

Orbit 스펙은 JSON 으로 내려받을 수 없다 — Swagger UI 페이지에 **JS 객체 리터럴로 인라인**돼
있고 키에 따옴표가 없어 `json.loads` 가 안 된다(§15.83). 그래서 이 도구가 페이지에서
`openapi:` 를 찾아 그것을 감싸는 `{` 부터 짝까지 잘라내고, **작은 파서로 직접 읽는다.**

**정규식으로 키를 따옴표 씌워 JSON 으로 만들지 않는다.** 그 방법은 문자열 안의 `foo:` 도
키로 바꿔 **없는 이름을 만들어 낸다** — README 가 적은 두 방향 중 조용한 쪽이다. 파서는
문자열을 문자열로 알기 때문에 그 실수를 할 수 없고, 모르는 문법을 만나면 **터진다**(시끄러운
쪽). 그것이 이 선택의 전부다.

## 원본이 둘이고, 그 둘의 근거 등급이 다르다

| 원본 | 무엇 | 이름 |
|---|---|---|
| 게시 스펙(HTML 안의 객체) | 경로·메서드·스키마·필드·열거값 | `GET /robots`, `Schedule`, `Schedule.task` |
| `bosdyn-orbit` 클라이언트 | **스펙에 없는 경로** | `bosdyn-orbit:calendar/mission/dispatch/{nickname}` |
| 같은 클라이언트의 `payload` | **실제로 보내는 본문의 키** — 스펙의 스키마와 다르다 | `bosdyn-orbit:body:task.dispatchTarget.missionId` |

접두사가 있는 것은 우리 표기다. 벤더의 표기를 그대로 쓰는 원칙(§15.56)의 예외이며 이유가
있다 — **같은 이름 공간에 섞으면 게시 스펙에 있는 것과 클라이언트에만 있는 것을 구별할 수
없고**, 그 구별이 Orbit 측정의 핵심이다(게시본이 불완전하다는 것이 증명된 사실이다).

## 무엇을 안 덮나

- **응답의 모양을 안 본다.** 클라이언트에서는 경로 문자열만 읽는다.
- **`type: "object"` 로 열린 자리**는 필드가 없다 — `Webhook.events` 처럼 벤더가 스펙에 안 적고
  산문에만 적은 것이 있다. 매니페스트에 안 나오는 것이 곧 부재가 아니다.
- 인스턴스마다 자기 `/api/v0` 를 서빙하므로 **배포본이 게시본보다 넓을 수 있다**(§15.83 ③).
"""
import hashlib
import io
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from proto_symbols import write  # noqa: E402

NL = "\n"

# ── JS 객체 리터럴 파서 ─────────────────────────────────────────────────────
#
# Swagger UI 가 인라인하는 부분집합만 읽는다: 객체·배열·문자열(따옴표 둘 다)·수·
# true/false/null, 그리고 **따옴표 없는 식별자 키**. 그 밖은 터진다.

_IDENT = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*")
_NUMBER = re.compile(r"-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?")


class _Parser(object):

    def __init__(self, text, start):
        self.text = text
        self.i = start

    def fail(self, what):
        near = self.text[max(0, self.i - 40):self.i + 40].replace("\n", " ")
        raise ValueError("%s at %d near: %s" % (what, self.i, near))

    def ws(self):
        while self.i < len(self.text):
            c = self.text[self.i]
            if c in " \t\r\n":
                self.i += 1
            # 주석은 안 받는다. 나오면 터진다 — 조용히 건너뛰면 그 안의 것을 놓친다.
            else:
                return

    def value(self):
        self.ws()
        if self.i >= len(self.text):
            self.fail("unexpected end")
        c = self.text[self.i]
        if c == "{":
            return self.obj()
        if c == "[":
            return self.arr()
        if c in "\"'":
            return self.string()
        if self.text.startswith("true", self.i):
            self.i += 4
            return True
        if self.text.startswith("false", self.i):
            self.i += 5
            return False
        if self.text.startswith("null", self.i):
            self.i += 4
            return None
        m = _NUMBER.match(self.text, self.i)
        if m:
            self.i = m.end()
            return float(m.group(0)) if ("." in m.group(0) or "e" in m.group(0).lower()) else int(m.group(0))
        self.fail("unexpected token")

    def obj(self):
        self.i += 1  # {
        out = {}
        self.ws()
        if self.i < len(self.text) and self.text[self.i] == "}":
            self.i += 1
            return out
        while True:
            self.ws()
            c = self.text[self.i]
            if c in "\"'":
                key = self.string()
            else:
                # 식별자 키와 **수 키**(`responses: { 200: … }`). 둘 다 JS 리터럴의 문법이며,
                # 수 키를 안 받으면 응답 블록에서 터진다 — 실측으로 걸렸다.
                m = _IDENT.match(self.text, self.i) or _NUMBER.match(self.text, self.i)
                if not m:
                    self.fail("bad key")
                key = m.group(0)
                self.i = m.end()
            self.ws()
            if self.text[self.i] != ":":
                self.fail("expected ':'")
            self.i += 1
            out[key] = self.value()
            self.ws()
            c = self.text[self.i]
            if c == ",":
                self.i += 1
                self.ws()
                # 뒤따르는 쉼표 뒤에 닫는 괄호가 올 수 있다.
                if self.text[self.i] == "}":
                    self.i += 1
                    return out
                continue
            if c == "}":
                self.i += 1
                return out
            self.fail("expected ',' or '}'")

    def arr(self):
        self.i += 1  # [
        out = []
        self.ws()
        if self.text[self.i] == "]":
            self.i += 1
            return out
        while True:
            out.append(self.value())
            self.ws()
            c = self.text[self.i]
            if c == ",":
                self.i += 1
                self.ws()
                if self.text[self.i] == "]":
                    self.i += 1
                    return out
                continue
            if c == "]":
                self.i += 1
                return out
            self.fail("expected ',' or ']'")

    def string(self):
        quote = self.text[self.i]
        self.i += 1
        buf = []
        while True:
            if self.i >= len(self.text):
                self.fail("unterminated string")
            c = self.text[self.i]
            if c == "\\":
                nxt = self.text[self.i + 1]
                buf.append({"n": "\n", "t": "\t", "r": "\r", "b": "\b", "f": "\f"}.get(nxt, nxt))
                if nxt == "u":
                    buf[-1] = chr(int(self.text[self.i + 2:self.i + 6], 16))
                    self.i += 4
                self.i += 2
                continue
            if c == quote:
                self.i += 1
                return "".join(buf)
            buf.append(c)
            self.i += 1


def extract_spec(page):
    u"""페이지에서 스펙 객체를 잘라 읽는다.

    `openapi:` 를 찾아 **그것을 감싸는 `{`** 로 되짚어 올라간 뒤 파서에 넘긴다. 하나도 못
    찾으면 터진다 — 0 개를 조용히 통과시키면 *"벤더가 스펙을 안 준다"* 가 되고, 그것이
    §15.82 가 적어 둔 침묵이다.
    """
    for m in re.finditer(r"""["']?openapi["']?\s*:\s*["']3\.""", page):
        start = page.rfind("{", 0, m.start())
        if start < 0:
            continue
        spec = _Parser(page, start).value()
        if isinstance(spec, dict) and "paths" in spec:
            return spec
    raise ValueError("페이지에서 openapi 스펙 객체를 못 찾았다 — 추출기를 의심하라")


# ── 심볼 ────────────────────────────────────────────────────────────────────

_METHODS = ("get", "put", "post", "delete", "patch", "head", "options", "trace")


def spec_symbols(spec):
    names = set()

    for path, item in sorted((spec.get("paths") or {}).items()):
        if not isinstance(item, dict):
            continue
        for method in _METHODS:
            operation = item.get(method)
            if operation is None:
                continue
            names.add("%s %s" % (method.upper(), path))
            names |= _inline_schemas("%s %s" % (method.upper(), path), operation)

    schemas = ((spec.get("components") or {}).get("schemas") or {})
    for schema, body in sorted(schemas.items()):
        names.add(schema)
        names |= _properties(schema, body)
    return names


def _inline_schemas(where, operation):
    u"""경로에 **인라인으로 적힌 스키마**의 키들 — 응답 봉투와 요청 본문.

    `components.schemas` 만 읽으면 이것들이 통째로 빠진다. Orbit 에서 그 구멍이 실제로 물렸다:
    `GET /runs/` 의 응답이 `{limit, offset, total, resources: [Run]}` 인데 `Run` 만 매니페스트에 있어
    **봉투의 `resources` 를 짚을 수 없었다.** 짚을 수 없으면 우리 구현이 그 이름을 하드코딩하게 되고,
    벤더가 그것을 바꿔도 아무것도 안 빨개진다.

    `$ref` 는 안 따라간다 — 가리키는 스키마는 이미 따로 나온다. 봉투만 여기서 더한다.
    """
    names = set()
    bodies = []
    for response in (operation.get("responses") or {}).values():
        if isinstance(response, dict):
            for content in (response.get("content") or {}).values():
                if isinstance(content, dict) and isinstance(content.get("schema"), dict):
                    bodies.append(content["schema"])
    request = operation.get("requestBody")
    if isinstance(request, dict):
        for content in (request.get("content") or {}).values():
            if isinstance(content, dict) and isinstance(content.get("schema"), dict):
                bodies.append(content["schema"])

    for body in bodies:
        for key, value in sorted((body.get("properties") or {}).items()):
            name = "%s#%s" % (where, key)
            names.add(name)
            names |= _properties(name, value)
    return names


def _properties(prefix, body, depth=0):
    u"""스키마의 필드 이름. 인라인 중첩 객체는 점으로 잇는다.

    깊이를 제한하지 않는다 — 제한하면 깊은 자리의 이름이 조용히 사라지고, 그 침묵은
    *"벤더가 안 준다"* 로 읽힌다.
    """
    names = set()
    if not isinstance(body, dict):
        return names

    for key, value in sorted((body.get("properties") or {}).items()):
        name = prefix + "." + key
        names.add(name)
        names |= _properties(name, value, depth + 1)

    # 열거값. 스펙이 값 목록을 준 자리만이며, `type: "object"` 로 열린 자리는 안 나온다.
    #
    # **필드의 열거를 여기서 따로 세지 않는다.** 위의 재귀가 그 필드를 이 함수로 다시 들여보내고,
    # 그때 이 줄이 같은 이름을 만든다 — 첫 판은 둘 다 있었고, 결함 주입이 그 사실을 드러냈다
    # (한쪽을 지워도 매니페스트가 그대로였다). 죽은 가지를 남기면 **검사가 검사하는 척한다.**
    for choice in body.get("enum") or []:
        if isinstance(choice, str):
            names.add("%s=%s" % (prefix, choice))

    # 배열의 원소가 인라인 객체면 그 필드도 이름이다.
    items = body.get("items")
    if isinstance(items, dict):
        names |= _properties(prefix + "[]", items, depth + 1)
    return names


#: 경로를 실제로 치는 자리. **여기 없는 헬퍼가 생기면 그 경로는 안 나온다** — 그 침묵을
#: 막는 것은 이 목록을 벤더 소스와 맞대 보는 사람이다(README 의 "검사할 목록이 손으로 적혀 있다").
_CLIENT_CALLS = (
    "get_resource", "post_resource", "patch_resource", "delete_resource",
    "get", "post", "patch", "delete", "put",
)

_HOST_PREFIX = re.compile(r"^https?://\{[^}]*\}/?")
_API_PREFIX = re.compile(r"^api/v0/")


def _literal(node):
    u"""문자열 리터럴이나 f-스트링을 **벤더가 쓴 그대로** 옮긴다. 자리표시자는 `{식}` 로 남는다."""
    import ast
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value
    if isinstance(node, ast.JoinedStr):
        out = []
        for part in node.values:
            if isinstance(part, ast.Constant) and isinstance(part.value, str):
                out.append(part.value)
            elif isinstance(part, ast.FormattedValue):
                out.append("{" + ast.unparse(part.value) + "}")
            else:
                return None
        return "".join(out)
    return None


def _body_keys(node, prefix_path, out):
    u"""딕셔너리 리터럴의 **키 경로**를 모은다. 값이 리터럴이 아니면 거기서 멈춘다."""
    import ast
    if not isinstance(node, ast.Dict):
        return
    for key, value in zip(node.keys, node.values):
        if not (isinstance(key, ast.Constant) and isinstance(key.value, str)):
            continue
        path = prefix_path + [key.value]
        out.add(".".join(path))
        _body_keys(value, path, out)


def client_bodies(text, prefix):
    u"""클라이언트가 **실제로 보내는 본문의 키**.

    ## 왜 경로만으로 부족한가

    Orbit 에서 게시 스펙의 스키마와 클라이언트가 보내는 본문이 **다르다**. `Schedule.schedule.timeMs` 는
    스펙에서 `type: integer` 인데 클라이언트는 `{low, high, unsigned}` 를 보내고, `Schedule.task.missionId` 는
    클라이언트에서 `task.dispatchTarget.missionId` 다. 스펙만 매니페스트에 넣으면 **우리 구현이 스펙을 따라
    짜여 서버에 거절당하는데 검사는 초록**이다.

    `payload` 라는 이름에 붙는 딕셔너리만 읽는다 — 모든 딕셔너리를 읽으면 벤더가 안 보내는 키까지 이름이
    되고, 그것은 없는 것을 만드는 쪽이다.
    """
    import ast
    found = set()
    for node in ast.walk(ast.parse(text)):
        targets = []
        if isinstance(node, ast.Assign):
            targets = [t for t in node.targets if isinstance(t, ast.Name)]
        elif isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name):
            targets = [node.target]
        if any(t.id == "payload" for t in targets):
            keys = set()
            _body_keys(node.value, [], keys)
            found |= set(prefix + ":body:" + k for k in keys)
    return found


def client_symbols(text, prefix):
    u"""클라이언트가 **실제로 치는 경로**만. 응답 처리는 안 본다.

    ## 정규식으로 안 읽는다

    첫 판은 따옴표 안의 `a/b` 모양을 전부 경로로 읽었다. 그러면 **`application/json` 같은
    문자열이 벤더의 경로가 되고**(없는 이름을 만드는 쪽 — README 의 조용한 방향), 동시에
    진짜 경로는 놓친다: 벤더가 `f'calendar/mission/dispatch/{{nickname}}?currentDriverId={{id}}'`
    처럼 **쿼리를 붙여** 한 문자열로 쓰기 때문에 닫는 따옴표에 앵커를 건 정규식이 빗나갔다.
    **기록해 둔 사실(§15.83)과 안 맞아서 드러났다** — 매니페스트를 처음 만들 때 아는 이름
    하나를 확인하는 것이 그래서 값을 한다.

    그래서 AST 로 읽고, **호출된 자리**로 거른다: HTTP 헬퍼의 첫 인자만 경로다.
    """
    import ast
    tree = ast.parse(text)
    found = set()
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call) or not node.args:
            continue
        fn = node.func
        name = fn.attr if isinstance(fn, ast.Attribute) else (fn.id if isinstance(fn, ast.Name) else None)
        if name not in _CLIENT_CALLS:
            continue
        path = _literal(node.args[0])
        if path is None:
            continue
        path = _API_PREFIX.sub("", _HOST_PREFIX.sub("", path)).lstrip("/")
        # 호스트만 친 자리(인증 전 헬스체크)는 경로가 아니다. **자리표시자뿐인 것도 아니다** —
        # `get_resource(f'.../{path}')` 같은 범용 헬퍼의 정의 자체가 거기 걸린다. 남기면
        # 벤더에게 `{path}` 라는 경로가 있는 것처럼 보인다(없는 이름을 만드는 쪽).
        if path and re.sub(r"\{[^}]*\}|/", "", path):
            found.add(prefix + ":" + path)
    return found


def build(src_dir, prefix="bosdyn-orbit"):
    names, sources = set(), []
    for entry in sorted(os.listdir(src_dir)):
        path = os.path.join(src_dir, entry)
        if not os.path.isfile(path):
            continue
        raw = io.open(path, "rb").read()
        sources.append((entry, hashlib.sha256(raw).hexdigest()))
        text = raw.decode("utf-8", "replace")
        if entry.endswith((".html", ".htm")):
            names |= spec_symbols(extract_spec(text))
        elif entry.endswith(".py"):
            names |= client_symbols(text, prefix)
            names |= client_bodies(text, prefix)
        else:
            raise ValueError("모르는 원본이다: %s (.html 은 스펙, .py 는 클라이언트)" % entry)
    if not names:
        raise ValueError("이름이 하나도 안 나왔다 — 원본이 아니라 추출기를 먼저 의심하라")
    return names, sources


if __name__ == "__main__":
    src, vendor, release, out = sys.argv[1:5]
    got, srcs = build(src)
    write(out, vendor, release, "tools/vendor-manifest/openapi_symbols.py", srcs, got)
    sys.stdout.write("%d symbols from %d files" % (len(got), len(srcs)) + NL)
