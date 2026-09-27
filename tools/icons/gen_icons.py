#!/usr/bin/env python3
"""Generate the Straja GUI icon pack as crisp, palette-only pixel art.

The drawings intentionally use integer-aligned Pillow primitives.  Keeping the
artwork in code makes the pack reproducible and makes the palette/alpha rules
easy to audit without depending on an editor export.
"""

from __future__ import annotations

from pathlib import Path
from typing import Sequence

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parent
OUT_DIR = ROOT / "raw"
TRANSPARENT = (0, 0, 0, 0)

# The 15 colors from docs/icon-generation-prompt-pack.md.
PAPER = (0xDC, 0xD7, 0xBE, 0xFF)
PAPER_BRIGHT = (0xF0, 0xE9, 0xC9, 0xFF)
PAPER_DIM = (0xB4, 0xAF, 0x96, 0xFF)
LEATHER = (0x78, 0x50, 0x1E, 0xFF)
LEATHER_DEEP = (0x50, 0x3C, 0x1E, 0xFF)
INK = (0x28, 0x14, 0x00, 0xFF)
STEEL = (0x78, 0x78, 0x82, 0xFF)
STEEL_BRIGHT = (0xA0, 0xA0, 0xAA, 0xFF)
STEEL_DARK = (0x50, 0x50, 0x5A, 0xFF)
NIGHT = (0x14, 0x14, 0x28, 0xFF)
SEAL_RED = (0x8C, 0x28, 0x28, 0xFF)
SEAL_BRIGHT = (0xA0, 0x28, 0x28, 0xFF)
BRASS = (0xD2, 0xB4, 0x3C, 0xFF)
BRASS_BRIGHT = (0xDC, 0xBE, 0x50, 0xFF)
BRASS_DIM = (0xAA, 0x8C, 0x14, 0xFF)

PALETTE = {
    PAPER,
    PAPER_BRIGHT,
    PAPER_DIM,
    LEATHER,
    LEATHER_DEEP,
    INK,
    STEEL,
    STEEL_BRIGHT,
    STEEL_DARK,
    NIGHT,
    SEAL_RED,
    SEAL_BRIGHT,
    BRASS,
    BRASS_BRIGHT,
    BRASS_DIM,
}


def icon() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    image = Image.new("RGBA", (16, 16), TRANSPARENT)
    return image, ImageDraw.Draw(image)


def rect(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int], fill: tuple[int, int, int, int], outline: tuple[int, int, int, int] | None = None) -> None:
    draw.rectangle(box, fill=fill, outline=outline)


def poly(draw: ImageDraw.ImageDraw, points: Sequence[tuple[int, int]], fill: tuple[int, int, int, int], outline: tuple[int, int, int, int] | None = INK) -> None:
    draw.polygon(points, fill=fill)
    if outline is not None:
        draw.line([*points, points[0]], fill=outline, width=1)


def line(draw: ImageDraw.ImageDraw, points: Sequence[tuple[int, int]], fill: tuple[int, int, int, int], width: int = 1) -> None:
    draw.line(points, fill=fill, width=width)


def ellipse(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int], fill: tuple[int, int, int, int], outline: tuple[int, int, int, int] | None = None) -> None:
    draw.ellipse(box, fill=fill, outline=outline)


def ring(draw: ImageDraw.ImageDraw, outer: tuple[int, int, int, int], inner: tuple[int, int, int, int], fill: tuple[int, int, int, int], outline: tuple[int, int, int, int] = INK) -> None:
    ellipse(draw, outer, fill, outline)
    ellipse(draw, inner, TRANSPARENT)


def pixel(draw: ImageDraw.ImageDraw, x: int, y: int, fill: tuple[int, int, int, int]) -> None:
    draw.point((x, y), fill=fill)


def role_receptionist() -> Image.Image:
    image, d = icon()
    # Ticket leans against the bell.
    poly(d, [(2, 5), (5, 4), (7, 11), (4, 12)], PAPER)
    line(d, [(3, 6), (5, 6)], PAPER_BRIGHT)
    line(d, [(4, 8), (6, 8)], PAPER_DIM)
    # Bell dome, lip, wood counter and clapper.
    poly(d, [(5, 8), (5, 6), (6, 4), (8, 3), (10, 4), (11, 6), (11, 8)], BRASS)
    rect(d, (6, 5, 7, 6), BRASS_BRIGHT)
    rect(d, (4, 8, 12, 10), BRASS_BRIGHT, INK)
    line(d, [(5, 9), (11, 9)], BRASS_DIM)
    rect(d, (5, 10, 11, 12), LEATHER, INK)
    rect(d, (7, 11, 9, 12), LEATHER_DEEP)
    rect(d, (8, 12, 8, 13), INK)
    return image


