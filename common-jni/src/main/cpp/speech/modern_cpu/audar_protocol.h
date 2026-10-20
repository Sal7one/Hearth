#pragma once
#include <stdexcept>
#include <string>
namespace stt::speech {
struct AudarTranscript { std::string text, language; bool noSpeech; };
inline std::string audarTrim(const std::string& value) {
    const auto first = value.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) return {};
    return value.substr(first, value.find_last_not_of(" \t\r\n") - first + 1);
}
inline std::string audarLanguagePrefix(const std::string& language) {
    if (language == "ar") return "language Arabic<asr_text>";
    if (language == "en") return "language English<asr_text>";
    if (language == "auto") return {};
    throw std::invalid_argument("Audar supports Auto, Arabic or English source language");
}
inline AudarTranscript audarParse(const std::string& generation, const std::string& prefix) {
    const std::string output = audarTrim(prefix + generation);
    const auto boundary = output.find("<asr_text>");
    if (output.rfind("language ", 0) != 0 || boundary == std::string::npos)
        throw std::runtime_error("Audar returned an invalid ASR language scaffold");
    const std::string name = audarTrim(output.substr(9, boundary - 9));
    if (name.empty() || name.find_first_not_of("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz") != std::string::npos)
        throw std::runtime_error("Audar returned an invalid source language verdict");
    if (name == "None") {
        if (!audarTrim(output.substr(boundary + 10)).empty())
            throw std::runtime_error("Audar returned text after its no-speech verdict");
        return {{}, {}, true};
    }
    auto text = audarTrim(output.substr(boundary + 10));
    if (text.find("<|im_") != std::string::npos || text.find("<asr_text>") != std::string::npos || text.find("<|audio_") != std::string::npos)
        throw std::runtime_error("Audar returned assistant/audio scaffold in its transcript");
    return {std::move(text), name == "Arabic" ? "ar" : name == "English" ? "en" : "", false};
}
}
