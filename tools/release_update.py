#!/usr/bin/env python3
"""Build a verified OTA bundle; publish only when --publish is explicitly supplied."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile


PROJECT_ROOT = Path(__file__).resolve().parents[1]
PACKAGE_NAME = "com.family.tvguard"
UPDATE_REPO = "zzusec/tv-kids-guard"
GH_REPO = f"github.com/{UPDATE_REPO}"
APK_NAME = "tv-kids-guard.apk"
ASSET_NAMES = (APK_NAME, "update.json", "SHA256SUMS")
MAX_APK_SIZE = 50 * 1024 * 1024
MAX_METADATA_SIZE = 64 * 1024
VERSION_PATTERN = r"(?:0|[1-9][0-9]*)(?:\.(?:0|[1-9][0-9]*)){1,2}"
METADATA_KEYS = {
    "packageName", "versionCode", "versionName", "minSdk", "apkUrl",
    "size", "sha256", "notes",
}


class ReleaseError(Exception):
    """A validation or publishing failure, safe to show without a traceback."""


def run_command(args, *, env=None, sensitive=False):
    """Never invoke a shell or expose certificate/password command output."""
    try:
        result = subprocess.run(
            [str(arg) for arg in args], capture_output=True, check=False,
            env=env, timeout=120, stdin=subprocess.DEVNULL,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise ReleaseError(f"Cannot run {Path(args[0]).name}: {type(exc).__name__}") from exc
    if result.returncode:
        detail = "" if sensitive else result.stderr.decode("utf-8", errors="replace").strip()[:2000]
        raise ReleaseError(
            f"{Path(args[0]).name} failed (exit {result.returncode})"
            + (f": {detail}" if detail else "")
        )
    return result.stdout


def release_tag(version_name):
    # Numeric Android release versions only: no refs, paths, flags or URL escapes.
    if not isinstance(version_name, str) or not re.fullmatch(VERSION_PATTERN, version_name):
        raise ReleaseError("Unsafe versionName: expected numeric X.Y or X.Y.Z")
    return f"v{version_name}"


def apk_url(version_name):
    return f"https://github.com/{UPDATE_REPO}/releases/download/{release_tag(version_name)}/{APK_NAME}"


def check_apk_file(apk):
    if apk.is_symlink() or not apk.is_file() or apk.suffix != ".apk":
        raise ReleaseError("APK must be a regular, non-symlink .apk file")
    size = apk.stat().st_size
    if not 0 < size <= MAX_APK_SIZE:
        raise ReleaseError("APK must be nonempty and no larger than 50 MiB")
    return size


def output_directory(value, apk, *, allow_existing=False):
    raw = str(value)
    if (
        not raw or raw != raw.strip() or "#" in raw
        or any(ord(char) < 32 for char in raw)
        or any(part in (".", "..") for part in raw.split("/"))
    ):
        raise ReleaseError("Unsafe output path: whitespace, control characters, # or dot components")
    path = Path(raw).expanduser()
    if path.is_symlink():
        raise ReleaseError("Output directory must not be a symlink")
    path = path.resolve()
    build_outputs = PROJECT_ROOT / "app/build/outputs"
    if path == PROJECT_ROOT or path in PROJECT_ROOT.parents or apk.is_relative_to(path):
        raise ReleaseError("Output must not contain the project or input APK")
    if path.is_relative_to(PROJECT_ROOT) and not (
        path.is_relative_to(build_outputs) and path != build_outputs
    ):
        raise ReleaseError("In-project OTA output must be below app/build/outputs")
    if path.exists() and (not path.is_dir() or (not allow_existing and any(path.iterdir()))):
        raise ReleaseError("Output directory must be absent or empty; refusing to overwrite files")
    return path


def find_build_tools(sdk=None):
    if sdk is None:
        properties = PROJECT_ROOT / "local.properties"
        if properties.is_file():
            for line in properties.read_text(encoding="utf-8").splitlines():
                match = re.match(r"\s*sdk\.dir\s*=\s*(.+?)\s*$", line)
                if match:
                    # local.properties uses Java-property escaping for paths.
                    sdk = re.sub(r"\\([\\ :=])", r"\1", match.group(1))
                    break
        sdk = sdk or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise ReleaseError("Android SDK not found; use --sdk, local.properties sdk.dir or ANDROID_HOME")
    directory = Path(sdk).expanduser().resolve() / "build-tools"
    if not directory.is_dir():
        raise ReleaseError(f"Android SDK build-tools not found: {directory}")
    versions = sorted(
        (p for p in directory.iterdir() if p.is_dir() and re.fullmatch(r"[0-9]+(?:\.[0-9]+){2}", p.name)),
        key=lambda p: tuple(int(part) for part in p.name.split(".")), reverse=True,
    )
    for version in versions:
        for name in ("aapt", "aapt2"):
            aapt, signer = version / name, version / "apksigner"
            if os.access(aapt, os.X_OK) and os.access(signer, os.X_OK):
                return aapt, signer
    raise ReleaseError("Install stable Android SDK build-tools containing aapt/aapt2 and apksigner")


def stable_certificate_sha256():
    path = PROJECT_ROOT / "config/release-certificate.sha256"
    try:
        fingerprint = path.read_text(encoding="ascii").strip()
    except (OSError, UnicodeError) as exc:
        raise ReleaseError("Cannot read stable certificate fingerprint: config/release-certificate.sha256") from exc
    if not re.fullmatch(r"[0-9a-fA-F]{64}", fingerprint):
        raise ReleaseError("Invalid stable certificate fingerprint: expected 64 hexadecimal characters")
    return fingerprint.lower()


def inspect_apk(apk, sdk=None):
    check_apk_file(apk)
    aapt, apksigner = find_build_tools(sdk)
    badging = run_command([aapt, "dump", "badging", apk]).decode("utf-8", errors="replace")
    package = re.search(r"^package: (.+)$", badging, re.MULTILINE)
    minimum = re.search(r"^sdkVersion:'([0-9]+)'\s*$", badging, re.MULTILINE)
    if not package or not minimum:
        raise ReleaseError("Cannot read package/version/minSdk from the actual APK badging")
    fields = dict(re.findall(r"([A-Za-z][A-Za-z0-9_]*)='([^']*)'", package.group(1)))
    if fields.get("name") != PACKAGE_NAME:
        raise ReleaseError(f"Wrong APK package; expected {PACKAGE_NAME}")
    version = fields.get("versionName")
    release_tag(version)
    code = fields.get("versionCode", "")
    if not re.fullmatch(r"[0-9]+", code) or not 0 < int(code) <= 2100000000:
        raise ReleaseError("Invalid APK versionCode")
    if int(minimum.group(1)) < 1:
        raise ReleaseError("Invalid APK minSdk")
    verification = run_command([
        apksigner, "verify", "--verbose", "--print-certs", apk,
    ]).decode("utf-8", errors="replace")
    certificates = re.findall(
        r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$",
        verification, re.MULTILINE,
    )
    if len(certificates) != 1 or certificates[0].lower() != stable_certificate_sha256():
        raise ReleaseError("APK must have exactly one verified signer matching the stable certificate fingerprint")
    return {
        "packageName": PACKAGE_NAME, "versionCode": int(code),
        "versionName": version, "minSdk": int(minimum.group(1)),
    }


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def checksum_text(directory):
    return "".join(f"{sha256(directory / name)}  {name}\n" for name in ASSET_NAMES[:2])


def validate_bundle(directory):
    """The upload boundary accepts exactly these three files, never a glob."""
    # gh interprets '#' in a filename as an asset-label separator, even inside
    # an absolute directory path. Do not let it select a different local file.
    if "#" in str(directory) or "#" in str(directory.resolve()):
        raise ReleaseError("OTA asset paths must not contain the gh # label separator")
    if directory.is_symlink() or not directory.is_dir():
        raise ReleaseError("OTA bundle must be a regular directory")
    if {path.name for path in directory.iterdir()} != set(ASSET_NAMES):
        raise ReleaseError("OTA bundle must contain exactly tv-kids-guard.apk, update.json and SHA256SUMS")
    assets = [directory / name for name in ASSET_NAMES]
    if any(path.is_symlink() or not path.is_file() or path.stat().st_size == 0 for path in assets):
        raise ReleaseError("OTA assets must be nonempty regular files, not symlinks")
    size = check_apk_file(assets[0])
    if assets[1].stat().st_size > MAX_METADATA_SIZE:
        raise ReleaseError("update.json must be no larger than 64 KiB (including notes)")
    try:
        with assets[1].open("rb") as stream:
            encoded_metadata = stream.read(MAX_METADATA_SIZE + 1)
        if len(encoded_metadata) > MAX_METADATA_SIZE:
            raise ReleaseError("update.json must be no larger than 64 KiB (including notes)")
        metadata = json.loads(encoded_metadata.decode("utf-8"))
    except (ValueError, UnicodeError) as exc:
        raise ReleaseError("Invalid update.json") from exc
    if not isinstance(metadata, dict) or set(metadata) != METADATA_KEYS:
        raise ReleaseError("Invalid update.json fields")
    if (
        metadata["packageName"] != PACKAGE_NAME
        or type(metadata["versionCode"]) is not int or not 0 < metadata["versionCode"] <= 2100000000
        or type(metadata["minSdk"]) is not int or metadata["minSdk"] < 1
        or type(metadata["size"]) is not int or metadata["size"] != size
        or not isinstance(metadata["notes"], str)
        or metadata["apkUrl"] != apk_url(metadata["versionName"])
        or metadata["sha256"] != sha256(assets[0])
    ):
        raise ReleaseError("update.json does not match the OTA APK/contract")
    if assets[2].read_text(encoding="utf-8") != checksum_text(directory):
        raise ReleaseError("SHA256SUMS does not match the OTA assets")
    return metadata, assets


def generate_bundle(apk, out, notes="", sdk=None):
    apk = Path(apk).expanduser().absolute()
    check_apk_file(apk)
    destination = output_directory(out, apk.resolve())
    destination.parent.mkdir(parents=True, exist_ok=True)
    # Inspect the copied snapshot, not a Gradle output that may still be changing.
    with tempfile.TemporaryDirectory(prefix=".tvguard-ota-", dir=destination.parent) as temporary:
        staging = Path(temporary)
        staged_apk = staging / APK_NAME
        shutil.copyfile(apk, staged_apk)
        metadata = inspect_apk(staged_apk, sdk)
        metadata.update({
            "apkUrl": apk_url(metadata["versionName"]), "size": check_apk_file(staged_apk),
            "sha256": sha256(staged_apk), "notes": notes,
        })
        (staging / "update.json").write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
        )
        (staging / "SHA256SUMS").write_text(checksum_text(staging), encoding="utf-8")
        validate_bundle(staging)
        # Atomic installation also refuses a destination that became nonempty.
        os.replace(staging, destination)
    return destination


def verify_existing_bundle(apk, directory, sdk=None, notes=None):
    """Reuse a generated bundle without replacing files or trusting stale APK data."""
    apk = Path(apk).expanduser().absolute()
    directory = output_directory(directory, apk.resolve(), allow_existing=True)
    metadata, assets = validate_bundle(directory)
    inspected = inspect_apk(apk, sdk)
    # Recheck the bundled APK signature too, so a modified JSON/checksum file
    # cannot make an APK signed with another key eligible for publication.
    if inspect_apk(assets[0], sdk) != inspected:
        raise ReleaseError("Existing OTA bundle metadata differs from the input APK")
    if any(metadata[key] != value for key, value in inspected.items()):
        raise ReleaseError("Existing update.json metadata differs from the input APK")
    if check_apk_file(apk) != metadata["size"] or sha256(apk) != metadata["sha256"]:
        raise ReleaseError("Existing OTA bundle hash/size differs from the input APK")
    if notes is not None and notes != metadata["notes"]:
        raise ReleaseError("Existing OTA bundle notes differ; refusing to overwrite")
    return directory


def gh_json(args, env):
    try:
        return json.loads(run_command(["gh", *args], env=env))
    except (ValueError, UnicodeError) as exc:
        raise ReleaseError("gh returned invalid JSON") from exc


def verify_remote_assets(release, tag, sizes, *, draft=True):
    if not isinstance(release, dict) or release.get("tagName") != tag or release.get("isDraft") is not draft:
        raise ReleaseError("Remote release tag/draft state does not match the expected state")
    assets = release.get("assets")
    if not isinstance(assets, list) or len(assets) != len(ASSET_NAMES):
        raise ReleaseError("Remote draft does not contain exactly three assets")
    actual = {}
    for asset in assets:
        if not isinstance(asset, dict) or not isinstance(asset.get("name"), str):
            raise ReleaseError("Invalid remote asset metadata")
        name, size = asset["name"], asset.get("size")
        if name in actual or type(size) is not int:
            raise ReleaseError("Duplicate remote asset or invalid size")
        actual[name] = size
    if actual != sizes:
        raise ReleaseError("Remote asset names/sizes do not match the verified OTA bundle")


def publish_bundle(directory):
    directory = Path(directory).absolute()
    metadata, assets = validate_bundle(directory)
    tag = release_tag(metadata["versionName"])
    sizes = {path.name: path.stat().st_size for path in assets}
    env = os.environ.copy()
    env.update({"GH_HOST": "github.com", "GH_PROMPT_DISABLED": "1"})
    env.pop("GH_DEBUG", None)
    api = ["api", "--hostname", "github.com"]
    endpoint = f"repos/{UPDATE_REPO}"
    try:
        repo = gh_json([*api, endpoint], env)
    except ReleaseError as exc:
        raise ReleaseError(
            f"Updates repository {UPDATE_REPO} is unavailable; create/authenticate it first. "
            f"This tool never creates repositories. {exc}"
        ) from exc
    if not isinstance(repo, dict) or repo.get("full_name", "").lower() != UPDATE_REPO or repo.get("private") is not False:
        raise ReleaseError(f"Expected the pre-existing PUBLIC repository {UPDATE_REPO}")
    # Lists distinguish an absent tag from authentication/network failures. Never
    # treat a failed `release view` as permission to overwrite or create a tag.
    refs = gh_json([*api, f"{endpoint}/git/matching-refs/tags/{tag}"], env)
    if not isinstance(refs, list) or any(not isinstance(ref, dict) for ref in refs):
        raise ReleaseError("Invalid tag lookup response")
    if any(ref.get("ref") == f"refs/tags/{tag}" for ref in refs):
        raise ReleaseError(f"Tag {tag} already exists; refusing to overwrite")
    pages = gh_json([*api, "--paginate", "--slurp", f"{endpoint}/releases?per_page=100"], env)
    if not isinstance(pages, list) or any(
        not isinstance(page, list) or any(not isinstance(item, dict) for item in page) for page in pages
    ):
        raise ReleaseError("Invalid release lookup response")
    if any(item.get("tag_name") == tag for page in pages for item in page):
        raise ReleaseError(f"Release {tag} already exists (including drafts); refusing to overwrite")
    release_url = f"https://github.com/{UPDATE_REPO}/releases"
    try:
        run_command([
            "gh", "release", "create", tag, "--repo", GH_REPO, "--draft",
            "--title", tag, "--notes", metadata["notes"],
        ], env=env)
        # No --clobber, globs, source archive, keystore or extra notes attachment.
        validate_bundle(directory)
        run_command(["gh", "release", "upload", tag, "--repo", GH_REPO, *assets], env=env)
        remote = gh_json([
            "release", "view", tag, "--repo", GH_REPO, "--json", "tagName,isDraft,assets",
        ], env)
        verify_remote_assets(remote, tag, sizes)
    except (ReleaseError, OSError, UnicodeError) as exc:
        raise ReleaseError(
            f"Publish stopped; any created draft is retained for diagnosis at {release_url}. {exc}"
        ) from exc
    try:
        run_command(["gh", "release", "edit", tag, "--repo", GH_REPO, "--draft=false"], env=env)
        published = gh_json([
            "release", "view", tag, "--repo", GH_REPO, "--json", "tagName,isDraft,assets",
        ], env)
        verify_remote_assets(published, tag, sizes, draft=False)
    except (ReleaseError, OSError, UnicodeError) as exc:
        raise ReleaseError(
            f"Publish was not confirmed; inspect draft/published state at {release_url}. "
            f"No automatic retry, overwrite or deletion. {exc}"
        ) from exc
    return f"{release_url}/tag/{tag}"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, help="Signed release APK to inspect")
    parser.add_argument("--sdk", help="Android SDK root (otherwise local.properties/ANDROID_HOME)")
    parser.add_argument("--out", default=str(PROJECT_ROOT / "app/build/outputs/ota"),
                        help="Output directory; --publish may reuse an existing verified bundle")
    parser.add_argument("--notes", help="Notes in update.json; must match when reusing an existing bundle")
    parser.add_argument("--publish", action="store_true",
                        help=f"Publish to {UPDATE_REPO}; verify/reuse --out if already generated")
    args = parser.parse_args(argv)
    try:
        existing = Path(args.out).expanduser()
        if args.publish and existing.is_dir() and any(existing.iterdir()):
            directory = verify_existing_bundle(args.apk, args.out, args.sdk, args.notes)
            print(f"Verified existing OTA bundle (unchanged): {directory}")
        else:
            directory = generate_bundle(args.apk, args.out, args.notes or "", args.sdk)
            print(f"Generated verified OTA bundle: {directory}")
        if args.publish:
            print(f"Published OTA release: {publish_bundle(directory)}")
        return 0
    except (ReleaseError, OSError, UnicodeError) as exc:
        print(f"OTA release failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
