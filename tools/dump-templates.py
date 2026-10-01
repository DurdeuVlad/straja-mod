"""Dump real %s-templates for each literal site in tools/remaining.txt."""
import re, importlib.util, sys
from pathlib import Path

NESTED = re.compile(r'Component\.literal\(\s*"((?:[^"\\]|\\.)*)"\s*\)')

spec = importlib.util.spec_from_file_location("c", "tools/refusal-census.py")
rc = importlib.util.module_from_spec(spec); spec.loader.exec_module(rc)
SRC = Path("src/main/java")

want = {}
for line in Path("tools/remaining.txt").read_text(encoding="utf-8").splitlines():
    m = re.match(r"(.+?):(\d+)\t(DENY|info)\t(.*)", line)
    if m:
        want.setdefault(m.group(1), []).append(int(m.group(2)))

for rel, lines in want.items():
    src = (SRC / "com" / "dwurdy" / "straja" / rel).read_text(encoding="utf-8")
    ln = 0
    for m in rc.CALL_RE.finditer(src):
        try:
            end = rc.find_call_end(src, m.end() - 1)
        except ValueError:
            continue
        cur = src.count("\n", 0, m.start()) + 1
        if cur not in lines:
            continue
        arg = src[m.end():end]
        parts = rc.split_concat(arg)
        if not any(k == "lit" for k, _ in parts):
            parts = [("lit", rc.decode_java(t)) for t in NESTED.findall(arg)]
        tpl = "".join(v if k == "lit" else "%s" for k, v in parts)
        print(f"{rel}:{cur}\t{tpl}")
