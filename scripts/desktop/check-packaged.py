#!/usr/bin/env python3
"""Exercise the actual packaged launcher/JRE/native resources without opening a window."""
from pathlib import Path
import platform
import plistlib
import subprocess

ROOT=Path(__file__).resolve().parents[2]
base=ROOT/'desktopApp/build/compose/binaries/main/app'
if platform.system()=='Darwin':
    launchers=list(base.glob('*.app/Contents/MacOS/Hearth'))
else:
    launchers=list(base.glob('*/Hearth.exe'))
if len(launchers)!=1: raise SystemExit(f'Expected one packaged launcher: {launchers}')
if platform.system()=='Darwin':
    contents=launchers[0].parents[1]
    info=plistlib.loads((contents/'Info.plist').read_bytes())
    icon=info.get('CFBundleIconFile', '')
    if not icon or Path(icon).name!=icon: raise SystemExit('Missing or invalid packaged app icon')
    if not icon.endswith('.icns'): icon += '.icns'
    if (contents/'Resources'/icon).read_bytes() != (ROOT/'desktopApp/packaging/icons/hearth.icns').read_bytes():
        raise SystemExit('Packaged app does not use the Hearth icon')
report=ROOT/'build/desktop-packaged-self-check.properties'
report.unlink(missing_ok=True)
result=subprocess.run([str(launchers[0]),'--self-check',str(report)],timeout=120)
if result.returncode: raise SystemExit(f'Packaged launcher failed: {result.returncode}')
if not report.is_file() or 'result=PASS' not in report.read_text(encoding='iso-8859-1'):
    raise SystemExit('Packaged launcher did not confirm successful runtime validation')
print('Actual packaged launcher, native adapters, audio/crypto services and authenticated HTTP API: PASS')
