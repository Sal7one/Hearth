// Opt-in real pinned-weight cleanup; synthetic page and generated evidence stay outside Git.
#include "../comic_detector.h"
#include <chrono>
#include <fstream>
#include <iostream>
int main(int argc,char**argv){try{
    if(argc!=3)throw std::invalid_argument("Usage: lama-smoke pinned-lama.onnx output.ppm");
    constexpr int width=256,height=192;std::vector<uint32_t> source(width*height,0xfff8f0e0);std::vector<int32_t> spans;
    for(int y=0;y<height;++y)for(int x=0;x<width;++x)source[y*width+x]=0xff000000u|((180+x/4)<<16)|((160+y/4)<<8)|128;
    // Three glyph-like strokes on a gradient force a genuine neural-cleanup path.
    for(int y=60;y<132;++y)for(int x:{95,115,135}){spans.insert(spans.end(),{y,x,5});for(int dx=0;dx<5;++dx)source[y*width+x+dx]=0xff000000;}
    hearth::ocr::ComicDetector model(argv[1],2);auto start=std::chrono::steady_clock::now();auto patch=model.inpaint(source.data(),width,height,spans);
    if(patch.size()!=size_t(patch[2])*patch[3]+4)throw std::runtime_error("Invalid cleanup patch dimensions");
    auto result=source;int filled=0,changed=0;for(int y=0;y<patch[3];++y)for(int x=0;x<patch[2];++x){const auto pixel=uint32_t(patch[4+y*patch[2]+x]);
        if(pixel>>24){const int at=(y+patch[1])*width+x+patch[0];filled++;if(result[at]!=pixel)changed++;result[at]=pixel;}}
    if(filled!=72*3*5 || changed<filled/2)throw std::runtime_error("LaMa did not reconstruct its requested mask");
    for(int y=0;y<height;++y)for(int x=0;x<width;++x){const bool masked=y>=60&&y<132&&((x>=95&&x<100)||(x>=115&&x<120)||(x>=135&&x<140));
        if(!masked && result[y*width+x]!=source[y*width+x])throw std::runtime_error("Cleanup changed an unmasked pixel");}
    std::ofstream file(argv[2],std::ios::binary);file<<"P6\n"<<width<<" "<<height<<"\n255\n";
    for(auto p:result){const unsigned char rgb[]={static_cast<unsigned char>(p>>16),static_cast<unsigned char>(p>>8),static_cast<unsigned char>(p)};file.write(reinterpret_cast<const char*>(rgb),3);}
    model.cancel();bool cancelled=false;try{model.inpaint(source.data(),width,height,spans);}catch(const std::runtime_error&){cancelled=true;}if(!cancelled)throw std::runtime_error("LaMa ignored cancellation");
    std::cout<<"LAMA_REAL elapsed_ms="<<std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-start).count()<<" filled="<<filled<<" changed="<<changed<<" unchanged_outside_mask=PASS cancellation=PASS\n";
    return 0;
}catch(const std::exception&error){std::cerr<<error.what()<<"\n";return 1;}}
