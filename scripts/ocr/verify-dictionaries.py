#!/usr/bin/env python3
"""Reproduce packaged OCR dictionaries from pinned publisher configs (requires PyYAML)."""
import hashlib
import json
from pathlib import Path
import urllib.request
import yaml

root = Path(__file__).resolve().parents[2] / 'common-jni/src/main/assets/ocr'
for entry in json.loads((root / 'provenance.json').read_text()):
    url = f"https://huggingface.co/{entry['source']}/resolve/{entry['revision']}/inference.yml"
    with urllib.request.urlopen(url, timeout=60) as response:
        data = response.read(4 * 1024 * 1024 + 1)
    assert len(data) <= 4 * 1024 * 1024, 'Publisher config exceeds bound'
    assert hashlib.sha256(data).hexdigest() == entry['publisher_config_sha256'], entry['id']
    config = yaml.safe_load(data)
    tokens = [''] + config['PostProcess']['character_dict'] + [' ']
    expected = (json.dumps(tokens, ensure_ascii=False, separators=(',', ':')) + '\n').encode()
    assert hashlib.sha256(expected).hexdigest() == entry['dictionary_sha256'], entry['id']
    assert expected == (root / (entry['id'] + '.json')).read_bytes(), entry['id']
    assert len(tokens) == entry['classes'], entry['id']
    print(entry['id'], len(tokens), 'publisher dictionary matches')
