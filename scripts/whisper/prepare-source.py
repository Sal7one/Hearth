#!/usr/bin/env python3
"""Fail closed when the GPU backend cannot initialize; leave the pinned tree intact."""
from pathlib import Path
import sys
source, target = map(Path, sys.argv[1:])
text = source.read_text()
before = '    if (backend_gpu) {\n        result.push_back(backend_gpu);\n    }'
after = '''    if (params.use_gpu && !backend_gpu) {
        WHISPER_LOG_ERROR("Hearth: explicit GPU request failed; CPU fallback rejected\\n");
        return result;
    }
    if (backend_gpu) {
        result.push_back(backend_gpu);
    }'''
if text.count(before) != 1:
    raise SystemExit('Pinned Whisper GPU initialization changed; review the fail-closed patch')
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(text.replace(before, after))
