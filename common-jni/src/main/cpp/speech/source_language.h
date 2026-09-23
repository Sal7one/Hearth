#pragma once

#include <cctype>
#include <string>
#include <string_view>

namespace stt::speech {

// NeMo reports locale tags for an utterance. Different locales of the same
// language do not make the caption multilingual.
inline std::string primaryLanguageCode(std::string_view code) {
    const auto separator = code.find_first_of("-_");
    code = code.substr(0, separator);
    std::string primary;
    primary.reserve(code.size());
    for (unsigned char ch : code) primary.push_back(static_cast<char>(std::tolower(ch)));
    return primary;
}

inline bool samePrimaryLanguage(std::string_view first, std::string_view second) {
    const auto primary = primaryLanguageCode(first);
    return !primary.empty() && primary == primaryLanguageCode(second);
}

}  // namespace stt::speech
