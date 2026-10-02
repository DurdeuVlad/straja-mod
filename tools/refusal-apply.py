"""Second-pass refusal->remedy rewrite, census-fresh.

- recv.tell(<literal concat>)              -> recv.refuse("key","remedy"[,args])
- recv.sendSystemMessage(Component.literal)-> recv.sendSystemMessage(refusal(...))
- recv.sendFailure(Component.literal(...)) -> recv.sendFailure(refusal(...))

Only sites whose template exists in refusal-map.MAP are touched; anything else
is reported for manual handling.
"""
import json
import re
import sys
import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src" / "main" / "java"
sys.path.insert(0, str(ROOT / "tools"))
spec = importlib.util.spec_from_file_location("rm", ROOT / "tools" / "refusal-map.py")
rm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rm)
MAP = rm.MAP

spec2 = importlib.util.spec_from_file_location("rc", ROOT / "tools" / "refusal-census.py")
rc = importlib.util.module_from_spec(spec2)
spec2.loader.exec_module(rc)
CALL_RE, find_call_end, split_concat, decode_java = (
    rc.CALL_RE, rc.find_call_end, rc.split_concat, rc.decode_java)

SWEPT = [
    "application/service/AdminService.java",
    "application/service/ArchiveService.java",
    "application/service/ArmoryService.java",
    "application/service/AudienceService.java",
    "application/service/BoloService.java",
    "application/service/ComplaintService.java",
    "application/service/CustodyService.java",
    "application/service/EmergencyService.java",
    "application/service/EvidenceService.java",
    "application/service/FineService.java",
    "application/service/GuardService.java",
    "application/service/IdentityCardService.java",
    "application/service/IncidentService.java",
    "application/service/MissionService.java",
    "application/service/PrisonService.java",
    "application/service/ReportService.java",
    "application/service/RoomService.java",
    "application/service/ArrestRecordService.java",
    "application/service/AdminToolService.java",
    "application/service/EquipmentService.java",
    "application/service/PolicyService.java",
    "application/service/ReputationService.java",
    "application/service/RpExpansionService.java",
    "application/service/SecretaryService.java",
    "adapter/in/command/AdminCommandHelp.java",
    "adapter/in/command/DebugCommands.java",
    "adapter/in/command/NpcCommands.java",
    "adapter/in/command/StrajaCommands.java",
    "adapter/in/event/StrajaEvents.java",
    "adapter/in/form/FormSubmissionRouter.java",
    "adapter/in/npc/NpcRoles.java",
]


def template_of(parts):
    out, args = [], []
    for kind, val in parts:
        if kind == "lit":
            out.append(val)
        else:
            out.append("%s")
            args.append(val)
    return "".join(out), args


def java_str(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def extract(arg):
    """Return (template, args) for a call argument."""
    parts = split_concat(arg)
    if not any(k == "lit" for k, _ in parts):
        # nested Component.literal( ... ) — possibly line-wrapped and holding
        # a concat chain; parse the inner call's argument instead
        nested = re.search(r'Component\s*\.\s*literal\s*\(', arg)
        if nested:
            inner_open = arg.index("(", nested.end() - 1)
            try:
                inner_end = find_call_end(arg, inner_open)
            except ValueError:
                inner_end = -1
            if inner_end > inner_open:
                parts = split_concat(arg[inner_open + 1:inner_end])
    return template_of(parts)


def main():
    used = {}
    changed_files = []
    leftovers = []
    for rel in SWEPT:
        path = SRC / "com" / "dwurdy" / "straja" / rel
        src = path.read_text(encoding="utf-8")
        # collect all literal-carrying sites with live positions, back-to-front
        sites = []
        for m in CALL_RE.finditer(src):
            open_paren = m.end() - 1
            try:
                end = find_call_end(src, open_paren)
            except ValueError:
                continue
            arg = src[open_paren + 1:end]
            tpl, args = extract(arg)
            if not tpl:
                continue
            sites.append((m, open_paren, end, tpl, args))
        touched = False
        for m, open_paren, end, tpl, args in sorted(
                sites, key=lambda s: -s[1]):
            entry = MAP.get(tpl)
            if not entry:
                if rc.DENIAL_RE.search(tpl):
                    leftovers.append((rel, src.count("\n", 0, m.start()) + 1,
                                      tpl[:80]))
                continue
            key, remedy, en = entry
            extra = "" if not args else ", " + ", ".join(args)
            if m.group("m") == "tell":
                new = f'{m.group("recv")}.refuse({java_str(key)}, {java_str(remedy)}{extra})'
            else:
                new = (f'{m.group("recv")}.{m.group("m")}('
                       f'refusal({java_str(key)}, {java_str(remedy)}{extra}))')
            src = src[:m.start()] + new + src[end + 1:]
            used[key] = (tpl, en)
            touched = True
        if touched:
            path.write_text(src, encoding="utf-8")
            changed_files.append(rel)

    print(f"rewrote {len(used)} unique templates across {len(changed_files)} files")
    for f in changed_files:
        print("  ", f)
    if leftovers:
        print(f"\n{len(leftovers)} denial-shaped literals left unmapped:")
        for rel, line, tpl in leftovers:
            print(f"  {rel}:{line} {tpl!r}")
    (ROOT / "tools" / "refusal-lang.json").write_text(
        json.dumps(used, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
