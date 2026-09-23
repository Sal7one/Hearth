#!/usr/bin/env python3
"""Build a small attributed test pack from pinned FLEURS test recordings (never weights).
Downloads are explicit and cached under ignored research/benchmark-data. Only selected
recordings enter the pack. Requires ffmpeg; no Python dataset/ML dependency.
"""
import argparse, concurrent.futures, csv, hashlib, io, json, pathlib, subprocess, tarfile, tempfile, urllib.request, wave, zipfile
REVISION = '70bb2e84b976b7e960aa89f1c648e09c59f894dd'
BASE = f'https://huggingface.co/datasets/google/fleurs/resolve/{REVISION}/data'
LANGUAGES = {'en':'en_us','ar':'ar_eg','ru':'ru_ru','zh':'cmn_hans_cn'}
PAIRS = [('ar','en'),('en','ar'),('ru','ar'),('ru','en'),('zh','ar'),('zh','en')]
REPO = pathlib.Path(__file__).resolve().parents[2]
def sha(data): return hashlib.sha256(data).hexdigest()
def read_url(url):
    with urllib.request.urlopen(url,timeout=180) as response: return response.read()
def metadata(code,cache):
    p=cache/f'{code}-test.tsv'
    if not p.exists(): p.write_bytes(read_url(f'{BASE}/{LANGUAGES[code]}/test.tsv'))
    rows={}
    for row in csv.reader(io.StringIO(p.read_text()),delimiter='\t',quoting=csv.QUOTE_NONE):
        if len(row)!=7: raise ValueError('Unexpected FLEURS TSV schema')
        identity,name,text,transcript,_,samples,gender=row
        duration=int(samples)/16000
        if 1 <= duration <= 18 and len(text)<=500 and len(transcript)<=500:
            current=rows.get(identity)
            # One short recording per sentence; deterministic filename tie break.
            if current is None or (duration,name)<(current['duration'],current['name']):
                rows[identity]={'name':name,'text':text,'reference':transcript,'duration':duration,'gender':gender}
    return rows,sha(p.read_bytes())
def audio(code,rows,selected,cache,out):
    wanted={rows[i]['name']:i for i in selected}
    destination=out/'audio'/code; destination.mkdir(parents=True,exist_ok=True)
    missing={name:i for name,i in wanted.items() if not (destination/f'{i}.wav').exists()}
    if missing:
        # Stream the publisher archive rather than copying/extracting unrelated files.
        print(f'{code}: streaming test archive for {len(missing)} selected clips',flush=True)
        with urllib.request.urlopen(f'{BASE}/{LANGUAGES[code]}/audio/test.tar.gz',timeout=180) as response:
            with tarfile.open(fileobj=response,mode='r|gz') as archive:
                for item in archive:
                    name=pathlib.PurePosixPath(item.name).name
                    if name not in missing: continue
                    if not item.isfile() or item.size>12*1024*1024: raise ValueError('Unexpected audio member')
                    identity=missing.pop(name)
                    raw=archive.extractfile(item).read()
                    with tempfile.NamedTemporaryFile(suffix='.wav') as source:
                        source.write(raw); source.flush()
                        subprocess.run(['ffmpeg','-v','error','-y','-i',source.name,'-ac','1','-ar','16000','-c:a','pcm_s16le','-map_metadata','-1',str(destination/f'{identity}.wav')],check=True)
                    if not missing: break
        if missing: raise ValueError(f'Missing publisher recordings: {missing}')
    result=[]
    for identity in selected:
        p=destination/f'{identity}.wav'
        with wave.open(str(p),'rb') as wav:
            assert (wav.getnchannels(),wav.getsampwidth(),wav.getframerate())==(1,2,16000)
            pcm=wav.readframes(wav.getnframes())
        row=rows[identity]
        result.append({'id':f'fleurs-{code}-{identity}','source':code,'target':'','text':'','reference':row['reference'],
                       'referenceStatus':'publisher','audio':f'audio/{code}/{identity}.wav','sha256':sha(p.read_bytes()),
                       'pcmSha256':sha(pcm),'publisherSentenceId':identity,'publisherFile':row['name']})
    print(f'{code}: complete',flush=True)
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=pathlib.Path,default=pathlib.Path('/tmp/hearth-benchmark-pack'))
    parser.add_argument('--text-only',action='store_true')
    parser.add_argument('--quick-only',action='store_true',help='package the six shared short clips plus the warm-up clip')
    args=parser.parse_args(); cache=REPO/'research/benchmark-data'; cache.mkdir(parents=True,exist_ok=True)
    args.output.mkdir(parents=True,exist_ok=True)
    all_rows={}; metadata_hashes={}
    for code in LANGUAGES:
        all_rows[code],metadata_hashes[code]=metadata(code,cache)
    shared=set.intersection(*(set(rows) for rows in all_rows.values()))
    ordered=sorted(shared,key=lambda identity:sha(('hearth-fleurs-v1:'+identity).encode()))
    quick=[i for i in ordered if max(all_rows[c][i]['duration'] for c in LANGUAGES)<=9][:6]
    selected=quick+[i for i in ordered if i not in quick][:25] # 30 scored + one distinct warmup
    assert len(quick)==6 and len(selected)==31
    cases=[]
    speech_ids = quick + [selected[30]] if args.quick_only else selected
    translation_ids = quick if args.quick_only else selected
    if not args.text_only:
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            for records in pool.map(lambda c:audio(c,all_rows[c],speech_ids,cache,args.output),LANGUAGES): cases.extend(records)
    for source,target in PAIRS:
        for identity in translation_ids:
            cases.append({'id':f'fleurs-{source}-{target}-{identity}','source':source,'target':target,'text':all_rows[source][identity]['text'],
                          'reference':all_rows[target][identity]['text'],'referenceStatus':'publisher','publisherSentenceId':identity})
    manifest={'schemaVersion':1,'id':'hearth-fleurs-1','title':'FLEURS · Arabic, English, Russian, Chinese','revision':REVISION,
              'license':'CC-BY-4.0','sourceUrl':'https://huggingface.co/datasets/google/fleurs','attribution':'FLEURS: Few-shot Learning Evaluation of Universal Representations of Speech, Conneau et al. (2022). Google.',
              'metadataSha256':metadata_hashes,'quickSentenceIds':quick,'fullSentenceIds':quick if args.quick_only else selected[:30],
              'warmupSentenceId':selected[30],'cases':cases}
    (args.output/'benchmark-suite.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    (args.output/'LICENSE.txt').write_text('Selected FLEURS test data: Creative Commons Attribution 4.0 International.\nhttps://creativecommons.org/licenses/by/4.0/\nSource https://huggingface.co/datasets/google/fleurs at '+REVISION+'\nConneau et al. (2022), FLEURS: Few-shot Learning Evaluation of Universal Representations of Speech.\nAudio converted to mono 16 kHz PCM16; text unmodified; subset selected deterministically.\nNo training data. Sentence IDs align references across languages; not an exhaustive language/dialect evaluation.\n')
    path=args.output.with_suffix('.zip')
    with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED) as archive:
        for p in sorted(args.output.rglob('*')):
            if p.is_file(): archive.write(p,p.relative_to(args.output))
    print(json.dumps({'file':str(path),'bytes':path.stat().st_size,'sha256':sha(path.read_bytes()),'cases':len(cases)}),flush=True)
if __name__=='__main__': main()
