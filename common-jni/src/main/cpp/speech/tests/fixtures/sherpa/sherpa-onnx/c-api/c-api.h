#pragma once
// Minimal test double for the external Sherpa C API subset consumed by qwen_backend.cpp.
// Real builds use the pinned upstream header; this fixture tests adapter behavior without weights.
#include <cstdint>
extern "C" {
struct SherpaOnnxOfflineRecognizer;
struct SherpaOnnxOfflineStream;
struct SherpaOnnxOfflineRecognizerResult { const char* text; const char* lang; };
struct SherpaOnnxOfflineRecognizerConfig {
    struct { int32_t sample_rate, feature_dim; } feat_config;
    struct {
        int32_t num_threads;
        const char* provider;
        const char* model_type;
        const char* tokens;
        struct { const char* encoder; const char* decoder; const char* joiner; } transducer;
        struct { const char* model; } omnilingual;
        struct { const char* encoder; const char* merged_decoder; } moonshine;
        struct {
            const char* conv_frontend;
            const char* encoder;
            const char* decoder;
            const char* tokenizer;
            int32_t max_total_len, max_new_tokens;
            float temperature, top_p;
            int32_t seed;
        } qwen3_asr;
    } model_config;
    const char* decoding_method;
};
const SherpaOnnxOfflineRecognizer* SherpaOnnxCreateOfflineRecognizer(const SherpaOnnxOfflineRecognizerConfig*);
void SherpaOnnxDestroyOfflineRecognizer(const SherpaOnnxOfflineRecognizer*);
const SherpaOnnxOfflineStream* SherpaOnnxCreateOfflineStream(const SherpaOnnxOfflineRecognizer*);
void SherpaOnnxDestroyOfflineStream(const SherpaOnnxOfflineStream*);
void SherpaOnnxOfflineStreamSetOption(const SherpaOnnxOfflineStream*, const char*, const char*);
void SherpaOnnxAcceptWaveformOffline(const SherpaOnnxOfflineStream*, int32_t, const float*, int32_t);
void SherpaOnnxDecodeOfflineStream(const SherpaOnnxOfflineRecognizer*, const SherpaOnnxOfflineStream*);
const SherpaOnnxOfflineRecognizerResult* SherpaOnnxGetOfflineStreamResult(const SherpaOnnxOfflineStream*);
void SherpaOnnxDestroyOfflineRecognizerResult(const SherpaOnnxOfflineRecognizerResult*);
}
