// Native V3 ONNX pipeline. Graph contracts: Masterx/19b94afe41de738624b2f3afc75b4cd56221fbeb.
// Sampler/Perth/audio implementation: birdup000/chatterboxcpp 1a9a4fc00a6caa8c8bb9bee533225f40661e4220 (MIT).
#include "vendor/chatterbox.h"
#include "vendor/json.hpp"
#include "ort_values.h"
#include "frontend.h"
#include "chatterbox/audio.hpp"
#include "chatterbox/watermark.hpp"
#include <algorithm>
#include <atomic>
#include <cmath>
#include <fstream>
#include <memory>
#include <set>
#ifdef HEARTH_CHATTERBOX_TRACE
#include <iostream>
#include <cstdlib>
#endif
using json=nlohmann::json;
namespace {thread_local const std::atomic<bool>* watermarkCancellation=nullptr;struct WatermarkCancellation{const std::atomic<bool>* previous;explicit WatermarkCancellation(const std::atomic<bool>* value):previous(watermarkCancellation){watermarkCancellation=value;}~WatermarkCancellation(){watermarkCancellation=previous;}};}
extern "C" void hearth_chatterbox_check_watermark(){if(watermarkCancellation&&watermarkCancellation->load())throw std::runtime_error("Chatterbox watermark stopped");}

struct hearth_chatterbox {
 OrtEnv* env=nullptr;OrtSessionOptions* options=nullptr;OrtMemoryInfo* memory=nullptr;OrtRunOptions* run=nullptr;
 Session embed,speech,lm,decoder;std::map<std::string,Value> reference;
 std::unique_ptr<hearth::chatterbox::Tokenizer> tokenizer;std::unique_ptr<chatterbox::PerthWatermarker> perth;
 std::atomic<bool> cancelled{false};
 ~hearth_chatterbox(){for(auto* session:{&decoder,&lm,&speech,&embed})if(session->p){ort().ReleaseSession(session->p);session->p=nullptr;}reference.clear();if(run)ort().ReleaseRunOptions(run);if(memory)ort().ReleaseMemoryInfo(memory);if(options)ort().ReleaseSessionOptions(options);if(env)ort().ReleaseEnv(env);}
 void check(){if(cancelled.load())throw std::runtime_error("Chatterbox speech stopped");}
 template<class T> Value tensor(std::vector<T>& values,std::vector<int64_t> shape,ONNXTensorElementDataType type){OrtValue* out=nullptr;T dummy{};checked(ort().CreateTensorWithDataAsOrtValue(memory,values.empty()?&dummy:values.data(),values.size()*sizeof(T),shape.data(),shape.size(),type,&out));return Value(out);}
 Value zeros(std::vector<int64_t> shape){OrtAllocator* allocator=nullptr;checked(ort().GetAllocatorWithDefaultOptions(&allocator));OrtValue* out=nullptr;checked(ort().CreateTensorAsOrtValue(allocator,shape.data(),shape.size(),ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,&out));return Value(out);}
 void init(const std::string& root,int threads){
  if(threads<1||threads>8)throw std::runtime_error("Chatterbox requires 1-8 CPU threads");
  tokenizer=std::make_unique<hearth::chatterbox::Tokenizer>(root+"/tokenizer.json");
  // No download or raw vocoder fallback: this exact local encoder is mandatory.
  perth=std::make_unique<chatterbox::PerthWatermarker>(chatterbox::PerthWatermarker::from_file(root+"/perth-encoder.bin"));
  checked(ort().CreateEnv(ORT_LOGGING_LEVEL_ERROR,"hearth-chatterbox",&env));checked(ort().CreateSessionOptions(&options));checked(ort().SetIntraOpNumThreads(options,threads));checked(ort().SetInterOpNumThreads(options,1));checked(ort().SetSessionGraphOptimizationLevel(options,ORT_ENABLE_ALL));checked(ort().AddSessionConfigEntry(options,"session.intra_op.allow_spinning","0"));checked(ort().CreateCpuMemoryInfo(OrtArenaAllocator,OrtMemTypeDefault,&memory));checked(ort().CreateRunOptions(&run));
  embed.load(env,options,root+"/onnx/embed_tokens.onnx");speech.load(env,options,root+"/onnx/speech_encoder.onnx");lm.load(env,options,root+"/onnx/language_model_q4.ort20.onnx");decoder.load(env,options,root+"/onnx/conditional_decoder.onnx");
  auto audio=chatterbox::read_wav_mono(root+"/default_voice.wav");if(audio.sample_rate!=24000)throw std::runtime_error("Chatterbox reference must be 24 kHz");audio.samples.resize(std::min<size_t>(audio.samples.size(),6*24000));if(audio.samples.size()<24000)throw std::runtime_error("Chatterbox reference must contain at least 1 second");for(float sample:audio.samples)if(!std::isfinite(sample))throw std::runtime_error("Invalid Chatterbox reference PCM");
  auto input=tensor(audio.samples,{1,int64_t(audio.samples.size())},ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT);reference=speech.run(run,{{"audio_values",input.p}});
 }
 Value embedding(std::vector<int64_t>& ids,std::vector<int64_t>& positions){check();std::vector<float> exaggeration={0.5f};auto input=tensor(ids,{1,int64_t(ids.size())},ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64),position=tensor(positions,{1,int64_t(ids.size())},ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64),exag=tensor(exaggeration,{1},ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT);auto result=embed.run(run,{{"input_ids",input.p},{"position_ids",position.p},{"exaggeration",exag.p}});return std::move(result.at("inputs_embeds"));}

