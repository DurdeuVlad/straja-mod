"""Every string literal in swept files matching broadened denial vocabulary."""
import re, importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location("a", "tools/refusal-apply.py")
ap = importlib.util.module_from_spec(spec); spec2=spec.loader; spec.loader.exec_module(ap)
SWEPT = ap.SWEPT

LIT = re.compile(r'"([^"\n]*)"')
DENY = re.compile(r"(?i)(nu |doar |numai |trebuie |insuficient|dezactivat|rezervat|interzis|necesit|cere |offline|necunoscut|maximum|numărul maxim|limita|epuizat|deja|Ține|Alege|Scrie|Folosește|disponibil|blocat|decizia|obligatoriu|invalid|eșuat|depăș|prea multe|not running|missing|lipsește|unknown|failed|denied|mismatch)")
KEY_RE = re.compile(r'^(straja\.[a-z0-9_.]+|[a-z_]{2,30})$')
out=[]
for rel in SWEPT:
    src = Path("src/main/java/com/dwurdy/straja", rel).read_text(encoding="utf-8")
    # drop comments
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    src = re.sub(r'//[^\n]*', '', src)
    for i, line in enumerate(src.splitlines(), 1):
        for m in LIT.finditer(line):
            t = m.group(1)
            if len(t) < 8 or not DENY.search(t):
                continue
            if KEY_RE.match(t):
                continue
            out.append(f"{rel}:{i}\t{t[:110]}")
Path("tools/literal-dump.txt").write_text("\n".join(out), encoding="utf-8")
print(len(out))
