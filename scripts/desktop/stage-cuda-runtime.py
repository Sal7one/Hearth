#!/usr/bin/env python3
"""Stage signed CUDA redistributables from an installed Windows x64 toolkit.

No toolkit, driver, model, or DLL is downloaded. This is the app-local runtime
closure for Hearth's pinned GGML CUDA build, not a general CUDA SDK packager.
The NVIDIA display driver (nvcuda.dll) always remains a system prerequisite.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location("hearth_windows_crt_pe", Path(__file__).with_name("stage-windows-crt.py"))
_crt = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_crt)
sha256 = _crt.sha256
pe_imports = _crt.pe_imports
DEFAULT_ENTRIES = ("hearth_desktop_whisper.dll", "transiber_translation.dll")
DRIVER = "nvcuda.dll"
PROVENANCE = "nvidia-cuda-provenance.json"
NOTICE = "NVIDIA-CUDA-NOTICE.txt"
LICENSE = "NVIDIA-CUDA-EULA.txt"
RUNTIME = re.compile(r"(?:cudart|cublas|cublaslt)64_(12|13)\.dll")
SYSTEM_DLLS = _crt.SYSTEM_DLLS | {"version.dll", "winmm.dll", "cfgmgr32.dll", "setupapi.dll",
                                "psapi.dll", "secur32.dll", "crypt32.dll", "userenv.dll", "powrprof.dll"}
MAX_PE_BYTES = 1024 * 1024 * 1024


def _regular(path, root, maximum=MAX_PE_BYTES):
    if not path.is_file() or path.is_symlink() or path.stat().st_size > maximum:
        raise ValueError(f"Expected a bounded regular file: {path.name}")
    try:
        path.resolve().relative_to(root.resolve())
    except ValueError as error:
        raise ValueError(f"File escapes approved directory: {path.name}") from error
    return path


def installed_roots():
    """Only installer registry/default toolkit locations, never PATH DLLs."""
    roots = set()
    base = Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "NVIDIA GPU Computing Toolkit" / "CUDA"
    if base.is_dir():
        for path in base.iterdir():
            if path.is_dir() and re.fullmatch(r"v(?:12|13)\.\d+", path.name):
                path.resolve().relative_to(base.resolve())
                roots.add(path.resolve())
    if platform.system() == "Windows":
        import winreg
        registry = r"SOFTWARE\NVIDIA Corporation\GPU Computing Toolkit\CUDA"
        try:
            with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, registry, 0, winreg.KEY_READ | winreg.KEY_WOW64_64KEY) as key:
                count = winreg.QueryInfoKey(key)[0]
                for index in range(count):
                    version = winreg.EnumKey(key, index)
                    if not re.fullmatch(r"v(?:12|13)\.\d+", version):
                        continue
                    with winreg.OpenKey(key, version) as child:
                        value, kind = winreg.QueryValueEx(child, "InstallDir")
                        if kind in (winreg.REG_SZ, winreg.REG_EXPAND_SZ):
                            roots.add(Path(os.path.expandvars(value)).resolve())
        except FileNotFoundError:
            pass
    return sorted(roots)


def resolve_toolkit(requested=None):
    approved = installed_roots()
    requested = requested or os.environ.get("CUDA_PATH")
    if requested:
        selected = Path(requested).resolve()
        if selected not in approved:
            raise ValueError("CUDA toolkit must be an installer-registered or default installed toolkit, not an arbitrary DLL directory")
        candidates = [selected]
    else:
        candidates = approved
    valid = []
    for root in candidates:
        version_file = _regular(root / "version.json", root, 1024 * 1024)
        metadata = json.loads(version_file.read_text(encoding="utf-8-sig"))
        version = metadata.get("cuda", {}).get("version", "")
        if not re.fullmatch(r"(?:12|13)\.\d+(?:\.\d+){0,2}", version):
            raise ValueError("Expected an installed CUDA 12/13 toolkit version.json")
        # Build-time toolkit evidence, not just copied redistributable DLLs.
        _regular(root / "include" / "cuda.h", root, 16 * 1024 * 1024)
        _regular(root / "bin" / "nvcc.exe", root, 128 * 1024 * 1024)
        valid.append((tuple(map(int, version.split("."))), root, version, version_file))
    if not valid:
        raise ValueError("Install an official Windows x64 CUDA 12/13 toolkit before packaging CUDA")
    _, root, version, version_file = max(valid, key=lambda item: item[0])
    return root, version, version_file


def signatures(paths):
    command = r"""
