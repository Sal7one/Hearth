#include "hearth_oute.h"
#include "chat.h"
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <fstream>
#include <memory>
#include <mutex>
#include <stdexcept>

namespace {
std::mutex model_mutex; // Upstream speaker profile is process-global inside this private DSO.
std::once_flag backend_once;
thread_local std::string last_error;
constexpr int sample_rate=24000, maximum_frames=sample_rate*45;
}
struct hearth_oute {
    std::unique_ptr<chatllm::Pipeline> model;
    std::string profile;
    std::atomic_bool cancelled{false};
};
extern "C" {
int hearth_oute_abi(void) { return HEARTH_OUTE_ABI; }
const char* hearth_oute_last_error(void) { return last_error.c_str(); }
hearth_oute* hearth_oute_create(const char* model,const char* profile,int threads) {
    try {
        if(!model||!profile||threads<1||threads>8)throw std::invalid_argument("Invalid Oute model/profile/thread arguments");
        std::ifstream speaker(profile,std::ios::binary|std::ios::ate);
        if(!speaker||speaker.tellg()<=0||speaker.tellg()>65536)throw std::runtime_error("Oute reference profile is missing or exceeds 64 KiB");
        std::lock_guard<std::mutex> lock(model_mutex);
        std::call_once(backend_once,[]{chatllm::ComputeManager::init();});
        auto result=std::make_unique<hearth_oute>();
        chatllm::ModelObject::extra_args options(8192,"",false,threads,128,"f16");
        result->model=std::make_unique<chatllm::Pipeline>(model,options);
        if(!result->model->is_loaded())throw std::runtime_error("Oute combined generator/DAC model did not load");
        result->profile=profile;
        return result.release();
    } catch(const std::exception& e) { last_error=e.what();return nullptr; }
}
int hearth_oute_synthesize(hearth_oute* context,const char* text,hearth_oute_pcm callback,void* user) {
    try {
        if(!context||!text||!callback||std::strlen(text)==0||std::strlen(text)>2048)throw std::invalid_argument("Invalid or excessive Oute speech request");
        std::unique_lock<std::mutex> lock(model_mutex);
        if(context->cancelled.load())return 1;
        context->model->set_additional_args({{"speaker",context->profile}});
        chatllm::GenerationConfig options{};
        options.max_length=8192; options.max_context_length=8192; options.max_new_tokens=4096;
        options.do_sample=true; options.top_k=40; options.top_p=.9f; options.temperature=.4f;
        options.penalty_window=64; options.repeat_penalty=1.1f; options.presence_penalty=0;options.frequency_penalty=0;
        options.tfs_z=1;options.sampling="hearth_oute";options.seed(42);
        std::vector<int16_t> pcm;int rate=0,channels=0;
        if(!context->model->speech_synthesis(text,options,pcm,rate,channels))throw std::runtime_error("Oute speech synthesis failed");
        if(context->cancelled.load())return 1;
        if(rate!=sample_rate||channels!=1||pcm.empty()||pcm.size()>maximum_frames)throw std::runtime_error("Oute produced empty, excessive or invalid audio");
        lock.unlock(); // Delivery owns PCM; callbacks may open another independent voice engine.
        std::vector<float> chunk(4096);
        for(size_t position=0;position<pcm.size();position+=chunk.size()) {
            if(context->cancelled.load())return 1;
            const auto count=std::min(chunk.size(),pcm.size()-position);
            for(size_t i=0;i<count;++i)chunk[i]=float(pcm[position+i])/32768.f;
            if(!callback(user,chunk.data(),static_cast<int>(count),rate)){hearth_oute_cancel(context);return 1;}
        }
        return 0;
    } catch(const std::exception& e) { last_error=e.what();return context&&context->cancelled.load()?1:-1; }
}
void hearth_oute_cancel(hearth_oute* context) {
    if(context){context->cancelled.store(true);context->model->abort_generation();}
}
void hearth_oute_destroy(hearth_oute* context) { delete context; }
}

// Upstream normally routes this through the CLI streamer; the app has no CLI.
void log_internal(int level, const char* text) {
    if(text && level >= 0) std::fprintf(stderr,"Oute: %s",text);
}
