#!/usr/bin/env python3
"""Developer-only pinned Oute fixtures. Four bounded ranges resume without refetching the prefix."""
import concurrent.futures,hashlib,json,os,shutil,threading,time,urllib.request
from pathlib import Path
root=Path(__file__).resolve().parents[2]
work=root/'build/voice-runtimes/oute-test-assets';work.mkdir(parents=True,exist_ok=True)
assets=[('oute-tts-1b.bin',1438416240,'e4e14b573459a8a4958e32e39678d2f0ac61cf73ccadb6dbda1f8fde1f8ade83','https://modelscope.cn/api/v1/models/judd2024/chatllm_quantized_oute-tts/repo?Revision=a1b7a4fc64949c9d6a3206422830df41009f1ad7&FilePath=oute-tts-1b.bin'),('en-female-1-neutral.json',25295,'6bb6bacca60c9759741d849c2baf2af3f6263a51b759d67826ef39e1efc4436c','https://raw.githubusercontent.com/edwko/OuteTTS/f5eac6e70d792844c6a6959d900a47af2c061a5b/outetts/version/v3/default_speakers/json/en-female-1-neutral.json')]
for name,size,digest,url in assets:
 target=work/name
 if target.exists():
  with target.open('rb') as source: assert target.stat().st_size==size and hashlib.file_digest(source,'sha256').hexdigest()==digest
  print(f'{name}: already verified',flush=True);continue
 temporary=target.with_suffix(target.suffix+'.part');state_path=temporary.with_suffix('.ranges.json')
 prefix=temporary.stat().st_size if temporary.exists() else 0
 if size<1024*1024:
  with urllib.request.urlopen(url,timeout=90) as response: payload=response.read(size+1)
  assert len(payload)==size and hashlib.sha256(payload).hexdigest()==digest,'Pinned reference integrity failed'
  temporary.write_bytes(payload)
 else:
  if state_path.exists():
   state=json.loads(state_path.read_text()); assert state['size']==size and state['sha256']==digest
   ranges=state['ranges'];prefix=state['prefix']
  else:
   assert 0<=prefix<=size
   remaining=size-prefix;step=(remaining+3)//4
   ranges=[{'next':start,'end':min(size,start+step)-1} for start in range(prefix,size,step)] if remaining else []
   state={'size':size,'sha256':digest,'prefix':prefix,'ranges':ranges}
   state_path.write_text(json.dumps(state))
  remaining=sum(max(0,item['end']-item['next']+1) for item in ranges)
  assert shutil.disk_usage(work).free>remaining+256*1024*1024,'Insufficient space for Oute smoke fixture'
  print(f'{name}: {remaining} bytes remaining across {len(ranges)} verified HTTP ranges',flush=True)
  lock=threading.Lock();fd=os.open(temporary,os.O_RDWR|os.O_CREAT,0o600)
  def save():
   draft=state_path.with_suffix('.json.tmp');draft.write_text(json.dumps(state));os.replace(draft,state_path)
  def fetch(item):
   for attempt in range(4):
    start=item['next'];end=item['end']
    if start>end:return
    try:
     request=urllib.request.Request(url,headers={'Range':f'bytes={start}-{end}'})
     with urllib.request.urlopen(request,timeout=90) as response:
      assert response.status==206 and response.headers.get('Content-Range')==f'bytes {start}-{end}/{size}','Unexpected HTTP range response'
      if response.headers.get('Content-Length'):assert int(response.headers['Content-Length'])==end-start+1
      offset=start
      while offset<=end:
       block=response.read(min(1024*1024,end-offset+1))
       if not block:raise IOError('Truncated HTTP range')
       assert os.pwrite(fd,block,offset)==len(block);offset+=len(block)
       with lock:item['next']=offset;save()
      assert response.read(1)==b'','HTTP range exceeds pinned size'
     print(f'{name}: range through {end} complete',flush=True);return
    except Exception as error:
     if attempt==3:raise RuntimeError(f'Pinned range failed at {item["next"]}: {type(error).__name__}') from error
     time.sleep(attempt+1)
  try:
   with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:list(pool.map(fetch,ranges))
   os.fsync(fd)
  finally:os.close(fd)
 with temporary.open('rb') as source:assert temporary.stat().st_size==size and hashlib.file_digest(source,'sha256').hexdigest()==digest,'Pinned artifact checksum/size failed'
 os.replace(temporary,target);state_path.unlink(missing_ok=True);print(f'{name}: verified',flush=True)
