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


VOSK_MODELS = [
    dict(checkpoint='vosk-model-small-en-us-0.15', label='US English Small 0.15', language='en',
         zip_bytes=41205931, zip_sha256='30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498',
         expanded_bytes=70898967, tree_sha256='1855931c4d84fa0f731a5a7906c1356837335d49ee7f2ca6a6a32389bac7f8e8'),
    dict(checkpoint='vosk-model-small-de-0.15', label='German Small 0.15', language='de',
         zip_bytes=46499967, zip_sha256='b7e53c90b1f0a38456f4cd62b366ecd58803cd97cd42b06438e2c131713d5e43',
         expanded_bytes=95621761, tree_sha256='b8a470cd032c88ea1bad4b173071a30c014ffa345f45a41499747fdac69a8743'),
    dict(checkpoint='vosk-model-small-fr-0.22', label='French Small 0.22', language='fr',
         zip_bytes=42233323, zip_sha256='cabf6180e177eb9b3a9a9d43a437bd5e549f3a7d09525e5d69a3fed787be12ad',
         expanded_bytes=68483807, tree_sha256='42427727ea015131d1f3f54c21bfeb772945b8682a87b6165dc61d9aa3d4d3c4'),
    dict(checkpoint='vosk-model-small-es-0.42', label='Spanish Small 0.42', language='es',
         zip_bytes=39817833, zip_sha256='09b239888f633ef2f0b4e09736e3d9936acfd810bc65d53fad45261762c6511f',
         expanded_bytes=60286598, tree_sha256='aaf9b97e2b0efadfdbaabb97900e620884908a554fd1f5072339d3f1458ad129'),
    dict(checkpoint='vosk-model-small-ru-0.22', label='Russian Small 0.22', language='ru',
         zip_bytes=46236750, zip_sha256='961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4',
         expanded_bytes=91289240, tree_sha256='4f1bd9e45f0a1c1fd669b6cb75ae1bcded09ed9e22de60247bb57a40814f0f07'),
    dict(checkpoint='vosk-model-small-cn-0.22', label='Chinese Small 0.22', language='zh',
         zip_bytes=43898754, zip_sha256='3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba',
         expanded_bytes=68292271, tree_sha256='ebbc20212be98c8cf0c5076915ebf2854106a7bf5ca376b11110f28b042e4555'),
]

