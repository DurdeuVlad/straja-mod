#!/usr/bin/env python3
"""Declarative scenario runner for the packaged combined-profile server.

Each JSON file in --scenarios declares ordered steps with observable
expectations. The runner provisions one disposable dedicated server via
server_harness, executes every scenario fail-fast, supports full server
restarts plus stopped-world fixture injection (corruption / KubeJS
migration), and emits JSON + JUnit reports.

Scenario file schema:
    {
      "name": "duty-patrol-economy", "group": "duty", "blocking": true,
      "timeoutSeconds": 300,
      "observable": "what a pass looks like",
      "failure": "what a failure looks like",
      "prerequisites": ["fixture or config the scenario relies on"],
      "steps": [ ... ]
    }

Step types:
    command (default): {command, expectContains|expectRegex|expectNotContains}
    log:               {expectRegex | expectAbsentRegex}   — grep server log
    sleep:             {seconds}
    restart:           {whileStopped: [inject steps]}      — stop → inject → start
    inject-corrupt:    {store: "fines"}                    — stopped only
    inject-kubejs:     {dir: "fixture", entries: {k: json}} — stopped only
    expect-file:       {path, gunzip?, contains}           — server-dir file check

Any step may set "cleanup": true — cleanup steps always run at scenario
end (in declaration order) even after a failure.
"""
from __future__ import annotations

import argparse
import gzip
import json
import os
import re
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field

sys.path.insert(0, os.path.dirname(__file__))
import nbt_write  # noqa: E402
import server_harness as sh  # noqa: E402

STEP_TYPES = {"command", "log", "sleep", "restart", "inject-corrupt",
              "inject-kubejs", "expect-file"}
REQUIRED_GROUPS = {"bootstrap", "personnel", "duty-economy", "missions",
                   "custody-prison", "fines-complaints", "rooms-archive",
                   "reports-emergency", "restart", "backup-migration"}
def _unsafe_rel(path: str) -> bool:
    """Reject paths that escape the server dir: '..', absolute, drive letters."""
    if not path or "\0" in path:
        return True
    if path.startswith(("/", "\\")) or re.match(r"^[A-Za-z]:", path):
        return True
    return ".." in re.split(r"[/\\]", path)


@dataclass
class StepResult:
    name: str
    status: str
    detail: str = ""
    duration_s: float = 0.0


@dataclass
class ScenarioResult:
    name: str
    group: str
    blocking: bool
    status: str = sh.FAIL
    steps: list = field(default_factory=list)
    notes: list = field(default_factory=list)
    dep_skipped: bool = False


# ---------------------------------------------------------------------------
# Scenario validation

def load_scenarios(directory: str) -> list:
    """Load + validate every *.json scenario; deterministic order by name."""
    if not os.path.isdir(directory):
        raise sh.HarnessError(sh.ERROR, f"scenario dir missing: {directory}")
    scenarios = []
    for fname in sorted(os.listdir(directory)):
        if not fname.endswith(".json"):
            continue
        path = os.path.join(directory, fname)
        try:
            with open(path, "r", encoding="utf-8") as fh:
                sc = json.load(fh)
        except (OSError, json.JSONDecodeError) as exc:
            raise sh.HarnessError(sh.ERROR, f"{fname}: unreadable: {exc}")
        _validate_scenario(sc, fname)
        sc["_file"] = fname
        scenarios.append(sc)
    return scenarios


