#!/usr/bin/env python3
"""Advisory real-client UI verification — MC Pilot + Xvfb.

Launches a genuine NeoForge 1.21.1 client under Xvfb/Mesa on a hosted
runner, joins the disposable combined-profile server built from the exact
staged JAR, drives declarative scenario steps through the `mct` CLI
(semantic state first, screenshots as retained evidence), and reports an
honest verdict:

    pass    — every selected scenario's expectations held
    fail    — a scenario assertion/step failed (the product misbehaved)
    blocked — capability/infrastructure absent (xvfb, mct, client boot,
              client-mod download); the product was never exercised

Internal step failures always record honestly; only the RC workflow's
aggregate treats this job as permanently advisory. No production account,
no public listener — the server binds loopback only (server_harness
defaults) and the client uses an offline deterministic identity.

Scenario file schema (JSON, one file per scenario):
    {
      "name": "...", "group": "...", "timeoutSeconds": 180,
      "observable": "what a pass looks like",
      "failure": "what a failure looks like",
      "requiresClients": 1,           # 2 => capacity-gated, skipped if short
      "steps": [ ... ], "cleanup": [ ... ]
    }

Step types:
    rcon:        {command, expectContains|expectRegex|expectNotContains|
                  expectAbsentRegex, capture|captures}
    mct:         {args: [...], expect: [rules], capture|captures}
    screenshot:  {name}                           — retained evidence
    sleep:       {seconds}
    client:      {action: stop|rejoin}            — rejoin = stop+launch+wait
    npc-action:  {label, historyLast}             — resolve the labelled
                 clickEvent from raw chat components, send its command
    var:         {name, from, rules:[{regex,value}], default?}
    form:        {fill: [...], submit|cancel, expectTitleRegex}
                 — Tab-navigates the native Straja form, types values,
                 Enter on Submit/Cancel (no pixel coordinates)

capture/captures store regex group 1 (or the whole match) under {name};
later steps interpolate {name} in command/args/fill values.

mct expect rules (evaluated against the parsed JSON reply, raw text for
rawRegex): {path, equals|contains|regex|gte|gt} or {rawRegex}.

Every scenario needs a documented observable result, a timeout, and at
least one expect-bearing step. Steps marked "setup": true may omit
expectations. Cleanup steps run in order even after a failure.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field

sys.path.insert(0, os.path.dirname(__file__))
import server_harness as sh  # noqa: E402

PASS, FAIL, BLOCKED, SKIPPED = sh.PASS, sh.FAIL, "blocked", "skipped"
STEP_TYPES = {"rcon", "mct", "screenshot", "sleep", "client", "form", "var",
              "npc-action", "client2"}

_BLOCK_KINDS = {"no_tool", "install_failed", "client_boot", "client_join_ws"}
_FAIL_KINDS = {"assertion", "timeout", "process"}


class ClientUiError(Exception):
    def __init__(self, kind: str, message: str):
        super().__init__(message)
        self.kind = kind
        self.message = message


# ---------------------------------------------------------------------------
# Manifest + environment

def load_client_manifest(path: str) -> dict:
    with open(path, "r", encoding="utf-8") as fh:
        manifest = json.load(fh)
    for key in ("mct", "clientMod", "client", "aptPackages"):
        if key not in manifest:
            raise ClientUiError("assertion", f"client manifest missing '{key}'")
    return manifest


def _which(binary: str) -> str:
    found = shutil.which(binary)
    return found or ""


def preflight(manifest: dict, env: dict, log) -> str:
    """Verify/install the pinned toolchain; return the mct binary path."""
    node = _which("node")
    npm = _which("npm")
    java = _which(manifest["client"].get("java", "java"))
    xvfb = _which("Xvfb")
    missing = [name for name, p in (("node", node), ("npm", npm),
                                    ("java", java), ("Xvfb", xvfb))
               if not p]
    if missing:
        raise ClientUiError(
            "no_tool", "missing required binaries: " + ", ".join(missing))
    want_major = str(manifest.get("node", {}).get("major", 20))
    out = subprocess.run([node, "--version"], capture_output=True, text=True,
                         timeout=20)
    got = out.stdout.strip().lstrip("v")
    if got.split(".")[0] != want_major:
        raise ClientUiError("no_tool",
                            f"node {got} present, manifest requires {want_major}.x")

    pkg = manifest["mct"]["package"]
    ver = manifest["mct"]["version"]
    spec = f"{pkg}@{ver}"
    integrity = manifest["mct"].get("integrity")
    if integrity:
        view = subprocess.run([npm, "view", spec, "dist.integrity"],
                              capture_output=True, text=True, timeout=60)
        got_integrity = view.stdout.strip()
        if got_integrity != integrity:
            raise ClientUiError(
                "install_failed",
                f"npm integrity drift for {spec}: registry reports "
                f"{got_integrity!r}, manifest pins {integrity!r}")
    # Install into the disposable MCT home instead of the runner's global
    # prefix.  The package's transitive dependency graph has historically
    # published a workspace protocol in a latest @xmcl/unzip release; an
    # explicit npm override keeps the advisory toolchain reproducible while
    # leaving the product under test untouched.
    install_root = os.path.join(env.get("MCT_HOME", os.getcwd()), "npm")
    os.makedirs(install_root, exist_ok=True)
    package_json = {
        "private": True,
        "dependencies": {pkg: ver},
        "overrides": manifest["mct"].get("overrides", {}),
    }
    with open(os.path.join(install_root, "package.json"), "w",
              encoding="utf-8") as fh:
        json.dump(package_json, fh, indent=2)
        fh.write("\n")
    install = subprocess.run(
        [npm, "install", "--no-audit", "--no-fund"],
        capture_output=True, text=True, timeout=600, env=env,
        cwd=install_root)
    if install.returncode != 0:
        raise ClientUiError("install_failed",
                            f"npm install {spec} failed: "
                            f"{install.stderr.strip()[:300]}")
    mct = ""
    for cand in (os.path.join(install_root, "node_modules", ".bin", "mct"),
                 os.path.join(install_root, "node_modules", ".bin", "mct.cmd")):
        if os.path.exists(cand):
            mct = cand
            break
    if not mct:
        raise ClientUiError("no_tool", "mct not found after npm install")
    log(f"mct installed: {spec} -> {mct}")
    return mct


def seed_client_mod(manifest: dict, env: dict, log):
    """Place the checksum-verified client-mod jar in the mct mod cache so
    `client create` uses it instead of the catalog's dead release URL."""
    dep = {key: manifest["clientMod"][key]
           for key in ("url", "sha256", "sha512", "size", "expectedModId")}
    cache = env.get("MCT_CACHE_DIR") or os.path.join(
        os.path.expanduser("~"), ".mct", "cache")
    dest = os.path.join(cache, "mod")
    jar = sh.download_dependency(
        {"fileName": manifest["clientMod"]["file"], **dep}, dest)
    log(f"client mod cached: {os.path.basename(jar)}")
    return jar


