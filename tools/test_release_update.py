#!/usr/bin/env python3
"""Focused tests with temporary public fingerprints and mocked SDK/GitHub calls."""

import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


# Also works under python -I without adding the tools directory to sys.path.
SPEC = importlib.util.spec_from_file_location("release_update", Path(__file__).with_name("release_update.py"))
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)

CERTIFICATE_HASH = "0123456789abcdef" * 4
APK_BYTES = b"test APK snapshot"
BADGING = (
    "package: name='com.family.tvguard' versionCode='14' versionName='2.2.0' "
    "platformBuildVersionCode='35'\nsdkVersion:'21'\ntargetSdkVersion:'33'\n"
)


class ReleaseUpdateTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.project = self.root / "project"
        (self.project / "config").mkdir(parents=True)
        self.certificate_file = self.project / "config/release-certificate.sha256"
        self.certificate_file.write_text(CERTIFICATE_HASH + "\n", encoding="ascii")
        self.apk = self.project / "app-release.apk"
        self.apk.write_bytes(APK_BYTES)
        self.out = self.root / "ota"
        self.commands = []
        self.badging = BADGING
        self.signer_hash = CERTIFICATE_HASH
        self.reject_signature = False
        self.addCleanup(patch.stopall)
        patch.object(release, "PROJECT_ROOT", self.project).start()
        patch.object(release.subprocess, "run", side_effect=AssertionError("Tests must not invoke external tools")).start()
        self.find_build_tools = release.find_build_tools
        patch.object(release, "find_build_tools", return_value=(Path("/sdk/aapt"), Path("/sdk/apksigner"))).start()
        self.command = patch.object(release, "run_command", side_effect=self.sdk_command).start()

    def sdk_command(self, args, **kwargs):
        self.commands.append((args, kwargs))
        if args[0] == Path("/sdk/aapt") and args[1:3] == ["dump", "badging"]:
            return self.badging.encode()
        if args[0] == Path("/sdk/apksigner") and args[1] == "verify":
            if self.reject_signature:
                raise release.ReleaseError("apksigner failed (exit 1)")
            return f"Verifies\nSigner #1 certificate SHA-256 digest: {self.signer_hash}\n".encode()
        self.fail(f"Unexpected command: {args}")

    def bundle(self):
        return release.generate_bundle(self.apk, self.out, "修复设置认证与 OTA 更新", "/sdk")

    def github(self, *, refs=None, pages=None, remote=None, fail_upload=False, fail_edit=False,
               keep_draft=False, missing_repo=False, fail_lookup=False):
        published = False

        def fake(args, **kwargs):
            nonlocal published
            if args[0] != "gh":
                return self.sdk_command(args, **kwargs)
            self.commands.append((args, kwargs))
            self.assertEqual(args[0], "gh")
            self.assertEqual(kwargs["env"]["GH_HOST"], "github.com")
            self.assertNotIn("GH_DEBUG", kwargs["env"])
            if args[1] == "api":
                endpoint = args[-1]
                self.assertIn(f"repos/{release.UPDATE_REPO}", endpoint)
                if "/git/matching-refs/" in endpoint:
                    if fail_lookup:
                        raise release.ReleaseError("gh failed (exit 1): HTTP 503")
                    return json.dumps(refs if refs is not None else []).encode()
                if "/releases?" in endpoint:
                    return json.dumps(pages if pages is not None else [[]]).encode()
                if missing_repo:
                    raise release.ReleaseError("gh failed (exit 1): HTTP 404")
                return json.dumps({"full_name": release.UPDATE_REPO, "private": False}).encode()
            operation = args[2]
            self.assertEqual(args[args.index("--repo") + 1], release.GH_REPO)
            if operation == "upload" and fail_upload:
                raise release.ReleaseError("gh upload failed (exit 1)")
            if operation == "edit":
                if fail_edit:
                    raise release.ReleaseError("gh edit failed (exit 1)")
                published = not keep_draft
            if operation == "view":
                if remote is not None:
                    return json.dumps(remote).encode()
                return json.dumps({
                    "tagName": "v2.2.0", "isDraft": not published,
                    "assets": [{"name": name, "size": (self.out / name).stat().st_size} for name in release.ASSET_NAMES],
                }).encode()
            return b""
        self.command.side_effect = fake

    def operations(self):
        return [args[2] for args, _ in self.commands if args[:2] == ["gh", "release"]]

    def test_metadata_from_actual_apk_and_stable_certificate(self):
        directory = self.bundle()
        metadata, assets = release.validate_bundle(directory)
        self.assertEqual(metadata, {
            "packageName": "com.family.tvguard", "versionCode": 14,
            "versionName": "2.2.0", "minSdk": 21,
            "apkUrl": "https://github.com/zzusec/tv-kids-guard/releases/download/v2.2.0/tv-kids-guard.apk",
            "size": len(APK_BYTES), "sha256": hashlib.sha256(APK_BYTES).hexdigest(),
            "notes": "修复设置认证与 OTA 更新",
        })
        self.assertEqual({path.name for path in assets}, set(release.ASSET_NAMES))
        checksums = (directory / "SHA256SUMS").read_text()
        self.assertEqual(checksums, "".join(
            f"{hashlib.sha256((directory / name).read_bytes()).hexdigest()}  {name}\n"
            for name in ("tv-kids-guard.apk", "update.json")
        ))
        self.assertFalse(any(args[0] == "gh" for args, _ in self.commands))
        self.assertFalse(any(self.out.parent.glob(".tvguard-ota-*")))

    def test_public_certificate_fingerprint_default_and_normalization(self):
        with patch.object(release, "PROJECT_ROOT", Path(__file__).resolve().parents[1]):
            self.assertEqual(
                release.stable_certificate_sha256(),
                "3c5aca1a0ac6fb34630df37bd5a1073d5cfa43f2c695157e1a752adda6507b24",
            )
        self.assertEqual(release.stable_certificate_sha256(), CERTIFICATE_HASH)
        self.certificate_file.write_text(CERTIFICATE_HASH.upper() + "\n", encoding="ascii")
        self.assertEqual(release.stable_certificate_sha256(), CERTIFICATE_HASH)
        self.bundle()
        self.assertFalse((self.project / "app/tvguard.keystore").exists())
        self.assertEqual([args[0] for args, _ in self.commands], [Path("/sdk/aapt"), Path("/sdk/apksigner")])

    def test_reject_missing_or_invalid_public_certificate_fingerprint(self):
        self.certificate_file.unlink()
        with self.assertRaisesRegex(release.ReleaseError, "Cannot read stable certificate fingerprint"):
            self.bundle()
        self.assertFalse(self.out.exists())
        invalid = (
            b"", b"a" * 63, b"a" * 65, b"g" * 64,
            ("SHA256: " + CERTIFICATE_HASH).encode("ascii"),
            ":".join(["ab"] * 32).encode("ascii"),
            (CERTIFICATE_HASH + "\n" + CERTIFICATE_HASH).encode("ascii"),
            ("a" * 63 + "\x00").encode("ascii"), b"\xff" * 64,
        )
        for fingerprint in invalid:
            with self.subTest(fingerprint=fingerprint):
                self.certificate_file.write_bytes(fingerprint)
                with self.assertRaisesRegex(release.ReleaseError, "certificate fingerprint"):
                    self.bundle()
                self.assertFalse(self.out.exists())
                self.assertFalse(any(self.root.glob(".tvguard-ota-*")))
        self.assertFalse(any(args[0] == "gh" for args, _ in self.commands))

    def test_reject_wrong_package_signature_and_unsafe_version(self):
        cases = (
            (BADGING.replace("com.family.tvguard", "com.other.app"), CERTIFICATE_HASH, False),
            (BADGING.replace("2.2.0", "../../main"), CERTIFICATE_HASH, False),
            (BADGING.replace("2.2.0", "--draft"), CERTIFICATE_HASH, False),
            (BADGING, "f" * 64, False),
            (BADGING, CERTIFICATE_HASH, True),
        )
        for badging, signer_hash, unsigned in cases:
            with self.subTest(badging=badging, unsigned=unsigned, signer_hash=signer_hash):
                self.badging, self.signer_hash, self.reject_signature = badging, signer_hash, unsigned
                with self.assertRaises(release.ReleaseError):
                    self.bundle()
                self.assertFalse(self.out.exists())
                self.assertFalse(any(self.root.glob(".tvguard-ota-*")))

    def test_reject_oversize_unsafe_output_and_overwrite(self):
        with self.apk.open("wb") as stream:
            stream.truncate(release.MAX_APK_SIZE + 1)
        with self.assertRaisesRegex(release.ReleaseError, "50 MiB"):
            self.bundle()
        self.apk.write_bytes(APK_BYTES)
        link = self.root / "linked"
        link.symlink_to(self.project, target_is_directory=True)
        for output in (self.project, self.root, self.project / "app", link, str(self.root) + "/../other", self.root / "ota#label"):
            with self.subTest(output=output), self.assertRaises(release.ReleaseError):
                release.generate_bundle(self.apk, output)
        self.bundle()
        before = (self.out / "update.json").read_bytes()
        with self.assertRaisesRegex(release.ReleaseError, "refusing to overwrite"):
            self.bundle()
        self.assertEqual((self.out / "update.json").read_bytes(), before)
        metadata = json.loads(before)
        metadata["notes"] = ""
        overhead = len((json.dumps(metadata, ensure_ascii=False, indent=2) + "\n").encode())
        notes = "n" * (release.MAX_METADATA_SIZE - overhead)
        boundary = self.root / "metadata-limit"
        release.generate_bundle(self.apk, boundary, notes)
        self.assertEqual((boundary / "update.json").stat().st_size, release.MAX_METADATA_SIZE)
        release.validate_bundle(boundary)
        with self.assertRaisesRegex(release.ReleaseError, "64 KiB"):
            release.generate_bundle(self.apk, self.root / "metadata-too-large", notes + "界")
        self.assertFalse((self.root / "metadata-too-large").exists())
        (boundary / "update.json").write_bytes(b" " * (release.MAX_METADATA_SIZE + 1))
        with self.assertRaisesRegex(release.ReleaseError, "64 KiB"):
            release.validate_bundle(boundary)

    def test_whitelist_and_integrity_block_upload(self):
        self.bundle()
        self.github()
        labeled = self.root / "ota#label"
        self.out.rename(labeled)
        with self.assertRaisesRegex(release.ReleaseError, "label separator"):
            release.publish_bundle(labeled)
        labeled.rename(self.out)
        for name in ("source.zip", "tvguard.keystore", "token.txt"):
            with self.subTest(extra=name):
                extra = self.out / name
                extra.write_text("must never be uploaded")
                with self.assertRaisesRegex(release.ReleaseError, "exactly"):
                    release.publish_bundle(self.out)
                extra.unlink()
        apk = self.out / release.APK_NAME
        apk.unlink()
        apk.symlink_to(self.apk)
        with self.assertRaisesRegex(release.ReleaseError, "symlinks"):
            release.publish_bundle(self.out)
        apk.unlink()
        apk.write_bytes(b"tampered")
        with self.assertRaisesRegex(release.ReleaseError, "contract"):
            release.publish_bundle(self.out)
        self.assertFalse(any(args[0] == "gh" for args, _ in self.commands))

    def test_publish_exact_three_assets_after_draft_verification(self):
        self.bundle()
        original = {name: (self.out / name).read_bytes() for name in release.ASSET_NAMES}
        self.github()
        with patch.object(release, "generate_bundle", side_effect=AssertionError("must not overwrite")):
            with patch("sys.stdout", new_callable=io.StringIO) as output:
                result = release.main([
                    "--apk", str(self.apk), "--out", str(self.out), "--publish",
                ])
        self.assertEqual(result, 0)
        self.assertIn("Verified existing OTA bundle (unchanged)", output.getvalue())
        self.assertIn("https://github.com/zzusec/tv-kids-guard/releases/tag/v2.2.0", output.getvalue())
        self.assertEqual(original, {name: (self.out / name).read_bytes() for name in release.ASSET_NAMES})
        self.assertEqual(self.operations(), ["create", "upload", "view", "edit", "view"])
        create = next(args for args, _ in self.commands if args[:3] == ["gh", "release", "create"])
        self.assertIn("--draft", create)
        upload = next(args for args, _ in self.commands if args[:3] == ["gh", "release", "upload"])
        self.assertEqual(upload[-3:], [self.out / name for name in release.ASSET_NAMES])
        self.assertNotIn("--clobber", upload)
        edit = next(args for args, _ in self.commands if args[:3] == ["gh", "release", "edit"])
        self.assertIn("--draft=false", edit)
        cases = (
            (APK_BYTES + b"changed", BADGING, CERTIFICATE_HASH, None, "hash/size"),
            (APK_BYTES, BADGING.replace("versionCode='14'", "versionCode='15'"), CERTIFICATE_HASH, None, "metadata"),
            (APK_BYTES, BADGING, "f" * 64, None, "stable certificate fingerprint"),
            (APK_BYTES, BADGING, CERTIFICATE_HASH, "different notes", "notes differ"),
        )
        for content, badging, signer, notes, message in cases:
            with self.subTest(message=message):
                self.apk.write_bytes(content)
                self.badging, self.signer_hash = badging, signer
                self.commands.clear()
                arguments = ["--apk", str(self.apk), "--out", str(self.out), "--publish"]
                if notes is not None:
                    arguments.extend(["--notes", notes])
                with patch("sys.stderr", new_callable=io.StringIO) as errors:
                    result = release.main(arguments)
                self.assertEqual(result, 1)
                self.assertIn(message, errors.getvalue())
                self.assertEqual(self.operations(), [])
                self.assertEqual(original, {name: (self.out / name).read_bytes() for name in release.ASSET_NAMES})

    def test_publish_errors_keep_draft_and_never_report_success(self):
        self.bundle()
        variants = (
            ({"fail_upload": True}, "retained", ["create", "upload"]),
            ({"remote": {"tagName": "v2.2.0", "isDraft": True, "assets": []}}, "retained", ["create", "upload", "view"]),
            ({"remote": {"tagName": "v2.2.0", "isDraft": True, "assets": [
                {"name": name, "size": 1} for name in release.ASSET_NAMES
            ]}}, "names/sizes", ["create", "upload", "view"]),
            ({"fail_edit": True}, "not confirmed", ["create", "upload", "view", "edit"]),
            ({"keep_draft": True}, "not confirmed", ["create", "upload", "view", "edit", "view"]),
        )
        for options, message, operations in variants:
            with self.subTest(options=options):
                self.commands.clear()
                self.github(**options)
                with self.assertRaisesRegex(release.ReleaseError, message):
                    release.publish_bundle(self.out)
                self.assertEqual(self.operations(), operations)

    def test_missing_repo_or_existing_tag_or_draft_is_not_overwritten(self):
        self.bundle()
        cases = (
            ({"missing_repo": True}, "never creates repositories"),
            ({"fail_lookup": True}, "HTTP 503"),
            ({"refs": [{"ref": "refs/tags/v2.2.0"}]}, "already exists"),
            ({"pages": [[{"tag_name": "v2.2.0", "draft": True}]]}, "already exists"),
        )
        for options, message in cases:
            with self.subTest(options=options):
                self.commands.clear()
                self.github(**options)
                with self.assertRaisesRegex(release.ReleaseError, message):
                    release.publish_bundle(self.out)
                self.assertEqual(self.operations(), [])

    def test_sdk_discovery_explicit_local_properties_and_environment(self):
        # Exercise discovery rather than the SDK mock used by other tests.
        sdk = self.root / "android sdk"
        for version in ("9.0.0", "35.0.0", "36.0.0-rc1"):
            tools = sdk / "build-tools" / version
            tools.mkdir(parents=True)
            for name in ("aapt2", "apksigner"):
                executable = tools / name
                executable.write_text("not executed")
                executable.chmod(0o755)
        expected = (sdk / "build-tools/35.0.0/aapt2", sdk / "build-tools/35.0.0/apksigner")
        self.assertEqual(self.find_build_tools(sdk), expected)
        properties = self.project / "local.properties"
        properties.write_text("sdk.dir=" + str(sdk).replace(" ", "\\ ") + "\n")
        self.assertEqual(self.find_build_tools(), expected)
        properties.unlink()
        with patch.dict("os.environ", {"ANDROID_HOME": str(sdk)}):
            self.assertEqual(self.find_build_tools(), expected)


if __name__ == "__main__":
    unittest.main()
