#ifndef COMMON_JNI_UTF8_UTILS_H
#define COMMON_JNI_UTF8_UTILS_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace common_jni::text {

/**
 * Strictly convert UTF-16 code units to standard UTF-8.
 *
 * JNI's GetStringUTFChars returns Modified UTF-8, which is not suitable for
 * filesystem, model, or engine APIs expecting standard UTF-8. This converter
 * rejects unpaired surrogates and embedded NULs instead of producing a path or
 * C string whose meaning changes when consumed by a native API.
 */
template <typename Utf16Unit>
bool utf16ToUtf8(
    const Utf16Unit* input,
    std::size_t length,
    std::string& output,
    std::string* error = nullptr
) {
    output.clear();
    if (!input && length != 0U) {
        if (error) *error = "UTF-16 input is null";
        return false;
    }

    std::string candidate;
    candidate.reserve(length);
    for (std::size_t index = 0; index < length; ++index) {
        const std::uint32_t first = static_cast<std::uint16_t>(input[index]);
        std::uint32_t codePoint = first;
        if (first >= 0xD800U && first <= 0xDBFFU) {
            if (index + 1U >= length) {
                if (error) *error = "UTF-16 ends with an unpaired high surrogate";
                return false;
            }
            const std::uint32_t second =
                static_cast<std::uint16_t>(input[index + 1U]);
            if (second < 0xDC00U || second > 0xDFFFU) {
                if (error) *error = "UTF-16 contains an unpaired high surrogate";
                return false;
            }
            codePoint = 0x10000U + ((first - 0xD800U) << 10U) +
                        (second - 0xDC00U);
            ++index;
        } else if (first >= 0xDC00U && first <= 0xDFFFU) {
            if (error) *error = "UTF-16 contains an unpaired low surrogate";
            return false;
        }

        if (codePoint == 0U) {
            if (error) *error = "UTF-16 contains an embedded NUL";
            return false;
        }
        if (codePoint <= 0x7FU) {
            candidate.push_back(static_cast<char>(codePoint));
        } else if (codePoint <= 0x7FFU) {
            candidate.push_back(static_cast<char>(0xC0U | (codePoint >> 6U)));
            candidate.push_back(static_cast<char>(0x80U | (codePoint & 0x3FU)));
        } else if (codePoint <= 0xFFFFU) {
            candidate.push_back(static_cast<char>(0xE0U | (codePoint >> 12U)));
            candidate.push_back(static_cast<char>(0x80U | ((codePoint >> 6U) & 0x3FU)));
            candidate.push_back(static_cast<char>(0x80U | (codePoint & 0x3FU)));
        } else {
            candidate.push_back(static_cast<char>(0xF0U | (codePoint >> 18U)));
            candidate.push_back(static_cast<char>(0x80U | ((codePoint >> 12U) & 0x3FU)));
            candidate.push_back(static_cast<char>(0x80U | ((codePoint >> 6U) & 0x3FU)));
            candidate.push_back(static_cast<char>(0x80U | (codePoint & 0x3FU)));
        }
    }

    output = std::move(candidate);
    if (error) error->clear();
    return true;
}

