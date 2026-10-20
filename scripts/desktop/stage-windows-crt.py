#!/usr/bin/env python3
"""Stage only signed, release x64 CRT files from an installed Visual Studio.

Run after build-native.py on Windows. No DLL is taken from PATH/System32 and no
redistributable installer or model is downloaded. The original JNI source-set
receipt is retained; staged CRT bytes get their own provenance and file hashes.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import struct
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
DLLS = ("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll", "msvcp140_1.dll")
SYSTEM_DLLS = {"kernel32.dll", "kernelbase.dll", "ntdll.dll", "advapi32.dll",
              "user32.dll", "ole32.dll", "oleaut32.dll", "combase.dll",
              "rpcrt4.dll", "bcrypt.dll", "ucrtbase.dll", "msvcp_win.dll",
              "ws2_32.dll", "shlwapi.dll", "shell32.dll"}
NOTICE_NAME = "MICROSOFT-VC-RUNTIME-NOTICE.txt"


def sha256(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def pe_imports(data):
    """Return AMD64 PE normal/delay imports, rejecting truncated/invalid images."""
    def unpack(fmt, offset):
        size = struct.calcsize(fmt)
        if offset < 0 or offset + size > len(data):
            raise ValueError("Truncated PE image")
        return struct.unpack_from(fmt, data, offset)

    if data[:2] != b"MZ":
        raise ValueError("Not a PE image")
    pe, = unpack("<I", 0x3c)
    if data[pe:pe+4] != b"PE\0\0":
        raise ValueError("Invalid PE signature")
    machine, sections = unpack("<HH", pe + 4)
    optional_size, = unpack("<H", pe + 20)
    optional = pe + 24
    magic, = unpack("<H", optional)
    if machine != 0x8664 or magic != 0x20b or optional_size < 224 or sections > 96:
        raise ValueError("Expected an x64 PE32+ release DLL")
    directories, = unpack("<I", optional + 108)
    image_base, = unpack("<Q", optional + 24)
    layout = []
    for index in range(sections):
        size, rva, raw_size, raw_offset = unpack("<IIII", optional + optional_size + index * 40 + 8)
        layout.append((rva, max(size, raw_size), raw_offset, raw_size))

    def offset(rva, length=1):
        for start, size, raw, raw_size in layout:
            if start <= rva and rva + length <= start + size:
                position = raw + rva - start
                if rva + length > start + raw_size or position + length > len(data):
                    break
                return position
        raise ValueError("PE import points outside file data")

    def name(rva):
        start = offset(rva)
        end = data.find(b"\0", start, min(start + 260, len(data)))
        if end < 0:
            raise ValueError("Invalid PE import name")
        result = data[start:end].decode("ascii").lower()
        if not re.fullmatch(r"[a-z0-9_.-]+\.dll", result):
            raise ValueError("Unsafe PE import name")
        return result

    imports = set()
    for directory, record_size in ((1, 20), (13, 32)):
        if directories <= directory:
            continue
        rva, size = unpack("<II", optional + 112 + directory * 8)
        if not rva:
            continue
        if size < record_size or size > 1024 * 1024:
            raise ValueError("Invalid PE import table size")
        for index in range(min(size // record_size, 4096)):
            record = unpack("<" + "I" * (record_size // 4), offset(rva + index * record_size, record_size))
            if not any(record):
                break
            name_rva = record[3] if directory == 1 else record[1]
            if directory == 13 and not record[0] & 1:
                name_rva -= image_base
            imports.add(name(name_rva))
        else:
            raise ValueError("Unterminated PE import table")
    return sorted(imports)


def check_import_closure(imports):
    unsupported = [name for name in imports if name not in DLLS and name not in SYSTEM_DLLS
                   and not name.startswith(("api-ms-win-", "ext-ms-win-"))]
    if unsupported:
        raise ValueError("Unbundled non-system DLL dependencies: " + ", ".join(unsupported))


def redist_candidates(installation):
    base = installation / "VC" / "Redist" / "MSVC"
    if not base.is_dir():
        return []
    candidates = []
    for version in base.iterdir():
        if not version.is_dir() or not re.fullmatch(r"14\.\d+(?:\.\d+){0,2}", version.name):
            continue
        for family in ("Microsoft.VC143.CRT", "Microsoft.VC145.CRT"):
            candidate = version / "x64" / family
            if candidate.is_dir() and all((candidate / name).is_file() for name in DLLS):
                # Resolve junctions/symlinks before trusting the approved Redist location.
                candidate.resolve().relative_to(base.resolve())
                candidates.append(candidate)
    return sorted(candidates, key=lambda item: tuple(map(int, item.parents[1].name.split("."))), reverse=True)


def discover():
    installer = Path(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")) / "Microsoft Visual Studio" / "Installer" / "vswhere.exe"
    if not installer.is_file():
        raise ValueError("Visual Studio Installer vswhere.exe is required")
    output = subprocess.check_output([str(installer), "-all", "-products", "*", "-requires",
                                      "Microsoft.VisualStudio.Component.VC.Tools.x86.x64", "-format", "json", "-utf8"])
    installations = json.loads(output.decode("utf-8-sig"))
    for item in sorted(installations, key=lambda value: value.get("installationVersion", ""), reverse=True):
        if not item.get("isComplete") or not item.get("isLaunchable") or item.get("isPrerelease"):
            continue
        installation = Path(item["installationPath"]).resolve()
        candidates = redist_candidates(installation)
        if candidates:
            return installation, candidates[0], item
    raise ValueError("No complete release Visual Studio installation has all four x64 VC143/VC145 CRT redistributables")


def signatures(paths):
    # Pass filenames through JSON stdin, never interpolated into PowerShell source.
    command = r"""
