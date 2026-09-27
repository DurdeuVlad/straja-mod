"""Generate annotated SVG wireframes for the NPC surface visual system.

Output: docs/media/mockups/*.svg  (421x240 logical, rendered x2).
Run from repo root:  python tools/mockups/gen_mockups.py
Palette tokens mirror docs/npc-surface-visual-system.md section 1.
"""

import os

P = {
    "paper": "#dcd7be", "paper_bright": "#f0e9c9", "paper_dim": "#b4af96",
    "leather": "#78501e", "leather_deep": "#503c1e", "ink": "#281400",
    "steel": "#787882", "steel_bright": "#a0a0aa", "steel_dark": "#50505a",
    "night": "#141428", "seal": "#8c2828", "seal_bright": "#a02828",
    "brass": "#d2b43c", "brass_bright": "#dcbe50", "brass_dim": "#aa8c14",
}
S = 2  # render scale
W, H = 421, 240


def esc(t):
    return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def rect(x, y, w, h, fill, stroke=None, dash=None, opacity=1.0):
    s = f'<rect x="{x*S}" y="{y*S}" width="{w*S}" height="{h*S}" fill="{fill}" fill-opacity="{opacity}"'
    if stroke:
        s += f' stroke="{stroke}" stroke-width="1"'
    if dash:
        s += ' stroke-dasharray="4 3"'
    return s + "/>"


def text(x, y, t, fill=None, size=11, anchor="start", mono=True):
    fam = "monospace" if mono else "sans-serif"
    return (f'<text x="{x*S}" y="{y*S}" fill="{fill or P["paper"]}" '
            f'font-size="{size*S}" font-family="{fam}" text-anchor="{anchor}">{esc(t)}</text>')


def icon(x, y, label, color):
    return (rect(x, y, 16, 16, P["leather_deep"], color) +
            text(x + 8, y + 12, label, P["paper_bright"], 8, "middle"))


def button(x, y, w, label, color=None, icon_label=None, disabled=False):
    c = color or P["steel"]
    parts = [rect(x, y, w, 22, P["night"], c)]
    if icon_label:
        parts.append(icon(x + 3, y + 3, icon_label, c))
        parts.append(text(x + 24, y + 15, label, P["paper_dim"] if disabled else P["paper"], 9))
    else:
        parts.append(text(x + w / 2, y + 15, label, P["paper_dim"] if disabled else P["paper"], 9, "middle"))
    return "".join(parts)


def textarea(x, y, w, h, lines):
    parts = [rect(x, y, w, h, P["ink"], P["leather"], opacity=0.55)]
    for i, ln in enumerate(lines):
        parts.append(text(x + 4, y + 13 + i * 13, ln, P["paper"], 9))
    return "".join(parts)


def scrollpanel(x, y, w, h, label):
    return (rect(x, y, w, h, "none", P["steel"], dash=True) +
            rect(x + w - 10, y, 10, h, P["steel_dark"], P["steel"]) +
            text(x + 2, y - 4, label, P["steel_bright"], 8))


def dim(x1, y1, x2, y2, label):
    return (f'<line x1="{x1*S}" y1="{y1*S}" x2="{x2*S}" y2="{y2*S}" stroke="{P["brass_dim"]}" stroke-width="1"/>' +
            text((x1 + x2) / 2, min(y1, y2) - 2, label, P["brass_bright"], 8, "middle"))


def header(title, icon_label, note=""):
    return (rect(0, 0, W, H, P["night"]) +
            icon(12, 8, icon_label, P["brass"]) +
            text(32, 20, title, P["paper_bright"], 11) +
            rect(12, 30, 396, 1, P["leather"]) +
            (text(408, 20, note, P["paper_dim"], 8, "end") if note else ""))


def svg(body):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W*S}" height="{H*S}" '
            f'viewBox="0 0 {W*S} {H*S}"><style>rect{{shape-rendering:crispEdges}}</style>' +
            body + "</svg>")


def panel_frame():
    return rect(1, 1, W - 2, H - 2, "none", P["leather_deep"])


