#!/usr/bin/env python3
"""Pure unit tests for the advisory client-UI runner — no client, no server."""
import json
import os
import socket
import sys
import tempfile
import threading
import time
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import client_ui as cu  # noqa: E402


def _scenario(steps, **extra):
    base = {"name": "t", "group": "g", "observable": "o", "failure": "f",
            "steps": steps}
    base.update(extra)
    return base


class ValidateScenarioTests(unittest.TestCase):
    def test_repository_scenarios_validate(self):
        root = os.path.join(os.path.dirname(__file__), "client_scenarios")
        paths = []
        for directory, _, names in os.walk(root):
            paths.extend(os.path.join(directory, name)
                         for name in names if name.endswith(".json"))
        self.assertTrue(paths)
        for path in sorted(paths):
            with self.subTest(path=os.path.relpath(path, root)):
                with open(path, encoding="utf-8") as fh:
                    raw = json.load(fh)
                self.assertEqual(cu.validate_scenario(raw, path), [])

    def test_admin_provisioning_covers_unassign_and_native_host(self):
        # NPC-024 keeps the provisioning scenario aligned with the release-gate
        # runbook: wand selection, replacement, reconnect, unassignment, and a
        # post-unassign check that only native CustomNPCs behavior remains.
        path = os.path.join(os.path.dirname(__file__), "client_scenarios",
                            "customnpcs", "admin_provisioning.json")
        with open(path, encoding="utf-8") as fh:
            raw = json.load(fh)
        names = [step.get("name", "") for step in raw["steps"]]
        for marker in ("capture-customnpc", "left-click-with-admin-wand",
                       "choose-jailer-profile", "confirm-jailer-profile",
                       "player-surface-action-dispatches",
                       "choose-secretary-profile", "confirm-secretary-profile",
                       "reconnect-for-persistence-check",
                       "click-unassign", "confirm-unassign",
                       "interact-after-unassign",
                       "native-host-behavior-after-unassign",
                       "rejoin-after-unassign",
                       "native-behavior-persists-after-rejoin"):
            self.assertIn(marker, names)
        for marker in ("provider-status-after-unassign",
                       "unassigned-state-screenshot"):
            self.assertIn(marker, names)
        click_at = names.index("click-unassign")
        confirm_at = names.index("confirm-unassign")
        native_at = names.index("native-host-behavior-after-unassign")
        self.assertLess(click_at, confirm_at)
        self.assertLess(confirm_at, native_at)
        native_step = raw["steps"][native_at]
        self.assertIn("expectAbsentRegex", native_step)
        self.assertIn("GuiCustom", native_step["expectAbsentRegex"])
        status_step = raw["steps"][names.index("provider-status-after-unassign")]
        self.assertIn("expectAbsentRegex", status_step)

    def test_mct_text_expectations_rejected_when_expect_present(self):
        raw = _scenario([{"type": "mct", "args": ["gui", "info"],
                          "expect": [{"rawRegex": "open"}],
                          "expectAbsentRegex": "GuiCustom"}])
        self.assertTrue(any("ignored" in e for e in cu.validate_scenario(raw, "p")))
        raw = _scenario([{"type": "mct", "args": ["gui", "info"],
                          "expectAbsentRegex": "GuiCustom"}])
        self.assertEqual(cu.validate_scenario(raw, "p"), [])

    def test_valid_minimal(self):
        raw = _scenario([{"type": "mct", "args": ["status", "health"],
                          "expect": [{"path": "health", "gte": 1}]}])
        self.assertEqual(cu.validate_scenario(raw, "p"), [])

    def test_missing_keys(self):
        self.assertTrue(cu.validate_scenario({}, "p"))

    def test_no_expect_anywhere_fails(self):
        raw = _scenario([{"type": "rcon", "command": "x", "setup": True},
                         {"type": "sleep", "seconds": 1}])
        self.assertTrue(cu.validate_scenario(raw, "p"))


    def test_rcon_needs_expect_or_setup(self):
        raw = _scenario([{"type": "rcon", "command": "x"},
                         {"type": "sleep", "seconds": 1}])
        errors = cu.validate_scenario(raw, "p")
        self.assertTrue(any("expectation" in e for e in errors))

    def test_npc_action_is_expectation_bearing(self):
        raw = _scenario([{"type": "npc-action", "label": "Depune cererea"}])
        self.assertEqual(cu.validate_scenario(raw, "p"), [])

    def test_npc_action_needs_label(self):
        raw = _scenario([{"type": "npc-action"}])
        self.assertTrue(cu.validate_scenario(raw, "p"))

    def test_unknown_type(self):
        raw = _scenario([{"type": "bogus"}])
        self.assertTrue(cu.validate_scenario(raw, "p"))

    def test_capture_needs_name_and_regex(self):
        raw = _scenario([{"type": "rcon", "command": "x", "setup": True,
                          "capture": {"name": "a"}},
                         {"type": "rcon", "command": "y",
                          "expectContains": "z"}])
        self.assertTrue(cu.validate_scenario(raw, "p"))


