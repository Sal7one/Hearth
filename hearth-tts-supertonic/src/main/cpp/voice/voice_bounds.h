#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <stdexcept>
#include <vector>
namespace hearth::voice {
inline void validateInput(const std::vector<int64_t>& ids,const std::vector<float>& ttl,const std::vector<float>& dp,int steps,float speed) {
    if(ids.empty()||ids.size()>1024||ttl.size()!=12800||dp.size()!=128||steps<1||steps>16||!std::isfinite(speed)||speed<.5f||speed>2.f)
        throw std::invalid_argument("Invalid Supertonic input, voice shape, steps or speed");
    if(!std::all_of(ids.begin(),ids.end(),[](int64_t v){return v>=0&&v<65536;}) ||
       !std::all_of(ttl.begin(),ttl.end(),[](float v){return std::isfinite(v);}) ||
       !std::all_of(dp.begin(),dp.end(),[](float v){return std::isfinite(v);}))throw std::invalid_argument("Invalid voice tensor values");
}
struct VoicePlan {int samples;int64_t latentLength;};
inline VoicePlan plan(float predictedSeconds,float speed) {
    if(!std::isfinite(speed)||speed<.5f||speed>2.f)throw std::invalid_argument("Invalid voice speed");
    const float seconds=predictedSeconds/speed;
    if(!std::isfinite(seconds)||seconds<=0||seconds>30)throw std::runtime_error("Predicted speech duration exceeds 30 seconds; use shorter text segments");
    const int samples=int(seconds*44100);
    if(samples<1)throw std::runtime_error("Predicted voice has no audio samples");
    return {samples,int64_t(std::ceil(seconds*44100/(512*6)))};
}
}
