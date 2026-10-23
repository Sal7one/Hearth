#ifdef NDEBUG
#undef NDEBUG
#endif
#include "wav_output.h"
#include <cassert>
#include <cstring>
int main() {
    FILE* file=std::tmpfile();assert(file);
    { WavOutput wav(file);uint8_t pcm[]={0,0,1,0};wav.write(pcm,4);wav.finish();
      std::rewind(file);char bytes[48]={};assert(std::fread(bytes,1,48,file)==48);
      assert(std::memcmp(bytes,"RIFF",4)==0 && std::memcmp(bytes+8,"WAVEfmt ",8)==0);
      assert(static_cast<unsigned char>(bytes[4])==40 && static_cast<unsigned char>(bytes[40])==4);
      bool odd=false;try{wav.write(pcm,3);}catch(const std::runtime_error&){odd=true;}assert(odd);
      bool limit=false;try{wav.write(pcm,16000ULL*2*60*60+2);}catch(const std::runtime_error&){limit=true;}assert(limit);
    }
    FILE* empty=std::tmpfile();assert(empty);{WavOutput wav(empty);bool failed=false;try{wav.finish();}catch(const std::runtime_error&){failed=true;}assert(failed);}
    return 0;
}
