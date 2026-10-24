#include "../marian_engine.h"
#include <cstdlib>
#include <iostream>
#include <string>

namespace {
void require(bool condition, const std::string& message) {
    if (!condition) {
        std::cerr << "FAIL: " << message << '\n';
        std::exit(1);
    }
}
}

// Real pinned EN→AR graphs, not a fake ORT implementation. This is a runtime
// compatibility/short-input regression; it does not rank translation quality.
int main(int argc, char** argv) {
    require(argc == 2, "provide the verified EN→AR package directory");
    std::string error;
    auto engine = stt::marian::MarianEngine::create(argv[1], 2, &error);
    require(engine != nullptr, error);
    const auto hello = engine->translate("Hello");
    require(hello.ok && hello.text == "مرحبا", "pinned greeting translates: " + hello.error);
    const auto readMore = engine->translate("Read more");
    require(readMore.ok && readMore.text == "اقرأ المزيد", "pinned OCR label translates: " + readMore.error);

    // Independent SentencePiece + ORT reference, including publisher 4-beam
    // generation, also chooses immediate EOS for this isolated UI label.
    // Forcing a first token produced Arabic "this", an incorrect translation.
    const auto search = engine->translate("Search");
    require(!search.ok && search.emptyOutput && search.text.empty() &&
            search.error.find("Try a longer phrase or another translator") != std::string::npos,
            "model no-output limitation remains an explicit actionable failure");
    for (const auto* whitespace : {" ", "\t", "\r", "\xC2\xA0"}) {
        const auto blank = engine->translate(whitespace);
        require(!blank.ok && !blank.emptyOutput && blank.text.empty() &&
                blank.error == "Marian input contains no translatable text",
                "normalized whitespace must not hallucinate a translation");
    }
    for (const auto* sample : {"Coffee is a popular drink.", "1", "...", "Read more"}) {
        const auto result = engine->translate(sample);
        require(result.ok && !result.text.empty(), std::string("translation after model failure: ") + sample + ": " + result.error);
        if (std::string(sample) == "1") require(result.text == "1", "numeric text remains numeric");
        if (std::string(sample) == "...") require(result.text == "...", "punctuation remains punctuation");
        std::cout << sample << " → " << result.text << " (" << result.latencyMs << " ms)\n";
    }
    engine->cancel();
    const auto cancelled = engine->translate("Hello");
    require(!cancelled.ok && !cancelled.emptyOutput && cancelled.error == "Marian translation cancelled",
            "cancellation remains final for the native session");
    auto bounded = stt::marian::MarianEngine::create(argv[1], 2, &error, 1);
    require(bounded != nullptr, error);
    const auto capped = bounded->translate("Coffee is a popular drink.");
    require(!capped.ok && !capped.emptyOutput && capped.text.empty() &&
            capped.error.find("incomplete text was not published") != std::string::npos,
            "token cap cannot publish partial translation");
    std::cout << "marian_model_smoke: ALL PASS\n";
}
