#pragma once
#include <fstream>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>
#include <nlohmann/json.hpp>

extern "C" {
struct HearthBpeBuffer { unsigned char* data; size_t len; };
void* hearth_bpe_create(const unsigned char*, size_t, HearthBpeBuffer*);
int hearth_bpe_encode(const void*, const unsigned char*, size_t, HearthBpeBuffer*, HearthBpeBuffer*);
int hearth_bpe_decode(const void*, const unsigned char*, size_t, HearthBpeBuffer*, HearthBpeBuffer*);
void hearth_bpe_destroy(void*);
void hearth_bpe_free_buffer(HearthBpeBuffer);
}

namespace hearth {
class ByteBpe {
    struct Buffer {
        HearthBpeBuffer value{nullptr, 0};
        ~Buffer() { hearth_bpe_free_buffer(value); }
        std::string text() const { return value.data ? std::string(reinterpret_cast<char*>(value.data), value.len) : std::string(); }
    };
    std::unique_ptr<void, decltype(&hearth_bpe_destroy)> handle{nullptr, hearth_bpe_destroy};
public:
    explicit ByteBpe(const std::string& path) {
        std::ifstream input(path, std::ios::binary | std::ios::ate);
        if (!input || input.tellg() <= 0 || input.tellg() > 8 * 1024 * 1024)
            throw std::runtime_error("Jisr tokenizer is missing or exceeds 8 MiB");
        std::string json(static_cast<size_t>(input.tellg()), '\0');
        input.seekg(0);
        if (!input.read(json.data(), static_cast<std::streamsize>(json.size()))) throw std::runtime_error("Cannot read Jisr tokenizer");
        Buffer error;
        handle.reset(hearth_bpe_create(reinterpret_cast<const unsigned char*>(json.data()), json.size(), &error.value));
        if (!handle) throw std::runtime_error("Jisr tokenizer: " + error.text());
    }
    nlohmann::json encodeWithIds(const std::string& text) {
        Buffer output, error;
        if (!hearth_bpe_encode(handle.get(), reinterpret_cast<const unsigned char*>(text.data()), text.size(), &output.value, &error.value))
            throw std::runtime_error("Jisr encode: " + error.text());
        return nlohmann::json::parse(output.text());
    }
    std::vector<std::string> encode(const std::string& text) { return encodeWithIds(text).at("tokens").get<std::vector<std::string>>(); }
    std::string decode(const std::vector<std::string>& pieces) {
        const auto json = nlohmann::json(pieces).dump();
        Buffer output, error;
        if (!hearth_bpe_decode(handle.get(), reinterpret_cast<const unsigned char*>(json.data()), json.size(), &output.value, &error.value))
            throw std::runtime_error("Jisr decode: " + error.text());
        return output.text();
    }
};
}