def role_secretary() -> Image.Image:
    image, d = icon()
    # Sealed document under the stamp.
    poly(d, [(4, 9), (12, 9), (12, 13), (4, 13)], PAPER)
    line(d, [(5, 11), (8, 11)], PAPER_DIM)
    line(d, [(5, 12), (7, 12)], PAPER_DIM)
    # Stamp handle and rubber foot pressing down.
    rect(d, (6, 3, 10, 5), LEATHER_DEEP, INK)
    rect(d, (7, 5, 9, 8), LEATHER, INK)
    rect(d, (5, 8, 11, 10), LEATHER_DEEP, INK)
    rect(d, (6, 9, 10, 9), LEATHER)
    # Wax seal peeking out below the foot.
    poly(d, [(7, 11), (9, 10), (11, 11), (10, 13), (8, 14), (6, 12)], SEAL_RED)
    pixel(d, 8, 11, SEAL_BRIGHT)
    return image


def role_instructor() -> Image.Image:
    image, d = icon()
    # Open manual with a dark leather cover peeking beneath the pages.
    poly(d, [(2, 7), (7, 5), (8, 6), (8, 14), (3, 13)], LEATHER_DEEP)
    poly(d, [(8, 6), (9, 5), (14, 7), (13, 13), (8, 14)], LEATHER_DEEP)
    poly(d, [(2, 7), (7, 6), (8, 7), (8, 13), (3, 12)], PAPER)
    poly(d, [(8, 7), (9, 6), (14, 8), (13, 12), (8, 13)], PAPER_BRIGHT)
    line(d, [(8, 7), (8, 13)], LEATHER)
    line(d, [(4, 9), (7, 9)], PAPER_DIM)
    line(d, [(4, 10), (7, 10)], PAPER_DIM)
    line(d, [(9, 8), (12, 9)], PAPER_DIM)
    line(d, [(9, 10), (12, 11)], PAPER_DIM)
    # Pointer lies across both pages; the ink under-stroke keeps its silhouette crisp.
    line(d, [(3, 11), (13, 4)], INK, 3)
    line(d, [(3, 11), (13, 4)], LEATHER, 1)
    pixel(d, 13, 4, BRASS_BRIGHT)
    return image


def role_armorer() -> Image.Image:
    image, d = icon()
    poly(d, [(3, 5), (6, 3), (10, 3), (13, 6), (12, 10), (10, 13), (5, 12), (3, 9)], STEEL)
    poly(d, [(3, 5), (6, 3), (7, 5), (5, 8), (5, 12), (3, 9)], STEEL_DARK, None)
    line(d, [(7, 4), (11, 5), (12, 7)], STEEL_BRIGHT)
    line(d, [(5, 8), (11, 9)], BRASS, 2)
    pixel(d, 6, 8, BRASS_BRIGHT)
    pixel(d, 9, 9, BRASS_BRIGHT)
    pixel(d, 11, 9, BRASS_DIM)
    return image


def role_jailer() -> Image.Image:
    image, d = icon()
    # Short chain behind the cuffs.
    line(d, [(7, 7), (9, 9)], INK, 3)
    line(d, [(7, 7), (9, 9)], STEEL_DARK, 1)
    line(d, [(9, 9), (10, 11)], INK, 3)
    line(d, [(9, 9), (10, 11)], STEEL_BRIGHT, 1)
    # Pixel rings with transparent centers.
    ring(d, (2, 4, 8, 10), (4, 6, 6, 8), STEEL, INK)
    ring(d, (8, 7, 14, 13), (10, 9, 12, 11), STEEL_DARK, INK)
    rect(d, (3, 5, 4, 7), STEEL_BRIGHT)
    pixel(d, 9, 8, STEEL_BRIGHT)
    pixel(d, 12, 12, STEEL)
    return image