class PreflightTests(unittest.TestCase):
    def test_preflight_installs_mct_in_isolated_home_with_overrides(self):
        manifest = {
            "mct": {"package": "@scope/mct", "version": "1.2.3",
                    "integrity": "sha512-pinned",
                    "overrides": {"broken-package": "1.0.0"}},
            "client": {"java": "java"},
        }
        with tempfile.TemporaryDirectory() as home:
            env = {"MCT_HOME": home}
            calls = []

            def fake_run(command, **kwargs):
                calls.append((command, kwargs))
                if command[1] == "--version":
                    return type("Result", (), {"returncode": 0,
                                                "stdout": "v20.12.0\n",
                                                "stderr": ""})()
                if command[1] == "view":
                    return type("Result", (), {"returncode": 0,
                                                "stdout": "sha512-pinned\n",
                                                "stderr": ""})()
                if command[1] == "install":
                    binary = os.path.join(kwargs["cwd"], "node_modules",
                                          ".bin", "mct")
                    os.makedirs(os.path.dirname(binary), exist_ok=True)
                    open(binary, "w", encoding="utf-8").close()
                    return type("Result", (), {"returncode": 0,
                                                "stdout": "",
                                                "stderr": ""})()
                raise AssertionError(command)

            import unittest.mock as mock
            with mock.patch.object(cu, "_which",
                                   side_effect=lambda name: name), \
                 mock.patch.object(cu.subprocess, "run", side_effect=fake_run):
                mct = cu.preflight(manifest, env, lambda _: None)

            install_root = os.path.join(home, "npm")
            self.assertEqual(mct, os.path.join(install_root, "node_modules",
                                                ".bin", "mct"))
            with open(os.path.join(install_root, "package.json"),
                      encoding="utf-8") as fh:
                package = json.load(fh)
            self.assertEqual(package["dependencies"],
                             {"@scope/mct": "1.2.3"})
            self.assertEqual(package["overrides"],
                             {"broken-package": "1.0.0"})
            install_call = next(item for item in calls
                                if item[0][1] == "install")
            self.assertEqual(install_call[1]["cwd"], install_root)


class _FakeMct:
    binary = "mct"
    env = {}

    def __init__(self, replies=None):
        self.replies = replies or {}
        self.calls = []

    def __call__(self, args, timeout=60):
        self.calls.append(list(args))
        return "ok"

    def json(self, args, timeout=60):
        self.calls.append(list(args))
        return self.replies.get(tuple(args), {})


