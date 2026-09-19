#!/usr/bin/env python3
"""Check standalone APK contents, native dependencies and vendored runtime hashes."""
from pathlib import Path
import argparse, hashlib, json, os, re, subprocess, tempfile, zipfile
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--build-type", choices=["qa", "release"], default="qa")
build_type = parser.parse_args().build_type
root = Path(__file__).resolve().parents[1]
libroot = root / 'common-jni/src/main/jniLibs'
vendor_hashes = {}
for line in (libroot / 'SHA256SUMS').read_text().splitlines():
    expected, name = line.split(None, 1)
    vendor_hashes["lib/" + name] = expected
    assert hashlib.sha256((libroot/name).read_bytes()).hexdigest() == expected, name
sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
if not sdk:
    props = root / 'local.properties'
    if props.exists():
        sdk = next((line.split('=',1)[1] for line in props.read_text().splitlines() if line.startswith('sdk.dir=')), None)
if not sdk:
    raise SystemExit('Set ANDROID_HOME or sdk.dir in local.properties')
sdk = Path(sdk)
readelf = next((sdk/'ndk/27.0.12077973/toolchains/llvm/prebuilt').glob('*/bin/llvm-readelf'))
aapt = sorted((sdk/'build-tools').glob('*/aapt2'))[-1]
record = json.loads((root/'common-jni/src/main/assets/licenses/translation/runtime-build.json').read_text())
assert record['binary']['sha256'] == hashlib.sha256((libroot/'arm64-v8a/libtransiber_translation.so').read_bytes()).hexdigest()
for name, digest in record['sources'].items():
    assert hashlib.sha256((root/name).read_bytes()).hexdigest() == digest, f'Translation runtime must be rebuilt: {name}'

speech = json.loads((root/'common-jni/src/main/assets/licenses/speech/runtime-build.json').read_text())
for name, digest in speech['adapterSources'].items():
    assert hashlib.sha256((root/name).read_bytes()).hexdigest() == digest, f'Speech runtime must be rebuilt: {name}'
pins = dict(re.findall(r'#define\s+(HEARTH_\w+_REVISION)\s+"([0-9a-f]+)"', (root/'common-jni/src/main/cpp/speech/backend_versions.h').read_text()))
assert pins == speech['pins'], 'Speech runtime revision mismatch'
for artifact in speech['artifacts']:
    name = artifact['file']
    binary = libroot/'arm64-v8a'/name
    assert binary.stat().st_size == artifact['bytes'], name
    assert hashlib.sha256(binary.read_bytes()).hexdigest() == artifact['sha256'], name

ocr_assets = root/'common-jni/src/main/assets/ocr'
for entry in json.loads((ocr_assets/'provenance.json').read_text()):
    data = (ocr_assets/f"{entry['id']}.json").read_bytes()
    assert hashlib.sha256(data).hexdigest() == entry['dictionary_sha256'], entry['id']
    assert len(json.loads(data)) == entry['classes'], entry['id']

manga = json.loads((ocr_assets/'manga-provenance.json').read_text())
assert hashlib.sha256((ocr_assets/'manga.json').read_bytes()).hexdigest() == manga['dictionary_sha256']
assert len(json.loads((ocr_assets/'manga.json').read_text())) == manga['classes'] == 6144

expected_libs = {'libtransiber_translation.so','libcommon_jni.so','libc++_shared.so','libvosk.so','libonnxruntime.so','libhearth_qwen.so','libhearth_nemotron.so','libandroidx.graphics.path.so','libdatastore_shared_counter.so','libimage_processing_util_jni.so'}
platform = {'liblog.so','libandroid.so','libjnigraphics.so','libm.so','libdl.so','libc.so','libz.so'}
for flavor in ['play','foss']:
    flavor_libs = expected_libs | ({"libtranslate_jni.so"} if flavor == "play" else set())
    suffix = '-unsigned' if build_type == 'release' else ''
    apk = root/f'app/build/outputs/apk/{flavor}/{build_type}/app-{flavor}-{build_type}{suffix}.apk'
    assert apk.is_file(), f'Build {flavor} {build_type} first'
    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory() as tmp:
        native = [n for n in archive.namelist() if n.startswith('lib/') and n.endswith('.so')]
        assert {Path(n).name for n in native} == flavor_libs, native
        assert all(n.startswith('lib/arm64-v8a/') for n in native)
        for name in native:
            file = Path(tmp)/Path(name).name
            data = archive.read(name)
            if name in vendor_hashes:
                assert hashlib.sha256(data).hexdigest() == vendor_hashes[name], f'Packaged vendor runtime changed: {name}'
            file.write_bytes(data)
            info = subprocess.check_output([readelf, '-lW', file], text=True)
            aligns = [int(line.split()[-1],16) for line in info.splitlines() if line.strip().startswith('LOAD ')]
            assert aligns and min(aligns) >= 16384, f'{name}: page alignment {aligns}'
            dynamic = subprocess.check_output([readelf, '-d', file], text=True)
            needed = set(re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic))
            assert not needed - flavor_libs - platform, (name, needed)
            if file.name == 'libtransiber_translation.so':
                symbols = subprocess.check_output([readelf, '--dyn-syms', '-W', file], text=True)
                exports = [line.split()[-1] for line in symbols.splitlines() if re.search(r'\b(?:GLOBAL|WEAK)\s+DEFAULT\s+\d+\s+', line)]
                assert exports and all(s.startswith('Java_com_sal7one_common_1jni_translation_LocalTranslationNative_') for s in exports), exports
                assert not needed & {'libvosk.so', 'libcommon_jni.so', 'libhearth_qwen.so', 'libhearth_nemotron.so'}, needed
            if file.name == 'libcommon_jni.so':
                assert 'libvosk.so' not in needed, 'Vosk must remain behind its local C API loader: its exported static libc++ conflicts with speech plugins'
                assert 'libc++_shared.so' in needed

        for family in ['speech', 'translation']:
            name = f'assets/licenses/{family}/runtime-build.json'
            assert archive.read(name) == (root/'common-jni/src/main'/name).read_bytes(), name
        for item in ocr_assets.glob('*.json'):
            assert archive.read('assets/ocr/' + item.name) == item.read_bytes(), item.name
        assert 'assets/licenses/ggml-cpu-NOTICES.txt' in archive.namelist()
        assert 'assets/licenses/ocr/NOTICE.txt' in archive.namelist()
        for item in (root/'common-jni/src/main/assets/licenses/voice').iterdir():
            assert archive.read('assets/licenses/voice/' + item.name) == item.read_bytes(), item.name
        assert 'assets/licenses/speech/runtime-build.json' in archive.namelist()
        assert 'assets/licenses/translation/runtime-build.json' in archive.namelist()
        assert 'assets/licenses/vosk/COPYING' in archive.namelist()
    permissions = subprocess.check_output([aapt, 'dump', 'permissions', apk], text=True)
    assert ('android.permission.INTERNET' in permissions) == (flavor == 'play')
    assert ('android.permission.ACCESS_NETWORK_STATE' in permissions) == (flavor == 'play')
    assert 'android.permission.CAMERA' in permissions
    assert 'android.permission.READ_MEDIA_VIDEO' not in permissions
    badging = subprocess.check_output([aapt, 'dump', 'badging', apk], text=True)
    package = "com.sal7one.transiber.qa" if build_type == "qa" else "com.sal7one.transiber"
    assert f"name='{package}'" in badging
    print(f'{flavor} {build_type}: {apk.stat().st_size} bytes; speech, translation and OCR libraries with AndroidX camera utilities; 16KB aligned; permissions PASS')
print('Standalone artifact verification PASS')
