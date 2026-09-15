#!/usr/bin/env python3
"""Disposable dedicated-server harness for Straja CI.

Installs NeoForge from the official Maven (checksum-verified sidecars),
stages the exact packaged artifact by SHA-256 (never rebuilt), downloads
and verifies pinned dependency profiles from canonical sources, boots the
server with loopback-only RCON, runs profile checks, performs a clean
shutdown, and emits JSON + JUnit reports. Standard library only.

Usage:
    python3 tools/ci/server_harness.py run \
        --manifest tools/ci/server_manifest.json \
        --profile required-only \
        --jar build/libs/straja-<version>.jar \
        --work-root <dir> --artifacts-dir <dir>
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import secrets
import shutil
import socket
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field

# ---------------------------------------------------------------------------
# Result model

PASS = "pass"
FAIL = "fail"
BLOCKED = "blocked"
SKIPPED = "skipped"
# Distinct non-success classifications (used as check/profile statuses):
TIMEOUT = "timeout"
STARTUP_CRASH = "startup_crash"
DOWNLOAD_MISSING = "download_missing"
CHECKSUM_MISMATCH = "checksum_mismatch"
MOD_ID_MISMATCH = "mod_id_mismatch"
RCON_LOST = "rcon_lost"
ERROR = "error"

NON_SUCCESS = {FAIL, BLOCKED, SKIPPED, TIMEOUT, STARTUP_CRASH,
               DOWNLOAD_MISSING, CHECKSUM_MISMATCH, MOD_ID_MISMATCH,
               RCON_LOST, ERROR}


class HarnessError(Exception):
    """A classified harness failure — becomes the profile's status."""

    def __init__(self, kind: str, message: str):
        super().__init__(message)
        self.kind = kind
        self.message = message


@dataclass
class CheckResult:
    name: str
    status: str
    detail: str = ""
    duration_s: float = 0.0


@dataclass
class ProfileResult:
    profile: str
    blocking: bool
    advisory: bool
    status: str = FAIL
    checks: list = field(default_factory=list)
    timings: dict = field(default_factory=dict)
    artifact_dir: str = ""
    notes: list = field(default_factory=list)


# ---------------------------------------------------------------------------
# Manifest

_DEP_REQUIRED = {"source", "sourceId", "url", "fileName", "size", "side",
                 "expectedModId", "sha256", "sha512"}
_PROFILE_REQUIRED = {"blocking", "deps", "config", "timeoutSeconds"}
_HEX256 = re.compile(r"^[0-9a-f]{64}$")
_HEX512 = re.compile(r"^[0-9a-f]{128}$")
_TOML_KEY = re.compile(r"^[A-Za-z][A-Za-z0-9]*$")
_CONFIG_KEY = re.compile(r"^([a-z]+)\.([A-Za-z][A-Za-z0-9]*)$")


def _unsafe_filename(name: str) -> bool:
    return (not name or name in (".", "..") or name.startswith(".")
            or "/" in name or "\\" in name or ":" in name)