def start_xvfb(manifest: dict, env: dict, log) -> subprocess.Popen:
    client = manifest["client"]
    display = client.get("display", ":99")
    res = client.get("resolution", "1280x720x24")
    proc = subprocess.Popen(
        ["Xvfb", display, "-screen", "0", res],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=env)
    time.sleep(1)
    if proc.poll() is not None:
        raise ClientUiError("no_tool", f"Xvfb {display} exited immediately")
    env["DISPLAY"] = display
    log(f"Xvfb up on {display} ({res}), "
        f"lang={client.get('language', 'en_us')} via options.txt")
    return proc


# ---------------------------------------------------------------------------
# mct invocation + transcript

@dataclass
class Transcript:
    entries: list = field(default_factory=list)

    def record(self, label: str, data):
        self.entries.append({"t": round(time.time(), 3),
                             "label": label, "data": data})


class Mct:
    """Thin wrapper over the mct CLI. Every call lands in the transcript."""

    def __init__(self, binary: str, client_name: str, env: dict,
                 transcript: Transcript):
        self.binary = binary
        self.client = client_name
        self.env = env
        self.transcript = transcript

    def __call__(self, args: list, timeout: int = 60) -> str:
        cmd = [self.binary, "--client", self.client] + [str(a) for a in args]
        self.transcript.record("mct " + " ".join(map(str, args)), None)
        try:
            proc = subprocess.run(cmd, capture_output=True, text=True,
                                  timeout=timeout, env=self.env)
        except subprocess.TimeoutExpired as exc:
            self.transcript.record("mct-timeout", str(args))
            raise ClientUiError("timeout",
                                f"mct {' '.join(map(str, args))} timed out") \
                from exc
        out = (proc.stdout or "").strip()
        err = (proc.stderr or "").strip()
        self.transcript.record("mct-out",
                               {"code": proc.returncode,
                                "stdout": out[:4000], "stderr": err[:2000]})
        if proc.returncode != 0:
            raise ClientUiError(
                "process", f"mct {' '.join(map(str, args))} exited "
                f"{proc.returncode}: {(err or out)[:300]}")
        return out

    def json(self, args: list, timeout: int = 60):
        """Run a command expected to emit a JSON object on stdout."""
        out = self(args, timeout=timeout)
        try:
            return json.loads(out)
        except json.JSONDecodeError:
            pass
        # tolerate banner lines before a (possibly pretty-printed) object
        start = out.find("{")
        if start >= 0:
            try:
                return json.loads(out[start:])
            except json.JSONDecodeError:
                pass
        raise ClientUiError("assertion",
                            f"mct {' '.join(map(str, args))} produced no JSON "
                            f"object: {out[:300]!r}")


