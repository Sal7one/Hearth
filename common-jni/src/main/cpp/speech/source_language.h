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

// SenseVoice emits scaffold tags such as <|en|>, not an ISO language code.
// Strip only known language tags; unknown event/emotion tags stay unknown.
inline std::string senseVoiceLanguageCode(std::string_view value) {
    if (value.size() >= 4 && value.substr(0, 2) == "<|" && value.substr(value.size() - 2) == "|>")
        value = value.substr(2, value.size() - 4);
    const std::string code(value);
    return code == "en" || code == "zh" || code == "yue" || code == "ja" || code == "ko" ? code : "";
}

inline bool samePrimaryLanguage(std::string_view first, std::string_view second) {
    const auto primary = primaryLanguageCode(first);
    return !primary.empty() && primary == primaryLanguageCode(second);
}

}  // namespace stt::speech
