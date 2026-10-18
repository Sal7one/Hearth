// Exercise the production ABI adapter and native session; fake only the external
// model API so ownership, failures and duplicate-final behavior need no weights.
#include "../backends/transcribepsy_backend.cpp"
#include "../speech_session.h"
#include <cassert>
#include <iostream>
using namespace stt::speech;
namespace {
int checks = 0;
void check(bool value) { ++checks; if (!value) throw std::runtime_error("TranscribePsy adapter assertion failed at check " + std::to_string(checks)); }
template<class F> void fails(F f, const std::string& expected) {
    try { f(); throw std::runtime_error("Expected error: " + expected); }
    catch (const std::exception& e) { check(std::string(e.what()).find(expected) != std::string::npos); }
}
HearthSpeechConfig config(const char* language = "auto") {
    return {sizeof(HearthSpeechConfig), "fixture.gguf", "", "", "", "", language, 2, 3, 1000, 200, -45, "cpu", ""};
}
}
int main() {
    const auto& backend = *hearth_speech_backend_v1();
    check(std::string(backend.id) == "transcribepsy");
    check(std::string(backend.revision) == HEARTH_TRANSCRIBEPSY_REVISION);
    std::vector<float> audio(16000, 0.25f);
    {
        SpeechNativeSession session(backend, config());
        check(parakeet::Engine::live == 1);
        check(parakeet::Engine::lastOptions.n_gpu_layers == 0);
        check(parakeet::Engine::lastOptions.n_threads == 2);
        check(session.executionInfo() == "TranscribePsy · mel: CPU · encoder: CPU · decoder: CPU");
        const auto first = session.push(audio.data(), audio.size(), 0);
        check(first.find("Bonjour.") != std::string::npos);
        check(first.find("\"language\":\"\"") != std::string::npos);
        check(first.find("\"utteranceId\":1") != std::string::npos);
        const auto second = session.push(audio.data(), audio.size(), audio.size());
        check(second.find("Bonjour.") != std::string::npos);
        check(second.find("\"utteranceId\":2") != std::string::npos);
        check(session.finish().find("\"events\":[]") != std::string::npos);
        check(parakeet::Engine::calls == 2);
        session.reset();
        check(session.push(audio.data(), 8000, 0).find("\"events\":[]") != std::string::npos);
        check(session.finish().find("Bonjour.") != std::string::npos);
    }
    check(parakeet::Engine::live == 0);
    {
        SpeechNativeSession session(backend, config("fr"));
        check(session.push(audio.data(), audio.size(), 0).find("\"language\":\"fr\"") != std::string::npos);
        parakeet::Engine::failDecode = true;
        fails([&] { session.push(audio.data(), audio.size(), audio.size()); }, "fixture encoder error");
        parakeet::Engine::failDecode = false;
        fails([&] { session.finish(); }, "fixture encoder error");
        session.reset();
        check(session.push(audio.data(), audio.size(), 0).find("Bonjour.") != std::string::npos);
    }
    check(parakeet::Engine::live == 0);
    parakeet::Engine::failLoad = true;
    fails([&] { SpeechNativeSession session(backend, config()); }, "fixture GGUF load error");
    check(parakeet::Engine::live == 0);
    parakeet::Engine::failLoad = false;
    parakeet::Engine::type = "ctc";
    fails([&] { SpeechNativeSession session(backend, config()); }, "FastConformer TDT");
    check(parakeet::Engine::live == 0);
    parakeet::Engine::type = "tdt";
    {
        SpeechNativeSession session(backend, config());
        session.push(audio.data(), 8000, 0);
        session.reset();
        check(session.finish().find("\"events\":[]") != std::string::npos);
    }
    check(parakeet::Engine::live == 0);
    std::cout << "transcribepsy_backend_test: " << checks << " checks PASS\n";
}
