#include "chatterbox.h"
#include <cmath>
#include <cstdio>
#include <stdexcept>
struct Result{size_t samples=0,chunks=0;bool abort=false;};
int pcm(const float* samples,size_t count,int rate,void* opaque){auto& out=*static_cast<Result*>(opaque);if(rate!=24000||!count||count>24000)throw std::runtime_error("PCM bound");for(size_t i=0;i<count;++i)if(!std::isfinite(samples[i]))throw std::runtime_error("PCM finite");out.samples+=count;++out.chunks;return out.abort?0:1;}
int main(int argc,char** argv){if(argc!=2)return 2;auto* voice=hearth_chatterbox_create(argv[1],2);if(!voice)throw std::runtime_error(hearth_chatterbox_last_error());for(int i=0;i<3;++i){Result result{};result.abort=i==2;int status=hearth_chatterbox_synthesize(voice,i==0?"مرحبا بكم.":"Hello from Hearth.",i==0?"ar":"en","default",pcm,&result);std::printf("CHATTERBOX SMOKE language=%s status=%d samples=%zu chunks=%zu\n",i==0?"ar":"en",status,result.samples,result.chunks);std::fflush(stdout);if(i==2?status!=1:status!=0||result.samples==0)throw std::runtime_error(hearth_chatterbox_last_error());}hearth_chatterbox_destroy(voice);hearth_chatterbox_destroy(nullptr);std::puts("CHATTERBOX REAL CABI SMOKE PASS");}
