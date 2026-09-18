#!/usr/bin/env python3
"""Unit tests for scenario_runner, nbt_write, and the hardened rcon client."""
import gzip
import json
import os
import struct
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import nbt_write  # noqa: E402
import scenario_runner as sr  # noqa: E402
import server_harness as sh  # noqa: E402
import rcon as rcon_mod  # noqa: E402


def _sc(name="s1", group="bootstrap", steps=None, **kw):
    if steps is None:
        steps = [{"name": "x", "command": "list", "expectContains": "players"}]
    sc = {"name": name, "group": group, "steps": steps}
    sc.update(kw)
    return sc


class ScenarioValidationTests(unittest.TestCase):
    def test_loads_sorted(self):
        with tempfile.TemporaryDirectory() as tmp:
            for n in ("b.json", "a.json"):
                with open(os.path.join(tmp, n), "w") as fh:
                    json.dump(_sc(name=n[:-5]), fh)
            scenarios = sr.load_scenarios(tmp)
        self.assertEqual([s["name"] for s in scenarios], ["a", "b"])

    def test_missing_dir(self):
        with self.assertRaises(sh.HarnessError):
            sr.load_scenarios("/nonexistent-dir-xyz")

    def test_rejects_no_steps(self):
        with tempfile.TemporaryDirectory() as tmp:
            with open(os.path.join(tmp, "s.json"), "w") as fh:
                json.dump(_sc(steps=[]), fh)
            with self.assertRaises(sh.HarnessError):
                sr.load_scenarios(tmp)

    def test_command_needs_expectation(self):
        with tempfile.TemporaryDirectory() as tmp:
            with open(os.path.join(tmp, "s.json"), "w") as fh:
                json.dump(_sc(steps=[{"name": "x", "command": "list"}]), fh)
            with self.assertRaises(sh.HarnessError):
                sr.load_scenarios(tmp)

    def test_rejects_unsafe_inject_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            step = {"name": "x", "type": "expect-file",
                    "path": "../escape", "contains": "y"}
            with open(os.path.join(tmp, "s.json"), "w") as fh:
                json.dump(_sc(steps=[step]), fh)
            with self.assertRaises(sh.HarnessError):
                sr.load_scenarios(tmp)

    def test_rejects_bad_step_type(self):
        with tempfile.TemporaryDirectory() as tmp:
            with open(os.path.join(tmp, "s.json"), "w") as fh:
                json.dump(_sc(steps=[{"name": "x", "type": "rm-rf"}]), fh)
            with self.assertRaises(sh.HarnessError):
                sr.load_scenarios(tmp)

    def test_shipped_scenarios_cover_required_groups(self):
        directory = os.path.join(os.path.dirname(__file__), "scenarios")
        scenarios = sr.load_scenarios(directory)
        groups = {s["group"] for s in scenarios}
        self.assertTrue(sr.REQUIRED_GROUPS <= groups,
                        f"missing groups: {sr.REQUIRED_GROUPS - groups}")
        for sc in scenarios:
            self.assertTrue(sc.get("observable"))
            self.assertTrue(sc.get("failure"))


class FakeRcon:
    def __init__(self, responses=None, fail_after=None):
        self.responses = responses or {}
        self.calls = []
        self.fail_after = fail_after

    def execute(self, command):
        self.calls.append(command)
        if self.fail_after is not None and len(self.calls) > self.fail_after:
            raise sh.HarnessError(sh.RCON_LOST, "connection dropped")
        for needle, resp in self.responses.items():
            if needle in command:
                return resp
        return ""