$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
Import-Module Microsoft.PowerShell.Security -ErrorAction Stop
$paths = [Console]::In.ReadToEnd() | ConvertFrom-Json
$results = @(foreach ($path in $paths) {
  $signature = Get-AuthenticodeSignature -LiteralPath $path
  $certificate = $signature.SignerCertificate
  if ($signature.Status -ne 'Valid' -or $null -eq $certificate -or
      $certificate.GetNameInfo([System.Security.Cryptography.X509Certificates.X509NameType]::SimpleName, $false) -ne 'NVIDIA Corporation') {
    throw "Expected valid NVIDIA Authenticode signature: $path ($($signature.Status))"
  }
  [pscustomobject]@{name=[IO.Path]::GetFileName($path).ToLowerInvariant();
    publisher=$certificate.Subject; thumbprint=$certificate.Thumbprint;
    version=(Get-Item -LiteralPath $path).VersionInfo.FileVersion; status='Valid'}
})
ConvertTo-Json -InputObject $results -Depth 4 -Compress
"""
    host = shutil.which("pwsh.exe") or shutil.which("powershell.exe")
    if host is None:
        raise ValueError("PowerShell with Microsoft.PowerShell.Security is required for NVIDIA Authenticode verification")
    environment = {key: value for key, value in os.environ.items() if key.casefold() != "psmodulepath"}
    output = subprocess.check_output([host, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", command],
                                     input=json.dumps([str(path) for path in paths]).encode("utf-8"), env=environment)
    return json.loads(output.decode("utf-8-sig"))


def runtime_sources(root, major):
    sources = {}
    for directory in (root / "bin" / "x64", root / "bin"):
        if not directory.is_dir():
            continue
        directory.resolve().relative_to(root.resolve())
        for path in directory.iterdir():
            name = path.name.lower()
            match = RUNTIME.fullmatch(name)
            if match and int(match[1]) == major:
                _regular(path, root)
                if name in sources and sha256(sources[name]) != sha256(path):
                    raise ValueError(f"Ambiguous installed CUDA runtime: {name}")
                sources[name] = path
    return sources


def dependency_closure(entries, sources, major):
    """Return actual normal/delay closure, plus cuBLAS's documented Lt dependency."""
    imports, visiting, order = {}, set(), []

    def visit(name):
        if name in visiting:
            raise ValueError(f"Cyclic CUDA runtime dependency: {name}")
        if name in imports:
            return
        match = RUNTIME.fullmatch(name)
        if not match or int(match[1]) != major or name not in sources:
            raise ValueError(f"Missing installed CUDA redistributable: {name}")
        visiting.add(name)
        dependencies = pe_imports(sources[name].read_bytes())
        # cuBLAS can resolve Lt dynamically; NVIDIA's library descriptor requires
        # it even when its name is absent from the PE import table.
        if name == f"cublas64_{major}.dll":
            dependencies = sorted(set(dependencies) | {f"cublaslt64_{major}.dll"})
        imports[name] = dependencies
        for dependency in dependencies:
            resolve(dependency)
        visiting.remove(name)
        order.append(name)

    def resolve(name):
        if name == DRIVER or name in SYSTEM_DLLS or name in _crt.DLLS or name.startswith(("api-ms-win-", "ext-ms-win-")):
            return
        if RUNTIME.fullmatch(name):
            visit(name)
            return
        raise ValueError(f"Unbundled non-system CUDA DLL dependency: {name}")

    entry_imports = {}
    for name, path in entries.items():
        dependencies = pe_imports(path.read_bytes())
        entry_imports[name] = dependencies
        for dependency in dependencies:
            resolve(dependency)
    if f"cublas64_{major}.dll" not in imports:
        raise ValueError("CUDA entry DLLs do not import cuBLAS; refusing to label a CPU-only build CUDA")
    return order, {**entry_imports, **imports}


