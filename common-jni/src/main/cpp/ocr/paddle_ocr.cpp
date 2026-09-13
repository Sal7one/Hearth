#include "paddle_ocr.h"
#include "../onnx/include/onnxruntime/onnxruntime_c_api.h"
#include <atomic>
#include <array>
#include <mutex>

namespace hearth::ocr {
namespace {
const OrtApi* api() { static const OrtApi* a=OrtGetApiBase()->GetApi(ORT_API_VERSION);if(!a)throw std::runtime_error("ONNX Runtime API unavailable for PaddleOCR");return a; }
void check(OrtStatus* status) {if(status){std::string error=api()->GetErrorMessage(status);api()->ReleaseStatus(status);throw std::runtime_error(error);}}
struct Tensor {OrtValue* value=nullptr; ~Tensor(){if(value)api()->ReleaseValue(value);} Tensor()=default;Tensor(const Tensor&)=delete;};
struct Shape {OrtTensorTypeAndShapeInfo* value=nullptr;~Shape(){if(value)api()->ReleaseTensorTypeAndShapeInfo(value);}};
struct Names {OrtAllocator* allocator=nullptr;char* in=nullptr;char* out=nullptr;~Names(){if(in)allocator->Free(allocator,in);if(out)allocator->Free(allocator,out);}};
}
struct PaddleOcr::Impl {
    OrtEnv* env=nullptr;OrtSessionOptions* options=nullptr;OrtMemoryInfo* memory=nullptr;OrtSession* det=nullptr;OrtSession* rec=nullptr;OrtRunOptions* run=nullptr;
    std::mutex mutex;std::atomic<bool> cancelled{false};int classes;
    explicit Impl(int n):classes(n){}
    ~Impl(){if(rec)api()->ReleaseSession(rec);if(det)api()->ReleaseSession(det);if(run)api()->ReleaseRunOptions(run);if(memory)api()->ReleaseMemoryInfo(memory);if(options)api()->ReleaseSessionOptions(options);if(env)api()->ReleaseEnv(env);}
    void initialize(const std::string& d,const std::string& r) {
        check(api()->CreateEnv(ORT_LOGGING_LEVEL_ERROR,"hearth-ocr",&env));check(api()->CreateSessionOptions(&options));
        check(api()->SetIntraOpNumThreads(options,2));check(api()->SetInterOpNumThreads(options,1));
        check(api()->SetSessionGraphOptimizationLevel(options,ORT_ENABLE_ALL));
        // No spinning while the camera is idle; preserve battery between analyses.
        check(api()->AddSessionConfigEntry(options,"session.intra_op.allow_spinning","0"));
        check(api()->CreateCpuMemoryInfo(OrtArenaAllocator,OrtMemTypeDefault,&memory));check(api()->CreateRunOptions(&run));
        check(api()->CreateSession(env,d.c_str(),options,&det));check(api()->CreateSession(env,r.c_str(),options,&rec));
    }
    std::vector<float> infer(OrtSession* session,std::vector<float>& data,int w,int h,std::vector<int64_t>& dims) {
        if(cancelled.load())throw std::runtime_error("OCR cancelled");
        size_t n=0;check(api()->SessionGetInputCount(session,&n));if(n!=1)throw std::runtime_error("PaddleOCR requires one image input");
        Names names;check(api()->GetAllocatorWithDefaultOptions(&names.allocator));check(api()->SessionGetInputName(session,0,names.allocator,&names.in));check(api()->SessionGetOutputName(session,0,names.allocator,&names.out));
        Tensor input,output;int64_t shape[]={1,3,h,w};check(api()->CreateTensorWithDataAsOrtValue(memory,data.data(),data.size()*sizeof(float),shape,4,ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,&input.value));
        const OrtValue* values[]={input.value};const char* inputs[]={names.in};const char* outputs[]={names.out};
        check(api()->Run(session,run,inputs,values,1,outputs,1,&output.value));
        Shape info;check(api()->GetTensorTypeAndShape(output.value,&info.value));ONNXTensorElementDataType type;
        check(api()->GetTensorElementType(info.value,&type));if(type!=ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT)throw std::runtime_error("OCR output must be float32");
        check(api()->GetDimensionsCount(info.value,&n));if(n>4)throw std::runtime_error("Unexpected OCR output rank");dims.resize(n);check(api()->GetDimensions(info.value,dims.data(),n));
        size_t count;check(api()->GetTensorShapeElementCount(info.value,&count));if(count>50000000)throw std::runtime_error("OCR output exceeds memory bound");
        float* result;check(api()->GetTensorMutableData(output.value,reinterpret_cast<void**>(&result)));return {result,result+count};
    }
    std::vector<float> image(const uint32_t* pixels,int width,int height,Box box,int w,int h,int contentWidth,bool detector) {
        std::vector<float> output(static_cast<size_t>(3)*w*h,0.f);
        const float mean[]={.485f,.456f,.406f},sd[]={.229f,.224f,.225f};
        for(int y=0;y<h;++y)for(int x=0;x<contentWidth;++x) {
            float fx=std::clamp(box.x+(x+.5f)*box.width/contentWidth-.5f,0.f,float(width-1));
            float fy=std::clamp(box.y+(y+.5f)*box.height/h-.5f,0.f,float(height-1));
            int x0=int(fx),y0=int(fy),x1=std::min(x0+1,width-1),y1=std::min(y0+1,height-1);float dx=fx-x0,dy=fy-y0;
            for(int c=0;c<3;++c){ // Publisher DecodeImage uses BGR for both stages.
                int shift=c*8;auto value=[&](int a,int b){return float((pixels[b*width+a]>>shift)&255)/255.f;};
                float v=(value(x0,y0)*(1-dx)+value(x1,y0)*dx)*(1-dy)+(value(x0,y1)*(1-dx)+value(x1,y1)*dx)*dy;
                output[(static_cast<size_t>(c)*h+y)*w+x]=detector?(v-mean[c])/sd[c]:(v-.5f)*2;
            }
        }
        return output;
    }
};
PaddleOcr::PaddleOcr(const std::string& d,const std::string& r,int classes):impl(std::make_unique<Impl>(classes)){if(classes<2||classes>30000)throw std::invalid_argument("Invalid OCR dictionary size");impl->initialize(d,r);}
PaddleOcr::~PaddleOcr()=default;
void PaddleOcr::cancel(){impl->cancelled.store(true);if(impl->run){auto status=api()->RunOptionsSetTerminate(impl->run);if(status)api()->ReleaseStatus(status);}}
std::vector<Line> PaddleOcr::recognize(const uint32_t* pixels,int width,int height,int maxSide) {
    std::lock_guard<std::mutex> lock(impl->mutex);
    if(!pixels||width<1||height<1||width>2048||height>2048||maxSide<320||maxSide>960)throw std::invalid_argument("OCR image must be at most 2048 pixels per side");
    float scale=std::min(1.f,float(maxSide)/std::max(width,height));
    int w=std::max(32,int(std::round(width*scale/32))*32),h=std::max(32,int(std::round(height*scale/32))*32);
    auto data=impl->image(pixels,width,height,{0,0,width,height,1},w,h,w,true);std::vector<int64_t> shape;auto probs=impl->infer(impl->det,data,w,h,shape);
    if(shape.size()!=4||shape[0]!=1||shape[1]!=1||shape[2]<1||shape[2]>2048||shape[3]<1||shape[3]>2048)throw std::runtime_error("Unsupported PaddleOCR detector output");
    const int detectorWidth=int(shape[3]),detectorHeight=int(shape[2]);
    auto boxes=regions(probs.data(),detectorWidth,detectorHeight);std::vector<Line> lines;
    for(auto box:boxes) {
        float sx=float(width)/detectorWidth,sy=float(height)/detectorHeight;
        int x=int(box.x*sx),y=int(box.y*sy);box={x,y,std::min(width-x,int(std::ceil(box.width*sx))),std::min(height-y,int(std::ceil(box.height*sy))),box.score};
        if(box.height<2||box.width<2)continue;
        int contentWidth=std::clamp(int(std::ceil(48.f*box.width/box.height)),1,1600),rw=std::max(320,contentWidth);
        auto crop=impl->image(pixels,width,height,box,rw,48,contentWidth,false);auto scores=impl->infer(impl->rec,crop,rw,48,shape);
        if(shape.size()!=3||shape[0]!=1||shape[2]!=impl->classes)throw std::runtime_error("PaddleOCR recognition model and dictionary do not match");
        auto decoded=ctc(scores.data(),int(shape[1]),int(shape[2]));
        if(!decoded.tokens.empty()&&decoded.confidence>=.5f)lines.push_back({box,std::move(decoded)});
    }
    return lines;
}
}
