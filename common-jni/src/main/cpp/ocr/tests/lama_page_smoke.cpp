// Copyright 2026 Hearth contributors. SPDX-License-Identifier: Apache-2.0
// Opt-in real LaMa inference on an entirely synthetic nine-region page.
// No model download, device access, or third-party image fixture is required.
// Usage: lama-page-smoke pinned-lama.onnx [separate|union|both]
// The model is loaded outside each measurement. "separate" performs nine calls;
// "union" submits the same mask once so Koharu's coalesced tiles can be shared.
#include "../comic_detector.h"
#include <algorithm>
#include <chrono>
#include <iostream>
#include <string>

namespace {
constexpr int width=770,height=1080;
struct Fixture {
    std::vector<uint32_t> pixels;
    std::vector<uint8_t> mask;
    std::vector<std::vector<int32_t>> regions;
    std::vector<int32_t> combined;
};
Fixture fixture() {
    Fixture result;result.pixels.resize(width*height);result.mask.resize(width*height);
    // Textured context prevents this fixture from exercising only flat fills.
    for(int y=0;y<height;++y)for(int x=0;x<width;++x)
        result.pixels[y*width+x]=0xff000000u|(uint32_t(160+x%50)<<16)|(uint32_t(170+y%50)<<8)|128;
    for(int y:{100,400,700})for(int x:{100,330,560}) {
        std::vector<int32_t> spans;
        for(int dy=0;dy<50;++dy)for(int dx:{0,12,24}) {
            spans.insert(spans.end(),{y+dy,x+dx,4});
            for(int offset=0;offset<4;++offset) {
                const int at=(y+dy)*width+x+dx+offset;
                result.pixels[at]=0xff000000u;result.mask[at]=1;
            }
        }
        result.regions.push_back(std::move(spans));
    }
    // Canonical ordered, non-overlapping RLE is the public native mask contract.
    for(int y=0;y<height;++y) {
        int start=-1;
        for(int x=0;x<=width;++x) {
            const bool on=x<width&&result.mask[y*width+x];
            if(on&&start<0)start=x;
            if(!on&&start>=0) {result.combined.insert(result.combined.end(),{y,start,x-start});start=-1;}
        }
    }
    return result;
}
void measure(const std::string& path,const Fixture& source,bool combined) {
    hearth::ocr::ComicDetector model(path,2);
    std::vector<uint32_t> output=source.pixels;
    size_t covered=0;
    const auto start=std::chrono::steady_clock::now();
    auto apply=[&](const std::vector<int32_t>& spans) {
        const auto patch=model.inpaint(source.pixels.data(),width,height,spans);
        if(patch.size()<4)throw std::runtime_error("Cleanup returned a truncated patch");
        const int left=patch[0],top=patch[1],pw=patch[2],ph=patch[3];
        if(left<0||top<0||pw<1||ph<1||int64_t(left)+pw>width||int64_t(top)+ph>height||
           patch.size()!=4+size_t(pw)*ph)throw std::runtime_error("Cleanup returned invalid patch dimensions");
        std::vector<uint8_t> requested(width*height);
        size_t expected=0,actual=0;
        for(size_t i=0;i<spans.size();i+=3)for(int x=spans[i+1];x<spans[i+1]+spans[i+2];++x) {
            requested[spans[i]*width+x]=1;++expected;
        }
        for(int y=0;y<ph;++y)for(int x=0;x<pw;++x) {
            const auto pixel=uint32_t(patch[4+y*pw+x]);const int at=(y+top)*width+x+left;
            if(requested[at]) {
                if((pixel>>24)!=255)throw std::runtime_error("Cleanup omitted a requested pixel or returned partial alpha");
                output[at]=pixel;++actual;
            } else if(pixel!=0)throw std::runtime_error("Cleanup published a pixel outside this call's requested mask");
        }
        if(actual!=expected)throw std::runtime_error("Cleanup patch does not cover every requested pixel");
        covered+=actual;
    };
    if(combined)apply(source.combined);else for(const auto& region:source.regions)apply(region);
    const auto elapsed=std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-start).count();
    size_t requested=0,changed=0;
    for(size_t i=0;i<source.pixels.size();++i) {
        if(source.mask[i]) {++requested;if(output[i]!=source.pixels[i])++changed;}
        else if(output[i]!=source.pixels[i])throw std::runtime_error("Cleanup changed an unmasked source pixel");
    }
    if(requested!=5400||covered!=requested||changed<requested/2)
        throw std::runtime_error("LaMa did not reconstruct the synthetic nine-region text mask");
    std::cout<<"LAMA_PAGE mode="<<(combined?"union":"separate")<<" elapsed_ms="<<elapsed
             <<" requested="<<requested<<" covered="<<covered<<" changed="<<changed
             <<" alpha_coverage=PASS unchanged_outside_mask=PASS\n";
}
}
int main(int argc,char** argv) { try {
    if(argc<2||argc>3)throw std::invalid_argument("Usage: lama-page-smoke pinned-lama.onnx [separate|union|both]");
    const std::string mode=argc==3?argv[2]:"both";
    if(mode!="separate"&&mode!="union"&&mode!="both")throw std::invalid_argument("Unknown benchmark mode");
    const auto source=fixture();
    if(mode!="union")measure(argv[1],source,false);
    if(mode!="separate")measure(argv[1],source,true);
    return 0;
}catch(const std::exception& error) {std::cerr<<error.what()<<"\n";return 1;} }
