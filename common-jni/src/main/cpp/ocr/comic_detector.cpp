#include "comic_detector.h"
#include "model_path.h"
#include "../onnx/include/onnxruntime/onnxruntime_c_api.h"
#include <atomic>
#include <array>
#include <mutex>

namespace hearth::ocr {
namespace {
const OrtApi* api() {
    static const OrtApi* result=OrtGetApiBase()->GetApi(ORT_API_VERSION);
    if(!result) throw std::runtime_error("ONNX Runtime API unavailable for comic detection");
    return result;
}
void checked(OrtStatus* status) {
    if(status) { std::string error=api()->GetErrorMessage(status); api()->ReleaseStatus(status); throw std::runtime_error(error); }
}
struct Value {
    OrtValue* value=nullptr;
    Value() = default;
    Value(const Value&) = delete;
    Value& operator=(const Value&) = delete;
    ~Value() { if(value) api()->ReleaseValue(value); }
};
void validateTensor(OrtValue* value, ONNXTensorElementDataType expected, const std::vector<int64_t>& dimensions) {
    OrtTensorTypeAndShapeInfo* shape=nullptr;
    checked(api()->GetTensorTypeAndShape(value,&shape));
    try {
        ONNXTensorElementDataType type; size_t rank=0;
        checked(api()->GetTensorElementType(shape,&type)); checked(api()->GetDimensionsCount(shape,&rank));
        if(type!=expected || rank!=dimensions.size()) throw std::runtime_error("Comic detector output type/rank differs from the pinned export");
        std::vector<int64_t> actual(rank); checked(api()->GetDimensions(shape,actual.data(),rank));
        if(actual!=dimensions) throw std::runtime_error("Comic detector output shape differs from the pinned export");
    } catch(...) { api()->ReleaseTensorTypeAndShapeInfo(shape); throw; }
    api()->ReleaseTensorTypeAndShapeInfo(shape);
}
}
struct ComicDetector::Impl {
    OrtEnv* env=nullptr; OrtSessionOptions* options=nullptr; OrtMemoryInfo* memory=nullptr;
    OrtSession* session=nullptr; OrtRunOptions* run=nullptr;
    std::atomic<bool> cancelled{false}; std::mutex mutex;
    ~Impl() {
        if(session) api()->ReleaseSession(session); if(run) api()->ReleaseRunOptions(run);
        if(memory) api()->ReleaseMemoryInfo(memory); if(options) api()->ReleaseSessionOptions(options);
        if(env) api()->ReleaseEnv(env);
    }
};
ComicDetector::ComicDetector(const std::string& model):impl(std::make_unique<Impl>()) {
    checked(api()->CreateEnv(ORT_LOGGING_LEVEL_ERROR,"hearth-comic-detection",&impl->env));
    checked(api()->CreateSessionOptions(&impl->options));
    checked(api()->SetIntraOpNumThreads(impl->options,2)); checked(api()->SetInterOpNumThreads(impl->options,1));
    checked(api()->AddSessionConfigEntry(impl->options,"session.intra_op.allow_spinning","0"));
    checked(api()->SetSessionGraphOptimizationLevel(impl->options,ORT_ENABLE_ALL));
    checked(api()->CreateCpuMemoryInfo(OrtArenaAllocator,OrtMemTypeDefault,&impl->memory));
    checked(api()->CreateRunOptions(&impl->run));
    const auto path=nativeModelPath(model);
    checked(api()->CreateSession(impl->env,path.c_str(),impl->options,&impl->session));
    size_t inputs=0,outputs=0;
    checked(api()->SessionGetInputCount(impl->session,&inputs)); checked(api()->SessionGetOutputCount(impl->session,&outputs));
    if(inputs!=2 || outputs!=3) throw std::runtime_error("Comic detector must have two inputs and three outputs");
}
ComicDetector::~ComicDetector()=default;
void ComicDetector::cancel() {
    impl->cancelled.store(true);
    if(impl->run) { auto status=api()->RunOptionsSetTerminate(impl->run); if(status) api()->ReleaseStatus(status); }
}
std::vector<ComicDetection> ComicDetector::detect(const uint32_t* pixels,int width,int height) {
    std::lock_guard<std::mutex> lock(impl->mutex);
    if(!pixels || width<1 || height<1 || width>2048 || height>2048) throw std::invalid_argument("Comic detector input must be at most 2048 pixels per side");
    if(impl->cancelled.load()) throw std::runtime_error("Comic detection cancelled");
    constexpr int side=640,plane=side*side;
    std::vector<float> image(3*plane);
    // Export preprocessing: stretch to 640², bilinear RGB / 255. No letterbox or ImageNet normalization.
    for(int y=0;y<side;++y) for(int x=0;x<side;++x) {
        const float fx=std::clamp((x+.5f)*width/side-.5f,0.f,float(width-1));
        const float fy=std::clamp((y+.5f)*height/side-.5f,0.f,float(height-1));
        const int x0=int(fx),y0=int(fy),x1=std::min(x0+1,width-1),y1=std::min(y0+1,height-1);
        const float dx=fx-x0,dy=fy-y0;
        for(int c=0;c<3;++c) {
            const int shift=16-c*8;
            auto channel=[&](int a,int b) {return float((pixels[b*width+a]>>shift)&255)/255.f;};
            image[c*plane+y*side+x]=(channel(x0,y0)*(1-dx)+channel(x1,y0)*dx)*(1-dy)+(channel(x0,y1)*(1-dx)+channel(x1,y1)*dx)*dy;
        }
    }
    int64_t sizes[]={width,height},imageShape[]={1,3,side,side},sizeShape[]={1,2};
    Value input,target,labels,boxes,scores;
    checked(api()->CreateTensorWithDataAsOrtValue(impl->memory,image.data(),image.size()*sizeof(float),imageShape,4,ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,&input.value));
    checked(api()->CreateTensorWithDataAsOrtValue(impl->memory,sizes,sizeof(sizes),sizeShape,2,ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64,&target.value));
    const OrtValue* values[]={input.value,target.value}; const char* names[]={"images","orig_target_sizes"};
    const char* outputs[]={"labels","boxes","scores"}; OrtValue* result[]={nullptr,nullptr,nullptr};
    auto status=api()->Run(impl->session,impl->run,names,values,2,outputs,3,result);
    labels.value=result[0]; boxes.value=result[1]; scores.value=result[2]; checked(status);
    if(impl->cancelled.load()) throw std::runtime_error("Comic detection cancelled");
    validateTensor(labels.value,ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64,{1,300});
    validateTensor(boxes.value,ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,{1,300,4});
    validateTensor(scores.value,ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,{1,300});
    int64_t* classes=nullptr; float* coords=nullptr; float* confidences=nullptr;
    checked(api()->GetTensorMutableData(labels.value,reinterpret_cast<void**>(&classes)));
    checked(api()->GetTensorMutableData(boxes.value,reinterpret_cast<void**>(&coords)));
    checked(api()->GetTensorMutableData(scores.value,reinterpret_cast<void**>(&confidences)));
    std::vector<ComicDetection> detections;
    for(int i=0;i<300;++i) {
        if(classes[i]<0 || classes[i]>2 || !std::isfinite(confidences[i]) || confidences[i]<0 || confidences[i]>1)
            throw std::runtime_error("Invalid comic detector class/confidence");
        for(int j=0;j<4;++j) if(!std::isfinite(coords[i*4+j])) throw std::runtime_error("Non-finite comic detector geometry");
        if(confidences[i]<.3f) continue;
        const int left=int(std::floor(std::clamp(coords[i*4],0.f,float(width))));
        const int top=int(std::floor(std::clamp(coords[i*4+1],0.f,float(height))));
        const int right=int(std::ceil(std::clamp(coords[i*4+2],0.f,float(width))));
        const int bottom=int(std::ceil(std::clamp(coords[i*4+3],0.f,float(height))));
        if(right-left>=3 && bottom-top>=3) detections.push_back({{left,top,right-left,bottom-top,confidences[i]},int(classes[i])});
    }
    return detections;
}
}
