#!/usr/bin/env python3
"""Unit tests for performance.py — percentile math, threshold boundaries,
parse validation, allowlist matching, gate evaluation, report generation."""
import json
import os
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import performance as perf  # noqa: E402
import server_harness as sh  # noqa: E402


def _sample(avg_nanos=30_000_000, heap=int(1e9), ticks=None, t=0.0):
    return {"tick": 1000, "avgTickNanos": avg_nanos,
            "heapUsedBytes": heap, "heapMaxBytes": int(8e9),
            "onlinePlayers": 0, "virtualPlayers": 4,
            "fines": 2, "missions": 3, "custody": 1, "cells": 1,
            "tickNanos": ticks if ticks is not None else [30_000_000] * 100,
            "t_s": t}


class PercentileTests(unittest.TestCase):
    def test_nearest_rank(self):
        self.assertEqual(perf.percentile([10, 20, 30, 40, 50, 60, 70, 80,
                                          90, 100], 95), 100)
        # 5% outliers are tolerated: the 95th of 20 ordered items is the
        # 19th — a single spike does not break the percentile gate.
        self.assertEqual(perf.percentile([1] * 19 + [100], 95), 1)
        self.assertEqual(perf.percentile([5, 1, 9, 3], 50), 3)

    def test_empty_and_bad_p(self):
        with self.assertRaises(ValueError):
            perf.percentile([], 95)
        for bad in (0, -5, 101):
            with self.assertRaises(ValueError):
                perf.percentile([1, 2], bad)


class ParseTests(unittest.TestCase):
    def test_parses_full_line(self):
        s = perf.parse_perf_line(
            "perf tick=42 avgTickNanos=12345 heapUsedBytes=100 "
            "heapMaxBytes=200 onlinePlayers=1 virtualPlayers=4 fines=2 "
            "missions=3 custody=1 cells=0 tickNanos=100,200,300\n")
        self.assertEqual(s["tick"], 42)
        self.assertEqual(s["tickNanos"], [100, 200, 300])
        self.assertEqual(s["fines"], 2)

    def test_multiline_picks_perf(self):
        s = perf.parse_perf_line("noise\nperf tick=1 avgTickNanos=2 "
                                 "heapUsedBytes=3 heapMaxBytes=4 "
                                 "onlinePlayers=0 virtualPlayers=0 fines=0 "
                                 "missions=0 custody=0 cells=0 "
                                 "tickNanos=5\ntrailing")
        self.assertEqual(s["tick"], 1)

    def test_missing_key_fails(self):
        with self.assertRaises(ValueError):
            perf.parse_perf_line("perf tick=1 avgTickNanos=2\n")

    def test_invalid_units_fail(self):
        with self.assertRaises(ValueError):
            perf.parse_perf_line(
                "perf tick=abc avgTickNanos=2 heapUsedBytes=3 heapMaxBytes=4 "
                "onlinePlayers=0 virtualPlayers=0 fines=0 missions=0 "
                "custody=0 cells=0 tickNanos=5")

    def test_empty_tick_ring_fails(self):
        with self.assertRaises(ValueError):
            perf.parse_perf_line(
                "perf tick=1 avgTickNanos=2 heapUsedBytes=3 heapMaxBytes=4 "
                "onlinePlayers=0 virtualPlayers=0 fines=0 missions=0 "
                "custody=0 cells=0 tickNanos=")