def _validate_scenario(sc: dict, fname: str):
    for key in ("name", "group", "steps"):
        if not sc.get(key):
            raise sh.HarnessError(sh.ERROR, f"{fname}: missing {key!r}")
    if not isinstance(sc["steps"], list) or not sc["steps"]:
        raise sh.HarnessError(sh.ERROR, f"{fname}: steps must be a non-empty list")
    for i, step in enumerate(sc["steps"]):
        stype = step.get("type", "command")
        if stype not in STEP_TYPES:
            raise sh.HarnessError(sh.ERROR, f"{fname} step {i}: bad type {stype!r}")
        if not step.get("name"):
            raise sh.HarnessError(sh.ERROR, f"{fname} step {i}: name required")
        if stype == "command":
            if not step.get("command"):
                raise sh.HarnessError(sh.ERROR, f"{fname} step {i}: command required")
            if not any(k in step for k in
                       ("expectContains", "expectRegex", "expectNotContains",
                        "expectAbsentRegex")):
                raise sh.HarnessError(
                    sh.ERROR, f"{fname} step {i}: command needs an expectation")
        if stype == "inject-corrupt" and not re.match(r"^[a-z_]+$", step.get("store", "")):
            raise sh.HarnessError(sh.ERROR, f"{fname} step {i}: bad store name")
        if stype in ("inject-corrupt", "inject-kubejs", "expect-file"):
            for key in ("path", "dir"):
                if key in step and _unsafe_rel(step[key]):
                    raise sh.HarnessError(sh.ERROR,
                                          f"{fname} step {i}: unsafe {key} {step[key]!r}")
    deps = sc.get("requiresDeps", [])
    if not isinstance(deps, list) or not all(isinstance(d, str) for d in deps):
        raise sh.HarnessError(sh.ERROR, f"{fname}: requiresDeps must be a string list")


# ---------------------------------------------------------------------------
# Step execution

class _Ctx:
    """Mutable per-suite context: live server + RCON handle."""

    def __init__(self):
        self.rcon = None
        self.proc = None
        self.log_fh = None
        self.server_dir = ""
        self.server_log = ""
        self.argfile = ""
        self.heap = "2G"
        self.timeout_s = 600
        self.stopped = False
        self.rcon_lost = False


def _exec_command(ctx: _Ctx, step: dict) -> str:
    if ctx.rcon_lost:
        raise sh.HarnessError(sh.SKIPPED, "rcon lost earlier — cannot run commands")
    if ctx.stopped:
        raise sh.HarnessError(sh.FAIL, "server is stopped — restart first")
    try:
        resp = ctx.rcon.execute(step["command"])
    except sh.HarnessError as exc:
        ctx.rcon_lost = True
        raise
    if "expectContains" in step and step["expectContains"] not in resp:
        raise sh.HarnessError(
            sh.FAIL, f"{step['command']!r}: missing {step['expectContains']!r} "
                     f"in response {resp[-200:]!r}")
    if "expectRegex" in step and not re.search(step["expectRegex"], resp, re.S):
        raise sh.HarnessError(
            sh.FAIL, f"{step['command']!r}: regex {step['expectRegex']!r} "
                     f"not in response {resp[-200:]!r}")
    if "expectNotContains" in step and step["expectNotContains"] in resp:
        raise sh.HarnessError(
            sh.FAIL, f"{step['command']!r}: forbidden {step['expectNotContains']!r} "
                     f"present in {resp[-200:]!r}")
    if "expectAbsentRegex" in step and re.search(step["expectAbsentRegex"], resp, re.S):
        raise sh.HarnessError(
            sh.FAIL, f"{step['command']!r}: response unexpectedly matches "
                     f"/{step['expectAbsentRegex']}/")
    return resp.strip()[:120]


def _exec_log(ctx: _Ctx, step: dict) -> str:
    text = sh._read_log(ctx.server_log)
    if "expectRegex" in step and not re.search(step["expectRegex"], text, re.S):
        raise sh.HarnessError(sh.FAIL,
                              f"log lacks /{step['expectRegex']}/")
    if "expectAbsentRegex" in step and re.search(step["expectAbsentRegex"], text, re.S):
        raise sh.HarnessError(sh.FAIL,
                              f"log unexpectedly matches /{step['expectAbsentRegex']}/")
    return "log expectation satisfied"


def _exec_inject_corrupt(ctx: _Ctx, step: dict):
    if not ctx.stopped:
        raise sh.HarnessError(sh.FAIL,
                              "inject-corrupt requires a stopped server")
    path = os.path.join(ctx.server_dir, "world", "data",
                        f"straja_{step['store']}.dat")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    nbt_write.write_store_dat(path, {"json": "{corrupt-payload"})
    return f"corrupt {step['store']} store injected"


def _exec_inject_kubejs(ctx: _Ctx, step: dict):
    if not ctx.stopped:
        raise sh.HarnessError(sh.FAIL,
                              "inject-kubejs requires a stopped server")
    fixture = os.path.join(ctx.server_dir, step["dir"])
    os.makedirs(fixture, exist_ok=True)
    nbt_write.write_kubejs(os.path.join(fixture, "kubejs_persistent_data.nbt"),
                           step.get("entries", {}))
    return f"kubejs fixture at {step['dir']}"