class StepExecutionTests(unittest.TestCase):
    def _ctx(self, responses=None, fail_after=None):
        ctx = sr._Ctx()
        ctx.rcon = FakeRcon(responses, fail_after)
        ctx.server_dir = tempfile.mkdtemp()
        ctx.server_log = os.path.join(ctx.server_dir, "s.log")
        with open(ctx.server_log, "w") as fh:
            fh.write("server log content\n")
        return ctx

    def test_command_expect_contains(self):
        ctx = self._ctx({"list": "There are 2 players"})
        out = sr._exec_command(ctx, {"command": "list",
                                     "expectContains": "2 players"})
        self.assertIn("2 players", out)

    def test_command_expect_fails(self):
        ctx = self._ctx({"list": "empty"})
        with self.assertRaises(sh.HarnessError) as cm:
            sr._exec_command(ctx, {"command": "list", "expectContains": "players"})
        self.assertEqual(cm.exception.kind, sh.FAIL)

    def test_command_regex(self):
        ctx = self._ctx({"dump": "uuid=abc rank=3"})
        sr._exec_command(ctx, {"command": "dump-state", "expectRegex": "rank=3"})

    def test_command_not_contains(self):
        ctx = self._ctx({"x": "fine"})
        with self.assertRaises(sh.HarnessError):
            sr._exec_command(ctx, {"command": "x", "expectNotContains": "fine"})

    def test_rcon_lost_marks_context(self):
        ctx = self._ctx(fail_after=0)
        with self.assertRaises(sh.HarnessError) as cm:
            sr._exec_command(ctx, {"command": "list", "expectContains": "x"})
        self.assertEqual(cm.exception.kind, sh.RCON_LOST)
        self.assertTrue(ctx.rcon_lost)

    def test_log_expectation(self):
        ctx = self._ctx()
        sr._exec_log(ctx, {"expectRegex": "server log content"})
        with self.assertRaises(sh.HarnessError):
            sr._exec_log(ctx, {"expectRegex": "absent-marker"})

    def test_inject_corrupt_requires_stopped(self):
        ctx = self._ctx()
        with self.assertRaises(sh.HarnessError) as cm:
            sr._exec_inject_corrupt(ctx, {"store": "fines"})
        self.assertEqual(cm.exception.kind, sh.FAIL)
        ctx.stopped = True
        sr._exec_inject_corrupt(ctx, {"store": "fines"})
        self.assertTrue(os.path.isfile(os.path.join(
            ctx.server_dir, "world", "data", "straja_fines.dat")))

    def test_expect_file_gunzip(self):
        ctx = self._ctx()
        path = os.path.join(ctx.server_dir, "data.dat")
        nbt_write.write_store_dat(path, {"json": "{bad"})
        sr._exec_expect_file(ctx, {"path": "data.dat", "gunzip": True,
                                   "contains": "{bad"})
        with self.assertRaises(sh.HarnessError):
            sr._exec_expect_file(ctx, {"path": "data.dat", "gunzip": True,
                                       "contains": "nope"})


class ScenarioRunTests(unittest.TestCase):
    def test_fail_fast_and_cleanup_runs(self):
        ctx = sr._Ctx()
        ctx.rcon = FakeRcon({"good": "ok-marker", "clean": "cleaned"})
        ctx.server_dir = tempfile.mkdtemp()
        ctx.server_log = os.path.join(ctx.server_dir, "s.log")
        open(ctx.server_log, "w").close()
        sc = _sc(steps=[
            {"name": "ok", "command": "good", "expectContains": "ok-marker"},
            {"name": "bad", "command": "bad", "expectContains": "never"},
            {"name": "never-runs", "command": "good", "expectContains": "ok"},
            {"name": "cleanup", "command": "clean", "expectContains": "cleaned",
             "cleanup": True},
        ])
        result = sr.run_scenario(ctx, sc, lambda m: None)
        self.assertEqual(result.status, sh.FAIL)
        names = [s.name for s in result.steps]
        self.assertIn("cleanup", names)
        skipped = [s for s in result.steps if s.status == sh.SKIPPED]
        self.assertEqual([s.name for s in skipped], ["never-runs"])