class ClientModTests(unittest.TestCase):
    def test_seed_client_mod_uses_server_dependency_contract(self):
        manifest = {"clientMod": {"file": "mct-bridge.jar",
                                   "url": "https://example.invalid/bridge",
                                   "sha256": "abc", "sha512": "def",
                                   "size": 123, "expectedModId": "mct"}}
        with tempfile.TemporaryDirectory() as home:
            env = {"MCT_CACHE_DIR": os.path.join(home, "cache")}
            import unittest.mock as mock
            with mock.patch.object(cu.sh, "download_dependency",
                                   return_value="cached.jar") as download:
                result = cu.seed_client_mod(manifest, env, lambda _: None)
            self.assertEqual(result, "cached.jar")
            download.assert_called_once_with(
                {"fileName": "mct-bridge.jar",
                 "url": "https://example.invalid/bridge", "sha256": "abc",
                 "sha512": "def", "size": 123, "expectedModId": "mct"},
                os.path.join(home, "cache", "mod"))

    def test_create_client_unwraps_mct_success_envelope(self):
        manifest = {
            "client": {"name": "ci-straja", "loader": "neoforge",
                       "minecraftVersion": "1.21.1", "account": "ci",
                       "language": "en_us"},
            "clientMod": {"file": "mct-client-mod.jar", "sha256": "abc"},
        }
        with tempfile.TemporaryDirectory() as home:
            mods_dir = os.path.join(home, "mods")
            minecraft_dir = os.path.join(home, "minecraft")
            os.makedirs(mods_dir)
            os.makedirs(minecraft_dir)
            jar_path = os.path.join(home, "straja.jar")
            bridge_path = os.path.join(mods_dir, "mct-client-mod.jar")
            open(jar_path, "wb").close()
            open(bridge_path, "wb").close()
            reply = {"success": True,
                     "data": {"modsDir": mods_dir,
                              "minecraftDir": minecraft_dir}}
            mct = _FakeMct({("client", "create", "ci-straja", "--loader",
                              "neoforge", "--version", "1.21.1", "--account",
                              "ci", "--java", "java"): reply})
            import unittest.mock as mock
            with mock.patch.object(cu.sh, "verify_artifact") as verify:
                result = cu.create_client(mct, manifest, jar_path, [], {},
                                          lambda _: None)

            self.assertEqual(result, "ci-straja")
            with open(os.path.join(minecraft_dir, "options.txt"),
                      encoding="utf-8") as fh:
                self.assertIn("lang:en_us", fh.read())
            self.assertTrue(os.path.exists(os.path.join(mods_dir,
                                                         "straja.jar")))
            verify.assert_called_once_with(bridge_path, sha256="abc",
                                           label="mct client-mod bridge")


class _FakeRcon:
    def __init__(self, out="ok"):
        self.out = out
        self.commands = []

    def execute(self, command, timeout=None):
        self.commands.append(command)
        return self.out


class RconStepTests(unittest.TestCase):
    def test_rcon_step_uses_shared_adapter_contract(self):
        class StrictRcon:
            def __init__(self):
                self.commands = []

            def execute(self, command):
                self.commands.append(command)
                return "ok"

        rcon = StrictRcon()
        ctx = _ctx(rcon=rcon)
        cu._exec_step({"type": "rcon", "command": "list",
                       "expectContains": "ok"}, ctx, [])
        self.assertEqual(rcon.commands, ["list"])


class ClientActionTests(unittest.TestCase):
    def test_reconnect_preserves_bridge_and_waits_for_world(self):
        mct = _FakeMct()
        waited = []
        ctx = _ctx(mct=mct)
        ctx.wait_ready = lambda: waited.append(True)
        cu._exec_step({"type": "client", "action": "reconnect"}, ctx, [])
        self.assertIn(
            ["client", "reconnect", "--address", "127.0.0.1:25565"],
            mct.calls,
        )
        self.assertEqual(waited, [True])