def toolkit_license(root):
    # CUDA's installed EULA is mandatory. Never substitute Hearth's license or
    # silently ship only a web link for proprietary redistributables.
    for relative in ("EULA.txt", "eula.txt", "doc/EULA.txt", "doc/eula.txt"):
        path = root / relative
        if path.is_file():
            _regular(path, root, 8 * 1024 * 1024)
            text = path.read_text(encoding="utf-8-sig")
            if "NVIDIA" not in text or "CUDA" not in text:
                raise ValueError("Installed CUDA EULA is not recognizable NVIDIA CUDA license text")
            return path
    raise ValueError("Installed CUDA EULA.txt is required for redistribution")


def stage_files(bundle, toolkit_root=None, native_names=None):
    """Stage files before native probes/final manifest; return cudaRuntime receipt.

    The caller owns final native manifest creation. CRT redistribution is a
    separate existing step; recognized Microsoft CRT imports are not copied here.
    """
    bundle = Path(bundle).resolve()
    if not bundle.is_dir():
        raise ValueError("Native bundle must already exist")
    names = tuple(native_names or DEFAULT_ENTRIES)
    if not names or len(set(names)) != len(names) or any(not re.fullmatch(r"[a-z0-9_.-]+\.dll", name) for name in names):
        raise ValueError("CUDA entries must be unique lowercase DLL basenames")
    entries = {name: _regular(bundle / name, bundle) for name in names}
    root, version, version_file = resolve_toolkit(toolkit_root)
    major = int(version.split(".")[0])
    sources = runtime_sources(root, major)
    license_file = toolkit_license(root)
    # Fingerprint before parsing imports/signatures, then compare again before
    # publication so the inspected dependency graph describes the copied bytes.
    original = {path: sha256(path) for path in [*entries.values(), *sources.values(), version_file, license_file]}
    if json.loads(version_file.read_text(encoding="utf-8-sig")).get("cuda", {}).get("version") != version:
        raise ValueError("CUDA toolkit version changed during discovery")
    order, imports = dependency_closure(entries, sources, major)
    selected = [sources[name] for name in order]
    signed = signatures(selected)
    if (len(signed) != len(order) or {item.get("name") for item in signed} != set(order)
            or any(item.get("status") != "Valid" for item in signed)):
        raise ValueError("Incomplete NVIDIA Authenticode verification")
    # The copied installed EULA is authoritative for this exact toolkit. CUDA's
    # component patch versions are not necessarily documentation archive URLs.
    terms = "https://docs.nvidia.com/cuda/eula/index.html"
    provenance = {"schemaVersion": 1, "toolkitVersion": version,
                  "toolkitVersionSha256": original[version_file], "redistributionTerms": terms,
                  "stageScriptSha256": sha256(Path(__file__)), "signatures": signed,
                  "sourceFiles": {name: {"path": sources[name].relative_to(root).as_posix(), "sha256": original[sources[name]]} for name in order},
                  "entrySha256": {name: original[path] for name, path in entries.items()},
                  "imports": imports, "loadOrder": order, "driver": DRIVER,
                  "requiresSystemDriver": True, "installedLicensePath": license_file.relative_to(root).as_posix()}
    notice = ("NVIDIA CUDA runtime redistributables\nCopyright NVIDIA Corporation. All rights reserved.\n\n"
              "Unmodified signed CUDA runtime files are distributed app-locally for Hearth.\n"
              "They remain NVIDIA software under the accompanying installed NVIDIA CUDA EULA.\n"
              "They are not relicensed under Hearth's license. No developer tools or NVIDIA\n"
              "driver are included. A compatible installed NVIDIA display driver is required.\n"
              f"Installed toolkit: {version}\nRedistribution terms: {terms}\n"
              "https://docs.nvidia.com/cuda/eula/index.html#attachment-a\n")
    with tempfile.TemporaryDirectory(prefix="hearth-cuda-", dir=bundle.parent) as temporary:
        staged = Path(temporary)
        for name in order:
            shutil.copyfile(sources[name], staged / name)
            if sha256(staged / name) != original[sources[name]]:
                raise ValueError("CUDA source changed while copying")
        shutil.copyfile(license_file, staged / LICENSE)
        (staged / NOTICE).write_text(notice, encoding="utf-8", newline="\n")
        (staged / PROVENANCE).write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8", newline="\n")
        # Verify sources/entries again after Authenticode and copying, before
        # publishing anything, including the exact EULA/version metadata bytes.
        if any(sha256(path) != digest for path, digest in original.items()) or sha256(staged / LICENSE) != original[license_file]:
            raise ValueError("CUDA source or native entry changed during verification")
        files = {path.name: sha256(path) for path in staged.iterdir()}
        for path in staged.iterdir():
            if (bundle / path.name).is_symlink():
                raise ValueError("Refusing to replace a symlink in native bundle")
        for path in staged.iterdir():
            os.replace(path, bundle / path.name)
    return {"schemaVersion": 1, "toolkitVersion": version, "entries": list(names),
            "files": order, "loadOrder": order, "sha256": files, "provenance": PROVENANCE,
            "license": LICENSE, "redistributionTerms": terms, "driver": DRIVER, "requiresSystemDriver": True}


