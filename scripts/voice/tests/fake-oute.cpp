// Offline C ABI fixture only; never staged or packaged as a production runtime.
#include "hearth_oute.h"
#include <atomic>
#include <limits>
#include <string>
#include <thread>
struct hearth_oute {std::atomic_bool cancelled{false};};
extern "C" {
int hearth_oute_abi(){return HEARTH_OUTE_ABI;}
const char* hearth_oute_last_error(){return "خطأ محرك OuteTTS 🗣";}
hearth_oute* hearth_oute_create(const char* model,const char*,int){return std::string(model)=="unicode-error"?nullptr:new hearth_oute;}
void hearth_oute_destroy(hearth_oute* p){delete p;}
void hearth_oute_cancel(hearth_oute* p){p->cancelled=true;}
int hearth_oute_synthesize(hearth_oute* p,const char* text,hearth_oute_pcm cb,void* user){
 std::string mode(text);float samples[24001]={.25f,-.25f};
 if(mode=="wait"){while(!p->cancelled)std::this_thread::yield();return 1;}
 if(p->cancelled)return 1;
 if(mode=="threaded"){int result=0;std::thread worker([&]{result=cb(user,samples,24000,24000);});worker.join();return result&&!p->cancelled?0:1;}
 if(mode=="empty")return 0;
 if(mode=="bad-pcm"){samples[0]=std::numeric_limits<float>::quiet_NaN();return cb(user,samples,1,24000)?0:1;}
 if(mode=="oversized")return cb(user,samples,24001,24000)?0:1;
 if(mode=="rate")return cb(user,samples,1,44100)?0:1;
 if(mode=="total"){for(int i=0;i<46;i++)if(!cb(user,samples,24000,24000))return 1;return 0;}
 return cb(user,samples,24000,24000)&&!p->cancelled?0:1;
}
}
