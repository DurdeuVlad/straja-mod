#!/usr/bin/env python3
"""Pure unit tests for the publish/promote engine — no network, no tokens."""
import hashlib
import json
import os
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(__file__))
import publish as pub  # noqa: E402


def _jar(tmp, content=b"fake-jar"):
    path = os.path.join(tmp, "straja-0.2.0.jar")
    with open(path, "wb") as fh:
        fh.write(content)
    return path


class EvidenceTests(unittest.TestCase):
    GATES = {"game_tests": "success", "server_profiles": "success",
             "rc_scenarios": "success", "rc_performance": "success",
             "foreign_npc_advisory": "pass", "client_ui_advisory": "blocked"}

    def test_green_evidence(self):
        doc = pub.build_evidence("v0.2.0-rc.1", "deadbeef", "0.2.0",
                                 "straja-0.2.0.jar", "a" * 64, "b" * 128,
                                 self.GATES)
        self.assertTrue(doc["gates_green"])
        self.assertEqual(doc["advisory"]["client_ui_advisory"], "blocked")

    def test_blocking_failure_not_green(self):
        gates = dict(self.GATES, rc_performance="failure")
        doc = pub.build_evidence("v0.2.0-rc.1", "c", "0.2.0",
                                 "straja-0.2.0.jar", "a" * 64, "b" * 128,
                                 gates)
        self.assertFalse(doc["gates_green"])

    def test_tag_version_mismatch_fails(self):
        with self.assertRaises(pub.PublishError):
            pub.build_evidence("v0.3.0-rc.1", "c", "0.2.0",
                               "straja-0.2.0.jar", "a", "b", self.GATES)

    def test_bad_tag_fails(self):
        with self.assertRaises(pub.PublishError):
            pub.build_evidence("release-1", "c", "0.2.0",
                               "straja-0.2.0.jar", "a", "b", self.GATES)


class SbomTests(unittest.TestCase):
    def test_components(self):
        manifest = {"neoforge": {"version": "21.1.235"},
                    "dependencies": {"vampirism": {"version": "1.10.7",
                                                 "url": "https://x/m.jar"}}}
        doc = pub.build_sbom("0.2.0", manifest)
        names = [c["name"] for c in doc["components"]]
        self.assertIn("neoforge", names)
        self.assertIn("vampirism", names)


class FakeHttp:
    """Patches publish._http with a URL->response map. Records calls."""

    def __init__(self, responses):
        self.responses = responses
        self.calls = []

    def __call__(self, url, **kw):
        self.calls.append((kw.get("method", "GET"), url))
        for key in sorted(self.responses, key=len, reverse=True):
            value = self.responses[key]
            if key in url:
                if isinstance(value, Exception):
                    raise value
                return value
        raise pub.PublishError(f"unstubbed {url}")


class ModrinthTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.jar = _jar(self.tmp)
        self.sha = hashlib.sha256(b"fake-jar").hexdigest()
        self.ledger = pub.Ledger()

    def test_creates_when_absent(self):
        fake = FakeHttp({"/project/p/version": [],
                         "/version": {"id": "v1"}})
        with mock.patch.object(pub, "_http", fake):
            e = pub.modrinth_publish("p", "tok", "v0.2.0-rc.1", "Straja",
                                     "beta", self.jar, self.sha,
                                     ["1.21.1"], ["neoforge"], "notes",
                                     False, self.ledger)
        self.assertEqual(e.state, "published")
        self.assertEqual(e.remote_id, "v1")
        self.assertTrue(any(m == "POST" for m, _ in fake.calls))

    def test_same_hash_is_noop(self):
        fake = FakeHttp({"/project/p/version":
                         [{"version_number": "v0.2.0-rc.1", "id": "v1",
                           "files": [{"primary": True,
                                      "hashes": {"sha256": self.sha}}]}]})
        with mock.patch.object(pub, "_http", fake):
            e = pub.modrinth_publish("p", "tok", "v0.2.0-rc.1", "Straja",
                                     "beta", self.jar, self.sha,
                                     ["1.21.1"], ["neoforge"], "",
                                     False, self.ledger)
        self.assertEqual(e.state, "noop")
        self.assertFalse(any(m == "POST" for m, _ in fake.calls))

    def test_hash_conflict_fails_closed(self):
        fake = FakeHttp({"/project/p/version":
                         [{"version_number": "v0.2.0-rc.1", "id": "v1",
                           "files": [{"primary": True,
                                      "hashes": {"sha256": "0" * 64}}]}]})
        with mock.patch.object(pub, "_http", fake):
            with self.assertRaises(pub.PublishError):
                pub.modrinth_publish("p", "tok", "v0.2.0-rc.1", "S", "beta",
                                     self.jar, self.sha, ["1.21.1"],
                                     ["neoforge"], "", False, self.ledger)

    def test_dry_run_skips_mutation(self):
        fake = FakeHttp({"/project/p/version": []})
        with mock.patch.object(pub, "_http", fake):
            e = pub.modrinth_publish("p", "tok", "v0.2.0-rc.1", "S", "beta",
                                     self.jar, self.sha, ["1.21.1"],
                                     ["neoforge"], "", True, self.ledger)
        self.assertEqual(e.state, "dry-run")
        self.assertFalse(any(m == "POST" for m, _ in fake.calls))

    def test_promote_patches_rc(self):
        versions = [{"version_number": "v0.2.0-rc.1", "id": "v1",
                     "version_type": "beta",
                     "files": [{"primary": True,
                                "hashes": {"sha256": self.sha}}]}]
        fake = FakeHttp({"/project/p/version": versions,
                         "/version/v1": {}})
        with mock.patch.object(pub, "_http", fake):
            e = pub.modrinth_promote("p", "tok", "v0.2.0-rc.1", "v0.2.0",
                                     "Straja 0.2.0", self.sha,
                                     ["1.21.1"], ["neoforge"],
                                     False, self.ledger)
        self.assertEqual(e.state, "promoted")
        self.assertTrue(any(m == "PATCH" for m, _ in fake.calls))

    def test_promote_stable_exists_noop(self):
        versions = [{"version_number": "v0.2.0", "id": "v9",
                     "files": [{"primary": True,
                                "hashes": {"sha256": self.sha}}]}]
        fake = FakeHttp({"/project/p/version": versions})
        with mock.patch.object(pub, "_http", fake):
            e = pub.modrinth_promote("p", "tok", "v0.2.0-rc.1", "v0.2.0",
                                     "Straja 0.2.0", self.sha,
                                     ["1.21.1"], ["neoforge"],
                                     False, self.ledger)
        self.assertEqual(e.state, "noop")
        self.assertFalse(any(m == "PATCH" for m, _ in fake.calls))

    def test_promote_hash_mismatch_fails(self):
        versions = [{"version_number": "v0.2.0-rc.1", "id": "v1",
                     "files": [{"primary": True,
                                "hashes": {"sha256": "f" * 64}}]}]
        fake = FakeHttp({"/project/p/version": versions})
        with mock.patch.object(pub, "_http", fake):
            with self.assertRaises(pub.PublishError):
                pub.modrinth_promote("p", "tok", "v0.2.0-rc.1", "v0.2.0",
                                     "S", self.sha, ["1.21.1"],
                                     ["neoforge"], False, self.ledger)

    def test_promote_missing_rc_fails(self):
        fake = FakeHttp({"/project/p/version": []})
        with mock.patch.object(pub, "_http", fake):
            with self.assertRaises(pub.PublishError):
                pub.modrinth_promote("p", "tok", "v0.2.0-rc.1", "v0.2.0",
                                     "S", self.sha, [], [], False,
                                     self.ledger)


class CurseForgeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.jar = _jar(self.tmp)
        self.sha1 = hashlib.sha1(b"fake-jar").hexdigest()
        self.ledger = pub.Ledger()

    def test_uploads_when_absent(self):
        fake = FakeHttp({"/files": {"data": []},
                         "version-types": {"data": []},
                         "/mods/p/files": {"id": 42}})
        with mock.patch.object(pub, "_http", fake):
            e = pub.curseforge_publish("p", "tok", "v0.2.0-rc.1", "Straja",
                                       "beta", self.jar, ["123"],
                                       "notes", False, self.ledger)
        self.assertEqual(e.state, "published")
        self.assertEqual(e.remote_id, "42")

    def test_matching_hash_is_noop(self):
        files = {"data": [{"id": 7, "fileName": "straja-0.2.0.jar",
                           "hashes": [{"algo": 1, "value": self.sha1}]}]}
        fake = FakeHttp({"/files": files})
        with mock.patch.object(pub, "_http", fake):
            e = pub.curseforge_publish("p", "tok", "v0.2.0", "S",
                                       "release", self.jar, ["1"],
                                       "", False, self.ledger)
        self.assertEqual(e.state, "noop")
        self.assertFalse(any(m == "POST" for m, _ in fake.calls))

    def test_different_hash_same_name_uploads(self):
        """Same filename, different bytes -> the file genuinely differs,
        so a new upload is correct (CF allows multiple files per name)."""
        files = {"data": [{"id": 7, "fileName": "straja-0.2.0.jar",
                           "hashes": [{"algo": 1, "value": "0" * 40}]}]}
        fake = FakeHttp({"/files": files, "/mods/p/files": {"id": 9}})
        with mock.patch.object(pub, "_http", fake):
            e = pub.curseforge_publish("p", "tok", "v0.2.0", "S",
                                       "release", self.jar, ["5"],
                                       "", False, self.ledger)
        self.assertEqual(e.state, "published")


class GitHubTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.jar = _jar(self.tmp)
        self.ledger = pub.Ledger()

    def _gh(self, payload):
        def fake(args, token=None):
            if "view" in args:
                return json.dumps(payload) if payload else \
                    (_ for _ in ()).throw(pub.PublishError("not found"))
            return ""
        return fake

    def test_creates_prerelease(self):
        calls = []

        def fake(args, token=None):
            calls.append(args[:2])
            if "view" in args:
                if not any("create" in c for c in calls):
                    raise pub.PublishError("not found")
                return json.dumps({"id": 5, "tagName": "v0.2.0-rc.1"})
            return ""

        with mock.patch.object(pub, "_gh", fake):
            e = pub.gh_create_release("r/r", "v0.2.0-rc.1", "RC1", "notes",
                                      [self.jar], True, False, "abc",
                                      False, self.ledger)
        self.assertEqual(e.state, "published")

    def test_existing_release_noop_uploads_missing(self):
        rel = {"id": 5, "tagName": "v0.2.0-rc.1",
               "assets": [{"name": "other.txt"}]}
        calls = []

        def fake(args, token=None):
            calls.append(args)
            if "view" in args:
                return json.dumps(rel)
            return ""

        with mock.patch.object(pub, "_gh", fake):
            e = pub.gh_create_release("r/r", "v0.2.0-rc.1", "RC1", "n",
                                      [self.jar], True, False, "abc",
                                      False, self.ledger)
        self.assertEqual(e.state, "noop")
        self.assertTrue(any(a[0] == "release" and a[1] == "upload"
                            for a in calls))


class ResolveRcTests(unittest.TestCase):
    def test_highest_green_on_commit_wins(self):
        manifests = {
            "v0.2.0-rc.1": {"gates_green": True, "commit": "abc",
                            "sha256": "1" * 64},
            "v0.2.0-rc.2": {"gates_green": True, "commit": "abc",
                            "sha256": "2" * 64},
            "v0.2.0-rc.3": {"gates_green": False, "commit": "abc",
                            "sha256": "3" * 64},
        }
        listing = [{"tagName": t, "isPrerelease": True, "isDraft": False}
                   for t in manifests]

        def fake_list(repo, token=None):
            return listing

        def fake_view(repo, tag, token=None):
            return {"tagName": tag, "targetCommitish": "abc",
                    "assets": [{"name": "release-manifest.json"}]}

        def fake_dl(repo, tag, asset, out):
            path = os.path.join(out, asset)
            with open(path, "w") as fh:
                json.dump(manifests[tag], fh)
            return path

        with mock.patch.object(pub, "gh_list_releases", fake_list), \
                mock.patch.object(pub, "gh_release", fake_view), \
                mock.patch.object(pub, "gh_download_asset", fake_dl):
            m = pub.resolve_rc("r/r", "v0.2.0", "abc")
        self.assertEqual(m["rc_tag"], "v0.2.0-rc.2")

    def test_wrong_commit_skipped(self):
        manifests = {"v0.2.0-rc.1": {"gates_green": True, "commit": "other",
                                     "sha256": "1" * 64}}
        listing = [{"tagName": "v0.2.0-rc.1", "isPrerelease": True,
                    "isDraft": False}]

        def fake_view(repo, tag, token=None):
            return {"tagName": tag, "targetCommitish": "other",
                    "assets": []}

        with mock.patch.object(pub, "gh_list_releases",
                               lambda repo, token=None: listing), \
                mock.patch.object(pub, "gh_release", fake_view):
            with self.assertRaises(pub.PublishError):
                pub.resolve_rc("r/r", "v0.2.0", "abc")

    def test_no_candidates_fails(self):
        with mock.patch.object(pub, "gh_list_releases",
                               lambda repo, token=None: []):
            with self.assertRaises(pub.PublishError):
                pub.resolve_rc("r/r", "v0.2.0", "abc")

    def test_rejects_non_stable_tag(self):
        with self.assertRaises(pub.PublishError):
            pub.resolve_rc("r/r", "v0.2.0-rc.1", "abc")


if __name__ == "__main__":
    unittest.main()