def role_archivist() -> Image.Image:
    image, d = icon()
    # Papers rise above the leather folder.
    poly(d, [(7, 3), (12, 3), (13, 11), (7, 11)], PAPER_BRIGHT)
    line(d, [(8, 5), (12, 5)], PAPER_DIM)
    line(d, [(8, 6), (12, 6)], PAPER_DIM)
    # Folder body and tab.
    poly(d, [(2, 5), (6, 5), (7, 4), (12, 4), (14, 6), (14, 13), (2, 13)], LEATHER_DEEP)
    rect(d, (3, 7, 13, 12), LEATHER, INK)
    line(d, [(4, 8), (12, 8)], LEATHER_DEEP)
    rect(d, (11, 10, 13, 12), LEATHER_DEEP)
    pixel(d, 12, 10, PAPER)
    return image


def act_assign() -> Image.Image:
    image, d = icon()
    # Red mark left on the parchment below the moving stamp.
    poly(d, [(4, 11), (7, 10), (10, 11), (11, 13), (8, 14), (5, 13)], SEAL_RED)
    pixel(d, 6, 11, SEAL_BRIGHT)
    pixel(d, 8, 13, SEAL_BRIGHT)
    # Stamp is lifted at a slight angle, with two motion pixels.
    line(d, [(4, 5), (6, 4)], LEATHER_DEEP, 2)
    rect(d, (7, 3, 10, 5), LEATHER, INK)
    poly(d, [(8, 5), (10, 6), (9, 9), (7, 8)], LEATHER_DEEP)
    poly(d, [(7, 8), (11, 9), (10, 11), (6, 10)], LEATHER)
    pixel(d, 5, 6, BRASS_DIM)
    pixel(d, 4, 7, BRASS_DIM)
    return image


def act_unassign() -> Image.Image:
    image, d = icon()
    # Chain link being cut.
    ring(d, (9, 5, 14, 12), (11, 7, 12, 10), STEEL_DARK, INK)
    pixel(d, 13, 6, STEEL_BRIGHT)
    # Bolt-cutter handles and jaws, with a dark under-stroke.
    line(d, [(6, 9), (2, 13)], INK, 3)
    line(d, [(6, 9), (2, 13)], STEEL, 1)
    line(d, [(6, 9), (5, 14)], INK, 3)
    line(d, [(6, 9), (5, 14)], STEEL_DARK, 1)
    line(d, [(6, 9), (8, 5)], INK, 3)
    line(d, [(6, 9), (8, 5)], STEEL_BRIGHT, 1)
    line(d, [(6, 9), (10, 6)], INK, 3)
    line(d, [(6, 9), (10, 6)], STEEL, 1)
    ellipse(d, (5, 8, 7, 10), STEEL_BRIGHT, INK)
    return image


def act_status() -> Image.Image:
    image, d = icon()
    # Envelope body and folded flap.
    poly(d, [(2, 5), (13, 5), (13, 12), (2, 12)], PAPER)
    poly(d, [(2, 5), (7, 9), (13, 5)], PAPER_BRIGHT)
    line(d, [(2, 12), (6, 8)], PAPER_DIM)
    line(d, [(13, 12), (9, 8)], PAPER_DIM)
    # Brass clasp at the meeting point of the flap.
    poly(d, [(7, 8), (8, 7), (9, 8), (8, 10)], BRASS)
    pixel(d, 8, 8, BRASS_BRIGHT)
    return image


def act_audit() -> Image.Image:
    image, d = icon()
    poly(d, [(3, 3), (12, 4), (13, 13), (4, 14), (2, 12), (2, 5)], LEATHER_DEEP)
    poly(d, [(4, 4), (11, 5), (12, 12), (4, 13)], PAPER)
    line(d, [(5, 6), (10, 7)], LEATHER_DEEP)
    line(d, [(5, 8), (10, 9)], STEEL_DARK)
    line(d, [(5, 10), (10, 11)], STEEL_DARK)
    line(d, [(5, 12), (8, 12)], PAPER_DIM)
    # Brass corner protector.
    poly(d, [(10, 11), (12, 11), (12, 13), (10, 13)], BRASS)
    pixel(d, 11, 11, BRASS_BRIGHT)
    return image


