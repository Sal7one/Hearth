#!/usr/bin/env python3
"""Build isolated Play-only HTTP(S) aria2 executables and corresponding sources.

No ytdlp/Seal wrapper, downloads at runtime, external shared libraries, or unpinned
Termux payload. Requires NDK27, make/autoconf/automake/gettext/libtool and Perl.
Generated artifacts are under app/build, sources under build/research.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import io
import copy
import gzip
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCES = {
    "aria2": ("https://github.com/aria2/aria2.git", "02f2d0d8472b3c38c29b4dba8c75ebd5fdd2899a"),
    "openssl": ("https://github.com/openssl/openssl.git", "45e844fa2a14ec92d146bd8f5778ac130b6625fb"),
}
# Canonical `git -c tar.umask=0002 archive --format=tar --prefix=NAME/ PIN`.
# These hashes authenticate Git-free source delivery as well as fresh checkouts.
SOURCE_ARCHIVES = {
    "aria2": "8a3d395acc53951ce4e020186a2040dbf66c7a266f985b16cabf0f704e77b1df",
    "openssl": "2611c027f97f06f32d5195a38788ed7885e293adaa01e5ceb164a3896632b2b0",
}
ABIS = {"arm64-v8a": ("aarch64-linux-android", "android-arm64"), "x86_64": ("x86_64-linux-android", "android-x86_64")}

def run(args, cwd, env=None):
    subprocess.run([str(a) for a in args], cwd=cwd, env=env, check=True)

def source(name):
    url, pin = SOURCES[name]
    bundled = ROOT / "source-archives" / (name + ".tar")
    if bundled.exists():
        verified_archive(name, bundled.read_bytes())
        path = ROOT / "build/aria2-offline-source" / name
        if path.exists():
            shutil.rmtree(path)
        extract_archive(name, bundled.read_bytes(), path)
        return path
    path = ROOT / "build/research" / name
    if not (path / ".git").is_dir():
        path.mkdir(parents=True, exist_ok=True)
        run(["git", "init"], path)
        run(["git", "remote", "add", "origin", url], path)
        run(["git", "fetch", "--depth=1", "origin", pin], path)
        run(["git", "checkout", "--detach", "FETCH_HEAD"], path)
    assert subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=path, text=True).strip() == pin, f"Wrong {name} source revision"
    assert not subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=no"], cwd=path), f"Modified {name} source"
    return path

def verified_archive(name, data):
    actual = hashlib.sha256(data).hexdigest()
    if actual != SOURCE_ARCHIVES[name]:
        raise RuntimeError(f"{name} source archive failed SHA-256 verification: {actual}")
    return data

def source_archive(path):
    name = path.name
    if (path / ".git").is_dir():
        data = subprocess.check_output(["git", "-c", "tar.umask=0002", "archive", "--format=tar", "--prefix=" + name + "/", SOURCES[name][1]], cwd=path)
    else:
        # A received corresponding-source bundle is sufficient; Git and network
        # access are unnecessary when these pinned archives are available.
        data = (ROOT / "source-archives" / (name + ".tar")).read_bytes()
    return verified_archive(name, data)

def extract_archive(name, data, destination):
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(verified_archive(name, data))) as tar:
        members = []
        for original in tar:
            if original.name.rstrip("/") == name:
                continue
            if not original.name.startswith(name + "/"):
                raise RuntimeError(f"Unexpected {name} source archive root: {original.name}")
            member = copy.copy(original)
            member.name = original.name[len(name) + 1:]
            if member.islnk():
                if not member.linkname.startswith(name + "/"):
                    raise RuntimeError("Source archive hard link escapes its root")
                member.linkname = member.linkname[len(name) + 1:]
            members.append(member)
        tar.extractall(destination, members=members, filter="data")

def extract_source(path, destination):
    extract_archive(path.name, source_archive(path), destination)

def file_sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source_file:
        for chunk in iter(lambda: source_file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

def toolchain_notices(ndk):
    result = {}
    for name, upstream in (("NDK-NOTICE.txt", "NOTICE"), ("NDK-TOOLCHAIN-NOTICE.txt", "NOTICE.toolchain")):
        received = ROOT / "licenses/aria2" / name
        path = received if received.is_file() else ndk / upstream
        if not path.is_file():
            raise RuntimeError(f"Required NDK license notice is missing: {path}")
        result[name] = path.read_bytes()
    return result

def write_source_bundle(sources, ndk):
    bundle = ROOT / "build/aria2-corresponding-source.tar.gz"
    bundle.parent.mkdir(parents=True, exist_ok=True)
    notices = toolchain_notices(ndk)
    files = {
        "scripts/downloads/build-aria2-android.py": Path(__file__).read_bytes(),
        "scripts/native_abis.py": (ROOT / "scripts/native_abis.py").read_bytes(),
        "licenses/aria2/GPL-3.0.txt": (ROOT / "licenses/aria2/GPL-3.0.txt").read_bytes(),
        "licenses/aria2/README.md": (ROOT / "licenses/aria2/README.md").read_bytes(),
        "LICENSE": (ROOT / "LICENSE").read_bytes(),
        "NOTICE": (ROOT / "NOTICE").read_bytes(),
        "source-archives/manifest.json": (json.dumps({"sources": SOURCES, "sha256": SOURCE_ARCHIVES}, indent=2) + "\n").encode(),
    }
    for name, content in notices.items():
        files["licenses/aria2/" + name] = content
    fd, temporary_name = tempfile.mkstemp(prefix=".aria2-source-", suffix=".tar.gz", dir=bundle.parent)
    temporary = Path(temporary_name)
    try:
        # Reproducible container headers; inner tar bytes retain the pinned Git
        # archive metadata. No local usernames, paths or timestamps are included.
        with os.fdopen(fd, "wb") as output, gzip.GzipFile(filename="", fileobj=output, mode="wb", mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode="w") as tar:
                def add(name, data):
                    item = tarfile.TarInfo(name)
                    item.size = len(data)
                    item.mode = 0o755 if name.endswith(".py") else 0o644
                    tar.addfile(item, io.BytesIO(data))
                for name, content in sorted(files.items()):
                    add(name, content)
                for name, path in sorted(sources.items()):
                    add("source-archives/" + name + ".tar", source_archive(path))
        temporary.replace(bundle)
    finally:
        temporary.unlink(missing_ok=True)
    digest = file_sha256(bundle)
    (ROOT / "build/aria2-source-manifest.json").write_text(json.dumps({
        "sources": SOURCES, "sourceArchives": SOURCE_ARCHIVES, "correspondingSourceSha256": digest,
    }, indent=2) + "\n")
    return bundle, digest, notices

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--abis", default="arm64-v8a,x86_64")
    parser.add_argument("--ndk", default=str(Path(os.environ.get("ANDROID_SDK_ROOT", str(Path.home()/"Library/Android/sdk"))) / "ndk/27.0.12077973"))
    parser.add_argument("--source-only", action="store_true", help="Create and verify corresponding sources without compiling or rewriting binary build identity")
    opts = parser.parse_args()
    abis = [abi for abi in opts.abis.split(",") if abi in ABIS]
    out = ROOT / "app/build/generated/aria2"
    script_sha = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    signature = {"sources": SOURCES, "sourceArchives": SOURCE_ARCHIVES, "scriptSha256": script_sha, "ndk": "27.0.12077973",
                 "helperSha256": file_sha256(ROOT / "scripts/native_abis.py"), "readmeSha256": file_sha256(ROOT / "licenses/aria2/README.md")}
    manifest = out / "assets/downloads/aria2-runtime.json"
    old = json.loads(manifest.read_text()) if manifest.exists() else {}
    bundle = ROOT/"build/aria2-corresponding-source.tar.gz"
    required_notices = ("aria2-COPYING.txt", "GPL-3.0.txt", "OpenSSL-LICENSE.txt", "NOTICE.txt", "NDK-NOTICE.txt", "NDK-TOOLCHAIN-NOTICE.txt")
    if not opts.source_only and bundle.is_file() and old.get("correspondingSourceSha256") == file_sha256(bundle) and all(
        (out/"assets/downloads"/name).is_file() for name in required_notices) and old.get("signature") == json.loads(json.dumps(signature)) and all(
        (out / "jniLibs" / abi / "libhearth_aria2c.so").is_file() and
        hashlib.sha256((out / "jniLibs" / abi / "libhearth_aria2c.so").read_bytes()).hexdigest() == old.get("binaries", {}).get(abi, {}).get("sha256") for abi in abis):
        print("Pinned aria2 runtimes already built")
        return
    sources = {name: source(name) for name in SOURCES}
    bundle, source_digest, ndk_notices = write_source_bundle(sources, Path(opts.ndk))
    if opts.source_only:
        print(json.dumps({"bundle": str(bundle), "sha256": source_digest, "sources": SOURCE_ARCHIVES}, indent=2))
        return
    revision_file = Path(opts.ndk) / "source.properties"
    if not revision_file.is_file() or not any(line.strip() == "Pkg.Revision = 27.0.12077973" for line in revision_file.read_text().splitlines()):
        raise RuntimeError("This build requires Android NDK 27.0.12077973; choose that directory with --ndk")
    toolchains = list((Path(opts.ndk)/"toolchains/llvm/prebuilt").glob("*"))
    if not toolchains: raise RuntimeError("NDK27 LLVM toolchain missing")
    toolchain = toolchains[0]
    workroot = ROOT / "build/aria2-android"
    binaries = old.get("binaries", {}) if old.get("signature") == json.loads(json.dumps(signature)) else {}
    for abi in abis:
        work = workroot / abi
        if work.exists(): shutil.rmtree(work)
        work.mkdir(parents=True)
        triple, openssl_target = ABIS[abi]
        env = os.environ.copy()
        env.update({"ANDROID_NDK_ROOT": str(opts.ndk), "PATH": str(toolchain/"bin")+os.pathsep+env["PATH"],
                    "CC": triple+"28-clang", "CXX": triple+"28-clang++", "AR": "llvm-ar", "RANLIB": "llvm-ranlib",
                    "PKG_CONFIG_LIBDIR": str(work/"empty-pkgconfig")})
        openssl = work/"openssl"
        extract_source(sources["openssl"], openssl)
        prefix = work/"prefix"
        run(["perl", "Configure", openssl_target, "no-shared", "no-tests", "no-apps", "no-docs", "no-module", "no-legacy", "no-asm",
             "-D__ANDROID_API__=28", "-fPIC", "-Os", "--prefix="+str(prefix), "--libdir=lib"], openssl, env)
        run(["make", "-j4", "build_libs"], openssl, env)
        run(["make", "install_dev"], openssl, env)
        aria = work/"aria2"
        extract_source(sources["aria2"], aria)
        run(["autoreconf", "-fi"], aria, env)
        env.update({"OPENSSL_CFLAGS": "-I"+str(prefix/"include"), "OPENSSL_LIBS": f"-L{prefix/'lib'} -lssl -lcrypto -ldl",
                    "CXXFLAGS": "-Os -fPIE -fPIC", "CFLAGS": "-Os -fPIE -fPIC",
                    "LDFLAGS": "-pie -static-libstdc++ -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"})
        run([aria/"configure", "--host="+triple, "--disable-nls", "--disable-bittorrent", "--disable-metalink", "--disable-websocket",
             "--disable-libaria2", "--without-appletls", "--without-wintls", "--without-gnutls", "--without-libnettle", "--without-libgmp",
             "--without-libgcrypt", "--with-openssl", "--without-sqlite3", "--without-libxml2", "--without-libexpat", "--without-libcares",
             "--without-libssh2", "--without-libz", "--without-libuv", "--without-tcmalloc", "--without-jemalloc"], aria, env)
        run(["make", "-j4", "-C", "src"], aria, env)
        binary = out/"jniLibs"/abi/"libhearth_aria2c.so"
        binary.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(aria/"src/aria2c", binary)
        run([toolchain/"bin/llvm-strip", "--strip-unneeded", binary], work)
        import sys
        sys.path.insert(0, str(ROOT/"scripts"))
        from native_abis import verify_elf
        verify_elf(abi, binary.read_bytes())
        needed = subprocess.check_output([toolchain/"bin/llvm-readelf", "-d", binary], text=True)
        assert "libc++_shared" not in needed and "libssl.so" not in needed and "libcrypto.so" not in needed, needed
        binaries[abi] = {"sha256": hashlib.sha256(binary.read_bytes()).hexdigest(), "bytes": binary.stat().st_size}
    assets = out/"assets/downloads"
    assets.mkdir(parents=True, exist_ok=True)
    manifest.write_text(json.dumps({"signature": signature, "binaries": binaries, "license": "GPL-3.0-or-later", "httpOnly": True,
                                    "correspondingSourceSha256": source_digest}, indent=2)+"\n")
    # Source delivery is part of staging, not an unfulfilled offer. Release assets
    # include this alongside APKs. Original source licenses are retained verbatim.
    shutil.copy2(sources["aria2"]/"COPYING", assets/"aria2-COPYING.txt")
    shutil.copy2(ROOT/"licenses/aria2/GPL-3.0.txt", assets/"GPL-3.0.txt")
    shutil.copy2(sources["openssl"]/"LICENSE.txt", assets/"OpenSSL-LICENSE.txt")
    for name, content in ndk_notices.items():
        (assets/name).write_bytes(content)
    (assets/"NOTICE.txt").write_text("aria2 1.37.0, Copyright Tatsuhiro Tsujikawa. GPL-2.0-or-later; this OpenSSL-linked build is distributed under GPL-3.0-or-later.\nOpenSSL 3.5.9, Copyright The OpenSSL Project Authors. Apache-2.0.\nAndroid NDK27 compiler/runtime notices are included as NDK-NOTICE.txt and NDK-TOOLCHAIN-NOTICE.txt in this directory and in licenses/aria2 in the corresponding-source bundle.\nPinned source and Git-free build instructions are in scripts/downloads/build-aria2-android.py and licenses/aria2/README.md in the accompanying aria2-corresponding-source.tar.gz release asset. No Sealplus/ytdlp code or runtime is bundled.\n")
    print(json.dumps(binaries, indent=2))

if __name__ == "__main__": main()
