#include "tts.h"
#include <cstdio>
#include <string>
#include <vector>
static bool output(const uint8_t*,size_t bytes,void* opaque){*static_cast<size_t*>(opaque)+=bytes;return true;}
int main(int argc,char** argv){
 if(argc!=2)return 2;
 std::string root=argv[1],generator=root+"/magpie_tts_multilingual_357m.v2607.f16.gguf",codec=root+"/nemo_nano_codec_22khz_1.89kbps_21.5fps.decoder.f16.gguf",tokenizer=root+"/tokenizer";
 nemo_speech_tts_model_config model{};model.size=sizeof(model);model.magpie_model=generator.c_str();model.codec_model=codec.c_str();model.tokenizer_model_dir=tokenizer.c_str();
 auto runtime=nemo_speech_tts_runtime_config_default();runtime.threads=4;runtime.codec_threads=4;runtime.codec_queue_depth=1;runtime.codec_cpu=true;runtime.lt_backend=NEMO_SPEECH_TTS_BACKEND_CPU;runtime.sampling_backend=NEMO_SPEECH_TTS_BACKEND_CPU;
 nemo_speech_tts_synthesizer_config config{};config.size=sizeof(config);config.model=&model;config.runtime=&runtime;
 nemo_speech_tts_synthesizer* voice=nullptr;
 if(nemo_speech_tts_create(&config,&voice)!=NEMO_SPEECH_TTS_OK){std::fprintf(stderr,"create: %s\n",nemo_speech_tts_last_error());return 1;}
 std::vector<std::string> expected={"Aria","Jason","John","Leo","Sofia"};
 if(nemo_speech_tts_speaker_count(voice)!=5)return 3;
 for(size_t i=0;i<expected.size();++i){std::printf("speaker[%zu]=%s expected=%s\n",i,nemo_speech_tts_speaker_name(voice,i),expected[i].c_str());if(expected[i]!=nemo_speech_tts_speaker_name(voice,i))return 4;}
 for(auto lang:{"en","ar"}){
  auto options=nemo_speech_tts_synthesis_options_default();options.language_code=lang==std::string("ar")?"ar-MSA":lang;options.voice_name="John";options.speaker=-1;
  auto stats=nemo_speech_tts_synthesis_stats_default();size_t bytes=0;
  auto status=nemo_speech_tts_synthesize_text(voice,&options,lang==std::string("ar")?"مرحباً، هذا اختبار للصوت العربي.":"Hello, this is a short speech test.",output,&bytes,&stats);
  if(status!=NEMO_SPEECH_TTS_OK || bytes==0){std::fprintf(stderr,"%s: %s\n",lang,nemo_speech_tts_last_error());nemo_speech_tts_destroy(voice);return 5;}
  std::printf("PASS language=%s bytes=%zu rate=%d chunks=%d elapsed_s=%.3f audio_s=%.3f ttfa_ms=%.3f\n",lang,bytes,stats.sample_rate,stats.chunks,stats.elapsed_s,stats.audio_s,stats.ttfa_ms);std::fflush(stdout);
 }
 auto cancel_options=nemo_speech_tts_synthesis_options_default();cancel_options.language_code="ar-MSA";
 auto cancelled=nemo_speech_tts_synthesize_text(voice,&cancel_options,"هذا اختبار إلغاء القراءة.",[](const uint8_t*,size_t,void*){return false;},nullptr,nullptr);
 if(cancelled==NEMO_SPEECH_TTS_OK){std::fprintf(stderr,"Callback abort was ignored\n");return 6;}
 std::printf("PASS callback abort stops synthesis with upstream status=%d error=%s\n",int(cancelled),nemo_speech_tts_last_error());
 auto invalid=nemo_speech_tts_synthesis_options_default();invalid.language_code="unsupported";
 size_t invalid_bytes=0;auto failed=nemo_speech_tts_synthesize_text(voice,&invalid,"Hello",output,&invalid_bytes,nullptr);
 if(failed==NEMO_SPEECH_TTS_OK || !*nemo_speech_tts_last_error())return 7;
 std::printf("PASS invalid language surfaces error: %s\n",nemo_speech_tts_last_error());
 nemo_speech_tts_destroy(voice);return 0;
}
