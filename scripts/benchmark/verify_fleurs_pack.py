#!/usr/bin/env python3
"""Offline audit of the checked-in FLEURS quick set against pinned test.tsv files."""
import hashlib
import json
import pathlib
import runpy
import sys
import wave

ROOT = pathlib.Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'app/src/main/assets/benchmark'
CACHE = ROOT / 'research/benchmark-data'
GENERATOR = runpy.run_path(str(ROOT / 'scripts/benchmark/make_fleurs_pack.py'))


def digest(data):
    return hashlib.sha256(data).hexdigest()


def verify():
    manifest = json.loads((ASSETS / 'benchmark-suite.json').read_text())
    assert manifest['revision'] == GENERATOR['REVISION'], 'Publisher revision changed'
    quick = manifest['quickSentenceIds']
    assert len(quick) == 6 and len(set(quick)) == 6, 'Quick IDs must contain six distinct sentences'
    assert manifest['warmupSentenceId'] not in quick, 'Warmup is a scored sample'
    metadata = {}
    for code in GENERATOR['LANGUAGES']:
        tsv = CACHE / f'{code}-test.tsv'
        if not tsv.exists():
            raise FileNotFoundError(f'{tsv} missing; run make_fleurs_pack.py --text-only to cache pinned metadata')
        rows, actual_hash = GENERATOR['metadata'](code, CACHE)
        assert manifest['metadataSha256'][code] == actual_hash, f'{code}: test.tsv digest mismatch'
        metadata[code] = rows
        assert all(identity in rows for identity in quick), f'{code}: quick sentence absent from test split'

    counts = {'speech': {code: 0 for code in metadata}, 'translation': {}}
    for case in manifest['cases']:
        source = case['source']
        identity = case['publisherSentenceId']
        assert source in metadata and identity in metadata[source], f'{case["id"]}: missing publisher sentence'
        row = metadata[source][identity]
        assert case['referenceStatus'] == 'publisher', f'{case["id"]}: unsupported reference provenance'
        if case.get('audio'):
            assert case['reference'] == row['reference'], f'{case["id"]}: transcription differs from publisher TSV'
            assert case['publisherFile'] == row['name'], f'{case["id"]}: wrong publisher recording'
            expected_path = f'audio/{source}/{identity}.wav'
            assert case['audio'] == expected_path, f'{case["id"]}: unexpected audio path'
            path = ASSETS / expected_path
            data = path.read_bytes()
            assert digest(data) == case['sha256'], f'{case["id"]}: WAV digest mismatch'
            with wave.open(str(path), 'rb') as wav:
                assert (wav.getnchannels(), wav.getsampwidth(), wav.getframerate(), wav.getcomptype()) == (1, 2, 16000, 'NONE'), f'{case["id"]}: invalid WAV format'
                assert 0.5 <= wav.getnframes() / 16000 <= 30, f'{case["id"]}: invalid clip length'
                pcm = wav.readframes(wav.getnframes())
            assert digest(pcm) == case['pcmSha256'], f'{case["id"]}: PCM digest mismatch'
            counts['speech'][source] += 1
        else:
            target = case['target']
            assert target in metadata and identity in metadata[target], f'{case["id"]}: unaligned target'
            assert case['text'] == row['text'], f'{case["id"]}: source text differs from publisher TSV'
            assert case['reference'] == metadata[target][identity]['text'], f'{case["id"]}: translation reference differs from publisher TSV'
            counts['translation'][(source, target)] = counts['translation'].get((source, target), 0) + 1
    assert counts['speech'] == {code: 7 for code in metadata}, 'Expected six scored WAVs and one warmup per language'
    assert len(counts['translation']) == 6 and all(count == 6 for count in counts['translation'].values()), 'Translation directions are incomplete'
    assert len(manifest['cases']) == 64, 'Unexpected checked-in case count'
    print('Verified 28 WAV/PCM hashes and transcripts, 36 aligned translation references, four pinned test.tsv hashes, and quick-set coverage.')


if __name__ == '__main__':
    try:
        verify()
    except (AssertionError, FileNotFoundError) as error:
        print(f'FLEURS pack verification failed: {error}', file=sys.stderr)
        sys.exit(1)
