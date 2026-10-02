"""Upsert every map entry into both lang files; scan source for stray keys.
RO value = the map's RO template; EN value = the map's EN text."""
import json, re, importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location("m", "tools/refusal-map.py")
mm = importlib.util.module_from_spec(spec); spec.loader.exec_module(mm)
MAP = mm.MAP

SRC = Path("src/main/java")
LANG = Path("src/main/resources/assets/straja/lang")

# any straja.* key literal inside a refuse()/refusal()/tellKey() arg
KEY_RE = re.compile(
    r'(?:refuse|refusal|tellKey)\s*\([^)]*?"(straja\.[a-z0-9_.]+)"', re.S)

ro = json.loads((LANG / "ro_ro.json").read_text(encoding="utf-8"))
en = json.loads((LANG / "en_us.json").read_text(encoding="utf-8"))

for ro_text, (key, remedy, en_text) in MAP.items():
    ro[key] = ro_text
    en[key] = en_text
    if not remedy.startswith("straja.remedy."):
        print("BAD REMEDY in map:", key, remedy)

referenced = set()
for f in SRC.rglob("*.java"):
    src = re.sub(r"/\*.*?\*/", "", f.read_text(encoding="utf-8"), flags=re.S)
    for k in KEY_RE.findall(src):
        referenced.add(k)

missing = [k for k in referenced if k not in ro]
(LANG / "ro_ro.json").write_text(json.dumps(ro, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
(LANG / "en_us.json").write_text(json.dumps(en, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

print(f"map entries: {len(MAP)}, referenced keys: {len(referenced)}")
print(f"ro_ro: {len(ro)} keys, en_us: {len(en)} keys")
d = set(ro) ^ set(en)
print("parity diff:", d if d else "none")
if missing:
    print("UNMAPPED keys referenced but missing from ro_ro:")
    for k in sorted(missing): print("  ", k)