def stage(bundle, toolkit_root=None, native_names=None):
    """Compatibility wrapper: also update an already-created Windows manifest."""
    bundle = Path(bundle).resolve()
    path = bundle / "manifest.json"
    manifest = None
    if path.exists():
        manifest = json.loads(path.read_text(encoding="utf-8"))
        if manifest.get("platform") != "windows-x64" or manifest.get("schemaVersion") != 1:
            raise ValueError("Expected a windows-x64 native manifest")
        for name in native_names or DEFAULT_ENTRIES:
            if manifest.get("files", {}).get(name) != sha256(bundle / name):
                raise ValueError("CUDA native entry does not match existing manifest")
    receipt = stage_files(bundle, toolkit_root, native_names)
    if manifest is not None:
        manifest["files"].update(receipt["sha256"])
        manifest["cudaRuntime"] = receipt
        temporary = path.with_suffix(".json.tmp")
        temporary.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8", newline="\n")
        os.replace(temporary, path)
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, default=ROOT / "desktop-runtime/native-libs/windows-x64")
    parser.add_argument("--toolkit", type=Path)
    parser.add_argument("--entry", action="append", dest="entries")
    parser.add_argument("--receipt-json", type=Path, help="Builder-owned receipt; no pre-existing native manifest needed")
    args = parser.parse_args()
    if platform.system() != "Windows" or platform.machine().lower() not in ("amd64", "x86_64"):
        parser.error("CUDA runtime staging requires a Windows x64 host")
    receipt = stage_files(args.bundle, args.toolkit, args.entries) if args.receipt_json else stage(args.bundle, args.toolkit, args.entries)
    if args.receipt_json:
        args.receipt_json.parent.mkdir(parents=True, exist_ok=True)
        temporary = args.receipt_json.with_suffix(".tmp")
        temporary.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8", newline="\n")
        os.replace(temporary, args.receipt_json)
    print("Staged NVIDIA-signed CUDA runtime:", ", ".join(receipt["loadOrder"]))


if __name__ == "__main__":
    main()