# ---------------------------------------------------------------------------
# Scenario schema validation + execution

def validate_scenario(raw: dict, path: str) -> list:
    errors = []
    for key in ("name", "group", "observable", "failure", "steps"):
        if key not in raw:
            errors.append(f"{path}: missing '{key}'")
    if errors:
        return errors
    if not isinstance(raw["steps"], list) or not raw["steps"]:
        errors.append(f"{path}: steps must be a non-empty list")
        return errors
    saw_expect = False
    for i, step in enumerate(raw["steps"]):
        stype = step.get("type", "mct")
        if stype not in STEP_TYPES:
            errors.append(f"{path} step {i}: unknown type {stype!r}")
            continue
        expects = _step_expects(step)
        saw_expect = saw_expect or bool(expects) or stype in (
            "npc-action", "form", "client2")
        if stype == "npc-action" and not step.get("label"):
            errors.append(f"{path} step {i}: npc-action needs label")
        if not expects and not step.get("setup") and stype in ("rcon", "mct"):
            errors.append(f"{path} step {i} ({step.get('name', stype)}): "
                          f"{stype} steps need an expectation or setup:true")
        if stype == "mct" and not step.get("args"):
            errors.append(f"{path} step {i}: mct step needs args")
        if stype == "rcon" and not step.get("command"):
            errors.append(f"{path} step {i}: rcon step needs command")
        if stype == "form" and not isinstance(step.get("fill", []), list):
            errors.append(f"{path} step {i}: form fill must be a list")
        caps = [step["capture"]] if step.get("capture") else []
        caps += step.get("captures", [])
        for cap in caps:
            if not cap.get("name") or not cap.get("regex"):
                errors.append(f"{path} step {i}: capture needs name+regex")
    if not saw_expect:
        errors.append(f"{path}: no step carries an expectation")
    return errors


def _step_expects(step: dict) -> list:
    return [k for k in ("expect", "expectContains", "expectRegex",
                        "expectNotContains", "expectAbsentRegex",
                        "expectTitleRegex", "capture", "captures")
            if k in step]


def _expect_json(reply, rules: list, label: str):
    for rule in rules:
        if "rawRegex" in rule:
            if not re.search(rule["rawRegex"], reply if isinstance(reply, str)
                             else json.dumps(reply, ensure_ascii=False)):
                raise ClientUiError(
                    "assertion", f"{label}: raw output misses "
                    f"/{rule['rawRegex']}/")
            continue
        path = rule.get("path", "")
        node = reply
        for part in path.split(".") if path else []:
            if isinstance(node, dict):
                node = node.get(part)
            elif isinstance(node, list) and part.isdigit():
                node = node[int(part)] if int(part) < len(node) else None
            else:
                node = None
            if node is None:
                break
        text = json.dumps(node) if isinstance(node, (dict, list)) \
            else ("" if node is None else str(node))
        if "equals" in rule and node != rule["equals"]:
            raise ClientUiError("assertion",
                                f"{label}: {path}={node!r} != {rule['equals']!r}")
        if "contains" in rule and rule["contains"] not in text:
            raise ClientUiError("assertion",
                                f"{label}: {path} misses {rule['contains']!r}")
        if "regex" in rule and not re.search(rule["regex"], text):
            raise ClientUiError("assertion",
                                f"{label}: {path} misses /{rule['regex']}/")
        if "gte" in rule and not (isinstance(node, (int, float))
                                  and node >= rule["gte"]):
            raise ClientUiError("assertion",
                                f"{label}: {path}={node!r} < {rule['gte']}")
        if "gt" in rule and not (isinstance(node, (int, float))
                                 and node > rule["gt"]):
            raise ClientUiError("assertion",
                                f"{label}: {path}={node!r} <= {rule['gt']}")
        if "occurrences" in rule:
            count = len(re.findall(rule["occurrences"], text if path else
                                   json.dumps(reply, ensure_ascii=False)))
            expected = int(rule.get("count", 1))
            if count != expected:
                raise ClientUiError(
                    "assertion",
                    f"{label}: /{rule['occurrences']}/ found {count}x, "
                    f"expected {expected}x")


