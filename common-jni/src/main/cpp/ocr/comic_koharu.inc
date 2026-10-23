// Included inside hearth::ocr after the common ORT/session implementation.
// RF-DETR processing adapted from Koharu 73abcaf (MIT, see OCR license assets).
std::vector<ComicDetection> ComicDetector::detectKoharu(const uint32_t* pixels,int width,int height) {
    constexpr int side=1152,plane=side*side,maskSide=288,maskPlane=maskSide*maskSide;
    constexpr float mean[]={.485f,.456f,.406f},deviation[]={.229f,.224f,.225f};
    std::vector<float> image(3*plane);
    for(int y=0;y<side;++y)for(int x=0;x<side;++x){
        const float fx=std::clamp((x+.5f)*width/side-.5f,0.f,float(width-1)),fy=std::clamp((y+.5f)*height/side-.5f,0.f,float(height-1));
        const int x0=int(fx),y0=int(fy),x1=std::min(x0+1,width-1),y1=std::min(y0+1,height-1);const float dx=fx-x0,dy=fy-y0;
        for(int c=0;c<3;++c){auto channel=[&](int a,int b){return float((pixels[b*width+a]>>(16-c*8))&255)/255.f;};
            const float value=(channel(x0,y0)*(1-dx)+channel(x1,y0)*dx)*(1-dy)+(channel(x0,y1)*(1-dx)+channel(x1,y1)*dx)*dy;
            image[c*plane+y*side+x]=(value-mean[c])/deviation[c];}
    }
    int64_t shape[]={1,3,side,side};Value input,boxes,logits,masks;
    checked(api()->CreateTensorWithDataAsOrtValue(impl->memory,image.data(),image.size()*sizeof(float),shape,4,ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,&input.value));
    const OrtValue* values[]={input.value};const char* names[]={"input"};const char* outputs[]={"dets","labels","masks"};OrtValue* result[]={nullptr,nullptr,nullptr};
    auto status=api()->Run(impl->session,impl->run,names,values,1,outputs,3,result);boxes.value=result[0];logits.value=result[1];masks.value=result[2];checked(status);
    if(impl->cancelled.load())throw std::runtime_error("Koharu segmentation cancelled");
    validateTensor(boxes.value,ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,{1,300,4});validateTensor(logits.value,ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,{1,300,5});
    validateTensor(masks.value,ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,{1,300,maskSide,maskSide});
    float *coords=nullptr,*classes=nullptr,*maskValues=nullptr;
    checked(api()->GetTensorMutableData(boxes.value,reinterpret_cast<void**>(&coords)));checked(api()->GetTensorMutableData(logits.value,reinterpret_cast<void**>(&classes)));
    checked(api()->GetTensorMutableData(masks.value,reinterpret_cast<void**>(&maskValues)));
    struct Candidate{int query,label;float score;Box box;};std::vector<Candidate> candidates;
    // Rank all pairs including background before thresholds, matching upstream.
    std::vector<std::pair<float,int>> ranked;ranked.reserve(1500);
    for(int i=0;i<1500;++i){if(!std::isfinite(classes[i]))throw std::runtime_error("Non-finite Koharu class logit");ranked.push_back({1.f/(1.f+std::exp(-classes[i])),i});}
    std::stable_sort(ranked.begin(),ranked.end(),[](const auto&a,const auto&b){return a.first>b.first;});constexpr float thresholds[]={.25f,.20f,.50f,.50f};
    for(int i=0;i<300;++i){
        const int label=ranked[i].second%5,query=ranked[i].second/5;const float score=ranked[i].first;if(label==4 || score<=thresholds[label])continue;
        const auto* b=coords+query*4;for(int j=0;j<4;++j)if(!std::isfinite(b[j]))throw std::runtime_error("Non-finite Koharu box");
        const int left=int(std::floor(std::clamp((b[0]-b[2]/2)*width,0.f,float(width)))),top=int(std::floor(std::clamp((b[1]-b[3]/2)*height,0.f,float(height))));
        const int right=int(std::ceil(std::clamp((b[0]+b[2]/2)*width,0.f,float(width)))),bottom=int(std::ceil(std::clamp((b[1]+b[3]/2)*height,0.f,float(height))));
        if(right-left<3 || bottom-top<3)continue;Box box{left,top,right-left,bottom-top,score};bool duplicate=false;
        for(const auto& kept:candidates)if(kept.label==label){
            const long overlap=std::max(0,std::min(right,kept.box.x+kept.box.width)-std::max(left,kept.box.x))*long(std::max(0,std::min(bottom,kept.box.y+kept.box.height)-std::max(top,kept.box.y)));
            if(double(overlap)/(long(box.width)*box.height+long(kept.box.width)*kept.box.height-overlap)>.5){duplicate=true;break;}}
        if(!duplicate)candidates.push_back({query,label,score,box});
    }
    if(candidates.size()>128)throw std::runtime_error("Koharu page exceeds 128 layout elements. Select a smaller area.");
    std::vector<ComicDetection> found;size_t spanCount=0;constexpr int mapping[]={1,4,0,3};
    for(const auto& candidate:candidates){
        if(impl->cancelled.load())throw std::runtime_error("Koharu segmentation cancelled");
        const float* source=maskValues+candidate.query*maskPlane;std::vector<int32_t> spans;
        for(int i=0;i<maskPlane;++i)if(!std::isfinite(source[i]))throw std::runtime_error("Non-finite Koharu mask");
        // Project one instance at a time with align_corners=false, avoiding N×page RAM.
        for(int y=0;y<height;++y){const float fy=std::clamp((y+.5f)*maskSide/height-.5f,0.f,float(maskSide-1));
            const int y0=int(fy),y1=std::min(y0+1,maskSide-1);const float dy=fy-y0;int start=-1;
            for(int x=0;x<=width;++x){bool foreground=false;if(x<width){
                const float fx=std::clamp((x+.5f)*maskSide/width-.5f,0.f,float(maskSide-1));const int x0=int(fx),x1=std::min(x0+1,maskSide-1);const float dx=fx-x0;
                foreground=((source[y0*maskSide+x0]*(1-dx)+source[y0*maskSide+x1]*dx)*(1-dy)+(source[y1*maskSide+x0]*(1-dx)+source[y1*maskSide+x1]*dx)*dy)>0;}
                if(foreground && start<0)start=x;
                if(!foreground && start>=0){spans.insert(spans.end(),{y,start,x-start});start=-1;if(++spanCount>1'000'000)throw std::runtime_error("Koharu mask complexity exceeds its safe page budget. Select a smaller area.");}
            }
        }
        if(!spans.empty())found.push_back({candidate.box,mapping[candidate.label],std::move(spans)});
    }
    return found;
}