def load_manifest(path: str) -> dict:
    """Load and strictly validate the server manifest."""
    try:
        with open(path, "r", encoding="utf-8") as fh:
            manifest = json.load(fh)
    except (OSError, json.JSONDecodeError) as exc:
        raise HarnessError(ERROR, f"cannot load manifest {path}: {exc}")

    nf = manifest.get("neoforge", {})
    for key in ("version", "groupId", "artifactId", "classifier", "mavenBase"):
        if not nf.get(key):
            raise HarnessError(ERROR, f"manifest neoforge.{key} missing")
    if not str(nf["mavenBase"]).startswith("https://"):
        raise HarnessError(ERROR, "neoforge.mavenBase must be https")

    deps = manifest.get("dependencies", {})
    for name, dep in deps.items():
        missing = _DEP_REQUIRED - set(dep)
        if missing:
            raise HarnessError(ERROR, f"dependency {name}: missing {sorted(missing)}")
        if _unsafe_filename(dep["fileName"]):
            raise HarnessError(ERROR, f"dependency {name}: unsafe fileName {dep['fileName']!r}")
        if not str(dep["url"]).startswith("https://"):
            raise HarnessError(ERROR, f"dependency {name}: url must be https")
        if dep["side"] != "server":
            raise HarnessError(ERROR, f"dependency {name}: side must be 'server'")
        if not isinstance(dep["size"], int) or dep["size"] <= 0:
            raise HarnessError(ERROR, f"dependency {name}: size must be a positive int")
        if not _HEX256.match(str(dep["sha256"])):
            raise HarnessError(ERROR, f"dependency {name}: sha256 must be 64 lowercase hex")
        if not _HEX512.match(str(dep["sha512"])):
            raise HarnessError(ERROR, f"dependency {name}: sha512 must be 128 lowercase hex")
        if not re.match(r"^[a-z][a-z0-9_]*$", dep["expectedModId"]):
            raise HarnessError(ERROR, f"dependency {name}: invalid expectedModId")

    profiles = manifest.get("profiles", {})
    if not profiles:
        raise HarnessError(ERROR, "manifest defines no profiles")
    for name, prof in profiles.items():
        missing = _PROFILE_REQUIRED - set(prof)
        if missing:
            raise HarnessError(ERROR, f"profile {name}: missing {sorted(missing)}")
        for dep_name in prof["deps"]:
            if dep_name not in deps:
                raise HarnessError(ERROR, f"profile {name}: unknown dep {dep_name}")
        for cfg_key, cfg_val in prof["config"].items():
            if not _CONFIG_KEY.match(cfg_key):
                raise HarnessError(ERROR, f"profile {name}: bad config key {cfg_key!r}")
            if not isinstance(cfg_val, (str, bool, int)):
                raise HarnessError(ERROR, f"profile {name}: bad config value for {cfg_key}")
        if not isinstance(prof["timeoutSeconds"], int) or prof["timeoutSeconds"] <= 0:
            raise HarnessError(ERROR, f"profile {name}: timeoutSeconds must be positive int")
    return manifest


# ---------------------------------------------------------------------------
# Download + verification

_UA = {"User-Agent": "straja-ci-harness/1.0"}


def fetch_bytes(url: str, timeout: int = 120) -> bytes:
    req = urllib.request.Request(url, headers=_UA)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            if resp.status != 200:
                raise HarnessError(DOWNLOAD_MISSING, f"{url} -> HTTP {resp.status}")
            return resp.read()
    except urllib.error.HTTPError as exc:
        raise HarnessError(DOWNLOAD_MISSING, f"{url} -> HTTP {exc.code}")
    except (urllib.error.URLError, socket.timeout, TimeoutError) as exc:
        raise HarnessError(DOWNLOAD_MISSING, f"{url} -> {exc}")


def _digests(path: str):
    h256, h512 = hashlib.sha256(), hashlib.sha512()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h256.update(chunk)
            h512.update(chunk)
    return h256.hexdigest(), h512.hexdigest()


def verify_artifact(path: str, sha256: str = None, sha512: str = None,
                    size: int = None, label: str = "artifact"):
    """Verify size and pinned digests; raise checksum_mismatch on failure."""
    actual_size = os.path.getsize(path)
    if size is not None and actual_size != size:
        raise HarnessError(CHECKSUM_MISMATCH,
                           f"{label}: size {actual_size} != expected {size}")
    a256, a512 = _digests(path)
    if sha256 and a256 != sha256:
        raise HarnessError(CHECKSUM_MISMATCH,
                           f"{label}: sha256 {a256[:16]}… != pinned {sha256[:16]}…")
    if sha512 and a512 != sha512:
        raise HarnessError(CHECKSUM_MISMATCH,
                           f"{label}: sha512 mismatch")


def jar_mod_ids(path: str) -> set:
    """Return the modIds declared in a mod JAR's neoforge.mods.toml."""
    try:
        with zipfile.ZipFile(path) as zf:
            names = [n for n in zf.namelist()
                     if n.endswith("neoforge.mods.toml") or n.endswith("mods.toml")]
            ids = set()
            for name in names:
                text = zf.read(name).decode("utf-8", "replace")
                ids.update(re.findall(r'modId\s*=\s*"([^"]+)"', text))
            return ids
    except (zipfile.BadZipFile, KeyError, OSError) as exc:
        raise HarnessError(MOD_ID_MISMATCH, f"{path}: unreadable mod metadata: {exc}")


