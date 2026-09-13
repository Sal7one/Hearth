#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <stdexcept>
#include <vector>

namespace hearth::ocr {
struct Box { int x, y, width, height; float score; };
// DB probability-map components, expanded using area/perimeter unclip distance.
// Horizontal text-line baseline: camera rotation is handled before this operation.
inline std::vector<Box> regions(const float* map, int w, int h) {
    if (!map || w < 1 || h < 1 || w > 2048 || h > 2048) throw std::invalid_argument("Invalid OCR detector map");
    std::vector<uint8_t> seen(static_cast<size_t>(w)*h);
    std::vector<int> queue; std::vector<Box> out;
    for (int seed=0; seed<w*h; ++seed) {
        if (seen[seed] || !std::isfinite(map[seed]) || map[seed] < .3f) continue;
        queue.clear(); queue.push_back(seed); seen[seed]=1;
        int left=w, top=h, right=0, bottom=0; double score=0;
        for (size_t j=0;j<queue.size();++j) {
            int p=queue[j], x=p%w, y=p/w;
            left=std::min(left,x); right=std::max(right,x); top=std::min(top,y); bottom=std::max(bottom,y); score+=map[p];
            const int dx[]={-1,1,0,0}, dy[]={0,0,-1,1};
            for (int k=0;k<4;++k) {
                int a=x+dx[k], b=y+dy[k]; if(a<0||b<0||a>=w||b>=h) continue;
                int n=b*w+a;
                if (!seen[n] && std::isfinite(map[n]) && map[n]>=.3f) { seen[n]=1;queue.push_back(n); }
            }
        }
        const int bw=right-left+1,bh=bottom-top+1;
        if (queue.size()<6 || bw<3 || bh<3 || score/queue.size()<.6) continue;
        int pad=std::max(2,static_cast<int>(1.5*bw*bh/(2.*(bw+bh))));
        int x=std::max(0,left-pad),y=std::max(0,top-pad);
        out.push_back({x,y,std::min(w,right+pad+1)-x,std::min(h,bottom+pad+1)-y,static_cast<float>(score/queue.size())});
    }
    std::sort(out.begin(),out.end(),[](const Box&a,const Box&b){return a.y==b.y?a.x<b.x:a.y<b.y;});
    if(out.size()>64) out.resize(64);
    return out;
}
struct Decoded { std::vector<int> tokens; float confidence=0; };
inline Decoded ctc(const float* scores,int steps,int classes) {
    if(!scores||steps<1||steps>8192||classes<2||classes>30000 || static_cast<int64_t>(steps)*classes>50000000)
        throw std::invalid_argument("Invalid OCR recognizer output");
    Decoded out; int previous=-1;double sum=0;
    for(int t=0;t<steps;++t) {
        int best=0; float confidence=-1;
        for(int c=0;c<classes;++c) {float v=scores[static_cast<size_t>(t)*classes+c];if(!std::isfinite(v))throw std::runtime_error("Non-finite OCR recognition score");if(v>confidence){best=c;confidence=v;}}
        if(best!=0&&best!=previous){out.tokens.push_back(best);sum+=confidence;}
        previous=best;
    }
    if(!out.tokens.empty())out.confidence=static_cast<float>(sum/out.tokens.size());
    return out;
}
}