class NbtWriterTests(unittest.TestCase):
    def test_kubejs_readable_by_java_layout(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = os.path.join(tmp, "kubejs_persistent_data.nbt")
            nbt_write.write_kubejs(p, {"straja_setup": "{\"x\":1}"})
            with open(p, "rb") as fh:
                raw = gzip.decompress(fh.read())
        self.assertEqual(raw[0], 10)  # TAG_Compound
        # Server-level keys must be flat — MigrationService reads
        # data.get("straja_setup"), not "KubeJSPersistentData.straja_setup".
        self.assertNotIn(b"KubeJSPersistentData", raw)
        self.assertIn(b"straja_setup", raw)
        self.assertIn(b'{"x":1}', raw)

    def test_store_dat_layout(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = os.path.join(tmp, "straja_fines.dat")
            nbt_write.write_store_dat(p, {"json": "{corrupt"})
            with open(p, "rb") as fh:
                raw = gzip.decompress(fh.read())
        # JsonStore nests payload under data.straja — a fixture without that
        # wrapper silently loads as an empty store.
        self.assertIn(b"data", raw)
        self.assertIn(b"straja", raw)
        self.assertIn(b"corrupt", raw)
        self.assertIn(b"DataVersion", raw)


class RconFramingTests(unittest.TestCase):
    def test_packet_layout(self):
        pkt = rcon_mod._packet(2, rcon_mod.SERVERDATA_EXECCOMMAND, "list")
        (length,) = struct.unpack("<i", pkt[:4])
        self.assertEqual(length, len(pkt) - 4)
        self.assertTrue(pkt.endswith(b"\x00\x00"))

    def test_exit_codes_distinct(self):
        codes = {rcon_mod.EXIT_OK, rcon_mod.EXIT_USAGE, rcon_mod.EXIT_AUTH_FAILED,
                 rcon_mod.EXIT_TIMEOUT, rcon_mod.EXIT_PROTOCOL,
                 rcon_mod.EXIT_CONNECT}
        self.assertEqual(len(codes), 6)

    def test_protocol_error_on_bad_length(self):
        class FakeSock:
            def recv(self, n):
                return struct.pack("<i", -5)

        with self.assertRaises(rcon_mod.RconProtocolError):
            rcon_mod._read_packet(FakeSock())

    def test_protocol_error_on_truncated_body(self):
        # Header announces 20 bytes, stream ends after 5.
        stream = struct.pack("<i", 20) + b"short"
        class FakeSock:
            buf = bytearray(stream)
            def recv(self, n):
                out = bytes(self.buf[:n]); del self.buf[:n]
                return out

        with self.assertRaises(rcon_mod.RconProtocolError):
            rcon_mod._read_packet(FakeSock())

    def test_multi_packet_response_assembled(self):
        # auth ok, exec response split across two packets, sentinel terminates
        wire = (rcon_mod._packet(1, 0, "")
                + rcon_mod._packet(2, 0, "part-1 ")
                + rcon_mod._packet(2, 0, "part-2")
                + rcon_mod._packet(rcon_mod._SENTINEL_ID, 0, ""))

        class FakeSock:
            def __init__(self):
                self.buf = bytearray(wire)
                self.recv_calls = 0
                self.sentinel_recv_calls = None

            def recv(self, n):
                self.recv_calls += 1
                out = bytes(self.buf[:n]); del self.buf[:n]
                return out

            def sendall(self, data):
                request_id = struct.unpack("<i", data[4:8])[0]
                if request_id == rcon_mod._SENTINEL_ID:
                    self.sentinel_recv_calls = self.recv_calls

            def settimeout(self, t): pass
            def close(self): pass

        sock = FakeSock()
        orig = rcon_mod.socket.create_connection
        rcon_mod.socket.create_connection = lambda *a, **k: sock
        try:
            text = rcon_mod.rcon("h", 1, "s3cret", "cmd")
        finally:
            rcon_mod.socket.create_connection = orig
        self.assertEqual(text, "part-1 part-2")
        self.assertGreaterEqual(sock.sentinel_recv_calls, 4)

    def test_timeout_without_response(self):
        wire = rcon_mod._packet(1, 0, "")  # auth ok, then nothing

        class FakeSock:
            def __init__(self): self.buf = bytearray(wire)
            def recv(self, n):
                if not self.buf:
                    raise rcon_mod.socket.timeout("idle")
                out = bytes(self.buf[:n]); del self.buf[:n]
                return out
            def sendall(self, data): pass
            def settimeout(self, t): pass
            def close(self): pass

        orig = rcon_mod.socket.create_connection
        rcon_mod.socket.create_connection = lambda *a, **k: FakeSock()
        try:
            with self.assertRaises(rcon_mod.RconTimeout):
                rcon_mod.rcon("h", 1, "s3cret", "cmd", timeout=1)
        finally:
            rcon_mod.socket.create_connection = orig

    def test_password_never_in_errors(self):
        err = rcon_mod.RconAuthError("authentication rejected")
        self.assertNotIn("password", str(err).lower().replace("authentication", ""))


if __name__ == "__main__":
    unittest.main()