def act_cleanup() -> Image.Image:
    image, d = icon()
    # Faint, broken ghost outlines behind the broom.
    line(d, [(2, 8), (2, 6), (3, 5), (5, 5), (6, 7)], PAPER_DIM)
    line(d, [(2, 8), (4, 9), (5, 8)], PAPER_DIM)
    line(d, [(4, 11), (5, 9), (7, 9), (8, 11)], PAPER_DIM)
    line(d, [(5, 12), (6, 13)], PAPER_DIM)
    # Broom handle.
    line(d, [(4, 2), (11, 11)], INK, 3)
    line(d, [(4, 2), (11, 11)], LEATHER, 1)
    pixel(d, 4, 2, LEATHER_DEEP)
    # Ferrule and sweeping bristles.
    poly(d, [(9, 9), (11, 10), (10, 12), (8, 11)], BRASS_DIM)
    poly(d, [(9, 11), (12, 10), (14, 12), (13, 14), (8, 14)], PAPER_DIM)
    line(d, [(10, 12), (13, 13)], PAPER)
    line(d, [(9, 13), (12, 14)], LEATHER_DEEP)
    return image


def act_input() -> Image.Image:
    image, d = icon()
    # Paper receiving the quill stroke.
    poly(d, [(3, 6), (12, 6), (13, 13), (3, 13)], PAPER_BRIGHT)
    line(d, [(5, 9), (10, 9)], PAPER_DIM)
    line(d, [(5, 11), (8, 11)], PAPER_DIM)
    line(d, [(8, 12), (12, 12)], INK)
    # Feather and shaft.
    poly(d, [(3, 3), (5, 2), (8, 4), (10, 8), (7, 7), (5, 5)], PAPER)
    line(d, [(4, 3), (7, 7), (11, 12)], INK, 2)
    line(d, [(4, 3), (7, 7), (11, 12)], LEATHER, 1)
    line(d, [(5, 3), (7, 5)], PAPER_BRIGHT)
    pixel(d, 11, 12, INK)
    return image


def quest_active() -> Image.Image:
    image, d = icon()
    # Rolled carnet body and curled ends.
    poly(d, [(4, 4), (12, 4), (12, 12), (4, 12)], PAPER)
    poly(d, [(3, 4), (5, 4), (5, 12), (3, 11)], PAPER_DIM)
    poly(d, [(11, 4), (13, 5), (13, 11), (11, 12)], PAPER_BRIGHT)
    line(d, [(6, 6), (10, 6)], PAPER_DIM)
    line(d, [(6, 8), (10, 8)], PAPER_DIM)
    line(d, [(6, 10), (9, 10)], PAPER_DIM)
    # Brass mission band.
    rect(d, (8, 4, 9, 12), BRASS, INK)
    rect(d, (8, 5, 8, 10), BRASS_BRIGHT)
    return image


def quest_new() -> Image.Image:
    image, d = icon()
    poly(d, [(2, 5), (13, 5), (13, 12), (2, 12)], PAPER_BRIGHT)
    poly(d, [(2, 5), (7, 9), (13, 5)], PAPER)
    line(d, [(2, 12), (6, 8)], PAPER_DIM)
    line(d, [(13, 12), (9, 8)], PAPER_DIM)
    # Bright unread seal.
    poly(d, [(7, 8), (8, 7), (10, 8), (9, 10), (7, 9)], SEAL_BRIGHT)
    pixel(d, 8, 8, PAPER_BRIGHT)
    return image


def quest_done() -> Image.Image:
    image, d = icon()
    poly(d, [(3, 3), (12, 3), (12, 13), (3, 13)], PAPER)
    poly(d, [(10, 3), (12, 5), (10, 5)], PAPER_BRIGHT, None)
    line(d, [(5, 6), (9, 6)], PAPER_DIM)
    line(d, [(5, 8), (8, 8)], PAPER_DIM)
    # Red wax seal with a readable check cut into it.
    poly(d, [(8, 9), (10, 8), (12, 10), (11, 13), (8, 13), (6, 11)], SEAL_RED)
    line(d, [(7, 11), (9, 12), (11, 9)], SEAL_BRIGHT, 1)
    pixel(d, 7, 10, SEAL_BRIGHT)
    return image


def state_ok() -> Image.Image:
    image, d = icon()
    ring(d, (2, 2, 13, 13), (4, 4, 11, 11), BRASS, INK)
    # Chunky, unmistakable check mark.
    line(d, [(5, 8), (7, 10), (11, 6)], INK, 3)
    line(d, [(5, 8), (7, 10), (11, 6)], BRASS_BRIGHT, 1)
    pixel(d, 10, 6, BRASS_BRIGHT)
    return image