class WsPortReleaseTests(unittest.TestCase):
    def test_returns_immediately_when_port_is_free(self):
        # Find a port the OS reports as free, then wait on it.
        probe = socket.socket()
        probe.bind(("127.0.0.1", 0))
        free_port = probe.getsockname()[1]
        probe.close()
        manifest = {"client": {"wsPort": free_port}}
        transcript = cu.Transcript()
        start = time.monotonic()
        cu._await_ws_port_release(manifest, transcript, timeout_s=30)
        self.assertLess(time.monotonic() - start, 5)
        self.assertEqual([], transcript.entries)

    def test_records_and_proceeds_when_port_stays_held(self):
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        listener.listen(5)
        held_port = listener.getsockname()[1]
        manifest = {"client": {"wsPort": held_port}}
        transcript = cu.Transcript()
        try:
            cu._await_ws_port_release(manifest, transcript, timeout_s=2)
        finally:
            listener.close()
        self.assertEqual("ws-port-release",
                         transcript.entries[0]["label"])

    def test_proceeds_once_listener_releases(self):
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        listener.listen(5)
        held_port = listener.getsockname()[1]
        manifest = {"client": {"wsPort": held_port}}
        transcript = cu.Transcript()
        threading.Timer(0.5, listener.close).start()
        cu._await_ws_port_release(manifest, transcript, timeout_s=30)
        self.assertEqual([], transcript.entries)


def _ctx(mct=None, rcon=None):
    return cu.ClientContext(
        mct=mct or _FakeMct(), rcon=rcon or _FakeRcon(),
        artifacts=".", transcript=cu.Transcript(), manifest={},
        env={}, client_name="c", game_port=25565,
        launch_client=lambda: None, wait_ready=lambda: None)


class InterpCaptureTests(unittest.TestCase):
    def test_interp_and_capture(self):
        ctx = _ctx()
        step = {"capture": {"name": "tok", "regex": "token=(\\w+)"}}
        cu._capture(step, "x token=abc123 y", ctx)
        self.assertEqual(ctx.vars["tok"], "abc123")
        self.assertEqual(cu._interp("cmd {tok}!", ctx), "cmd abc123!")

    def test_captures_list(self):
        ctx = _ctx()
        step = {"captures": [
            {"name": "x", "regex": "\"x\":\\s*(-?[0-9.]+)"},
            {"name": "z", "regex": "\"z\":\\s*(-?[0-9.]+)"}]}
        cu._capture(step, '{"x": -1.5, "y": -60, "z": 3}', ctx)
        self.assertEqual(ctx.vars["x"], "-1.5")
        self.assertEqual(ctx.vars["z"], "3")

    def test_unknown_var_raises(self):
        ctx = _ctx()
        with self.assertRaises(cu.ClientUiError):
            cu._interp("{missing}", ctx)

    def test_capture_miss_raises(self):
        ctx = _ctx()
        with self.assertRaises(cu.ClientUiError):
            cu._capture({"capture": {"name": "a", "regex": "zzz"}}, "abc", ctx)


class VarStepTests(unittest.TestCase):
    def test_var_rules(self):
        ctx = _ctx()
        ctx.vars["q"] = "Care este regula de bază? (scrie: disciplina)"
        step = {"name": "a", "from": "q",
                "rules": [{"regex": "regula", "value": "disciplina"},
                          {"regex": "minute", "value": "30"}]}
        steps = []
        cu._exec_step({"type": "var", **step}, ctx, steps)
        self.assertEqual(ctx.vars["a"], "disciplina")

    def test_var_no_match_no_default_fails(self):
        ctx = _ctx()
        ctx.vars["q"] = "unrelated"
        with self.assertRaises(cu.ClientUiError):
            cu._exec_step({"type": "var", "name": "a", "from": "q",
                           "rules": [{"regex": "zzz", "value": "1"}]},
                          ctx, [])
        ctx2 = _ctx()
        ctx2.vars["q"] = "unrelated"
        cu._exec_step({"type": "var", "name": "a", "from": "q",
                       "rules": [{"regex": "zzz", "value": "1"}],
                       "default": "d"}, ctx2, [])
        self.assertEqual(ctx2.vars["a"], "d")


