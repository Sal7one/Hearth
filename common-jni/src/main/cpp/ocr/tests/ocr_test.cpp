#include "../ocr_geometry.h"
#include "../japanese_geometry.h"
#include <cassert>
#include <iostream>
#include <limits>
using namespace hearth::ocr;
int main(){
 std::vector<float> map(100*40);assert(regions(map.data(),100,40).empty());
 for(int y=10;y<20;++y)for(int x=20;x<70;++x)map[y*100+x]=.9f;
 auto boxes=regions(map.data(),100,40);assert(boxes.size()==1);assert(boxes[0].x<20&&boxes[0].y<10);assert(boxes[0].x+boxes[0].width>70);assert(boxes[0].y+boxes[0].height<=40);
 map.assign(100*40,0);map[0]=1;assert(regions(map.data(),100,40).empty());map[0]=std::numeric_limits<float>::quiet_NaN();assert(regions(map.data(),100,40).empty());
 float scores[]={.1f,.9f,0,.1f,.9f,0,.9f,.1f,0,.1f,.8f,.1f,.1f,.1f,.8f};auto text=ctc(scores,5,3);assert((text.tokens==std::vector<int>{1,1,2}));assert(text.confidence>.8f);
 bool failed=false;try{ctc(scores,5,1);}catch(const std::invalid_argument&){failed=true;}assert(failed);
 auto chars=characters({{12354,0,10,.9f},{12356,1,9,.8f},{12358,12,20,.7f}});
 assert(chars.size()==2);assert(chars[0].code==12354&&chars[1].code==12358);
 failed=false;try{characters({{0xd800,0,10,.9f}});}catch(const std::runtime_error&){failed=true;}assert(failed);
 failed=false;try{characters({{12354,10,10,.9f}});}catch(const std::runtime_error&){failed=true;}assert(failed);
 std::cout<<"OCR geometry/CTC/character NMS: 14 checks PASS\n";
}
