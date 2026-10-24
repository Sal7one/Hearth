// Exercise the production transducer adapter and session; only the external model API is faked.
#include "../backends/qwen_backend.cpp"
#include "../speech_session.h"
#include <iostream>

namespace {
int checks=0, recognizers=0, streams=0, results=0, decodes=0, languageOptions=0;
std::string output="Bonjour le monde.", detected;
bool failRecognizer=false;
void check(bool value) {
    ++checks;
    if(!value) throw std::runtime_error("Phonon adapter assertion failed at check "+std::to_string(checks));
}
template<class F> void fails(F action,const std::string& expected) {
    try { action(); }
    catch(const std::exception& error) { check(std::string(error.what()).find(expected)!=std::string::npos);return; }
    throw std::runtime_error("Expected failure: "+expected);
}
HearthSpeechConfig config(const char* language="auto") {
    return {sizeof(HearthSpeechConfig),"tokens.txt","joiner.int8.onnx","encoder.int8.onnx","decoder.int8.onnx","",
        language,2,3,1000,200,-45,"cpu",""};
}
}

struct SherpaOnnxOfflineRecognizer {};
struct SherpaOnnxOfflineStream {};
extern "C" {
const SherpaOnnxOfflineRecognizer* SherpaOnnxCreateOfflineRecognizer(const SherpaOnnxOfflineRecognizerConfig* config) {
    check(config && config->feat_config.sample_rate==16000 && config->feat_config.feature_dim==128);
    check(config->model_config.num_threads==2 && std::string(config->model_config.provider)=="cpu");
    check(std::string(config->model_config.model_type)=="nemo_transducer");
    check(std::string(config->model_config.tokens)=="tokens.txt");
    check(std::string(config->model_config.transducer.encoder)=="encoder.int8.onnx");
    check(std::string(config->model_config.transducer.decoder)=="decoder.int8.onnx");
    check(std::string(config->model_config.transducer.joiner)=="joiner.int8.onnx");
    check(std::string(config->decoding_method)=="greedy_search");
    if(failRecognizer)return nullptr;
    ++recognizers;return new SherpaOnnxOfflineRecognizer;
}
void SherpaOnnxDestroyOfflineRecognizer(const SherpaOnnxOfflineRecognizer* p) { --recognizers;delete p; }
const SherpaOnnxOfflineStream* SherpaOnnxCreateOfflineStream(const SherpaOnnxOfflineRecognizer*) {
    ++streams;return new SherpaOnnxOfflineStream;
}
void SherpaOnnxDestroyOfflineStream(const SherpaOnnxOfflineStream* p) { --streams;delete p; }
void SherpaOnnxOfflineStreamSetOption(const SherpaOnnxOfflineStream*,const char*,const char*) { ++languageOptions; }
void SherpaOnnxAcceptWaveformOffline(const SherpaOnnxOfflineStream*,int32_t sampleRate,const float* samples,int32_t count) {
    check(sampleRate==16000 && samples && count>0 && count<=16000);
}
void SherpaOnnxDecodeOfflineStream(const SherpaOnnxOfflineRecognizer*,const SherpaOnnxOfflineStream*) { ++decodes; }
const SherpaOnnxOfflineRecognizerResult* SherpaOnnxGetOfflineStreamResult(const SherpaOnnxOfflineStream*) {
    ++results;return new SherpaOnnxOfflineRecognizerResult{output.c_str(),detected.empty()?nullptr:detected.c_str()};
}
void SherpaOnnxDestroyOfflineRecognizerResult(const SherpaOnnxOfflineRecognizerResult* p) { --results;delete p; }
}

int main() {
    using namespace stt::speech;
    const auto& backend=*hearth_phonon_backend_v1();
    check(std::string(backend.id)=="phonon" && std::string(backend.revision)==HEARTH_QWEN_REVISION);
    std::vector<float> audio(16000,0.25f);
    {
        SpeechNativeSession session(backend,config());
        check(recognizers==1 && session.executionInfo()=="CPU · ONNX Runtime");
        const auto first=session.push(audio.data(),audio.size(),0);
        check(first.find(output)!=std::string::npos);
        check(first.find("\"language\":\"\"")!=std::string::npos); // Multilingual Auto is not English.
        check(first.find("\"utteranceId\":1")!=std::string::npos);
        check(session.finish().find("\"events\":[]")!=std::string::npos);
        check(decodes==1 && streams==0 && results==0);
        session.reset();
        check(session.push(audio.data(),8000,0).find("\"events\":[]")!=std::string::npos);
        check(session.finish().find(output)!=std::string::npos);
    }
    check(recognizers==0 && streams==0 && results==0);
    {
        output="Привет мир.";
        SpeechNativeSession session(backend,config("ru"));
        check(session.push(audio.data(),audio.size(),0).find("\"language\":\"ru\"")!=std::string::npos);
    }
    {
        // Legacy English-only profiles still pin English before entering this shared backend.
        output="Hello world.";
        SpeechNativeSession session(backend,config("en"));
        check(session.push(audio.data(),audio.size(),0).find("\"language\":\"en\"")!=std::string::npos);
    }
    {
        output.clear();detected.clear();
        SpeechNativeSession session(backend,config());
        const auto empty=session.push(audio.data(),audio.size(),0);
        check(empty.find("\"text\":\"\"")!=std::string::npos && empty.find("\"language\":\"\"")!=std::string::npos);
        check(session.finish().find("\"events\":[]")!=std::string::npos);
    }
    {
        const auto before=decodes;
        std::vector<float> silence(16000,0);
        SpeechNativeSession session(backend,config());
        check(session.push(silence.data(),silence.size(),0).find("\"events\":[]")!=std::string::npos);
        check(session.finish().find("\"events\":[]")!=std::string::npos && decodes==before);
    }
    check(languageOptions==0); // A source declaration is not a fabricated transducer language hint.
    check(recognizers==0 && streams==0 && results==0);
    failRecognizer=true;
    fails([&] { SpeechNativeSession session(backend,config()); },"SherpaOnnxCreateOfflineRecognizer returned null");
    check(recognizers==0 && streams==0 && results==0);
    std::cout<<"phonon_backend_test: "<<checks<<" checks PASS\n";
}