def vosk_models(models):
    for pin in VOSK_MODELS:
        models.append(dict(id=pin['checkpoint'], name=f"Vosk · {pin['label']}", family='Vosk', stage='STT',
            engineId='vosk-local', languages=[pin['language']],
            source='https://alphacephei.com/vosk/models', license='Apache-2.0',
            note='Pinned Vosk Kaldi model; the ZIP is hash-verified before extraction and the expanded model-tree SHA-256 is verified before loading. One desktop engine id (vosk-local) serves all six models.',
            options={'treeSha256': pin['tree_sha256']}, platforms=['macos-arm64'],
            artifacts=[dict(filename=f"{pin['checkpoint']}.zip", bytes=pin['zip_bytes'], sha256=pin['zip_sha256'],
                url=f"https://alphacephei.com/vosk/models/{pin['checkpoint']}.zip")],
            archive=dict(format='zip', root=pin['checkpoint'], expandedBytes=pin['expanded_bytes'], treeSha256=pin['tree_sha256'])))

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
    moss_source = (ROOT / 'hearth-tts-moss/src/main/java/com/sal7one/hearth/tts/moss/MossCatalog.kt').read_text(encoding='utf-8')
    moss_assets = re.findall(r'MossAsset\("([^"]+)",\s*(\d+),\s*"([a-f0-9]{64})",\s*"([^"]+)"\)', moss_source)
    assert len(moss_assets) == 14, len(moss_assets)
    models.append(dict(id='moss-tts-nano', name='MOSS-TTS Nano 100M', family='MOSS', stage='TTS', engineId='moss-tts-local',
        languages=['ar', 'en'], source='https://huggingface.co/OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX', license='Apache-2.0',
        note='Pinned MOSS-TTS Nano 100M ONNX package; CPU-only, 48 kHz mono, macOS Apple silicon. Every component is verified before loading; the default voice is Ava.',
        options={'voice': 'Ava', 'threads': '2'}, platforms=['macos-arm64'],
        artifacts=[dict(filename=name, bytes=int(size), sha256=digest, url=url) for name, size, digest, url in moss_assets]))
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
    omnilingual = next(a for a in audit['artifacts'] if a['id'] == 'omnilingual-ctc-300m-v2-int8')
    omnilingual_files = [
        ('model.int8.onnx', 365841453, 'e3042b2f3b3ef0af2211bf99d2b4bf94a21f5ac0e9898827e7dd6d003a860e91'),
        ('tokens.txt', 90630, '7d99997ef207ff14c2cfe825f2aa037528ea250113cc3c6392bfe49326884ba6'),
    ]
    models.append(dict(id='omnilingual-ctc-300m-v2-int8', name='Omnilingual CTC 300M · int8', family='Omnilingual CTC',
        stage='STT', engineId='omnilingual-local', languages=['en', 'ar', 'ru', 'zh'],
        source='https://huggingface.co/facebook/omniASR-CTC-300M', license=omnilingual['license'],
        note='Pinned sherpa-onnx Omnilingual CTC 300M package; CPU-only, utterance-windowed, macOS Apple silicon. '
             'The archive and both model files are verified before loading. CTC recognition; the source language '
             'labels captions for translation and is never a decoder hint.',
        options={}, platforms=['macos-arm64'],
        artifacts=[dict(filename=omnilingual['url'].rsplit('/', 1)[-1], bytes=omnilingual['bytes'], sha256=omnilingual['sha256'], url=omnilingual['url'])],
        archive=dict(format='tar.bz2', root='sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05',
            expandedBytes=sum(size for _, size, _ in omnilingual_files),
            files=[dict(path=path, bytes=size, sha256=digest) for path, size, digest in omnilingual_files])))
    phonon = next(a for a in audit['artifacts'] if a['id'] == 'phonon-2')
    phonon_files = [
        ('tokens.txt', 93939, 'd58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d'),
        ('encoder.int8.onnx', 611893758, '953ae3bd0b635e4d0929e53cdc1d4757629099b99dee9b88e0812cb6ee6f670a'),
        ('decoder.int8.onnx', 12257659, 'd92e2d8fc9c99ef9faf0a2cf1728af4ec08c0a83b99ece29a46037c4a5a757bf'),
        ('joiner.int8.onnx', 5281492, 'b848103db33b89be7167d218587ea59e772e40ba6ba1cd787230e7e754839633'),
    ]
    models.append(dict(id='phonon-2', name='Phonon 2 · int8', family='Phonon 2',
        stage='STT', engineId='phonon-local', languages=['en'],
        source='https://huggingface.co/FermionResearch/Phonon-2', license=phonon['license'],
        note='Pinned Phonon 2 (NeMo TDT transducer) int8 package; CPU-only, utterance-windowed, macOS Apple silicon. '
             'The archive and all four files are verified before loading. English only; the joiner is passed to the '
             'backend conv_frontend slot.',
        options={}, platforms=['macos-arm64'],
        artifacts=[dict(filename=phonon['url'].rsplit('/', 1)[-1], bytes=phonon['bytes'], sha256=phonon['sha256'], url=phonon['url'])],
        archive=dict(format='tar.bz2', root='phonon-2-hearth-int8',
            expandedBytes=sum(size for _, size, _ in phonon_files),
            files=[dict(path=path, bytes=size, sha256=digest) for path, size, digest in phonon_files])))
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
    vosk_models(models)
    assert len(models) == 58, len(models)
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
