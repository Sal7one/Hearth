#include "whisper_engine.h"
#include "json_utils.h"
#include <chrono>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <thread>
#include <vector>

int main(int argc, char** argv) {
    try {
        if (argc != 5) throw std::runtime_error("Expected MODEL SHA256 PCM16.wav cpu|vulkan");
        stt::EngineConfig config;
        config.modelPath = argv[1]; config.modelSha256 = argv[2];
        config.whisperAcceleration = argv[4]; config.language = "en";
        config.numThreads = 2; config.enableVad = false;
        config.chunkDurationMs = 8000; config.contextDurationMs = 0;
        config.noSpeechThreshold = 0.8f; config.suppressNonSpeechTokens = true;
        std::ifstream file(argv[3], std::ios::binary);
        std::vector<char> wav{std::istreambuf_iterator<char>(file), {}};
        if (wav.size() < 44 || std::memcmp(wav.data(), "RIFF", 4)) throw std::runtime_error("Missing WAV");
        size_t offset = 12, bytes = 0;
        for (; offset + 8 <= wav.size();) {
            uint32_t size; std::memcpy(&size, wav.data() + offset + 4, 4);
            const bool data = !std::memcmp(wav.data() + offset, "data", 4);
            offset += 8;
            if (offset + size > wav.size()) throw std::runtime_error("Invalid WAV chunk");
            if (data) { bytes = size; break; }
            offset += size + (size & 1);
        }
        if (!bytes || bytes % 2) throw std::runtime_error("Expected PCM16 data");
        std::vector<int16_t> pcm(bytes / 2); std::memcpy(pcm.data(), wav.data() + offset, bytes);
        stt::WhisperEngine engine;
        auto start = std::chrono::steady_clock::now();
        if (!engine.initialize(config)) throw std::runtime_error(engine.getLastError());
        auto elapsed = [&] { return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count(); };
        std::cout << "{\"runtime\":\"" << engine.getRuntimeDescription() << "\",\"loadMs\":" << elapsed() << "}\n";
        for (int repeat = 0; repeat < 3; ++repeat) {
            start = std::chrono::steady_clock::now();
            auto result = engine.transcribeBatch(pcm.data(), static_cast<int>(pcm.size()), 16000);
            if (result.empty()) throw std::runtime_error(engine.getLastError());
            std::cout << "{\"batchMs\":" << elapsed() << ",\"result\":" << result << "}\n";
        }
        // Exercise the production worker and reset/finalize ownership too.
        if (!engine.resetChecked()) throw std::runtime_error(engine.getLastError());
        for (size_t i = 0; i < pcm.size(); i += 320) {
            if (engine.pushAudio(pcm.data() + i, static_cast<int>(std::min<size_t>(320, pcm.size() - i)), 16000) < 0)
                throw std::runtime_error(engine.getLastError());
        }
        auto final = engine.finalize();
        if (final.empty()) throw std::runtime_error(engine.getLastError());
        std::cout << "{\"streamFinal\":" << final << "}\n";
        engine.release();
        if (!engine.getRuntimeDescription().empty()) throw std::runtime_error("Released runtime still active");
        return 0;
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