def state_denied() -> Image.Image:
    image, d = icon()
    ring(d, (2, 2, 13, 13), (4, 4, 11, 11), SEAL_RED, INK)
    # Chunky X in the bright wax highlight.
    line(d, [(5, 5), (10, 10)], INK, 3)
    line(d, [(10, 5), (5, 10)], INK, 3)
    line(d, [(5, 5), (10, 10)], SEAL_BRIGHT, 1)
    line(d, [(10, 5), (5, 10)], SEAL_BRIGHT, 1)
    return image


def state_warn() -> Image.Image:
    image, d = icon()
    # Alarm whistle body, mouthpiece and finger ring.
    poly(d, [(3, 7), (6, 5), (11, 5), (13, 7), (11, 10), (6, 10), (3, 9)], BRASS)
    poly(d, [(2, 6), (6, 6), (6, 9), (2, 9)], BRASS_BRIGHT)
    rect(d, (10, 6, 13, 8), BRASS_DIM, INK)
    pixel(d, 11, 6, BRASS_BRIGHT)
    ring(d, (7, 9, 10, 12), (8, 10, 9, 11), BRASS_BRIGHT, INK)
    line(d, [(7, 6), (7, 9)], BRASS_BRIGHT)
    pixel(d, 4, 7, PAPER_BRIGHT)
    return image


def panel_bg() -> Image.Image:
    size = 32
    image = Image.new("RGBA", (size, size), NIGHT)
    d = ImageDraw.Draw(image)
    # A matching 1px inset edge on every side makes the tile boundary stable.
    rect(d, (0, 0, 31, 31), NIGHT)
    line(d, [(0, 0), (31, 0), (31, 31), (0, 31), (0, 0)], LEATHER)
    # Deliberately low-contrast, short parchment grain.  It never touches the
    # edge, so the leather frame and night base tile without seams.
    grain = [
        ((3, 5), (10, 5)), ((16, 5), (21, 5)), ((25, 5), (28, 5)),
        ((5, 10), (7, 10)), ((12, 10), (18, 10)), ((22, 10), (27, 10)),
        ((2, 15), (8, 15)), ((14, 15), (17, 15)), ((22, 15), (29, 15)),
        ((4, 21), (11, 21)), ((15, 21), (23, 21)), ((26, 21), (29, 21)),
        ((3, 27), (6, 27)), ((10, 27), (18, 27)), ((22, 27), (28, 27)),
        ((8, 7), (8, 8)), ((24, 12), (24, 14)), ((11, 24), (11, 26)),
    ]
    for start, end in grain:
        line(d, [start, end], LEATHER_DEEP)
    pixel(d, 18, 13, LEATHER_DEEP)
    pixel(d, 20, 26, LEATHER_DEEP)
    return image


GENERATORS = {
    "role_receptionist.png": role_receptionist,
    "role_secretary.png": role_secretary,
    "role_instructor.png": role_instructor,
    "role_armorer.png": role_armorer,
    "role_jailer.png": role_jailer,
    "role_archivist.png": role_archivist,
    "act_assign.png": act_assign,
    "act_unassign.png": act_unassign,
    "act_status.png": act_status,
    "act_audit.png": act_audit,
    "act_cleanup.png": act_cleanup,
    "act_input.png": act_input,
    "quest_active.png": quest_active,
    "quest_new.png": quest_new,
    "quest_done.png": quest_done,
    "state_ok.png": state_ok,
    "state_denied.png": state_denied,
    "state_warn.png": state_warn,
    "panel_bg.png": panel_bg,
}


def validate(name: str, image: Image.Image) -> None:
    expected = (32, 32) if name == "panel_bg.png" else (16, 16)
    if image.size != expected:
        raise ValueError(f"{name}: expected {expected}, got {image.size}")
    if image.mode != "RGBA":
        raise ValueError(f"{name}: expected RGBA, got {image.mode}")
    for rgba in image.getdata():
        if rgba[3] not in (0, 255):
            raise ValueError(f"{name}: non-binary alpha {rgba[3]}")
        if rgba[3] == 255 and rgba not in PALETTE:
            raise ValueError(f"{name}: off-palette color {rgba}")


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for name, generator in GENERATORS.items():
        image = generator()
        validate(name, image)
        image.save(OUT_DIR / name, format="PNG")
        print(f"wrote {OUT_DIR / name}")
    print(f"generated {len(GENERATORS)} assets")


if __name__ == "__main__":
    main()
