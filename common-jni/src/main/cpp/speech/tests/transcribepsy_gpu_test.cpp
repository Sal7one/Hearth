// Real GPU adapter policy with a model API fixture: GPU allocation/fallback
// evidence must be resolved after loading, never inferred from the request.
#define HEARTH_TRANSCRIBEPSY_ACCELERATION "vulkan"
#include "../backends/transcribepsy_backend.cpp"
#include "../speech_session.h"
#include <iostream>
using namespace stt::speech;
int main() {
    int checks = 0;
    const auto check = [&](bool value) { ++checks; if (!value) throw std::runtime_error("GPU speech assertion failed"); };
    auto config = HearthSpeechConfig{sizeof(HearthSpeechConfig), "fixture.gguf", "", "", "", "", "auto", 2, 3, 1000, 200, -45, "vulkan", ""};
    const auto& api = *hearth_speech_backend_v1();
    const auto fails = [&](const char* expected) {
        try { SpeechNativeSession session(api, config); throw std::runtime_error("Expected failure"); }
        catch (const std::exception& e) { check(std::string(e.what()).find(expected) != std::string::npos); }
        check(parakeet::Engine::live == 0);
    };
    fails("resolved to encoder CPU, decoder CPU");
    parakeet::Engine::device = parakeet::BackendDevice::GPU;
    parakeet::Engine::decoder = "Vulkan0";
    fails("encoder CPU, decoder Vulkan0");
    parakeet::Engine::encoder = "Vulkan0";
    {
        SpeechNativeSession session(api, config);
        check(parakeet::Engine::lastOptions.n_gpu_layers == 99);
        check(session.executionInfo() == "TranscribePsy · mel: CPU · encoder: Vulkan0 · decoder: Vulkan0");
        std::vector<float> audio(16000, 0.2f);
        const auto first = session.push(audio.data(), audio.size(), 0);
        check(first.find("\"utteranceId\":1") != std::string::npos);
        check(first.find("\"language\":\"\"") != std::string::npos);
        check(session.finish().find("\"events\":[]") != std::string::npos);
    }
    check(parakeet::Engine::live == 0);
    parakeet::Engine::decoder = parakeet::Engine::encoder = "Metal";
    fails("decoder Metal");
    config.acceleration = "cpu";
    fails("runtime acceleration mismatch");
    std::cout << "transcribepsy_gpu_test: " << checks << " checks PASS\n";
}
