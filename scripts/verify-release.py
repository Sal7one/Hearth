#!/usr/bin/env python3
"""Check standalone APK contents, native dependencies and vendored runtime hashes."""
from pathlib import Path
import hashlib, json, os, re, subprocess, tempfile, zipfile
root = Path(__file__).resolve().parents[1]
libroot = root / 'common-jni/src/main/jniLibs'
for line in (libroot / 'SHA256SUMS').read_text().splitlines():
    expected, name = line.split(None, 1)
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

expected_libs = {'libtransiber_translation.so','libcommon_jni.so','libc++_shared.so','libvosk.so','libonnxruntime.so','libhearth_qwen.so','libhearth_nemotron.so','libandroidx.graphics.path.so','libdatastore_shared_counter.so'}
platform = {'liblog.so','libandroid.so','libjnigraphics.so','libm.so','libdl.so','libc.so','libz.so'}
for flavor in ['play','foss']:
    flavor_libs = expected_libs | ({"libtranslate_jni.so"} if flavor == "play" else set())
    apk = root/f'app/build/outputs/apk/{flavor}/qa/app-{flavor}-qa.apk'
    assert apk.is_file(), f'Build {flavor} QA first'
    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory() as tmp:
        native = [n for n in archive.namelist() if n.startswith('lib/') and n.endswith('.so')]
        assert {Path(n).name for n in native} == flavor_libs, native
        assert all(n.startswith('lib/arm64-v8a/') for n in native)
        for name in native:
            file = Path(tmp)/Path(name).name
            file.write_bytes(archive.read(name))
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

        assert 'assets/licenses/speech/runtime-build.json' in archive.namelist()
        assert 'assets/licenses/translation/runtime-build.json' in archive.namelist()
        assert 'assets/licenses/vosk/COPYING' in archive.namelist()
    permissions = subprocess.check_output([aapt, 'dump', 'permissions', apk], text=True)
    assert ('android.permission.INTERNET' in permissions) == (flavor == 'play')
    assert 'android.permission.CAMERA' not in permissions
    assert 'android.permission.READ_MEDIA_VIDEO' not in permissions
    badging = subprocess.check_output([aapt, 'dump', 'badging', apk], text=True)
    assert "name='com.sal7one.transiber.qa'" in badging
    print(f'{flavor}: {apk.stat().st_size} bytes; speech and translation libraries plus two AndroidX libraries; 16KB aligned; permissions PASS')
print('Standalone artifact verification PASS')