def verify_mod_id(path: str, expected: str, label: str):
    ids = jar_mod_ids(path)
    if expected not in ids:
        raise HarnessError(MOD_ID_MISMATCH,
                           f"{label}: expected mod id {expected!r}, found {sorted(ids)}")


def download_dependency(dep: dict, cache_dir: str) -> str:
    """Download a dependency into cache_dir and verify it end-to-end."""
    os.makedirs(cache_dir, exist_ok=True)
    dest = os.path.join(cache_dir, dep["fileName"])
    if os.path.exists(dest):
        verify_artifact(dest, dep["sha256"], dep["sha512"], dep["size"],
                        label=dep["fileName"])
        verify_mod_id(dest, dep["expectedModId"], dep["fileName"])
        return dest
    data = fetch_bytes(dep["url"], timeout=300)
    tmp = dest + ".part"
    with open(tmp, "wb") as fh:
        fh.write(data)
    try:
        verify_artifact(tmp, dep["sha256"], dep["sha512"], dep["size"],
                        label=dep["fileName"])
        verify_mod_id(tmp, dep["expectedModId"], dep["fileName"])
    except HarnessError:
        os.unlink(tmp)
        raise
    os.replace(tmp, dest)
    return dest


# ---------------------------------------------------------------------------
# NeoForge provisioning

def neoforge_installer_url(nf: dict) -> tuple:
    version = nf["version"]
    jar = f"{nf['artifactId']}-{version}-{nf['classifier']}.jar"
    group_path = nf["groupId"].replace(".", "/")
    return f"{nf['mavenBase']}/{group_path}/{nf['artifactId']}/{version}/{jar}", jar


def _parse_sidecar(text: str) -> str:
    m = re.match(r"^([0-9a-fA-F]{64,128})", text.strip())
    return m.group(1).lower() if m else ""


def provision_neoforge(server_dir: str, manifest: dict, cache_dir: str,
                       log_fn) -> str:
    """Install a verified NeoForge server into server_dir; return argfile."""
    nf = manifest["neoforge"]
    url, jar_name = neoforge_installer_url(nf)
    expected = {}
    for sidecar in nf.get("checksumSidecars", ["sha256"]):
        text = fetch_bytes(f"{url}.{sidecar}", timeout=30).decode("ascii", "replace")
        digest = _parse_sidecar(text)
        if not digest:
            raise HarnessError(CHECKSUM_MISMATCH,
                               f"empty {sidecar} sidecar for {jar_name}")
        expected[sidecar] = digest

    os.makedirs(cache_dir, exist_ok=True)
    installer = os.path.join(cache_dir, jar_name)
    if not os.path.exists(installer):
        data = fetch_bytes(url, timeout=300)
        with open(installer, "wb") as fh:
            fh.write(data)
    verify_artifact(installer, expected.get("sha256"), expected.get("sha512"),
                    label=jar_name)

    log_fn(f"installing {jar_name} into {server_dir}")
    install_log = os.path.join(server_dir, "installer.log")
    os.makedirs(server_dir, exist_ok=True)
    env = dict(os.environ)
    tmp_dir = os.path.join(server_dir, ".install-tmp")
    os.makedirs(tmp_dir, exist_ok=True)
    # Bound every JVM the installer spawns (ART, splitter, …) and keep its
    # temp extraction inside the work dir so a full/small OS temp cannot
    # truncate downloads.
    env["JAVA_TOOL_OPTIONS"] = f"-Xmx1g -Djava.io.tmpdir={tmp_dir}"
    with open(install_log, "wb") as out:
        proc = subprocess.run(
            ["java", "-jar", installer, "--installServer", server_dir],
            cwd=server_dir, stdout=out, stderr=subprocess.STDOUT,
            timeout=600, env=env)
    if proc.returncode != 0:
        raise HarnessError(ERROR, f"neoforge installer exited {proc.returncode}; see installer.log")

    argfile_name = "win_args.txt" if os.name == "nt" else "unix_args.txt"
    argfile = os.path.join(server_dir, "libraries", "net", "neoforged",
                           "neoforge", nf["version"], argfile_name)
    if not os.path.isfile(argfile):
        raise HarnessError(ERROR, f"missing {argfile_name} after install")
    return argfile


# ---------------------------------------------------------------------------
# Server config

