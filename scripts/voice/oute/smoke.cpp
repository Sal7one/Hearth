#include "hearth_oute.h"
#include <chrono>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>
#include <thread>
using Clock=std::chrono::steady_clock;
struct Audio{std::vector<int16_t> pcm;int chunks=0;};
int receive(void* opaque,const float* samples,int n,int rate){
 auto &out=*static_cast<Audio*>(opaque);if(rate!=24000||n<=0||n>rate)return 0;
 ++out.chunks;for(int i=0;i<n;++i){if(!std::isfinite(samples[i])||std::abs(samples[i])>1)return 0;out.pcm.push_back(int16_t(samples[i]*32767));}return 1;
}
void number(std::ofstream& f,uint32_t value,int bytes){for(int i=0;i<bytes;++i)f.put(char((value>>(i*8))&255));}
int main(int argc,char**argv){
 if(argc<5)return 2;auto start=Clock::now();auto* ctx=hearth_oute_create(argv[1],argv[2],2);
 if(!ctx){std::cerr<<hearth_oute_last_error()<<"\n";return 3;}
 auto loaded=Clock::now();Audio output;
 const int result=hearth_oute_synthesize(ctx,argv[3],receive,&output);auto end=Clock::now();
 if(result!=0){std::cerr<<hearth_oute_last_error()<<" status="<<result<<"\n";hearth_oute_destroy(ctx);return 4;}
 double power=0;for(auto v:output.pcm)power+=double(v)*v/(32768.0*32768.0);
 if(output.pcm.size()<2400||power<=0){hearth_oute_destroy(ctx);return 5;}
 std::ofstream file(argv[4],std::ios::binary);file.write("RIFF",4);number(file,36+output.pcm.size()*2,4);file.write("WAVEfmt ",8);number(file,16,4);number(file,1,2);number(file,1,2);number(file,24000,4);number(file,48000,4);number(file,2,2);number(file,16,2);file.write("data",4);number(file,output.pcm.size()*2,4);file.write(reinterpret_cast<const char*>(output.pcm.data()),output.pcm.size()*2);
 std::cout<<"{\"abi\":"<<hearth_oute_abi()<<",\"rate\":24000,\"frames\":"<<output.pcm.size()<<",\"chunks\":"<<output.chunks<<",\"rms\":"<<std::sqrt(power/output.pcm.size())<<",\"loadMs\":"<<std::chrono::duration_cast<std::chrono::milliseconds>(loaded-start).count()<<",\"synthesisMs\":"<<std::chrono::duration_cast<std::chrono::milliseconds>(end-loaded).count()<<"}\n";
 hearth_oute_destroy(ctx);return 0;
}
