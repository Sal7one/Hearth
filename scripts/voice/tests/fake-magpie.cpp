// Offline JNI contract fixture; never staged or packaged as a production runtime.
#include "tts.h"
#include <string>
#include <thread>
struct nemo_speech_tts_synthesizer {};
extern "C" {
nemo_speech_tts_runtime_config nemo_speech_tts_runtime_config_default(){nemo_speech_tts_runtime_config out{};out.size=sizeof(out);return out;}
nemo_speech_tts_synthesis_options nemo_speech_tts_synthesis_options_default(){nemo_speech_tts_synthesis_options out{};out.size=sizeof(out);return out;}
nemo_speech_tts_status nemo_speech_tts_create(const nemo_speech_tts_synthesizer_config* config,nemo_speech_tts_synthesizer** out){if(std::string(config->model->magpie_model)=="unicode-error"){*out=nullptr;return NEMO_SPEECH_TTS_ERROR_RUNTIME;}*out=new nemo_speech_tts_synthesizer;return NEMO_SPEECH_TTS_OK;}
void nemo_speech_tts_destroy(nemo_speech_tts_synthesizer* voice){delete voice;}
int32_t nemo_speech_tts_speaker_count(const nemo_speech_tts_synthesizer*){return 5;}
int32_t nemo_speech_tts_sample_rate(const nemo_speech_tts_synthesizer*){return 22050;}
const char* nemo_speech_tts_speaker_name(const nemo_speech_tts_synthesizer*,size_t i){static const char* names[]={"Aria","Jason","John","Leo","Sofia"};return names[i];}
const char* nemo_speech_tts_last_error(){return "خطأ محرك Magpie 🗣";}
nemo_speech_tts_status nemo_speech_tts_synthesize_text(nemo_speech_tts_synthesizer*,const nemo_speech_tts_synthesis_options*,const char* text,nemo_speech_tts_pcm_callback callback,void* opaque,nemo_speech_tts_synthesis_stats*){
 const uint8_t pcm[]={0,16,0,240};if(std::string(text)=="bad-pcm"){callback(pcm,1,opaque);return NEMO_SPEECH_TTS_ERROR_CANCELLED;}if(std::string(text)=="threaded-close"){bool keep=false;std::thread worker([&]{keep=callback(pcm,sizeof(pcm),opaque);});worker.join();return keep?NEMO_SPEECH_TTS_OK:NEMO_SPEECH_TTS_ERROR_RUNTIME;}return callback(pcm,sizeof(pcm),opaque)?NEMO_SPEECH_TTS_OK:NEMO_SPEECH_TTS_ERROR_RUNTIME;
}
}
