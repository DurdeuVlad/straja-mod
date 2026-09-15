#!/usr/bin/env python3
"""Straja release contract: version/tag validation and artifact selection.

Dependency-free on purpose so the fast CI job can run it before any
credentials are read. It enforces:

* ``mod_version`` in ``gradle.properties`` is declared exactly once and uses
  bare semver ``X.Y.Z``. It is the single source of truth.
* Stable release tags are ``v<mod_version>`` and release-candidate tags are
  ``v<mod_version>-rc.N`` with N >= 1. Anything else fails closed.
* Exactly one unclassifiered ``<mod_id>-<mod_version>.jar`` exists in the libs
  directory. Source/dev/test/GameTest JARs are never distributable, and any
  unexpected ``<mod_id>-*.jar`` fails the contract instead of being guessed at.
* The selected JAR embeds ``META-INF/neoforge.mods.toml`` whose ``modId`` and
  ``version`` agree with ``mod_id``/``mod_version``, and it contains no
  test/GameTest classes or ``.java`` sources.
"""

from __future__ import annotations

import argparse
import hashlib
import re
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path

SEMVER_RE = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")
MOD_ID_RE = re.compile(r"^[a-z][a-z0-9_]{1,63}$")
STABLE_TAG_RE = re.compile(r"^v((0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*))$")
RC_TAG_RE = re.compile(
    r"^v((0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*))-rc\.([1-9]\d*)$"
)

# Entries that must never appear in the distributable JAR: GameTest classes
# live in a separate source set and .java sources never ship. Note that
# com/dwurdy/straja/adapter/in/test/ IS production code (the virtual-player
# harness behind /straja test), so a package merely named "test" is allowed.
FORBIDDEN_ENTRY_RE = re.compile(r"(^|/)gametests?/", re.IGNORECASE)


class ContractError(Exception):
    """A release-contract violation. Always fails closed."""


@dataclass(frozen=True)
class Tag:
    kind: str  # "stable" or "rc"
    version: str
    rc: int | None = None


def read_gradle_properties(path: Path) -> dict[str, str]:
    """Parse gradle.properties, failing on duplicate or malformed keys."""
    props: dict[str, str] = {}
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise ContractError(f"cannot read {path}: {exc}") from exc
    for lineno, raw in enumerate(lines, start=1):
        line = raw.strip()
        if not line or line.startswith("#") or line.startswith("!"):
            continue
        if "=" not in line and ":" not in line:
            raise ContractError(f"{path}:{lineno}: malformed property line: {raw!r}")
        key, value = re.split(r"[=:]", line, maxsplit=1)
        key = key.strip()
        if key in props:
            raise ContractError(f"{path}:{lineno}: duplicate property {key!r}")
        props[key] = value.strip()
    return props


def require_property(props: dict[str, str], key: str, pattern: re.Pattern) -> str:
    value = props.get(key)
    if value is None or value == "":
        raise ContractError(f"gradle.properties is missing required property {key!r}")
    if not pattern.match(value):
        raise ContractError(
            f"gradle.properties property {key!r}={value!r} does not match {pattern.pattern}"
        )
    return value


def require_mod_version(props: dict[str, str]) -> str:
    return require_property(props, "mod_version", SEMVER_RE)


def classify_tag(tag: str) -> Tag:
    """Classify a release tag; raises ContractError for anything malformed."""
    match = STABLE_TAG_RE.match(tag)
    if match:
        return Tag(kind="stable", version=match.group(1))
    match = RC_TAG_RE.match(tag)
    if match:
        return Tag(kind="rc", version=match.group(1), rc=int(match.group(5)))
    raise ContractError(
        f"malformed release tag {tag!r}: expected v<mod_version> or "
        "v<mod_version>-rc.N with N >= 1"
    )


def validate_tag(tag: str, mod_version: str) -> Tag:
    """Return the classified tag if it agrees with mod_version, else fail."""
    parsed = classify_tag(tag)
    if parsed.version != mod_version:
        raise ContractError(
            f"release tag {tag!r} implies version {parsed.version!r} but "
            f"gradle.properties declares mod_version={mod_version!r}"
        )
    return parsed


def select_distributable(libs_dir: Path, mod_id: str, mod_version: str) -> Path:
    """Return the single distributable JAR, rejecting every ambiguous case."""
    if not libs_dir.is_dir():
        raise ContractError(f"libs directory {libs_dir} does not exist")
    jars = sorted(libs_dir.glob("*.jar"))
    expected_name = f"{mod_id}-{mod_version}.jar"
    candidates = [p for p in jars if p.name == expected_name]
    unexpected = [
        p for p in jars if p.name.startswith(f"{mod_id}-") and p.name != expected_name
    ]
    if unexpected:
        names = ", ".join(p.name for p in unexpected)
        raise ContractError(
            f"unexpected {mod_id} archive(s) in {libs_dir}: {names}. "
            "Source/dev/test/GameTest JARs are not distributable and must not "
            "share the mod archives name."
        )
    if not candidates:
        names = ", ".join(p.name for p in jars) or "<none>"
        raise ContractError(
            f"expected exactly one distributable {expected_name} in {libs_dir}, "
            f"found JARs: {names}"
        )
    return candidates[0]


