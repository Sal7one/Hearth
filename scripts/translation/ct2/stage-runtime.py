#!/usr/bin/env python3
"""Stage a verified CT2 CPU binary and its pinned sources/licenses; no weights."""
from pathlib import Path
import hashlib,json,os,shutil,subprocess,sys
root=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(root/'scripts'))
from native_abis import ABIS,source_set_digest,update_checksums,verify_elf
abi=sys.argv[1] if len(sys.argv)>1 else 'arm64-v8a'
assert abi == "arm64-v8a", "CT2 is validated only for ARM64"
work=root/'build/ct2-runtime';build=work/f'android-{abi}'
cache=dict(line.split('=',1) for line in (build/'CMakeCache.txt').read_text().splitlines() if '=' in line and not line.startswith(('//','#')))
ct2=Path(cache['CT2_SOURCE:UNINITIALIZED'] if 'CT2_SOURCE:UNINITIALIZED' in cache else cache['CT2_SOURCE:PATH'])
spm=Path(cache['SPM_SOURCE:UNINITIALIZED'] if 'SPM_SOURCE:UNINITIALIZED' in cache else cache['SPM_SOURCE:PATH'])
def git(p):return subprocess.check_output(['git','-C',str(p),'rev-parse','HEAD'],text=True).strip()
assert git(ct2)=='8c8cab2a954810216a45d65c1b8d56839c846e5b'
assert git(spm)=='17d7580d6407802f85855d2cc9190634e2c95624'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
ndk=Path(os.environ.get('ANDROID_NDK_HOME',Path.home()/'Library/Android/sdk/ndk/27.0.12077973'))
assert 'Pkg.Revision = 27.0.12077973' in (ndk/'source.properties').read_text()
source=build/'libtransiber_ct2.so';verify_elf(abi,source.read_bytes())
target=root/f'common-jni/src/main/jniLibs/{abi}/libtransiber_ct2.so'
shutil.copy2(source,target)
strip=next(ndk.glob('toolchains/llvm/prebuilt/*/bin/llvm-strip'))
subprocess.run([str(strip),'--strip-unneeded',str(target)],check=True)
licenses=root/'common-jni/src/main/assets/licenses/ct2';licenses.mkdir(parents=True,exist_ok=True)
for name,path in [('CTranslate2',ct2),('SentencePiece',spm)]:
 for item in path.rglob('*'):
  if item.is_file() and '.git' not in item.parts and (item.name.upper().startswith(('LICENSE','COPYING','NOTICE'))):
   destination=licenses/name/item.relative_to(path);destination.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(item,destination)
recordpath=licenses/'runtime-build.json'
previous=json.loads(recordpath.read_text()) if recordpath.exists() else {}
sources={str(p.relative_to(root)):sha(p) for folder in ['common-jni/src/main/cpp/translation/ct2','scripts/translation/ct2'] for p in sorted((root/folder).glob('*')) if p.is_file()}
sources['common-jni/src/main/cpp/common/lease_registry.h']=sha(root/'common-jni/src/main/cpp/common/lease_registry.h')
binaries=previous.get('binaries',{});binaries[abi]={'name':target.name,'bytes':target.stat().st_size,'sha256':sha(target),'sourceSetSha256':source_set_digest(sources)}
record={'ctranslate2Revision':git(ct2),'sentencepieceRevision':git(spm),'submodules':subprocess.check_output(['git','-C',str(ct2),'submodule','status','--recursive'],text=True).strip().splitlines(),'ndk':'27.0.12077973','backend':'CPU Ruy; two threads; one queued batch; QuickMT FLOAT32; SMaLL INT8_FLOAT32; greedy; no GPU','androidPatch':'Optional pthread affinity rejected on Android API28; session never requests pinning. Generated build copy only.','sources':sources,'binaries':binaries}
recordpath.write_text(json.dumps(record,indent=2)+'\n');update_checksums(root/'common-jni/src/main/jniLibs')
print(target,sha(target))
