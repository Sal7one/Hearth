#include "oute_sampling.h"
#include <cassert>
#include <limits>
struct Score{int id;float score;};
int main(){
 std::vector<Score> values={{0,std::log(.70f)},{1,std::log(.20f)},{2,std::log(.08f)},{3,std::log(.02f)}};
 hearth::oute::filter_probabilities(values,.9f,.05f);
 assert(values.size()==2&&values[0].id==0&&values[1].id==1);
 assert(std::abs(values[0].score/values[1].score-3.5f)<.0001f);
 values={{0,1000},{1,996},{2,-INFINITY}};hearth::oute::filter_probabilities(values);assert(values.size()==1&&values[0].score==1);
 for(float invalid:{std::numeric_limits<float>::quiet_NaN(),INFINITY}){values={{0,invalid}};bool thrown=false;try{hearth::oute::filter_probabilities(values);}catch(const std::runtime_error&){thrown=true;}assert(thrown);}
 values={{0,-INFINITY}};bool thrown=false;try{hearth::oute::filter_probabilities(values);}catch(const std::runtime_error&){thrown=true;}assert(thrown);
}
