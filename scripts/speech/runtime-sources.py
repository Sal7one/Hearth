#!/usr/bin/env python3
"""One exact source snapshot shared by speech build and publication checks."""
import hashlib
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]

def snapshot():
    files = list((REPO / "common-jni/src/main/cpp/speech/backends").glob("*"))
    files += list((REPO / "scripts/speech").glob("*.cmake"))
    files += [REPO / "scripts/speech" / name for name in ("build-runtimes.sh", "runtime-sources.py")]
    files += [REPO / "common-jni/src/main/cpp/common/opencl_loader.h"]
    files += [REPO / "scripts/translation" / name for name in ("generate-opencl-shim.py", "patch-opencl-backend.py")]
    files += [REPO / "common-jni/src/main/cpp/speech" / name for name in (
        "backend_abi.h", "backend_versions.h", "utterance_segmenter.h", "qwen_language.h",
        "endpoint_budget.h", "source_language.h")]
    return {str(p.relative_to(REPO)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(files) if p.is_file()}

if __name__ == "__main__":
    print(json.dumps(snapshot(), indent=2))
