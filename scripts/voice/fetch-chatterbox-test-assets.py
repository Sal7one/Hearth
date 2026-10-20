#!/usr/bin/env python3
"""Explicit developer real-model smoke assets; never invoked from Gradle or library code."""
import argparse,hashlib,json,urllib.request
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('--directory',type=Path,default=Path('build/voice-runtimes/chatterbox-models'));args=parser.parse_args()
root=Path(__file__).resolve().parents[2]
model=next(x for x in json.loads((root/'docs/handoff/models/tts.json').read_text())['models'] if x['id']=='chatterbox-multilingual-v3')
args.directory.mkdir(parents=True,exist_ok=True)
for asset in [x for x in model['components'] if x['required']]:
 target=args.directory/asset['filename'];temporary=target.with_suffix(target.suffix+'.part');target.parent.mkdir(parents=True,exist_ok=True)
 if target.is_file() and target.stat().st_size==asset['bytes'] and hashlib.file_digest(target.open('rb'),'sha256').hexdigest()==asset['sha256']:continue
 try:
  request=urllib.request.Request(asset['url'],headers={'User-Agent':'Hearth-explicit-native-smoke'})
  with urllib.request.urlopen(request,timeout=120) as source,temporary.open('wb') as sink:
   total=0;digest=hashlib.sha256()
   while True:
    data=source.read(1024*1024)
    if not data:break
    total+=len(data)
    if total>asset['bytes']:raise ValueError('File exceeds pinned bytes')
    digest.update(data);sink.write(data)
  if total!=asset['bytes'] or digest.hexdigest()!=asset['sha256']:raise ValueError(f'Pinned model integrity failed: bytes={total}, sha256={digest.hexdigest()}')
  temporary.replace(target);print('Verified',target.name,total,flush=True)
 finally:temporary.unlink(missing_ok=True)
