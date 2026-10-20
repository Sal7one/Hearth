#include "../voice_bounds.h"
#include <cassert>
#include <iostream>
#include <limits>
using namespace hearth::voice;
template<class F> void rejects(F f){bool rejected=false;try {f();}catch(const std::exception&){rejected=true;}assert(rejected);}
int main(){
    std::vector<int64_t> ids={0,123,65535};std::vector<float> ttl(12800,.1f),dp(128,.2f);
    validateInput(ids,ttl,dp,5,1);
    for(int steps:{0,17,-1})rejects([&]{validateInput(ids,ttl,dp,steps,1);});
    for(float speed:{0.f,.49f,2.01f,std::numeric_limits<float>::infinity(),std::numeric_limits<float>::quiet_NaN()})rejects([&]{validateInput(ids,ttl,dp,5,speed);});
    rejects([&]{validateInput({},ttl,dp,5,1);});rejects([&]{validateInput(std::vector<int64_t>(1025),ttl,dp,5,1);});
    rejects([&]{validateInput({-1},ttl,dp,5,1);});rejects([&]{validateInput({65536},ttl,dp,5,1);});
    rejects([&]{validateInput(ids,{},dp,5,1);});rejects([&]{validateInput(ids,ttl,{},5,1);});
    ttl[100]=std::numeric_limits<float>::quiet_NaN();rejects([&]{validateInput(ids,ttl,dp,5,1);});
    for(float duration:{0.f,-1.f,30.01f,std::numeric_limits<float>::infinity(),std::numeric_limits<float>::quiet_NaN()})rejects([&]{plan(duration,1);});
    rejects([&]{plan(20,.5f);});rejects([&]{plan(1,0);});
    auto p=plan(1,1);assert(p.samples==44100);assert(p.latentLength==15);
    auto fast=plan(1,2);assert(fast.samples==22050);assert(fast.latentLength==8);
    auto slow=plan(1,.5);assert(slow.samples==88200);assert(slow.latentLength==29);
    std::cout<<"Voice bounds PASS (invalid tokens/styles/speed/steps/duration and chunk geometry)\n";
}