def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def write_server_properties(server_dir: str, game_port: int, rcon_port: int,
                            rcon_password: str):
    props = {
        "server-ip": "127.0.0.1",
        "server-port": str(game_port),
        "enable-rcon": "true",
        "rcon.port": str(rcon_port),
        "rcon.password": rcon_password,
        "broadcast-rcon-to-ops": "false",
        "enable-query": "false",
        "enable-status": "false",
        "online-mode": "false",
        "enforce-secure-profile": "false",
        "level-seed": "straja-ci",
        "level-type": "minecraft\\:flat",
        "difficulty": "peaceful",
        "spawn-monsters": "false",
        "spawn-animals": "false",
        "spawn-npcs": "false",
        "spawn-protection": "0",
        "view-distance": "4",
        "simulation-distance": "4",
        "max-players": "4",
        "sync-chunk-writes": "false",
    }
    path = os.path.join(server_dir, "server.properties")
    with open(path, "w", encoding="utf-8") as fh:
        for key, val in props.items():
            fh.write(f"{key}={val}\n")


def _toml_value(val) -> str:
    if isinstance(val, bool):
        return "true" if val else "false"
    if isinstance(val, int):
        return str(val)
    return json.dumps(str(val))


def write_straja_config(server_dir: str, config: dict):
    """Write a minimal straja-server.toml; NeoForge fills unset defaults."""
    sections: dict = {}
    for dotted, val in config.items():
        section, key = dotted.split(".", 1)
        sections.setdefault(section, []).append((key, val))
    path = os.path.join(server_dir, "config")
    os.makedirs(path, exist_ok=True)
    with open(os.path.join(path, "straja-server.toml"), "w", encoding="utf-8") as fh:
        for section, entries in sections.items():
            fh.write(f"[{section}]\n")
            for key, val in entries:
                fh.write(f"{key} = {_toml_value(val)}\n")


def stage_mods(server_dir: str, straja_jar: str, dep_paths: list,
               jar_sha256: str = None):
    """Stage the exact packaged artifact (by SHA-256) plus verified deps."""
    mods = os.path.join(server_dir, "mods")
    os.makedirs(mods, exist_ok=True)
    verify_artifact(straja_jar, sha256=jar_sha256, label="straja artifact")
    shutil.copy2(straja_jar, mods)
    for dep in dep_paths:
        shutil.copy2(dep, mods)


# ---------------------------------------------------------------------------
# RCON (vendored minimal client — same protocol as tools/rcon.py)

_SERVERDATA_AUTH = 3
_SERVERDATA_EXEC = 2


def _rcon_packet(req_id: int, pkt_type: int, payload: str) -> bytes:
    body = struct.pack("<ii", req_id, pkt_type) + payload.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(body)) + body


def _rcon_recv_exact(sock, n: int) -> bytes:
    data = b""
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            return data
        data += chunk
    return data


class RconClient:
    """One-shot-per-command RCON client with bounded timeouts."""

    def __init__(self, host: str, port: int, password: str, timeout: int = 15):
        self.host, self.port, self.password, self.timeout = host, port, password, timeout

    def execute(self, command: str) -> str:
        sock = None
        try:
            sock = socket.create_connection((self.host, self.port),
                                            timeout=self.timeout)
            sock.settimeout(self.timeout)
            sock.sendall(_rcon_packet(1, _SERVERDATA_AUTH, self.password))
            header = _rcon_recv_exact(sock, 4)
            if len(header) < 4:
                raise HarnessError(RCON_LOST, "rcon auth: no response")
            (length,) = struct.unpack("<i", header)
            body = _rcon_recv_exact(sock, length)
            req_id = struct.unpack("<i", body[:4])[0]
            if req_id == -1:
                raise HarnessError(RCON_LOST, "rcon auth rejected")
            sock.sendall(_rcon_packet(2, _SERVERDATA_EXEC, command))
            deadline = time.time() + self.timeout
            while time.time() < deadline:
                try:
                    header = _rcon_recv_exact(sock, 4)
                except socket.timeout:
                    break
                if len(header) < 4:
                    break
                (length,) = struct.unpack("<i", header)
                body = _rcon_recv_exact(sock, length)
                rid = struct.unpack("<i", body[:4])[0]
                if rid == 2:
                    return body[8:-2].decode("utf-8", "replace")
            return ""
        except (ConnectionRefusedError, socket.timeout, OSError) as exc:
            raise HarnessError(RCON_LOST, f"rcon {command!r}: {exc}")
        finally:
            if sock is not None:
                sock.close()


