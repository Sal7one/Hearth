"""APK identity and signature checks shared by the delivery gate and its tests."""
from dataclasses import dataclass
from pathlib import Path
import re
import subprocess


@dataclass(frozen=True)
class ApkIdentity:
    package: str
    version_code: int
    version_name: str


def configured_identity(build_script: str, build_type: str) -> ApkIdentity:
    if build_type not in ("qa", "release"):
        raise ValueError(f"Unsupported delivery build type: {build_type}")
    def single(pattern, label):
        values = re.findall(pattern, build_script)
        if len(values) != 1:
            raise ValueError(f"Expected one literal {label} in app/build.gradle.kts")
        return values[0]
    package = single(r'\bapplicationId\s*=\s*"([^"]+)"', "applicationId")
    version_code = int(single(r'\bversionCode\s*=\s*(\d+)\b', "versionCode"))
    version_name = single(r'\bversionName\s*=\s*"([^"]+)"', "versionName")
    return ApkIdentity(package + (".qa" if build_type == "qa" else ""), version_code, version_name)


def parse_identity(badging: str) -> ApkIdentity:
    packages = re.findall(r"^package: (.+)$", badging, re.MULTILINE)
    if len(packages) != 1:
        raise ValueError("aapt must report exactly one package identity")
    fields = dict(re.findall(r"(\w+)='([^']*)'", packages[0]))
    try:
        identity = ApkIdentity(fields["name"], int(fields["versionCode"]), fields["versionName"])
    except (KeyError, ValueError) as error:
        raise ValueError("aapt package identity/version is missing or malformed") from error
    if not identity.package or identity.version_code <= 0 or not identity.version_name:
        raise ValueError("aapt package identity/version is empty or invalid")
    return identity


def signature_verdict(returncode: int, output: str, allow_unsigned_for_signing: bool = False):
    """Return signer fingerprints, or None only for an explicitly allowed unsigned input."""
    if returncode:
        # Do not let the internal preparation option excuse a corrupt signature.
        errors = re.findall(r"^ERROR: (.+)$", output, re.MULTILINE)
        if allow_unsigned_for_signing and errors == ["Missing META-INF/MANIFEST.MF"]:
            return None
        detail = "\n".join(line for line in output.splitlines() if line.startswith(("ERROR:", "DOES NOT VERIFY")))
        raise ValueError("APK signature verification failed; this APK cannot be delivered for installation. "
                         + (detail or output.strip()))
    # Build-tools 37 names unrotated signers by signature scheme; older tools
    # use numbered signers. Match certificate records only, never a source
    # stamp or public-key digest. A successful verifier exit is still required.
    signer = r"(?:Signer #[1-9]\d*|V(?:1|2|3\.0) Signer(?: #[1-9]\d*)?:)"
    certificates = frozenset(re.findall(
        rf"^{signer} certificate SHA-256 digest: ([0-9a-fA-F]{{64}})$", output, re.MULTILINE))
    if not certificates:
        raise ValueError("apksigner did not report a verified signer certificate. Verifier output:\n"
                         + output.strip()[:2000])
    return frozenset(value.lower() for value in certificates)


def verify_delivery(apk: Path, aapt: Path, apksigner: Path, expected: ApkIdentity,
                    allow_unsigned_for_signing: bool = False, run=subprocess.run):
    badging = run([str(aapt), "dump", "badging", str(apk)], text=True, capture_output=True)
    if badging.returncode:
        raise ValueError(f"Cannot read APK identity: {badging.stderr.strip()}")
    actual = parse_identity(badging.stdout)
    if actual != expected:
        raise ValueError(f"{apk.name}: expected {expected}, found {actual}; rebuild the requested variant/version")
    signature = run([str(apksigner), "verify", "--verbose", "--print-certs", str(apk)], text=True, capture_output=True)
    return signature_verdict(signature.returncode, signature.stdout + "\n" + signature.stderr,
                             allow_unsigned_for_signing)
