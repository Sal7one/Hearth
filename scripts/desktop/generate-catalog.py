#!/usr/bin/env python3
"""Derive desktop pins from the Android runtime catalogs and reviewed model cards."""
import argparse
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / 'desktop-data/src/main/resources/desktop-models.json'




def kotlin_calls(text, name):
    """Yield the argument text of each name(...) call, respecting strings and nesting."""
    import re
    for match in re.finditer(rf'{name}\s*\(', text):
        start = match.end(); depth = 1; index = start; in_string = False; escaped = False
        while index < len(text):
            ch = text[index]
            if in_string:
                if escaped: escaped = False
                elif ch == '\\': escaped = True
                elif ch == '"': in_string = False
            else:
                if ch == '"': in_string = True
                elif ch == '(': depth += 1
                elif ch == ')':
                    depth -= 1
                    if depth == 0:
                        yield text[start:index]
                        break
            index += 1


def split_kotlin_args(call):
    args = []; depth = 0; in_string = False; escaped = False; start = 0
    for index, ch in enumerate(call):
        if in_string:
            if escaped: escaped = False
            elif ch == '\\': escaped = True
            elif ch == '"': in_string = False
        else:
            if ch == '"': in_string = True
            elif ch in '([{': depth += 1
            elif ch in ')]}': depth -= 1
            elif ch == ',' and depth == 0:
                args.append(call[start:index].strip()); start = index + 1
    args.append(call[start:].strip())
    return [arg for arg in args if arg]


def kotlin_string(arg):
    import re
    match = re.fullmatch(r'"(.*)"', arg, re.S)
    return match.group(1) if match else arg.strip()


def kotlin_set(arg):
    import re
    return set(re.findall(r'"([^"]+)"', arg))


