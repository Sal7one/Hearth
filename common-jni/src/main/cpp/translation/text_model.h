#pragma once
#include "llama.h"
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
namespace transiber {
inline thread_local std::string nativeError;
inline void captureLog(ggml_log_level level, const char* text, void*) {
    if (level == GGML_LOG_LEVEL_ERROR && text) {
        nativeError.append(text);
        if (nativeError.size() > 8192) nativeError.erase(0, nativeError.size() - 8192);
    }
}
inline std::runtime_error failure(const std::string& operation) {
    return std::runtime_error(nativeError.empty() ? operation : operation + "\n" + nativeError);
}

class TextModel {
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model{nullptr, llama_model_free};
    std::unique_ptr<llama_context, decltype(&llama_free)> context{nullptr, llama_free};
    std::chrono::steady_clock::time_point deadline;
    bool gemma = false;
    static bool abortDecode(void* p) {
        auto& self = *static_cast<TextModel*>(p);
        return self.cancelled.load() || std::chrono::steady_clock::now() >= self.deadline;
    }
public:
    std::mutex mutex;
    std::atomic<bool> cancelled{false};
    explicit TextModel(const std::string& path) {
        static std::once_flag init;
        std::call_once(init, [] { llama_log_set(captureLog, nullptr); llama_backend_init(); });
        nativeError.clear();
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        model.reset(llama_model_load_from_file(path.c_str(), mp));
        if (!model) throw failure("llama_model_load_from_file failed: " + path);
        char architecture[128]{};
        llama_model_meta_val_str(model.get(), "general.architecture", architecture, sizeof(architecture));
        gemma = std::string(architecture) == "gemma3";
        if (!gemma && std::string(architecture) != "hunyuan-dense") throw std::runtime_error("Unsupported translation architecture: " + std::string(architecture));
        auto cp = llama_context_default_params();
        cp.n_ctx = 2048; cp.n_batch = 128; cp.n_ubatch = 128;
        cp.n_threads = 2; cp.n_threads_batch = 2;
        context.reset(llama_init_from_model(model.get(), cp));
        if (!context) throw failure("llama_init_from_model failed");
        llama_set_abort_callback(context.get(), abortDecode, this);
    }
    std::string translate(const std::string& prompt) {
        std::lock_guard<std::mutex> lock(mutex);
        if (cancelled.load()) throw std::runtime_error("Local translation cancelled");
        nativeError.clear();
        deadline = std::chrono::steady_clock::now() + std::chrono::seconds(20);
        if (prompt.empty() || prompt.size() > 12000) throw std::invalid_argument("Translation prompt exceeds 12000 UTF-8 bytes or is empty");
        // Explicit publisher-compatible Hunyuan chat envelope. The text itself
        // is tokenized without special-token parsing, so caption text cannot
        // inject a synthetic end-of-turn marker.
        const auto* vocab = llama_model_get_vocab(model.get());
        auto tokenize = [vocab](const std::string& text, bool special, bool add) {
            std::vector<llama_token> tokens(text.size() + 32);
            int n = llama_tokenize(vocab, text.data(), text.size(), tokens.data(), tokens.size(), add, special);
            if (n < 0) { tokens.resize(-n); n = llama_tokenize(vocab, text.data(), text.size(), tokens.data(), tokens.size(), add, special); }
            if (n < 0) throw std::runtime_error("llama_tokenize failed");
            tokens.resize(n); return tokens;
        };
        auto tokens = tokenize(gemma ? "<bos><start_of_turn>user\n" : "<｜hy_begin▁of▁sentence｜><｜hy_User｜>", true, false);
        auto content = tokenize(prompt, false, false); tokens.insert(tokens.end(), content.begin(), content.end());
        auto suffix = tokenize(gemma ? "<end_of_turn>\n<start_of_turn>model\n" : "<｜hy_Assistant｜>", true, false); tokens.insert(tokens.end(), suffix.begin(), suffix.end());
        constexpr int maxOutput = 384;
        if (tokens.size() + maxOutput > 2048) throw std::runtime_error("Caption exceeds the local translation context (2048 tokens)");
        llama_memory_clear(llama_get_memory(context.get()), true);
        auto decode = [this](llama_batch batch) {
            if (abortDecode(this)) throw std::runtime_error(cancelled.load() ? "Local translation cancelled" : "Local translation exceeded 20 seconds");
            const int status = llama_decode(context.get(), batch);
            if (status != 0) throw failure(cancelled.load() ? "Local translation cancelled" : abortDecode(this) ? "Local translation exceeded 20 seconds" : "llama_decode failed: " + std::to_string(status));
        };
        for (size_t i = 0; i < tokens.size(); i += 128) {
            decode(llama_batch_get_one(tokens.data() + i, std::min<size_t>(128, tokens.size() - i)));
        }
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(), llama_sampler_free);
        std::string output;
        for (int i = 0; i < maxOutput; ++i) {
            auto token = llama_sampler_sample(sampler.get(), context.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) {
                if (output.find_first_not_of(" \n\r\t") == std::string::npos) throw std::runtime_error("Local translation model returned empty text");
                return output;
            }
            std::vector<char> piece(128);
            int n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            if (n < 0) { piece.resize(-n); n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false); }
            if (n < 0) throw std::runtime_error("llama_token_to_piece failed");
            output.append(piece.data(), n);
            decode(llama_batch_get_one(&token, 1));
        }
        throw std::runtime_error("Local translation exceeded 384 output tokens; incomplete text was not published");
    }
};
}
