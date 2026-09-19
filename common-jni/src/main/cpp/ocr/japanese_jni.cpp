#include "japanese_ocr.h"
#include "../common/lease_registry.h"
#include <jni.h>
namespace {
stt::concurrency::LeaseRegistry<hearth::ocr::JapaneseOcr> models;
std::string path(JNIEnv* e,jbyteArray input){if(!input)throw std::invalid_argument("Missing OCR path");int n=e->GetArrayLength(input);if(n<1||n>4096)throw std::invalid_argument("Invalid OCR path");std::string s(n,'\0');e->GetByteArrayRegion(input,0,n,reinterpret_cast<jbyte*>(s.data()));if(s.find('\0')!=std::string::npos)throw std::invalid_argument("NUL in OCR path");return s;}
void fail(JNIEnv* e,const char* message){if(!e->ExceptionCheck())e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),message);}
}
extern "C" JNIEXPORT jlong JNICALL Java_com_sal7one_common_1jni_ocr_JapaneseNative_create(JNIEnv* e,jobject,jint kind,jbyteArray d,jbyteArray r,jbyteArray v){try{return models.insert(std::make_unique<hearth::ocr::JapaneseOcr>(kind,path(e,d),path(e,r),kind==2?path(e,v):std::string()));}catch(const std::exception& x){fail(e,x.what());return 0;}catch(...){fail(e,"OCR initialization failed");return 0;}}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_sal7one_common_1jni_ocr_JapaneseNative_recognize(JNIEnv* e,jobject,jlong id,jintArray argb,jint w,jint h){
 try{
    auto model=models.acquire(id);if(!model)throw std::invalid_argument("OCR model is closed");
    if(!argb||w<1||h<1||w>2048||h>2048||e->GetArrayLength(argb)!=w*h)throw std::invalid_argument("Invalid OCR pixels");
    std::vector<uint32_t> pixels(static_cast<size_t>(w)*h);e->GetIntArrayRegion(argb,0,w*h,reinterpret_cast<jint*>(pixels.data()));if(e->ExceptionCheck())return nullptr;
    auto lines=model->recognize(pixels.data(),w,h);auto out=e->NewObjectArray(lines.size(),e->FindClass("[I"),nullptr);if(!out)return nullptr;
    for(size_t i=0;i<lines.size();++i){const auto& line=lines[i];std::vector<jint> row={line.box.x,line.box.y,line.box.width,line.box.height,int(line.text.confidence*10000)};row.insert(row.end(),line.text.tokens.begin(),line.text.tokens.end());auto a=e->NewIntArray(row.size());if(!a)return nullptr;e->SetIntArrayRegion(a,0,row.size(),row.data());e->SetObjectArrayElement(out,i,a);e->DeleteLocalRef(a);if(e->ExceptionCheck())return nullptr;}return out;
 }catch(const std::exception& x){fail(e,x.what());return nullptr;}catch(...){fail(e,"OCR inference failed");return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_ocr_JapaneseNative_cancel(JNIEnv*,jobject,jlong id){auto model=models.acquire(id);if(model)model->cancel();}
extern "C" JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_ocr_JapaneseNative_destroy(JNIEnv*,jobject,jlong id){auto retired=models.retire(id);if(auto* m=retired.signalTarget())m->cancel();auto exclusive=std::move(retired).lockExclusive();}
