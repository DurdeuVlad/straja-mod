#!/usr/bin/env python3
"""Unit tests for tools/ci/server_harness.py — no network, no server."""
import hashlib
import json
import os
import struct
import sys
import tempfile
import unittest
import zipfile
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(__file__))
import server_harness as h  # noqa: E402


def _dep(name="envelope", **over):
    dep = {
        "source": "modrinth",
        "sourceId": "modrinth:envelope:MmLbWZuv",
        "url": "https://example.com/envelope.jar",
        "fileName": f"{name}.jar",
        "size": 1,
        "side": "server",
        "expectedModId": "envelope",
        "sha256": "a" * 64,
        "sha512": "b" * 128,
    }
    dep.update(over)
    return dep


def _manifest(**over):
    manifest = {
        "schema": 1,
        "neoforge": {
            "version": "21.1.248",
            "groupId": "net.neoforged",
            "artifactId": "neoforge",
            "classifier": "installer",
            "mavenBase": "https://maven.neoforged.net/releases",
            "checksumSidecars": ["sha256", "sha512"],
        },
        "dependencies": {"envelope": _dep()},
        "profiles": {
            "required-only": {
                "blocking": True,
                "deps": ["envelope"],
                "config": {"identity.environment": "local",
                           "testing.enableTestCommands": True},
                "timeoutSeconds": 600,
            }
        },
    }
    manifest.update(over)
    return manifest


def _write_manifest(tmp, manifest):
    path = os.path.join(tmp, "manifest.json")
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh)
    return path


def _make_jar(path, mod_ids=("testmod",)):
    toml = "".join(f'[[mods]]\nmodId="{mid}"\n' for mid in mod_ids)
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("META-INF/neoforge.mods.toml", toml)
        zf.writestr("dummy.txt", "payload")


class ManifestTests(unittest.TestCase):
    def test_valid_manifest_loads(self):
        with tempfile.TemporaryDirectory() as tmp:
            m = h.load_manifest(_write_manifest(tmp, _manifest()))
        self.assertIn("required-only", m["profiles"])

    def test_rejects_traversal_filename(self):
        with tempfile.TemporaryDirectory() as tmp:
            m = _manifest()
            m["dependencies"]["envelope"]["fileName"] = "../evil.jar"
            with self.assertRaises(h.HarnessError) as cm:
                h.load_manifest(_write_manifest(tmp, m))
        self.assertEqual(cm.exception.kind, h.ERROR)

    def test_rejects_absolute_filename(self):
        with tempfile.TemporaryDirectory() as tmp:
            m = _manifest()
            m["dependencies"]["envelope"]["fileName"] = "/etc/passwd"
            with self.assertRaises(h.HarnessError):
                h.load_manifest(_write_manifest(tmp, m))

    def test_rejects_http_url(self):
        with tempfile.TemporaryDirectory() as tmp:
            m = _manifest()
            m["dependencies"]["envelope"]["url"] = "http://insecure/x.jar"
            with self.assertRaises(h.HarnessError):
                h.load_manifest(_write_manifest(tmp, m))

    def test_rejects_client_side_dep(self):
        with tempfile.TemporaryDirectory() as tmp:
            m = _manifest()
            m["dependencies"]["envelope"]["side"] = "client"
            with self.assertRaises(h.HarnessError):
                h.load_manifest(_write_manifest(tmp, m))

    def test_rejects_bad_digest(self):
        with tempfile.TemporaryDirectory() as tmp:
            m = _manifest()
            m["dependencies"]["envelope"]["sha256"] = "ZZZZ"
            with self.assertRaises(h.HarnessError):
                h.load_manifest(_write_manifest(tmp, m))

    def test_rejects_unknown_profile_dep(self):
        with tempfile.TemporaryDirectory() as tmp:
            m = _manifest()
            m["profiles"]["required-only"]["deps"] = ["ghost"]
            with self.assertRaises(h.HarnessError):
                h.load_manifest(_write_manifest(tmp, m))

    def test_rejects_bad_config_key(self):
        with tempfile.TemporaryDirectory() as tmp:
            m = _manifest()
            m["profiles"]["required-only"]["config"] = {"Bogus": "x"}
            with self.assertRaises(h.HarnessError):
                h.load_manifest(_write_manifest(tmp, m))

    def test_shipped_manifest_is_valid(self):
        repo_manifest = os.path.join(os.path.dirname(__file__),
                                     "server_manifest.json")
        m = h.load_manifest(repo_manifest)
        self.assertEqual(m["neoforge"]["version"], "21.1.248")
        self.assertIn("foreign-npc", m["profiles"])
        self.assertFalse(m["profiles"]["foreign-npc"]["blocking"])