def _expect_text(text: str, step: dict, label: str):
    if "expectContains" in step and step["expectContains"] not in text:
        raise ClientUiError("assertion",
                            f"{label}: output misses {step['expectContains']!r}")
    if "expectRegex" in step and not re.search(step["expectRegex"], text):
        raise ClientUiError("assertion",
                            f"{label}: output misses /{step['expectRegex']}/")
    if "expectNotContains" in step and step["expectNotContains"] in text:
        raise ClientUiError("assertion",
                            f"{label}: output contains forbidden "
                            f"{step['expectNotContains']!r}")
    if "expectAbsentRegex" in step \
            and re.search(step["expectAbsentRegex"], text):
        raise ClientUiError("assertion",
                            f"{label}: output matches forbidden "
                            f"/{step['expectAbsentRegex']}/")


@dataclass
class ClientContext:
    mct: Mct
    rcon: sh.RconClient
    artifacts: str
    transcript: Transcript
    manifest: dict
    env: dict
    client_name: str
    game_port: int
    launch_client: object      # callable()
    wait_ready: object         # callable()
    jar_path: str = ""
    dep_paths: list = field(default_factory=list)
    vars: dict = field(default_factory=dict)


def _interp(text: str, ctx: ClientContext) -> str:
    """{name} placeholders resolve from captured values."""
    def repl(match):
        name = match.group(1)
        if name not in ctx.vars:
            raise ClientUiError("assertion",
                                f"unknown capture variable {name!r}")
        return str(ctx.vars[name])
    return re.sub(r"\{([A-Za-z_][A-Za-z0-9_]*)\}", repl, text)


def _capture(step: dict, text: str, ctx: ClientContext):
    caps = []
    if step.get("capture"):
        caps.append(step["capture"])
    caps.extend(step.get("captures", []))
    for cap in caps:
        m = re.search(cap["regex"], text, re.S)
        if not m:
            raise ClientUiError(
                "assertion",
                f"capture {cap['name']!r} found no /{cap['regex']}/ match")
        ctx.vars[cap["name"]] = m.group(1) if m.groups() else m.group(0)
        ctx.transcript.record("capture",
                              {cap["name"]: ctx.vars[cap["name"]]})


def _form_fill(step: dict, ctx: ClientContext):
    """Fill the StrajaFormScreen via real keyboard input: Tab cycles
    widgets in declaration order (fields first, then Submit, Cancel), a
    focused EditBox takes `input type`, Enter activates the focused
    button. No pixel math — fully deterministic."""
    ctx.mct(["gui", "wait-open", "--timeout", "20"], timeout=40)
    info = ctx.mct.json(["gui", "info"], timeout=30)
    ctx.transcript.record("form-info", info)
    title_rule = step.get("expectTitleRegex")
    if title_rule:
        blob = json.dumps(info, ensure_ascii=False)
        if not re.search(title_rule, blob):
            raise ClientUiError(
                "assertion", f"form title misses /{title_rule}/: {blob[:300]}")
    fields = step.get("fill", [])
    for value in fields:
        ctx.mct(["input", "key", "press", "tab"], timeout=15)
        ctx.mct(["input", "type", _interp(str(value), ctx)], timeout=30)
    if step.get("close"):
        ctx.mct(["input", "key", "press", "escape"], timeout=15)
    elif step.get("submit"):
        ctx.mct(["input", "key", "press", "tab"], timeout=15)
        ctx.mct(["input", "key", "press", "enter"], timeout=15)
    elif step.get("cancel"):
        ctx.mct(["input", "key", "press", "tab"], timeout=15)
        ctx.mct(["input", "key", "press", "tab"], timeout=15)
        ctx.mct(["input", "key", "press", "enter"], timeout=15)


def _walk_components(node, out: list):
    """Collect (text, clickEvent.value) pairs from a serialized component
    tree — siblings may sit under extra/siblings/with or bare lists."""
    if isinstance(node, list):
        for item in node:
            _walk_components(item, out)
        return
    if not isinstance(node, dict):
        return
    ce = node.get("clickEvent")
    if isinstance(ce, dict) and ce.get("action") == "run_command" \
            and ce.get("value"):
        out.append((str(node.get("text", "")), str(ce["value"])))
    for key in ("extra", "siblings", "with", "hoverEvent"):
        child = node.get(key)
        if isinstance(child, (dict, list)):
            _walk_components(child, out)
    he = node.get("hoverEvent")
    if isinstance(he, dict):
        _walk_components(he.get("value") or he.get("contents"), out)


def _raw_messages(reply) -> list:
    """Pull serialized component JSON strings out of a chat-history reply."""
    raws = []
    def scan(node):
        if isinstance(node, dict):
            for key, value in node.items():
                if key == "raw" and isinstance(value, str):
                    raws.append(value)
                else:
                    scan(value)
        elif isinstance(node, list):
            for item in node:
                scan(item)
    scan(reply)
    return raws