/** Strictly convert standard UTF-8 to UTF-16 code units. */
inline bool utf8ToUtf16(
    std::string_view input,
    std::vector<std::uint16_t>& output,
    std::string* error = nullptr
) {
    std::vector<std::uint16_t> candidate;
    candidate.reserve(input.size());

    std::size_t index = 0;
    while (index < input.size()) {
        const auto lead = static_cast<std::uint8_t>(input[index]);
        std::uint32_t codePoint = 0;
        std::size_t width = 0;
        std::uint32_t minimum = 0;
        if (lead <= 0x7FU) {
            codePoint = lead;
            width = 1;
        } else if (lead >= 0xC2U && lead <= 0xDFU) {
            codePoint = lead & 0x1FU;
            width = 2;
            minimum = 0x80U;
        } else if (lead >= 0xE0U && lead <= 0xEFU) {
            codePoint = lead & 0x0FU;
            width = 3;
            minimum = 0x800U;
        } else if (lead >= 0xF0U && lead <= 0xF4U) {
            codePoint = lead & 0x07U;
            width = 4;
            minimum = 0x10000U;
        } else {
            if (error) *error = "UTF-8 contains an invalid leading byte";
            return false;
        }

        if (index + width > input.size()) {
            if (error) *error = "UTF-8 ends inside a code point";
            return false;
        }
        for (std::size_t offset = 1; offset < width; ++offset) {
            const auto continuation =
                static_cast<std::uint8_t>(input[index + offset]);
            if ((continuation & 0xC0U) != 0x80U) {
                if (error) *error = "UTF-8 contains an invalid continuation byte";
                return false;
            }
            codePoint = (codePoint << 6U) | (continuation & 0x3FU);
        }
        if ((width > 1U && codePoint < minimum) || codePoint > 0x10FFFFU ||
            (codePoint >= 0xD800U && codePoint <= 0xDFFFU)) {
            if (error) *error = "UTF-8 contains an invalid scalar value";
            return false;
        }

        if (codePoint <= 0xFFFFU) {
            candidate.push_back(static_cast<std::uint16_t>(codePoint));
        } else {
            codePoint -= 0x10000U;
            candidate.push_back(static_cast<std::uint16_t>(
                0xD800U + (codePoint >> 10U)
            ));
            candidate.push_back(static_cast<std::uint16_t>(
                0xDC00U + (codePoint & 0x3FFU)
            ));
        }
        index += width;
    }

    output = std::move(candidate);
    if (error) error->clear();
    return true;
}

/**
 * Make decoder output safe for strict UTF-8 consumers such as utf8ToUtf16().
 *
 * Token decoders emit bytes, not characters: a token cap or segment split can
 * stop inside a multi-byte character. A truncated character at the very end is
 * dropped (the next hypothesis re-emits it whole). Every other maximal
 * ill-formed subpart becomes U+FFFD (Unicode 3.9, as Java's UTF-8 decoder
 * does). Well-formed input is returned unchanged.
 */
inline std::string repairUtf8(std::string_view input) {
    constexpr std::string_view replacement = "\xEF\xBF\xBD";
    std::string output;
    output.reserve(input.size());

    std::size_t index = 0;
    while (index < input.size()) {
        const auto lead = static_cast<std::uint8_t>(input[index]);
        std::size_t width = 0;
        std::uint8_t secondLow = 0x80U;
        std::uint8_t secondHigh = 0xBFU;
        if (lead <= 0x7FU) {
            width = 1;
        } else if (lead >= 0xC2U && lead <= 0xDFU) {
            width = 2;
        } else if (lead >= 0xE0U && lead <= 0xEFU) {
            width = 3;
            if (lead == 0xE0U) secondLow = 0xA0U;   // no overlongs
            if (lead == 0xEDU) secondHigh = 0x9FU;  // no surrogates
        } else if (lead >= 0xF0U && lead <= 0xF4U) {
            width = 4;
            if (lead == 0xF0U) secondLow = 0x90U;   // no overlongs
            if (lead == 0xF4U) secondHigh = 0x8FU;  // <= U+10FFFF
        }
        if (width == 0) {
            output.append(replacement);
            ++index;
            continue;
        }

        std::size_t valid = 1;
        while (valid < width && index + valid < input.size()) {
            const auto next = static_cast<std::uint8_t>(input[index + valid]);
            const std::uint8_t low = valid == 1U ? secondLow : 0x80U;
            const std::uint8_t high = valid == 1U ? secondHigh : 0xBFU;
            if (next < low || next > high) break;
            ++valid;
        }
        if (valid == width) {
            output.append(input.substr(index, width));
        } else if (index + valid == input.size()) {
            break;  // Truncated final character: drop it, never guess it.
        } else {
            output.append(replacement);
        }
        index += valid;
    }
    return output;
}

} // namespace common_jni::text

#endif // COMMON_JNI_UTF8_UTILS_H
