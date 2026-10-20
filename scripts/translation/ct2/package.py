#!/usr/bin/env python3
"""Create an offline-import ZIP from already-downloaded pinned CT2 assets. Never fetches weights."""
from pathlib import Path
import argparse,hashlib,json,re,zipfile
root=Path(__file__).resolve().parents[3]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('model_id',choices=['ct2-quickmt-en-ar','ct2-quickmt-ar-en','ct2-small100-int8'])
parser.add_argument('directory',type=Path)
parser.add_argument('output',type=Path)
args=parser.parse_args()
# Parse the reviewed machine-readable source inventory; precision does not create a family.
data=json.loads((root/'docs/handoff/models/translation.json').read_text())
if args.model_id.startswith('ct2-quickmt-'):
    selected=next(m for m in data['models'] if m['family']=='quickmt')
    prefix=args.model_id.removeprefix('ct2-quickmt-')+'/'
    components=[dict(c,filename=c['filename'].removeprefix(prefix)) for c in selected['components'] if c['filename'].startswith(prefix)]
else:
    components=next(m for m in data['models'] if m['family']=='small100')['components']
for component in components:
    file=args.directory/component['filename']
    assert file.is_file() and file.stat().st_size==component['bytes'], f'Missing/truncated {file}'
    digest=hashlib.sha256()
    with file.open('rb') as source:
        for block in iter(lambda:source.read(1024*1024),b''):digest.update(block)
    assert digest.hexdigest()==component['sha256'], f'Checksum differs: {file}'
args.output.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(args.output,'x',compression=zipfile.ZIP_STORED) as archive:
    archive.writestr('hearth-translation.json',json.dumps({'schemaVersion':1,'modelId':args.model_id}))
    for component in components:archive.write(args.directory/component['filename'],component['filename'])
print(args.output)
