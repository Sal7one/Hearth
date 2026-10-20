// Offline C ABI fixture only; never staged or packaged as a production runtime.
#include "omnivoice.h"
#include <string>
#include <thread>
#include <limits>
struct ov_context {};
extern "C" {
void ov_init_default_params(ov_init_params* p){*p={};p->abi_version=OV_ABI_VERSION;}
void ov_tts_default_params(ov_tts_params* p){*p={};p->abi_version=OV_ABI_VERSION;}
ov_context* ov_init(const ov_init_params* p){return std::string(p->model_path)=="unicode-error"?nullptr:new ov_context;}
void ov_free(ov_context* p){delete p;}
const char* ov_last_error(){return "خطأ محرك OmniVoice 🗣";}
void ov_audio_free(ov_audio* p){*p={};}
int ov_n_languages(){return 2;}
const char* ov_language_id(int i){return i?"en":"arb";}
ov_status ov_synthesize(ov_context*,const ov_tts_params* p,ov_audio*){
 if(p->cancel && p->cancel(p->cancel_user_data))return OV_STATUS_CANCELLED;
 float values[24001]={.25f,-.25f};
 if(std::string(p->text)=="bad-pcm"){values[0]=std::numeric_limits<float>::quiet_NaN();p->on_chunk(values,1,p->on_chunk_user_data);return OV_STATUS_CANCELLED;}
 if(std::string(p->text)=="threaded-close"){bool keep=false;std::thread worker([&]{keep=p->on_chunk(values,24001,p->on_chunk_user_data);});worker.join();return keep?OV_STATUS_OK:OV_STATUS_CANCELLED;}return p->on_chunk(values,24001,p->on_chunk_user_data)?OV_STATUS_OK:OV_STATUS_CANCELLED;
}
}