class ChecksumTests(unittest.TestCase):
    def test_verify_artifact_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = os.path.join(tmp, "a.jar")
            data = b"jar bytes"
            with open(p, "wb") as fh:
                fh.write(data)
            h.verify_artifact(p, hashlib.sha256(data).hexdigest(),
                              hashlib.sha512(data).hexdigest(), len(data))

    def test_checksum_mismatch_classified(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = os.path.join(tmp, "a.jar")
            with open(p, "wb") as fh:
                fh.write(b"data")
            with self.assertRaises(h.HarnessError) as cm:
                h.verify_artifact(p, sha256="0" * 64)
            self.assertEqual(cm.exception.kind, h.CHECKSUM_MISMATCH)

    def test_size_mismatch_classified(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = os.path.join(tmp, "a.jar")
            with open(p, "wb") as fh:
                fh.write(b"data")
            with self.assertRaises(h.HarnessError) as cm:
                h.verify_artifact(p, size=999)
            self.assertEqual(cm.exception.kind, h.CHECKSUM_MISMATCH)

    def test_jar_mod_ids(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = os.path.join(tmp, "m.jar")
            _make_jar(p, ("straja", "envelope"))
            self.assertEqual(h.jar_mod_ids(p), {"straja", "envelope"})

    def test_mod_id_mismatch_classified(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = os.path.join(tmp, "m.jar")
            _make_jar(p, ("othermod",))
            with self.assertRaises(h.HarnessError) as cm:
                h.verify_mod_id(p, "straja", "m.jar")
            self.assertEqual(cm.exception.kind, h.MOD_ID_MISMATCH)

    def test_bad_jar_classified_as_mod_id_mismatch(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = os.path.join(tmp, "notajar.bin")
            with open(p, "wb") as fh:
                fh.write(b"not a zip")
            with self.assertRaises(h.HarnessError) as cm:
                h.jar_mod_ids(p)
            self.assertEqual(cm.exception.kind, h.MOD_ID_MISMATCH)


class SidecarTests(unittest.TestCase):
    def test_parse_bare_hex(self):
        self.assertEqual(h._parse_sidecar("a" * 64 + "\n"), "a" * 64)

    def test_parse_hex_with_filename(self):
        self.assertEqual(h._parse_sidecar("b" * 64 + "  neoforge.jar\n"),
                         "b" * 64)

    def test_parse_garbage_returns_empty(self):
        self.assertEqual(h._parse_sidecar("<html>404</html>"), "")


class RedactionTests(unittest.TestCase):
    def test_password_masked(self):
        text = "rcon.password=hunter2\nother=1"
        out = h.redact(text, ["hunter2"])
        self.assertNotIn("hunter2", out)
        self.assertIn("«redacted»", out)

    def test_empty_secret_safe(self):
        self.assertEqual(h.redact("abc", [None, ""]), "abc")


class LogMarkerTests(unittest.TestCase):
    def test_ready_marker_fresh_log(self):
        with tempfile.TemporaryDirectory() as tmp:
            log = os.path.join(tmp, "server.log")
            with open(log, "w") as fh:
                fh.write("[12:00:00] Done (3.2s)! For help, type \"help\"\n")
            self.assertTrue(h._log_contains(log, h._READY_RE))

    def test_ready_marker_absent(self):
        with tempfile.TemporaryDirectory() as tmp:
            log = os.path.join(tmp, "server.log")
            with open(log, "w") as fh:
                fh.write("still starting\n")
            self.assertFalse(h._log_contains(log, h._READY_RE))

    def test_crash_marker(self):
        self.assertTrue(h._CRASH_RE.search(
            "java.lang.Exception in server tick loop"))


class ClassificationTests(unittest.TestCase):
    def test_harness_error_kinds(self):
        for kind in (h.TIMEOUT, h.STARTUP_CRASH, h.DOWNLOAD_MISSING,
                     h.CHECKSUM_MISMATCH, h.MOD_ID_MISMATCH, h.RCON_LOST):
            err = h.HarnessError(kind, "x")
            self.assertIn(err.kind, h.NON_SUCCESS)

    def test_wait_ready_startup_crash(self):
        class FakeProc:
            returncode = 1

            def poll(self):
                return 1

        with tempfile.TemporaryDirectory() as tmp:
            log = os.path.join(tmp, "l.log")
            open(log, "w").close()
            with self.assertRaises(h.HarnessError) as cm:
                h.wait_ready(FakeProc(), log, rcon=None, timeout_s=5)
        self.assertEqual(cm.exception.kind, h.STARTUP_CRASH)

    def test_wait_ready_timeout(self):
        class FakeProc:
            returncode = None

            def poll(self):
                return None

        with tempfile.TemporaryDirectory() as tmp:
            log = os.path.join(tmp, "l.log")
            open(log, "w").close()
            with self.assertRaises(h.HarnessError) as cm:
                h.wait_ready(FakeProc(), log, rcon=None, timeout_s=1)
        self.assertEqual(cm.exception.kind, h.TIMEOUT)


class CleanupTests(unittest.TestCase):
    def test_kill_tree_noop_when_exited(self):
        class FakeProc:
            pid = 999999

            def poll(self):
                return 0

        h.kill_tree(FakeProc())  # must not raise or touch a live pid

    def test_server_properties_bind_loopback(self):
        with tempfile.TemporaryDirectory() as tmp:
            h.write_server_properties(tmp, 30001, 30002, "pw")
            with open(os.path.join(tmp, "server.properties")) as fh:
                props = fh.read()
        self.assertIn("server-ip=127.0.0.1", props)
        self.assertIn("enable-query=false", props)
        self.assertIn("enable-status=false", props)
        self.assertIn("rcon.port=30002", props)

    def test_straja_config_renders_sections(self):
        with tempfile.TemporaryDirectory() as tmp:
            h.write_straja_config(tmp, {"identity.environment": "production",
                                        "testing.enableTestCommands": False,
                                        "debug.enabled": False})
            with open(os.path.join(tmp, "config", "straja-server.toml")) as fh:
                cfg = fh.read()
        self.assertIn("[identity]", cfg)
        self.assertIn('environment = "production"', cfg)
        self.assertIn("enableTestCommands = false", cfg)


class ReportTests(unittest.TestCase):
    def test_junit_and_json_emitted(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = h.ProfileResult(profile="required-only", blocking=True,
                                     advisory=False, status=h.FAIL)
            result.checks = [
                h.CheckResult("boot", h.PASS, "ok", 1.0),
                h.CheckResult("mods", h.CHECKSUM_MISMATCH, "bad jar", 0.5),
            ]
            result.timings = {"total_s": 42}
            h.write_reports(result, tmp)
            tree = ET.parse(os.path.join(tmp, "junit.xml"))
            suite = tree.getroot()
            self.assertEqual(suite.get("tests"), "2")
            self.assertEqual(suite.get("failures"), "1")
            failure = suite.find("testcase/failure")
            self.assertEqual(failure.get("type"), h.CHECKSUM_MISMATCH)
            with open(os.path.join(tmp, "result.json")) as fh:
                data = json.load(fh)
            self.assertEqual(data["status"], h.FAIL)
            self.assertEqual(len(data["checks"]), 2)

    def test_rcon_packet_layout(self):
        pkt = h._rcon_packet(7, 2, "list")
        (length,) = struct.unpack("<i", pkt[:4])
        self.assertEqual(length, len(pkt) - 4)
        req_id, ptype = struct.unpack("<ii", pkt[4:12])
        self.assertEqual((req_id, ptype), (7, 2))
        self.assertTrue(pkt.endswith(b"\x00\x00"))


if __name__ == "__main__":
    unittest.main()
