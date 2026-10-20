#!/usr/bin/env python3
"""Check installable APK signatures/identity, contents and native runtime integrity."""
from pathlib import Path
import argparse, hashlib, json, os, re, subprocess, tempfile, zipfile
from native_abis import ABIS, source_set_digest, verify_elf
from apk_delivery import configured_identity, verify_delivery
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--build-type", choices=["qa", "release"], default="qa")
parser.add_argument("--allow-unsigned-for-signing", action="store_true",
                    help="Release-only internal pre-sign checks; unsigned APKs are NOT installable delivery artifacts")
args = parser.parse_args()
build_type = args.build_type
if args.allow_unsigned_for_signing and build_type != "release":
    parser.error("--allow-unsigned-for-signing applies only to internal Release signing preparation")
root = Path(__file__).resolve().parents[1]
expected_identity = configured_identity((root/'app/build.gradle.kts').read_text(), build_type)
libroot = root / 'common-jni/src/main/jniLibs'
phone_assets = root / 'app/src/main/assets/phone-setup'
phone_crypto = json.loads((phone_assets / 'forge-source.json').read_text())
for name, digest in phone_crypto['files'].items():
    assert hashlib.sha256((phone_assets / name).read_bytes()).hexdigest() == digest, f'Phone crypto bundle changed: {name}'
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
apksigner = aapt.with_name('apksigner')
assert apksigner.is_file(), f'Android build-tools apksigner missing: {apksigner}'
record = json.loads((root/'common-jni/src/main/assets/licenses/translation/runtime-build.json').read_text())
assert record['llama_revision'] == '64e9bceb2c3a856efed96feda784a50947049feb', 'Translation source pin changed'
assert set(record['binaries']) == set(ABIS), 'Missing translation ABI'
for abi, binary in record['binaries'].items():
    assert binary['sourceSetSha256'] == source_set_digest(record['sources']), f'Translation {abi} source snapshot is stale'
    assert binary['bytes'] == (libroot/abi/binary['name']).stat().st_size, abi
    assert binary['sha256'] == hashlib.sha256((libroot/abi/binary['name']).read_bytes()).hexdigest(), abi
for name, digest in record['sources'].items():
    assert hashlib.sha256((root/name).read_bytes()).hexdigest() == digest, f'Translation runtime must be rebuilt: {name}'
gpu = record['vulkan']
assert gpu['abi'] == 'arm64-v8a', 'Vulkan runtime must remain optional ARM64 only'
assert gpu['dependencies'] == {'Vulkan-Headers':'19725e4d48082fe78e26622b15d3080ccd54112b', 'SPIRV-Headers':'01e0577914a75a2569c846778c2f93aa8e6feddd'}, 'Unpinned Vulkan headers'
gpu_binary = gpu['binary']
assert gpu_binary['name'] == 'libtransiber_translation_vulkan.so'
assert gpu_binary['sourceSetSha256'] == source_set_digest(record['sources']), 'Vulkan source snapshot is stale'
gpu_path = libroot/gpu['abi']/gpu_binary['name']
assert gpu_path.stat().st_size == gpu_binary['bytes']
assert hashlib.sha256(gpu_path.read_bytes()).hexdigest() == gpu_binary['sha256']

# Each optional runtime carries a separate immutable source snapshot and only ships on ARM64.
gpu_artifacts = {gpu_binary['name']: ('arm64-v8a', True)}
opencl = record['opencl']
assert opencl['abi'] == 'arm64-v8a'
assert opencl['dependencies'] == {'OpenCL-Headers': '30bc20a8e90468e231d7c639805ae61ad1fefa4f'}
opencl_binary = opencl['binary']
assert opencl_binary['name'] == 'libtransiber_translation_opencl.so'
assert opencl_binary['sourceSetSha256'] == source_set_digest(record['sources'])
opencl_path = libroot/opencl['abi']/opencl_binary['name']
assert opencl_path.stat().st_size == opencl_binary['bytes']
assert hashlib.sha256(opencl_path.read_bytes()).hexdigest() == opencl_binary['sha256']
gpu_artifacts[opencl_binary['name']] = ('arm64-v8a', False)

