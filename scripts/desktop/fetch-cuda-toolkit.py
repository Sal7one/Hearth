#!/usr/bin/env python3
"""Fetch the build-only CUDA compiler and headers for the Nemotron GPU backend.

Whisper and GGUF translation on CUDA bundle NVIDIA's own cuBLAS libraries, so that
build needs an installed official toolkit (docs/desktop-cuda.md) and this script does
not serve it. The Nemotron backend is different: it links NVIDIA's static runtime and
code built here, and ships no NVIDIA library beside it. It needs the compiler, the
headers and two import libraries at build time only.

This script assembles exactly that from NVIDIA's own redistributable archives, each
pinned by SHA-256, inside the ignored build tree. It needs no administrator rights,
runs no installer, changes no PATH and extracts none of NVIDIA's runtime DLLs.

The archives are NVIDIA software under the NVIDIA CUDA EULA
(https://docs.nvidia.com/cuda/eula/index.html). Pass --accept-nvidia-eula to download
them; each component's own licence text is kept beside the tools.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import shutil
import sys
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
VERSION = "12.9.1"
SOURCE = "https://developer.download.nvidia.com/compute/cuda/redist/"
EULA = "https://docs.nvidia.com/cuda/eula/index.html"
RECEIPT = "hearth-cuda-toolkit.json"
# From NVIDIA's redistrib_12.9.1.json (SHA-256 8335301010b0023ee1ff61eb11e2600ca62002d76780de4089011ad77e0c7630).
# `keep` lists what the build uses; nothing else leaves the archive. No runtime DLL is listed.
COMPONENTS = {
    "cuda_nvcc": {
        "version": "12.9.86", "size": 126917884,
        "archive": "cuda_nvcc/windows-x86_64/cuda_nvcc-windows-x86_64-12.9.86-archive.zip",
        "sha256": "227b109663b5e57d2718bcabb24a4ba0d9d4e52d958e327dc476f7c28691be85",
        "keep": ("bin/", "nvvm/", "include/", "lib/"),
    },
    "cuda_cudart": {
        "version": "12.9.79", "size": 3521238,
        "archive": "cuda_cudart/windows-x86_64/cuda_cudart-windows-x86_64-12.9.79-archive.zip",
        "sha256": "179e9c43b0735ffe67207b3da556eb5a0c50f3047961882b7657d3b822d34ef8",
        "keep": ("include/", "lib/"),
    },
    "cuda_cccl": {
        "version": "12.9.27", "size": 3127658,
        "archive": "cuda_cccl/windows-x86_64/cuda_cccl-windows-x86_64-12.9.27-archive.zip",
        "sha256": "17aaa7c6b8f94a417d8f3261780b7e34b9cbdfab7513bce86768623b06aa28b5",
        "keep": ("include/", "lib/"),
    },
    "libcublas": {
        "version": "12.9.1.4", "size": 549755186,
        "archive": "libcublas/windows-x86_64/libcublas-windows-x86_64-12.9.1.4-archive.zip",
        "sha256": "d534d98b0b453a98914dbf3adf47d7e84b55037abf02f87466439e1dcef581ed",
        "keep": ("include/", "lib/x64/cublas.lib"),
    },
}
# The assembled tree is a pure function of the pinned archives and `keep`.
TREE_SHA256 = "9746ba6d9627131367e8c74cb039f782dc8ff7558ac751936b025e3c62fd875e"
MAX_ENTRIES = 40_000
MAX_FILE_BYTES = 512 * 1024 * 1024
MAX_TREE_BYTES = 2 * 1024 * 1024 * 1024
REQUIRED = ("bin/nvcc.exe", "include/cuda.h", "include/cuda_runtime.h", "include/cublas_v2.h",
            "lib/x64/cudart_static.lib", "lib/x64/cuda.lib", "lib/x64/cublas.lib")


class ToolkitError(RuntimeError):
    pass


def default_root():
    return ROOT / "build/desktop-dependencies/cuda-toolkit" / VERSION


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def tree_sha256(root):
    """One digest for the whole tree: every file's path and SHA-256, in path order. The receipt is not part of it."""
    root = Path(root)
    digest = hashlib.sha256()
    count = 0
    for path in sorted(item for item in root.rglob("*") if item.is_file()):
        relative = path.relative_to(root).as_posix()
        if relative == RECEIPT:
            continue
        if path.is_symlink():
            raise ToolkitError(f"The CUDA build tools contain a link: {relative}")
        digest.update(relative.encode("utf-8") + b"\0" + sha256(path).encode("ascii") + b"\n")
        count += 1
    return digest.hexdigest(), count


