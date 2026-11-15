// The NVIDIA CUDA build of the Nemotron speech backend on Windows.
//
// The shared adapter (common-jni/.../speech/backends/nemotron_backend.cpp) is CPU-only and is
// part of the Android runtime receipt, so it is not edited. The Windows build compiles a
// prepared copy whose three counted replacements call into this file: which device a session
// runs on, and what that session reports. It is force-included into that copy.
#pragma once

#include <string>

struct HearthSpeechConfig;

namespace hearth::nemotron {

// The device a session asked for with acceleration "cuda" or "cuda:N", after proving it is
// present and able to run this backend. `execution` receives what the session reports.
// Any other request, CPU included, throws: this library never falls back to another device.
int selectDevice(const HearthSpeechConfig& config, std::string& execution);

}  // namespace hearth::nemotron