def _npc_action(step: dict, ctx: ClientContext):
    """Click a chat-menu action: read `chat history`, parse each message's
    raw serialized component, find the labelled run_command clickEvent,
    and send it back as a real player command."""
    label = step["label"]
    last = int(step.get("historyLast", 20))
    reply = ctx.mct.json(["chat", "history", "--last", str(last)],
                         timeout=45)
    found = []
    for raw in _raw_messages(reply):
        try:
            root = json.loads(raw)
        except json.JSONDecodeError:
            continue
        _walk_components(root, found)
    ctx.transcript.record("npc-actions",
                          [{"text": t, "command": c} for t, c in found])
    needle = f"[{label}]"
    command = next((c for t, c in found if needle in t or label in t), None)
    if command is None:
        raise ClientUiError(
            "assertion",
            f"no clickable action {label!r} in recent chat "
            f"({len(found)} actions seen: "
            f"{[t for t, _ in found][:10]})")
    out = ctx.mct(["chat", "send", command], timeout=45)
    _expect_text(out, step, f"npc-action {label}")


def _client2(step: dict, ctx: ClientContext):
    """Second real client (capacity-gated by requiresClients:2): create,
    launch, join the same server, and run an optional health query. Only
    attempted where hosted capacity permits; failure here is a real
    client capability failure for the recorded step."""
    action = step.get("action", "join")
    name2 = str(ctx.client_name) + "-2"
    account2 = str(ctx.manifest["client"]["account"]) + "_2"
    mct2 = Mct(ctx.mct.binary, name2, ctx.mct.env, ctx.transcript)
    if action == "join":
        create_client(mct2, ctx.manifest, ctx.jar_path, ctx.dep_paths,
                      ctx.mct.env,
                      lambda msg: ctx.transcript.record("client2", msg))
        mct2(["client", "launch", name2,
              "--server", f"127.0.0.1:{ctx.game_port}",
              "--account", account2, "--force"], timeout=120)
        mct2(["client", "wait-ready", name2,
              "--timeout",
              str(ctx.manifest["client"]["readyTimeoutSeconds"])],
             timeout=ctx.manifest["client"]["readyTimeoutSeconds"] + 60)
    elif action == "stop":
        mct2(["client", "stop", name2], timeout=60)
        return
    elif action == "mct":
        args = [_interp(str(a), ctx) for a in step["args"]]
        if step.get("expect"):
            reply = mct2.json(args, timeout=int(step.get("timeout", 60)))
            _expect_json(reply, step["expect"], "client2")
        else:
            mct2(args, timeout=int(step.get("timeout", 60)))
        return
    else:
        raise ClientUiError("assertion", f"unknown client2 action {action!r}")


def _exec_step(step: dict, ctx: ClientContext, report_steps: list):
    stype = step.get("type", "mct")
    label = step.get("name") or stype
    record = {"name": label, "type": stype, "status": PASS}
    report_steps.append(record)
    timeout = int(step.get("timeout", 60))
    try:
        if stype == "sleep":
            time.sleep(float(step.get("seconds", 1)))
        elif stype == "screenshot":
            out = os.path.join(ctx.artifacts, "screenshots",
                               f"{step['name']}.png")
            os.makedirs(os.path.dirname(out), exist_ok=True)
            ctx.mct(["screenshot", "--output", out, "--timeout", "45"],
                    timeout=90)
            record["artifact"] = out
        elif stype == "client":
            action = step.get("action")
            if action == "stop":
                ctx.mct(["client", "stop", ctx.client_name], timeout=timeout)
            elif action == "start":
                ctx.launch_client()
                ctx.wait_ready()
            elif action == "rejoin":
                ctx.mct(["client", "stop", ctx.client_name], timeout=timeout)
                ctx.launch_client()
                ctx.wait_ready()
            else:
                raise ClientUiError("assertion",
                                    f"unknown client action {action!r}")
        elif stype == "var":
            source = str(ctx.vars.get(step.get("from", ""), ""))
            for rule in step.get("rules", []):
                if re.search(rule["regex"], source, re.S):
                    ctx.vars[step["name"]] = rule["value"]
                    break
            if step["name"] not in ctx.vars:
                if "default" in step:
                    ctx.vars[step["name"]] = step["default"]
                else:
                    raise ClientUiError(
                        "assertion",
                        f"var {step['name']!r}: no rule matched "
                        f"{source[:200]!r}")
        elif stype == "client2":
            _client2(step, ctx)
        elif stype == "npc-action":
            _npc_action(step, ctx)
        elif stype == "form":
            _form_fill(step, ctx)
        elif stype == "rcon":
            cmd = _interp(step["command"], ctx)
            out = ctx.rcon.execute(cmd, timeout=timeout)
            ctx.transcript.record("rcon " + cmd, out[:2000])
            _expect_text(out, step, label)
            _capture(step, out, ctx)
        elif stype == "mct":
            args = [_interp(str(a), ctx) for a in step["args"]]
            if step.get("expect") or step.get("capture") \
                    or step.get("captures"):
                reply = ctx.mct.json(args, timeout=timeout)
                if step.get("expect"):
                    _expect_json(reply, step["expect"], label)
                _capture(step, json.dumps(reply, ensure_ascii=False), ctx)
            else:
                out = ctx.mct(args, timeout=timeout)
                _expect_text(out, step, label)
    except ClientUiError as exc:
        record["status"] = FAIL
        record["error"] = f"{exc.kind}: {exc.message}"
        raise
    except sh.HarnessError as exc:
        record["status"] = FAIL
        record["error"] = f"rcon {exc.kind}: {exc.message}"
        raise ClientUiError("assertion",
                            f"{label}: rcon failed — {exc.message}") from exc


