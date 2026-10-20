// Offline C ABI contract fixture only. Never staged or packaged.
#include "moss.h"
#include <string>
#include <thread>
#include <limits>
struct hearth_moss {bool stopped=false;};
extern "C" {
hearth_moss* hearth_moss_create(const char* root,int){return std::string(root)=="unicode-error"?nullptr:new hearth_moss;}
void hearth_moss_destroy(hearth_moss* p){delete p;}
void hearth_moss_cancel(hearth_moss* p){if(p)p->stopped=true;}
const char* hearth_moss_last_error(){return "خطأ محرك MOSS 🗣";}
int hearth_moss_voice_count(const hearth_moss*){return 2;}
const char* hearth_moss_voice_name(const hearth_moss*,int i){return i?"Ava":"Junhao";}
int hearth_moss_synthesize(hearth_moss* p,const char* text,const char*,hearth_moss_pcm callback,void* opaque){
 if(p->stopped)return 1;float values[48001]={.25f,-.25f};
 if(std::string(text)=="bad-pcm"){values[0]=std::numeric_limits<float>::quiet_NaN();callback(values,1,48000,opaque);return 1;}
 if(std::string(text)=="threaded-close"){int keep=0;std::thread worker([&]{keep=callback(values,48001,48000,opaque);});worker.join();return keep?0:1;}const int status=callback(values,48001,48000,opaque)?0:1;return p->stopped?1:status;
}
}
