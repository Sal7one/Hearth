#!/usr/bin/env python3
"""Narrow reproducible cancellation patch; the pinned upstream checkout remains clean."""
import sys
from pathlib import Path
source,destination=map(Path,sys.argv[1:])
text=source.read_text()
text=text.replace('namespace chatterbox {','extern "C" void hearth_chatterbox_check_watermark();\n\nnamespace chatterbox {',1)
for marker in ['for (size_t frame = 0; frame < frames; ++frame) {','for (size_t frame = 0; frame < spec.frames; ++frame) {','for (int oc = 0; oc < layer.output_channels; ++oc) {','for (size_t t = 0; t < frames; ++t) {']:
 if marker not in text:raise SystemExit('Pinned Perth cancellation injection marker missing: '+marker)
 text=text.replace(marker,marker+'\n    hearth_chatterbox_check_watermark();')
marker='  return std::max(0.0f, std::min(1.0f, confidence));'
if text.count(marker)!=1:raise SystemExit('Pinned Perth confidence marker missing')
text=text.replace(marker,'  if (!std::isfinite(confidence)) return std::numeric_limits<float>::quiet_NaN();\n'+marker)
destination.write_text(text)