def kotlin_ct2_models():
    """Read the reviewed CT2 pins from the Android catalog; multi-component cards."""
    import re
    text = (ROOT / 'common-jni/src/main/java/com/sal7one/common_jni/translation/Ct2Catalog.kt').read_text(encoding='utf-8')
    models = []
    for call in kotlin_calls(text, 'Ct2ModelSpec'):
        args = split_kotlin_args(call)
        if len(args) < 8 or not args[0].lstrip().startswith('"'):
            continue
        model_id = kotlin_string(args[0]); label = kotlin_string(args[1])
        mode = int(args[2].removesuffix('L'))
        sources = kotlin_set(args[3]); targets = kotlin_set(args[4])
        files = []
        for file_call in kotlin_calls(args[5], 'Ct2File'):
            parts = split_kotlin_args(file_call)
            if len(parts) != 5:
                continue
            name = kotlin_string(parts[0]); size = int(parts[1].removesuffix('L'))
            digest = kotlin_string(parts[2]); repo = kotlin_string(parts[3]); revision = kotlin_string(parts[4])
            files.append(dict(filename=name, bytes=size, sha256=digest,
                              url=f'https://huggingface.co/{repo}/resolve/{revision}/{name}'))
        license_name = kotlin_string(args[6]); card = kotlin_string(args[7])
        named = {arg.split('=', 1)[0].strip(): arg.split('=', 1)[1].strip() for arg in args[8:] if '=' in arg}
        if named.get('downloadEnabled', 'true') == 'false':
            continue
        if not files or len(files) < 2:
            continue
        models.append(dict(id=model_id, name=label, family='ct2', stage='TRANSLATION', engineId='marian-local',
            languages=sorted(sources | targets), source=card, license=license_name,
            note=f'Pinned CTranslate2 package ({len(files)} components); CPU-only, macOS Apple silicon. Component hashes are verified at install and before translation.',
            options={'profile': str(mode), 'backend': 'ct2', 'family': 'ct2',
                     'sources': ','.join(sorted(sources)), 'targets': ','.join(sorted(targets))},
            artifacts=files, platforms=['macos-arm64']))
    assert len(models) == 3, [m['id'] for m in models]
    return models


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
    models.extend(kotlin_ct2_models())
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
    # Qwen3-ASR desktop package: engine-owned file pins plus the audited release archive.
    qwen_source = (ROOT / 'desktop-runtime/src/main/kotlin/com/sal7one/hearth/desktop/runtime/QwenSpeechModel.kt').read_text(encoding='utf-8')
    qwen_files = []
    for path, size, digest in re.findall(r'PinnedFile\("([^"]+)",\s*([0-9_]+)L,\s*"([a-f0-9]{64})"\)', qwen_source):
        qwen_files.append((path, int(size.replace('_', '')), digest))
    assert len(qwen_files) == 6, qwen_files
    language_block = re.search(r'val languages: Set<String> = setOf\((.*?)\)', qwen_source, re.S).group(1)
    qwen_languages = re.findall(r'"([a-z]+)"', language_block)
    assert len(qwen_languages) == 30, qwen_languages
    audit = json.loads((ROOT / 'docs/model-catalog-audit-2026-10-19.json').read_text(encoding='utf-8'))
    archive = next(a for a in audit['artifacts'] if a['id'] == 'qwen3-asr-0.6b')
    assert archive['bytes'] > 0 and re.fullmatch(r'[a-f0-9]{64}', archive['sha256']), 'Qwen3-ASR archive pin is missing'
    models.append(dict(id='qwen3-asr-0.6b', name='Qwen3-ASR 0.6B · int8', family='Qwen3-ASR', stage='STT', engineId='qwen3-asr-local',
        languages=qwen_languages, source='https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models', license='Apache-2.0',
        note='Pinned sherpa-onnx Qwen3-ASR package; CPU-only, utterance-windowed, macOS Apple silicon. Six model files are verified individually before loading; the archive hash is the audited release pin.',
        options={}, platforms=['macos-arm64'],
        artifacts=[dict(filename='sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2', bytes=archive['bytes'], sha256=archive['sha256'], url=archive['url'])],
        archive=dict(format='tar.bz2', root='sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25',
            expandedBytes=sum(size for _, size, _ in qwen_files),
            files=[dict(path=path, bytes=size, sha256=digest) for path, size, digest in qwen_files])))
    moonshine = next(a for a in audit['artifacts'] if a['id'] == 'moonshine-tiny-en-v2')
    moonshine_files = [
        ('tokens.txt', 549350, '2870d843e14c1e187bf1913a521562a63b53933814bd7f2145120468f494a049'),
        ('encoder_model.ort', 13281600, '94e90a4654fc45cdfedb77c4c08e1739f48862998e58fada384b25118134f221'),
        ('decoder_model_merged.ort', 30412256, 'cf524c4862d36e9e5ab032eddc73637efd822d70e868ac575cf1a46e1e4708a0'),
        ('LICENSE', 13344, '6148d7574a6554b7379b633cfd4c4fe5840c3f548d13bc83e00b52dc6fa00abd'),
    ]
    models.append(dict(id='moonshine-tiny-en-v2', name='Moonshine tiny.en · q8', family='Moonshine',
        stage='STT', engineId='moonshine-local', languages=['en'],
        source='https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models', license='MIT',
        note='Pinned sherpa-onnx Moonshine package; CPU-only, macOS Apple silicon. The archive and each model file are verified before loading.',
        options={}, platforms=['macos-arm64'],
        artifacts=[dict(filename=moonshine['url'].rsplit('/', 1)[-1], bytes=moonshine['bytes'], sha256=moonshine['sha256'], url=moonshine['url'])],
        archive=dict(format='tar.bz2', root='sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27',
            expandedBytes=sum(size for _, size, _ in moonshine_files),
            files=[dict(path=path, bytes=size, sha256=digest) for path, size, digest in moonshine_files])))
    transcribepsy = next(a for a in audit['artifacts'] if a['id'] == 'transcribepsy-250m-eu-sub-q8')
    models.append(dict(id='transcribepsy-250m-eu-sub-q8', name='TranscribePsy 250M · EU (q8)', family='TranscribePsy',
        stage='STT', engineId='transcribepsy-local',
        languages=['en', 'nl', 'fr', 'de', 'it', 'pl', 'pt', 'es'],
        source='https://huggingface.co/qvac/TranscribePsy-250M-EU-sub-gguf', license='Apache-2.0',
        note='Pinned TranscribePsy 250M EU q8 GGUF; CPU-only, macOS Apple silicon. The single GGUF hash is verified before loading.',
        options={}, platforms=['macos-arm64'],
        artifacts=[dict(filename=transcribepsy['url'].rsplit('/', 1)[-1], bytes=transcribepsy['bytes'], sha256=transcribepsy['sha256'], url=transcribepsy['url'])]))
    nemotron = next(a for a in audit['artifacts'] if a['id'] == 'nemotron-3.5-asr-0.6b')
    models.append(dict(id='nemotron-3.5-asr-0.6b', name='Nemotron 3.5 ASR · 0.6B (q8)', family='Nemotron 3.5',
        stage='STT', engineId='nemotron-asr-local',
        languages=['en', 'es', 'fr', 'it', 'pt', 'nl', 'de', 'tr', 'ru', 'ar', 'hi', 'ja', 'ko', 'vi', 'uk',
                   'pl', 'sv', 'cs', 'nb', 'da', 'bg', 'fi', 'hr', 'sk', 'zh', 'hu', 'ro', 'et'],
        source='https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b', license=nemotron['license'],
        note='Pinned Nemotron 3.5 ASR streaming q8 GGUF; CPU-only, macOS Apple silicon. The single GGUF hash is verified before loading.',
        options={}, platforms=['macos-arm64'],
        artifacts=[dict(filename=nemotron['url'].rsplit('/', 1)[-1], bytes=nemotron['bytes'], sha256=nemotron['sha256'], url=nemotron['url'])]))
    assert len(models) == 49, len(models)
    return json.dumps({'schemaVersion':1,'provenance':'Generated from existing Android pins, the audited Qwen3-ASR release archive, reviewed CT2 catalog pins and reviewed desktop engine pins; run scripts/desktop/generate-catalog.py --check','models':models},ensure_ascii=False,indent=2)+'\n'


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
