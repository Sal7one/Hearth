// Supertonic 3 inference adapted from supertone-inc/supertonic (MIT).
// See assets/licenses/voice/ for attribution and pinned upstream provenance.
#include "supertonic.h"
#include "voice_bounds.h"
#include "../onnx/include/onnxruntime/onnxruntime_c_api.h"
#include <algorithm>
#include <atomic>
#include <cmath>
#include <mutex>
#include <random>
#include <stdexcept>

namespace hearth::voice {
namespace {
const OrtApi* api() {
    static auto* value=OrtGetApiBase()->GetApi(ORT_API_VERSION);
    if(!value) throw std::runtime_error("Supertonic: ONNX Runtime API unavailable");
    return value;
}
void checked(OrtStatus* status) {
    if(status) {
        std::string message=api()->GetErrorMessage(status);
        api()->ReleaseStatus(status);
        throw std::runtime_error(message);
    }
}
struct Tensor {
    OrtValue* value=nullptr;
    Tensor()=default;
    Tensor(const Tensor&)=delete;
    Tensor(Tensor&& other) noexcept:value(other.value){other.value=nullptr;}
    ~Tensor(){if(value)api()->ReleaseValue(value);}
};
struct Output { std::vector<float> values; std::vector<int64_t> shape; };
}
struct Supertonic::Impl {
    OrtEnv* env=nullptr;
    OrtSessionOptions* options=nullptr;
    OrtMemoryInfo* memory=nullptr;
    OrtRunOptions* run=nullptr;
    OrtSession* sessions[4]={};
    std::atomic<bool> cancelled{false};
    std::mutex mutex;
    ~Impl() {
        for(auto* session:sessions)if(session)api()->ReleaseSession(session);
        if(run)api()->ReleaseRunOptions(run);
        if(memory)api()->ReleaseMemoryInfo(memory);
        if(options)api()->ReleaseSessionOptions(options);
        if(env)api()->ReleaseEnv(env);
    }
    void initialize(const std::string& dir) {
        checked(api()->CreateEnv(ORT_LOGGING_LEVEL_ERROR,"hearth-voice",&env));
        checked(api()->CreateSessionOptions(&options));
        checked(api()->SetIntraOpNumThreads(options,2));
        checked(api()->SetInterOpNumThreads(options,1));
        checked(api()->SetSessionGraphOptimizationLevel(options,ORT_ENABLE_ALL));
        checked(api()->AddSessionConfigEntry(options,"session.intra_op.allow_spinning","0"));
        checked(api()->CreateCpuMemoryInfo(OrtArenaAllocator,OrtMemTypeDefault,&memory));
        checked(api()->CreateRunOptions(&run));
        const char* names[]={"duration_predictor.onnx","text_encoder.onnx","vector_estimator.onnx","vocoder.onnx"};
        for(int i=0;i<4;++i)checked(api()->CreateSession(env,(dir+"/"+names[i]).c_str(),options,&sessions[i]));
    }
    Tensor tensor(void* data,size_t bytes,const std::vector<int64_t>& shape,ONNXTensorElementDataType type) {
        Tensor out;
        checked(api()->CreateTensorWithDataAsOrtValue(memory,data,bytes,shape.data(),shape.size(),type,&out.value));
        return out;
    }
    Tensor floats(std::vector<float>& data,const std::vector<int64_t>& shape) {
        return tensor(data.data(),data.size()*sizeof(float),shape,ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT);
    }
    Output infer(int session,const std::vector<const char*>& names,const std::vector<const OrtValue*>& values,const char* output) {
        if(cancelled.load())throw std::runtime_error("Speech synthesis cancelled");
        Tensor result;
        checked(api()->Run(sessions[session],run,names.data(),values.data(),values.size(),&output,1,&result.value));
        OrtTensorTypeAndShapeInfo* info=nullptr;
        checked(api()->GetTensorTypeAndShape(result.value,&info));
        Output out;
        try {
            ONNXTensorElementDataType type;
            checked(api()->GetTensorElementType(info,&type));
            if(type!=ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT)throw std::runtime_error("Supertonic output must be float32");
            size_t rank,count;
            checked(api()->GetDimensionsCount(info,&rank));
            checked(api()->GetTensorShapeElementCount(info,&count));
            if(rank>4||count>5'000'000)throw std::runtime_error("Supertonic output exceeds tensor bound");
            out.shape.resize(rank);
            checked(api()->GetDimensions(info,out.shape.data(),rank));
            float* data=nullptr;
            checked(api()->GetTensorMutableData(result.value,reinterpret_cast<void**>(&data)));
            out.values.assign(data,data+count);
            if(!std::all_of(out.values.begin(),out.values.end(),[](float v){return std::isfinite(v);}))
                throw std::runtime_error("Supertonic returned non-finite values");
        }catch(...){api()->ReleaseTensorTypeAndShapeInfo(info);throw;}
        api()->ReleaseTensorTypeAndShapeInfo(info);
        return out;
    }
};
Supertonic::Supertonic(const std::string& directory):impl(std::make_unique<Impl>()){impl->initialize(directory);}
Supertonic::~Supertonic()=default;
void Supertonic::cancel() {
    impl->cancelled.store(true);
    if(impl->run){auto* status=api()->RunOptionsSetTerminate(impl->run);if(status)api()->ReleaseStatus(status);}
}
std::vector<float> Supertonic::synthesize(const std::vector<int64_t>& ids,std::vector<float> ttl,std::vector<float> dp,int steps,float speed) {
    std::lock_guard<std::mutex> lock(impl->mutex);
    validateInput(ids,ttl,dp,steps,speed);
    int64_t n=ids.size();
    auto text=impl->tensor(const_cast<int64_t*>(ids.data()),ids.size()*8,{1,n},ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64);
    std::vector<float> mask(ids.size(),1.f);
    auto textMask=impl->floats(mask,{1,1,n});
    auto styleTtl=impl->floats(ttl,{1,50,256});
    auto styleDp=impl->floats(dp,{1,8,16});
    auto duration=impl->infer(0,{"text_ids","style_dp","text_mask"},{text.value,styleDp.value,textMask.value},"duration");
    if(duration.values.size()!=1)throw std::runtime_error("Unexpected Supertonic duration shape");
    const auto outputPlan=plan(duration.values[0],speed);
    auto embedding=impl->infer(1,{"text_ids","style_ttl","text_mask"},{text.value,styleTtl.value,textMask.value},"text_emb");
    auto embedded=impl->floats(embedding.values,embedding.shape);
    constexpr int rate=44100,dimension=24*6;
    int samples=outputPlan.samples;
    int64_t length=outputPlan.latentLength;
    std::vector<int64_t> latentShape={1,dimension,length};
    std::vector<float> latent(dimension*length),latentMask(length,1.f),total={float(steps)},stepValue(1);
    std::mt19937 random(std::random_device{}());std::normal_distribution<float> noise(0.f,1.f);
    for(auto& value:latent)value=noise(random);
    auto lm=impl->floats(latentMask,{1,1,length});auto ts=impl->floats(total,{1});auto cs=impl->floats(stepValue,{1});
    for(int i=0;i<steps;++i) {
        stepValue[0]=float(i);auto input=impl->floats(latent,latentShape);
        auto next=impl->infer(2,{"noisy_latent","text_emb","style_ttl","text_mask","latent_mask","total_step","current_step"},
            {input.value,embedded.value,styleTtl.value,textMask.value,lm.value,ts.value,cs.value},"denoised_latent");
        if(next.shape!=latentShape)throw std::runtime_error("Unexpected Supertonic latent shape");
        latent=std::move(next.values);
    }
    auto input=impl->floats(latent,latentShape);
    auto wav=impl->infer(3,{"latent"},{input.value},"wav_tts");
    if(wav.values.size()<size_t(samples)||wav.values.size()>size_t(31*rate))throw std::runtime_error("Unexpected Supertonic waveform length");
    wav.values.resize(samples);
    return wav.values;
}
}
