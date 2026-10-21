#!/usr/bin/env python3
"""Fetch pinned, model-free desktop build dependencies into the ignored build tree."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
LLAMA_PIN = '64e9bceb2c3a856efed96feda784a50947049feb'
SHERPA_PIN = '210f340bcfdfd5b9ad6b24245e77a934d6c28f1b'
ASSETS = {
    'macos-arm64': ('onnxruntime-osx-arm64-1.20.1.tgz', 'b678fc3c2354c771fea4fba420edeccfba205140088334df801e7fc40e83a57a'),
    'macos-x64': ('onnxruntime-osx-x86_64-1.20.1.tgz', '0f73006813af2a1a5d1723ed7dfb694fc629d15037124081bb61b7bf7d99fc78'),
    'windows-x64': ('onnxruntime-win-x64-1.20.1.zip', '78d447051e48bd2e1e778bba378bec4ece11191c9e538cf7b2c4a4565e8f5581'),
}


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024*1024), b''):
            value.update(chunk)
    return value.hexdigest()


def extract(archive, destination):
    total = 0
    def target(name, size):
        nonlocal total
        parts = PurePosixPath(name)
        if '\\' in name or parts.is_absolute() or '..' in parts.parts or ':' in name:
            raise ValueError('Unsafe runtime archive path')
        total += size
        if total > 1024*1024*1024 or size > 512*1024*1024:
            raise ValueError('Runtime archive exceeds bounds')
        output = destination.joinpath(*parts.parts)
        output.parent.mkdir(parents=True, exist_ok=True)
        return output
    if archive.suffix == '.zip':
        with zipfile.ZipFile(archive) as package:
            if len(package.infolist()) > 5000:
                raise ValueError('Too many runtime archive entries')
            for item in package.infolist():
                output = target(item.filename, item.file_size)
                if item.is_dir():
                    output.mkdir(parents=True, exist_ok=True)
                else:
                    with package.open(item) as source, output.open('wb') as sink:
                        shutil.copyfileobj(source, sink)
    else:
        with tarfile.open(archive) as package:
            for index, item in enumerate(package):
                if index > 5000:
                    raise ValueError('Too many runtime archive entries')
                output = target(item.name, item.size)
                if item.isdir():
                    output.mkdir(parents=True, exist_ok=True)
                elif item.isfile():
                    with package.extractfile(item) as source, output.open('wb') as sink:
                        shutil.copyfileobj(source, sink)
                    output.chmod(item.mode & 0o755)
                elif not item.issym():
                    raise ValueError('Unsupported runtime archive entry')
                # Generic dylib symlinks are unnecessary: builds use the exact versioned file.


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--github-env',action='store_true')
    args=parser.parse_args()
    system={'Darwin':'macos','Windows':'windows'}.get(platform.system())
    arch={'arm64':'arm64','aarch64':'arm64','x86_64':'x64','amd64':'x64'}.get(platform.machine().lower())
    key=f'{system}-{arch}'
    if key not in ASSETS:
        parser.error(f'Unsupported desktop distribution target: {key}')
    work=ROOT/'build/desktop-dependencies';work.mkdir(parents=True,exist_ok=True)
    llama=work/'llama.cpp'
    if not (llama/'.git').is_dir():
        if llama.exists(): raise RuntimeError('Refusing to overwrite a non-checkout dependency directory')
        subprocess.run(['git','init',str(llama)],check=True)
        subprocess.run(['git','-C',str(llama),'remote','add','origin','https://github.com/ggml-org/llama.cpp.git'],check=True)
    status=subprocess.check_output(['git','-C',str(llama),'status','--porcelain'],text=True)
    if status.strip(): raise RuntimeError('Dependency checkout has local changes; not resetting it')
    exists=subprocess.run(['git','-C',str(llama),'cat-file','-e',f'{LLAMA_PIN}^{{commit}}'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0
    if not exists: subprocess.run(['git','-C',str(llama),'fetch','--depth','1','https://github.com/ggml-org/llama.cpp.git',LLAMA_PIN],check=True)
    subprocess.run(['git','-C',str(llama),'checkout','--detach',LLAMA_PIN],check=True)
    sherpa=work/'sherpa-onnx'
    if not (sherpa/'.git').is_dir():
        if sherpa.exists(): raise RuntimeError('Refusing to overwrite a non-checkout sherpa-onnx directory')
        subprocess.run(['git','init',str(sherpa)],check=True)
        subprocess.run(['git','-C',str(sherpa),'remote','add','origin','https://github.com/k2-fsa/sherpa-onnx.git'],check=True)
    status=subprocess.check_output(['git','-C',str(sherpa),'status','--porcelain'],text=True)
    if status.strip(): raise RuntimeError('sherpa-onnx checkout has local changes; not resetting it')
    exists=subprocess.run(['git','-C',str(sherpa),'cat-file','-e',f'{SHERPA_PIN}^{{commit}}'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0
    if not exists: subprocess.run(['git','-C',str(sherpa),'fetch','--depth','1','https://github.com/k2-fsa/sherpa-onnx.git',SHERPA_PIN],check=True)
    subprocess.run(['git','-C',str(sherpa),'checkout','--detach',SHERPA_PIN],check=True)
    name,expected=ASSETS[key];archive=work/name
    if not archive.exists() or digest(archive)!=expected:
        partial=archive.with_suffix(archive.suffix+'.part')
        try:
            request=urllib.request.Request(f'https://github.com/microsoft/onnxruntime/releases/download/v1.20.1/{name}',headers={'User-Agent':'Hearth-desktop-build'})
            with urllib.request.urlopen(request,timeout=60) as source,partial.open('wb') as sink:
                count=0
                while block:=source.read(1024*1024):
                    count+=len(block)
                    if count>100*1024*1024: raise ValueError('ONNX archive exceeds pinned release size bound')
                    sink.write(block)
            if digest(partial)!=expected: raise ValueError('Official ONNX Runtime archive checksum mismatch')
            partial.replace(archive)
        finally: partial.unlink(missing_ok=True)
    ort=work/name.removesuffix('.tgz').removesuffix('.zip')
    # Verify the extracted cache against fresh content from the hash-pinned archive, not just VERSION_NUMBER.
    with tempfile.TemporaryDirectory(prefix='ort-verified-',dir=work) as temporary:
        staged=Path(temporary);extract(archive,staged)
        source=staged/ort.name
        if ort.exists():
            for original in source.rglob('*'):
                if original.is_file():
                    destination=ort/original.relative_to(source)
                    if destination.is_symlink() or not destination.is_file() or digest(destination)!=digest(original):
                        raise ValueError(f'Extracted ONNX Runtime cache differs from its pinned archive: {original.relative_to(source)}')
        else: shutil.move(str(source),str(ort))
    if (ort/'VERSION_NUMBER').read_text().strip()!='1.20.1': raise ValueError('Unexpected ONNX Runtime version')
    paths={'HEARTH_LLAMA_ROOT':str(llama),'HEARTH_SHERPA_ROOT':str(sherpa),'HEARTH_ORT_ROOT':str(ort)}
    if args.github_env:
        with open(os.environ['GITHUB_ENV'],'a',encoding='utf-8') as output:
            for key,value in paths.items(): output.write(f'{key}={value}\n')
    print(json.dumps(paths,indent=2))


if __name__=='__main__': main()
