#include "whisper.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <iomanip>
#include <iostream>
#include <memory>
#include <string>
#include <vector>

static std::string jsonEscape(const std::string& value) {
    std::string out;
    for (unsigned char c : value) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char escaped[7]; std::snprintf(escaped, sizeof(escaped), "\\u%04x", c); out += escaped;
                } else out += static_cast<char>(c);
        }
    }
    return out;
}

static bool readExact(char* output, size_t count) {
    std::cin.read(output, static_cast<std::streamsize>(count));
    return static_cast<size_t>(std::cin.gcount()) == count;
}

int main(int argc, char** argv) {
    if (argc != 4) {
        std::cerr << "Usage: hearth_whisper_host MODEL.bin LANGUAGE THREADS\n";
        return 2;
    }
    try {
        const int threads = std::clamp(std::stoi(argv[3]), 1, 16);
        const auto loadStart = std::chrono::steady_clock::now();
        whisper_context_params contextParams = whisper_context_default_params();
        contextParams.use_gpu = false;
        std::unique_ptr<whisper_context, decltype(&whisper_free)> context(
            whisper_init_from_file_with_params(argv[1], contextParams), whisper_free);
        if (!context) throw std::runtime_error("whisper_init_from_file_with_params failed");
        const double loadMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - loadStart).count();
        std::cout << "{\"event\":\"ready\",\"loadMs\":" << loadMs << "}" << std::endl;

        while (true) {
            uint32_t count = 0;
            char header[4];
            if (!readExact(header, sizeof(header))) {
                if (std::cin.eof() && std::cin.gcount() == 0) break;
                throw std::runtime_error("truncated PCM sample length");
            }
            std::memcpy(&count, header, sizeof(count)); // Host and runner are both little-endian Apple/Android devices.
            if (count < 8000 || count > 16000 * 30) throw std::runtime_error("PCM sample is outside 0.5–30 seconds");
            std::vector<int16_t> pcm16(count);
            if (!readExact(reinterpret_cast<char*>(pcm16.data()), pcm16.size() * sizeof(int16_t)))
                throw std::runtime_error("truncated PCM sample payload");
            std::vector<float> pcm(count);
            std::transform(pcm16.begin(), pcm16.end(), pcm.begin(), [](int16_t sample) { return sample / 32768.0f; });
            float peak = 0.0f;
            for (float sample : pcm) peak = std::max(peak, std::abs(sample));
            if (peak > 0.0001f && peak < 0.5f) {
                const float gain = 0.9f / peak;
                for (float& sample : pcm) sample *= gain;
            }

            const auto start = std::chrono::steady_clock::now();
            try {
                whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
                params.n_threads = threads;
                params.language = std::string(argv[2]) == "auto" ? nullptr : argv[2];
                params.detect_language = std::string(argv[2]) == "auto";
                params.translate = false;
                params.no_context = true;
                params.no_speech_thold = 0.8f;
                params.entropy_thold = 2.8f;
                params.temperature_inc = 0.2f;
                params.suppress_blank = false;
                params.token_timestamps = true;
                params.print_special = false;
                params.print_progress = false;
                params.print_realtime = false;
                params.print_timestamps = false;
                params.max_tokens = 0;
                const int status = whisper_full(context.get(), params, pcm.data(), static_cast<int>(pcm.size()));
                if (status != 0) throw std::runtime_error("whisper_full failed: " + std::to_string(status));
                std::string text;
                for (int i = 0; i < whisper_full_n_segments(context.get()); ++i) {
                    const char* segment = whisper_full_get_segment_text(context.get(), i);
                    if (segment) text += segment;
                }
                size_t first = text.find_first_not_of(" \t\n\r");
                if (first == std::string::npos) text.clear();
                else text = text.substr(first, text.find_last_not_of(" \t\n\r") - first + 1);
                if (text == "[BLANK_AUDIO]" || text == "[NOISE]" || text == "[MUSIC]") text.clear();
                const double elapsed = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
                std::cout << "{\"elapsedMs\":" << elapsed << ",\"text\":\"" << jsonEscape(text) << "\"}" << std::endl;
            } catch (const std::exception& error) {
                const double elapsed = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
                std::cout << "{\"elapsedMs\":" << elapsed << ",\"error\":\"" << jsonEscape(error.what()) << "\"}" << std::endl;
            }
        }
    } catch (const std::exception& error) {
        std::cerr << error.what() << std::endl;
        return 1;
    }
    return 0;
}
