#pragma once
#include <algorithm>
#include <cmath>
#include <stdexcept>
#include <vector>
namespace hearth::oute {
// Input is already temperature/penalty/top-k filtered by the pinned upstream sampler.
// Preserve relative probability weights: applying softmax twice flattens speech tokens.
template<class Score> void filter_probabilities(std::vector<Score>& values,float top_p=.9f,float min_p=.05f) {
 if(values.empty())throw std::runtime_error("Oute sampler has no token candidates");
 for(const auto& value:values)if(std::isnan(value.score)||value.score==INFINITY)throw std::runtime_error("Oute sampler received invalid logits");
 std::sort(values.begin(),values.end(),[](const auto& a,const auto& b){return a.score>b.score;});
 const float maximum=values.front().score;
 if(!std::isfinite(maximum))throw std::runtime_error("Oute sampler has no finite token candidate");
 const float floor=min_p;float total=0;
 for(auto& value:values){value.score=std::exp(value.score-maximum);if(value.score>=floor)total+=value.score;}
 float cumulative=0;size_t keep=0;
 for(const auto& value:values){if(value.score<floor)break;cumulative+=value.score/total;++keep;if(cumulative>=top_p)break;}
 values.resize(std::max(size_t(1),keep));
}
}
