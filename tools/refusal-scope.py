"""Scope the refusal plan to methods reachable through player surfaces.

A service tell() is in scope if its enclosing method is declared in an
application/port/in interface (NPC actions and public commands call those).
Adapter files are always in scope. Emits refusal-scope.json and prints a
per-file denial count for the in-scope set.
"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src" / "main" / "java" / "com" / "dwurdy" / "straja"
PORTS = SRC / "application" / "port" / "in"

DENIAL_RE = re.compile(
    r"(nu\s|doar|refuz|permis|expir|invalid|indisponibil|depăș|ocupat|"
    r"imposibil|trebuie|necesar|nu\s+se\s+poate|lipsesc|lipsește|eșuat)",
    re.IGNORECASE)

KEYWORDS = {"if", "for", "while", "switch", "catch", "synchronized", "try",
            "else", "do", "return", "new", "throw"}


def method_spans(src):
    """Return [(name, open_idx, close_idx)] for method-like `name(...) {`."""
    spans = []
    depth = 0
    in_str = in_chr = in_line = in_block = False
    i = 0
    stack = []
    while i < len(src):
        c = src[i]
        nxt = src[i + 1] if i + 1 < len(src) else ""
        if in_line:
            if c == "\n":
                in_line = False
        elif in_block:
            if c == "*" and nxt == "/":
                in_block = False
                i += 1
        elif in_str:
            if c == "\\":
                i += 1
            elif c == '"':
                in_str = False
        elif in_chr:
            if c == "\\":
                i += 1
            elif c == "'":
                in_chr = False
        else:
            if c == "/" and nxt == "/":
                in_line = True
            elif c == "/" and nxt == "*":
                in_block = True
            elif c == '"':
                in_str = True
            elif c == "'":
                in_chr = True
            elif c == "{":
                stack.append(depth)
                depth += 1
                # look back: is this `name(...) {`?
                j = i - 1
                while j >= 0 and src[j] in " \t\r\n":
                    j -= 1
                if j >= 0 and src[j] == ")":
                    # find matching '('
                    k = j
                    pdepth = 0
                    while k >= 0:
                        if src[k] == ")":
                            pdepth += 1
                        elif src[k] == "(":
                            pdepth -= 1
                            if pdepth == 0:
                                break
                        k -= 1
                    if k > 0:
                        m = re.search(r"([A-Za-z_]\w*)\s*$", src[:k])
                        if m and m.group(1) not in KEYWORDS:
                            spans.append((m.group(1), i, None, len(stack)))
            elif c == "}":
                depth -= 1
                stack.pop()
        i += 1
    # second pass: find each method's close brace
    out = []
    depth = 0
    in_str = in_chr = in_line = in_block = False
    meth_stack = []
    i = 0
    opens = {s[1]: s for s in spans}
    while i < len(src):
        c = src[i]
        nxt = src[i + 1] if i + 1 < len(src) else ""
        if in_line:
            if c == "\n":
                in_line = False
        elif in_block:
            if c == "*" and nxt == "/":
                in_block = False
                i += 1
        elif in_str:
            if c == "\\":
                i += 1
            elif c == '"':
                in_str = False
        elif in_chr:
            if c == "\\":
                i += 1
            elif c == "'":
                in_chr = False
        else:
            if c == "/" and nxt == "/":
                in_line = True
            elif c == "/" and nxt == "*":
                in_block = True
            elif c == '"':
                in_str = True
            elif c == "'":
                in_chr = True
            elif c == "{":
                if i in opens:
                    meth_stack.append([i, opens[i]])
                else:
                    meth_stack.append([i, None])
            elif c == "}":
                if meth_stack:
                    oi, m = meth_stack.pop()
                    if m is not None:
                        out.append((m[0], oi, i))
        i += 1
    return out


def enclosing(spans, idx):
    best = None
    for name, o, c in spans:
        if o is not None and c is not None and o < idx < c:
            if best is None or o > best[1]:
                best = (name, o, c)
    return best[0] if best else None


def port_method_names():
    names = set()
    for p in PORTS.rglob("*.java"):
        src = p.read_text(encoding="utf-8")
        for m in re.finditer(r"^\s*(?:[\w<>\[\],.?]+\s+)+(\w+)\s*\(", src, re.M):
            names.add(m.group(1))
    return names


def main():
    recs = json.loads((ROOT / "tools" / "refusal-census.json").read_text(encoding="utf-8"))
    plan = json.loads((ROOT / "tools" / "refusal-plan.json").read_text(encoding="utf-8"))
    # plan entries carry site = "<path after com/dwurdy/straja/>:line"; key the
    # census records identically so the two lists cannot drift apart.
    rec_by_key = {}
    for r in recs:
        short = r["file"].split("src/main/java/com/dwurdy/straja/")[1]
        rec_by_key[(short, r["line"])] = r
    ports = port_method_names()
    cache = {}
    out = []
    for p in plan:
        short, line = p["site"].rsplit(":", 1)
        r = rec_by_key[(short, int(line))]
        path = ROOT / r["file"]
        if r["file"] not in cache:
            src = path.read_text(encoding="utf-8")
            cache[r["file"]] = (src, method_spans(src))
        src, spans = cache[r["file"]]
        # site idx: re-locate call at its line
        lines = src.split("\n")
        line_start = sum(len(l) + 1 for l in lines[: r["line"] - 1])
        call_idx = src.find(r["recv"] + "." + r["method"] + "(", line_start)
        meth = enclosing(spans, call_idx) if call_idx >= 0 else None
        in_scope = (r["file"].startswith("src/main/java/com/dwurdy/straja/adapter/")
                    or (meth in ports if meth else False))
        p["enclosing"] = meth
        p["in_scope"] = in_scope
        p["denial"] = bool(DENIAL_RE.search(p["template"]))
    (ROOT / "tools" / "refusal-scope.json").write_text(
        json.dumps(plan, ensure_ascii=False, indent=1), encoding="utf-8")
    scope = [p for p in plan if p["in_scope"] and p["denial"] and not p["complex"]]
    uniq = {p["template"] for p in scope}
    print(f"in-scope denial sites: {len(scope)}  unique: {len(uniq)}")
    print(f"in-scope denial COMPLEX (manual): "
          f"{sum(1 for p in plan if p['in_scope'] and p['denial'] and p['complex'])}")
    from collections import Counter
    c = Counter(p["site"].split(":")[0].split("/")[-1] for p in scope)
    for f, n in c.most_common():
        print(f"  {n:3} {f}")


if __name__ == "__main__":
    main()
