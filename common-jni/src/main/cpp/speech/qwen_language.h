#pragma once
#include <map>
#include <stdexcept>
#include <string>

namespace stt::speech {
// Qwen's prompt expects English language names, not ISO codes. Empty means Auto.
inline std::string qwenLanguageName(const std::string& code) {
    static const std::map<std::string, std::string> names = {
        {"zh", "Chinese"}, {"en", "English"}, {"yue", "Cantonese"}, {"ar", "Arabic"},
        {"de", "German"}, {"fr", "French"}, {"es", "Spanish"}, {"pt", "Portuguese"},
        {"id", "Indonesian"}, {"it", "Italian"}, {"ko", "Korean"}, {"ru", "Russian"},
        {"th", "Thai"}, {"vi", "Vietnamese"}, {"ja", "Japanese"}, {"tr", "Turkish"},
        {"hi", "Hindi"}, {"ms", "Malay"}, {"nl", "Dutch"}, {"sv", "Swedish"},
        {"da", "Danish"}, {"fi", "Finnish"}, {"pl", "Polish"}, {"cs", "Czech"},
        {"fil", "Filipino"}, {"fa", "Persian"}, {"el", "Greek"}, {"hu", "Hungarian"},
        {"mk", "Macedonian"}, {"ro", "Romanian"},
    };
    if (code == "auto") return {};
    const auto found = names.find(code);
    if (found == names.end()) throw std::invalid_argument("Unsupported Qwen source language: " + code);
    return found->second;
}
}
