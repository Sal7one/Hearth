#pragma once
#include <ctranslate2/translator.h>
#include <sentencepiece_processor.h>
#include <atomic>
#include <chrono>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace hearth {
class Ct2Model {
    bool small;
    sentencepiece::SentencePieceProcessor source, target;
    ctranslate2::Translator translator;
    std::mutex mutex;
    static ctranslate2::ReplicaPoolConfig pool() {
        ctranslate2::ReplicaPoolConfig config;
        config.num_threads_per_replica = 2;
        config.max_queued_batches = 1;
        return config;
    }
public:
    std::atomic<bool> cancelled{false};
    Ct2Model(const std::string& directory, int mode) : small(mode == 1),
        translator(directory, ctranslate2::Device::CPU,
                   mode == 1 ? ctranslate2::ComputeType::INT8_FLOAT32 : ctranslate2::ComputeType::FLOAT32,
                   {0}, false, pool()) {
        if (mode != 0 && mode != 1) throw std::invalid_argument("Unsupported CT2 tokenizer mode");
        auto status = source.Load(directory + (small ? "/sentencepiece.bpe.model" : "/src.spm.model"));
        if (!status.ok()) throw std::runtime_error("CT2 source tokenizer: " + status.ToString());
        status = target.Load(directory + (small ? "/sentencepiece.bpe.model" : "/tgt.spm.model"));
        if (!status.ok()) throw std::runtime_error("CT2 target tokenizer: " + status.ToString());
    }
    std::string translate(const std::string& text, const std::string& language) {
        std::lock_guard<std::mutex> lock(mutex);
        if (cancelled.load()) throw std::runtime_error("CT2 translation cancelled");
        if (text.empty() || text.size() > 12000) throw std::invalid_argument("CT2 input must contain 1–12000 UTF-8 bytes");
        std::vector<std::string> pieces;
        auto status = source.Encode(text, &pieces);
        if (!status.ok()) throw std::runtime_error("CT2 encode failed: " + status.ToString());
        if (pieces.empty() || pieces.size() > 1022) throw std::invalid_argument("CT2 input exceeds 1022 tokens; split the text first");
        if (small) {
            if (language.size() < 2 || language.size() > 3 || language.find_first_not_of("abcdefghijklmnopqrstuvwxyz") != std::string::npos)
                throw std::invalid_argument("Invalid CT2 target language");
            pieces.insert(pieces.begin(), "__" + language + "__");
        }
        // Both publisher tokenizers append EOS themselves; add_source_eos=false
        // in QuickMT's CT2 config means the runtime must not append a second EOS.
        pieces.push_back("</s>");
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(20);
        ctranslate2::TranslationOptions options;
        options.beam_size = 1;
        options.max_input_length = 0;
        options.max_decoding_length = 384;
        options.disable_unk = !small;
        options.callback = [this, deadline](ctranslate2::GenerationStepResult) {
            return cancelled.load() || std::chrono::steady_clock::now() >= deadline;
        };
        const auto result = translator.translate_batch({pieces}, options);
        if (cancelled.load()) throw std::runtime_error("CT2 translation cancelled");
        if (std::chrono::steady_clock::now() >= deadline) throw std::runtime_error("CT2 translation exceeded 20 seconds");
        if (result.size() != 1 || result[0].hypotheses.size() != 1 || result[0].hypotheses[0].empty())
            throw std::runtime_error("CT2 returned no translation");
        // A length-limited hypothesis is not a complete translation.
        if (result[0].hypotheses[0].size() >= options.max_decoding_length)
            throw std::runtime_error("CT2 translation exceeded 384 output tokens; incomplete text was not published");
        std::string output;
        status = target.Decode(result[0].hypotheses[0], &output);
        if (!status.ok()) throw std::runtime_error("CT2 decode failed: " + status.ToString());
        if (output.find_first_not_of(" \r\n\t") == std::string::npos) throw std::runtime_error("CT2 returned empty text");
        return output;
    }
};
}
