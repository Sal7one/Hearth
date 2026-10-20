#!/usr/bin/env python3
"""Exercise the actual packaged launcher/JRE/native resources without opening a window."""
from pathlib import Path
import platform
import subprocess

ROOT=Path(__file__).resolve().parents[2]
base=ROOT/'desktopApp/build/compose/binaries/main/app'
if platform.system()=='Darwin':
    launchers=list(base.glob('*.app/Contents/MacOS/Hearth'))
else:
    launchers=list(base.glob('*/Hearth.exe'))
if len(launchers)!=1: raise SystemExit(f'Expected one packaged launcher: {launchers}')
report=ROOT/'build/desktop-packaged-self-check.properties'
report.unlink(missing_ok=True)
result=subprocess.run([str(launchers[0]),'--self-check',str(report)],timeout=120)
if result.returncode: raise SystemExit(f'Packaged launcher failed: {result.returncode}')
if not report.is_file() or 'result=PASS' not in report.read_text(encoding='iso-8859-1'):
    raise SystemExit('Packaged launcher did not confirm successful runtime validation')
print('Actual packaged launcher, native adapters, audio/crypto services and authenticated HTTP API: PASS')
