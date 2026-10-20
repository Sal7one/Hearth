// Retain the production adapter + owner session, fake only the pinned upstream C API.
#include "../expansion/transcribe_cpp_backend.cpp"
#include "../speech_session.h"
#include <cassert>
#include <iostream>
struct transcribe_model { std::string arch, variant; };
struct transcribe_session { std::string text, language; int samples = 0, lastDecode = 0; bool truncated = false; };
namespace fixture {
int models=0,sessions=0,calls=0,checks=0; bool failLoad=false,failSession=false,failRun=false,truncated=false,badGpu=false,badVariant=false;
std::string nextArch="cohere_asr",nextVariant="cohere-transcribe-arabic-07-2026";
std::string requested;
void check(bool v) { ++checks; if(!v) throw std::runtime_error("Native STT assertion " + std::to_string(checks)); }
template<class F> void fails(F f,const std::string& expected) { try {f();throw std::runtime_error("MISSING ERROR");} catch(const std::exception& e){check(std::string(e.what()).find(expected)!=std::string::npos);} }
}
extern "C" {
void transcribe_log_set(transcribe_log_callback,void*){}
const char* transcribe_status_string(int s){return s==0?"OK":"fixture engine failure";}
void transcribe_backend_init_params_init(transcribe_backend_init_params* p){*p={};p->struct_size=sizeof(*p);}
transcribe_status transcribe_init_backends_ex(const transcribe_backend_init_params*){return TRANSCRIBE_OK;}
void transcribe_model_load_params_init(transcribe_model_load_params* p){*p={};p->struct_size=sizeof(*p);}
transcribe_status transcribe_model_load_file(const char*,const transcribe_model_load_params* p,transcribe_model** out){
 fixture::requested=p->backend==TRANSCRIBE_BACKEND_CPU?"cpu":"vulkan";*out=nullptr;
 if(fixture::failLoad)return TRANSCRIBE_ERR_BACKEND;
 *out=new transcribe_model{fixture::nextArch,fixture::badVariant?"wrong":fixture::nextVariant};++fixture::models;return TRANSCRIBE_OK;}
void transcribe_model_free(transcribe_model* p){if(p){--fixture::models;delete p;}}
const char* transcribe_model_arch_string(const transcribe_model* p){return p->arch.c_str();}
const char* transcribe_model_variant_string(const transcribe_model* p){return p->variant.c_str();}
const char* transcribe_model_backend(const transcribe_model*){return fixture::badGpu?"cpu":fixture::requested.c_str();}
void transcribe_capabilities_init(transcribe_capabilities* p){*p={};p->struct_size=sizeof(*p);}
transcribe_status transcribe_model_get_capabilities(const transcribe_model*,transcribe_capabilities* p){p->native_sample_rate=16000;p->supports_streaming=true;return TRANSCRIBE_OK;}
void transcribe_session_params_init(transcribe_session_params* p){*p={};p->struct_size=sizeof(*p);}
transcribe_status transcribe_session_init(transcribe_model*,const transcribe_session_params* p,transcribe_session** out){
 fixture::check(p->struct_size==sizeof(*p)&&p->n_threads==2);*out=nullptr;if(fixture::failSession)return TRANSCRIBE_ERR_OOM;
 *out=new transcribe_session;++fixture::sessions;return TRANSCRIBE_OK;}
void transcribe_session_free(transcribe_session* p){if(p){--fixture::sessions;delete p;}}
void transcribe_run_params_init(transcribe_run_params* p){*p={};p->struct_size=sizeof(*p);}
transcribe_status transcribe_run(transcribe_session* p,const float*,int,const transcribe_run_params* opts){
 ++fixture::calls;if(fixture::failRun)return TRANSCRIBE_ERR_BACKEND;p->text="مرحبا Hello.";p->language=opts->language?opts->language:"ar";p->truncated=fixture::truncated;return TRANSCRIBE_OK;}
bool transcribe_was_truncated(const transcribe_session* p){return p->truncated;}
const char* transcribe_full_text(const transcribe_session* p){return p->text.c_str();}
const char* transcribe_detected_language(const transcribe_session* p){return p->language.c_str();}
void transcribe_stream_reset(transcribe_session* p){p->text.clear();p->language.clear();p->samples=p->lastDecode=0;p->truncated=false;}
void transcribe_stream_params_init(transcribe_stream_params* p){*p={};p->struct_size=sizeof(*p);}
void transcribe_voxtral_realtime_stream_ext_init(transcribe_voxtral_realtime_stream_ext* p){*p={};p->ext.size=sizeof(*p);p->ext.kind=TRANSCRIBE_EXT_KIND_VOXTRAL_REALTIME_STREAM;}
bool transcribe_model_accepts_ext_kind(const transcribe_model*,transcribe_ext_slot,uint32_t kind){return kind==TRANSCRIBE_EXT_KIND_VOXTRAL_REALTIME_STREAM;}
transcribe_status transcribe_stream_begin(transcribe_session*,const transcribe_run_params* p,const transcribe_stream_params* s){fixture::check(!p->language&&s->family);return TRANSCRIBE_OK;}
void transcribe_stream_update_init(transcribe_stream_update* p){*p={};p->struct_size=sizeof(*p);}
transcribe_status transcribe_stream_feed(transcribe_session* p,const float*,int n,transcribe_stream_update* out){
 if(fixture::failRun)return TRANSCRIBE_ERR_BACKEND;p->samples+=n;
 out->result_changed=p->samples-p->lastDecode>=8000;
 if(out->result_changed){p->lastDecode=p->samples;p->text="مرحبا Hello.";p->language="ar";}return TRANSCRIBE_OK;}
transcribe_status transcribe_stream_finalize(transcribe_session* p,transcribe_stream_update*){
 if(fixture::failRun)return TRANSCRIBE_ERR_BACKEND;p->text="مرحبا Hello.";p->language="ar";p->truncated=fixture::truncated;return TRANSCRIBE_OK;}
}
HearthSpeechConfig config(const char* lang="ar"){return {sizeof(HearthSpeechConfig),"fixture.gguf","","","","",lang,2,3,1000,200,-45,HEARTH_TRANSCRIBE_ACCELERATION,""};}
int main(){using namespace fixture;using namespace stt::speech;std::vector<float> audio(16000,.25f);
 const auto& c=*hearth_cohere_backend_v1();
 {SpeechNativeSession s(c,config());check(models==1&&sessions==1);check(s.executionInfo().find("primary compute: " HEARTH_TRANSCRIBE_ACCELERATION)!=std::string::npos);
 check(s.push(audio.data(),16000,0).find("Hello.")!=std::string::npos);check(s.finish().find("\"events\":[]")!=std::string::npos);
 s.reset();check(s.push(audio.data(),8000,0).find("\"events\":[]")!=std::string::npos);check(s.finish().find("\"final\":true")!=std::string::npos);}
 check(models==0&&sessions==0);fails([&]{SpeechNativeSession s(c,config("auto"));},"requires ar or en");
 badVariant=true;fails([&]{SpeechNativeSession s(c,config());},"architecture/variant mismatch");badVariant=false;check(models==0&&sessions==0);
 failLoad=true;fails([&]{SpeechNativeSession s(c,config());},"fixture engine failure");failLoad=false;
 failSession=true;fails([&]{SpeechNativeSession s(c,config());},"fixture engine failure");failSession=false;check(models==0&&sessions==0);
 {SpeechNativeSession s(c,config());failRun=true;fails([&]{s.push(audio.data(),16000,0);},"fixture engine failure");failRun=false;fails([&]{s.finish();},"fixture engine failure");s.reset();check(s.push(audio.data(),16000,0).find("\"final\":true")!=std::string::npos);}
 {SpeechNativeSession s(c,config());truncated=true;fails([&]{s.push(audio.data(),16000,0);},"truncated");truncated=false;}
 if(std::string(HEARTH_TRANSCRIBE_ACCELERATION)=="vulkan"){badGpu=true;fails([&]{SpeechNativeSession s(c,config());},"resolved to cpu");badGpu=false;}
 nextArch="funasr_nano";nextVariant="fun-asr-mlt-nano-2512";
 {SpeechNativeSession s(*hearth_funasr_backend_v1(),config("auto"));check(s.push(audio.data(),16000,0).find("\"language\":\"ar\"")!=std::string::npos);}
 nextArch="voxtral_realtime";nextVariant="voxtral-mini-4b-realtime-2602";
 {SpeechNativeSession s(*hearth_voxtral_backend_v1(),config("auto"));
 auto part=s.push(audio.data(),8000,0);check(part.find("\"final\":false")!=std::string::npos);auto final=s.push(audio.data(),8000,8000);check(final.find("\"final\":true")!=std::string::npos);check(final.find("\"utteranceId\":1")!=std::string::npos);
 check(s.finish().find("\"events\":[]")!=std::string::npos);s.reset();s.push(audio.data(),8000,0);check(s.finish().find("\"final\":true")!=std::string::npos);}
 {SpeechNativeSession s(*hearth_voxtral_backend_v1(),config("auto"));s.push(audio.data(),8000,0);truncated=true;fails([&]{s.finish();},"truncated");truncated=false;}
 fails([&]{SpeechNativeSession s(*hearth_voxtral_backend_v1(),config("xx"));},"source declaration");
 {SpeechNativeSession s(*hearth_voxtral_backend_v1(),config("ar"));check(s.push(audio.data(),16000,0).find("\"language\":\"ar\"")!=std::string::npos);}
 {SpeechNativeSession s(*hearth_voxtral_backend_v1(),config("auto"));std::vector<float> silence(16000,0);check(s.push(silence.data(),16000,0).find("\"events\":[]")!=std::string::npos);check(s.finish().find("\"events\":[]")!=std::string::npos);}
 check(models==0&&sessions==0);std::cout<<"transcribe_cpp_backend_test (" HEARTH_TRANSCRIBE_ACCELERATION "): "<<checks<<" checks PASS\n";
}
