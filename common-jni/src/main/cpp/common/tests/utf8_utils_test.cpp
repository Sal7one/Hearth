#include "utf8_utils.h"

#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

using common_jni::text::repairUtf8;
using common_jni::text::utf8ToUtf16;

namespace {
int checks = 0;
void check(bool ok, const char* what) {
    ++checks;
    if (!ok) {
        std::fprintf(stderr, "utf8_utils FAIL: %s\n", what);
        std::exit(1);
    }
}
bool strictlyValid(const std::string& text) {
    std::vector<std::uint16_t> utf16;
    return utf8ToUtf16(text, utf16);
}
const std::string kFffd = "\xEF\xBF\xBD";
}  // namespace

int main() {
    // Well-formed text is unchanged: ASCII, Arabic, CJK, supplementary emoji.
    const std::string mixed = "Hello مرحبا 中文 \xF0\x9F\x98\x80 end";
    check(repairUtf8(mixed) == mixed, "well-formed text unchanged");
    check(repairUtf8("").empty(), "empty stays empty");
    check(repairUtf8(std::string_view("a\0b", 3)) == std::string("a\0b", 3), "NUL is a scalar value");

    // Truncated final character (token cap): dropped, not replaced.
    check(repairUtf8("中文\xE6\x96") == "中文", "truncated 3-byte tail dropped");
    check(repairUtf8("x\xF0\x9F\x98") == "x", "truncated 4-byte tail dropped");
    check(repairUtf8("\xD9") == "", "lone Arabic lead dropped at end");

    // Split across a segment join ("中" = E4 B8 AD): one U+FFFD per maximal subpart.
    check(repairUtf8("\xE4\xB8 \xAD") == kFffd + " " + kFffd, "split character across a space");

    // Ill-formed sequences inside the text.
    check(repairUtf8("a\x80z") == "a" + kFffd + "z", "stray continuation");
    check(repairUtf8("\xC0\xAF") == kFffd + kFffd, "overlong lead C0");
    check(repairUtf8("\xE0\x80\x80!") == kFffd + kFffd + kFffd + "!", "overlong E0 80");
    check(repairUtf8("\xED\xA0\x80!") == kFffd + kFffd + kFffd + "!", "surrogate ED A0");
    check(repairUtf8("\xF4\x90\x80\x80!") == kFffd + kFffd + kFffd + kFffd + "!", "above U+10FFFF");
    check(repairUtf8("\xF5z\xFF") == kFffd + "z" + kFffd, "invalid leads F5/FF");
    check(repairUtf8("\xE4\xB8z") == kFffd + "z", "incomplete 3-byte before ASCII");
    // An ill-formed prefix at the end is not a truncated character.
    check(repairUtf8("a\xE0\x80") == "a" + kFffd + kFffd, "E0 80 at end is ill-formed");

    // Boundary scalars stay intact.
    const std::string edges = "\xC2\x80\xDF\xBF\xE0\xA0\x80\xED\x9F\xBF\xEE\x80\x80\xF0\x90\x80\x80\xF4\x8F\xBF\xBF";
    check(repairUtf8(edges) == edges, "boundary scalars unchanged");

    // Every output is accepted by the strict JNI converter.
    const std::vector<std::string> samples = {
        "中文\xE6\x96", "\xE4\xB8 \xAD", "a\x80z", "\xC0\xAF", "\xED\xA0\x80", "\xF4\x90\x80\x80",
        "\xF0\x9F\x98", "\xFF\xFE\xFD", mixed, edges, std::string(1, '\x80') + "中",
    };
    for (const auto& sample : samples) {
        check(strictlyValid(repairUtf8(sample)), "output is strictly valid UTF-8");
    }
    // Exhaustive two-byte inputs: output is always strictly valid.
    for (int first = 0; first < 256; ++first) {
        for (int second = 0; second < 256; ++second) {
            const char bytes[2] = {static_cast<char>(first), static_cast<char>(second)};
            if (!strictlyValid(repairUtf8(std::string_view(bytes, 2)))) {
                check(false, "exhaustive two-byte input produced invalid UTF-8");
            }
        }
    }
    ++checks;

    std::printf("utf8_utils: %d checks PASS\n", checks);
    return 0;
}