# ---------------------------------------------------------------------------
# Process management

_READY_RE = re.compile(r'Done \(')
_CRASH_RE = re.compile(r'Exception in server tick loop|Failed to start the minecraft server|crash report')


def start_server(server_dir: str, argfile: str, log_path: str,
                 heap: str = "2G", append: bool = False):
    rel = os.path.relpath(argfile, server_dir)
    cmd = ["java", f"-Xmx{heap}", "-Xms512m", f"@{rel}", "nogui"]
    log_fh = open(log_path, "ab" if append else "wb")
    kwargs = {}
    if os.name == "nt":
        kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        kwargs["start_new_session"] = True
    proc = subprocess.Popen(cmd, cwd=server_dir, stdout=log_fh,
                            stderr=subprocess.STDOUT, **kwargs)
    return proc, log_fh


def _log_contains(log_path: str, pattern) -> bool:
    try:
        with open(log_path, "r", encoding="utf-8", errors="replace") as fh:
            return bool(pattern.search(fh.read()))
    except OSError:
        return False


def wait_ready(proc, log_path: str, rcon: RconClient, timeout_s: int):
    """Wait for a fresh ready marker plus a live RCON round-trip."""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        if proc.poll() is not None:
            raise HarnessError(STARTUP_CRASH,
                               f"server exited {proc.returncode} during startup")
        if _log_contains(log_path, _READY_RE):
            try:
                rcon.execute("list")
                return
            except HarnessError:
                pass
        if _log_contains(log_path, _CRASH_RE) and proc.poll() is not None:
            raise HarnessError(STARTUP_CRASH, "server crashed during startup")
        time.sleep(1.0)
    raise HarnessError(TIMEOUT, f"server not ready within {timeout_s}s")


def _read_log(path: str) -> str:
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            return fh.read()
    except OSError:
        return ""


def kill_tree(proc):
    """Terminate only the process tree this harness spawned."""
    if proc.poll() is not None:
        return
    try:
        if os.name == "nt":
            subprocess.run(["taskkill", "/T", "/F", "/PID", str(proc.pid)],
                           capture_output=True, timeout=30)
        else:
            os.killpg(os.getpgid(proc.pid), 9)
    except (OSError, subprocess.SubprocessError):
        try:
            proc.kill()
        except OSError:
            pass
    try:
        proc.wait(timeout=15)
    except subprocess.TimeoutExpired:
        pass


def clean_shutdown(rcon: RconClient, proc, timeout_s: int = 90):
    """save-all flush → stop → bounded wait; escalate to kill only our tree."""
    rcon.execute("save-all flush")
    time.sleep(1.0)
    rcon.execute("stop")
    try:
        proc.wait(timeout=timeout_s)
        return
    except subprocess.TimeoutExpired:
        raise HarnessError(TIMEOUT, "server did not stop within budget")


# ---------------------------------------------------------------------------
# Redaction + reports

def redact(text: str, secrets_: list) -> str:
    out = text
    for secret in secrets_:
        if secret:
            out = out.replace(secret, "«redacted»")
    return out


def _junit(result: ProfileResult, xml_path: str):
    suite = ET.Element("testsuite", {
        "name": f"straja.server.{result.profile}",
        "tests": str(len(result.checks)),
        "failures": str(sum(1 for c in result.checks if c.status in NON_SUCCESS)),
        "skipped": "0",
    })
    for check in result.checks:
        case = ET.SubElement(suite, "testcase", {
            "classname": f"straja.server.{result.profile}",
            "name": check.name,
            "time": f"{check.duration_s:.3f}",
        })
        if check.status != PASS:
            node = ET.SubElement(case, "failure", {"type": check.status})
            node.text = check.detail
    tree = ET.ElementTree(suite)
    ET.indent(tree, space="  ")
    tree.write(xml_path, encoding="utf-8", xml_declaration=True)


