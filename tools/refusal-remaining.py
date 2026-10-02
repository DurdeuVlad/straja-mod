"""List every remaining literal tell/sendSystemMessage/sendFailure in swept files."""
import re
import sys
import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location("rc", Path("tools/refusal-census.py"))
rc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rc)

SWEPT = [f"application/service/{n}.java" for n in (
    "AdminService ArchiveService ArmoryService AudienceService BoloService "
    "ComplaintService CustodyService EmergencyService EvidenceService "
    "FineService GuardService IdentityCardService IncidentService "
    "MissionService PrisonService ReportService RoomService "
    "RpExpansionService SecretaryService ArrestRecordService "
    "AdminToolService").split()] + [
    "adapter/in/command/StrajaCommands.java",
    "adapter/in/event/StrajaEvents.java",
    "adapter/in/form/FormSubmissionRouter.java",
    "adapter/in/npc/NpcRoles.java",
]

NESTED = re.compile(r'Component\s*\.\s*literal\(\s*"((?:[^"\\]|\\.)*)"\s*\)')

out = []
for rel in SWEPT:
    p = Path("src/main/java/com/dwurdy/straja") / rel
    src = p.read_text(encoding="utf-8")
    for m in rc.CALL_RE.finditer(src):
        try:
            end = rc.find_call_end(src, m.end() - 1)
        except ValueError:
            continue
        arg = src[m.end():end]
        parts = rc.split_concat(arg)
        lits = [t for k, t in parts if k == "lit"]
        if not lits:
            lits = [rc.decode_java(s) for s in NESTED.findall(arg)]
            if not lits:
                continue
        text = "".join(lits)
        line = src.count("\n", 0, m.start()) + 1
        deny = "DENY" if rc.DENIAL_RE.search(text) else "info"
        out.append(f"{rel}:{line}\t{deny}\t{text[:120]}")

Path("tools/remaining.txt").write_text("\n".join(out), encoding="utf-8")
print(f"{len(out)} literal sites")