class NpcActionTests(unittest.TestCase):
    RAW = ("{\"text\":\"[Straja] Recepționistă: ceva\",\"extra\":["
           "{\"text\":\" \"},"
           "{\"text\":\"[Depune cererea]\",\"clickEvent\":{\"action\":"
           "\"run_command\",\"value\":\"/straja npc-action tok-1\"}},"
           "{\"text\":\" \"},"
           "{\"text\":\"[Regulament]\",\"clickEvent\":{\"action\":"
           "\"run_command\",\"value\":\"/straja npc-action tok-2\"}}]}")

    def test_extracts_label_command(self):
        mct = _FakeMct({("chat", "history", "--last", "20"):
                        {"messages": [{"plain": "x", "raw": self.RAW}]}})
        ctx = _ctx(mct=mct)
        cu._exec_step({"type": "npc-action", "label": "Depune cererea"},
                      ctx, [])
        self.assertIn(["chat", "send", "/straja npc-action tok-1"],
                      mct.calls)

    def test_missing_label_fails(self):
        mct = _FakeMct({("chat", "history", "--last", "20"):
                        {"messages": [{"plain": "x", "raw": self.RAW}]}})
        ctx = _ctx(mct=mct)
        with self.assertRaises(cu.ClientUiError) as cm:
            cu._exec_step({"type": "npc-action", "label": "Nu există"},
                          ctx, [])
        self.assertEqual(cm.exception.kind, "assertion")

    def test_nested_siblings_walked(self):
        raw = json.dumps({"siblings": [{"text": "[X]",
                                        "clickEvent": {"action": "run_command",
                                                       "value": "/do x"}}]})
        mct = _FakeMct({("chat", "history", "--last", "5"):
                        [{"raw": raw}]})
        ctx = _ctx(mct=mct)
        cu._exec_step({"type": "npc-action", "label": "X",
                       "historyLast": 5}, ctx, [])
        self.assertIn(["chat", "send", "/do x"], mct.calls)


class FormStepTests(unittest.TestCase):
    def test_fill_then_submit_tabs_to_submit(self):
        mct = _FakeMct({("gui", "info"): {"title": "Raport de activitate"}})
        ctx = _ctx(mct=mct)
        cu._exec_step({"type": "form", "fill": ["a", "b"],
                       "submit": True}, ctx, [])
        presses = [c for c in mct.calls if c[:3] == ["input", "key", "press"]]
        types = [c for c in mct.calls if c[:2] == ["input", "type"]]
        # fill: tab+type per field, then submit: tab+enter
        self.assertEqual([p[3] for p in presses],
                         ["tab", "tab", "tab", "enter"])
        self.assertEqual([t[2] for t in types], ["a", "b"])

    def test_cancel_tabs_past_submit(self):
        mct = _FakeMct({("gui", "info"): {"title": "Give up"}})
        ctx = _ctx(mct=mct)
        cu._exec_step({"type": "form", "cancel": True}, ctx, [])
        presses = [c[3] for c in mct.calls
                   if c[:3] == ["input", "key", "press"]]
        self.assertEqual(presses, ["tab", "tab", "enter"])

    def test_close_presses_escape(self):
        mct = _FakeMct({("gui", "info"): {"title": "x"}})
        ctx = _ctx(mct=mct)
        cu._exec_step({"type": "form", "close": True}, ctx, [])
        presses = [c[3] for c in mct.calls
                   if c[:3] == ["input", "key", "press"]]
        self.assertEqual(presses, ["escape"])

    def test_title_mismatch_fails(self):
        mct = _FakeMct({("gui", "info"): {"title": "Wrong"}})
        ctx = _ctx(mct=mct)
        with self.assertRaises(cu.ClientUiError) as cm:
            cu._exec_step({"type": "form",
                           "expectTitleRegex": "Raport"}, ctx, [])
        self.assertEqual(cm.exception.kind, "assertion")


class OccurrencesTests(unittest.TestCase):
    def test_occurrence_count(self):
        ctx = _ctx(mct=_FakeMct({("chat", "history", "--last", "5"):
                                 {"messages": [{"plain": "depus depus"}]}}))
        # _FakeMct.json returns the dict; occurrences scans the serialized reply
        cu._exec_step({"type": "mct",
                       "args": ["chat", "history", "--last", "5"],
                       "expect": [{"occurrences": "depus", "count": 2}]},
                      ctx, [])
        with self.assertRaises(cu.ClientUiError):
            cu._exec_step({"type": "mct",
                           "args": ["chat", "history", "--last", "5"],
                           "expect": [{"occurrences": "depus",
                                       "count": 1}]}, ctx, [])