def write_reports(result: ProfileResult, out_dir: str):
    os.makedirs(out_dir, exist_ok=True)
    _junit(result, os.path.join(out_dir, "junit.xml"))
    payload = {
        "profile": result.profile,
        "blocking": result.blocking,
        "advisory": result.advisory,
        "status": result.status,
        "checks": [{"name": c.name, "status": c.status,
                    "detail": c.detail, "duration_s": c.duration_s}
                   for c in result.checks],
        "timings": result.timings,
        "notes": result.notes,
    }
    with open(os.path.join(out_dir, "result.json"), "w", encoding="utf-8") as fh:
        json.dump(payload, fh, indent=2)


# ---------------------------------------------------------------------------
# Profile checks — every check returns CheckResult

def _check(fn):
    """Time a check and translate HarnessError into a CheckResult."""
    def wrapper(ctx):
        start = time.time()
        try:
            detail = fn(ctx) or ""
            return CheckResult(fn.__name__, PASS, detail,
                               round(time.time() - start, 3))
        except HarnessError as exc:
            return CheckResult(fn.__name__, exc.kind, exc.message,
                               round(time.time() - start, 3))
        except Exception as exc:  # noqa: BLE001 — classify everything
            return CheckResult(fn.__name__, ERROR, f"{type(exc).__name__}: {exc}",
                               round(time.time() - start, 3))
    return wrapper


def _rcon(ctx, cmd: str) -> str:
    resp = ctx["rcon"].execute(cmd)
    ctx["rcon_log"].append(f"$ {cmd}\n{resp}")
    return resp


def _log_grep(ctx, needle: str) -> bool:
    return needle in _read_log(ctx["server_log"])


@_check
def check_server_boot(ctx):
    return f"ready in {ctx['timings'].get('ready_s', '?')}s"


@_check
def check_expected_mods(ctx):
    """Expected mod ids appear in the server's startup mod list."""
    log = _read_log(ctx["server_log"])
    missing = [mid for mid in ctx["expect_mod_ids"]
               if not re.search(rf"\b{re.escape(mid)}\b", log)]
    if missing:
        raise HarnessError(FAIL, f"mod ids absent from server log: {missing}")
    return f"mods present: {', '.join(ctx['expect_mod_ids'])}"


@_check
def check_no_deployment_gate_failures(ctx):
    if _log_grep(ctx, "DEPLOYMENT GATE FAILED"):
        raise HarnessError(FAIL, "server logged a DEPLOYMENT GATE FAILED line")


@_check
def check_test_surface(ctx):
    resp = _rcon(ctx, "straja test create-player harness1")
    if not resp.strip():
        raise HarnessError(FAIL, "empty response from test surface")
    resp = _rcon(ctx, "straja test set-rank harness1 2")
    return "virtual player created, rank set"


_REFUSAL_MARKERS = ("disabled", "dezactivat", "unknown or incomplete command")


def _assert_refused(resp: str, surface: str):
    low = resp.lower()
    if resp.strip() and not any(m in low for m in _REFUSAL_MARKERS):
        raise HarnessError(FAIL,
                           f"{surface} answered {resp!r} — expected refusal")


@_check
def check_test_surface_refused(ctx):
    resp = _rcon(ctx, "straja test create-player harness1")
    _assert_refused(resp, "test surface")
    perf = _rcon(ctx, "straja test perf-sample")
    _assert_refused(perf, "perf-sample")
    return f"refused: {resp.strip() or '(not registered)'}; " \
           f"perf-sample: {perf.strip() or '(not registered)'}"


def _accrue_and_pay(ctx) -> str:
    """Create a commissioner (always free-duty eligible), accrue duty salary,
    trigger payout. Returns salary response + tell log."""
    _rcon(ctx, "straja test create-player pay1")
    _rcon(ctx, "straja test set-commissioner pay1")
    _rcon(ctx, "straja test start-duty pay1")
    _rcon(ctx, "straja test advance-time 7200")
    resp = _rcon(ctx, "straja test salary pay1")
    tell = _rcon(ctx, "straja test tell-log pay1")
    return resp + "\n" + tell


@_check
def check_salary_fail_closed(ctx):
    """Without a coin provider the balance is preserved, not silently paid."""
    text = _accrue_and_pay(ctx)
    if "Moneda externă nu este configurată" not in text:
        raise HarnessError(FAIL, f"expected fail-closed salary message; got: {text[-300:]}")
    state = _rcon(ctx, "straja test dump-state pay1")
    if "PAID" in state:
        raise HarnessError(FAIL, "salary marked PAID without a coin provider")
    return "unpaid balance retained; no coins minted"


