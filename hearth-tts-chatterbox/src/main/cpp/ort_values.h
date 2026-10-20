#pragma once
#include "onnxruntime/onnxruntime_c_api.h"
#include <string>
#include <vector>
#include <map>
#include <stdexcept>
namespace {
thread_local std::string last_error;
const OrtApi& ort(){auto* p=OrtGetApiBase()->GetApi(ORT_API_VERSION);if(!p)throw std::runtime_error("Chatterbox: ONNX API unavailable");return *p;}
void checked(OrtStatus* error){if(error){std::string message=ort().GetErrorMessage(error);ort().ReleaseStatus(error);throw std::runtime_error(message);}}

struct Value {
 OrtValue* p=nullptr;
 Value()=default;explicit Value(OrtValue* p):p(p){}
 Value(const Value&)=delete;Value& operator=(const Value&)=delete;
 Value(Value&& other)noexcept:p(other.p){other.p=nullptr;}
 Value& operator=(Value&& other)noexcept{if(p)ort().ReleaseValue(p);p=other.p;other.p=nullptr;return *this;}
 ~Value(){if(p)ort().ReleaseValue(p);}
 std::vector<int64_t> shape()const {OrtTensorTypeAndShapeInfo* info=nullptr;checked(ort().GetTensorTypeAndShape(p,&info));try{size_t rank=0,count=0;checked(ort().GetDimensionsCount(info,&rank));checked(ort().GetTensorShapeElementCount(info,&count));if(rank>6||count>100000000)throw std::runtime_error("Chatterbox tensor exceeds bound");std::vector<int64_t> out(rank);checked(ort().GetDimensions(info,out.data(),rank));ort().ReleaseTensorTypeAndShapeInfo(info);return out;}catch(...){ort().ReleaseTensorTypeAndShapeInfo(info);throw;}}
 size_t count()const{size_t n=1;for(auto d:shape()){if(d<0||d>100000000)throw std::runtime_error("Chatterbox invalid output dimensions");n*=size_t(d);if(n>100000000)throw std::runtime_error("Chatterbox tensor bound");}return n;}
 template<class T> T* data(ONNXTensorElementDataType expected)const{OrtTensorTypeAndShapeInfo* info=nullptr;checked(ort().GetTensorTypeAndShape(p,&info));ONNXTensorElementDataType actual;auto* status=ort().GetTensorElementType(info,&actual);ort().ReleaseTensorTypeAndShapeInfo(info);checked(status);if(actual!=expected)throw std::runtime_error("Unexpected Chatterbox tensor dtype");void* out=nullptr;checked(ort().GetTensorMutableData(p,&out));return static_cast<T*>(out);}
 int scalar()const{if(count()!=1)throw std::runtime_error("Chatterbox scalar shape mismatch");return *data<int32_t>(ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32);}
};
struct Session {
 OrtSession* p=nullptr;std::vector<std::string> outputs;
 ~Session(){if(p)ort().ReleaseSession(p);}
 void load(OrtEnv* env,OrtSessionOptions* options,const std::string& path){checked(ort().CreateSession(env,path.c_str(),options,&p));size_t n=0;checked(ort().SessionGetOutputCount(p,&n));if(n>100)throw std::runtime_error("Chatterbox output count exceeds bound");OrtAllocator* allocator=nullptr;checked(ort().GetAllocatorWithDefaultOptions(&allocator));for(size_t i=0;i<n;++i){char* name=nullptr;checked(ort().SessionGetOutputName(p,i,allocator,&name));outputs.emplace_back(name);allocator->Free(allocator,name);}}
 std::map<std::string,Value> run(OrtRunOptions* options,const std::vector<std::pair<std::string,const OrtValue*>>& feeds){std::vector<const char*> names,outNames;std::vector<const OrtValue*> values;for(const auto& [name,value]:feeds){names.push_back(name.c_str());values.push_back(value);}for(const auto& name:outputs)outNames.push_back(name.c_str());std::vector<OrtValue*> out(outputs.size(),nullptr);auto* error=ort().Run(p,options,names.data(),values.data(),values.size(),outNames.data(),outNames.size(),out.data());if(error){for(auto* value:out)if(value)ort().ReleaseValue(value);checked(error);}std::map<std::string,Value> result;for(size_t i=0;i<out.size();++i)result.emplace(outputs[i],Value(out[i]));return result;}
};
}