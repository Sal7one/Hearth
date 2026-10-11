#include "marian_engine.h"

#include <iostream>
#include <string>

// A real model probe using the same engine and tokenizer consumed by JNI.
// Models remain external: unit tests must not silently download weights.
int main(int argc, char** argv) {
    if (argc != 3) {
        std::cerr << "Usage: marian_smoke MODEL_DIRECTORY SOURCE_TEXT\n";
        return 2;
    }
    std::string error;
    auto engine = stt::marian::MarianEngine::create(argv[1], 2, &error);
    if (!engine) {
        std::cerr << error << '\n';
        return 1;
    }
    const auto result = engine->translate(argv[2]);
    if (!result.ok) {
        std::cerr << result.error << '\n';
        return 1;
    }
    if (result.text.empty()) {
        std::cerr << "Marian produced an empty translation\n";
        return 1;
    }
    std::cout << result.text << '\n' << "latency_ms=" << result.latencyMs << '\n';
    return 0;
}
