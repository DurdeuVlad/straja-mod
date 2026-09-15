#!/usr/bin/env python3
"""Minimal gzipped-NBT writer for CI fixtures.

Only what the scenario fixtures need: a named compound root containing
string/int tags and nested compounds. Written out big-endian, gzipped —
the exact layout KubeJsNbtReader and vanilla SavedData expect.
"""
import gzip
import struct

TAG_END = 0
TAG_BYTE = 1
TAG_INT = 3
TAG_LONG = 4
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10


def _utf(s: str) -> bytes:
    data = s.encode("utf-8")
    return struct.pack(">H", len(data)) + data


def _tag(name: str, tag_id: int, payload: bytes) -> bytes:
    return bytes([tag_id]) + _utf(name) + payload


def _compound(entries: dict) -> bytes:
    out = b""
    for key, val in entries.items():
        out += _entry(key, val)
    return out + bytes([TAG_END])


def _entry(key: str, val) -> bytes:
    if isinstance(val, str):
        return _tag(key, TAG_STRING, _utf(val))
    if isinstance(val, bool):
        return _tag(key, TAG_BYTE, bytes([1 if val else 0]))
    if isinstance(val, int):
        return _tag(key, TAG_INT, struct.pack(">i", val))
    if isinstance(val, dict):
        return _tag(key, TAG_COMPOUND, _compound(val))
    raise TypeError(f"unsupported NBT value for {key!r}: {type(val)}")


def write_gzip_nbt(path: str, root: dict, root_name: str = ""):
    """Write {root_name: compound(root)} gzipped to path."""
    body = bytes([TAG_COMPOUND]) + _utf(root_name) + _compound(root)
    with open(path, "wb") as fh:
        fh.write(gzip.compress(body))


def write_store_dat(path: str, store_keys: dict, data_version: int = 3955):
    """Write a Straja SavedData .dat. JsonStore.save() nests the payload under
    a ``straja`` key inside ``data``, so the on-disk layout is
    {data: {straja: {...}}, DataVersion: int} — anything else loads as an
    empty store."""
    write_gzip_nbt(path, {"data": {"straja": dict(store_keys)},
                          "DataVersion": data_version})


def write_kubejs(path: str, entries: dict):
    """Write a server-level kubejs_persistent_data.nbt. MigrationService reads
    flat top-level string keys (``straja_setup`` etc.); the
    ``KubeJSPersistentData.`` prefix applies only to per-player .dat files."""
    write_gzip_nbt(path, dict(entries))
