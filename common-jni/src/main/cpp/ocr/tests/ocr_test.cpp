#include "../ocr_geometry.h"
#include "../japanese_geometry.h"
#include "../comic_vision_geometry.h"
#include <cassert>
#include <iostream>
#include <limits>
#include <random>
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
 // Nearby disconnected strokes coalesce into one bounded model invocation.
 std::vector<uint8_t> mask(1200*800);
 for(int y=100;y<160;++y)for(int x:{100,140,300})mask[y*1200+x]=1;
 auto tiles=comic::inpaintTiles(mask,1200,800);
 assert(tiles.tiles.size()==1&&tiles.tiles[0].components.size()==3);
 assert(tiles.tiles[0].crop.left==0&&tiles.tiles[0].crop.top==0);
 assert(tiles.tiles[0].crop.right==429&&tiles.tiles[0].crop.bottom==288);
 // Distant text is not shrunk into a whole-page crop.
 mask[700*1200+1100]=1;tiles=comic::inpaintTiles(mask,1200,800);
 assert(tiles.tiles.size()==2);
 assert(tiles.tiles[1].crop.left==972&&tiles.tiles[1].crop.bottom==800);
 // Eight-connectivity, as upstream; diagonally touching strokes stay together.
 mask.assign(1200*800,0);mask[100*1200+100]=1;mask[101*1200+101]=1;
 tiles=comic::inpaintTiles(mask,1200,800);assert(tiles.tiles.size()==1&&tiles.tiles[0].components.size()==1);
 // Split oversized components without overlapping ownership or masked seams.
 mask.assign(1200*800,0);for(int x=50;x<1150;++x)mask[400*1200+x]=1;
 tiles=comic::inpaintTiles(mask,1200,800);assert(tiles.tiles.size()==3);
 std::vector<int> owners(mask.size());
 for(const auto& tile:tiles.tiles){
   assert(tile.core.right-tile.core.left<=512&&tile.core.bottom-tile.core.top<=512);
   assert(tile.crop.right-tile.crop.left<=768&&tile.crop.bottom-tile.crop.top<=768);
   for(int y=tile.core.top;y<tile.core.bottom;++y)for(int x=tile.core.left;x<tile.core.right;++x)
     if(std::binary_search(tile.components.begin(),tile.components.end(),tiles.labels[y*1200+x]))++owners[y*1200+x];
 }
 for(size_t i=0;i<mask.size();++i)assert(owners[i]==(mask[i]?1:0));
 assert(tiles.tiles[0].crop.right>tiles.tiles[0].core.right&&tiles.tiles[1].crop.left<tiles.tiles[1].core.left);
 failed=false;try{comic::inpaintTiles(mask,1199,800);}catch(const std::invalid_argument&){failed=true;}assert(failed);
 std::vector<uint8_t> emptyMask(16*16);assert(comic::inpaintTiles(emptyMask,16,16).tiles.empty());
 std::vector<uint8_t> fragmented(256*256);for(int y=0;y<256;y+=3)for(int x=0;x<256;x+=3)fragmented[y*256+x]=1;
 failed=false;try{comic::inpaintTiles(fragmented,256,256);}catch(const std::runtime_error&){failed=true;}assert(failed);
 // Positive-support projection must equal the full-page bilinear reference,
 // including masks extending outside detection boxes and edge singletons.
 std::mt19937 generator(73);std::uniform_real_distribution<float> logit(-5.f,1.f);
 for(int side:{1,3,9})for(int width:{1,5,20})for(int height:{1,4,23}) {
   std::vector<float> source(side*side);for(auto& v:source)v=logit(generator);source.back()=3.f;
   const auto projected=comic::MaskProjection(width,height,side).project(source.data(),10000);
   std::vector<uint8_t> actual(width*height),expected(width*height);
   for(size_t i=0;i<projected.size();i+=3)for(int x=projected[i+1];x<projected[i+1]+projected[i+2];++x)actual[projected[i]*width+x]=1;
   for(int y=0;y<height;++y)for(int x=0;x<width;++x) {
     const float fx=std::clamp((x+.5f)*side/width-.5f,0.f,float(side-1)),fy=std::clamp((y+.5f)*side/height-.5f,0.f,float(side-1));
     const int x0=int(fx),y0=int(fy),x1=std::min(x0+1,side-1),y1=std::min(y0+1,side-1);const float dx=fx-x0,dy=fy-y0;
     expected[y*width+x]=((source[y0*side+x0]*(1-dx)+source[y0*side+x1]*dx)*(1-dy)+(source[y1*side+x0]*(1-dx)+source[y1*side+x1]*dx)*dy)>0;
   }
   assert(actual==expected);
 }
 float negative[]={-1,-1,-1,-1};assert(comic::MaskProjection(20,20,2).project(negative,1).empty());
 negative[0]=1;failed=false;try{comic::MaskProjection(20,20,2).project(negative,0);}catch(const std::runtime_error&){failed=true;}assert(failed);
 negative[0]=std::numeric_limits<float>::quiet_NaN();failed=false;try{comic::MaskProjection(20,20,2).project(negative,1);}catch(const std::runtime_error&){failed=true;}assert(failed);
 std::cout<<"OCR geometry/CTC/character NMS and Koharu cleanup coalescing/ownership: PASS\n";
}
