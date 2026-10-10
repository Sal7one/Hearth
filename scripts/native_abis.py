"""Android ABI identity checks shared by staging and APK verification."""
import struct
import hashlib
import json

ABIS = ("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
ELF_IDENTITY = {"armeabi-v7a": (1, 40), "arm64-v8a": (2, 183),
                "x86": (1, 3), "x86_64": (2, 62)}

def source_set_digest(source_hashes):
    """Bind each ABI's binary to its own source snapshot, regardless of map order."""
    return hashlib.sha256(json.dumps(source_hashes, sort_keys=True,
        separators=(",", ":")).encode("utf-8")).hexdigest()

def verify_elf(abi, data):
    """Reject wrong machine, width, byte order or a non-shared ELF before staging."""
    width, machine = ELF_IDENTITY[abi]
    if len(data) < (64 if width == 2 else 52) or data[:4] != b"\x7fELF":
        raise ValueError(f"{abi}: missing/truncated ELF header")
    if data[4] != width or data[5] != 1:
        raise ValueError(f"{abi}: incorrect ELF width or byte order")
    kind, actual = struct.unpack_from("<HH", data, 16)
    if kind != 3 or actual != machine:
        raise ValueError(f"{abi}: incorrect ELF type or machine ({kind}, {actual})")

def update_checksums(lib_root):
    import hashlib
    files = sorted(p for abi in ABIS for p in (lib_root / abi).glob("*.so"))
    (lib_root / "SHA256SUMS").write_text("".join(
        f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(lib_root)}\n" for p in files))
