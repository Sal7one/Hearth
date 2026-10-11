#pragma once
#include "vision_limits.h"
#include "../translation/text_model.h"
#include "../common/utf8_utils.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include <cstring>

namespace hearth::vision {

/** Capture/storage-independent RGB OCR. One context per owner, one serialized request.
 * The pinned mtmd handles image normalization, grid positions and GLM projector math.
 * No camera, Android, file parsing, networking, provider keys or FFmpeg belongs here. */
class VisionModel {
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model_{nullptr, llama_model_free};
    std::unique_ptr<llama_context, decltype(&llama_free)> context_{nullptr, llama_free};
    std::unique_ptr<mtmd_context, decltype(&mtmd_free)> vision_{nullptr, mtmd_free};
    std::mutex mutex_;
    std::chrono::steady_clock::time_point deadline_;
    static bool abortDecode(void* object) {
        auto& self = *static_cast<VisionModel*>(object);
        return self.cancelled.load() || std::chrono::steady_clock::now() >= self.deadline_;
    }
    static bool observeVision(ggml_tensor* tensor, bool ask, void* object) {
        // Observe matrix boundaries; do not copy or inspect tensor payloads.
        // Returning false after evaluation cancels mtmd's scheduler graph.
        if (ask) return tensor->op == GGML_OP_MUL_MAT;
        return !abortDecode(object);
    }
    void checkInterrupted() {
        if (abortDecode(this)) throw std::runtime_error(cancelled.load()
            ? "Vision OCR cancelled" : "Vision OCR exceeded 120 seconds");
    }
    void checkStatus(int status, const std::string& operation) {
        checkInterrupted();
        if (status != 0) throw transiber::failure(operation + ": " + std::to_string(status));
    }
public:
    std::atomic<bool> cancelled{false};
    explicit VisionModel(const std::string& decoder, const std::string& projector, int threads = 2) {
        if (sizeof(void*) < 8) throw std::runtime_error("GLM-OCR requires a 64-bit process; choose a smaller OCR reader on this device");
        if (threads < 1 || threads > 8) throw std::invalid_argument("Vision OCR threads must be 1–8");
        transiber::initializeRuntime();
        static std::once_flag logging;
        std::call_once(logging, [] { mtmd_helper_log_set(transiber::captureLog, nullptr); });
        transiber::nativeError.clear();
        deadline_ = std::chrono::steady_clock::now() + std::chrono::seconds(kDeadlineSeconds);
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        model_.reset(llama_model_load_from_file(decoder.c_str(), mp));
        if (!model_) throw transiber::failure("Vision OCR decoder load failed");
        char architecture[128]{};
        llama_model_meta_val_str(model_.get(), "general.architecture", architecture, sizeof(architecture));
        // GLM-OCR declares 17 blocks including its optional next-token-prediction
        // block; the production decoder may skip that MTP block.
        if (std::strcmp(architecture, "glm4") != 0 ||
            llama_model_n_layer(model_.get()) + llama_model_n_layer_nextn(model_.get()) != 17)
            throw std::invalid_argument("This vision adapter currently supports the 17-layer GLM-OCR decoder only");
        auto cp = llama_context_default_params();
        cp.n_ctx = kContextTokens; cp.n_batch = 128; cp.n_ubatch = 128;
        cp.n_threads = threads; cp.n_threads_batch = threads;
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
        context_.reset(llama_init_from_model(model_.get(), cp));
        if (!context_) throw transiber::failure("Vision OCR decoder context creation failed");
        llama_set_abort_callback(context_.get(), abortDecode, this);
        auto vp = mtmd_context_params_default();
        vp.use_gpu = false; vp.n_threads = threads; vp.warmup = false;
        vp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
        vp.image_min_tokens = 64; vp.image_max_tokens = kImageTokens;
        vp.cb_eval = observeVision; vp.cb_eval_user_data = this;
        vision_.reset(mtmd_init_from_file(projector.c_str(), model_.get(), vp));
        if (!vision_ || !mtmd_support_vision(vision_.get()))
            throw transiber::failure("Vision OCR projector initialization failed");
    }

