#!/usr/bin/env python3
"""Compare actual CPU/GPU batch and worker-final text; fail on lost words/negation."""
import argparse, json, statistics
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('cpu', type=Path)
parser.add_argument('gpu', type=Path)
parser.add_argument('--contains', action='append', default=[])
args = parser.parse_args()

def read(path):
    entries = [json.loads(line) for line in path.read_text().splitlines() if line.startswith('{')]
    assert len(entries) == 5, f'{path}: expected ready, three batches, and a worker final'
    assert entries[0].get('runtime'), f'{path}: missing runtime'
    texts = [item['result']['text'] for item in entries[1:4]] + [entries[4]['streamFinal']['text']]
    assert len(set(texts)) == 1, f'{path}: repeated decode or worker final differs: {texts}'
    assert all(phrase.casefold() in texts[0].casefold() for phrase in args.contains), f'{path}: lost required phrase'
    return entries, texts[0]

cpu, cpu_text = read(args.cpu)
gpu, gpu_text = read(args.gpu)
assert cpu[0]['runtime'] == 'CPU'
assert gpu[0]['runtime'].startswith('Vulkan GPU: ')
assert cpu_text == gpu_text, f'GPU transcript differs:\nCPU: {cpu_text}\nGPU: {gpu_text}'
print(json.dumps({'text': cpu_text, 'matchingBatchAndFinalTexts': 8,
    'cpuWarmMedianMs': statistics.median(row['batchMs'] for row in cpu[2:4]),
    'gpuWarmMedianMs': statistics.median(row['batchMs'] for row in gpu[2:4]),
    'gpuRuntime': gpu[0]['runtime']}, ensure_ascii=False))
