#!/usr/bin/env python3
"""Convert the existing Hearth artwork into native app icons (run on macOS)."""
from pathlib import Path
import struct
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "desktopApp/src/desktopMain/resources/hearth.png"
OUTPUT = ROOT / "desktopApp/packaging/icons"


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="hearth-icons-") as temporary:
        directory = Path(temporary)
        iconset = directory / "hearth.iconset"
        iconset.mkdir()
        frames = {}
        for size in (16, 32, 48, 64, 128, 256, 512, 1024):
            path = directory / f"{size}.png"
            subprocess.run(["/usr/bin/sips", "--resampleHeightWidth", str(size), str(size),
                            str(SOURCE), "--out", str(path)], check=True, capture_output=True)
            frames[size] = path.read_bytes()
        for size in (16, 32, 128, 256, 512):
            (iconset / f"icon_{size}x{size}.png").write_bytes(frames[size])
            (iconset / f"icon_{size}x{size}@2x.png").write_bytes(frames[size * 2])
        subprocess.run(["/usr/bin/iconutil", "--convert", "icns", str(iconset),
                        "--output", str(OUTPUT / "hearth.icns")], check=True)
        # Vista and later support PNG-compressed ICO frames. Include smaller
        # sizes for Explorer/title bars and 256 px for high-density launchers.
        sizes = (16, 32, 48, 64, 128, 256)
        offset = 6 + 16 * len(sizes)
        headers = []
        for size in sizes:
            data = frames[size]
            headers.append(struct.pack("<BBBBHHII", size % 256, size % 256, 0, 0,
                                       1, 32, len(data), offset))
            offset += len(data)
        (OUTPUT / "hearth.ico").write_bytes(struct.pack("<HHH", 0, 1, len(sizes)) +
            b"".join(headers) + b"".join(frames[size] for size in sizes))
    for name in ("hearth.icns", "hearth.ico"):
        print(f"{name}: {(OUTPUT / name).stat().st_size:,} bytes from {SOURCE.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
