"""Refine refusal-census.json into a deduplicated rewrite plan.

Emits tools/refusal-plan.json: one entry per callsite with a normalized
"template" (literal text with %s where code args sit) plus an arg list.
Dedupe happens downstream by template.
"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Files in the sweep: roleplay-port services + player-facing adapters.
SWEPT = {
    "application/service/AdminService.java",
    "application/service/ArchiveService.java",
    "application/service/ArmoryService.java",
    "application/service/ArrestRecordService.java",
    "application/service/AudienceService.java",
    "application/service/BoloService.java",
    "application/service/ComplaintService.java",
    "application/service/CustodyService.java",
    "application/service/EmergencyService.java",
    "application/service/EquipmentService.java",
    "application/service/EvidenceService.java",
    "application/service/FineService.java",
    "application/service/GuardService.java",
    "application/service/IdentityCardService.java",
    "application/service/IncidentService.java",
    "application/service/MissionService.java",
    "application/service/PrisonService.java",
    "application/service/ReportService.java",
    "application/service/ReputationService.java",
    "application/service/RoomService.java",
    "application/service/RpExpansionService.java",
    "application/service/SecretaryService.java",
    "adapter/in/npc/NpcRoles.java",
    "adapter/in/npc/NpcPlayerSurface.java",
    "adapter/in/command/StrajaCommands.java",
    "adapter/in/form/FormSubmissionRouter.java",
    "adapter/in/event/StrajaEvents.java",
    "adapter/out/minecraft/CustodyMessageProjector.java",
}


def template_of(parts):
    out = []
    args = []
    for kind, val in parts:
        if kind == "lit":
            out.append(val)
        else:
            out.append("%s")
            args.append(val)
    return "".join(out), args


def is_complex(arg, method):
    if "?" in arg or "::" in arg or arg.count('"') == 0:
        return True
    if method != "tell":
        # nested Component.literal inside sendSystemMessage/sendFailure is
        # rewritable only when it's a bare literal with nothing appended
        stripped = re.sub(r'Component\.literal\(\s*"(?:[^"\\]|\\.)*"\s*\)', "", arg)
        return bool(re.search(r"[+?]|\.\w+\(", stripped))
    return False


def main():
    recs = json.loads((ROOT / "tools" / "refusal-census.json").read_text(encoding="utf-8"))
    plan = []
    for r in recs:
        short = r["file"].split("src/main/java/com/dwurdy/straja/")[1]
        if short not in SWEPT:
            continue
        tpl, args = template_of(r["parts"])
        plan.append({
            "site": f"{short}:{r['line']}",
            "recv": r["recv"],
            "method": r["method"],
            "arg": r["arg"],
            "template": tpl,
            "args": args,
            "complex": is_complex(r["arg"], r["method"]),
        })
    (ROOT / "tools" / "refusal-plan.json").write_text(
        json.dumps(plan, ensure_ascii=False, indent=1), encoding="utf-8")
    # unique templates that look like denials vs not
    uniq = {}
    for p in plan:
        uniq.setdefault(p["template"], []).append(p["site"])
    print(f"sites in swept files: {len(plan)}")
    print(f"unique templates: {len(uniq)}")
    print(f"complex (manual): {sum(1 for p in plan if p['complex'])}")
    with open(ROOT / "tools" / "unique-templates.txt", "w", encoding="utf-8") as f:
        for tpl, sites in sorted(uniq.items(), key=lambda kv: -len(kv[1])):
            f.write(f"[{len(sites)}x] {tpl!r}\n")


if __name__ == "__main__":
    main()
