#!/usr/bin/env python3
"""Normalize/verify Straja GUI icon assets against the M0 asset contract.

Contract (docs/npc-surface-visual-system.md + icon-generation-prompt-pack.md):
  * icons:   16x16 PNG, binary alpha {0,255}, palette colors only
  * panel tile: 32x32 PNG, same rules (name contains 'panel_bg')

Usage:
  python normalize.py SRC_DIR --out DST_DIR   # snap colors to palette, write
  python normalize.py --check DIR             # verify only, exit 1 on failure
"""
import sys
from pathlib import Path

from PIL import Image

PALETTE = {
    0xDCD7BE, 0xF0E9C9, 0xB4AF96,  # paper, paper-bright, paper-dim
    0x78501E, 0x503C1E, 0x281400,  # leather, leather-deep, ink
    0x787882, 0xA0A0AA, 0x50505A,  # steel, steel-bright, steel-dark
    0x141428,                       # night
    0x8C2828, 0xA02828,             # seal-red, seal-bright
    0xD2B43C, 0xDCBE50, 0xAA8C14,  # brass, brass-bright, brass-dim
}


def _rgb(pixel):
    return (pixel[0] << 16) | (pixel[1] << 8) | pixel[2]


def _nearest(color):
    r, g, b = color >> 16, (color >> 8) & 0xFF, color & 0xFF
    return min(PALETTE, key=lambda p: ((p >> 16) - r) ** 2 + (((p >> 8) & 0xFF) - g) ** 2 + ((p & 0xFF) - b) ** 2)


def expected_size(name):
    return 32 if "panel_bg" in name else 16


def normalize(path, out=None, check=False):
    """Returns list of violations (empty = clean)."""
    problems = []
    img = Image.open(path).convert("RGBA")
    size = expected_size(path.stem)
    if img.size != (size, size):
        problems.append(f"size {img.size[0]}x{img.size[1]}, expected {size}x{size}")
    px = img.load()
    off_palette = {}
    bad_alpha = 0
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            if a != 255:
                bad_alpha += 1
            c = _rgb((r, g, b))
            if c not in PALETTE:
                off_palette[c] = off_palette.get(c, 0) + 1
            if not check:
                if a != 255:
                    a = 255
                if c not in PALETTE:
                    n = _nearest(c)
                    r, g, b = n >> 16, (n >> 8) & 0xFF, n & 0xFF
                px[x, y] = (r, g, b, a)
    if bad_alpha:
        problems.append(f"{bad_alpha} pixels with non-binary alpha")
    if off_palette:
        worst = sorted(off_palette.items(), key=lambda kv: -kv[1])[:5]
        detail = ", ".join(f"#{c:06x}x{n}" for c, n in worst)
        problems.append(f"{sum(off_palette.values())} off-palette pixels ({detail})")
    if out and not check:
        img.save(out)
    return problems


def main():
    args = sys.argv[1:]
    check = "--check" in args
    args = [a for a in args if a != "--check"]
    out_dir = None
    if "--out" in args:
        i = args.index("--out")
        out_dir = Path(args[i + 1])
        args = args[:i] + args[i + 2:]
        out_dir.mkdir(parents=True, exist_ok=True)
    src = Path(args[0])
    files = sorted(src.glob("*.png")) if src.is_dir() else [src]
    if not files:
        print(f"no PNGs in {src}")
        return 1
    failures = 0
    for f in files:
        out = out_dir / f.name if out_dir else None
        problems = normalize(f, out=out, check=check)
        status = "OK  " if not problems else ("FAIL" if check else "FIX ")
        print(f"{status} {f.name}" + ("" if not problems else f"  -> {'; '.join(problems)}"))
        failures += bool(problems and check)
    if check and failures:
        print(f"\n{failures}/{len(files)} file(s) violate the asset contract")
        return 1
    if out_dir:
        print(f"\nwrote {len(files)} normalized file(s) to {out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
