// Host-only real inference probe. Input is mono 16kHz little-endian PCM16.
#include "../speech_session.h"
#include <chrono>
#include <fstream>
#include <iostream>
#include <sstream>

int main(int argc, char** argv) {
    try {
        if (argc != 4) throw std::invalid_argument("Usage: speech_smoke PACKAGE_DIR AUDIO.pcm SOURCE_LANGUAGE");
        const std::string root = argv[1];
        std::ifstream manifest(root + "/hearth-speech.json");
        std::stringstream buffer; buffer << manifest.rdbuf();
        stt::JsonValue j; std::string error;
        if (!stt::JsonUtils::parse(buffer.str(), j, &error)) throw std::runtime_error(error);
        std::string profile; j.find("profile")->asString(profile);
        const auto* roles = j.find("roles");
        stt::JsonValue::Object cfg{{"backend", profile.find("qwen") == 0 ? "qwen3_asr" :
            profile.find("omnilingual") == 0 ? "omnilingual_ctc" : profile.find("moonshine") == 0 ? "moonshine" : "nemotron_3_5"}};
        for (const char* name : {"model", "frontend", "encoder", "decoder", "tokenizer"}) {
            std::string path;
            if (const auto* value = roles->find(name)) { value->asString(path); path = root + "/" + path; }
            cfg.emplace_back(name, path);
        }
        cfg.insert(cfg.end(), {{"language", argv[3]}, {"numThreads", 4}, {"rightContext", 3}, {"maxUtteranceMs", 4000}, {"silenceMs", 600}, {"silenceThresholdDb", -45}});
        stt::speech::SpeechConfig config(stt::JsonUtils::stringify(stt::JsonValue::object(cfg)));
        auto start = std::chrono::steady_clock::now();
        stt::speech::SpeechNativeSession session(stt::speech::loadBackend(config.backend), config.value);
        auto loaded = std::chrono::steady_clock::now();
        std::ifstream pcm(argv[2], std::ios::binary);
        if (!pcm) throw std::runtime_error("Cannot open PCM input");
        std::vector<char> bytes(1600); std::vector<float> samples(800);
        int64_t offset = 0;
        while (pcm.read(bytes.data(), bytes.size()) || pcm.gcount()) {
            const size_t count = pcm.gcount();
            if (count % 2) throw std::runtime_error("Odd PCM byte count");
            for (size_t i = 0; i < count / 2; ++i) {
                int value = static_cast<unsigned char>(bytes[i * 2]) | (static_cast<unsigned char>(bytes[i * 2 + 1]) << 8);
                samples[i] = static_cast<float>(value >= 32768 ? value - 65536 : value) / 32768.0f;
            }
            const auto result = session.push(samples.data(), count / 2, offset);
            if (result.find("\"events\":[]") == std::string::npos) std::cout << result << '\n';
            offset += count / 2;
        }
        std::cout << session.finish() << '\n';
        const auto end = std::chrono::steady_clock::now();
        std::cerr << "load_seconds=" << std::chrono::duration<double>(loaded-start).count()
            << " inference_seconds=" << std::chrono::duration<double>(end-loaded).count()
            << " audio_seconds=" << offset / 16000.0 << '\n';
        return 0;
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
