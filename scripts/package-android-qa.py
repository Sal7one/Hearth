#!/usr/bin/env python3
"""Stage individually downloadable QA APKs, checksums and bounded validation receipts."""
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
from apk_delivery import configured_identity, verify_delivery

ROOT = Path(__file__).resolve().parents[1]
ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64", "universal")
MAX_EVIDENCE_BYTES = 50 * 1024 * 1024
MAX_EVIDENCE_FILES = 1000
ARCHIVE_SUFFIXES = (".zip", ".tar", ".tar.gz", ".tgz", ".gz", ".bz2", ".xz", ".7z", ".rar", ".apk", ".aar", ".jar")


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""): value.update(block)
    return value.hexdigest()

def checked_copy(source, destination, expected_sha256=None):
    """Check published bytes after copying; a changing input never becomes a valid asset."""
    size = source.stat().st_size
    sha256 = expected_sha256 or digest(source)
    shutil.copyfile(source, destination)
    if destination.stat().st_size != size or digest(destination) != sha256:
        raise SystemExit(f"Artifact changed while being packaged: {source.name}")
    return {"name": destination.name, "bytes": size, "sha256": sha256}


def stage_apks(root, staging, asset_version):
    """Each verified ABI/flavor gets its own installable release asset, never an APK bundle ZIP."""
    apks = []
    for flavor in ("play", "foss"):
        for abi in ABIS:
            source = root / f"app/build/outputs/apk/{flavor}/qa/app-{flavor}-{abi}-qa.apk"
            name = f"hearth-{asset_version}-{flavor}-{abi}-qa.apk"
            apks.append({"flavor": flavor, "abi": abi, **checked_copy(source, staging / name)})
    return apks


def evidence_files(evidence):
    """Only bounded ordinary files: no symlinks, external paths or combined evidence archives."""
    evidence = evidence.resolve()
    entries = sorted(evidence.rglob("*"))
    if any(path.is_symlink() or not path.resolve().is_relative_to(evidence)
           or (not path.is_file() and not path.is_dir()) for path in entries):
        raise SystemExit("Validation evidence must contain ordinary files/directories inside its staging directory")
    files = [path for path in entries if path.is_file()]
    if len(files) > MAX_EVIDENCE_FILES or sum(path.stat().st_size for path in files) > MAX_EVIDENCE_BYTES:
        raise SystemExit("Validation evidence exceeds its 1000-file / 50-MiB bound")
    for path in files:
        with path.open("rb") as stream:
            magic = stream.read(512)
        if path.name.lower().endswith(ARCHIVE_SUFFIXES) or magic.startswith((
                b"PK\x03\x04", b"PK\x05\x06", b"PK\x07\x08", b"\x1f\x8b", b"7z\xbc\xaf\x27\x1c",
                b"Rar!\x1a\x07", b"BZh", b"\xfd7zXZ\x00")) or magic[257:262] == b"ustar":
            raise SystemExit(f"Evidence archives are not release assets; stage individual JSON/log files: {path.name}")
    return files


