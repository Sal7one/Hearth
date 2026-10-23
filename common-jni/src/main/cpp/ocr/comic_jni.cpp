#include "comic_detector.h"
#include "../common/lease_registry.h"
#include "../jni/jni_helper.h"
#include <jni.h>
namespace {
stt::concurrency::LeaseRegistry<hearth::ocr::ComicDetector> models;
std::string path(JNIEnv* env,jbyteArray input) {
    if(!input) throw std::invalid_argument("Missing comic detector path");
    const auto n=env->GetArrayLength(input);
    if(n<1 || n>4096) throw std::invalid_argument("Invalid comic detector path");
    std::string result(n,'\0'); env->GetByteArrayRegion(input,0,n,reinterpret_cast<jbyte*>(result.data()));
    if(env->ExceptionCheck()) throw std::runtime_error("Cannot read comic detector path");
    if(result.find('\0')!=std::string::npos) throw std::invalid_argument("NUL in comic detector path");
    return result;
}
void fail(JNIEnv* env,const char* message) {
    // Same package and owned-handle lifetime as existing OCR adapters.
    jni::throwIllegalStateException(env,message);
}
}
extern "C" JNIEXPORT jlong JNICALL Java_com_sal7one_common_1jni_ocr_ComicNative_create(JNIEnv* env,jobject,jbyteArray model) {
    try {return models.insert(std::make_unique<hearth::ocr::ComicDetector>(path(env,model)));}
    catch(const std::exception& error) {fail(env,error.what());return 0;}
    catch(...) {fail(env,"Comic detector initialization failed");return 0;}
}
extern "C" JNIEXPORT jlong JNICALL Java_com_sal7one_common_1jni_ocr_ComicNative_createKoharu(JNIEnv* env,jobject,jbyteArray model) {
    try {return models.insert(std::make_unique<hearth::ocr::ComicDetector>(path(env,model),true));}
    catch(const std::exception& error) {fail(env,error.what());return 0;}
    catch(...) {fail(env,"Koharu segmentation initialization failed");return 0;}
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_sal7one_common_1jni_ocr_ComicNative_detect(JNIEnv* env,jobject,jlong id,jintArray argb,jint width,jint height) {
    try {
        auto model=models.acquire(id); if(!model) throw std::invalid_argument("Comic detector is closed");
        if(!argb || width<1 || height<1 || width>2048 || height>2048 || env->GetArrayLength(argb)!=width*height)
            throw std::invalid_argument("Invalid comic detector pixels");
        std::vector<uint32_t> pixels(size_t(width)*height);
        env->GetIntArrayRegion(argb,0,width*height,reinterpret_cast<jint*>(pixels.data())); if(env->ExceptionCheck()) return nullptr;
        auto found=model->detect(pixels.data(),width,height);
        auto result=env->NewObjectArray(found.size(),env->FindClass("[I"),nullptr); if(!result) return nullptr;
        for(size_t i=0;i<found.size();++i) {
            const auto& d=found[i]; const jint row[]={d.box.x,d.box.y,d.box.width,d.box.height,int(d.box.score*10000),d.label};
            const auto length=6+d.mask.size();auto entry=env->NewIntArray(length); if(!entry) return nullptr;
            env->SetIntArrayRegion(entry,0,6,row); env->SetObjectArrayElement(result,i,entry); env->DeleteLocalRef(entry);
            // The returned row owns its copy; no OrtValue or borrowed native handle escapes.
            if(!d.mask.empty()){
                auto owned=static_cast<jintArray>(env->GetObjectArrayElement(result,i));if(!owned)return nullptr;
                env->SetIntArrayRegion(owned,6,d.mask.size(),reinterpret_cast<const jint*>(d.mask.data()));env->DeleteLocalRef(owned);
            }
            if(env->ExceptionCheck()) return nullptr;
        }
        return result;
    } catch(const std::exception& error) {fail(env,error.what());return nullptr;}
    catch(...) {fail(env,"Comic detection failed");return nullptr;}
}
extern "C" JNIEXPORT jlong JNICALL Java_com_sal7one_common_1jni_ocr_ComicNative_createInpainter(JNIEnv* env,jobject,jbyteArray model) {
    try{return models.insert(std::make_unique<hearth::ocr::ComicDetector>(path(env,model),2));}
    catch(const std::exception& error){fail(env,error.what());return 0;}
    catch(...){fail(env,"LaMa initialization failed");return 0;}
}
extern "C" JNIEXPORT jintArray JNICALL Java_com_sal7one_common_1jni_ocr_ComicNative_inpaint(JNIEnv* env,jobject,jlong id,jintArray argb,jint width,jint height,jintArray mask) {
    try{
        auto model=models.acquire(id);if(!model)throw std::invalid_argument("Comic inpainter is closed");
        if(!argb || !mask || width<1 || height<1 || width>2048 || height>2048 || env->GetArrayLength(argb)!=width*height)throw std::invalid_argument("Invalid comic inpainter pixels");
        const auto length=env->GetArrayLength(mask);if(length<3 || length>3'000'000 || length%3)throw std::invalid_argument("Invalid comic inpainter mask");
        std::vector<uint32_t> pixels(size_t(width)*height);std::vector<int32_t> spans(length);
        env->GetIntArrayRegion(argb,0,width*height,reinterpret_cast<jint*>(pixels.data()));env->GetIntArrayRegion(mask,0,length,reinterpret_cast<jint*>(spans.data()));if(env->ExceptionCheck())return nullptr;
        auto patch=model->inpaint(pixels.data(),width,height,spans);auto out=env->NewIntArray(patch.size());if(!out)return nullptr;
        env->SetIntArrayRegion(out,0,patch.size(),reinterpret_cast<const jint*>(patch.data()));return out;
    }catch(const std::exception& error){fail(env,error.what());return nullptr;}
    catch(...){fail(env,"LaMa cleanup failed");return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_ocr_ComicNative_cancel(JNIEnv*,jobject,jlong id) {auto model=models.acquire(id);if(model) model->cancel();}
extern "C" JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_ocr_ComicNative_destroy(JNIEnv*,jobject,jlong id) {
    auto retired=models.retire(id);if(auto* model=retired.signalTarget()) model->cancel();auto exclusive=std::move(retired).lockExclusive();
}