def verify_optional_snapshot(metadata, source_key, expected):
    for name, digest in metadata[source_key].items():
        assert hashlib.sha256((root/name).read_bytes()).hexdigest() == digest, f'GPU source snapshot is stale: {name}'
    artifacts = metadata.get('artifacts') or [dict(metadata['binary'], abi=metadata['abi'])]
    assert {item.get('file', item.get('name')) for item in artifacts} == set(expected)
    for item in artifacts:
        name = item.get('file', item.get('name'))
        assert item['abi'] == 'arm64-v8a', f'{name}: optional GPU runtime must be ARM64 only'
        assert item['sourceSetSha256'] == source_set_digest(metadata[source_key]), f'{name}: stale source digest'
        path = libroot/item['abi']/name
        assert path.stat().st_size == item['bytes']
        assert hashlib.sha256(path.read_bytes()).hexdigest() == item['sha256']
        gpu_artifacts[name] = (item['abi'], expected[name])

speech_gpu = json.loads((root/'common-jni/src/main/assets/licenses/speech/gpu-runtime-build.json').read_text())
assert speech_gpu['abi'] == 2
verify_optional_snapshot(speech_gpu, 'adapterSources', {'libhearth_transcribepsy_vulkan.so': True, 'libhearth_transcribepsy_opencl.so': False})
# Independently built multilingual STT plugins; CPU availability never loads Vulkan.
speech_expansion = json.loads((root/'common-jni/src/main/assets/licenses/speech-expansion/runtime-build.json').read_text())
assert speech_expansion['abi'] == 2
assert speech_expansion['architectures'] == ['arm64-v8a']
expansion_pin = re.search(r'HEARTH_TRANSCRIBE_CPP_REVISION "([a-f0-9]{40})"', (root/'common-jni/src/main/cpp/speech/expansion/versions.h').read_text()).group(1)
assert speech_expansion['sourceRevision'] == expansion_pin == '1dc22facfc931e2f8adb43924b0e38b9c46f83c7'
assert speech_expansion['backends'] == ['cohere_asr', 'funasr_mlt_nano', 'voxtral_realtime']
assert speech_expansion['publicExports'] == ['hearth_cohere_backend_v1', 'hearth_funasr_backend_v1', 'hearth_voxtral_backend_v1']
verify_optional_snapshot(speech_expansion, 'adapterSources', {'libhearth_transcribe_cpp.so': False, 'libhearth_transcribe_cpp_vulkan.so': True})

# Dedicated CPU translation runtime, not a generic ONNX/GGUF format shortcut.
ct2 = json.loads((root/'common-jni/src/main/assets/licenses/ct2/runtime-build.json').read_text())
assert ct2['ctranslate2Revision'] == '8c8cab2a954810216a45d65c1b8d56839c846e5b'
assert ct2['sentencepieceRevision'] == '17d7580d6407802f85855d2cc9190634e2c95624'
assert set(ct2['binaries']) == {'arm64-v8a'}
verify_optional_snapshot(dict(ct2, artifacts=[dict(item, abi=abi) for abi, item in ct2['binaries'].items()]),
                         'sources', {'libtransiber_ct2.so': False})
# Independently packaged Magpie CPU provider; its JNI shim remains available on every app ABI.
magpie_root = root/'hearth-tts-magpie/src/main'
magpie = json.loads((magpie_root/'assets/licenses/magpie/runtime-build.json').read_text())
for path, digest in magpie['sources'].items():
    assert hashlib.sha256((root/path).read_bytes()).hexdigest() == digest, f'Magpie source snapshot stale: {path}'
assert magpie['sourceSetSha256'] == source_set_digest(magpie['sources']), 'Magpie source set changed'
assert magpie['source']['revision'] == 'caa3f47c7c75fa2e9e6340272ebad57c1647dd44'
assert magpie['source']['ggmlRevision'] == 'bd4f514db14d87fded667787a7a963bfbaa98e89'
assert magpie['runtime']['abi'] == 'arm64-v8a' and magpie['runtime']['backend'] == 'CPU'
magpie_binary = magpie_root/'jniLibs/arm64-v8a/libhearth_magpie.so'
assert magpie_binary.stat().st_size == magpie['runtime']['bytes']
assert hashlib.sha256(magpie_binary.read_bytes()).hexdigest() == magpie['runtime']['sha256']
assert hashlib.sha256((root/'scripts/voice/nemo-tts-inject.cmake').read_bytes()).hexdigest() == magpie['build']['injectionSha256']
assert hashlib.sha256((root/'scripts/voice/magpie.map').read_bytes()).hexdigest() == magpie['build']['exportsSha256']
vendor_hashes['lib/arm64-v8a/libhearth_magpie.so'] = magpie['runtime']['sha256']
gpu_artifacts['libhearth_magpie.so'] = ('arm64-v8a', False)

