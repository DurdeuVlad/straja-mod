#!/usr/bin/env python3
"""Generate the empty GameTest structure template.

The GameTest runtime requires a real structure file for every registered
test. Rather than committing a binary fixture, the empty template is written
here in code: an air-only region ``data/straja/structure/empty.nbt`` inside
the directory passed as argv[1] (the Gradle gameTest generated-resources
directory).
"""

import gzip
import struct
import sys
from pathlib import Path

# Generous air volume: tests only use it as a safe spawn/protection region.
SIZE = (8, 6, 8)
# SharedConstants data version for Minecraft 1.21.1.
DATA_VERSION = 3955
NAMESPACE = "straja"
TEMPLATE = "empty"

TAG_END = 0
TAG_INT = 3
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11


def _tag(tag_type: int, name: str) -> bytes:
    encoded = name.encode("utf-8")
    return bytes([tag_type]) + struct.pack(">H", len(encoded)) + encoded


def _string(name: str, value: str) -> bytes:
    encoded = value.encode("utf-8")
    return _tag(TAG_STRING, name) + struct.pack(">H", len(encoded)) + encoded


def _int(name: str, value: int) -> bytes:
    return _tag(TAG_INT, name) + struct.pack(">i", value)


def _int_array(name: str, values) -> bytes:
    return (
        _tag(TAG_INT_ARRAY, name)
        + struct.pack(">i", len(values))
        + struct.pack(f">{len(values)}i", *values)
    )


def _list(name: str, element_type: int, elements) -> bytes:
    return (
        _tag(TAG_LIST, name)
        + bytes([element_type])
        + struct.pack(">i", len(elements))
        + b"".join(elements)
    )


def build_template() -> bytes:
    """Minimal ``StructureTemplate.save`` payload: a single-air palette with
    no explicit blocks or entities, so the whole region stays air."""
    palette_entry = _string("Name", "minecraft:air") + bytes([TAG_END])
    root = (
        _int_array("size", SIZE)
        + _list("palette", TAG_COMPOUND, [palette_entry])
        + _list("blocks", TAG_COMPOUND, [])
        + _list("entities", TAG_COMPOUND, [])
        + _int("DataVersion", DATA_VERSION)
        + bytes([TAG_END])
    )
    return _tag(TAG_COMPOUND, "") + root


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: gen_gametest_structure.py <output-dir>", file=sys.stderr)
        return 2
    output_dir = Path(sys.argv[1]) / "data" / NAMESPACE / "structure"
    output_dir.mkdir(parents=True, exist_ok=True)
    target = output_dir / f"{TEMPLATE}.nbt"
    target.write_bytes(gzip.compress(build_template()))
    print(f"wrote {target}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
