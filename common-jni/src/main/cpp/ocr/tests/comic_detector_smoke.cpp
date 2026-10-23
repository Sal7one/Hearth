// Opt-in real ONNX inference, no bundled weights or third-party image fixture.
#include "../comic_detector.h"
#include <chrono>
#include <fstream>
#include <iostream>

int main(int argc,char** argv) {
    try {
        if(argc!=3) throw std::invalid_argument("Usage: comic-detector-smoke pinned-detector.onnx page.ppm");
        std::ifstream file(argv[2],std::ios::binary);
        std::string magic; int width=0,height=0,maximum=0;
        file>>magic>>width>>height>>maximum;file.get();
        if(magic!="P6" || width<1 || height<1 || width>2048 || height>2048 || maximum!=255)
            throw std::runtime_error("Fixture must be an upright RGB P6 image, <=2048 per side");
        std::vector<uint32_t> pixels(size_t(width)*height);
        for(auto& pixel:pixels) {
            unsigned char rgb[3];file.read(reinterpret_cast<char*>(rgb),3);
            if(!file) throw std::runtime_error("Truncated fixture");
            pixel=0xff000000u|(uint32_t(rgb[0])<<16)|(uint32_t(rgb[1])<<8)|rgb[2];
        }
        hearth::ocr::ComicDetector detector(argv[1]);
        auto begin=std::chrono::steady_clock::now();
        auto results=detector.detect(pixels.data(),width,height);
        auto elapsed=std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-begin).count();
        std::cout<<"page "<<width<<" "<<height<<" detection_ms "<<elapsed<<"\n";
        for(const auto& result:results) std::cout<<result.label<<" "<<result.box.x<<" "<<result.box.y<<" "<<result.box.width<<" "<<result.box.height<<" "<<result.box.score<<"\n";
        std::vector<uint32_t> blank(640*640,0xffffffffu);
        auto empty=detector.detect(blank.data(),640,640);
        for(const auto& result:empty) if(result.label!=0) throw std::runtime_error("Blank page produced a text region");
        detector.cancel();bool cancelled=false;
        try {detector.detect(blank.data(),640,640);} catch(const std::runtime_error&) {cancelled=true;}
        if(!cancelled) throw std::runtime_error("Detector ignored cancellation");
        std::cout<<"blank_and_cancellation PASS\n";
        return 0;
    } catch(const std::exception& error) {std::cerr<<error.what()<<"\n";return 1;}
}