@_check
def check_salary_physical_coins(ctx):
    text = _accrue_and_pay(ctx)
    if "Salariu plătit în monede fizice" not in text:
        raise HarnessError(FAIL, f"expected physical-coin payout; got: {text[-300:]}")
    inv = _rcon(ctx, "straja test inventory pay1")
    if "adys_decorations:" not in inv:
        raise HarnessError(FAIL, f"no adys coin items in inventory: {inv[-200:]}")
    return "physical coin payout confirmed"


@_check
def check_vampirism_provider(ctx):
    if not _log_grep(ctx, "Vampirism provider loaded"):
        raise HarnessError(FAIL, "log lacks 'Vampirism provider loaded'")


@_check
def check_native_ownership(ctx):
    if not _log_grep(ctx, "native ownership is active"):
        raise HarnessError(FAIL, "log lacks 'native ownership is active'")


@_check
def check_debug_refused(ctx):
    resp = _rcon(ctx, "straja debug readiness")
    _assert_refused(resp, "debug surface")
    return "debug surface refused"


@_check
def check_customnpcs_loaded(ctx):
    if not re.search(r"\bcustomnpcs\b", _read_log(ctx["server_log"])):
        raise HarnessError(FAIL, "customnpcs not present in server log")


@_check
def check_clean_shutdown(ctx):
    # executed by the orchestrator after checks; placeholder for ordering
    return ""


_PROFILE_CHECKS = {
    "required-only": [check_server_boot, check_expected_mods,
                      check_native_ownership, check_no_deployment_gate_failures,
                      check_test_surface, check_salary_fail_closed],
    "coin-provider": [check_server_boot, check_expected_mods,
                      check_native_ownership, check_no_deployment_gate_failures,
                      check_test_surface, check_salary_physical_coins],
    "vampirism": [check_server_boot, check_expected_mods,
                  check_vampirism_provider, check_no_deployment_gate_failures,
                  check_test_surface, check_salary_fail_closed],
    "combined": [check_server_boot, check_expected_mods,
                 check_vampirism_provider, check_no_deployment_gate_failures,
                 check_test_surface, check_salary_physical_coins],
    "production-config": [check_server_boot, check_expected_mods,
                          check_no_deployment_gate_failures,
                          check_test_surface_refused, check_debug_refused],
    "foreign-npc": [check_server_boot, check_expected_mods,
                    check_customnpcs_loaded],
}


# ---------------------------------------------------------------------------
# Orchestration

def _retain(server_dir: str, out_dir: str, secrets_: list):
    """Copy logs/crash-reports/non-secret config into the artifact dir."""
    keep = {"logs": "logs", "crash-reports": "crash-reports", "config": "config"}
    for src_name, dst_name in keep.items():
        src = os.path.join(server_dir, src_name)
        dst = os.path.join(out_dir, dst_name)
        if not os.path.isdir(src):
            continue
        os.makedirs(dst, exist_ok=True)
        for root, _, files in os.walk(src):
            for fname in files:
                fpath = os.path.join(root, fname)
                try:
                    with open(fpath, "rb") as fh:
                        content = fh.read()
                except OSError:
                    continue
                content = redact(content.decode("utf-8", "replace"),
                                 secrets_).encode("utf-8")
                rel = os.path.relpath(fpath, src)
                target = os.path.join(dst, rel)
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with open(target, "wb") as out:
                    out.write(content)