SURFACES = {
    "surface-role": header("Receptionist — Straja desk", "R", "quest glyphs right") +
        textarea(12, 34, 396, 52, [
            "The receptionist stamps your carnet and", "waves you toward the hall. (body, 62ch wrap)"]) +
        scrollpanel(12, 92, 195, 112, "choices scroll (12,92,195,112)") +
        text(16, 104, "Node text wraps at 30ch…", P["paper"], 9) +
        button(12, 118, 190, "Request identity card", P["brass"], "◆") +
        button(12, 143, 190, "Ask about duties", P["steel"], "◆") +
        button(12, 168, 190, "Leave", P["steel"], "◆") +
        text(218, 100, "Quest journal", P["paper_bright"], 10) +
        icon(218, 106, "N", P["brass"]) + text(240, 118, "Admission file — ACTIVE", P["paper"], 8) +
        icon(218, 128, "D", P["steel"]) + text(240, 140, "First patrol — DONE", P["paper_dim"], 8) +
        button(12, 214, 190, "Close", P["steel"]) +
        panel_frame() + dim(12, 236, 408, 236, "421x240 (was 320 — compressed)"),
    "surface-input": header("Admission quiz", "Q", "question restated") +
        textarea(12, 34, 396, 40, ["Question: Câte minute ai pentru a ajunge",
                                    "la următorul checkpoint? (scrie: 30)"]) +
        rect(12, 84, 396, 22, "#000", P["steel_bright"]) +
        text(16, 99, "30|", P["paper"], 10) +
        text(12, 122, "answer field — focus ring steel-bright", P["paper_dim"], 8) +
        button(12, 214, 190, "Submit", P["brass"], "✓") +
        button(218, 214, 190, "Cancel", P["steel"]) +
        panel_frame(),
    "surface-selector": header("Assign profile — NPC 8f3a…", "W", "paged") +
        text(12, 44, "Current: (none)", P["paper_dim"], 9) +
        scrollpanel(12, 52, 396, 152, "profile rows scroll (12,52,396,152)") +
        "".join(button(16, 58 + i * 26, 360, n, P["steel"], "◆")
                for i, n in enumerate(["Instructor — admission quiz", "Receptionist — front desk",
                                        "Jailer — custody ops", "Archivist — records", "Armorer — duty kit"])) +
        button(12, 185, 120, "Previous", P["steel"]) +
        text(210, 199, "Page 1 / 2", P["paper_dim"], 9, "middle") +
        button(276, 185, 132, "Next", P["steel"]) +
        button(12, 214, 120, "Status", P["steel"], "i") +
        button(138, 214, 132, "Re-sync profile", P["steel"], "r") +
        button(276, 214, 132, "Unassign", P["seal"], "x") +
        panel_frame(),
    "surface-confirm": header("Confirm profile for NPC 8f3a…", "✓", "") +
        text(12, 48, "Profile: straja.instructor.admission", P["paper"], 9) +
        text(12, 62, "Replacing: (none)", P["paper_dim"], 9) +
        textarea(12, 76, 396, 60, ["Reference rows render in a TextArea —",
                                    "labels inside scroll regions draw blank.",
                                    "(kept from PR #179 fix)"]) +
        button(12, 150, 190, "Assign profile", P["brass"], "✓") +
        button(218, 150, 190, "Cancel", P["steel"]) +
        panel_frame(),
    "surface-unassign": header("Unassign profile — NPC 8f3a…", "x", "destructive") +
        text(12, 48, "Profile: straja.jailer.custody", P["paper"], 9) +
        text(12, 62, "Custody flags and role state are released.", P["paper_dim"], 9) +
        button(12, 150, 190, "Unassign profile", P["seal"], "x") +
        button(218, 150, 190, "Cancel", P["steel"]) +
        panel_frame(),
    "surface-duplicates": header("Duplicate bindings for NPC 8f3a…", "C", "") +
        textarea(12, 34, 396, 40, ["Two profiles claim this NPC. Keep one;",
                                    "the other binding is released."]) +
        scrollpanel(12, 80, 396, 100, "duplicate rows (12,80,396,100)") +
        button(16, 86, 360, "Keep: instructor (set by ci_straja, 09-27)", P["brass"], "◆") +
        button(16, 112, 360, "Keep: jailer (set by console, 09-26)", P["steel"], "◆") +
        button(218, 214, 190, "Back", P["steel"]) +
        panel_frame(),
    "surface-status": header("NPC assignment status", "i", "") +
        textarea(12, 34, 396, 172, [
            "Provider    : customnpcs (reflective)",
            "NPC         : 8f3a…e1", "Profile     : straja.jailer.custody",
            "Lifecycle   : BOUND", "Set by      : ci_straja @ 2026-09-27",
            "Projection  : surface ok · tokens ok",
            "", "rows read-only; icon header replaces plain label"]) +
        button(12, 214, 190, "Audit history", P["steel"], "a") +
        button(218, 214, 190, "Back", P["steel"]) +
        panel_frame(),
    "surface-audit": header("Provisioning audit", "a", "") +
        textarea(12, 34, 396, 172, [
            "10:41  ASSIGN instructor   ACCEPTED  ok",
            "10:42  ASSIGN jailer       ACCEPTED  ok",
            "10:44  UNASSIGN jailer     ACCEPTED  ok",
            "10:47  ASSIGN instructor   DENIED    no wand",
            "10:52  ASSIGN receptionist ACCEPTED  ok"]) +
        button(12, 214, 190, "Back to status", P["steel"]) +
        panel_frame(),
    "surface-result": header("Provisioning — DENIED", "!", "denied state") +
        icon(12, 40, "✕", P["seal_bright"]) +
        text(36, 46, "NPC Tool permission and held wand are", P["seal_bright"], 9) +
        text(36, 58, "required for this action.", P["seal_bright"], 9) +
        text(12, 84, "Wrapped message lines; severity icon left, no under-panel text.", P["paper_dim"], 8) +
        button(12, 214, 190, "Back", P["steel"]) +
        panel_frame(),
    "screen-native-form": header("Straja form (native screen)", "F", "vanilla path") +
        text(12, 44, "Question: Câte minute…", P["paper"], 9) +
        rect(12, 52, 396, 22, "#000", P["steel_bright"]) +
        text(16, 67, "answer…", P["paper_dim"], 10) +
        button(12, 214, 190, "Submit", P["brass"]) +
        button(218, 214, 190, "Cancel", P["steel"]) +
        panel_frame() +
        text(12, 236, "vanilla AbstractContainerScreen — themed header only", P["steel"], 8),
}

out = "docs/media/mockups"
os.makedirs(out, exist_ok=True)
for name, body in SURFACES.items():
    with open(os.path.join(out, name + ".svg"), "w", encoding="utf-8") as f:
        f.write(svg(body))
    print("wrote", name + ".svg")
