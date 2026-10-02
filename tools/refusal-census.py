"""Extract player-facing message callsites for the refusal->remedy sweep.

Scans Java sources for `X.tell(...)`, `x.sendSystemMessage(...)` and
`sendFailure(...)` calls whose argument contains string literals, splits
top-level `+` concatenations into literal and code parts, and emits a JSON
inventory for review. Denial detection is a broad Romanian-language heuristic;
the emitted table is reviewed by hand before any rewrite happens.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src" / "main" / "java"

DENIAL_RE = re.compile(
    r"(nu\s|doar|refuz|permis|expir|invalid|indisponibil|depăș|ocupat|"
    r"imposibil|trebuie|necesar|nu\s+se\s+poate|lipsesc|lipsește|eșuat)",
    re.IGNORECASE)

CALL_RE = re.compile(
    r"(?P<recv>[\w.\)\(\[\]]+)\.(?P<m>tell|sendSystemMessage|sendFailure)\(")


def iter_chars(src):
    """Yield (index, char) skipping nothing; caller tracks state."""
    yield from enumerate(src)


def skip_ws(src, i):
    while i < len(src) and src[i] in " \t\r\n":
        i += 1
    return i


def find_call_end(src, open_paren):
    """Return index of the ')' matching src[open_paren] == '('."""
    depth = 0
    i = open_paren
    in_str = in_chr = in_line = in_block = False
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
            elif c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
                if depth == 0:
                    return i
        i += 1
    raise ValueError("unbalanced parens")


def split_concat(expr):
    """Split a Java expression on top-level '+' concatenation.
    Returns list of ('lit', decoded_text) or ('code', source) parts."""
    parts = []
    depth = 0
    in_str = in_chr = False
    buf = ""
    i = 0
    while i < len(expr):
        c = expr[i]
        if in_str:
            buf += c
            if c == "\\":
                i += 1
                buf += expr[i]
            elif c == '"':
                in_str = False
            i += 1
            continue
        if in_chr:
            buf += c
            if c == "\\":
                i += 1
                buf += expr[i]
            elif c == "'":
                in_chr = False
            i += 1
            continue
        if c == '"':
            in_str = True
            buf += c
        elif c == "'":
            in_chr = True
            buf += c
        elif c in "([{":
            depth += 1
            buf += c
        elif c in ")]}":
            depth -= 1
            buf += c
        elif c == "+" and depth == 0:
            parts.append(buf)
            buf = ""
        else:
            buf += c
        i += 1
    if buf.strip():
        parts.append(buf)
    out = []
    for p in parts:
        p = p.strip()
        if not p:
            continue
        if p.startswith('"') and p.endswith('"'):
            out.append(("lit", decode_java(p[1:-1])))
        else:
            out.append(("code", " ".join(p.split())))
    return out


def decode_java(s):
    out = []
    i = 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            n = s[i + 1]
            out.append({"n": "\n", "t": "\t", '"': '"', "\\": "\\"}.get(n, "\\" + n))
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def line_of(src, idx):
    return src.count("\n", 0, idx) + 1


def main():
    records = []
    for path in sorted(SRC.rglob("*.java")):
        rel = path.relative_to(ROOT).as_posix()
        src = path.read_text(encoding="utf-8")
        for m in CALL_RE.finditer(src):
            open_paren = m.end() - 1
            try:
                end = find_call_end(src, open_paren)
            except ValueError:
                continue
            arg = src[open_paren + 1:end]
            parts = split_concat(arg)
            lits = [t for k, t in parts if k == "lit"]
            if not lits:
                # e.g. sendSystemMessage(Component.literal("...")) — the
                # literal sits inside a nested call, not at concat top level.
                nested = re.findall(
                    r'Component\.literal\(\s*"((?:[^"\\]|\\.)*)"\s*\)', arg)
                lits = [decode_java(s) for s in nested]
                if not lits:
                    continue
                parts = [("lit", t) for t in lits]
            text = "".join(lits)
            records.append({
                "file": rel,
                "line": line_of(src, m.start()),
                "recv": m.group("recv"),
                "method": m.group("m"),
                "arg": " ".join(arg.split())[:400],
                "parts": parts,
                "text": text[:300],
                "denial": bool(DENIAL_RE.search(text)),
            })
    out = ROOT / "tools" / "refusal-census.json"
    out.write_text(json.dumps(records, ensure_ascii=False, indent=1),
                   encoding="utf-8")
    deny = [r for r in records if r["denial"]]
    print(f"total literal callsites: {len(records)}")
    print(f"denial-shaped: {len(deny)}")
    from collections import Counter
    c = Counter(r["file"] for r in deny)
    for f, n in c.most_common():
        print(f"  {n:3} {f}")


if __name__ == "__main__":
    main()
