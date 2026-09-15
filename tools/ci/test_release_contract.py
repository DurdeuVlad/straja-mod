#!/usr/bin/env python3
"""Unit tests for the Straja release contract (tools/ci/release_contract.py)."""

from __future__ import annotations

import hashlib
import tempfile
import unittest
import zipfile
from pathlib import Path

import release_contract as rc

PROPS = """\
# comment
mod_id=straja
mod_version=0.2.0
mod_group_id=com.dwurdy.straja
"""

MODS_TOML = """\
modLoader="javafml"
loaderVersion="[4,)"
license="LGPL-3.0-only"
[[mods]]
modId="{mod_id}"
version="{version}"
displayName="Straja Mod"
[[dependencies.{mod_id}]]
    modId="neoforge"
    type="required"
"""


def write_properties(directory: Path, text: str) -> Path:
    path = directory / "gradle.properties"
    path.write_text(text, encoding="utf-8")
    return path


def write_jar(
    directory: Path,
    name: str,
    *,
    mod_id: str = "straja",
    version: str = "0.2.0",
    mods_toml: bool = True,
    entries: tuple[str, ...] = (),
) -> Path:
    path = directory / name
    with zipfile.ZipFile(path, "w") as jar:
        jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
        if mods_toml:
            jar.writestr(
                "META-INF/neoforge.mods.toml",
                MODS_TOML.format(mod_id=mod_id, version=version),
            )
        jar.writestr("com/dwurdy/straja/Straja.class", b"\xca\xfe\xba\xbe")
        for entry in entries:
            jar.writestr(entry, b"x")
    return path


class GradlePropertiesTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def test_parses_mod_version(self):
        path = write_properties(self.dir, PROPS)
        props = rc.read_gradle_properties(path)
        self.assertEqual(rc.require_mod_version(props), "0.2.0")

    def test_missing_mod_version_fails(self):
        path = write_properties(self.dir, "mod_id=straja\n")
        props = rc.read_gradle_properties(path)
        with self.assertRaises(rc.ContractError):
            rc.require_mod_version(props)

    def test_duplicate_mod_version_fails(self):
        path = write_properties(
            self.dir, "mod_version=0.2.0\nmod_version=0.3.0\nmod_id=straja\n"
        )
        with self.assertRaises(rc.ContractError):
            rc.read_gradle_properties(path)

    def test_duplicate_any_key_fails(self):
        path = write_properties(self.dir, "mod_id=a\nmod_id=b\n")
        with self.assertRaises(rc.ContractError):
            rc.read_gradle_properties(path)

    def test_malformed_mod_version_fails(self):
        for bad in ("0.2.0-rc.1", "0.2", "v0.2.0", "0.2.0 ", "0.02.0"):
            props = {"mod_version": bad}
            with self.assertRaises(rc.ContractError, msg=bad):
                rc.require_mod_version(props)

    def test_malformed_property_line_fails(self):
        path = write_properties(self.dir, "mod_id straja\n")
        with self.assertRaises(rc.ContractError):
            rc.read_gradle_properties(path)


class TagContractTests(unittest.TestCase):
    def test_stable_tag_matches(self):
        tag = rc.validate_tag("v0.2.0", "0.2.0")
        self.assertEqual(tag.kind, "stable")
        self.assertIsNone(tag.rc)

    def test_rc_tag_matches(self):
        tag = rc.validate_tag("v0.2.0-rc.3", "0.2.0")
        self.assertEqual(tag.kind, "rc")
        self.assertEqual(tag.rc, 3)

    def test_rc_zero_rejected(self):
        with self.assertRaises(rc.ContractError):
            rc.validate_tag("v0.2.0-rc.0", "0.2.0")

    def test_stable_tag_version_mismatch_fails(self):
        with self.assertRaises(rc.ContractError):
            rc.validate_tag("v0.3.0", "0.2.0")

    def test_rc_tag_version_mismatch_fails(self):
        with self.assertRaises(rc.ContractError):
            rc.validate_tag("v0.3.0-rc.1", "0.2.0")

    def test_malformed_tags_fail(self):
        for bad in (
            "",
            "0.2.0",
            "v0.2",
            "v0.2.0-",
            "v0.2.0-rc",
            "v0.2.0-rc.",
            "v0.2.0-rc.1x",
            "v0.2.0-beta.1",
            "release-v0.2.0",
            "V0.2.0",
            "v0.2.0-rc.01",
        ):
            with self.assertRaises(rc.ContractError, msg=bad):
                rc.classify_tag(bad)


class ArtifactSelectionTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)
        self.libs = self.dir / "libs"
        self.libs.mkdir()

    def tearDown(self):
        self._tmp.cleanup()

    def test_selects_exact_distributable(self):
        jar = write_jar(self.libs, "straja-0.2.0.jar")
        self.assertEqual(rc.select_distributable(self.libs, "straja", "0.2.0"), jar)

    def test_ignores_foreign_jars(self):
        write_jar(self.libs, "envelope-0.6.2.jar", mod_id="envelope")
        jar = write_jar(self.libs, "straja-0.2.0.jar")
        self.assertEqual(rc.select_distributable(self.libs, "straja", "0.2.0"), jar)

    def test_missing_jar_fails(self):
        with self.assertRaises(rc.ContractError):
            rc.select_distributable(self.libs, "straja", "0.2.0")

    def test_stale_version_jar_fails(self):
        write_jar(self.libs, "straja-0.1.0.jar", version="0.1.0")
        with self.assertRaises(rc.ContractError):
            rc.select_distributable(self.libs, "straja", "0.2.0")

    def test_classifier_jars_rejected(self):
        for name in (
            "straja-0.2.0-sources.jar",
            "straja-0.2.0-javadoc.jar",
            "straja-0.2.0-dev.jar",
            "straja-0.2.0-test.jar",
            "straja-0.2.0-gametest.jar",
        ):
            with self.subTest(name=name):
                write_jar(self.libs, "straja-0.2.0.jar")
                write_jar(self.libs, name)
                with self.assertRaises(rc.ContractError):
                    rc.select_distributable(self.libs, "straja", "0.2.0")
                for p in self.libs.glob("*.jar"):
                    p.unlink()


class JarInspectionTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def test_valid_jar_passes(self):
        jar = write_jar(self.dir, "straja-0.2.0.jar")
        rc.inspect_jar(jar, "straja", "0.2.0")

    def test_metadata_version_mismatch_fails(self):
        jar = write_jar(self.dir, "straja-0.2.0.jar", version="0.1.0")
        with self.assertRaises(rc.ContractError):
            rc.inspect_jar(jar, "straja", "0.2.0")

    def test_metadata_mod_id_mismatch_fails(self):
        jar = write_jar(self.dir, "straja-0.2.0.jar", mod_id="straja2")
        with self.assertRaises(rc.ContractError):
            rc.inspect_jar(jar, "straja", "0.2.0")

    def test_missing_mods_toml_fails(self):
        jar = write_jar(self.dir, "straja-0.2.0.jar", mods_toml=False)
        with self.assertRaises(rc.ContractError):
            rc.inspect_jar(jar, "straja", "0.2.0")

    def test_gametest_class_entry_fails(self):
        jar = write_jar(
            self.dir,
            "straja-0.2.0.jar",
            entries=("com/dwurdy/straja/gametest/BootTest.class",),
        )
        with self.assertRaises(rc.ContractError):
            rc.inspect_jar(jar, "straja", "0.2.0")

    def test_virtual_player_test_package_is_allowed(self):
        # adapter/in/test/ ships the /straja test virtual-player harness; it is
        # production code and must not trip the GameTest/source rejection rule.
        jar = write_jar(
            self.dir,
            "straja-0.2.0.jar",
            entries=(
                "com/dwurdy/straja/adapter/in/test/VirtualPlayerGateway.class",
                "com/dwurdy/straja/adapter/in/test/TestPlayerRegistry.class",
            ),
        )
        rc.inspect_jar(jar, "straja", "0.2.0")

    def test_java_source_entry_fails(self):
        jar = write_jar(
            self.dir,
            "straja-0.2.0.jar",
            entries=("com/dwurdy/straja/Straja.java",),
        )
        with self.assertRaises(rc.ContractError):
            rc.inspect_jar(jar, "straja", "0.2.0")

    def test_digests_match_content(self):
        jar = write_jar(self.dir, "straja-0.2.0.jar")
        out = self.dir / "release"
        written = rc.write_digests(jar, out)
        data = jar.read_bytes()
        self.assertEqual(
            (out / "straja-0.2.0.jar.sha256").read_text(),
            f"{hashlib.sha256(data).hexdigest()}  straja-0.2.0.jar\n",
        )
        self.assertEqual(
            (out / "straja-0.2.0.jar.sha512").read_text(),
            f"{hashlib.sha512(data).hexdigest()}  straja-0.2.0.jar\n",
        )
        self.assertEqual(len(written), 2)


class CliTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)
        self.props = write_properties(self.dir, PROPS)

    def tearDown(self):
        self._tmp.cleanup()

    def test_version_command_without_tag(self):
        self.assertEqual(
            rc.main(["version", "--properties", str(self.props)]), 0
        )

    def test_version_command_bad_tag_fails(self):
        self.assertEqual(
            rc.main(
                ["version", "--properties", str(self.props), "--tag", "v9.9.9"]
            ),
            1,
        )

    def test_artifact_command(self):
        libs = self.dir / "libs"
        libs.mkdir()
        write_jar(libs, "straja-0.2.0.jar")
        out = self.dir / "release"
        self.assertEqual(
            rc.main(
                [
                    "artifact",
                    "--properties",
                    str(self.props),
                    "--libs-dir",
                    str(libs),
                    "--checksum-dir",
                    str(out),
                ]
            ),
            0,
        )
        self.assertTrue((out / "straja-0.2.0.jar.sha256").exists())

    def test_artifact_command_fails_closed(self):
        libs = self.dir / "libs"
        libs.mkdir()
        self.assertEqual(
            rc.main(
                ["artifact", "--properties", str(self.props), "--libs-dir", str(libs)]
            ),
            1,
        )


if __name__ == "__main__":
    unittest.main()
