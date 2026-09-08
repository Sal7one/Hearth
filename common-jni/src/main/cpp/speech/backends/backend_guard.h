#pragma once
#include "../backend_abi.h"
#include <exception>
#include <stdexcept>
#include <string>
namespace stt::speech {
inline thread_local std::string backendError;
template<class F> int guarded(F operation) noexcept {
    try { backendError.clear(); operation(); return 0; }
    catch (const std::exception& e) { backendError = e.what(); return 1; }
    catch (...) { backendError = "Unknown native speech backend exception"; return 1; }
}
inline const char* lastError() { return backendError.c_str(); }
inline void requireConfig(const HearthSpeechConfig* c) {
    if (!c || c->size != sizeof(HearthSpeechConfig)) throw std::invalid_argument("Speech backend config ABI mismatch");
}
}
