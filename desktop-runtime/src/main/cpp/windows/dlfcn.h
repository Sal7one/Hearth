// Minimal <dlfcn.h> for MSVC builds of the shared adapters. Paths are UTF-8 and
// absolute; a library's own dependencies resolve beside it, never from the
// current directory or PATH order.
#pragma once
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <string>

#define RTLD_LAZY 1
#define RTLD_NOW 2
#define RTLD_LOCAL 4
#define RTLD_GLOBAL 8

struct Dl_info { const char* dli_fname; void* dli_fbase; const char* dli_sname; void* dli_saddr; };

namespace hearth_dl {
inline std::string& error() { static thread_local std::string value; return value; }
inline std::wstring wide(const char* utf8) {
    const int size = utf8 ? MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, utf8, -1, nullptr, 0) : 0;
    if (size <= 0) return {};
    std::wstring value(static_cast<size_t>(size), L'\0');
    MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, utf8, -1, value.data(), size);
    value.resize(static_cast<size_t>(size) - 1);
    return value;
}
inline std::string utf8(const std::wstring& value) {
    const int size = WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), nullptr, 0, nullptr, nullptr);
    std::string result(static_cast<size_t>(size > 0 ? size : 0), '\0');
    if (size > 0) WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), result.data(), size, nullptr, nullptr);
    return result;
}
}

inline void* dlopen(const char* path, int) {
    const std::wstring name = hearth_dl::wide(path);
    if (name.empty()) { hearth_dl::error() = "The library path is missing or not valid UTF-8"; return nullptr; }
    const HMODULE module = LoadLibraryExW(name.c_str(), nullptr, LOAD_WITH_ALTERED_SEARCH_PATH);
    if (!module) hearth_dl::error() = "Windows could not load the library (error " + std::to_string(GetLastError()) + ")";
    return module;
}
inline void* dlsym(void* library, const char* symbol) {
    const auto address = GetProcAddress(static_cast<HMODULE>(library), symbol);
    if (!address) hearth_dl::error() = std::string("Missing symbol ") + symbol;
    return reinterpret_cast<void*>(address);
}
inline int dlclose(void* library) { return FreeLibrary(static_cast<HMODULE>(library)) ? 0 : 1; }
inline const char* dlerror() { return hearth_dl::error().empty() ? nullptr : hearth_dl::error().c_str(); }
/** Resolves the module that contains an address; the name stays valid for the calling thread. */
inline int dladdr(const void* address, Dl_info* info) {
    HMODULE module = nullptr;
    if (!info || !GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                                     static_cast<LPCWSTR>(address), &module)) return 0;
    std::wstring name(32768, L'\0');
    const DWORD length = GetModuleFileNameW(module, name.data(), static_cast<DWORD>(name.size()));
    if (length == 0 || length >= name.size()) return 0;
    name.resize(length);
    static thread_local std::string filename;
    filename = hearth_dl::utf8(name);
    *info = Dl_info{filename.c_str(), module, nullptr, nullptr};
    return 1;
}