whisper_gpu = json.loads((root/'common-jni/src/main/assets/licenses/whisper-vulkan/runtime-build.json').read_text())
assert whisper_gpu['apiVersion'] == 1
assert whisper_gpu['vendorTreeSha256'] == '5f3464bc06c04426d66eae69e5e510d32fb76621b524c42ab25b4294f035a920'
verify_optional_snapshot(whisper_gpu, 'sources', {'libhearth_whisper_vulkan.so': True})

speech = json.loads((root/'common-jni/src/main/assets/licenses/speech/runtime-build.json').read_text())
for name, digest in speech['adapterSources'].items():
    assert hashlib.sha256((root/name).read_bytes()).hexdigest() == digest, f'Speech runtime must be rebuilt: {name}'
pins = dict(re.findall(r'#define\s+(HEARTH_\w+_REVISION)\s+"([0-9a-f]+)"', (root/'common-jni/src/main/cpp/speech/backend_versions.h').read_text()))
assert speech['abi'] == 2, 'Speech ABI mismatch'
assert pins == speech['pins'] == speech_gpu['pins'], 'Speech runtime revision mismatch'
assert {(r['abi'], r['file']) for r in speech['artifacts']} == {(a, f) for a in ABIS for f in ('libhearth_qwen.so', 'libhearth_nemotron.so', 'libhearth_transcribepsy.so')}, 'Missing speech ABI'
for artifact in speech['artifacts']:
    assert artifact['sourceSetSha256'] == source_set_digest(speech['adapterSources']), f"Speech {artifact['abi']} source snapshot is stale"
    name = artifact['file']
    binary = libroot/artifact['abi']/name
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

