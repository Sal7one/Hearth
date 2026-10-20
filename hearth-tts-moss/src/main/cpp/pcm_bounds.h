#pragma once
#include <cmath>
#include <cstddef>
#include <stdexcept>
#include <vector>
namespace hearth::moss {
inline std::vector<float> monoAverage(const float* left,const float* right,size_t count){
 if(!left||!right||count==0||count>48000)throw std::runtime_error("MOSS stereo PCM exceeds chunk bound");
 std::vector<float> result(count);
 for(size_t i=0;i<count;++i){if(!std::isfinite(left[i])||!std::isfinite(right[i]))throw std::runtime_error("MOSS returned non-finite stereo PCM");result[i]=left[i]*.5f+right[i]*.5f;}
 return result;
}
}
