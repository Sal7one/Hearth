// Model contracts: docs/manga-ocr-adapters.md. No Python runtime is required.
#include "japanese_ocr.h"
#include "japanese_geometry.h"
#include "../onnx/include/onnxruntime/onnxruntime_c_api.h"
#include <atomic>
#include <chrono>
#include <mutex>

namespace hearth::ocr {
namespace {
const OrtApi* api() { auto* a=OrtGetApiBase()->GetApi(ORT_API_VERSION); if(!a)throw std::runtime_error("ONNX Runtime API unavailable");return a; }
void check(OrtStatus* s) { if(s){std::string message=api()->GetErrorMessage(s);api()->ReleaseStatus(s);throw std::runtime_error(message);} }
struct Value {
    OrtValue* p=nullptr;
    Value()=default; Value(const Value&)=delete;
    Value(Value&& other) noexcept : p(other.p) { other.p=nullptr; }
    ~Value(){if(p)api()->ReleaseValue(p);}
    std::vector<int64_t> shape(ONNXTensorElementDataType expected) const {
        OrtTensorTypeAndShapeInfo* info=nullptr;check(api()->GetTensorTypeAndShape(p,&info));
        struct Guard { OrtTensorTypeAndShapeInfo* p;~Guard(){api()->ReleaseTensorTypeAndShapeInfo(p);} } guard{info};
        ONNXTensorElementDataType type;check(api()->GetTensorElementType(info,&type));
        if(type!=expected)throw std::runtime_error("Unexpected OCR tensor element type");
        size_t rank=0,count=0;check(api()->GetDimensionsCount(info,&rank));check(api()->GetTensorShapeElementCount(info,&count));
        if(rank>4 || count>50000000)throw std::runtime_error("OCR tensor exceeds bounds");
        std::vector<int64_t> dims(rank);check(api()->GetDimensions(info,dims.data(),rank));return dims;
    }
    template<class T> T* data() const {void* ptr=nullptr;check(api()->GetTensorMutableData(p,&ptr));return static_cast<T*>(ptr);}
};

// Bilinear upright ARGB -> CHW. Meiki uses BGR [0,1]; Manga uses grayscale [-1,1].
std::vector<float> image(const uint32_t* pixels,int width,int height,Box crop,int tw,int th,int cw,int ch,bool manga) {
    if(cw<1||ch<1||cw>tw||ch>th||crop.width<1||crop.height<1)throw std::runtime_error("Invalid OCR resize");
    std::vector<float> out(size_t(3)*tw*th,0.f);
    for(int y=0;y<ch;++y)for(int x=0;x<cw;++x){
        float fx=std::clamp(crop.x+(x+.5f)*crop.width/cw-.5f,0.f,float(width-1));
        float fy=std::clamp(crop.y+(y+.5f)*crop.height/ch-.5f,0.f,float(height-1));
        int x0=int(fx),y0=int(fy),x1=std::min(x0+1,width-1),y1=std::min(y0+1,height-1);float dx=fx-x0,dy=fy-y0;
        for(int c=0;c<3;++c){
            auto sample=[&](int a,int b){auto p=pixels[b*width+a];return manga ? std::round(.299f*((p>>16)&255)+.587f*((p>>8)&255)+.114f*(p&255))/255.f : float((p>>(c*8))&255)/255.f;};
            float v=(sample(x0,y0)*(1-dx)+sample(x1,y0)*dx)*(1-dy)+(sample(x0,y1)*(1-dx)+sample(x1,y1)*dx)*dy;
            out[(size_t(c)*th+y)*tw+x]=manga ? (v-.5f)*2 : v;
        }
    }
    return out;
}
}

struct JapaneseOcr::Impl {
    int kind; OrtEnv* env=nullptr;OrtSessionOptions* options=nullptr;OrtMemoryInfo* memory=nullptr;OrtRunOptions* run=nullptr;
    OrtSession* first=nullptr;OrtSession* second=nullptr;OrtSession* third=nullptr;
    std::mutex mutex;std::atomic<bool> cancelled{false};std::chrono::steady_clock::time_point deadline;
    explicit Impl(int k):kind(k){}
    ~Impl(){if(third)api()->ReleaseSession(third);if(second)api()->ReleaseSession(second);if(first)api()->ReleaseSession(first);if(run)api()->ReleaseRunOptions(run);if(memory)api()->ReleaseMemoryInfo(memory);if(options)api()->ReleaseSessionOptions(options);if(env)api()->ReleaseEnv(env);}
    void open(const std::string& a,const std::string& b,const std::string& c){
        if(kind!=1&&kind!=2)throw std::invalid_argument("Unknown Japanese OCR architecture");
        check(api()->CreateEnv(ORT_LOGGING_LEVEL_ERROR,"hearth-japanese-ocr",&env));check(api()->CreateSessionOptions(&options));
        check(api()->SetIntraOpNumThreads(options,2));check(api()->SetInterOpNumThreads(options,1));
        check(api()->SetSessionGraphOptimizationLevel(options,ORT_ENABLE_ALL));
        check(api()->AddSessionConfigEntry(options,"session.intra_op.allow_spinning","0"));
        check(api()->AddSessionConfigEntry(options,"session.inter_op.allow_spinning","0"));
        check(api()->CreateCpuMemoryInfo(OrtArenaAllocator,OrtMemTypeDefault,&memory));check(api()->CreateRunOptions(&run));
        check(api()->CreateSession(env,a.c_str(),options,&first));check(api()->CreateSession(env,b.c_str(),options,&second));
        if(kind==2)check(api()->CreateSession(env,c.c_str(),options,&third));
    }
    void active(){if(cancelled.load())throw std::runtime_error("OCR cancelled");if(std::chrono::steady_clock::now()>deadline)throw std::runtime_error("OCR exceeded 45 seconds. Select a smaller region.");}
    template<class T> Value tensor(std::vector<T>& data,std::vector<int64_t> dims,ONNXTensorElementDataType type){
        Value v;check(api()->CreateTensorWithDataAsOrtValue(memory,data.data(),data.size()*sizeof(T),dims.data(),dims.size(),type,&v.p));return v;
    }
    std::vector<Value> infer(OrtSession* session,std::vector<const char*> names,std::vector<const OrtValue*> inputs,std::vector<const char*> outputs){
        active();std::vector<Value> result(outputs.size());std::vector<OrtValue*> raw(outputs.size(),nullptr);
        auto status=api()->Run(session,run,names.data(),inputs.data(),inputs.size(),outputs.data(),outputs.size(),raw.data());
        for(size_t i=0;i<raw.size();++i)result[i].p=raw[i];check(status);active();return result;
    }
    std::vector<Line> manga(const uint32_t* pixels,int w,int h){
        auto data=image(pixels,w,h,{0,0,w,h,1},224,224,224,224,true);
        auto input=tensor(data,{1,3,224,224},ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT);
        auto encoded=infer(first,{"pixel_values"},{input.p},{"last_hidden_state"});
        if(encoded[0].shape(ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT)!=std::vector<int64_t>{1,197,768})throw std::runtime_error("Unexpected Manga encoder shape");
        std::vector<int64_t> ids{2};Decoded decoded;
        for(int step=0;step<299;++step){
            auto tokens=tensor(ids,{1,int64_t(ids.size())},ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64);
            auto output=infer(second,{"input_ids","encoder_hidden_states"},{tokens.p,encoded[0].p},{"logits"});
            if(output[0].shape(ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT)!=std::vector<int64_t>{1,int64_t(ids.size()),6144})throw std::runtime_error("Unexpected Manga decoder shape");
            auto scores=output[0].data<float>()+(ids.size()-1)*6144;int best=0;
            for(int i=0;i<6144;++i){if(!std::isfinite(scores[i]))throw std::runtime_error("Non-finite Manga logits");if(scores[i]>scores[best])best=i;}
            if(best==3){decoded.confidence=0;return {{Box{0,0,w,h,1},std::move(decoded)}};}
            if(best==1)throw std::runtime_error("Manga OCR generated an unknown token. Select a clearer crop.");
            // The checkpoint may emit CLS again after decoder_start_token_id.
            // Keep it in the decoder context, matching skip_special_tokens at output.
            ids.push_back(best);if(best>=4)decoded.tokens.push_back(best);
        }
        throw std::runtime_error("Manga OCR reached its 300-token limit. Select a smaller bubble.");
    }
    std::vector<Value> meikiRun(OrtSession* session,std::vector<float>& pixels,int tw,int th,int ow,int oh,bool detector){
        auto input=tensor(pixels,{1,3,th,tw},ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT);
        std::vector<int64_t> sizes{ow,oh};auto size=tensor(sizes,{1,2},ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64);
        auto output=infer(session,{"images","orig_target_sizes"},{input.p,size.p},{detector?"labels":"char_codes","boxes","scores"});
        auto labels=output[0].shape(detector?ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64:ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32);
        if(labels.size()!=2||labels[0]!=1||labels[1]<1||labels[1]>64||
           output[1].shape(ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT)!=std::vector<int64_t>{1,labels[1],4}||
           output[2].shape(ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT)!=labels)throw std::runtime_error("Unexpected Meiki output shape");
        return output;
    }
    std::vector<Line> meiki(const uint32_t* pixels,int w,int h){
        float scale=std::min(960.f/w,544.f/h);int cw=std::max(1,int(w*scale)),ch=std::max(1,int(h*scale));
        auto data=image(pixels,w,h,{0,0,w,h,1},960,544,cw,ch,false);
        auto detected=meikiRun(first,data,960,544,int(960/scale),int(544/scale),true);
        auto n=detected[2].shape(ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT)[1];auto boxes=detected[1].data<float>();auto scores=detected[2].data<float>();
        std::vector<Line> lines;
        for(int i=0;i<n;++i){
            if(!std::isfinite(scores[i]))throw std::runtime_error("Non-finite Meiki score");if(scores[i]<.5f)continue;
            for(int k=0;k<4;++k)if(!std::isfinite(boxes[4*i+k]))throw std::runtime_error("Non-finite Meiki box");
            int x=int(std::clamp(boxes[4*i],0.f,float(w))),y=int(std::clamp(boxes[4*i+1],0.f,float(h)));
            int right=int(std::clamp(boxes[4*i+2],0.f,float(w))),bottom=int(std::clamp(boxes[4*i+3],0.f,float(h)));
            Box box{x,y,right-x,bottom-y,scores[i]};if(box.width<2||box.height<2)continue;
            bool vertical=box.height>box.width;int tw=vertical?32:960,th=vertical?480:32;
            std::vector<CharacterBox> candidates;
            float factor=vertical?32.f/box.width:32.f/box.height;
            int segment=vertical?std::max(1,int(420/factor)):box.height;
            int stride=vertical?std::max(1,int(356/factor)):box.height;
            for(int offset=0;offset<box.height;offset+=stride){
                Box crop=box;if(vertical){crop.y+=offset;crop.height=std::min(segment,box.height-offset);}
                float f=std::min(float(tw)/crop.width,float(th)/crop.height);
                int ew=std::max(1,int(std::round(crop.width*f))),eh=std::max(1,int(std::round(crop.height*f)));
                auto content=image(pixels,w,h,crop,tw,th,std::min(tw,ew),std::min(th,eh),false);
                auto recognized=meikiRun(vertical?third:second,content,tw,th,tw,th,false);
                auto count=recognized[2].shape(ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT)[1];auto chars=recognized[0].data<int32_t>();auto b=recognized[1].data<float>();auto s=recognized[2].data<float>();
                for(int j=0;j<count;++j){
                    if(!std::isfinite(s[j]))throw std::runtime_error("Non-finite Meiki character score");if(s[j]<.1f)continue;
                    float start=b[4*j+(vertical?1:0)],end=b[4*j+(vertical?3:2)],extent=float(vertical?eh:ew);
                    if(!std::isfinite(start)||!std::isfinite(end))throw std::runtime_error("Non-finite Meiki character box");
                    start=std::clamp(start,0.f,extent);end=std::clamp(end,0.f,extent);if(end<=start)continue;
                    float sourceExtent=float(vertical?crop.height:crop.width);
                    candidates.push_back({chars[j],start/extent*sourceExtent+(vertical?offset:0),end/extent*sourceExtent+(vertical?offset:0),s[j]});
                }
                if(!vertical||offset+crop.height>=box.height)break;
            }
            auto accepted=characters(std::move(candidates));Decoded text;
            for(auto& c:accepted){text.tokens.push_back(c.code);text.confidence+=c.confidence;}
            if(!accepted.empty()){text.confidence/=accepted.size();lines.push_back({box,std::move(text)});}
        }
        const bool columns=std::all_of(lines.begin(),lines.end(),[](auto& line){return line.box.height>line.box.width;});
        std::stable_sort(lines.begin(),lines.end(),[columns](auto& a,auto& b){
            if(columns && a.box.x!=b.box.x)return a.box.x>b.box.x;
            if(a.box.y!=b.box.y)return a.box.y<b.box.y;
            return a.box.x<b.box.x;
        });return lines;
    }
};
JapaneseOcr::JapaneseOcr(int kind,const std::string& a,const std::string& b,const std::string& c):impl(std::make_unique<Impl>(kind)){impl->open(a,b,c);}
JapaneseOcr::~JapaneseOcr()=default;
void JapaneseOcr::cancel(){impl->cancelled.store(true);if(impl->run){auto* status=api()->RunOptionsSetTerminate(impl->run);if(status)api()->ReleaseStatus(status);}}
std::vector<Line> JapaneseOcr::recognize(const uint32_t* pixels,int w,int h){
    if(!pixels||w<1||h<1||w>2048||h>2048)throw std::invalid_argument("OCR image must be at most 2048 pixels per side");
    std::lock_guard<std::mutex> lock(impl->mutex);impl->deadline=std::chrono::steady_clock::now()+std::chrono::seconds(45);impl->active();
    return impl->kind==1?impl->manga(pixels,w,h):impl->meiki(pixels,w,h);
}
}
