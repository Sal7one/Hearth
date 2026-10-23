#!/usr/bin/env python3
"""Run real Whisper + EuroLLM GPU inference through the packaged launcher/JRE.

No downloads, recording, playback, model/config edits, or GitHub calls. Pass the
pinned local model files and any 0.5–30 second speech WAV explicitly.
"""
import argparse
import json
from pathlib import Path
import platform
import os
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backend', required=True, choices=('metal', 'cuda'))
    parser.add_argument('--whisper', required=True, type=Path)
    parser.add_argument('--translation', required=True, type=Path)
    parser.add_argument('--wav', required=True, type=Path)
    parser.add_argument('--launcher', type=Path, help='Packaged Hearth executable; auto-detected from local app image otherwise')
    parser.add_argument('--receipt', type=Path, default=ROOT / 'build/desktop-gpu-validation.json')
    args = parser.parse_args()
    expected = 'Darwin' if args.backend == 'metal' else 'Windows'
    if platform.system() != expected:
        parser.error(f'{args.backend} validation requires {expected} hardware')
    base = ROOT / 'desktopApp/build/compose/binaries/main/app'
    candidates = list(base.glob('*.app/Contents/MacOS/Hearth')) if expected == 'Darwin' else list(base.glob('*/Hearth.exe'))
    launcher = args.launcher or (candidates[0] if len(candidates) == 1 else None)
    if not launcher or not launcher.is_file():
        parser.error('Provide --launcher or build the packaged app image first')
    for path in (args.whisper, args.translation, args.wav):
        if not path.is_file(): parser.error(f'Missing fixture: {path}')
    args.receipt.parent.mkdir(parents=True, exist_ok=True)
    args.receipt.unlink(missing_ok=True)
    result = subprocess.run([str(launcher.resolve()), '--gpu-check', args.backend, str(args.whisper.resolve()),
                             str(args.translation.resolve()), str(args.wav.resolve()), str(args.receipt.resolve())], timeout=300)
    if result.returncode:
        raise SystemExit(f'GPU inference failed ({result.returncode}); inspect {args.receipt}')
    receipt = json.loads(args.receipt.read_text(encoding='utf-8'))
    if receipt.get('status') != 'PASS' or receipt.get('backend') != args.backend:
        raise SystemExit(f'GPU inference did not produce a passing receipt: {args.receipt}')
    if args.backend == 'cuda':
        # Read optional driver evidence only from the installed system utility;
        # inference/placement above remains the pass criterion.
        utility = Path(os.environ.get('SystemRoot', 'C:/Windows')) / 'System32/nvidia-smi.exe'
        if utility.is_file():
            info = subprocess.run([str(utility), '--query-gpu=index,name,driver_version', '--format=csv,noheader'],
                                  capture_output=True, text=True, timeout=30)
            receipt['nvidiaDriverQuery'] = {'exitCode': info.returncode, 'output': info.stdout.strip(), 'error': info.stderr.strip()}
            args.receipt.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'Actual {args.backend} speech + Arabic translation inference: PASS')
    print(f'Device, native/model hashes, placement, outputs and timings: {args.receipt.resolve()}')


if __name__ == '__main__': main()
