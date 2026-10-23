#pragma once
#include <algorithm>
#include <cstdint>
#include <cmath>
#include <limits>
#include <stdexcept>
#include <vector>

namespace hearth::ocr::comic {
struct Interpolation { int first,second; float fraction; };
// RF-DETR's exact align_corners=false bilinear projection. A convex bilinear
// sample cannot be positive when all contributing source logits are <=0.
// Bounding the positive support therefore skips background work without
// clipping masks to their (independent) predicted detection boxes.
class MaskProjection {
    int width,height,side;
    std::vector<Interpolation> columns,rows;
    static std::vector<Interpolation> axis(int extent,int side) {
        std::vector<Interpolation> result;result.reserve(extent);
        for(int i=0;i<extent;++i) {
            const float position=std::clamp((i+.5f)*side/extent-.5f,0.f,float(side-1));
            const int first=int(position);result.push_back({first,std::min(first+1,side-1),position-first});
        }
        return result;
    }
public:
    MaskProjection(int width,int height,int side):width(width),height(height),side(side) {
        if(width<1 || height<1 || width>2048 || height>2048 || side<1 || side>1152)
            throw std::invalid_argument("Invalid instance mask dimensions");
        columns=axis(width,side);rows=axis(height,side);
    }
    std::vector<int32_t> project(const float* source,size_t remainingSpans) const {
        if(!source)throw std::invalid_argument("Missing instance mask");
        int left=side,top=side,right=-1,bottom=-1;
        for(int y=0;y<side;++y)for(int x=0;x<side;++x) {
            const float value=source[y*side+x];
            if(!std::isfinite(value))throw std::runtime_error("Non-finite Koharu mask");
            if(value>0) { left=std::min(left,x);top=std::min(top,y);right=std::max(right,x);bottom=std::max(bottom,y); }
        }
        std::vector<int32_t> spans;
        if(right<left || bottom<top)return spans;
        int firstX=0,lastX=width;
        while(firstX<lastX && columns[firstX].second<left)++firstX;
        while(lastX>firstX && columns[lastX-1].first>right)--lastX;
        for(int y=0;y<height;++y) {
            const auto row=rows[y];if(row.second<top || row.first>bottom)continue;
            const float* upper=source+row.first*side;const float* lower=source+row.second*side;
            int start=-1;
            for(int x=firstX;x<=lastX;++x) {
                bool foreground=false;
                if(x<lastX) {
                    const auto column=columns[x];const float dx=column.fraction,dy=row.fraction;
                    foreground=((upper[column.first]*(1-dx)+upper[column.second]*dx)*(1-dy)+
                                (lower[column.first]*(1-dx)+lower[column.second]*dx)*dy)>0;
                }
                if(foreground && start<0)start=x;
                if(!foreground && start>=0) {
                    if(spans.size()/3>=remainingSpans)throw std::runtime_error("Koharu mask complexity exceeds its safe page budget. Select a smaller area.");
                    spans.insert(spans.end(),{y,start,x-start});start=-1;
                }
            }
        }
        return spans;
    }
};
// Ported from Koharu 73abcaf, koharu-pipeline/stages/inpainting.rs (MIT).
// Keep context masks separate from tile ownership: neighboring glyphs must not
// become input pixels for the model to reconstruct, or get composited twice.
struct Bounds { int left,top,right,bottom; };
struct Tile { std::vector<int32_t> components; Bounds core,crop; };
struct Tiling { std::vector<int32_t> labels; std::vector<Tile> tiles; };
inline Bounds unite(Bounds a,Bounds b) {
    return {std::min(a.left,b.left),std::min(a.top,b.top),std::max(a.right,b.right),std::max(a.bottom,b.bottom)};
}
inline int64_t area(Bounds b) { return int64_t(b.right-b.left)*(b.bottom-b.top); }
inline Bounds context(Bounds b,int width,int height) {
    constexpr int margin=128;
    return {std::max(0,b.left-margin),std::max(0,b.top-margin),std::min(width,b.right+margin),std::min(height,b.bottom+margin)};
}
inline Tiling inpaintTiles(const std::vector<uint8_t>& mask,int width,int height) {
    if(width<1 || height<1 || width>2048 || height>2048 || mask.size()!=size_t(width)*height)
        throw std::invalid_argument("Invalid cleanup mask dimensions");
    Tiling result;result.labels.resize(mask.size());
    struct Component { int32_t label; Bounds bounds; };
    std::vector<Component> components;
    std::vector<int32_t> queue;
    for(int y=0;y<height;++y)for(int x=0;x<width;++x) {
        const int at=y*width+x;
        if(!mask[at] || result.labels[at])continue;
        if(components.size()>=4096)throw std::runtime_error("Cleanup mask exceeds 4096 components. Select a smaller area.");
        const int32_t label=int32_t(components.size()+1);
        Bounds bounds{x,y,x+1,y+1};queue.clear();queue.push_back(at);result.labels[at]=label;
        for(size_t i=0;i<queue.size();++i) {
            const int px=queue[i]%width,py=queue[i]/width;
            bounds=unite(bounds,{px,py,px+1,py+1});
            for(int ny=std::max(0,py-1);ny<=std::min(height-1,py+1);++ny)
                for(int nx=std::max(0,px-1);nx<=std::min(width-1,px+1);++nx) {
                    const int neighbor=ny*width+nx;
                    if(mask[neighbor] && !result.labels[neighbor]) { result.labels[neighbor]=label;queue.push_back(neighbor); }
                }
        }
        components.push_back({label,bounds});
    }
    constexpr int side=512;
    std::vector<Tile> split;
    for(const auto& component:components) {
        const Bounds bounds=component.bounds;
        if(bounds.right-bounds.left<=side && bounds.bottom-bounds.top<=side) {
            size_t best=result.tiles.size();int64_t growth=std::numeric_limits<int64_t>::max();Bounds combined=bounds;
            for(size_t i=0;i<result.tiles.size();++i) {
                const Bounds candidate=unite(result.tiles[i].core,bounds);
                if(candidate.right-candidate.left>side || candidate.bottom-candidate.top>side)continue;
                const auto cost=area(candidate)-area(result.tiles[i].core);
                if(cost<growth) { best=i;growth=cost;combined=candidate; }
            }
            if(best<result.tiles.size()) {
                auto& tile=result.tiles[best];tile.components.push_back(component.label);tile.core=combined;tile.crop=context(combined,width,height);
            } else result.tiles.push_back({{component.label},bounds,context(bounds,width,height)});
        } else {
            for(int top=bounds.top;top<bounds.bottom;top+=side)for(int left=bounds.left;left<bounds.right;left+=side) {
                const Bounds core{left,top,std::min(left+side,bounds.right),std::min(top+side,bounds.bottom)};
                Bounds occupied{core.right,core.bottom,0,0};
                for(int y=core.top;y<core.bottom;++y)for(int x=core.left;x<core.right;++x)
                    if(result.labels[y*width+x]==component.label)occupied=unite(occupied,{x,y,x+1,y+1});
                if(occupied.right>occupied.left && occupied.bottom>occupied.top)
                    split.push_back({{component.label},core,context(occupied,width,height)});
                if(result.tiles.size()+split.size()>128)throw std::runtime_error("Cleanup mask exceeds 128 tiles. Select a smaller area.");
            }
        }
        if(result.tiles.size()+split.size()>128)throw std::runtime_error("Cleanup mask exceeds 128 tiles. Select a smaller area.");
    }
    result.tiles.insert(result.tiles.end(),split.begin(),split.end());
    std::stable_sort(result.tiles.begin(),result.tiles.end(),[](const Tile& a,const Tile& b) {
        if(a.core.top!=b.core.top)return a.core.top<b.core.top;
        if(a.core.left!=b.core.left)return a.core.left<b.core.left;
        if(a.core.bottom!=b.core.bottom)return a.core.bottom<b.core.bottom;
        return a.core.right<b.core.right;
    });
    return result;
}
}
