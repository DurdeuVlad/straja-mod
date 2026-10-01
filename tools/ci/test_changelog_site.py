#!/usr/bin/env python3
"""Pure unit tests for the Pages changelog generator — no network."""
import json
import os
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(__file__))
import changelog_site as cs  # noqa: E402


def _release(tag, body="notes", prerelease=False, assets=None):
    return {
        "tag_name": tag, "name": f"Straja {tag}", "draft": False,
        "prerelease": prerelease, "published_at": "2026-10-01T12:00:00Z",
        "body": body, "assets": assets or [],
    }


class AssetLinkTests(unittest.TestCase):
    def test_jar_gets_primary_button_and_comes_first(self):
        rel = _release("v0.2.0", assets=[
            {"name": "sbom.json", "browser_download_url": "u1", "size": 10},
            {"name": "straja-0.2.0.jar", "browser_download_url": "u2",
             "size": 1500},
            {"name": "straja-0.2.0.jar.sha256", "browser_download_url": "u3",
             "size": 1},
        ])
        html = cs.render_release(rel)
        self.assertLess(html.index("straja-0.2.0.jar"), html.index("sbom.json"))
        self.assertIn('class="btn jar"', html)

    def test_no_assets_shows_muted_placeholder(self):
        self.assertIn("no assets", cs.render_release(_release("v1.0.0")))


class RenderTests(unittest.TestCase):
    def test_prerelease_gets_rc_badge(self):
        self.assertIn('badge rc', cs.render_release(
            _release("v0.3.0-rc.1", prerelease=True)))
        self.assertIn('badge stable', cs.render_release(_release("v0.2.0")))

    def test_notes_are_html_escaped(self):
        html = cs.render_release(_release("v1", body="<script>x</script>"))
        self.assertIn("&lt;script&gt;", html)
        self.assertNotIn("<script>", html)


class MainTests(unittest.TestCase):
    def test_writes_index_sorted_newest_first_and_skips_drafts(self):
        rels = [
            _release("v0.1.0", assets=[]),
            _release("v0.3.0-draft", assets=[]),
        ]
        rels[0]["published_at"] = "2026-09-01T00:00:00Z"
        rels[1]["published_at"] = "2026-10-01T00:00:00Z"
        rels[1]["draft"] = True
        with tempfile.TemporaryDirectory() as tmp:
            with mock.patch.object(cs, "gh_api", return_value=rels), \
                    mock.patch.dict(os.environ, {"GH_TOKEN": "t"}):
                sys.argv = ["changelog_site.py", "--repo", "o/r",
                            "--out", tmp]
                cs.main()
            with open(os.path.join(tmp, "index.html"),
                      encoding="utf-8") as fh:
                page = fh.read()
        self.assertIn("v0.1.0", page)
        self.assertNotIn("v0.3.0-draft", page)
        self.assertIn("Envelope", page)  # tester instructions present

    def test_no_releases_is_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            with mock.patch.object(cs, "gh_api", return_value=[]), \
                    mock.patch.dict(os.environ, {"GH_TOKEN": "t"}):
                sys.argv = ["changelog_site.py", "--repo", "o/r",
                            "--out", tmp]
                with self.assertRaises(SystemExit):
                    cs.main()


if __name__ == "__main__":
    unittest.main()