def _exec_expect_file(ctx: _Ctx, step: dict) -> str:
    """Assert on a file inside the server dir. Retries briefly — `save-all
    flush` returns before the SavedData write always lands on disk."""
    path = os.path.join(ctx.server_dir, step["path"])
    for _ in range(10):
        if os.path.isfile(path):
            break
        time.sleep(0.5)
    if not os.path.isfile(path):
        raise sh.HarnessError(sh.FAIL, f"missing file {step['path']}")
    with open(path, "rb") as fh:
        raw = fh.read()
    if step.get("gunzip"):
        try:
            raw = gzip.decompress(raw)
        except OSError as exc:
            raise sh.HarnessError(sh.FAIL, f"{step['path']}: gunzip failed: {exc}")
    needle = step["contains"].encode("utf-8")
    count = raw.count(needle)
    if count == 0:
        raise sh.HarnessError(sh.FAIL,
                              f"{step['path']} lacks {step['contains']!r}")
    if "occurrences" in step and count != int(step["occurrences"]):
        raise sh.HarnessError(
            sh.FAIL, f"{step['path']}: {step['contains']!r} occurs {count}x, "
                     f"expected {step['occurrences']}x")
    return f"{step['path']} contains {step['contains']!r} ({count}x)"


def _exec_restart(ctx: _Ctx, step: dict) -> str:
    """Full stop → optional stopped-world injections → start → ready."""
    if ctx.stopped:
        raise sh.HarnessError(sh.FAIL, "restart requested while stopped")
    sh.clean_shutdown(ctx.rcon, ctx.proc)
    if ctx.log_fh:
        ctx.log_fh.close()
    ctx.stopped = True
    for sub in step.get("whileStopped", []):
        _run_inject(ctx, sub)
    ctx.proc, ctx.log_fh = sh.start_server(ctx.server_dir, ctx.argfile,
                                           ctx.server_log, heap=ctx.heap,
                                           append=True)
    sh.wait_ready(ctx.proc, ctx.server_log, ctx.rcon, ctx.timeout_s)
    ctx.stopped = False
    return "server restarted clean"


def _run_inject(ctx: _Ctx, step: dict):
    stype = step.get("type")
    if stype == "inject-corrupt":
        _exec_inject_corrupt(ctx, step)
    elif stype == "inject-kubejs":
        _exec_inject_kubejs(ctx, step)
    else:
        raise sh.HarnessError(sh.ERROR,
                              f"whileStopped only accepts inject steps, got {stype!r}")


def _run_step(ctx: _Ctx, step: dict) -> str:
    stype = step.get("type", "command")
    if stype == "command":
        return _exec_command(ctx, step)
    if stype == "log":
        return _exec_log(ctx, step)
    if stype == "sleep":
        time.sleep(min(float(step["seconds"]), 120))
        return f"slept {step['seconds']}s"
    if stype == "restart":
        return _exec_restart(ctx, step)
    if stype in ("inject-corrupt", "inject-kubejs"):
        return _run_inject(ctx, step)
    if stype == "expect-file":
        return _exec_expect_file(ctx, step)
    raise sh.HarnessError(sh.ERROR, f"unknown step type {stype!r}")


def run_scenario(ctx: _Ctx, sc: dict, log_fn) -> ScenarioResult:
    result = ScenarioResult(name=sc["name"], group=sc["group"],
                            blocking=bool(sc.get("blocking", True)))
    deadline = time.time() + float(sc.get("timeoutSeconds", 300))
    failed = False
    for step in sc["steps"]:
        is_cleanup = bool(step.get("cleanup"))
        if time.time() > deadline and not is_cleanup:
            result.steps.append(StepResult(step["name"], sh.TIMEOUT,
                                           "scenario timeout exceeded"))
            break
        if failed and not is_cleanup:
            result.steps.append(StepResult(step["name"], sh.SKIPPED,
                                           "skipped after earlier failure"))
            continue
        start = time.time()
        try:
            detail = _run_step(ctx, step)
            result.steps.append(StepResult(step["name"], sh.PASS, detail or "",
                                           round(time.time() - start, 3)))
            log_fn(f"    ✓ {step['name']}")
        except sh.HarnessError as exc:
            result.steps.append(StepResult(step["name"], exc.kind, exc.message,
                                           round(time.time() - start, 3)))
            log_fn(f"    ✗ {step['name']}: {exc.kind} — {exc.message}")
            if not is_cleanup:
                failed = True
    statuses = {s.status for s in result.steps}
    if not result.steps:
        result.status = sh.SKIPPED
    elif statuses == {sh.PASS}:
        result.status = sh.PASS
    else:
        result.status = next((s.status for s in result.steps
                              if s.status != sh.PASS), sh.FAIL)
    return result