 Value languageModel(const Value& embedding,int64_t length,std::map<std::string,Value>& cache){check();const auto shape=embedding.shape();if(shape.size()!=3||shape[0]!=1||shape[2]!=1024)throw std::runtime_error("Chatterbox embedding shape mismatch");std::vector<int64_t> mask(size_t(length),1);auto attention=tensor(mask,{1,length},ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64);std::vector<std::pair<std::string,const OrtValue*>> feeds={{"attention_mask",attention.p},{"inputs_embeds",embedding.p}};if(cache.empty())for(int i=0;i<30;++i)for(const auto* kind:{"key","value"})cache.emplace("past_key_values."+std::to_string(i)+"."+kind,zeros({1,16,0,64}));for(const auto& [name,value]:cache)feeds.emplace_back(name,value.p);auto output=lm.run(run,feeds);cache.clear();for(auto it=output.begin();it!=output.end();){if(it->first.rfind("present.",0)==0){cache.emplace("past_key_values."+it->first.substr(8),std::move(it->second));it=output.erase(it);}else ++it;}if(cache.size()!=60)throw std::runtime_error("Chatterbox KV cache inventory mismatch");return std::move(output.at("logits"));}
 int synthesize(const std::string& text,const std::string& language,const std::string& voice,hearth_chatterbox_pcm callback,void* opaque){
  check();if(voice!="default")throw std::runtime_error("Unknown Chatterbox reference voice");auto ids=tokenizer->encode(text,language);auto positions=hearth::chatterbox::positions(ids);auto textEmbedding=embedding(ids,positions);const auto& audioFeatures=reference.at("audio_features");const auto aShape=audioFeatures.shape(),tShape=textEmbedding.shape();if(aShape.size()!=3||aShape[0]!=1||aShape[2]!=1024||tShape.size()!=3||tShape[2]!=1024)throw std::runtime_error("Chatterbox conditioning shape mismatch");auto* a=audioFeatures.data<float>(ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT);auto* t=textEmbedding.data<float>(ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT);std::vector<float> joined(a,a+audioFeatures.count());joined.insert(joined.end(),t,t+textEmbedding.count());auto prompt=tensor(joined,{1,aShape[1]+tShape[1],1024},ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT);std::map<std::string,Value> cache;int64_t cacheLength=aShape[1]+tShape[1];auto logits=languageModel(prompt,cacheLength,cache);std::vector<int64_t> generated;bool eos=false;
  // Greedy + repetition penalty 1.2 follows this exact export's published validation rung.
  for(int step=0;step<500;++step){check();const auto shape=logits.shape();if(shape.size()!=3||shape[0]!=1||shape[2]!=8194||shape[1]<1)throw std::runtime_error("Chatterbox logits shape mismatch");const float* values=logits.data<float>(ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT)+(shape[1]-1)*8194;std::set<int64_t> seen(generated.begin(),generated.end());seen.insert(6561);int64_t best=-1;float score=-INFINITY;for(int64_t id=0;id<8194;++id){float value=values[id];if(!std::isfinite(value))throw std::runtime_error("Chatterbox non-finite logits");if(seen.count(id))value=value<0?value*1.2f:value/1.2f;if(value>score){score=value;best=id;}}if(best==6562){eos=true;break;}if(best<0||best>=6561)throw std::runtime_error("Chatterbox generated an invalid speech token");generated.push_back(best);std::vector<int64_t> next={best},nextPosition={int64_t(generated.size())};auto nextEmbedding=embedding(next,nextPosition);logits=languageModel(nextEmbedding,++cacheLength,cache);}
  if(!eos)throw std::runtime_error("Chatterbox reached 20-second generation bound before completion; use shorter text");if(generated.size()<2)throw std::runtime_error("Chatterbox generated too little speech");cache.clear();
#ifdef HEARTH_CHATTERBOX_TRACE
  std::cout<<"CHATTERBOX TOKENS language="<<language<<" ids="<<json(generated).dump()<<std::endl;
#endif
  const auto& audioTokens=reference.at("audio_tokens");auto* ref=audioTokens.data<int64_t>(ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64);std::vector<int64_t> tokens(ref,ref+audioTokens.count());tokens.insert(tokens.end(),generated.begin(),generated.end());auto speechTokens=tensor(tokens,{1,int64_t(tokens.size())},ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64);check();auto decoded=decoder.run(run,{{"speech_tokens",speechTokens.p},{"speaker_embeddings",reference.at("speaker_embeddings").p},{"speaker_features",reference.at("speaker_features").p}});auto& waveform=decoded.at("waveform");const auto shape=waveform.shape();if(shape.size()!=2||shape[0]!=1||shape[1]<1||shape[1]>30*24000)throw std::runtime_error("Chatterbox waveform exceeds bound");auto* pcm=waveform.data<float>(ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT);const size_t count=std::min(waveform.count(),(generated.size()-1)*960);if(count<=1024)throw std::runtime_error("Chatterbox generated empty trimmed speech");chatterbox::Audio audio{24000,std::vector<float>(pcm,pcm+count)};for(float sample:audio.samples)if(!std::isfinite(sample))throw std::runtime_error("Chatterbox returned non-finite PCM");check();{WatermarkCancellation stopping(&cancelled);audio=perth->apply(audio);}check();if(audio.sample_rate!=24000||audio.samples.empty()||audio.samples.size()>30*24000)throw std::runtime_error("Invalid watermarked Chatterbox PCM");
#ifdef HEARTH_CHATTERBOX_TRACE
  if(const char* directory=std::getenv("HEARTH_CHATTERBOX_TRACE_DIRECTORY")){static thread_local int index=0;std::ofstream output(std::string(directory)+"/"+language+"-"+std::to_string(index++)+".f32",std::ios::binary);output.write(reinterpret_cast<const char*>(audio.samples.data()),audio.samples.size()*sizeof(float));}
#endif
  for(size_t offset=0;offset<audio.samples.size();){check();const auto n=std::min<size_t>(24000,audio.samples.size()-offset);if(!callback(audio.samples.data()+offset,n,24000,opaque)){cancelled.store(true);throw std::runtime_error("Chatterbox PCM callback stopped");}offset+=n;}return 0;
 }
};
extern "C" {
hearth_chatterbox* hearth_chatterbox_create(const char* directory,int threads){try{if(!directory||!*directory)throw std::runtime_error("Missing Chatterbox directory");auto out=std::make_unique<hearth_chatterbox>();out->init(directory,threads);return out.release();}catch(const std::exception& e){last_error=e.what();return nullptr;}}
void hearth_chatterbox_destroy(hearth_chatterbox* value){delete value;}
void hearth_chatterbox_cancel(hearth_chatterbox* value){if(value){value->cancelled.store(true);if(value->run){auto* status=ort().RunOptionsSetTerminate(value->run);if(status)ort().ReleaseStatus(status);}}}
int hearth_chatterbox_voice_count(const hearth_chatterbox* value){return value?1:0;}
const char* hearth_chatterbox_voice_name(const hearth_chatterbox* value,int index){return value&&index==0?"default":nullptr;}
const char* hearth_chatterbox_last_error(){return last_error.c_str();}
int hearth_chatterbox_synthesize(hearth_chatterbox* value,const char* text,const char* language,const char* voice,hearth_chatterbox_pcm callback,void* opaque){try{if(!value||!text||!*text||!language||!voice||!callback)throw std::runtime_error("Invalid Chatterbox synthesis request");return value->synthesize(text,language,voice,callback,opaque);}catch(const std::exception& e){last_error=e.what();return value&&value->cancelled.load()?1:2;}}
}
