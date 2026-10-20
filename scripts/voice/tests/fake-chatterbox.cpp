// Offline C ABI contract fixture only. Never staged or packaged.
#include "chatterbox.h"
#include <string>
#include <thread>
#include <limits>
struct hearth_chatterbox {bool stopped=false;};
extern "C" {
hearth_chatterbox* hearth_chatterbox_create(const char* root,int){return std::string(root)=="unicode-error"?nullptr:new hearth_chatterbox;}
void hearth_chatterbox_destroy(hearth_chatterbox* p){delete p;}
void hearth_chatterbox_cancel(hearth_chatterbox* p){if(p)p->stopped=true;}
const char* hearth_chatterbox_last_error(){return "خطأ محرك CHATTERBOX 🗣";}
int hearth_chatterbox_voice_count(const hearth_chatterbox*){return 1;}
const char* hearth_chatterbox_voice_name(const hearth_chatterbox*,int i){return "default";}
int hearth_chatterbox_synthesize(hearth_chatterbox* p,const char* text,const char*,const char*,hearth_chatterbox_pcm callback,void* opaque){
 if(p->stopped)return 1;float values[24001]={.25f,-.25f};
 if(std::string(text)=="bad-pcm"){values[0]=std::numeric_limits<float>::quiet_NaN();callback(values,1,24000,opaque);return 1;}
 if(std::string(text)=="threaded-close"){int keep=0;std::thread worker([&]{keep=callback(values,24001,24000,opaque);});worker.join();return keep?0:1;}const int status=callback(values,24001,24000,opaque)?0:1;return p->stopped?1:status;
}
}