$ErrorActionPreference = 'Stop'
$paths = [Console]::In.ReadToEnd() | ConvertFrom-Json
$results = @(foreach ($path in $paths) {
  $signature = Get-AuthenticodeSignature -LiteralPath $path
  $certificate = $signature.SignerCertificate
  if ($signature.Status -ne 'Valid' -or $null -eq $certificate -or
      $certificate.GetNameInfo([System.Security.Cryptography.X509Certificates.X509NameType]::SimpleName, $false) -ne 'Microsoft Corporation') {
    throw "Expected valid Microsoft Authenticode signature: $path ($($signature.Status))"
  }
  [pscustomobject]@{name=[IO.Path]::GetFileName($path).ToLowerInvariant();
    publisher=$certificate.Subject; thumbprint=$certificate.Thumbprint;
    version=(Get-Item -LiteralPath $path).VersionInfo.FileVersion; status='Valid'}
})
ConvertTo-Json -InputObject $results -Depth 4 -Compress
"""
    output = subprocess.check_output(["powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", command],
                                     input=json.dumps([str(path) for path in paths]).encode("utf-8"))
    return json.loads(output.decode("utf-8-sig"))


def stage(bundle):
    manifest_path = bundle / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("platform") != "windows-x64" or manifest.get("schemaVersion") != 1:
        raise ValueError("Expected a built windows-x64 native manifest")
    ort = bundle / "onnxruntime.dll"
    if manifest.get("files", {}).get(ort.name) != sha256(ort):
        raise ValueError("ONNX Runtime does not match the original native manifest")
    installation, source, info = discover()
    sources = [source / name for name in DLLS]
    if any(path.resolve().parent != source.resolve() for path in sources):
        raise ValueError("CRT files must remain inside the approved Visual Studio Redist directory")
    before = {path.name: sha256(path) for path in sources}
    signed = signatures(sources)
    if {entry["name"] for entry in signed} != set(DLLS):
        raise ValueError("Incomplete Microsoft signature verification")
    imports = {}
    for path in [ort, *sources]:
        imports[path.name] = pe_imports(path.read_bytes())
        check_import_closure(imports[path.name])
    major = int(info["installationVersion"].split(".")[0])
    year = {17: "2022", 18: "2026"}.get(major)
    if year is None:
        raise ValueError("Unsupported Visual Studio redistribution terms version")
    redistribution = f"https://learn.microsoft.com/en-us/visualstudio/releases/{year}/redistribution"
    # Copy a locally installed license/redist notice when present; do not guess DLL licenses.
    legal = []
    for parent in (installation, installation / "Licenses", source.parents[1], source.parents[2]):
        if parent.is_dir():
            legal.extend(path for path in parent.iterdir() if path.is_file() and
                         re.fullmatch(r"(?:licen[cs]e|redist)(?:[_.-][a-z0-9_-]+)?\.(?:txt|rtf|html)", path.name, re.I)
                         and path.stat().st_size <= 2 * 1024 * 1024)
    notice = ("Microsoft Visual C++ Runtime\nCopyright Microsoft Corporation. All rights reserved.\n\n"
              "These unmodified release x64 DLLs are redistributed app-locally from the\n"
              "licensed Visual Studio installation's VC/Redist directory. They remain\n"
              "Microsoft software and are not covered by Hearth's software license.\n"
              "Redistribution is subject to the installed Visual Studio license terms:\n"
              f"{redistribution}\n"
              "https://learn.microsoft.com/en-us/cpp/windows/redistributing-visual-cpp-files\n"
              "Windows 10/11 supply the Universal CRT system components.\n")
    provenance = {"visualStudioVersion": info["installationVersion"], "visualStudioProduct": info.get("productId"),
                  "sourceDirectory": source.relative_to(installation).as_posix(), "redistributionTerms": redistribution,
                  "signatures": signed, "imports": imports, "sourceSha256": before}
    # Verify every source and signature before changing the output. A final hash comparison
    # detects mutation between Authenticode verification and the copied bytes.
    with tempfile.TemporaryDirectory(prefix="hearth-crt-", dir=bundle.parent) as temporary:
        staged = Path(temporary)
        for path in sources:
            shutil.copyfile(path, staged / path.name)
            if sha256(staged / path.name) != before[path.name] or sha256(path) != before[path.name]:
                raise ValueError("CRT source changed during signature verification")
        (staged / NOTICE_NAME).write_text(notice, encoding="utf-8", newline="\n")
        for index, path in enumerate(sorted(set(legal))):
            filename = f"MICROSOFT-VISUAL-STUDIO-LICENSE-{index + 1}{path.suffix.lower()}"
            shutil.copyfile(path, staged / filename)
        provenance["installedLicenseFiles"] = sorted(path.name for path in staged.iterdir() if "LICENSE-" in path.name)
        (staged / "microsoft-crt-provenance.json").write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8", newline="\n")
        for path in staged.iterdir():
            os.replace(path, bundle / path.name)
            manifest["files"][path.name] = sha256(bundle / path.name)
        manifest["microsoftCrt"] = {"provenance": "microsoft-crt-provenance.json", "dlls": list(DLLS), "redistributionTerms": redistribution}
        temporary_manifest = bundle / "manifest.json.tmp"
        temporary_manifest.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8", newline="\n")
        os.replace(temporary_manifest, manifest_path)
    print("Staged Microsoft-signed x64 CRT:", ", ".join(DLLS), "from", source)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, default=ROOT / "desktop-runtime/native-libs/windows-x64")
    args = parser.parse_args()
    if platform.system() != "Windows" or platform.machine().lower() not in ("amd64", "x86_64"):
        raise SystemExit("CRT staging requires a Windows x64 host")
    stage(args.bundle.resolve())


if __name__ == "__main__":
    main()
