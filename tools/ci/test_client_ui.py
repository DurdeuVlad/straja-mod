#!/usr/bin/env python3
"""Pure unit tests for the advisory client-UI runner — no client, no server."""
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import client_ui as cu  # noqa: E402


def _scenario(steps, **extra):
    base = {"name": "t", "group": "g", "observable": "o", "failure": "f",
            "steps": steps}
    base.update(extra)
    return base


class ValidateScenarioTests(unittest.TestCase):
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


class _FakeRcon:
    def __init__(self, out="ok"):
        self.out = out
        self.commands = []

    def execute(self, command, timeout=None):
        self.commands.append(command)
        return self.out


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