def _read_mods_section(mods_toml: str) -> str:
    match = re.search(
        r"(?ms)^\s*\[\[mods\]\][^\n]*\n(.*?)(?=^\s*\[\[|\Z)", mods_toml
    )
    if not match:
        raise ContractError("neoforge.mods.toml has no [[mods]] section")
    return match.group(1)


def jar_mod_metadata(jar_path: Path) -> tuple[str, str]:
    """Return (modId, version) from the JAR's neoforge.mods.toml."""
    try:
        with zipfile.ZipFile(jar_path) as jar:
            try:
                text = jar.read("META-INF/neoforge.mods.toml").decode("utf-8")
            except KeyError as exc:
                raise ContractError(
                    f"{jar_path.name} is missing META-INF/neoforge.mods.toml"
                ) from exc
    except zipfile.BadZipFile as exc:
        raise ContractError(f"{jar_path.name} is not a readable JAR: {exc}") from exc
    section = _read_mods_section(text)
    mod_id = re.search(r'(?m)^\s*modId\s*=\s*"([^"]+)"', section)
    version = re.search(r'(?m)^\s*version\s*=\s*"([^"]+)"', section)
    if not mod_id or not version:
        raise ContractError(
            f"{jar_path.name} neoforge.mods.toml [[mods]] lacks modId/version"
        )
    return mod_id.group(1), version.group(1)


def forbidden_entries(jar_path: Path) -> list[str]:
    """List JAR entries that must never ship in the distributable."""
    try:
        with zipfile.ZipFile(jar_path) as jar:
            names = jar.namelist()
    except zipfile.BadZipFile as exc:
        raise ContractError(f"{jar_path.name} is not a readable JAR: {exc}") from exc
    return [
        n for n in names if n.endswith(".java") or FORBIDDEN_ENTRY_RE.search(n)
    ]


def inspect_jar(jar_path: Path, mod_id: str, mod_version: str) -> None:
    bad = forbidden_entries(jar_path)
    if bad:
        preview = ", ".join(bad[:10])
        raise ContractError(
            f"{jar_path.name} contains {len(bad)} non-distributable entr"
            f"{'y' if len(bad) == 1 else 'ies'} (test/GameTest classes or "
            f"sources): {preview}"
        )
    jar_mod_id, jar_version = jar_mod_metadata(jar_path)
    if jar_mod_id != mod_id:
        raise ContractError(
            f"{jar_path.name} declares modId={jar_mod_id!r}, expected {mod_id!r}"
        )
    if jar_version != mod_version:
        raise ContractError(
            f"{jar_path.name} declares version={jar_version!r} in "
            f"neoforge.mods.toml but gradle.properties declares {mod_version!r}"
        )


def write_digests(jar_path: Path, out_dir: Path) -> list[Path]:
    """Write sha256sum-style digest files for the JAR; return their paths."""
    out_dir.mkdir(parents=True, exist_ok=True)
    data = jar_path.read_bytes()
    written = []
    for algo in ("sha256", "sha512"):
        digest = hashlib.new(algo, data).hexdigest()
        dest = out_dir / f"{jar_path.name}.{algo}"
        dest.write_text(f"{digest}  {jar_path.name}\n", encoding="utf-8")
        written.append(dest)
    return written


def _cmd_version(args: argparse.Namespace) -> str:
    props = read_gradle_properties(args.properties)
    mod_version = require_mod_version(props)
    if not args.tag:
        return f"mod_version={mod_version} (non-tag ref; no tag validation needed)"
    parsed = validate_tag(args.tag, mod_version)
    if parsed.kind == "rc":
        return f"release candidate tag {args.tag} matches mod_version={mod_version}"
    return f"stable tag {args.tag} matches mod_version={mod_version}"


def _cmd_artifact(args: argparse.Namespace) -> str:
    props = read_gradle_properties(args.properties)
    mod_id = require_property(props, "mod_id", MOD_ID_RE)
    mod_version = require_mod_version(props)
    jar = select_distributable(args.libs_dir, mod_id, mod_version)
    inspect_jar(jar, mod_id, mod_version)
    out_dir = args.checksum_dir or jar.parent
    digests = write_digests(jar, out_dir)
    lines = [f"distributable artifact: {jar.name}"]
    lines += [f"wrote {p.name}: {p.read_text().split()[0]}" for p in digests]
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    p_version = sub.add_parser("version", help="validate mod_version and optional tag")
    p_version.add_argument("--properties", type=Path, required=True)
    p_version.add_argument("--tag", default="", help="git tag name, if any")
    p_version.set_defaults(func=_cmd_version)

    p_artifact = sub.add_parser(
        "artifact", help="select and inspect the distributable JAR"
    )
    p_artifact.add_argument("--properties", type=Path, required=True)
    p_artifact.add_argument("--libs-dir", type=Path, required=True)
    p_artifact.add_argument(
        "--checksum-dir",
        type=Path,
        default=None,
        help="where to write .sha256/.sha512 files (default: alongside the JAR)",
    )
    p_artifact.set_defaults(func=_cmd_artifact)

    args = parser.parse_args(argv)
    try:
        result = args.func(args)
    except ContractError as exc:
        print(f"CONTRACT FAIL: {exc}", file=sys.stderr)
        return 1
    print(f"CONTRACT OK: {result}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