    std::string recognize(const unsigned char* rgb, int width, int height) {
        std::lock_guard<std::mutex> lock(mutex_);
        validateImage(width, height, static_cast<std::size_t>(width) * static_cast<std::size_t>(height));
        if (!rgb) throw std::invalid_argument("Vision OCR requires RGB pixels");
        if (cancelled.load()) throw std::runtime_error("Vision OCR cancelled");
        transiber::nativeError.clear();
        deadline_ = std::chrono::steady_clock::now() + std::chrono::seconds(kDeadlineSeconds);
        llama_memory_clear(llama_get_memory(context_.get()), true);
        std::unique_ptr<mtmd_bitmap, decltype(&mtmd_bitmap_free)> bitmap(
            mtmd_bitmap_init(width, height, rgb), mtmd_bitmap_free);
        if (!bitmap) throw std::runtime_error("Vision OCR bitmap allocation failed");
        // Exact built-in ChatGLM4 envelope; no arbitrary prompt strings cross JNI.
        const std::string content = std::string(mtmd_default_marker()) + "\nText Recognition:";
        const llama_chat_message message{"user", content.c_str()};
        std::vector<char> prompt(1024);
        int length = llama_chat_apply_template("chatglm4", &message, 1, true, prompt.data(), prompt.size());
        if (length < 0 || static_cast<std::size_t>(length) >= prompt.size())
            throw std::runtime_error("Vision OCR GLM prompt formatting failed");
        const mtmd_input_text input{prompt.data(), static_cast<std::size_t>(length), false, true};
        std::unique_ptr<mtmd_input_chunks, decltype(&mtmd_input_chunks_free)> chunks(
            mtmd_input_chunks_init(), mtmd_input_chunks_free);
        const mtmd_bitmap* images[] = {bitmap.get()};
        checkStatus(mtmd_tokenize(vision_.get(), chunks.get(), &input, images, 1), "Vision OCR image preprocessing failed");
        const auto tokens = mtmd_helper_get_n_tokens(chunks.get());
        if (tokens + kOutputTokens > kContextTokens)
            throw std::runtime_error("Vision OCR image exceeds the 4096-token context; use a smaller page or crop");
        llama_pos position = 0;
        checkStatus(mtmd_helper_eval_chunks(vision_.get(), context_.get(), chunks.get(),
            0, 0, 128, true, &position), "Vision OCR image/decoder evaluation failed");
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
            llama_sampler_init_greedy(), llama_sampler_free);
        if (!sampler) throw std::runtime_error("Vision OCR sampler allocation failed");
        const auto* vocab = llama_model_get_vocab(model_.get());
        std::string output;
        for (int i = 0; i < kOutputTokens; ++i) {
            checkInterrupted();
            auto token = llama_sampler_sample(sampler.get(), context_.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) {
                if (output.find_first_not_of(" \n\r\t") == std::string::npos)
                    throw std::runtime_error("Vision OCR returned no readable text");
                std::vector<std::uint16_t> validated;
                if (!common_jni::text::utf8ToUtf16(output, validated))
                    throw std::runtime_error("Vision OCR returned invalid UTF-8 text");
                return output;
            }
            std::vector<char> piece(128);
            int size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            if (size < 0) {
                const auto required = -static_cast<std::int64_t>(size);
                if (required > static_cast<std::int64_t>(kMaximumOutputBytes))
                    throw std::runtime_error("Vision OCR token exceeds the bounded output size");
                piece.resize(static_cast<std::size_t>(required));
                size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            }
            if (size < 0) throw std::runtime_error("Vision OCR token decoding failed");
            if (output.size() + static_cast<std::size_t>(size) > kMaximumOutputBytes)
                throw std::runtime_error("Vision OCR output exceeds 65536 bytes; incomplete text was not published");
            output.append(piece.data(), size);
            auto batch = llama_batch_get_one(&token, 1);
            checkStatus(llama_decode(context_.get(), batch), "Vision OCR generation failed");
        }
        throw std::runtime_error("Vision OCR exceeded 1536 output tokens; incomplete text was not published. Crop or split the page.");
    }
};
} // namespace hearth::vision