def download(component, destination, open_url=urllib.request.urlopen, report=print):
    """Downloads one pinned archive, or reuses a copy that already has the pinned hash."""
    pin = COMPONENTS[component]
    target = Path(destination) / PurePosixPath(pin["archive"]).name
    if target.is_file() and target.stat().st_size == pin["size"] and sha256(target) == pin["sha256"]:
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_name(target.name + ".part")
    digest = hashlib.sha256()
    received = 0
    report(f"Downloading {component} {pin['version']} ({pin['size'] / 1e6:.0f} MB) from {SOURCE}")
    try:
        with open_url(SOURCE + pin["archive"]) as response, partial.open("wb") as output:
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                received += len(chunk)
                if received > pin["size"]:
                    raise ToolkitError(f"{component} is larger than its pinned {pin['size']} bytes")
                digest.update(chunk)
                output.write(chunk)
        if received != pin["size"] or digest.hexdigest() != pin["sha256"]:
            raise ToolkitError(f"{component} does not match its pinned size and SHA-256; nothing was extracted")
        os.replace(partial, target)
    finally:
        partial.unlink(missing_ok=True)
    return target


def members(component, archive):
    """The archive entries the build keeps, as (entry, path inside the toolkit). Unsafe paths refuse the whole archive."""
    keep = COMPONENTS[component]["keep"]
    selected = []
    entries = archive.infolist()
    if len(entries) > MAX_ENTRIES:
        raise ToolkitError(f"{component} has too many archive entries")
    for entry in entries:
        name = entry.filename
        parts = PurePosixPath(name).parts
        if "\\" in name or ":" in name or name.startswith("/") or ".." in parts or not parts:
            raise ToolkitError(f"{component} contains an unsafe archive path")
        # Every NVIDIA archive has one top-level folder named after itself.
        inner = "/".join(parts[1:])
        if entry.is_dir() or not inner:
            continue
        if inner == "LICENSE":
            selected.append((entry, f"licenses/{component}-LICENSE.txt"))
        elif any(inner == rule or (rule.endswith("/") and inner.startswith(rule)) for rule in keep):
            if entry.file_size > MAX_FILE_BYTES:
                raise ToolkitError(f"{component} contains an oversized file: {inner}")
            selected.append((entry, inner))
    return selected


