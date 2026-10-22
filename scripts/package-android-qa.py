#!/usr/bin/env python3
"""Package verified Android QA APKs, checksums and validation evidence for a draft release."""
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
import zipfile
from apk_delivery import configured_identity, verify_delivery

ROOT = Path(__file__).resolve().parents[1]
ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64", "universal")


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""): value.update(block)
    return value.hexdigest()


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
    if git("status", "--porcelain"):
        raise SystemExit("Commit the reviewed source before binding release artifacts to it")
    commit = git("rev-parse", "HEAD")
    if git("rev-parse", args.tag + "^{commit}") != commit:
        raise SystemExit("The release tag must resolve to the current clean source commit")
    evidence = args.evidence_dir.resolve()
    if not evidence.is_dir() or not evidence.is_relative_to(ROOT / "build"):
        raise SystemExit("Stage validation evidence in a dedicated directory under build/")
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
        apks = []
        for flavor in ("play", "foss"):
            for abi in ABIS:
                source = ROOT / f"app/build/outputs/apk/{flavor}/qa/app-{flavor}-{abi}-qa.apk"
                name = f"hearth-{identity.version_name}-{flavor}-{abi}-qa.apk"
                apks.append({"flavor": flavor, "abi": abi, "name": name, "bytes": source.stat().st_size,
                             "sha256": digest(source), "source": source})
                if abi == "arm64-v8a":
                    shutil.copyfile(source, staging / name)
                    if digest(staging / name) != apks[-1]["sha256"]: raise SystemExit("APK changed while being packaged")
        other_name = f"hearth-{identity.version_name}-other-android-abis-qa.zip"
        with zipfile.ZipFile(staging / other_name, "w", compression=zipfile.ZIP_STORED) as archive:
            others = [item for item in apks if item["abi"] != "arm64-v8a"]
            for item in others: archive.write(item["source"], item["name"])
            archive.writestr("SHA256SUMS", "".join(f'{p["sha256"]}  {p["name"]}\n' for p in others))
            archive.writestr("README.txt", "For S25/S22 Ultra use the separate ARM64 APKs.\n"
                "This archive contains Play/FOSS ARMv7, x86, x86_64 and universal QA APKs.\n"
                "Universal is for an unknown architecture. Play and FOSS update the same QA app; choose one.\n"
                "All local TTS and optional GPU plugins require ARM64. No model weights are bundled.\n")
        abi_configuration = json.loads((ROOT / "build/reports/android/abi-configuration.json").read_text())
        if set(abi_configuration["activeAbis"]) != set(ABIS) - {"universal"}:
            raise SystemExit("Restore the full all-ABI release configuration before packaging")
        manifest = {"schemaVersion": 1, "versionName": identity.version_name, "versionCode": identity.version_code,
            "tag": args.tag, "sourceCommit": commit, "applicationId": identity.package, "buildType": "qa",
            "signing": "Existing QA debug/test certificate; not production signing", "modelWeightsBundled": False,
            "androidDeviceInferenceVerified": False, "upgradeIdentity": upgrades,
            "apks": [{k: v for k, v in item.items() if k != "source"} for item in apks],
            "abiConfiguration": abi_configuration}
        validation_name = f"hearth-{identity.version_name}-validation.zip"
        files = sorted(path for path in evidence.rglob("*") if path.is_file())
        if any(path.is_symlink() or not path.resolve().is_relative_to(evidence) for path in files):
            raise SystemExit("Validation evidence must contain ordinary files inside its staging directory")
        if sum(path.stat().st_size for path in files) > 50 * 1024 * 1024 or len(files) > 1000:
            raise SystemExit("Validation evidence exceeds its archive bound")
        with zipfile.ZipFile(staging / validation_name, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            archive.writestr("BUILD-PROVENANCE.json", json.dumps(manifest, indent=2) + "\n")
            for path in files: archive.write(path, "evidence/" + path.relative_to(evidence).as_posix())
        assets = sorted(path for path in staging.iterdir() if path.is_file())
        if any(path.stat().st_size >= 2 * 1024**3 for path in assets):
            raise SystemExit("A release asset exceeds GitHub's per-file limit")
        (staging / "SHA256SUMS").write_text("".join(f"{digest(path)}  {path.name}\n" for path in assets))
        # Check generated archives before the completed release directory becomes visible.
        for path in staging.glob("*.zip"):
            with zipfile.ZipFile(path) as archive:
                if archive.testzip() is not None: raise SystemExit(f"Corrupt generated archive: {path.name}")
                if path.name == other_name:
                    for item in others:
                        value = hashlib.sha256()
                        with archive.open(item["name"]) as stream:
                            for block in iter(lambda: stream.read(1024 * 1024), b""): value.update(block)
                        if value.hexdigest() != item["sha256"]:
                            raise SystemExit(f'Archived APK changed while being packaged: {item["name"]}')
        if git("rev-parse", "HEAD") != commit or git("status", "--porcelain"):
            raise SystemExit("Source changed while the release was being packaged")
        staging.rename(destination)
    print(f"Verified QA release ready: {destination}")
    for path in sorted(destination.iterdir()): print(f"{path.name}: {path.stat().st_size:,} bytes")


if __name__ == "__main__":
    main()
