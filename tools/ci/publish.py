#!/usr/bin/env python3
"""Straja release publication and stable promotion.

Dependency-free engine behind the RC beta publish and the stable promotion
workflows. Every external mutation is query-before-create and hash-verified:

* Modrinth: ``GET /v2/project/{id}/version`` finds an existing version; the
  same version number with the same sha256 is a no-op, a different sha256
  fails closed. Stable promotion PATCHes the matching RC version's metadata
  (``version_number``/``name``/``version_type``) so the published file —
  and therefore its hash — is retained byte-identical.
* CurseForge: its upload API has no metadata-promotion endpoint, so the
  exact already-tested bytes are uploaded with ``releaseType`` matching the
  phase. Idempotency is by file hash (CurseForge records md5+sha1, never
  sha256): a matching hash on an existing file is a no-op.
* GitHub Releases is the ledger and is always finalized last: RC publishes a
  prerelease carrying the JAR, digests, evidence manifest, SBOM, and ledger;
  stable creates the release marked latest only after external targets are
  verified.

Secrets arrive via environment variables and are never logged or written to
the ledger. ``--dry-run`` performs every read and local validation but skips
every mutation.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field

MODRINTH_API = "https://api.modrinth.com/v2"
CURSEFORGE_API = "https://api.curseforge.com/v1"
CURSEFORGE_GAME_ID = 432  # Minecraft
USER_AGENT = "straja-mod release pipeline (github.com/DurdeuVlad/straja-mod)"

RC_TAG_RE = re.compile(r"^v((0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*))-rc\.([1-9]\d*)$")
STABLE_TAG_RE = re.compile(r"^v((0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*))$")


class PublishError(Exception):
    """Any publication/promotion failure. Always fails closed."""


@dataclass
class LedgerEntry:
    platform: str
    remote_id: str = ""
    url: str = ""
    expected_sha256: str = ""
    observed_sha256: str = ""
    state: str = "pending"  # published|promoted|noop|failed|dry-run
    detail: str = ""

    def as_dict(self) -> dict:
        return {"platform": self.platform, "id": self.remote_id,
                "url": self.url, "expected_sha256": self.expected_sha256,
                "observed_sha256": self.observed_sha256,
                "state": self.state, "detail": self.detail}


@dataclass
class Ledger:
    entries: list = field(default_factory=list)

    def add(self, entry: LedgerEntry) -> LedgerEntry:
        self.entries.append(entry)
        return entry

    def as_dict(self) -> dict:
        return {"entries": [e.as_dict() for e in self.entries],
                "generated_at": int(time.time())}

    def write(self, path: str):
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(self.as_dict(), fh, indent=2)


# ---------------------------------------------------------------------------
# helpers

def sha256_of(path: str) -> str:
    return hashlib.sha256(_read(path)).hexdigest()


def _read(path: str) -> bytes:
    with open(path, "rb") as fh:
        return fh.read()


def _http(url: str, *, token: str = None, token_header: str = "Authorization",
          token_prefix: str = "Bearer", method: str = "GET",
          body: bytes = None, headers: dict = None, timeout: int = 60):
    """Minimal JSON HTTP helper. Raises PublishError on HTTP errors; the
    response body is decoded JSON (or raw text when not JSON)."""
    req_headers = {"User-Agent": USER_AGENT, "Accept": "application/json"}
    if headers:
        req_headers.update(headers)
    if token:
        req_headers[token_header] = f"{token_prefix} {token}"
    req = urllib.request.Request(url, data=body, method=method,
                                 headers=req_headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
    except urllib.error.HTTPError as exc:
        raise PublishError(
            f"{method} {url} -> HTTP {exc.code}: "
            f"{exc.read()[:300].decode('utf-8', 'replace')}") from exc
    except urllib.error.URLError as exc:
        raise PublishError(f"{method} {url} -> {exc.reason}") from exc
    try:
        return json.loads(raw)
    except (json.JSONDecodeError, UnicodeDecodeError):
        return raw.decode("utf-8", "replace")


def _multipart(fields: dict, file_field: str, file_path: str) -> tuple:
    """Build a multipart/form-data body: one JSON 'data'-style field per
    dict entry plus the file part. Returns (body, content_type)."""
    boundary = f"----straja{int(time.time() * 1000)}"
    out = bytearray()
    for name, value in fields.items():
        out += f'--{boundary}\r\nContent-Disposition: form-data; ' \
               f'name="{name}"\r\n\r\n{value}\r\n'.encode()
    fname = os.path.basename(file_path)
    out += f'--{boundary}\r\nContent-Disposition: form-data; ' \
           f'name="{file_field}"; filename="{fname}"\r\n' \
           f'Content-Type: application/java-archive\r\n\r\n'.encode()
    out += _read(file_path)
    out += f"\r\n--{boundary}--\r\n".encode()
    return bytes(out), f"multipart/form-data; boundary={boundary}"


def _env(name: str, required: bool = True) -> str:
    value = os.environ.get(name, "")
    if required and not value:
        raise PublishError(f"missing environment variable {name}")
    return value


# ---------------------------------------------------------------------------
# evidence + sbom

def build_evidence(tag: str, commit: str, version: str, jar: str,
                   sha256: str, sha512: str, gates: dict,
                   run_id: str = "", run_url: str = "") -> dict:
    """The release manifest stored as a prerelease asset; stable promotion
    refuses to proceed without one whose gates are all green."""
    match = RC_TAG_RE.match(tag) or STABLE_TAG_RE.match(tag)
    if not match:
        raise PublishError(f"tag {tag!r} is not a valid release tag")
    if match.group(1) != version:
        raise PublishError(
            f"tag version {match.group(1)} != mod_version {version}")
    if not jar.endswith(f"-{version}.jar"):
        raise PublishError(f"jar {jar!r} does not embed version {version}")
    blocking = {k: v for k, v in gates.items() if not k.endswith("_advisory")}
    advisory = {k: v for k, v in gates.items() if k.endswith("_advisory")}
    return {
        "schema": 1, "tag": tag, "version": version, "commit": commit,
        "jar": jar, "sha256": sha256, "sha512": sha512,
        "built_at": int(time.time()),
        "gates": gates,
        "gates_green": all(v == "success" for v in blocking.values())
            and bool(blocking),
        "advisory": advisory,
        "run_id": run_id, "run_url": run_url,
    }


def build_sbom(version: str, server_manifest: dict) -> dict:
    """Minimal CycloneDX document listing every dependency the profiles
    actually ran against — honest scope: test-profile deps, not a full
    transitive Gradle resolution."""
    components = [{"type": "application", "name": "neoforge",
                   "version": server_manifest.get("neoforge", {}).get("version", ""),
                   "purl": f"pkg:maven/net.neoforged/neoforge@{server_manifest.get('neoforge', {}).get('version', '')}"}]
    for name, dep in sorted(server_manifest.get("dependencies", {}).items()):
        components.append({"type": "library", "name": name,
                           "version": dep.get("version", ""),
                           "purl": dep.get("url", "")})
    return {"bomFormat": "CycloneDX", "specVersion": "1.5",
            "version": 1,
            "metadata": {"component": {"type": "library", "name": "straja",
                                       "version": version}},
            "components": components}


# ---------------------------------------------------------------------------
# GitHub Releases (via gh CLI — handles the upload host + auth uniformly)

def _gh(args: list, token: str = None) -> str:
    env = dict(os.environ)
    if token:
        env["GH_TOKEN"] = token
    proc = subprocess.run(["gh"] + args, capture_output=True, text=True,
                          env=env, timeout=120)
    if proc.returncode != 0:
        raise PublishError(f"gh {' '.join(args[:3])} failed: "
                           f"{proc.stderr.strip()[:300]}")
    return proc.stdout


def gh_release(repo: str, tag: str, token: str = None):
    """Return the release object for tag, or None."""
    try:
        out = _gh(["release", "view", tag, "--repo", repo,
                   "--json", "id,tagName,isPrerelease,isDraft,"
                              "targetCommitish,assets"], token)
    except PublishError:
        return None
    return json.loads(out)


def gh_list_releases(repo: str, token: str = None) -> list:
    out = _gh(["release", "list", "--repo", repo, "--limit", "100",
               "--json", "tagName,isPrerelease,isDraft"], token)
    return json.loads(out)


def gh_release_for_tag(repo: str, tag: str, token: str = None) -> dict:
    rel = gh_release(repo, tag, token)
    return rel or {}


def gh_create_release(repo: str, tag: str, name: str, notes: str,
                      files: list, prerelease: bool, latest: bool,
                      commit: str, dry_run: bool,
                      ledger: Ledger) -> LedgerEntry:
    """Idempotent release create: an existing release for the tag gets its
    missing assets uploaded and is never recreated."""
    entry = ledger.add(LedgerEntry(platform="github"))
    files = [f for f in files if f and os.path.isfile(f)]
    existing = gh_release(repo, tag)
    if dry_run:
        entry.state = "dry-run"
        entry.detail = f"would {'update' if existing else 'create'} " \
                       f"{'prerelease' if prerelease else 'release'} {tag}"
        return entry
    if existing:
        have = {a["name"] for a in existing.get("assets", [])}
        missing = [f for f in files if os.path.basename(f) not in have]
        if missing:
            _gh(["release", "upload", tag] + missing +
                ["--repo", repo, "--clobber"])
        entry.remote_id = str(existing.get("id", ""))
        entry.url = f"https://github.com/{repo}/releases/tag/{tag}"
        entry.state = "noop"
        entry.detail = f"release existed; uploaded {len(missing)} assets"
        return entry
    args = ["release", "create", tag, "--repo", repo, "--title", name,
            "--notes", notes, "--target", commit]
    if prerelease:
        args.append("--prerelease")
    if latest:
        args.append("--latest")
    _gh(args + files)
    rel = gh_release(repo, tag) or {}
    entry.remote_id = str(rel.get("id", ""))
    entry.url = f"https://github.com/{repo}/releases/tag/{tag}"
    entry.state = "published"
    return entry


def gh_download_asset(repo: str, tag: str, asset: str, out_dir: str) -> str:
    """Download one release asset; returns the local path."""
    os.makedirs(out_dir, exist_ok=True)
    _gh(["release", "download", tag, "--repo", repo,
         "--pattern", asset, "--dir", out_dir, "--clobber"])
    return os.path.join(out_dir, asset)


def resolve_rc(repo: str, stable_tag: str, commit: str) -> dict:
    """Find the highest-numbered green RC prerelease for the stable version,
    pinned to the exact tagged commit. Returns the manifest evidence."""
    match = STABLE_TAG_RE.match(stable_tag)
    if not match:
        raise PublishError(f"{stable_tag!r} is not a stable tag")
    version = match.group(1)
    candidates = []
    for rel in gh_list_releases(repo):
        m = RC_TAG_RE.match(rel.get("tagName", ""))
        if not m or m.group(1) != version or not rel.get("isPrerelease"):
            continue
        full = gh_release(repo, rel["tagName"])
        if not full or full.get("targetCommitish") != commit:
            continue
        candidates.append((int(m.group(5)), rel["tagName"]))
    if not candidates:
        raise PublishError(
            f"no release candidate for {version} on commit {commit[:12]}")
    for _, tag in sorted(candidates, reverse=True):
        with _tempdir() as tmp:
            try:
                path = gh_download_asset(repo, tag, "release-manifest.json", tmp)
            except PublishError:
                continue
            with open(path, encoding="utf-8") as fh:
                manifest = json.load(fh)
        if manifest.get("gates_green") and manifest.get("commit") == commit:
            manifest["rc_tag"] = tag
            return manifest
    raise PublishError(
        f"no green release candidate for {version} on commit {commit[:12]} "
        f"(checked {[t for _, t in candidates]})")


class _tempdir:
    def __enter__(self):
        import tempfile
        self.path = tempfile.mkdtemp(prefix="straja-rc-")
        return self.path

    def __exit__(self, *exc):
        import shutil
        shutil.rmtree(self.path, ignore_errors=True)


# ---------------------------------------------------------------------------
# Modrinth

def modrinth_versions(project_id: str, token: str) -> list:
    return _http(f"{MODRINTH_API}/project/{project_id}/version",
                 token=token)


def _modrinth_match(versions: list, version_number: str):
    for v in versions:
        if v.get("version_number") == version_number:
            return v
    return None


def _modrinth_hash(version: dict) -> str:
    for f in version.get("files", []):
        if f.get("primary") or len(version["files"]) == 1:
            return (f.get("hashes") or {}).get("sha256", "")
    return (version.get("files", [{}])[0].get("hashes") or {}).get("sha256", "")


def modrinth_publish(project_id: str, token: str, tag: str, name: str,
                     version_type: str, jar: str, sha256: str,
                     game_versions: list, loaders: list,
                     changelog: str, dry_run: bool,
                     ledger: Ledger) -> LedgerEntry:
    """Query-before-create Modrinth publication. Same version+hash is a
    no-op; same version with a different hash fails closed."""
    entry = ledger.add(LedgerEntry(platform="modrinth",
                                   expected_sha256=sha256))
    existing = _modrinth_match(modrinth_versions(project_id, token), tag)
    if existing:
        observed = _modrinth_hash(existing)
        entry.remote_id = existing.get("id", "")
        entry.observed_sha256 = observed
        if observed == sha256:
            entry.state = "noop"
            entry.url = f"https://modrinth.com/mod/{project_id}/version/{existing['id']}"
            return entry
        raise PublishError(
            f"modrinth version {tag} exists with sha256 "
            f"{observed[:16]}… != expected {sha256[:16]}… — refusing to "
            "replace remote bytes")
    if dry_run:
        entry.state = "dry-run"
        entry.detail = f"would create {version_type} version {tag}"
        return entry
    data = {"name": name, "version_number": tag, "changelog": changelog,
            "version_type": version_type, "game_versions": game_versions,
            "loaders": loaders, "project_id": project_id,
            "file_parts": ["file"], "primary_file": "file",
            "dependencies": []}
    body, ctype = _multipart({"data": json.dumps(data)}, "file", jar)
    created = _http(f"{MODRINTH_API}/version", token=token, method="POST",
                    body=body, headers={"Content-Type": ctype})
    entry.remote_id = created.get("id", "")
    entry.url = f"https://modrinth.com/mod/{project_id}/version/{entry.remote_id}"
    entry.state = "published"
    return entry


def modrinth_promote(project_id: str, token: str, rc_tag: str,
                     stable_tag: str, name: str, sha256: str,
                     game_versions: list, loaders: list,
                     dry_run: bool, ledger: Ledger) -> LedgerEntry:
    """Stable promotion: PATCH the RC version's metadata to the stable
    identity. The file — and its sha256 — is retained byte-identical."""
    entry = ledger.add(LedgerEntry(platform="modrinth",
                                   expected_sha256=sha256))
    versions = modrinth_versions(project_id, token)
    stable = _modrinth_match(versions, stable_tag)
    if stable:
        observed = _modrinth_hash(stable)
        entry.remote_id = stable.get("id", "")
        entry.observed_sha256 = observed
        if observed != sha256:
            raise PublishError(
                f"modrinth stable {stable_tag} exists with sha256 "
                f"{observed[:16]}… != expected {sha256[:16]}…")
        entry.state = "noop"
        return entry
    rc = _modrinth_match(versions, rc_tag)
    if not rc:
        raise PublishError(f"no modrinth RC version {rc_tag} to promote")
    observed = _modrinth_hash(rc)
    entry.remote_id = rc.get("id", "")
    entry.observed_sha256 = observed
    if observed != sha256:
        raise PublishError(
            f"modrinth RC {rc_tag} sha256 {observed[:16]}… != "
            f"expected {sha256[:16]}… — promotion would not preserve bytes")
    if dry_run:
        entry.state = "dry-run"
        entry.detail = f"would promote {rc_tag} -> {stable_tag} as release"
        return entry
    _http(f"{MODRINTH_API}/version/{rc['id']}", token=token, method="PATCH",
          body=json.dumps({"version_number": stable_tag, "name": name,
                           "version_type": "release",
                           "game_versions": game_versions,
                           "loaders": loaders}).encode(),
          headers={"Content-Type": "application/json"})
    entry.url = f"https://modrinth.com/mod/{project_id}/version/{rc['id']}"
    entry.state = "promoted"
    return entry


# ---------------------------------------------------------------------------
# CurseForge

def cf_headers(token: str) -> dict:
    return {"X-Api-Token": token}


def curseforge_files(project_id: str, token: str) -> list:
    out, index = [], 0
    while True:
        page = _http(f"{CURSEFORGE_API}/mods/{project_id}/files"
                     f"?index={index}&pageSize=50",
                     token=token, token_header="X-Api-Token",
                     token_prefix="")
        data = page.get("data", []) if isinstance(page, dict) else page
        if not data:
            break
        out.extend(data)
        if len(data) < 50:
            break
        index += len(data)
    return out


def _cf_hashes(path: str) -> dict:
    raw = _read(path)
    return {"sha1": hashlib.sha1(raw).hexdigest(),
            "md5": hashlib.md5(raw).hexdigest()}


def _cf_match(files: list, jar_name: str, hashes: dict):
    """Find an existing file carrying the same bytes (sha1 or md5 match)."""
    for f in files:
        if f.get("fileName") != jar_name:
            continue
        remote = {(h.get("algo"), h.get("value"))
                  for h in f.get("hashes", [])}
        if (1, hashes["sha1"]) in remote or (2, hashes["md5"]) in remote:
            return f
    return None


def cf_game_version_ids(token: str, names: list) -> list:
    """Resolve game-version names (e.g. '1.21.1', 'NeoForge') to CurseForge
    version ids via the game version-types + versions endpoints."""
    types = _http(f"{CURSEFORGE_API}/games/{CURSEFORGE_GAME_ID}/version-types",
                  token=token, token_header="X-Api-Token", token_prefix="")
    wanted, ids = set(names), []
    resolved = set()
    for vt in types.get("data", []):
        slug = vt.get("slug", "")
        if not any(n.lower() in slug for n in ("minecraft", "modloader")):
            continue
        vers = _http(f"{CURSEFORGE_API}/games/{CURSEFORGE_GAME_ID}/versions"
                     f"?versionTypeIds={vt['id']}",
                     token=token, token_header="X-Api-Token", token_prefix="")
        for v in vers.get("data", []):
            if v.get("name") in wanted:
                ids.append(v["id"])
                resolved.add(v["name"])
    missing = wanted - resolved
    if missing:
        raise PublishError(
            f"CurseForge game versions unresolved: {sorted(missing)}")
    return ids


def curseforge_publish(project_id: str, token: str, tag: str, name: str,
                       release_type: str, jar: str, game_versions: list,
                       changelog: str, dry_run: bool,
                       ledger: Ledger) -> LedgerEntry:
    """Idempotent CurseForge upload: an existing file whose recorded
    md5/sha1 matches the local JAR is a no-op. CurseForge has no
    metadata-promotion endpoint, so stable uploads the exact same bytes
    classified as a release."""
    jar_name = os.path.basename(jar)
    expected_sha256 = sha256_of(jar)
    entry = ledger.add(LedgerEntry(platform="curseforge",
                                   expected_sha256=expected_sha256))
    hashes = _cf_hashes(jar)
    existing = _cf_match(curseforge_files(project_id, token),
                         jar_name, hashes)
    if existing:
        entry.remote_id = str(existing.get("id", ""))
        entry.url = f"https://www.curseforge.com/minecraft/mc-mods/" \
                    f"{project_id}/files/{existing['id']}"
        entry.state = "noop"
        entry.observed_sha256 = expected_sha256
        return entry
    ids = [int(v) for v in game_versions if str(v).isdigit()]
    names = [v for v in game_versions if not str(v).isdigit()]
    if names:
        ids.extend(cf_game_version_ids(token, names))
    if not ids:
        raise PublishError("no CurseForge game version ids resolved")
    if dry_run:
        entry.state = "dry-run"
        entry.detail = f"would upload {jar_name} as {release_type}"
        return entry
    metadata = {"changelog": changelog, "changelogType": "markdown",
                "displayName": name, "gameVersions": ids,
                "releaseType": release_type}
    body, ctype = _multipart({"metadata": json.dumps(metadata)},
                             "file", jar)
    created = _http(f"{CURSEFORGE_API}/mods/{project_id}/files",
                    token=token, token_header="X-Api-Token",
                    token_prefix="", method="POST", body=body,
                    headers={"Content-Type": ctype})
    entry.remote_id = str(created.get("id", ""))
    entry.url = f"https://www.curseforge.com/minecraft/mc-mods/" \
                f"{project_id}/files/{entry.remote_id}"
    entry.state = "published"
    return entry


# ---------------------------------------------------------------------------
# CLI

def _cmd_evidence(args):
    gates = json.load(open(args.gates_json, encoding="utf-8"))
    doc = build_evidence(args.tag, args.commit, args.version,
                         os.path.basename(args.jar), args.sha256,
                         args.sha512, gates, args.run_id, args.run_url)
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, indent=2)
    print(f"evidence: gates_green={doc['gates_green']} -> {args.out}")


def _cmd_sbom(args):
    with open(args.server_manifest, encoding="utf-8") as fh:
        manifest = json.load(fh)
    doc = build_sbom(args.version, manifest)
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, indent=2)
    print(f"sbom: {len(doc['components'])} components -> {args.out}")


def _cmd_resolve_rc(args):
    manifest = resolve_rc(args.repo, args.tag, args.commit)
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2)
    print(f"resolved {manifest['rc_tag']} (sha256={manifest['sha256'][:16]}…)")


def _split_csv(value: str) -> list:
    return [v.strip() for v in (value or "").split(",") if v.strip()]


def _cmd_modrinth(args):
    ledger = Ledger()
    if args.promote_from:
        entry = modrinth_promote(
            args.project_id, _env("MODRINTH_TOKEN"), args.promote_from,
            args.tag, args.name, args.sha256,
            _split_csv(args.game_versions), _split_csv(args.loaders),
            args.dry_run, ledger)
    else:
        entry = modrinth_publish(
            args.project_id, _env("MODRINTH_TOKEN"), args.tag, args.name,
            args.version_type, args.jar, args.sha256,
            _split_csv(args.game_versions), _split_csv(args.loaders),
            args.changelog, args.dry_run, ledger)
    ledger.write(args.ledger)
    print(f"modrinth: {entry.state} {entry.url or entry.detail}")


def _cmd_curseforge(args):
    ledger = Ledger()
    entry = curseforge_publish(
        args.project_id, _env("CURSEFORGE_API_KEY"), args.tag, args.name,
        args.release_type, args.jar, _split_csv(args.game_versions),
        args.changelog, args.dry_run, ledger)
    ledger.write(args.ledger)
    print(f"curseforge: {entry.state} {entry.url or entry.detail}")


def _cmd_github(args):
    ledger = Ledger()
    entry = gh_create_release(
        args.repo, args.tag, args.name, args.notes, args.files,
        args.prerelease, args.latest, args.commit, args.dry_run, ledger)
    ledger.write(args.ledger)
    print(f"github: {entry.state} {entry.url or entry.detail}")


def _cmd_merge_ledger(args):
    entries = []
    for path in args.ledgers:
        if not os.path.exists(path):
            # A platform step that never ran still leaves a visible gap.
            entries.append({"platform": os.path.basename(path)
                            .replace("-ledger.json", ""),
                            "id": "", "url": "", "expected_sha256": "",
                            "observed_sha256": "", "state": "failed",
                            "detail": "ledger file missing — step did not complete"})
            continue
        with open(path, encoding="utf-8") as fh:
            entries.extend(json.load(fh).get("entries", []))
    merged = {"entries": entries, "generated_at": int(time.time())}
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(merged, fh, indent=2)
    print(f"ledger: {len(entries)} entries -> {args.out}")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)

    ev = sub.add_parser("evidence")
    ev.add_argument("--tag", required=True)
    ev.add_argument("--commit", required=True)
    ev.add_argument("--version", required=True)
    ev.add_argument("--jar", required=True)
    ev.add_argument("--sha256", required=True)
    ev.add_argument("--sha512", required=True)
    ev.add_argument("--gates-json", required=True)
    ev.add_argument("--run-id", default="")
    ev.add_argument("--run-url", default="")
    ev.add_argument("--out", required=True)
    ev.set_defaults(fn=_cmd_evidence)

    sb = sub.add_parser("sbom")
    sb.add_argument("--server-manifest", default="tools/ci/server_manifest.json")
    sb.add_argument("--version", required=True)
    sb.add_argument("--out", required=True)
    sb.set_defaults(fn=_cmd_sbom)

    rr = sub.add_parser("resolve-rc")
    rr.add_argument("--repo", required=True)
    rr.add_argument("--tag", required=True)
    rr.add_argument("--commit", required=True)
    rr.add_argument("--out", required=True)
    rr.set_defaults(fn=_cmd_resolve_rc)

    mr = sub.add_parser("modrinth")
    mr.add_argument("--project-id", required=True)
    mr.add_argument("--tag", required=True)
    mr.add_argument("--name", required=True)
    mr.add_argument("--version-type", default="beta")
    mr.add_argument("--jar", default="")
    mr.add_argument("--sha256", required=True)
    mr.add_argument("--game-versions", default="1.21.1")
    mr.add_argument("--loaders", default="neoforge")
    mr.add_argument("--changelog", default="")
    mr.add_argument("--promote-from", default="")
    mr.add_argument("--dry-run", action="store_true")
    mr.add_argument("--ledger", required=True)
    mr.set_defaults(fn=_cmd_modrinth)

    cf = sub.add_parser("curseforge")
    cf.add_argument("--project-id", required=True)
    cf.add_argument("--tag", required=True)
    cf.add_argument("--name", required=True)
    cf.add_argument("--release-type", default="beta")
    cf.add_argument("--jar", required=True)
    cf.add_argument("--game-versions", default="")
    cf.add_argument("--changelog", default="")
    cf.add_argument("--dry-run", action="store_true")
    cf.add_argument("--ledger", required=True)
    cf.set_defaults(fn=_cmd_curseforge)

    gh = sub.add_parser("github")
    gh.add_argument("--repo", required=True)
    gh.add_argument("--tag", required=True)
    gh.add_argument("--name", required=True)
    gh.add_argument("--notes", default="")
    gh.add_argument("--files", nargs="*", default=[])
    gh.add_argument("--prerelease", action="store_true")
    gh.add_argument("--latest", action="store_true")
    gh.add_argument("--commit", required=True)
    gh.add_argument("--dry-run", action="store_true")
    gh.add_argument("--ledger", required=True)
    gh.set_defaults(fn=_cmd_github)

    ml = sub.add_parser("merge-ledger")
    ml.add_argument("--ledgers", nargs="+", required=True)
    ml.add_argument("--out", required=True)
    ml.set_defaults(fn=_cmd_merge_ledger)

    args = parser.parse_args(argv)
    try:
        args.fn(args)
    except PublishError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