def assemble(archives, root):
    """Extracts the kept members of every archive into a fresh tree and returns its digest."""
    root = Path(root)
    staging = root.with_name(root.name + ".partial")
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)
    total = 0
    written = {}
    try:
        for component, path in archives.items():
            with zipfile.ZipFile(path) as archive:
                selected = members(component, archive)
                if not any(target.startswith("licenses/") for _, target in selected):
                    raise ToolkitError(f"{component} has no licence text; refusing to use it")
                for entry, target in selected:
                    total += entry.file_size
                    if total > MAX_TREE_BYTES:
                        raise ToolkitError("The CUDA build tools exceed their size bound")
                    if written.setdefault(target.lower(), component) != component:
                        raise ToolkitError(f"{component} and {written[target.lower()]} both provide {target}")
                    output = staging.joinpath(*PurePosixPath(target).parts)
                    output.parent.mkdir(parents=True, exist_ok=True)
                    with archive.open(entry) as source, output.open("wb") as sink:
                        shutil.copyfileobj(source, sink, 1024 * 1024)
                    if output.stat().st_size != entry.file_size:
                        raise ToolkitError(f"{component} extracted {target} with the wrong size")
        missing = [name for name in REQUIRED if not (staging / name).is_file()]
        if missing:
            raise ToolkitError("The CUDA build tools are incomplete: " + ", ".join(missing))
        if any(path.suffix.lower() == ".dll" and "nvvm" not in path.relative_to(staging).parts for path in staging.rglob("*")):
            raise ToolkitError("A runtime DLL was extracted; the keep rules must never list one")
        digest, count = tree_sha256(staging)
        if TREE_SHA256 is not None and digest != TREE_SHA256:
            raise ToolkitError(f"The assembled CUDA build tools have tree SHA-256 {digest}, not the pinned {TREE_SHA256}")
        receipt = {"schemaVersion": 1, "toolkitVersion": VERSION, "source": SOURCE, "licence": EULA,
                   "treeSha256": digest, "files": count, "runtimeLibrariesIncluded": False,
                   "components": {name: {key: COMPONENTS[name][key] for key in ("version", "archive", "sha256", "size")} for name in archives}}
        (staging / RECEIPT).write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8", newline="\n")
        if root.exists():
            shutil.rmtree(root)
        os.replace(staging, root)
        return digest
    finally:
        if staging.exists():
            shutil.rmtree(staging)


def verify(root=None):
    """The toolkit version, after proving the tree is the pinned one. Raises ToolkitError with the reason otherwise."""
    root = Path(root) if root else default_root()
    try:
        receipt = json.loads((root / RECEIPT).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raise ToolkitError(f"The CUDA build tools are not fetched at {root}") from None
    pinned = {name: {key: COMPONENTS[name][key] for key in ("version", "archive", "sha256", "size")} for name in COMPONENTS}
    if receipt.get("toolkitVersion") != VERSION or receipt.get("components") != pinned:
        raise ToolkitError("The fetched CUDA build tools were made from different pins; fetch them again")
    digest, _ = tree_sha256(root)
    expected = TREE_SHA256 or receipt.get("treeSha256")
    if digest != expected:
        raise ToolkitError("The fetched CUDA build tools were changed after they were verified; fetch them again")
    missing = [name for name in REQUIRED if not (root / name).is_file()]
    if missing:
        raise ToolkitError("The CUDA build tools are incomplete: " + ", ".join(missing))
    return receipt["toolkitVersion"]


def fetch(root=None, downloads=None, open_url=urllib.request.urlopen, report=print):
    root = Path(root) if root else default_root()
    downloads = Path(downloads) if downloads else root.parent / "downloads"
    archives = {name: download(name, downloads, open_url, report) for name in COMPONENTS}
    report("Extracting the compiler, headers and import libraries")
    return assemble(archives, root)


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--accept-nvidia-eula", action="store_true", help=f"Required to download: the archives are under {EULA}")
    parser.add_argument("--check", action="store_true", help="Only verify what is already fetched; download nothing")
    parser.add_argument("--root", type=Path, default=default_root(), help="Where the build tools are assembled")
    args = parser.parse_args(arguments)
    if platform.system() != "Windows" or platform.machine().lower() not in ("amd64", "x86_64"):
        parser.error("These are the Windows x64 CUDA build tools")
    try:
        try:
            version = verify(args.root)
            print(f"CUDA {version} build tools are ready at {args.root}")
            return 0
        except ToolkitError as reason:
            if args.check:
                print(reason, file=sys.stderr)
                return 1
        if not args.accept_nvidia_eula:
            total = sum(pin["size"] for pin in COMPONENTS.values()) / 1e6
            parser.error(f"Downloading needs --accept-nvidia-eula: {total:.0f} MB of NVIDIA software under {EULA}")
        digest = fetch(args.root)
        print(f"CUDA {VERSION} build tools are ready at {args.root} (tree SHA-256 {digest})")
        return 0
    except ToolkitError as failure:
        print(failure, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
