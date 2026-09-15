#!/usr/bin/env python3
"""Release-blocking server health soak for the packaged mod (CI-007).

Runs the combined focused profile on a disposable NeoForge dedicated
server: measured startup, a warm-up phase, then a fixed measured soak
with a deterministic Straja workload. Every SAMPLE_INTERVAL_S seconds a
`straja test perf-sample` RCON call collects tick/heap/workload metrics;
raw samples are retained verbatim in perf-report.json.

Budgets (release-blocking — no retries on threshold failures):
  * measured average MSPT <= AVG_MSPT_MAX
  * every sample's p95 recent-tick window <= P95_TICK_MS_MAX
  * used heap <= HEAP_MAX_GIB GiB
  * metrics missing/invalid, RCON lost, process exit, watchdog/crash
    report, or non-allowlisted ERROR/FATAL log lines -> fail
  * startup: <=180s required-only, <=300s focused profiles (manifest)

Process RSS/CPU are reported as observations only — not portable hard
gates on shared runners.

Usage:
    python tools/ci/performance.py run \
        --manifest tools/ci/server_manifest.json \
        --profile combined \
        --jar build/libs/straja-X.Y.Z.jar --jar-sha256 <hex> \
        --work-root <dir> --artifacts-dir <dir> \
        [--warmup-s 120] [--measure-s 600] [--sample-interval-s 5]
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import server_harness as sh  # noqa: E402

# ---------------------------------------------------------------------------
# Budgets
AVG_MSPT_MAX = 45.0          # measured average tick, milliseconds
P95_TICK_MS_MAX = 50.0       # per-sample p95 of the recent-tick ring
HEAP_MAX_GIB = 6.0
SAMPLE_INTERVAL_S = 5.0
WARMUP_S = 120.0
MEASURE_S = 600.0
STARTUP_MAX_S = {"required-only": 180.0}   # focused profiles: 300 default
STARTUP_MAX_DEFAULT_S = 300.0

# Log lines matching any of these are not release-blocking errors. Keep the
# allowlist tight: every entry must be benign-by-review, never a wildcard
# that could hide a real regression.
LOG_ALLOWLIST = [
    re.compile(r"^\s*$"),
    re.compile(r"\[Straja\] Optional custody mods detected"),
    re.compile(r"\[Straja\] Vampirism provider loaded"),
    re.compile(r"Can't keep up! Is the server overloaded\?"),  # vanilla warn
    # Vanilla logs an ERROR when the dedicated server has no flat-worldgen
    # 'layers' key — benign on every fresh disposable world.
    re.compile(r"DedicatedServerProperties.*No key layers in MapLike"),
    # Envelope's BackgroundDelivery codec fails decoding its own empty store
    # on fresh worlds — upstream dependency noise, not a Straja defect.
    re.compile(r"DimensionDataStorage.*Error loading saved data: "
               r"envelope_background_delivery"),
]

_ERR_LINE = re.compile(r"/(ERROR|FATAL)\]|Exception in|Watchdog|crash", re.I)
_CRASH_DIR = os.path.join("crash-reports")


def percentile(values, p: float) -> float:
    """Nearest-rank percentile over unsorted values; p in (0, 100]."""
    if not values:
        raise ValueError("percentile of empty sequence")
    if not 0 < p <= 100:
        raise ValueError(f"bad percentile {p}")
    ordered = sorted(values)
    rank = max(1, int(-(-p * len(ordered) // 100)))  # ceil(p/100 * n)
    return float(ordered[rank - 1])


_PERF_KV = re.compile(r"(\w+)=([^\s]+)")


def parse_perf_line(text: str) -> dict:
    """Parse `perf k=v k=v ...` into a dict; raise ValueError on malformed
    or missing required keys — missing/invalid metrics fail the gate."""
    line = next((ln for ln in text.splitlines() if ln.startswith("perf ")),
                None)
    if line is None:
        raise ValueError(f"no perf line in {text[:200]!r}")
    fields = dict(_PERF_KV.findall(line))
    out = {}
    for key in ("tick", "avgTickNanos", "heapUsedBytes", "heapMaxBytes",
                "onlinePlayers", "virtualPlayers", "fines", "missions",
                "custody", "cells"):
        try:
            out[key] = int(fields[key])
        except (KeyError, ValueError) as exc:
            raise ValueError(f"perf line missing/invalid {key}") from exc
    raw_ticks = fields.get("tickNanos", "")
    try:
        out["tickNanos"] = [int(x) for x in raw_ticks.split(",") if x]
    except ValueError as exc:
        raise ValueError("perf line invalid tickNanos") from exc
    if not out["tickNanos"]:
        raise ValueError("perf line empty tickNanos")
    return out


def scan_log_errors(log_text: str, allowlist=LOG_ALLOWLIST) -> list:
    """ERROR/FATAL/exception/watchdog lines surviving the allowlist."""
    bad = []
    for line in log_text.splitlines():
        if not _ERR_LINE.search(line):
            continue
        if any(rx.search(line) for rx in allowlist):
            continue
        bad.append(line.strip()[:300])
    return bad


def evaluate(samples: list, *, avg_mspt_max=AVG_MSPT_MAX,
             p95_ms_max=P95_TICK_MS_MAX, heap_max_bytes=int(HEAP_MAX_GIB * (1 << 30)),
             startup_s=None, startup_max_s=None, log_errors=None,
             crash_reports=None, rcon_lost=False, process_died=False,
             expected_samples=None, workload_ops=None,
             workload_errors=None) -> list:
    """Pure gate: return a list of failure reasons ([] == healthy)."""
    failures = []
    if startup_s is not None and startup_max_s is not None \
            and startup_s > startup_max_s:
        failures.append(f"startup {startup_s:.0f}s exceeds {startup_max_s:.0f}s")
    if process_died:
        failures.append("server process exited during soak")
    if rcon_lost:
        failures.append("rcon lost during soak")
    if expected_samples is not None and len(samples) < expected_samples:
        failures.append(f"missing samples: {len(samples)}/{expected_samples}")
    if not samples:
        failures.append("no metric samples collected")
        failures.extend(f"log: {e}" for e in (log_errors or []))
        failures.extend(f"crash report: {c}" for c in (crash_reports or []))
        return failures
    if workload_ops is not None:
        if workload_ops == 0:
            failures.append("no workload ops executed")
        elif workload_errors is not None \
                and workload_errors * 2 > workload_ops:
            failures.append(f"majority of workload ops failed "
                            f"({workload_errors}/{workload_ops})")
    avg_ms = sum(s["avgTickNanos"] for s in samples) / len(samples) / 1e6
    if avg_ms > avg_mspt_max:
        failures.append(f"average MSPT {avg_ms:.2f} exceeds {avg_mspt_max:.0f}ms")
    worst_p95 = 0.0
    for i, s in enumerate(samples):
        p95 = percentile(s["tickNanos"], 95) / 1e6
        worst_p95 = max(worst_p95, p95)
        if p95 > p95_ms_max:
            failures.append(f"sample {i} p95 tick {p95:.2f}ms exceeds "
                            f"{p95_ms_max:.0f}ms")
    heap_max = max(s["heapUsedBytes"] for s in samples)
    if heap_max > heap_max_bytes:
        failures.append(f"heap used {heap_max / (1 << 30):.2f} GiB exceeds "
                        f"{heap_max_bytes / (1 << 30):.0f} GiB")
    failures.extend(f"log: {e}" for e in (log_errors or []))
    failures.extend(f"crash report: {c}" for c in (crash_reports or []))
    return failures


# ---------------------------------------------------------------------------
# Deterministic workload — cycles Straja operations between samples. Every
# op is a real `/straja test` flow already proven in the scenario suite;
# failures are tolerated at op level (the gate is on server health, not
# gameplay outcomes here) but are recorded in the report.

GUARDS = [f"perf_g{i}" for i in range(3)]
CITIZENS = [f"perf_c{i}" for i in range(2)]
COMMISSIONER = "perf_com"
OFFICER = "perf_o1"           # rank 4 — ISSUE_FINES capability
ROSTER = GUARDS + CITIZENS + [COMMISSIONER, OFFICER]


def _workload_plan(rcon, log_fn, errors):
    """Return a list of callables; cycled deterministically during the soak.
    `{n}`/`{c}`/`{com}` are replaced by round-robin roster names so every op
    targets a player that actually exists (the roster is created in the
    warm-up phase before the measured soak starts). Op-level failures are
    tolerated individually (refusals are normal gameplay) but transport
    failures are appended to `errors` for the health gate."""
    rr = {"n": 0}

    def op(*cmds):
        def go():
            name = GUARDS[rr["n"] % len(GUARDS)]
            citizen = CITIZENS[rr["n"] % len(CITIZENS)]
            rr["n"] += 1
            for cmd in cmds:
                try:
                    rcon.execute(cmd.format(n=name, c=citizen, com=COMMISSIONER,
                                            o=OFFICER))
                except sh.HarnessError as exc:
                    errors.append(exc.kind)
                    log_fn(f"    workload op failed ({exc.kind}): "
                           f"{cmd} — {exc.message}")
        return go

    ops = [
        # duty / checkpoints
        op("straja test tool-patrol perf_route 5 64 5"),
        op("straja test tool-patrol perf_route 10 64 10"),
        op("straja test tool-patrol-finish perf_route"),
        op("straja test start-duty {n}"),
        op("straja test move {n} 6 64 6"),
        op("straja test advance-time 60"),
        op("straja test move {n} 7 64 7"),
        op("straja test stop-duty {n}"),
        # missions / fines / complaints
        op("straja test template-create {com} perf_tpl 1 2.0 1.0"),
        op("straja test mission-create {com} {n} 30 100 workload_patrol"),
        # fine write+issue stay one op — the draft is keyed to its target,
        # so a round-robin mismatch between calls would always refuse
        op("straja test fine-write {o} {c} 100 lege_1 workload_fine",
           "straja test fine-issue {o} {c}"),
        op("straja test complaint-submit {c} {n} abuz workload_complaint"),

        # rope needs the issuer's held item + co-located target and is
        # consumed per application — the guard's slot 0 is free (the
        # officer already holds fine_book there)
        op("straja test give {n} straja:rope 1",
           "straja test select-slot {n} 0",
           "straja test move {n} 6 64 6",
           "straja test move {c} 6 64 6",
           "straja test rope {n} {c}"),
        # cell-create needs sealed in-world geometry; arrest exercises the
        # prison store (waitlist) without it
        op("straja test prison-arrest {o} {c} 1"),
        # inventory / economy
        op("straja test give {n} adys_decorations:bronze_coin 5"),
        op("straja test inventory {n}"),
        # NPC / entity actions
        op("straja test setup-here {n}"),
        op("straja test tool-clone-spawn {n} 8 64 8"),
        op("straja test tool-clone-clear {n}"),
        # periodic save + recovery
        op("save-all flush"),
        op("straja test rejoin {n}"),
    ]
    return ops


def _sample_proc(pid):
    """Best-effort RSS/CPU observation (Linux /proc; None elsewhere)."""
    try:
        with open(f"/proc/{pid}/status", "r", encoding="utf-8") as fh:
            status = fh.read()
        rss_kb = int(re.search(r"VmRSS:\s+(\d+) kB", status).group(1))
        with open(f"/proc/{pid}/stat", "r", encoding="utf-8") as fh:
            parts = fh.read().split()
        ticks = int(parts[13]) + int(parts[14])
        return {"rss_bytes": rss_kb * 1024, "cpu_ticks": ticks}
    except (OSError, AttributeError, ValueError, IndexError):
        return None


def run_perf(args, log_fn=print) -> dict:
    manifest = sh.load_manifest(args.manifest)
    if args.profile not in manifest["profiles"]:
        raise sh.HarnessError(sh.ERROR, f"unknown profile {args.profile}")
    prof = manifest["profiles"][args.profile]

    server_dir = os.path.join(args.work_root, "perf-server")
    server_log = os.path.join(server_dir, "logs", "harness-stdout.log")
    dep_cache = args.dep_cache or os.path.join(args.work_root, "_dep_cache")
    rcon_password = __import__("secrets").token_urlsafe(24)

    report = {"profile": args.profile, "status": sh.FAIL, "samples": [],
              "workload_ops": 0, "failures": [], "observations": {},
              "budgets": {"avgMsptMax": AVG_MSPT_MAX,
                          "p95TickMsMax": P95_TICK_MS_MAX,
                          "heapMaxGiB": HEAP_MAX_GIB,
                          "warmupS": args.warmup_s,
                          "measureS": args.measure_s,
                          "sampleIntervalS": args.sample_interval_s}}
    proc = None
    log_fh = None
    started = time.time()
    try:
        sh.verify_artifact(args.jar, sha256=args.jar_sha256,
                           label="straja artifact")
        argfile = sh.provision_neoforge(
            server_dir, manifest, os.path.join(dep_cache, "neoforge"), log_fn)
        dep_paths = [sh.download_dependency(manifest["dependencies"][d],
                                            dep_cache)
                     for d in prof["deps"]]
        sh.stage_mods(server_dir, args.jar, dep_paths, args.jar_sha256)
        game_port, rcon_port = sh.free_port(), sh.free_port()
        sh.write_server_properties(server_dir, game_port, rcon_port,
                                   rcon_password)
        with open(os.path.join(server_dir, "eula.txt"), "w") as fh:
            fh.write("eula=true\n")
        sh.write_straja_config(server_dir, prof["config"])

        os.makedirs(os.path.dirname(server_log), exist_ok=True)
        boot_at = time.time()
        proc, log_fh = sh.start_server(server_dir, argfile, server_log,
                                       heap=args.heap)
        rcon = sh.RconClient("127.0.0.1", rcon_port, rcon_password)
        sh.wait_ready(proc, server_log, rcon, prof["timeoutSeconds"])
        startup_s = time.time() - boot_at
        report["observations"]["startup_s"] = round(startup_s, 1)
        log_fn(f"server ready in {startup_s:.1f}s")

        # Roster setup: commissioner + guards + citizens + a rank-4 officer
        # carrying the fine book (fines need ISSUE_FINES + the item).
        rcon.execute(f"straja test create-player {COMMISSIONER}")
        rcon.execute(f"straja test set-commissioner {COMMISSIONER}")
        rcon.execute(f"straja test create-player {OFFICER}")
        rcon.execute(f"straja test set-rank {OFFICER} 4")
        rcon.execute(f"straja test give {OFFICER} straja:fine_book 1")
        for name in GUARDS:
            rcon.execute(f"straja test create-player {name}")
            rcon.execute(f"straja test set-rank {name} 2")
        for name in CITIZENS:
            rcon.execute(f"straja test create-player {name}")

        op_errors = []
        ops = _workload_plan(rcon, log_fn, op_errors)
        oi = [0]

        def run_op():
            ops[oi[0] % len(ops)]()
            oi[0] += 1

        # ---- warm-up: same workload, no samples counted
        log_fn(f"warm-up {args.warmup_s:.0f}s")
        deadline = time.time() + args.warmup_s
        while time.time() < deadline and proc.poll() is None:
            run_op()
            time.sleep(2)

        # ---- measured soak: absolute 5s boundaries — workload ops fill the
        # gaps but can never accumulate sampling drift.
        log_fn(f"measured soak {args.measure_s:.0f}s "
               f"(sample every {args.sample_interval_s:.0f}s)")
        rcon_lost = False
        t0 = time.time()
        boundary = 0
        while True:
            boundary += 1
            next_at = t0 + boundary * args.sample_interval_s
            if next_at - t0 > args.measure_s:
                break
            while time.time() < next_at:
                if proc.poll() is not None:
                    report["observations"]["exit_code"] = proc.returncode
                    break
                run_op()
                time.sleep(0.2)
            if proc.poll() is not None:
                report["observations"]["exit_code"] = proc.returncode
                break
            now = time.time()
            try:
                resp = rcon.execute("straja test perf-sample")
                sample = parse_perf_line(resp)
                sample["t_s"] = round(now - t0, 2)
                obs = _sample_proc(proc.pid)
                if obs:
                    sample["proc"] = obs
                report["samples"].append(sample)
            except sh.HarnessError:
                rcon_lost = True
                break
            except ValueError as exc:
                report["failures"].append(f"invalid metrics: {exc}")
        report["workload_ops"] = oi[0]
        report["workload_errors"] = len(op_errors)

        if proc.poll() is None:
            sh.clean_shutdown(rcon, proc)

        crash_dir = os.path.join(server_dir, _CRASH_DIR)
        crashes = sorted(os.listdir(crash_dir)) if os.path.isdir(crash_dir) \
            else []
        log_errors = scan_log_errors(sh._read_log(server_log))
        startup_max = STARTUP_MAX_S.get(args.profile, STARTUP_MAX_DEFAULT_S)
        expected = int(args.measure_s // args.sample_interval_s)
        report["failures"].extend(evaluate(
            report["samples"],
            startup_s=startup_s, startup_max_s=startup_max,
            log_errors=log_errors, crash_reports=crashes,
            rcon_lost=rcon_lost,
            process_died=proc.poll() is not None
            and report["observations"].get("exit_code") is not None,
            expected_samples=max(1, expected - 2),  # 2-sample slack
            workload_ops=oi[0], workload_errors=len(op_errors)))
        report["status"] = sh.PASS if not report["failures"] else sh.FAIL
    except sh.HarnessError as exc:
        report["status"] = exc.kind if exc.kind in sh.NON_SUCCESS else sh.ERROR
        report["failures"].append(f"{exc.kind}: {exc.message}")
        log_fn(f"perf soak failed: {exc.kind} — {exc.message}")
    finally:
        if proc is not None:
            sh.kill_tree(proc)
        if log_fh is not None:
            log_fh.close()
        report["timings"] = {"total_s": round(time.time() - started, 1)}
        sh._retain(server_dir, args.artifacts_dir, [rcon_password])
        _write_reports(report, args.artifacts_dir)
    return report


def _write_reports(report: dict, out_dir: str):
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "perf-report.json"), "w",
              encoding="utf-8") as fh:
        json.dump(report, fh, indent=2, ensure_ascii=False)

    root = ET.Element("testsuite", {"name": "straja.performance",
                                    "tests": "1",
                                    "failures": "1" if report["failures"] else "0",
                                    "skipped": "0"})
    case = ET.SubElement(root, "testcase", {
        "classname": "straja.performance", "name": "server-health-soak",
        "time": str(report.get("timings", {}).get("total_s", 0))})
    if report["failures"]:
        node = ET.SubElement(case, "failure", {"type": report["status"]})
        node.text = "\n".join(report["failures"])
    tree = ET.ElementTree(root)
    ET.indent(tree, space="  ")
    tree.write(os.path.join(out_dir, "junit.xml"), encoding="utf-8",
               xml_declaration=True)

    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        samples = report["samples"]
        lines = ["## Server health soak", "",
                 f"- Profile: `{report['profile']}` — status **{report['status']}**"]
        obs = report["observations"]
        if "startup_s" in obs:
            lines.append(f"- Startup: {obs['startup_s']:.0f}s")
        if samples:
            avg = sum(s["avgTickNanos"] for s in samples) / len(samples) / 1e6
            p95s = [percentile(s["tickNanos"], 95) / 1e6 for s in samples]
            peak = max(max(s["tickNanos"]) for s in samples) / 1e6
            heap = max(s["heapUsedBytes"] for s in samples) / (1 << 30)
            lines += [f"- Samples: {len(samples)} raw (5s interval)",
                      f"- Avg MSPT: {avg:.1f}ms (budget ≤{AVG_MSPT_MAX:.0f})",
                      f"- Worst window p95: {max(p95s):.1f}ms "
                      f"(budget ≤{P95_TICK_MS_MAX:.0f})",
                      f"- Max tick: {peak:.1f}ms",
                      f"- Heap max: {heap:.2f} GiB (budget ≤{HEAP_MAX_GIB:.0f})",
                      f"- Workload ops: {report['workload_ops']}"]
        if report["failures"]:
            lines += ["", "### Failures"] + [f"- `{f}`"
                                             for f in report["failures"]]
        with open(summary_path, "a", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + "\n")


def main(argv=None):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)
    run = sub.add_parser("run", help="provision server and run the health soak")
    run.add_argument("--manifest", default="tools/ci/server_manifest.json")
    run.add_argument("--profile", default="combined")
    run.add_argument("--jar", required=True)
    run.add_argument("--jar-sha256", default=None)
    run.add_argument("--work-root", required=True)
    run.add_argument("--artifacts-dir", required=True)
    run.add_argument("--dep-cache", default=None)
    run.add_argument("--heap", default="3G")
    run.add_argument("--warmup-s", type=float, default=WARMUP_S)
    run.add_argument("--measure-s", type=float, default=MEASURE_S)
    run.add_argument("--sample-interval-s", type=float,
                     default=SAMPLE_INTERVAL_S)
    args = parser.parse_args(argv)

    if args.cmd == "run":
        try:
            report = run_perf(args)
        except sh.HarnessError as exc:
            print(f"performance: {exc.kind}: {exc.message}", file=sys.stderr)
            return 2
        print(json.dumps({"status": report["status"],
                          "samples": len(report["samples"]),
                          "failures": report["failures"]}))
        return 0 if report["status"] == sh.PASS else 1
    return 2


if __name__ == "__main__":
    sys.exit(main())