class EvaluateTests(unittest.TestCase):
    def test_healthy_passes(self):
        samples = [_sample(t=i * 5) for i in range(120)]
        self.assertEqual(perf.evaluate(samples, startup_s=60,
                                       startup_max_s=300), [])

    def test_avg_boundary(self):
        # exactly at budget -> pass; one ns over -> fail
        at = perf.evaluate([_sample(avg_nanos=45_000_000)],
                           expected_samples=1)
        self.assertEqual(at, [])
        over = perf.evaluate([_sample(avg_nanos=45_000_001)],
                             expected_samples=1)
        self.assertTrue(any("MSPT" in f for f in over))

    def test_p95_boundary(self):
        # nearest-rank p95 of 100 ticks = 95th ordered value
        ok = [_sample(ticks=[40_000_000] * 94 + [50_000_000] * 6)]
        self.assertEqual(perf.evaluate(ok, expected_samples=1), [])
        bad = [_sample(ticks=[40_000_000] * 94 + [51_000_000] * 6)]
        fails = perf.evaluate(bad, expected_samples=1)
        self.assertTrue(any("p95" in f for f in fails))

    def test_heap_boundary(self):
        gib6 = int(perf.HEAP_MAX_GIB * (1 << 30))
        self.assertEqual(perf.evaluate([_sample(heap=gib6)],
                                       expected_samples=1), [])
        fails = perf.evaluate([_sample(heap=gib6 + 1)], expected_samples=1)
        self.assertTrue(any("heap" in f.lower() for f in fails))

    def test_startup_boundary(self):
        fails = perf.evaluate([_sample()], startup_s=301, startup_max_s=300)
        self.assertTrue(any("startup" in f for f in fails))
        ok = perf.evaluate([_sample()], startup_s=300, startup_max_s=300)
        self.assertEqual(ok, [])

    def test_missing_samples_fail(self):
        fails = perf.evaluate([_sample()] * 10, expected_samples=120)
        self.assertTrue(any("missing samples" in f for f in fails))

    def test_no_samples_fail(self):
        fails = perf.evaluate([], expected_samples=120)
        self.assertTrue(any("no metric samples" in f for f in fails))

    def test_rcon_lost_fails(self):
        fails = perf.evaluate([_sample()], rcon_lost=True, expected_samples=1)
        self.assertTrue(any("rcon" in f for f in fails))

    def test_process_died_fails(self):
        fails = perf.evaluate([_sample()], process_died=True,
                              expected_samples=1)
        self.assertTrue(any("process exited" in f for f in fails))

    def test_log_and_crash_failures(self):
        fails = perf.evaluate([_sample()], expected_samples=1,
                              log_errors=["ERROR boom"],
                              crash_reports=["crash-2026.txt"])
        self.assertTrue(any("ERROR boom" in f for f in fails))
        self.assertTrue(any("crash-2026.txt" in f for f in fails))

    def test_workload_gate(self):
        self.assertEqual(perf.evaluate([_sample()], expected_samples=1,
                                       workload_ops=100, workload_errors=0),
                         [])
        fails = perf.evaluate([_sample()], expected_samples=1,
                              workload_ops=0, workload_errors=0)
        self.assertTrue(any("no workload ops" in f for f in fails))
        fails = perf.evaluate([_sample()], expected_samples=1,
                              workload_ops=10, workload_errors=6)
        self.assertTrue(any("majority of workload" in f for f in fails))
        ok = perf.evaluate([_sample()], expected_samples=1,
                           workload_ops=10, workload_errors=5)
        self.assertEqual(ok, [])


class AllowlistTests(unittest.TestCase):
    def test_error_line_flagged(self):
        bad = perf.scan_log_errors(
            "[12:00:00] [Server thread/ERROR] [x/]: something broke\n")
        self.assertEqual(len(bad), 1)

    def test_allowlisted_lines_pass(self):
        text = ("[12:00:00] [Server thread/INFO] [Straja/]: [Straja] "
                "Vampirism provider loaded: ...\n"
                "[12:00:01] [Server thread/WARN] [minecraft/]: Can't keep up! "
                "Is the server overloaded? Running 2000ms behind\n")
        self.assertEqual(perf.scan_log_errors(text), [])

    def test_allowlist_does_not_swallow_straja_errors(self):
        bad = perf.scan_log_errors(
            "[12:00:00] [Server thread/ERROR] [Straja/]: [Straja] corrupt "
            "store 'fines' — backing up raw payload\n")
        self.assertEqual(len(bad), 1)


class ReportTests(unittest.TestCase):
    def test_reports_written(self):
        report = {"profile": "combined", "status": sh.PASS, "samples": [],
                  "workload_ops": 10, "failures": [], "observations": {},
                  "budgets": {}, "timings": {"total_s": 1.0}}
        with tempfile.TemporaryDirectory() as tmp:
            perf._write_reports(report, tmp)
            with open(os.path.join(tmp, "perf-report.json"),
                      encoding="utf-8") as fh:
                data = json.load(fh)
            self.assertEqual(data["status"], sh.PASS)
            root = ET.parse(os.path.join(tmp, "junit.xml")).getroot()
            self.assertEqual(root.get("failures"), "0")

    def test_junit_failure_node(self):
        report = {"profile": "combined", "status": sh.FAIL, "samples": [],
                  "workload_ops": 0, "failures": ["average MSPT 99 exceeds"],
                  "observations": {}, "budgets": {}, "timings": {}}
        with tempfile.TemporaryDirectory() as tmp:
            perf._write_reports(report, tmp)
            root = ET.parse(os.path.join(tmp, "junit.xml")).getroot()
            self.assertEqual(root.get("failures"), "1")
            self.assertIn("MSPT", root.find("testcase/failure").text)


if __name__ == "__main__":
    unittest.main()