def run_scenario(scenario: dict, ctx: ClientContext, log) -> dict:
    result = {"name": scenario["name"], "group": scenario["group"],
              "status": PASS, "steps": [], "skipped": False}
    timeout = float(scenario.get("timeoutSeconds", 180))
    deadline = time.time() + timeout
    failed = None
    try:
        for step in scenario["steps"]:
            if time.time() > deadline:
                raise ClientUiError("timeout",
                                    f"scenario exceeded {timeout:.0f}s")
            _exec_step(step, ctx, result["steps"])
    except ClientUiError as exc:
        failed = exc
        result["status"] = FAIL
        result["error"] = f"{exc.kind}: {exc.message}"
        log(f"  FAIL {scenario['name']}: {exc.kind} — {exc.message}")
    # cleanup steps run in declaration order even after a failure
    for step in scenario.get("cleanup", []):
        try:
            _exec_step(step, ctx, result["steps"])
        except ClientUiError as exc:
            result["steps"][-1].update(
                {"status": FAIL, "error": f"{exc.kind}: {exc.message}"})
            if failed is None:
                failed = exc
                result["status"] = FAIL
                result["error"] = f"cleanup {exc.kind}: {exc.message}"
    if result["status"] == PASS:
        log(f"  pass {scenario['name']} ({len(result['steps'])} steps)")
    return result


def capacity_ok(manifest: dict) -> tuple:
    cap = manifest.get("capacity", {})
    cpus = os.cpu_count() or 0
    mem_gib = 0.0
    try:
        with open("/proc/meminfo", "r", encoding="utf-8") as fh:
            for line in fh:
                if line.startswith("MemTotal:"):
                    mem_gib = int(line.split()[1]) / (1 << 20)
    except OSError:
        mem_gib = 0.0
    need_cpu = int(cap.get("minCpus", 4))
    need_mem = float(cap.get("minMemGiB", 8))
    return (cpus >= need_cpu and mem_gib >= need_mem,
            f"{cpus} cpus / {mem_gib:.1f} GiB (need {need_cpu}/{need_mem:g})")


# ---------------------------------------------------------------------------
# Reports

def write_reports(report: dict, out_dir: str):
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "client-report.json"), "w",
              encoding="utf-8") as fh:
        json.dump(report, fh, indent=2, ensure_ascii=False)

    root = ET.Element("testsuite", {
        "name": "straja.client-ui",
        "tests": str(len(report["scenarios"])),
        "failures": str(sum(1 for s in report["scenarios"]
                            if s["status"] == FAIL)),
        "skipped": str(sum(1 for s in report["scenarios"]
                           if s["status"] == SKIPPED))})
    for sc in report["scenarios"]:
        case = ET.SubElement(root, "testcase", {
            "classname": "straja.client_ui", "name": sc["name"]})
        if sc["status"] == FAIL:
            node = ET.SubElement(case, "failure", {"type": "assertion"})
            node.text = sc.get("error", "")
        elif sc["status"] == SKIPPED:
            node = ET.SubElement(case, "skipped")
            node.text = sc.get("reason", "")
    tree = ET.ElementTree(root)
    ET.indent(tree, space="  ")
    tree.write(os.path.join(out_dir, "junit.xml"), encoding="utf-8",
               xml_declaration=True)

    with open(os.path.join(out_dir, "transcript.json"), "w",
              encoding="utf-8") as fh:
        json.dump(report["transcript"], fh, indent=2, ensure_ascii=False)

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        lines = ["## Advisory client UI", "",
                 f"Verdict: **{report['verdict']}** "
                 f"({report.get('verdict_reason', '')})", "",
                 "| Scenario | Status |", "|---|---|"]
        for sc in report["scenarios"]:
            lines.append(f"| {sc['name']} | {sc['status']}"
                         f"{' — ' + sc.get('reason', '') if sc.get('reason') else ''} |")
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + "\n")


# ---------------------------------------------------------------------------
# Client lifecycle

