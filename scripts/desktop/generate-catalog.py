#!/usr/bin/env python3
"""Derive desktop pins from the Android runtime catalogs and reviewed model cards."""
import argparse
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / 'desktop-data/src/main/resources/desktop-models.json'


def generate():
    models = []
    whisper = (ROOT / 'app/src/main/java/com/sal7one/transiber/models/WhisperDownloads.kt').read_text(encoding="utf-8")
    revision = re.search(r'const val REVISION = "([a-f0-9]{40})"', whisper).group(1)
    for checkpoint, quant, filename, size, digest in re.findall(r'entry\("([^"]+)", "([^"]+)", "([^"]+)", (\d+)L, "([a-f0-9]{64})"', whisper):
        models.append(dict(id=f'whisper-{checkpoint}-{quant.lower()}', name=f'Whisper {checkpoint} · {quant}',
            family='Whisper', stage='STT', engineId='whisper-local', languages=['en'] if checkpoint.endswith('.en') else ['multilingual', 'ar', 'en'],
            source='https://huggingface.co/ggerganov/whisper.cpp', license='MIT',
            note='Pinned Android artifact; desktop uses the same whisper.cpp model format. Quantizations are file options within the Whisper family.', options={},
            artifacts=[dict(filename=filename, bytes=int(size), sha256=digest,
                url=f'https://huggingface.co/ggerganov/whisper.cpp/resolve/{revision}/{filename}')]))
    translations = json.loads((ROOT / 'docs/handoff/models/translation.json').read_text(encoding="utf-8"))
    for model in translations['models']:
        components = [c for c in model.get('components', []) if c.get('filename', '').endswith('.gguf')]
        if len(components) != 1 or len(model['components']) != 1:
            continue
        if model.get('nativePromptMode') not in (0, 1, 2, 3):
            continue
        c = components[0]
        mode = {0:'default', 1:'raw', 2:'euro', 3:'riva'}[model['nativePromptMode']]
        license_info = model.get('license', {})
        models.append(dict(id=model['id'], name=model['label'], family=model['family'], stage='TRANSLATION', engineId='llama-translation-local',
            languages=model.get('languages', ['ar', 'en']), source=model['sources'][0],
            license=license_info.get('name','See source') if isinstance(license_info, dict) else license_info,
            note='Uses the existing Hearth translation prompt protocol. Quality varies by direction; see linked model evidence.',
            options={'promptMode':mode, 'family':model['family']}, artifacts=[{k:c[k] for k in ('filename', 'bytes','sha256','url')}]))
    voice = (ROOT / 'hearth-tts-supertonic/src/main/java/com/sal7one/hearth/tts/supertonic/SupertonicCatalog.kt').read_text(encoding="utf-8")
    revision = re.search(r'const val revision = "([a-f0-9]{40})"', voice).group(1)
    assets = []
    for filename, remote, size, digest in re.findall(r'SupertonicAsset\("[^"]+","([^"]+)","([^"]+)",(\d+),"([a-f0-9]{64})"', voice):
        assets.append(dict(filename=filename, bytes=int(size), sha256=digest,
            url=f'https://huggingface.co/Supertone/supertonic-3/resolve/{revision}/{remote}'))
    assert len(assets) == 16
    models.append(dict(id='supertonic-3',name='Supertonic 3 · ten voices',family='Supertonic',stage='TTS',engineId='supertonic-local',
        languages=['multilingual','ar','en'], source='https://huggingface.co/Supertone/supertonic-3',license='OpenRAIL-M (weights); MIT (runtime)',
        note='Includes verified F1–F5 and M1–M5 voice styles. Desktop CPU adapter; Arabic/English listening still requires owner testing.',
        options={},artifacts=assets))
    assert len(models) >= 29
    return json.dumps({'schemaVersion':1,'provenance':'Generated from existing Android pins; run scripts/desktop/generate-catalog.py --check','models':models},ensure_ascii=False,indent=2)+'\n'


if __name__ == '__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('--check',action='store_true'); args=parser.parse_args()
    content=generate()
    if args.check:
        if not OUTPUT.exists() or OUTPUT.read_text(encoding="utf-8") != content:
            raise SystemExit('Desktop model catalog differs from Android pins; regenerate it.')
        print('Desktop model catalog matches Android pins')
    else:
        OUTPUT.parent.mkdir(parents=True,exist_ok=True); OUTPUT.write_text(content, encoding="utf-8", newline="\n")
        print(f'Wrote {OUTPUT.relative_to(ROOT)}')
