#!/usr/bin/env python3
"""Validate the complete vendored tree, whose original upstream revision is unknown."""
from pathlib import Path
import hashlib, json, re, sys

def source_hashes(source):
    return {str(p.relative_to(source)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(source.rglob('*')) if p.is_file()}

def digest(files):
    return hashlib.sha256(json.dumps(files, sort_keys=True, separators=(',', ':')).encode()).hexdigest()

if __name__ == '__main__':
    source, header = map(Path, sys.argv[1:])
    expected = re.search(r'#define HEARTH_WHISPER_REVISION "([0-9a-f]{64})"', header.read_text()).group(1)
    actual = digest(source_hashes(source))
    if actual != expected:
        raise SystemExit(f'Vendored Whisper source changed: expected {expected}, got {actual}')
    print('Verified Whisper source tree:', actual)