expected_libs = {'libhearth_magpie_jni.so','libtransiber_translation.so','libcommon_jni.so','libc++_shared.so','libvosk.so','libonnxruntime.so','libhearth_qwen.so','libhearth_nemotron.so','libhearth_transcribepsy.so','libandroidx.graphics.path.so','libdatastore_shared_counter.so','libimage_processing_util_jni.so'}
platform = {'liblog.so','libandroid.so','libjnigraphics.so','libm.so','libdl.so','libc.so','libz.so'}
delivery_signers = None
unsigned_inputs = 0
for flavor, architecture in [(f, a) for f in ('play','foss') for a in (*ABIS, 'universal')]:
    flavor_libs = expected_libs | ({"libtranslate_jni.so"} if flavor == "play" else set())
    apk = root/f'app/build/outputs/apk/{flavor}/{build_type}/app-{flavor}-{architecture}-{build_type}.apk'
    if build_type == 'release' and not apk.is_file():
        apk = apk.with_name(apk.stem + '-unsigned.apk')
    assert apk.is_file(), f'Build {flavor} {build_type} first'
    try:
        signers = verify_delivery(apk, aapt, apksigner, expected_identity, args.allow_unsigned_for_signing)
    except ValueError as error:
        raise SystemExit(f'{apk.name}: {error}') from error
    if signers is None:
        unsigned_inputs += 1
    elif delivery_signers is None:
        delivery_signers = signers
    else:
        assert signers == delivery_signers, f'{apk.name}: signer differs from the other delivery APKs; upgrades would fail'
    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory() as tmp:
        native = [n for n in archive.namelist() if n.startswith('lib/') and n.endswith('.so')]
        packaged_abis = set(ABIS) if architecture == 'universal' else {architecture}
        all_expected = flavor_libs | {name for name, (abi, _) in gpu_artifacts.items() if abi in packaged_abis}
        assert {Path(n).name for n in native} == all_expected, native
        assert {n.split('/')[1] for n in native} == packaged_abis
        assert all({Path(n).name for n in native if n.startswith('lib/' + abi + '/')} == flavor_libs | {name for name, (target_abi, _) in gpu_artifacts.items() if abi == target_abi} for abi in packaged_abis)
        for name in native:
            abi = name.split('/')[1]
            file = Path(tmp)/abi/Path(name).name
            file.parent.mkdir(exist_ok=True)
            data = archive.read(name)
            verify_elf(abi, data)
            if name in vendor_hashes:
                assert hashlib.sha256(data).hexdigest() == vendor_hashes[name], f'Packaged vendor runtime changed: {name}'
            file.write_bytes(data)
            info = subprocess.check_output([readelf, '-lW', file], text=True)
            aligns = [int(line.split()[-1],16) for line in info.splitlines() if line.strip().startswith('LOAD ')]
            required_alignment = 16384 if abi in ('arm64-v8a', 'x86_64') else 4096
            assert aligns and min(aligns) >= required_alignment, f'{name}: page alignment {aligns}'
            dynamic = subprocess.check_output([readelf, '-d', file], text=True)
            needed = set(re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic))
            allowed_platform = platform | ({'libvulkan.so'} if gpu_artifacts.get(file.name, (None, False))[1] else set())
            assert not needed - flavor_libs - allowed_platform, (name, needed)
            if file.name in ('libtransiber_translation.so', gpu_binary['name'], opencl_binary['name']):
                prefix = 'Java_com_sal7one_common_1jni_translation_' + ({'libtransiber_translation_vulkan.so': 'LocalTranslationVulkanNative_', 'libtransiber_translation_opencl.so': 'LocalTranslationOpenClNative_'}.get(file.name, 'LocalTranslationNative_'))
                symbols = subprocess.check_output([readelf, '--dyn-syms', '-W', file], text=True)
                exports = [line.split()[-1] for line in symbols.splitlines() if re.search(r'\b(?:GLOBAL|WEAK)\s+DEFAULT\s+\d+\s+', line)]
                methods = {'promptProtocolVersion', 'create', 'translate', 'cancel', 'destroy'}
                if file.name in gpu_artifacts: methods.add('executionInfo')
                if file.name == opencl_binary['name']: methods.add('configureCache')
                assert set(exports) == {prefix + method for method in methods}, (file.name, exports)
                assert not needed & {'libvosk.so', 'libcommon_jni.so', 'libhearth_qwen.so', 'libhearth_nemotron.so', 'libhearth_transcribepsy.so'}, needed
                if file.name == gpu_binary['name']:
                    assert 'libvulkan.so' in needed, 'Vulkan backend missing from the optional GPU runtime'
                    assert prefix + 'executionInfo' in exports, 'GPU execution status JNI missing'
                else:
                    assert 'libvulkan.so' not in needed, 'CPU runtime must not load the Vulkan driver'
            if file.name.startswith('libhearth_transcribe_cpp') or file.name == 'libtransiber_ct2.so':
                symbols = subprocess.check_output([readelf, '--dyn-syms', '-W', file], text=True)
                exports = {line.split()[-1] for line in symbols.splitlines() if re.search(r'\b(?:GLOBAL|WEAK)\s+DEFAULT\s+\d+\s+', line)}
                if file.name.startswith('libhearth_transcribe_cpp'):
                    assert exports == set(speech_expansion['publicExports']), (file.name, exports)
                else:
                    assert exports == {'Java_com_sal7one_common_1jni_translation_Ct2Native_' + method for method in ('protocolVersion', 'create', 'translate', 'cancel', 'destroy')}, (file.name, exports)
                assert not needed & {'libvosk.so', 'libcommon_jni.so', 'libonnxruntime.so'}, (file.name, needed)
            if file.name in {'libhearth_magpie.so', 'libhearth_magpie_jni.so'}:
                symbols = subprocess.check_output([readelf, '--dyn-syms', '-W', file], text=True)
                exports = {line.split()[-1] for line in symbols.splitlines() if re.search(r'\b(?:GLOBAL|WEAK)\s+DEFAULT\s+\d+\s+', line)}
                if file.name == 'libhearth_magpie_jni.so':
                    assert exports == {'Java_com_sal7one_common_1jni_voice_MagpieNative_' + name for name in ('available', 'create', 'voices', 'synthesize', 'cancel', 'destroy')}, exports
                else:
                    assert len(exports) == 12 and all(name.startswith('nemo_speech_tts_') for name in exports), exports
                assert not needed & {'libcommon_jni.so', 'libonnxruntime.so', 'libvulkan.so'}, needed
            if file.name in gpu_artifacts:
                assert ('libvulkan.so' in needed) == gpu_artifacts[file.name][1], f'{name}: unexpected Vulkan linkage'
                assert 'libOpenCL.so' not in needed, f'{name}: OpenCL must use the optional namespace-safe loader'
                if file.name.startswith('libhearth_transcribepsy_') or file.name == 'libhearth_whisper_vulkan.so':
                    symbols = subprocess.check_output([readelf, '--dyn-syms', '-W', file], text=True)
                    exports = [line.split()[-1] for line in symbols.splitlines() if re.search(r'\b(?:GLOBAL|WEAK)\s+DEFAULT\s+\d+\s+', line)]
                    expected_export = 'hearth_speech_backend_v1' if file.name.startswith('libhearth_transcribepsy_') else 'hearth_whisper_vulkan_api_v1'
                    assert exports == [expected_export], (file.name, exports)
            else:
                assert not needed & {'libvulkan.so', 'libOpenCL.so'}, f'{name}: CPU load must not probe GPU drivers'
            if file.name == 'libcommon_jni.so':
                assert 'libvosk.so' not in needed, 'Vosk must remain behind its local C API loader: its exported static libc++ conflicts with speech plugins'
                assert 'libc++_shared.so' in needed

        for item in phone_assets.iterdir():
            if item.is_file():
                assert archive.read('assets/phone-setup/' + item.name) == item.read_bytes(), item.name
        for family in ['speech', 'translation', 'whisper-vulkan']:
            name = f'assets/licenses/{family}/runtime-build.json'
            assert archive.read(name) == (root/'common-jni/src/main'/name).read_bytes(), name
            for notice in (root/'common-jni/src/main/assets/licenses'/family).rglob('*'):
                if notice.is_file():
                    packaged = 'assets/licenses/' + family + '/' + notice.relative_to(root/'common-jni/src/main/assets/licenses'/family).as_posix()
                    assert archive.read(packaged) == notice.read_bytes(), f'Runtime notice changed or missing: {packaged}'
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
    # Service binding permissions are NOT uses-permission entries: inspect the packaged
    # binary manifest as well, including declarations contributed by dependencies.
    manifest = subprocess.check_output([aapt, 'dump', 'xmltree', apk, '--file', 'AndroidManifest.xml'], text=True)
    assert 'com.sal7one.transiber.HearthApplication' in manifest, 'Process-wide model audit application missing'
    optional_native = re.findall(r'(?ms)^\s*E: uses-native-library\b(.*?)(?=^\s*E:|\Z)', manifest)
    opencl_declarations = [block for block in optional_native if 'libOpenCL.so' in block]
    assert len(opencl_declarations) == 1 and re.search(r'android:required[^\n]*=(?:false|\(type 0x12\)0x0)(?:\s|$)', opencl_declarations[0]), 'OpenCL must remain an optional vendor library'
    assert 'com.sal7one.transiber.translation.TranslateSelectionActivity' in manifest, f'{flavor}: text translation activity missing'
    assert 'android.intent.action.PROCESS_TEXT' in manifest and 'text/html' in manifest, f'{flavor}: selected-text or rich-text share action missing'
    assert 'android.intent.category.LEANBACK_LAUNCHER' in manifest, 'TV launcher missing'
    assert 'android:banner' in manifest, 'TV banner missing'
    assert 'com.sal7one.transiber.caption.OverlayControlsActivity' in manifest, 'Remote overlay controls missing'
    assert 'com.sal7one.transiber.caption.StopHearthActivity' in manifest, 'Emergency TV stop launcher missing'
    assert 'android.service.quicksettings.action.QS_TILE_PREFERENCES' in manifest, 'Tile controls shortcut missing'
    assert 'android.service.quicksettings.TOGGLEABLE_TILE' in manifest, 'Caption tile toggle semantics missing'
    for sensitive in ('android.permission.BIND_ACCESSIBILITY_SERVICE',
                      'android.accessibilityservice.AccessibilityService',
                      'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE',
                      'android.permission.READ_SMS', 'android.permission.RECEIVE_SMS',
                      'android.permission.REQUEST_INSTALL_PACKAGES'):
        assert sensitive not in manifest, f'{flavor}: unexpected sensitive access: {sensitive}'
    signature_status = 'UNSIGNED: signing preparation only, NOT INSTALLABLE' if signers is None else 'signature and current package/version verified'
    print(f'{flavor} {architecture} {build_type}: {apk.stat().st_size} bytes; {signature_status}; ABI/ELF, page alignment and permissions PASS')
if unsigned_inputs:
    print(f'Internal pre-sign content verification PASS ({unsigned_inputs} unsigned APKs). NOT valid for installable delivery.')
else:
    print('Standalone installable artifact verification PASS (signatures, current package/version and contents)')