# ---------------------------------------------------------------------------
# Suite orchestration

def run_suite(args, log_fn=print) -> dict:
    manifest = sh.load_manifest(args.manifest)
    if args.profile not in manifest["profiles"]:
        raise sh.HarnessError(sh.ERROR, f"unknown profile {args.profile}")
    prof = manifest["profiles"][args.profile]
    scenarios = load_scenarios(args.scenarios)
    if args.only:
        wanted = set(args.only)
        scenarios = [s for s in scenarios if s["name"] in wanted or s["group"] in wanted]
    if not scenarios:
        raise sh.HarnessError(sh.ERROR, "zero scenarios selected")

    server_dir = os.path.join(args.work_root, "scenario-server")
    server_log = os.path.join(server_dir, "logs", "harness-stdout.log")
    dep_cache = args.dep_cache or os.path.join(args.work_root, "_dep_cache")
    rcon_password = __import__("secrets").token_urlsafe(24)
    secrets_ = [rcon_password]

    ctx = _Ctx()
    ctx.server_dir, ctx.server_log = server_dir, server_log
    ctx.heap = args.heap
    suite = {"profile": args.profile, "status": sh.FAIL,
             "scenarios": [], "groups_missing": [], "timings": {}}
    started = time.time()

    try:
        sh.verify_artifact(args.jar, sha256=args.jar_sha256, label="straja artifact")
        ctx.argfile = sh.provision_neoforge(
            server_dir, manifest, os.path.join(dep_cache, "neoforge"), log_fn)
        dep_paths = [sh.download_dependency(manifest["dependencies"][d], dep_cache)
                     for d in prof["deps"]]
        sh.stage_mods(server_dir, args.jar, dep_paths, args.jar_sha256)
        game_port, rcon_port = sh.free_port(), sh.free_port()
        sh.write_server_properties(server_dir, game_port, rcon_port, rcon_password)
        with open(os.path.join(server_dir, "eula.txt"), "w") as fh:
            fh.write("eula=true\n")
        sh.write_straja_config(server_dir, prof["config"])

        os.makedirs(os.path.dirname(server_log), exist_ok=True)
        ctx.proc, ctx.log_fh = sh.start_server(server_dir, ctx.argfile,
                                               server_log, heap=args.heap)
        ctx.rcon = sh.RconClient("127.0.0.1", rcon_port, rcon_password)
        ctx.timeout_s = prof["timeoutSeconds"]
        sh.wait_ready(ctx.proc, server_log, ctx.rcon, prof["timeoutSeconds"])
        suite["timings"]["ready_s"] = round(time.time() - started, 1)
        log_fn(f"server ready in {suite['timings']['ready_s']}s")

        profile_deps = set(prof["deps"])
        for sc in scenarios:
            missing_deps = sorted(set(sc.get("requiresDeps", [])) - profile_deps)
            if missing_deps:
                log_fn(f"  scenario {sc['name']} ({sc['group']}): "
                       f"skipped — profile lacks {missing_deps}")
                result = ScenarioResult(name=sc["name"], group=sc["group"],
                                        blocking=False, dep_skipped=True)
                result.steps.append(StepResult(
                    "(deps)", sh.SKIPPED, f"profile lacks {missing_deps}"))
                result.status = sh.SKIPPED
                suite["scenarios"].append(result)
                continue
            log_fn(f"  scenario {sc['name']} ({sc['group']})")
            result = run_scenario(ctx, sc, log_fn)
            suite["scenarios"].append(result)

        seen_groups = {r.group for r in suite["scenarios"]}
        suite["groups_missing"] = sorted(REQUIRED_GROUPS - seen_groups)
        if ctx.proc.poll() is None:
            sh.clean_shutdown(ctx.rcon, ctx.proc)
        blocking_failed = [r for r in suite["scenarios"]
                           if r.blocking and r.status != sh.PASS]
        skipped_unexpected = [r for r in suite["scenarios"]
                              if r.status == sh.SKIPPED and not r.dep_skipped]
        if (blocking_failed or suite["groups_missing"] or ctx.rcon_lost
                or skipped_unexpected):
            suite["status"] = sh.FAIL
        else:
            suite["status"] = sh.PASS
    except sh.HarnessError as exc:
        suite["status"] = exc.kind if exc.kind in sh.NON_SUCCESS else sh.ERROR
        suite.setdefault("error", exc.message)
        log_fn(f"suite failed: {exc.kind} — {exc.message}")
    finally:
        if ctx.proc is not None:
            sh.kill_tree(ctx.proc)
        if ctx.log_fh is not None:
            ctx.log_fh.close()
        suite["timings"]["total_s"] = round(time.time() - started, 1)
        sh._retain(server_dir, args.artifacts_dir, secrets_)
        _write_reports(suite, args.artifacts_dir)
    return suite


