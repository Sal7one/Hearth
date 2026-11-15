// Force-included (/FI) into MSVC builds of sources shared with Android and macOS.
// Those files are hash-pinned by the Android runtime receipts, so Windows adapts
// the compiler instead of editing them: GCC attributes become no-ops and the one
// exported entry point of each library is named on the linker command line.
#pragma once
#ifdef _MSC_VER
#define __attribute__(specification)
#ifndef NOMINMAX
#define NOMINMAX
#endif
#endif
