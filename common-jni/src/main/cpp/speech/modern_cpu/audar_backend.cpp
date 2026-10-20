#include "../backends/backend_guard.h"
#include "../utterance_segmenter.h"
#include "audar_protocol.h"
#include "versions.h"
#include "llama.h"
#include "ggml-cpu.h"
#include "gguf.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include <array>
#include <chrono>
#include <cstring>
#include <deque>
#include <memory>
#include <mutex>

namespace stt::speech {
namespace {
template<class T, void (*Release)(T*)> using Handle = std::unique_ptr<T, decltype(Release)>;
void verifyProjector(const char* path) {
    Handle<gguf_context, gguf_free> metadata(gguf_init_from_file(path, {true, nullptr}), gguf_free);
    if (!metadata) throw std::runtime_error("Cannot read Audar audio encoder GGUF metadata");
    auto string = [&](const char* key) {
        const auto index = gguf_find_key(metadata.get(), key);
        return index >= 0 && gguf_get_kv_type(metadata.get(), index) == GGUF_TYPE_STRING ? std::string(gguf_get_val_str(metadata.get(), index)) : "";
    };
    if (string("clip.projector_type") != "qwen3a" && string("clip.audio.projector_type") != "qwen3a")
        throw std::invalid_argument("Audar requires a Qwen3-ASR audio projector");
    bool bf16 = false;
    for (int64_t i = 0; i < gguf_get_n_tensors(metadata.get()); ++i) {
        const auto type = gguf_get_tensor_type(metadata.get(), i);
        bf16 |= type == GGML_TYPE_BF16;
        const std::string name = gguf_get_tensor_name(metadata.get(), i);
        // The publisher BF16 projector retains four convolution/frontend tensors
        // in F16. Do not reject that exact mixed layout or allow an all-F16 encoder.
        const bool frontendF16 = type == GGML_TYPE_F16 &&
            (name == "a.conv2d.1.weight" || name == "a.conv2d.2.weight" ||
             name == "a.conv2d.3.weight" || name == "a.conv_out.weight");
        if (type != GGML_TYPE_F32 && type != GGML_TYPE_BF16 && !frontendF16)
            throw std::invalid_argument("Audar requires the publisher BF16 encoder with its F16 convolution frontend; quantized encoders are unsupported");
    }
    if (!bf16) throw std::invalid_argument("Audar encoder contains no BF16 weights");
}
struct Audar {
    Handle<llama_model, llama_model_free> model{nullptr, llama_model_free};
    Handle<ggml_threadpool, ggml_threadpool_free> pool{nullptr, ggml_threadpool_free};
    Handle<llama_context, llama_free> context{nullptr, llama_free};
    Handle<mtmd_context, mtmd_free> projector{nullptr, mtmd_free};
    UtteranceSegmenter segmenter;
    std::string prefix, execution;
    std::chrono::steady_clock::time_point deadline;
    struct Text { std::string text, language; int64_t end; };
    std::deque<Text> ready;
    Text current;
    static bool abort(void* data) { return std::chrono::steady_clock::now() >= static_cast<Audar*>(data)->deadline; }
    explicit Audar(const HearthSpeechConfig& c) : segmenter(c.max_utterance_ms,c.silence_ms,c.silence_threshold_db) {
        if (!c.acceleration || std::strcmp(c.acceleration,"cpu")) throw std::invalid_argument("Audar currently supports CPU only");
        if (!c.model || !*c.model || !c.encoder || !*c.encoder || !c.language || c.num_threads < 1 || c.num_threads > 8)
            throw std::invalid_argument("Audar requires decoder, BF16 encoder, source language and 1–8 threads");
        prefix = audarLanguagePrefix(c.language);
        verifyProjector(c.encoder);
        static std::once_flag initialize;
        std::call_once(initialize, [] { llama_backend_init(); });
        auto mp = llama_model_default_params(); mp.n_gpu_layers = 0;
        model.reset(llama_model_load_from_file(c.model, mp));
        if (!model) throw std::runtime_error("Audar decoder load failed");
        std::array<char,128> architecture{};
        if (llama_model_meta_val_str(model.get(),"general.architecture",architecture.data(),architecture.size()) < 0 || std::string(architecture.data()) != "qwen3vl")
            throw std::invalid_argument("Audar requires the Qwen3-ASR decoder architecture");
        auto cp = llama_context_default_params(); cp.n_ctx = 2048; cp.n_batch = 512; cp.n_ubatch = 512;
        cp.n_threads = c.num_threads; cp.n_threads_batch = c.num_threads;
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
        context.reset(llama_init_from_model(model.get(),cp));
        if (!context) throw std::runtime_error("Audar decoder context allocation failed");
        auto tp = ggml_threadpool_params_default(c.num_threads); tp.poll = 0;
        pool.reset(ggml_threadpool_new(&tp));
        if (!pool) throw std::runtime_error("Audar worker pool allocation failed");
        llama_attach_threadpool(context.get(),pool.get(),pool.get());
        llama_set_abort_callback(context.get(),abort,this);
        auto pp = mtmd_context_params_default(); pp.use_gpu = false; pp.n_threads = c.num_threads;
        pp.warmup = false; pp.print_timings = false;
        projector.reset(mtmd_init_from_file(c.encoder,model.get(),pp));
        if (!projector || !mtmd_support_audio(projector.get()) || mtmd_get_audio_sample_rate(projector.get()) != 16000)
            throw std::runtime_error("Audar requires a 16 kHz audio projector");
        execution = "Audar Flash · BF16 encoder: CPU · Qwen3-ASR decoder: CPU · greedy · threads: " + std::to_string(c.num_threads);
    }
    ~Audar() { if (context) llama_detach_threadpool(context.get()); }
    void decode(const std::vector<float>& audio, int64_t end) {
        if (ready.size() >= 32) throw std::runtime_error("Audar result queue full; drain after each push");
        deadline = std::chrono::steady_clock::now() + std::chrono::seconds(30);
        llama_memory_clear(llama_get_memory(context.get()),true);
        try {
            Handle<mtmd_bitmap, mtmd_bitmap_free> bitmap(mtmd_bitmap_init_from_audio(audio.size(),audio.data()), mtmd_bitmap_free);
            Handle<mtmd_input_chunks, mtmd_input_chunks_free> chunks(mtmd_input_chunks_init(), mtmd_input_chunks_free);
            if (!bitmap || !chunks) throw std::runtime_error("Audar audio input allocation failed");
            // Qwen-ASR ChatML. mtmd supplies audio_start/audio_end around its actual audio marker.
            const std::string prompt = "<|im_start|>system\n<|im_end|>\n<|im_start|>user\n" + std::string(mtmd_default_marker()) +
                "<|im_end|>\n<|im_start|>assistant\n" + prefix;
            const mtmd_input_text input{prompt.c_str(),prompt.size(),false,true};
            const mtmd_bitmap* media[] = {bitmap.get()};
            const auto tokenized = mtmd_tokenize(projector.get(),chunks.get(),&input,media,1);
            if (tokenized) throw std::runtime_error("Audar audio tokenization failed: " + std::to_string(tokenized));
            if (mtmd_helper_get_n_tokens(chunks.get()) + 256 >= llama_n_ctx(context.get())) throw std::runtime_error("Audar utterance exceeds its bounded context");
            llama_pos past = 0;
            const auto evaluated = mtmd_helper_eval_chunks(projector.get(),context.get(),chunks.get(),0,0,512,true,&past);
            if (evaluated) throw std::runtime_error("Audar encoder/prompt decode failed: " + std::to_string(evaluated));
            Handle<llama_sampler, llama_sampler_free> sampler(llama_sampler_init_greedy(),llama_sampler_free);
            if (!sampler) throw std::runtime_error("Audar sampler allocation failed");
            const auto* vocab = llama_model_get_vocab(model.get());
            std::string generated;
            for (int i = 0; i < 256; ++i) {
                if (abort(this)) throw std::runtime_error("Audar exceeded its 30-second utterance deadline");
                auto token = llama_sampler_sample(sampler.get(),context.get(),-1);
                if (llama_vocab_is_eog(vocab,token)) {
                    auto transcript = audarParse(generated,prefix);
                    ready.push_back({std::move(transcript.text),std::move(transcript.language),end});
                    llama_memory_clear(llama_get_memory(context.get()),true);
                    return;
                }
                std::array<char,256> piece{};
                int n = llama_token_to_piece(vocab,token,piece.data(),piece.size(),0,true);
                if (n < 0) {
                    std::vector<char> large(static_cast<size_t>(-n));
                    n = llama_token_to_piece(vocab,token,large.data(),large.size(),0,true);
                    if (n < 0) throw std::runtime_error("Audar token decoding failed");
                    generated.append(large.data(),static_cast<size_t>(n));
                } else generated.append(piece.data(),static_cast<size_t>(n));
                if (generated.size() > 65536) throw std::runtime_error("Audar transcript exceeds 64 KiB");
                auto batch = llama_batch_get_one(&token,1); batch.pos = &past;
                const auto code = llama_decode(context.get(),batch);
                if (code) throw std::runtime_error("Audar token decode failed: " + std::to_string(code));
                ++past;
            }
            throw std::runtime_error("Audar exhausted 256 output tokens before EOS; incomplete text was not published");
        } catch (...) { llama_memory_clear(llama_get_memory(context.get()),true); throw; }
    }
    auto consumer() { return [this](const std::vector<float>& audio,int64_t end) { decode(audio,end); }; }
};
int create(const HearthSpeechConfig* c,void** out) { return guarded([&] { if (!out) throw std::invalid_argument("Missing Audar output handle"); *out=nullptr; requireConfig(c); *out=new Audar(*c); }); }
void destroy(void* p) { delete static_cast<Audar*>(p); }
int push(void* p,const float* a,size_t n) { return guarded([&] { auto& s=*static_cast<Audar*>(p); s.segmenter.push(a,n,s.consumer()); }); }
int next(void* p,HearthSpeechResult* r,int* available) { return guarded([&] {
    if (!r || !available || r->size!=sizeof(*r)) throw std::invalid_argument("Audar result ABI mismatch");
    auto& s=*static_cast<Audar*>(p); *available=!s.ready.empty(); if (!*available)return;
    s.current=std::move(s.ready.front());s.ready.pop_front(); *r={sizeof(*r),s.current.text.c_str(),s.current.language.c_str(),1,s.current.end};
}); }
int finish(void* p) { return guarded([&] { auto& s=*static_cast<Audar*>(p);s.segmenter.finish(s.consumer()); }); }
int reset(void* p) { return guarded([&] { auto& s=*static_cast<Audar*>(p);s.segmenter.reset();s.ready.clear();s.current={};llama_memory_clear(llama_get_memory(s.context.get()),true); }); }
const HearthSpeechBackend api{HEARTH_SPEECH_ABI_VERSION,sizeof(HearthSpeechBackend),"audar_mtmd",HEARTH_AUDAR_REVISION,
    create,destroy,push,next,finish,reset,lastError,[](void* p){return static_cast<Audar*>(p)->execution.c_str();}};
}
}
extern "C" __attribute__((visibility("default"))) const HearthSpeechBackend* hearth_audar_backend_v1() { return &stt::speech::api; }
