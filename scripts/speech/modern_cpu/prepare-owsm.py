#!/usr/bin/env python3
"""Narrow, counted patches of pinned sources; never modify the upstream checkout."""
from pathlib import Path
import sys
source, output = map(Path, sys.argv[1:])
output.mkdir(parents=True, exist_ok=True)
assets = (source / 'src/models/owsm/assets.cpp').read_text()
old = '(model.variant != "base" && model.variant != "small" && model.variant != "medium")'
assert assets.count(old) == 1
assets = assets.replace(old, '(model.variant != "base" || model.hidden_size != 384 || model.encoder_layers != 6 || model.decoder_layers != 6)')
(output / 'owsm-assets.cpp').write_text(assets)
runtime = (source / 'src/models/owsm/runtime.cpp').read_text()
old = '    debug::timing_log_scalar("owsm.decoder_ms", debug::elapsed_ms(decoder_started));\n    return {std::move(generated), std::move(detected_language)};\n}'
assert runtime.count(old) == 1
runtime = runtime.replace(old, '    throw std::runtime_error("OWSM decoder exhausted the 374-token budget before EOS");\n}')
(output / 'owsm-runtime.cpp').write_text(runtime)
tensor = (source / 'src/framework/assets/tensor_source.cpp').read_text()
old = 'std::filesystem::temp_directory_path() / "audiocpp-gguf" / fingerprint.str()'
assert tensor.count(old) == 1
# Android has no writable global /tmp. Runtime models are verified app-private
# snapshots; put decoded sidecars beside their parent, outside the immutable tree.
tensor = tensor.replace(old, 'canonical.parent_path().parent_path() / ".owsm-cache" / fingerprint.str()')
(output / 'owsm-tensor-source.cpp').write_text(tensor)
