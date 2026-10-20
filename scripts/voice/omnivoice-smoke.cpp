#include "../../hearth-tts-omnivoice/src/main/cpp/vendor/omnivoice.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <stdexcept>
#include <string>
struct Output { size_t samples=0; size_t chunks=0; bool abort=false; };
bool pcm(const float* samples,int size,void* opaque) {
    auto& out=*static_cast<Output*>(opaque);
    if(!samples || size<1 || size>4*1024*1024 || !std::all_of(samples,samples+size,[](float x){return std::isfinite(x);}))throw std::runtime_error("Invalid output PCM");
    out.samples+=size;++out.chunks;return !out.abort;
}
int main(int argc,char** argv) {
    if(argc!=3)return 2;
    bool ar=false,en=false;for(int i=0;i<ov_n_languages();++i){const std::string lang=ov_language_id(i);ar|=lang=="arb";en|=lang=="en";}
    if(!ar || !en)throw std::runtime_error("Missing languages");
    ov_init_params init{};ov_init_default_params(&init);init.model_path=argv[1];init.codec_path=argv[2];init.device="CPU";init.use_fa=false;
    ov_context* context=ov_init(&init);if(!context)throw std::runtime_error(ov_last_error());
    for(int i=0;i<3;++i){
        Output output{};output.abort=i==2;
        ov_tts_params p{};ov_tts_default_params(&p);p.text=i==0?"مرحبا بكم.":"Hello from Hearth.";p.lang=i==0?"arb":"en";p.chunk_duration_sec=5;p.chunk_threshold_sec=5;p.mg_num_step=8;
        p.on_chunk=pcm;p.on_chunk_user_data=&output;p.instruct=i==1?"female":nullptr;
        ov_audio audio{};auto status=ov_synthesize(context,&p,&audio);ov_audio_free(&audio);
        std::printf("SMOKE language=%s design=%s status=%d samples=%zu chunks=%zu\n",p.lang,p.instruct?p.instruct:"auto",status,output.samples,output.chunks);std::fflush(stdout);
        if(i==2?status!=OV_STATUS_CANCELLED:status!=OV_STATUS_OK || output.samples==0)throw std::runtime_error(ov_last_error());
    }
    ov_free(context);ov_free(nullptr);std::puts("OMNIVOICE REAL MODEL SMOKE PASS");
}
