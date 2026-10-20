// MOSS ONNX pipeline based on OpenMOSS/MOSS-TTS-Nano 8b7bcc9341b3b4ef3a3a58ba1338a7d85ff133eb (Apache-2.0).
// Real SentencePiece text inputs, autoregressive KV cache, incremental stereo codec, explicit mono average.
#include "vendor/moss.h"
#include "pcm_bounds.h"
#include "vendor/json.hpp"
#include "onnxruntime/onnxruntime_c_api.h"
#include "sentencepiece_processor.h"
#include <algorithm>
#include <atomic>
#include <cmath>
#include <fstream>
#include <map>
#include <memory>
#include <random>
#include <stdexcept>
#include <string>
#include <vector>
using json=nlohmann::json;
namespace {
thread_local std::string last_error;
const OrtApi& ort(){auto* p=OrtGetApiBase()->GetApi(ORT_API_VERSION);if(!p)throw std::runtime_error("MOSS: ONNX API unavailable");return *p;}
void checked(OrtStatus* error){if(error){std::string message=ort().GetErrorMessage(error);ort().ReleaseStatus(error);throw std::runtime_error(message);}}
json read(const std::string& path){std::ifstream input(path,std::ios::binary|std::ios::ate);if(!input||input.tellg()<1||input.tellg()>1024*1024)throw std::runtime_error("Invalid MOSS metadata: "+path);input.seekg(0);return json::parse(input);}
struct Value {
 OrtValue* p=nullptr;
 Value()=default;explicit Value(OrtValue* p):p(p){}
 Value(const Value&)=delete;Value& operator=(const Value&)=delete;
 Value(Value&& other)noexcept:p(other.p){other.p=nullptr;}
 Value& operator=(Value&& other)noexcept{if(p)ort().ReleaseValue(p);p=other.p;other.p=nullptr;return *this;}
 ~Value(){if(p)ort().ReleaseValue(p);}
 std::vector<int64_t> shape()const {OrtTensorTypeAndShapeInfo* info=nullptr;checked(ort().GetTensorTypeAndShape(p,&info));try{size_t rank=0,count=0;checked(ort().GetDimensionsCount(info,&rank));checked(ort().GetTensorShapeElementCount(info,&count));if(rank>6||count>100000000)throw std::runtime_error("MOSS tensor exceeds bound");std::vector<int64_t> out(rank);checked(ort().GetDimensions(info,out.data(),rank));ort().ReleaseTensorTypeAndShapeInfo(info);return out;}catch(...){ort().ReleaseTensorTypeAndShapeInfo(info);throw;}}
 size_t count()const{size_t n=1;for(auto d:shape()){if(d<0||d>100000000)throw std::runtime_error("MOSS invalid output dimensions");n*=size_t(d);if(n>100000000)throw std::runtime_error("MOSS tensor bound");}return n;}
 template<class T> T* data(ONNXTensorElementDataType expected)const{OrtTensorTypeAndShapeInfo* info=nullptr;checked(ort().GetTensorTypeAndShape(p,&info));ONNXTensorElementDataType actual;auto* status=ort().GetTensorElementType(info,&actual);ort().ReleaseTensorTypeAndShapeInfo(info);checked(status);if(actual!=expected)throw std::runtime_error("Unexpected MOSS tensor dtype");void* out=nullptr;checked(ort().GetTensorMutableData(p,&out));return static_cast<T*>(out);}
 int scalar()const{if(count()!=1)throw std::runtime_error("MOSS scalar shape mismatch");return *data<int32_t>(ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32);}
};
struct Session {
 OrtSession* p=nullptr;std::vector<std::string> outputs;
 ~Session(){if(p)ort().ReleaseSession(p);}
 void load(OrtEnv* env,OrtSessionOptions* options,const std::string& path){checked(ort().CreateSession(env,path.c_str(),options,&p));size_t n=0;checked(ort().SessionGetOutputCount(p,&n));if(n>100)throw std::runtime_error("MOSS output count exceeds bound");OrtAllocator* allocator=nullptr;checked(ort().GetAllocatorWithDefaultOptions(&allocator));for(size_t i=0;i<n;++i){char* name=nullptr;checked(ort().SessionGetOutputName(p,i,allocator,&name));outputs.emplace_back(name);allocator->Free(allocator,name);}}
 std::map<std::string,Value> run(OrtRunOptions* options,const std::vector<std::pair<std::string,const OrtValue*>>& feeds){std::vector<const char*> names,outNames;std::vector<const OrtValue*> values;for(const auto& [name,value]:feeds){names.push_back(name.c_str());values.push_back(value);}for(const auto& name:outputs)outNames.push_back(name.c_str());std::vector<OrtValue*> out(outputs.size(),nullptr);auto* error=ort().Run(p,options,names.data(),values.data(),values.size(),outNames.data(),outNames.size(),out.data());if(error){for(auto* value:out)if(value)ort().ReleaseValue(value);checked(error);}std::map<std::string,Value> result;for(size_t i=0;i<out.size();++i)result.emplace(outputs[i],Value(out[i]));return result;}
};
}
struct hearth_moss {
 OrtEnv* env=nullptr;OrtSessionOptions* options=nullptr;OrtMemoryInfo* memory=nullptr;OrtRunOptions* run=nullptr;
 Session prefill,decode,local,codec;sentencepiece::SentencePieceProcessor tokenizer;json manifest,tts,codecMeta;
 std::atomic<bool> cancelled{false};std::vector<std::string> voiceNames;
 ~hearth_moss(){for(auto* session:{&codec,&local,&decode,&prefill})if(session->p){ort().ReleaseSession(session->p);session->p=nullptr;}if(run)ort().ReleaseRunOptions(run);if(memory)ort().ReleaseMemoryInfo(memory);if(options)ort().ReleaseSessionOptions(options);if(env)ort().ReleaseEnv(env);}
 void check(){if(cancelled.load())throw std::runtime_error("MOSS speech stopped");}
 void init(const std::string& root,int threads){
  if(threads<1||threads>8)throw std::runtime_error("MOSS requires 1-8 threads");
  manifest=read(root+"/browser_poc_manifest.json");tts=read(root+"/tts_browser_onnx_meta.json");codecMeta=read(root+"/codec_browser_onnx_meta.json");
  if(manifest["tts_config"]["n_vq"]!=16||codecMeta["codec_config"]["channels"]!=2||codecMeta["codec_config"]["sample_rate"]!=48000)throw std::runtime_error("Unsupported MOSS metadata contract");
  for(const auto& voice:manifest["builtin_voices"]){if(!voice["prompt_audio_codes"].is_array()||voice["prompt_audio_codes"].empty())throw std::runtime_error("MOSS voice has no reference codes");voiceNames.push_back(voice["voice"]);}
  if(voiceNames.empty()||voiceNames.size()>100)throw std::runtime_error("Invalid MOSS voice inventory");
  auto status=tokenizer.Load(root+"/tokenizer.model");if(!status.ok())throw std::runtime_error(status.ToString());
  checked(ort().CreateEnv(ORT_LOGGING_LEVEL_ERROR,"hearth-moss",&env));checked(ort().CreateSessionOptions(&options));checked(ort().SetIntraOpNumThreads(options,threads));checked(ort().SetInterOpNumThreads(options,1));checked(ort().SetSessionGraphOptimizationLevel(options,ORT_ENABLE_ALL));checked(ort().AddSessionConfigEntry(options,"session.intra_op.allow_spinning","0"));checked(ort().CreateCpuMemoryInfo(OrtArenaAllocator,OrtMemTypeDefault,&memory));checked(ort().CreateRunOptions(&run));
  prefill.load(env,options,root+"/moss_tts_prefill.onnx");decode.load(env,options,root+"/moss_tts_decode_step.onnx");local.load(env,options,root+"/moss_tts_local_fixed_sampled_frame.onnx");codec.load(env,options,root+"/moss_audio_tokenizer_decode_step.onnx");
 }
 template<class T>Value tensor(std::vector<T>& values,std::vector<int64_t> shape,ONNXTensorElementDataType type){OrtValue* out=nullptr;checked(ort().CreateTensorWithDataAsOrtValue(memory,values.data(),values.size()*sizeof(T),shape.data(),shape.size(),type,&out));return Value(out);}
 Value ownedZero(std::vector<int64_t> shape,ONNXTensorElementDataType type,int fill=0){OrtAllocator* allocator=nullptr;checked(ort().GetAllocatorWithDefaultOptions(&allocator));OrtValue* out=nullptr;checked(ort().CreateTensorAsOrtValue(allocator,shape.data(),shape.size(),type,&out));Value result(out);auto n=result.count();if(type==ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT)std::fill_n(result.data<float>(type),n,float(fill));else std::fill_n(result.data<int32_t>(type),n,int32_t(fill));return result;}
 std::vector<float> lastHidden(const Value& value){auto shape=value.shape();if((shape.size()!=2&&shape.size()!=3)||shape[0]!=1||shape.back()!=768)throw std::runtime_error("MOSS global_hidden shape mismatch");auto* data=value.data<float>(ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT);auto n=value.count();if(n<768)throw std::runtime_error("Empty MOSS global_hidden");return std::vector<float>(data+n-768,data+n);}
 int synthesize(const std::string& text,const std::string& voice,hearth_moss_pcm callback,void* opaque){
  check();std::vector<int> tokens;auto status=tokenizer.Encode(text,&tokens);if(!status.ok())throw std::runtime_error(status.ToString());if(tokens.empty()||tokens.size()>512)throw std::runtime_error("MOSS text tokens exceed bound");
  const auto selected=std::find(voiceNames.begin(),voiceNames.end(),voice);if(selected==voiceNames.end())throw std::runtime_error("Unknown MOSS voice");
  std::vector<int32_t> rows;auto addText=[&](int32_t token){rows.push_back(token);rows.insert(rows.end(),16,1024);};
  for(int token:manifest["prompt_templates"]["user_prompt_prefix_token_ids"])addText(token);addText(6);
  for(const auto& frame:manifest["builtin_voices"][size_t(selected-voiceNames.begin())]["prompt_audio_codes"]){if(frame.size()!=16)throw std::runtime_error("MOSS reference frame shape mismatch");addText(8);for(size_t q=0;q<16;++q){int token=frame[q];if(token<0||token>=1024)throw std::runtime_error("MOSS reference token outside vocabulary");rows[rows.size()-16+q]=token;}}
  addText(7);for(int token:manifest["prompt_templates"]["user_prompt_after_reference_token_ids"])addText(token);for(int token:tokens)addText(token);for(int token:manifest["prompt_templates"]["assistant_prompt_prefix_token_ids"])addText(token);addText(6);
  int32_t valid=int32_t(rows.size()/17);if(valid>1024)throw std::runtime_error("MOSS prompt context exceeds bound");std::vector<int32_t> mask(valid,1);
  auto input=tensor(rows,{1,valid,17},ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32),attention=tensor(mask,{1,valid},ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32);
  auto past=prefill.run(run,{{"input_ids",input.p},{"attention_mask",attention.p}});auto hidden=lastHidden(past.at("global_hidden"));
  std::map<std::string,Value> state;std::map<std::string,std::string> stateOutputs;
  auto addState=[&](const std::string& name,const std::string& output,const std::vector<int64_t>& shape,bool floating,int fill=0){state.emplace(name,ownedZero(shape,floating?ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT:ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32,fill));stateOutputs[name]=output;};
  for(const auto& spec:codecMeta["streaming_decode"]["transformer_offsets"])addState(spec["input_name"],spec["output_name"],spec["shape"],false);
  for(const auto& spec:codecMeta["streaming_decode"]["attention_caches"]){addState(spec["offset_input_name"],spec["offset_output_name"],spec["offset_shape"],false);addState(spec["cached_keys_input_name"],spec["cached_keys_output_name"],spec["cache_shape"],true);addState(spec["cached_values_input_name"],spec["cached_values_output_name"],spec["cache_shape"],true);addState(spec["cached_positions_input_name"],spec["cached_positions_output_name"],spec["positions_shape"],false,-1);}
  std::vector<int32_t> frames;size_t emitted=0;
  auto flush=[&](){if(frames.empty())return;check();std::vector<int32_t> length={int32_t(frames.size()/16)};auto codes=tensor(frames,{1,length[0],16},ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32),lengthValue=tensor(length,{1},ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32);std::vector<std::pair<std::string,const OrtValue*>> feeds={{"audio_codes",codes.p},{"audio_code_lengths",lengthValue.p}};for(auto& [name,value]:state)feeds.emplace_back(name,value.p);auto result=codec.run(run,feeds);const auto& audio=result.at("audio");const auto shape=audio.shape();const int n=result.at("audio_lengths").scalar();if(shape.size()!=3||shape[0]!=1||shape[1]!=2||n<1||n>48000||n>shape[2])throw std::runtime_error("MOSS stereo PCM shape exceeds chunk bound");auto* samples=audio.data<float>(ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT);auto mono=hearth::moss::monoAverage(samples,samples+shape[2],size_t(n));if(!callback(mono.data(),mono.size(),48000,opaque)){cancelled.store(true);throw std::runtime_error("MOSS PCM callback stopped");}emitted+=n;for(auto& [name,value]:state)value=std::move(result.at(stateOutputs.at(name)));frames.clear();};
  std::mt19937 random(1234);std::uniform_real_distribution<float> uniform(1e-6f,1.f-1e-6f);std::vector<int32_t> seen(16*1024,0);bool eos=false;
  for(int step=0;step<375;++step){check();std::vector<float> assistantRandom={uniform(random)},audioRandom(16);for(auto& value:audioRandom)value=uniform(random);auto global=tensor(hidden,{1,768},ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT),seenValue=tensor(seen,{1,16,1024},ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32),assistantValue=tensor(assistantRandom,{1},ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT),audioValue=tensor(audioRandom,{1,16},ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT);auto localResult=local.run(run,{{"global_hidden",global.p},{"repetition_seen_mask",seenValue.p},{"assistant_random_u",assistantValue.p},{"audio_random_u",audioValue.p}});if(localResult.at("should_continue").scalar()==0){eos=true;break;}const auto& frame=localResult.at("frame_token_ids");if(frame.count()!=16)throw std::runtime_error("MOSS sampled frame shape mismatch");auto* frameData=frame.data<int32_t>(ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32);std::vector<int32_t> next(17,1024);next[0]=9;for(int q=0;q<16;++q){const int token=frameData[q];if(token<0||token>=1024)throw std::runtime_error("MOSS sampled token outside vocabulary");next[q+1]=token;seen[q*1024+token]=1;frames.push_back(token);}if(frames.size()/16==6)flush();
   std::vector<int32_t> validValue={valid};auto nextValue=tensor(next,{1,1,17},ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32),pastLength=tensor(validValue,{1},ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32);std::vector<std::pair<std::string,const OrtValue*>> feeds={{"input_ids",nextValue.p},{"past_valid_lengths",pastLength.p}};const auto inNames=tts["onnx"]["decode_input_names"].get<std::vector<std::string>>(),outNames=tts["onnx"]["decode_output_names"].get<std::vector<std::string>>();if(inNames.size()!=26||outNames.size()!=25)throw std::runtime_error("MOSS cache contract mismatch");for(size_t i=2;i<inNames.size();++i)feeds.emplace_back(inNames[i],past.at(outNames[i-1]).p);auto result=decode.run(run,feeds);hidden=lastHidden(result.at("global_hidden"));past=std::move(result);++valid;
  }
  if(!eos)throw std::runtime_error("MOSS reached the 30-second generation bound before speech completion; use shorter text");flush();check();if(!emitted)throw std::runtime_error("MOSS produced no speech");return 0;
 }
};
extern "C" {
hearth_moss* hearth_moss_create(const char* directory,int threads){try{if(!directory||!*directory)throw std::runtime_error("Missing MOSS model directory");auto value=std::make_unique<hearth_moss>();value->init(directory,threads);return value.release();}catch(const std::exception& e){last_error=e.what();return nullptr;}}
void hearth_moss_destroy(hearth_moss* value){delete value;}
void hearth_moss_cancel(hearth_moss* value){if(value){value->cancelled.store(true);if(value->run){auto* status=ort().RunOptionsSetTerminate(value->run);if(status)ort().ReleaseStatus(status);}}}
int hearth_moss_voice_count(const hearth_moss* value){return value?int(value->voiceNames.size()):0;}
const char* hearth_moss_voice_name(const hearth_moss* value,int index){return value&&index>=0&&size_t(index)<value->voiceNames.size()?value->voiceNames[index].c_str():nullptr;}
const char* hearth_moss_last_error(){return last_error.c_str();}
int hearth_moss_synthesize(hearth_moss* value,const char* text,const char* voice,hearth_moss_pcm callback,void* opaque){try{if(!value||!text||!*text||!voice||!callback)throw std::runtime_error("Invalid MOSS synthesis request");return value->synthesize(text,voice,callback,opaque);}catch(const std::exception& e){last_error=e.what();return value&&value->cancelled.load()?1:2;}}
}
