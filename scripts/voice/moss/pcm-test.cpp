#include "pcm_bounds.h"
#include <cassert>
#include <limits>
#include <iostream>
template<class F>void rejects(F f){bool rejected=false;try{f();}catch(const std::exception&){rejected=true;}assert(rejected);}
int main(){float left[]={1.f,-1.f,.5f},right[]={-1.f,1.f,.25f};auto mono=hearth::moss::monoAverage(left,right,3);assert(mono.size()==3);assert(mono[0]==0.f&&mono[1]==0.f&&mono[2]==.375f);float same[]={1.f};assert(hearth::moss::monoAverage(same,same,1)[0]==1.f);rejects([&]{hearth::moss::monoAverage(left,right,0);});rejects([&]{hearth::moss::monoAverage(left,right,48001);});rejects([&]{hearth::moss::monoAverage(nullptr,right,1);});float bad[]={std::numeric_limits<float>::quiet_NaN()};rejects([&]{hearth::moss::monoAverage(bad,right,1);});std::cout<<"MOSS stereo downmix bounds PASS\n";}