class Client2Tests(unittest.TestCase):
    def _patch_mct(self):
        created = []
        orig = cu.Mct
        cu.Mct = lambda binary, name, env, transcript: created.append(name) \
            or _FakeMct()
        self.addCleanup(lambda: setattr(cu, "Mct", orig))
        return created

    def test_join_launches_second_client(self):
        created = self._patch_mct()
        manifest = {"client": {"account": "ci_straja",
                               "readyTimeoutSeconds": 10}}
        ctx = _ctx()
        ctx.manifest = manifest
        ctx.jar_path = "j.jar"
        ctx.dep_paths = []
        import unittest.mock as mock
        with mock.patch.object(cu, "create_client"):
            cu._exec_step({"type": "client2", "action": "join"}, ctx, [])
        self.assertEqual(created, ["c-2"])

    def test_stop_and_mct_actions(self):
        self._patch_mct()
        ctx = _ctx()
        ctx.manifest = {"client": {"account": "ci_straja"}}
        cu._exec_step({"type": "client2", "action": "stop"}, ctx, [])
        mct2 = _FakeMct({("status", "health"): {"health": 20}})
        orig = cu.Mct
        cu.Mct = lambda *a, **k: mct2
        self.addCleanup(lambda: setattr(cu, "Mct", orig))
        cu._exec_step({"type": "client2", "action": "mct",
                       "args": ["status", "health"],
                       "expect": [{"path": "health", "gte": 1}]}, ctx, [])
        self.assertIn(["status", "health"], mct2.calls)

    def test_unknown_action_fails(self):
        self._patch_mct()
        ctx = _ctx()
        ctx.manifest = {"client": {"account": "x"}}
        with self.assertRaises(cu.ClientUiError):
            cu._exec_step({"type": "client2", "action": "bogus"}, ctx, [])


class ExpectJsonTests(unittest.TestCase):
    def test_paths_and_ops(self):
        reply = {"health": 20, "pos": {"x": 1.5}, "items": ["a", "b"]}
        cu._expect_json(reply, [{"path": "health", "gte": 20},
                                {"path": "pos.x", "equals": 1.5},
                                {"path": "items.1", "equals": "b"}], "t")
        with self.assertRaises(cu.ClientUiError):
            cu._expect_json(reply, [{"path": "health", "gt": 20}], "t")

    def test_raw_regex(self):
        cu._expect_json({"a": 1}, [{"rawRegex": "\"a\""}], "t")
        with self.assertRaises(cu.ClientUiError):
            cu._expect_json({"a": 1}, [{"rawRegex": "\"b\""}], "t")


class StatusClassificationTests(unittest.TestCase):
    def test_block_vs_fail_kinds(self):
        for kind in ("no_tool", "install_failed", "client_boot",
                     "client_join_ws"):
            self.assertIn(kind, cu._BLOCK_KINDS)
        for kind in ("assertion", "timeout", "process"):
            self.assertIn(kind, cu._FAIL_KINDS)


class WriteReportsTests(unittest.TestCase):
    def test_reports_written(self):
        import tempfile
        report = {"verdict": "pass", "verdict_reason": "ok",
                  "scenarios": [{"name": "s1", "group": "g",
                                 "status": "pass", "steps": []},
                                {"name": "s2", "group": "g",
                                 "status": "skipped", "steps": [],
                                 "reason": "capacity"}],
                  "transcript": [{"t": 1, "label": "x", "data": None}]}
        with tempfile.TemporaryDirectory() as tmp:
            cu.write_reports(report, tmp)
            self.assertTrue(os.path.exists(
                os.path.join(tmp, "client-report.json")))
            self.assertTrue(os.path.exists(os.path.join(tmp, "junit.xml")))
            self.assertTrue(os.path.exists(
                os.path.join(tmp, "transcript.json")))


if __name__ == "__main__":
    unittest.main()