def _write_reports(suite: dict, out_dir: str):
    os.makedirs(out_dir, exist_ok=True)
    payload = dict(suite)
    payload["scenarios"] = [
        {"name": r.name, "group": r.group, "blocking": r.blocking,
         "status": r.status, "dep_skipped": r.dep_skipped,
         "steps": [{"name": s.name, "status": s.status, "detail": s.detail,
                    "duration_s": s.duration_s} for s in r.steps],
         "notes": r.notes}
        for r in suite["scenarios"]]
    with open(os.path.join(out_dir, "scenario-report.json"), "w",
              encoding="utf-8") as fh:
        json.dump(payload, fh, indent=2, ensure_ascii=False)

    total = sum(len(r.steps) for r in suite["scenarios"])
    failed = sum(1 for r in suite["scenarios"] for s in r.steps
                 if s.status not in (sh.PASS, sh.SKIPPED))
    skipped = sum(1 for r in suite["scenarios"] for s in r.steps
                  if s.status == sh.SKIPPED)
    root = ET.Element("testsuite", {"name": "straja.scenarios",
                                    "tests": str(total),
                                    "failures": str(failed),
                                    "skipped": str(skipped)})
    for r in suite["scenarios"]:
        for s in r.steps:
            case = ET.SubElement(root, "testcase", {
                "classname": f"straja.scenarios.{r.group}",
                "name": f"{r.name}.{s.name}", "time": f"{s.duration_s:.3f}"})
            if s.status == sh.SKIPPED:
                node = ET.SubElement(case, "skipped")
                node.text = s.detail
            elif s.status != sh.PASS:
                node = ET.SubElement(case, "failure", {"type": s.status})
                node.text = s.detail
    tree = ET.ElementTree(root)
    ET.indent(tree, space="  ")
    tree.write(os.path.join(out_dir, "junit.xml"), encoding="utf-8",
               xml_declaration=True)


def main(argv=None):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)
    run = sub.add_parser("run", help="provision server and run all scenarios")
    run.add_argument("--manifest", default="tools/ci/server_manifest.json")
    run.add_argument("--scenarios", default="tools/ci/scenarios")
    run.add_argument("--profile", default="combined")
    run.add_argument("--jar", required=True)
    run.add_argument("--jar-sha256", default=None)
    run.add_argument("--work-root", required=True)
    run.add_argument("--artifacts-dir", required=True)
    run.add_argument("--dep-cache", default=None)
    run.add_argument("--heap", default="2G")
    run.add_argument("--only", nargs="*", default=None,
                     help="run only named scenarios/groups")
    args = parser.parse_args(argv)

    if args.cmd == "run":
        try:
            suite = run_suite(args)
        except sh.HarnessError as exc:
            print(f"scenario runner: {exc.kind}: {exc.message}", file=sys.stderr)
            return 2
        print(json.dumps({"status": suite["status"],
                          "scenarios": len(suite["scenarios"])}))
        return 0 if suite["status"] == sh.PASS else 1
    return 2


if __name__ == "__main__":
    sys.exit(main())