def _mct_env(args, manifest: dict) -> dict:
    env = dict(os.environ)
    mct_home = os.path.join(args.work_root, "mct-home")
    env["MCT_HOME"] = mct_home
    env["MCT_CACHE_DIR"] = os.path.join(mct_home, "cache")
    env["MCT_SKILL_TARGETS"] = "none"
    os.makedirs(env["MCT_CACHE_DIR"], exist_ok=True)
    return env


def create_client(mct: Mct, manifest: dict, jar_path: str,
                  dep_paths: list, env: dict, log) -> str:
    client = manifest["client"]
    name = client["name"]
    out = mct.json(["client", "create", name,
                    "--loader", client["loader"],
                    "--version", client["minecraftVersion"],
                    "--account", client["account"],
                    "--java", client.get("java", "java")], timeout=900)
    mods_dir = out.get("modsDir")
    if not mods_dir or not os.path.isdir(mods_dir):
        raise ClientUiError("client_boot",
                            f"mct client create returned no modsDir: {out}")
    # mct has no locale flag — pin the language via options.txt so
    # assertions never depend on the host OS locale.
    minecraft_dir = out.get("minecraftDir")
    if minecraft_dir and os.path.isdir(minecraft_dir):
        options = os.path.join(minecraft_dir, "options.txt")
        existing = ""
        if os.path.exists(options):
            with open(options, "r", encoding="utf-8") as fh:
                existing = fh.read()
        lines = [l for l in existing.splitlines() if not l.startswith("lang:")]
        lines.append(f"lang:{client.get('language', 'en_us')}")
        with open(options, "w", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + "\n")
    # checksum-verify the injected automation bridge
    bridge = os.path.join(mods_dir, manifest["clientMod"]["file"])
    if not os.path.exists(bridge):
        # fall back: copy the verified jar into the instance ourselves
        cache_jar = seed_client_mod(manifest, env, log)
        shutil.copy2(cache_jar, bridge)
    sh.verify_artifact(bridge, sha256=manifest["clientMod"]["sha256"],
                       label="mct client-mod bridge")
    for path in [jar_path, *dep_paths]:
        shutil.copy2(path, os.path.join(mods_dir, os.path.basename(path)))
    log(f"client mods staged: {sorted(os.listdir(mods_dir))}")
    return name