def run_profile(args) -> ProfileResult:
    manifest = load_manifest(args.manifest)
    if args.profile not in manifest["profiles"]:
        raise HarnessError(SKIPPED, f"unknown profile {args.profile}")
    prof = manifest["profiles"][args.profile]
    result = ProfileResult(profile=args.profile,
                           blocking=bool(prof["blocking"]),
                           advisory=not prof["blocking"])
    out_dir = os.path.join(args.artifacts_dir, args.profile)
    server_dir = os.path.join(args.work_root, args.profile, "server")
    dep_cache = args.dep_cache or os.path.join(args.work_root, "_dep_cache")
    result.artifact_dir = out_dir

    proc, log_fh = None, None
    rcon_password = secrets.token_urlsafe(24)
    secrets_ = [rcon_password]
    server_log = os.path.join(server_dir, "logs", "harness-stdout.log")
    started = time.time()

    def log(msg):
        print(f"[{args.profile}] {msg}", flush=True)

    try:
        # 0. Fail fast on a wrong/corrupt artifact before any downloads.
        verify_artifact(args.jar, sha256=args.jar_sha256, label="straja artifact")

        # 1. Provision NeoForge (checksum-verified installer).
        log("provisioning NeoForge")
        argfile = provision_neoforge(server_dir, manifest,
                                     os.path.join(dep_cache, "neoforge"), log)

        # 2. Download + verify dependencies and stage the exact artifact.
        dep_paths = [download_dependency(manifest["dependencies"][d], dep_cache)
                     for d in prof["deps"]]
        stage_mods(server_dir, args.jar, dep_paths, args.jar_sha256)

        # 3. Deterministic, loopback-only server configuration.
        game_port, rcon_port = free_port(), free_port()
        write_server_properties(server_dir, game_port, rcon_port, rcon_password)
        with open(os.path.join(server_dir, "eula.txt"), "w") as fh:
            fh.write("eula=true\n")
        write_straja_config(server_dir, prof["config"])

        # 4. Boot; wait for a fresh ready marker + live RCON.
        os.makedirs(os.path.dirname(server_log), exist_ok=True)
        proc, log_fh = start_server(server_dir, argfile, server_log,
                                    heap=args.heap)
        rcon = RconClient("127.0.0.1", rcon_port, rcon_password)
        wait_ready(proc, server_log, rcon, prof["timeoutSeconds"])
        result.timings["ready_s"] = round(time.time() - started, 1)
        log(f"server ready in {result.timings['ready_s']}s")

        # 5. Profile checks.
        ctx = {"rcon": rcon, "server_log": server_log, "rcon_log": [],
               "expect_mod_ids": [manifest["dependencies"][d]["expectedModId"]
                                  for d in prof["deps"]] + ["straja"],
               "timings": result.timings}
        for check_fn in _PROFILE_CHECKS.get(args.profile, []):
            if check_fn is check_clean_shutdown:
                continue
            res = check_fn(ctx)
            result.checks.append(res)
            log(f"  {res.name}: {res.status}")
            if res.status != PASS and result.blocking:
                raise HarnessError(res.status, f"{res.name}: {res.detail}")

        # 6. Clean shutdown: save-all flush → stop.
        log("clean shutdown")
        clean_shutdown(rcon, proc)
        result.checks.append(CheckResult("clean_shutdown", PASS,
                                         "save-all flush + stop accepted"))
        failed = [c for c in result.checks if c.status != PASS]
        result.status = PASS if not failed else failed[0].status
    except HarnessError as exc:
        if not any(c.status == exc.kind for c in result.checks):
            result.checks.append(CheckResult("harness", exc.kind, exc.message))
        result.status = exc.kind if exc.kind in NON_SUCCESS else ERROR
        log(f"profile failed: {exc.kind} — {exc.message}")
    finally:
        if proc is not None:
            kill_tree(proc)
        if log_fh is not None:
            log_fh.close()
        _retain(server_dir, out_dir, secrets_)
        write_reports(result, out_dir)
        result.timings["total_s"] = round(time.time() - started, 1)
    return result


def main(argv=None):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)
    run = sub.add_parser("run", help="provision, run checks, report")
    run.add_argument("--manifest", default="tools/ci/server_manifest.json")
    run.add_argument("--profile", required=True)
    run.add_argument("--jar", required=True)
    run.add_argument("--jar-sha256", default=None)
    run.add_argument("--work-root", required=True)
    run.add_argument("--artifacts-dir", required=True)
    run.add_argument("--dep-cache", default=None)
    run.add_argument("--heap", default="2G", help="server -Xmx budget")
    args = parser.parse_args(argv)

    if args.cmd == "run":
        try:
            result = run_profile(args)
        except HarnessError as exc:
            print(f"harness: {exc.kind}: {exc.message}", file=sys.stderr)
            return 2
        print(json.dumps({"profile": result.profile, "status": result.status}))
        return 0 if result.status == PASS else 1
    return 2


if __name__ == "__main__":
    sys.exit(main())
