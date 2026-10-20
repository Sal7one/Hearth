#!/usr/bin/env python3
"""Explicit ignored smoke assets fetch; verifies exact source bytes. Never called by Gradle."""
import argparse, hashlib, json, urllib.request
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--directory',type=Path,default=Path('build/voice-runtimes/models'));args=p.parse_args();args.directory.mkdir(parents=True,exist_ok=True)
assets=[('nvidia/magpie_tts_multilingual_357m','446bff55e1fe3d3aa29c771574e7d2b0fa322990','magpie_tts_multilingual_357m.v2607.f16.gguf',568663392,'fb8e9297138dcc7b5fc51332f361e3cddfd433d4e0bc1d87e16e6aed21f618fc'),('nvidia/nemo-nano-codec-22khz-1.89kbps-21.5fps','fc00890b604aa2de298d2641ffc6c5f6caf8c4d7','nemo_nano_codec_22khz_1.89kbps_21.5fps.decoder.f16.gguf',78823104,'cc86d36d821a27cdc1d4ef600a3e2b0dabe76e88fcc2a8652d9543134c07ef2d')]
for repo,rev,name,size,digest in assets:
 dest=args.directory/name
 if dest.exists() and dest.stat().st_size==size and hashlib.sha256(dest.read_bytes()).hexdigest()==digest:continue
 req=urllib.request.Request(f'https://huggingface.co/{repo}/resolve/{rev}/{name}')
 temporary=dest.with_suffix('.part')
 try:
  total=0;h=hashlib.sha256()
  with urllib.request.urlopen(req,timeout=120) as response,temporary.open('wb') as sink:
   while block:=response.read(1024*1024):
    total+=len(block)
    if total>size:raise ValueError('Oversized model')
    h.update(block);sink.write(block)
  if total!=size or h.hexdigest()!=digest:raise ValueError('Model digest mismatch')
  temporary.replace(dest);print('Verified',name,total,flush=True)
 finally:temporary.unlink(missing_ok=True)
url='https://huggingface.co/nvidia/magpie_tts_multilingual_357m/resolve/446bff55e1fe3d3aa29c771574e7d2b0fa322990/magpie_tts_multilingual_357m.nemo'
ranges=[(0,20619263),(1459750400,1470207999)];dest=args.directory/'tokenizer.ranges';h=hashlib.sha256()
with dest.open('wb') as sink:
 for start,end in ranges:
  req=urllib.request.Request(url,headers={'Range':f'bytes={start}-{end}','Accept-Encoding':'identity'})
  with urllib.request.urlopen(req,timeout=120) as response:
   if response.status!=206 or response.headers['Content-Range']!=f'bytes {start}-{end}/1470208000':raise ValueError('Invalid model-host range response')
   block=response.read(end-start+2)
   if len(block)!=end-start+1:raise ValueError('Incomplete range')
   h.update(block);sink.write(block)
if h.hexdigest()!='169144a64f6d775178696914e5aad8282db44820e73341d5f9a4e7865fac1f26':raise ValueError('Tokenizer ranges digest mismatch')
print('Verified tokenizer.ranges',dest.stat().st_size,flush=True)
# Extract only the pinned tokenizer members from the two authenticated TAR segments.
index=json.loads((Path(__file__).resolve().parents[2]/'hearth-tts-magpie/src/main/assets/magpie-sources.json').read_text())
expected={x['name']:x for x in index['artifacts'][1]['members']};seen=set();output=args.directory/'tokenizer';output.mkdir(exist_ok=True)
with dest.open('rb') as stream:
 boundary=0
 for segment,(start,end) in enumerate(ranges):
  boundary+=end-start+1
  while stream.tell()+512<=boundary:
   header=stream.read(512)
   if not any(header):break
   name=header[:100].split(b'\0')[0].decode().removeprefix('./');size=int(header[124:136].strip(b'\0 ') or b'0',8);kind=header[156:157]
   if segment==0 and name=='model_weights.ckpt':break
   if stream.tell()+((size+511)//512)*512>boundary:raise ValueError('Truncated tokenizer member')
   data=stream.read(size);stream.seek((-size)%512,1)
   if kind in (b'0',b'\0'):
    item=expected[name]
    if len(data)==item['size'] and hashlib.sha256(data).hexdigest()==item['sha256']:
     (output/name).write_bytes(data);seen.add(name)
    # Earlier append-only snapshots are superseded; authenticated full payload pins every byte.
  stream.seek(boundary)
if seen!=set(expected):raise ValueError('Missing tokenizer members')
print('Verified extracted tokenizer members',len(seen),flush=True)