def run_client_ui(args, log=print) -> dict:
    """Provision server + real client, run scenarios, emit reports."""
    manifest = load_client_manifest(args.manifest)
    report = {"verdict": FAIL, "verdict_reason": "not run",
              "scenarios": [], "transcript": []}
    proc = xvfb = None
    log_fh = None
    rcon_password = None
    transcript = Transcript()
    try:
        env = _mct_env(args, manifest)
        mct_bin = preflight(manifest, env, log)
        seed_client_mod(manifest, env, log)
        xvfb = start_xvfb(manifest, env, log)

        server_dir = os.path.join(args.work_root, "client-server")
        server_log = os.path.join(server_dir, "logs", "server.log")
        server_manifest = sh.load_manifest(args.server_manifest)
        prof = server_manifest["profiles"][args.profile]
        sh.verify_artifact(args.jar, sha256=args.jar_sha256,
                           label="straja artifact")
        argfile = sh.provision_neoforge(
            server_dir, server_manifest,
            os.path.join(args.dep_cache, "neoforge"), log)
        dep_paths = [sh.download_dependency(server_manifest["dependencies"][d],
                                            args.dep_cache)
                     for d in prof["deps"]]
        sh.stage_mods(server_dir, args.jar, dep_paths, args.jar_sha256)
        game_port, rcon_port = sh.free_port(), sh.free_port()
        rcon_password = __import__("secrets").token_urlsafe(24)
        sh.write_server_properties(server_dir, game_port, rcon_port,
                                   rcon_password)
        with open(os.path.join(server_dir, "eula.txt"), "w",
                  encoding="utf-8") as fh:
            fh.write("eula=true\n")
        sh.write_straja_config(server_dir, prof["config"])
        os.makedirs(os.path.dirname(server_log), exist_ok=True)
        proc, log_fh = sh.start_server(server_dir, argfile, server_log,
                                       heap=args.heap)
        rcon = sh.RconClient("127.0.0.1", rcon_port, rcon_password)
        sh.wait_ready(proc, server_log, rcon, prof["timeoutSeconds"])
        log("combined server ready")

        mct = Mct(mct_bin, manifest["client"]["name"], env, transcript)
        client_name = create_client(mct, manifest, args.jar, dep_paths,
                                    env, log)

        def launch():
            mct(["client", "launch", client_name,
                 "--server", f"127.0.0.1:{game_port}",
                 "--account", manifest["client"]["account"],
                 "--force"], timeout=120)

        def wait_ready():
            mct(["client", "wait-ready", client_name,
                 "--timeout", str(manifest["client"]["readyTimeoutSeconds"])],
                timeout=manifest["client"]["readyTimeoutSeconds"] + 60)

        launch()
        # Phase 1: WebSocket up => the client process + automation bridge are
        # alive; failure here means the capability itself is absent (blocked).
        try:
            mct(["client", "wait-ready", client_name, "--no-world-check",
                 "--timeout", "120"], timeout=180)
        except ClientUiError as exc:
            raise ClientUiError(
                "client_join_ws",
                f"client process/bridge never came up: {exc.message}") from exc
        # Phase 2: in-world join — a refusal here is a real product failure
        # (registry mismatch, rejection), not missing infrastructure.
        try:
            wait_ready()
        except ClientUiError as exc:
            raise ClientUiError(
                "assertion", f"client never joined the world: {exc.message}") \
                from exc
        log("client joined; running scenarios")

        ctx = ClientContext(mct=mct, rcon=rcon, artifacts=args.artifacts_dir,
                            transcript=transcript, manifest=manifest,
                            env=env, client_name=client_name,
                            game_port=game_port,
                            launch_client=launch, wait_ready=wait_ready,
                            jar_path=args.jar, dep_paths=dep_paths,
                            vars={"account": manifest["client"]["account"]})

        ok_capacity, cap_reason = capacity_ok(manifest)
        for path in sorted(_scenario_files(args.scenarios_dir)):
            with open(path, "r", encoding="utf-8") as fh:
                raw = json.load(fh)
            errors = validate_scenario(raw, path)
            if errors:
                report["scenarios"].append(
                    {"name": os.path.basename(path), "group": "schema",
                     "status": FAIL, "steps": [], "skipped": False,
                     "error": "; ".join(errors)})
                continue
            if int(raw.get("requiresClients", 1)) > 1 and not ok_capacity:
                report["scenarios"].append(
                    {"name": raw["name"], "group": raw["group"],
                     "status": SKIPPED, "steps": [], "skipped": True,
                     "reason": f"insufficient hosted capacity: {cap_reason}"})
                continue
            report["scenarios"].append(run_scenario(raw, ctx, log))

        if not report["scenarios"]:
            raise ClientUiError("assertion", "no scenarios executed")
        verdict = PASS if all(s["status"] in (PASS, SKIPPED)
                              for s in report["scenarios"]) else FAIL
        report["verdict"] = verdict
        report["verdict_reason"] = "all scenarios held" if verdict == PASS \
            else "one or more scenario assertions failed"
        if proc.poll() is None:
            sh.clean_shutdown(rcon, proc)
    except ClientUiError as exc:
        report["verdict"] = BLOCKED if exc.kind in _BLOCK_KINDS else FAIL
        report["verdict_reason"] = f"{exc.kind}: {exc.message}"
        log(f"client-ui {report['verdict']}: {exc.kind} — {exc.message}")
    except sh.HarnessError as exc:
        report["verdict"] = BLOCKED
        report["verdict_reason"] = f"server harness {exc.kind}: {exc.message}"
        log(f"client-ui blocked: {exc.kind} — {exc.message}")
    finally:
        if proc is not None:
            sh.kill_tree(proc)
        if xvfb is not None:
            xvfb.terminate()
        if log_fh is not None:
            log_fh.close()
        report["transcript"] = transcript.entries
        write_reports(report, args.artifacts_dir)
        server_dir = os.path.join(args.work_root, "client-server")
        if os.path.isdir(server_dir):
            sh._retain(server_dir, args.artifacts_dir,
                       [rcon_password] if rcon_password else [])
    return report


def _scenario_files(directory: str) -> list:
    if not os.path.isdir(directory):
        return []
    return [os.path.join(directory, f) for f in os.listdir(directory)
            if f.endswith(".json")]


# ---------------------------------------------------------------------------
# CLI

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    run = sub.add_parser("run", help="full client UI scenario run")
    run.add_argument("--manifest", default="tools/ci/client_manifest.json")
    run.add_argument("--server-manifest",
                     default="tools/ci/server_manifest.json")
    run.add_argument("--profile", default="combined")
    run.add_argument("--jar", required=True)
    run.add_argument("--jar-sha256", default=None)
    run.add_argument("--scenarios-dir",
                     default="tools/ci/client_scenarios")
    run.add_argument("--work-root", required=True)
    run.add_argument("--artifacts-dir", required=True)
    run.add_argument("--dep-cache", required=True)
    run.add_argument("--heap", default="3G")
    args = parser.parse_args(argv)
    report = run_client_ui(args)
    return 0 if report["verdict"] == PASS else 1


if __name__ == "__main__":
    sys.exit(main())
