#!/usr/bin/env python3
"""Make pinned GGML OpenCL driver/compiler failures catchable at JNI.

Write a build-only copy. Kernel sources/selection stay unmodified. The upstream
CLI terminates its process on OpenCL errors; an Android app must show the error
and let the user explicitly select CPU instead.
"""
import argparse
import re
from pathlib import Path
parser = argparse.ArgumentParser()
parser.add_argument('--source', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--expected-exits', type=int, default=10)
args = parser.parse_args()
source = args.source.read_text()
if source.count('GGML_ASSERT(0);') != 1 or source.count('exit(1);') != args.expected_exits:
    raise SystemExit('Pinned OpenCL error-handling anchors changed; review before building')
source = source.replace('#include <CL/cl.h>', '#include <CL/cl.h>\n#include <stdexcept>')
source = source.replace('GGML_ASSERT(0);', 'throw std::runtime_error(std::string("OpenCL ") + #err + " failed (" + std::to_string(err_) + "). Select Vulkan or CPU.");')
source = source.replace('exit(1);', 'throw std::runtime_error(std::string("OpenCL backend failed at line ") + std::to_string(__LINE__) + ". Select Vulkan or CPU.");')
# Resource teardown runs during exception unwinding too. Preserve the actual
# cleanup error in the native log, without turning a recoverable load failure
# into std::terminate from a noexcept destructor.
cleanup_macro = '#define HEARTH_CL_CLEANUP(expression) do { try { const auto cleanup_error = (expression); if (cleanup_error != CL_SUCCESS) GGML_LOG_ERROR("OpenCL cleanup error %d: %s\\n", cleanup_error, #expression); } catch (const std::exception& cleanup_error) { GGML_LOG_ERROR("OpenCL cleanup error: %s\\n", cleanup_error.what()); } } while (0)\n'
source = source.replace('#include <stdexcept>', '#include <stdexcept>\n' + cleanup_macro)
for match in reversed(list(re.finditer(r'~\w+\s*\([^)]*\)\s*\{', source))):
    depth = 1
    end = match.end()
    while depth and end < len(source):
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    if depth: raise SystemExit('Unbalanced OpenCL destructor')
    source = source[:match.end()] + source[match.end():end].replace('CL_CHECK(', 'HEARTH_CL_CLEANUP(') + source[end:]
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(source)
