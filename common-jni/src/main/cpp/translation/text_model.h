#pragma once
#include "llama.h"
#include "ggml-cpu.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstring>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
namespace transiber {
inline thread_local std::string nativeError;
// The optional Vulkan runtime reports the loader's actual placement rather
// than presenting a requested GPU-layer count as completed acceleration.
inline thread_local std::string nativeOffloadSummary;
inline thread_local std::string nativeModelBufferSummary;
inline void captureLog(ggml_log_level level, const char* text, void*) {
    if (level == GGML_LOG_LEVEL_ERROR && text) {
        nativeError.append(text);
        if (nativeError.size() > 8192) nativeError.erase(0, nativeError.size() - 8192);
    }
    if (level == GGML_LOG_LEVEL_INFO && text && std::strstr(text, "offloaded ")) {
        nativeOffloadSummary.assign(text, std::min<size_t>(std::char_traits<char>::length(text), 512));
    }
    if (level == GGML_LOG_LEVEL_INFO && text && nativeModelBufferSummary.size() < 4096 &&
        std::strstr(text, " model buffer size = ")) {
        nativeModelBufferSummary.append(text, std::min<size_t>(
            std::char_traits<char>::length(text), 4096 - nativeModelBufferSummary.size()));
    }
}
inline void initializeRuntime() {
    static std::once_flag init;
    std::call_once(init, [] { llama_log_set(captureLog, nullptr); llama_backend_init(); });
}
inline std::runtime_error failure(const std::string& operation) {
    return std::runtime_error(nativeError.empty() ? operation : operation + "\n" + nativeError);
}

class TextModel {
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model{nullptr, llama_model_free};
    // Reverse member destruction releases the context before its worker pool.
    // Without an attached pool the pinned GGML CPU backend starts and joins
    // workers for every decode graph (including each generated token).
    std::unique_ptr<ggml_threadpool, decltype(&ggml_threadpool_free)> threadpool{nullptr, ggml_threadpool_free};
    std::unique_ptr<llama_context, decltype(&llama_free)> context{nullptr, llama_free};
    std::chrono::steady_clock::time_point deadline;
    bool gemma = false;
    bool qwen35 = false;
    bool euro = false;
    bool riva = false;
    bool rawPrompt = false;
    bool prefixCacheEnabled = false;
    llama_token assistantEnd = LLAMA_TOKEN_NULL;
    std::vector<llama_token> cachedPrompt;
    std::vector<llama_token> promptPrefix;
    std::vector<llama_token> promptSuffix;
    std::vector<llama_token> tokenize(const std::string& text, bool special) const {
        const auto* vocab = llama_model_get_vocab(model.get());
        std::vector<llama_token> tokens(text.size() + 32);
        int n = llama_tokenize(vocab, text.data(), text.size(), tokens.data(), tokens.size(), false, special);
        if (n < 0) { tokens.resize(-n); n = llama_tokenize(vocab, text.data(), text.size(), tokens.data(), tokens.size(), false, special); }
        if (n < 0) throw std::runtime_error("llama_tokenize failed");
        tokens.resize(n);
        return tokens;
    }
    static bool abortDecode(void* p) {
        auto& self = *static_cast<TextModel*>(p);
        return self.cancelled.load() || std::chrono::steady_clock::now() >= self.deadline;
    }
public:
    std::mutex mutex;
    std::atomic<bool> cancelled{false};
    explicit TextModel(const std::string& path, int threads = 2, int gpuLayers = 0, int promptMode = 0) :
        euro(promptMode == 2), riva(promptMode == 3), rawPrompt(promptMode == 1) {
        if (promptMode < 0 || promptMode > 3) throw std::invalid_argument("Unknown translation prompt mode");
        initializeRuntime();
        nativeError.clear();
        nativeOffloadSummary.clear();
        nativeModelBufferSummary.clear();
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = gpuLayers;
        model.reset(llama_model_load_from_file(path.c_str(), mp));
        if (!model) throw failure("llama_model_load_from_file failed: " + path);
        char architecture[128]{};
        llama_model_meta_val_str(model.get(), "general.architecture", architecture, sizeof(architecture));
        gemma = std::string(architecture) == "gemma3";
        qwen35 = std::string(architecture) == "qwen35";
        if (euro || riva) {
            if (std::string(architecture) != "llama") throw std::runtime_error("EuroLLM/Riva prompt requires the pinned Llama architecture");
        } else if (!gemma && !qwen35 && std::string(architecture) != "hunyuan-dense") throw std::runtime_error("Unsupported translation architecture: " + std::string(architecture));
        if (rawPrompt && !gemma) throw std::runtime_error("Raw translation prompt requires a Gemma 3 model");
        // Gemma's sliding-window cache can evict the start of a previous
        // prompt. Keep its existing full-prefill behavior until checkpointed
        // SWA reuse has model-level parity coverage.
        // Qwen3.5 combines attention with recurrent state. Its last state cannot
        // be rewound by deleting a token suffix, even when n_swa is zero.
        prefixCacheEnabled = !qwen35 && llama_model_n_swa(model.get()) == 0;
        if (!gemma) {
            // Hunyuan's own chat template ends assistant messages with this
            // control token. Some pinned GGUF conversions instead label an
            // ordinary '$' token as EOS, and llama's generic EOG discovery
            // does not recognize the Hunyuan assistant terminator.
            const char* marker = riva ? "</s>" : qwen35 || euro ? "<|im_end|>" : "<｜hy_place▁holder▁no▁2｜>";
            const auto stop = tokenize(marker, true);
            const auto* vocab = llama_model_get_vocab(model.get());
            if (stop.size() == 1 && llama_vocab_is_control(vocab, stop[0])) {
                const char* tokenText = llama_vocab_get_text(vocab, stop[0]);
                if (tokenText && std::string(marker) == tokenText) assistantEnd = stop[0];
            }
            const auto validMetadataEnd = [vocab](llama_token token) {
                return token != LLAMA_TOKEN_NULL && llama_vocab_is_eog(vocab, token) &&
                    llama_vocab_is_control(vocab, token);
            };
            if (assistantEnd == LLAMA_TOKEN_NULL &&
                !validMetadataEnd(llama_vocab_eos(vocab)) && !validMetadataEnd(llama_vocab_eot(vocab)))
                throw std::runtime_error(std::string(qwen35 ? "Qwen3.5" : "Hunyuan") + " translation model has no valid assistant terminator or control EOS/EOT token");
        }
        if (!rawPrompt) {
            promptPrefix = tokenize(riva ? "<s>System\\n" : euro ?
                "<|im_start|>system\n<|im_end|>\n<|im_start|>user\n" : gemma ? "<bos><start_of_turn>user\n" : qwen35 ?
                "<|im_start|>user\n" : "<｜hy_begin▁of▁sentence｜><｜hy_User｜>", true);
            // Index-Translate's publisher template with enable_thinking=false.
            // Prefill the empty reasoning block so only translation is generated.
            promptSuffix = tokenize(riva ? "</s>\\n<s>Assistant\\n" : euro ? "<|im_end|>\n<|im_start|>assistant\n" : gemma ? "<end_of_turn>\n<start_of_turn>model\n" : qwen35 ?
                "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n" : "<｜hy_Assistant｜>", true);
        }
        auto cp = llama_context_default_params();
        cp.n_ctx = 2048; cp.n_batch = 128; cp.n_ubatch = 128;
        cp.n_threads = std::max(1, threads); cp.n_threads_batch = std::max(1, threads);
        context.reset(llama_init_from_model(model.get(), cp));
        if (!context) throw failure("llama_init_from_model failed");
        auto tp = ggml_threadpool_params_default(cp.n_threads);
        // ASR, translation and TTS can share the CPU. Sleep between graphs
        // instead of leaving translation workers polling beside those engines.
        tp.poll = 0;
        threadpool.reset(ggml_threadpool_new(&tp));
        if (!threadpool) throw failure("Local translation worker pool creation failed");
        llama_attach_threadpool(context.get(), threadpool.get(), threadpool.get());
        llama_set_abort_callback(context.get(), abortDecode, this);
    }
    ~TextModel() {
        if (context) llama_detach_threadpool(context.get());
    }
    std::string translate(const std::string& prompt) {
        std::lock_guard<std::mutex> lock(mutex);
        try {
            if (cancelled.load()) throw std::runtime_error("Local translation cancelled");
            nativeError.clear();
            deadline = std::chrono::steady_clock::now() + std::chrono::seconds(20);
            if (prompt.empty() || prompt.size() > 12000) throw std::invalid_argument("Translation prompt exceeds 12000 UTF-8 bytes or is empty");
            // Explicit publisher-compatible Hunyuan chat envelope. The text itself
            // is tokenized without special-token parsing, so caption text cannot
            // inject a synthetic end-of-turn marker.
            const auto* vocab = llama_model_get_vocab(model.get());
            std::vector<llama_token> tokens = promptPrefix;
            std::string userContent = prompt;
            if (riva) {
                const auto separator = prompt.find("\n\n");
                if (separator == std::string::npos || separator > 180 || separator + 2 == prompt.size() ||
                    prompt.rfind("You are an expert at translating text from ", 0) != 0)
                    throw std::invalid_argument("Riva translation instruction is missing or invalid");
                const auto instruction = tokenize(prompt.substr(0, separator), false);
                tokens.insert(tokens.end(), instruction.begin(), instruction.end());
                // Match the pinned publisher tokenizer template, including its
                // literal backslash-n separators. User text is never parsed as tokens.
                const auto boundary = tokenize("</s>\\n<s>User\\n", true);
                tokens.insert(tokens.end(), boundary.begin(), boundary.end());
                userContent = prompt.substr(separator + 2);
            }
            const auto content = tokenize(userContent, false);
            tokens.insert(tokens.end(), content.begin(), content.end());
            tokens.insert(tokens.end(), promptSuffix.begin(), promptSuffix.end());
            constexpr int maxOutput = 384;
            if (tokens.size() + maxOutput > 2048) throw std::runtime_error("Caption exceeds the local translation context (2048 tokens)");
            if (tokens.empty()) throw std::runtime_error("llama_tokenize returned no translation tokens");
            auto memory = llama_get_memory(context.get());
            size_t reused = 0;
            if (prefixCacheEnabled && !cachedPrompt.empty() &&
                llama_memory_seq_pos_min(memory, 0) == 0 &&
                llama_memory_seq_pos_max(memory, 0) >= static_cast<llama_pos>(cachedPrompt.size() - 1)) {
                while (reused < cachedPrompt.size() && reused < tokens.size() &&
                       cachedPrompt[reused] == tokens[reused]) ++reused;
                // A fresh decode is required even for an identical/shorter prompt:
                // existing logits belong to the previous generated output.
                reused = std::min(reused, tokens.size() - 1);
                if (reused && !llama_memory_seq_rm(memory, 0, static_cast<llama_pos>(reused), -1)) reused = 0;
            }
            if (!reused) llama_memory_clear(memory, true);
            auto decode = [this](llama_batch batch) {
                if (abortDecode(this)) throw std::runtime_error(cancelled.load() ? "Local translation cancelled" : "Local translation exceeded 20 seconds");
                const int status = llama_decode(context.get(), batch);
                if (status != 0) throw failure(cancelled.load() ? "Local translation cancelled" : abortDecode(this) ? "Local translation exceeded 20 seconds" : "llama_decode failed: " + std::to_string(status));
            };
            std::array<llama_pos, 128> positions{};
            for (size_t i = reused; i < tokens.size(); i += 128) {
                const auto count = std::min<size_t>(128, tokens.size() - i);
                for (size_t j = 0; j < count; ++j) positions[j] = static_cast<llama_pos>(i + j);
                auto batch = llama_batch_get_one(tokens.data() + i, static_cast<int32_t>(count));
                batch.pos = positions.data();
                decode(batch);
            }
            std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(), llama_sampler_free);
            if (!sampler) throw failure("Local translation sampler creation failed");
            std::string output;
            for (int i = 0; i < maxOutput; ++i) {
                auto token = llama_sampler_sample(sampler.get(), context.get(), -1);
                // Caption text was tokenized literally above. Match only the
                // actual control-token ID, never a substring of generated text.
                // Hunyuan metadata must not turn ordinary '$' text into EOS.
                const bool metadataEnd = llama_vocab_is_eog(vocab, token) &&
                    (gemma || llama_vocab_is_control(vocab, token));
                if ((assistantEnd != LLAMA_TOKEN_NULL && token == assistantEnd) || metadataEnd) {
                    if (output.find_first_not_of(" \n\r\t") == std::string::npos) throw std::runtime_error("Local translation model returned empty text");
                    if (prefixCacheEnabled) cachedPrompt = tokens;
                    return output;
                }
                std::array<char, 128> piece{};
                int n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
                if (n < 0) {
                    std::vector<char> longPiece(static_cast<size_t>(-n));
                    n = llama_token_to_piece(vocab, token, longPiece.data(), longPiece.size(), 0, false);
                    if (n < 0) throw std::runtime_error("llama_token_to_piece failed");
                    output.append(longPiece.data(), static_cast<size_t>(n));
                } else output.append(piece.data(), static_cast<size_t>(n));
                llama_pos position = static_cast<llama_pos>(tokens.size() + i);
                auto batch = llama_batch_get_one(&token, 1);
                batch.pos = &position;
                decode(batch);
            }
            throw std::runtime_error("Local translation exceeded 384 output tokens; incomplete text was not published");
        } catch (...) {
            // A failed/aborted decode can leave a partially updated KV cache.
            // Retain no reusable prefix or generated context after failure.
            cachedPrompt.clear();
            llama_memory_clear(llama_get_memory(context.get()), true);
            throw;
        }
    }
};
}