def stage_evidence(evidence, staging):
    evidence = evidence.resolve()
    files = evidence_files(evidence)
    result = []
    names = set()
    for path in files:
        relative = path.relative_to(evidence).as_posix()
        name = "validation-" + re.sub(r"[^A-Za-z0-9._-]", "-", relative.replace("/", "--"))
        if len(name) > 200:
            suffix = Path(name).suffix[:16]
            name = name[:160] + "-" + hashlib.sha256(relative.encode()).hexdigest()[:16] + suffix
        if name.casefold() in names or (staging / name).exists():
            raise SystemExit(f"Validation evidence names collide after flattening: {relative}")
        names.add(name.casefold())
        result.append({"evidencePath": relative, **checked_copy(path, staging / name)})
    return result


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--evidence-dir", type=Path, required=True)
    args = parser.parse_args()
    identity = configured_identity((ROOT / "app/build.gradle.kts").read_text(), "qa")
    if not re.fullmatch(r"v" + re.escape(identity.version_name) + r"-rc\.[1-9]\d*", args.tag):
        parser.error("Tag must match the configured Android version and an RC number")
    # Keep candidate downloads distinguishable on a phone even when Android's
    # versionName remains stable across QA builds. The install identity is unchanged.
    asset_version = args.tag.removeprefix("v")
    if git("status", "--porcelain"):
        raise SystemExit("Commit the reviewed source before binding release artifacts to it")
    commit = git("rev-parse", "HEAD")
    if git("rev-parse", args.tag + "^{commit}") != commit:
        raise SystemExit("The release tag must resolve to the current clean source commit")
    evidence = args.evidence_dir.resolve()
    if not evidence.is_dir() or not evidence.is_relative_to(ROOT / "build"):
        raise SystemExit("Stage validation evidence in a dedicated directory under build/")
    evidence_files(evidence)  # Reject unsafe/archived evidence before opening the verification log.
    destination = ROOT / "build/releases" / args.tag
    if destination.exists(): raise SystemExit(f"Release output already exists: {destination}")
    destination.parent.mkdir(parents=True, exist_ok=True)

    # Recheck the actual bytes at the packaging boundary, including signatures,
    # current identity, ABI, source/runtime pins, exports, GPU linkage and FOSS permissions.
    with (evidence / "apk-verification.log").open("w") as output:
        subprocess.run([sys.executable, str(ROOT / "scripts/verify-release.py")], cwd=ROOT,
                       stdout=output, stderr=subprocess.STDOUT, check=True)

    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        sdk = next(line.split("=", 1)[1] for line in (ROOT / "local.properties").read_text().splitlines()
                   if line.startswith("sdk.dir="))
    aapt = sorted((Path(sdk) / "build-tools").glob("*/aapt2"))[-1]
    upgrades = json.loads((evidence / "qa-upgrade-identity.json").read_text())
    for flavor in ("play", "foss"):
        apk = ROOT / f"app/build/outputs/apk/{flavor}/qa/app-{flavor}-arm64-v8a-qa.apk"
        signers = verify_delivery(apk, aapt, aapt.with_name("apksigner"), identity)
        record = upgrades[flavor]
        if not (record["package"] == identity.package and record["versionCode"] == identity.version_code
                and record["versionName"] == identity.version_name and record["upgradeIdentityVerified"] is True
                and set(record["signerSha256"]) == set(signers)
                and record["previousVersionCode"] < identity.version_code):
            raise SystemExit(f"{flavor}: current APK does not match the verified upgrade identity receipt")

    with tempfile.TemporaryDirectory(prefix="android-qa-", dir=destination.parent) as temporary:
        staging = Path(temporary)
        apks = stage_apks(ROOT, staging, asset_version)
        abi_configuration = json.loads((ROOT / "build/reports/android/abi-configuration.json").read_text())
        if set(abi_configuration["activeAbis"]) != set(ABIS) - {"universal"}:
            raise SystemExit("Restore the full all-ABI release configuration before packaging")
        manifest = {"schemaVersion": 2, "assetLayout": "individual", "versionName": identity.version_name, "versionCode": identity.version_code,
            "tag": args.tag, "sourceCommit": commit, "applicationId": identity.package, "buildType": "qa",
            "signing": "Existing QA debug/test certificate; not production signing", "modelWeightsBundled": False,
            "androidDeviceInferenceVerified": False, "upgradeIdentity": upgrades,
            "apks": apks,
            "abiConfiguration": abi_configuration}
        aria_source = ROOT / 'build/aria2-corresponding-source.tar.gz'
        if not aria_source.is_file(): raise SystemExit('Build the aria2 corresponding-source archive before APK delivery')
        # This is corresponding source for a bundled GPL downloader, not a combined release download.
        manifest["correspondingSource"] = checked_copy(aria_source, staging / 'aria2-corresponding-source.tar.gz')
        receipts = stage_evidence(evidence, staging)
        (staging / "BUILD-PROVENANCE.json").write_text(json.dumps(manifest, indent=2) + "\n")
        (staging / "VALIDATION-RECEIPT.json").write_text(json.dumps({
            "schemaVersion": 1, "assetLayout": "individual", "tag": args.tag, "sourceCommit": commit,
            "evidenceBytes": sum(item["bytes"] for item in receipts), "evidenceFiles": receipts,
            "androidDeviceInferenceVerified": False,
        }, indent=2) + "\n")
        assets = sorted(path for path in staging.iterdir() if path.is_file())
        if any(path.stat().st_size >= 2 * 1024**3 for path in assets):
            raise SystemExit("A release asset exceeds GitHub's per-file limit")
        (staging / "SHA256SUMS").write_text("".join(f"{digest(path)}  {path.name}\n" for path in assets))
        if git("rev-parse", "HEAD") != commit or git("status", "--porcelain"):
            raise SystemExit("Source changed while the release was being packaged")
        staging.rename(destination)
    print(f"Verified QA release ready: {destination}")
    for path in sorted(destination.iterdir()): print(f"{path.name}: {path.stat().st_size:,} bytes")


if __name__ == "__main__":
    main()
