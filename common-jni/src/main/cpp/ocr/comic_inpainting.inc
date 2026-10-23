// LaMa's published ONNX tensor contract; region-local context and sparse-alpha publication.
std::vector<int32_t> ComicDetector::inpaint(const uint32_t* pixels,int width,int height,const std::vector<int32_t>& spans) {
    std::lock_guard<std::mutex> lock(impl->mutex);
    if(!impl->inpainter)throw std::runtime_error("Model is not the LaMa manga inpainter");
    if(!pixels || width<1 || height<1 || width>2048 || height>2048 || spans.empty() || spans.size()>3'000'000 || spans.size()%3)
        throw std::invalid_argument("Invalid LaMa image or mask");
    if(impl->cancelled.load())throw std::runtime_error("LaMa cleanup cancelled");
    std::vector<uint8_t> mask(size_t(width)*height,0);int left=width,top=height,right=0,bottom=0,previousY=-1,previousRight=0;
    for(size_t i=0;i<spans.size();i+=3){const int y=spans[i],x=spans[i+1],length=spans[i+2];
        if(y<0 || y>=height || x<0 || length<1 || int64_t(x)+length>width || y<previousY || (y==previousY && x<previousRight))throw std::invalid_argument("Invalid LaMa mask span");
        previousY=y;previousRight=x+length;left=std::min(left,x);top=std::min(top,y);right=std::max(right,x+length);bottom=std::max(bottom,y+1);
        std::fill(mask.begin()+y*width+x,mask.begin()+y*width+x+length,1);
    }
    left=std::max(0,left-64);top=std::max(0,top-64);right=std::min(width,right+64);bottom=std::min(height,bottom+64);
    const int cropWidth=right-left,cropHeight=bottom-top;constexpr int side=512,plane=side*side;
    std::vector<float> inputImage(3*plane),inputMask(plane);
    for(int y=0;y<side;++y)for(int x=0;x<side;++x){
        const float fx=std::clamp((x+.5f)*cropWidth/side-.5f,0.f,float(cropWidth-1)),fy=std::clamp((y+.5f)*cropHeight/side-.5f,0.f,float(cropHeight-1));
        const int x0=int(fx)+left,y0=int(fy)+top,x1=std::min(x0+1,right-1),y1=std::min(y0+1,bottom-1);const float dx=fx-int(fx),dy=fy-int(fy);
        for(int c=0;c<3;++c){auto channel=[&](int a,int b){return float((pixels[b*width+a]>>(16-c*8))&255)/255.f;};
            inputImage[c*plane+y*side+x]=(channel(x0,y0)*(1-dx)+channel(x1,y0)*dx)*(1-dy)+(channel(x0,y1)*(1-dx)+channel(x1,y1)*dx)*dy;}
        inputMask[y*side+x]=mask[(top+std::min(cropHeight-1,int((y+.5f)*cropHeight/side)))*width+left+std::min(cropWidth-1,int((x+.5f)*cropWidth/side))];
    }
    Value image,maskTensor,output;int64_t imageShape[]={1,3,side,side},maskShape[]={1,1,side,side};
    checked(api()->CreateTensorWithDataAsOrtValue(impl->memory,inputImage.data(),inputImage.size()*sizeof(float),imageShape,4,ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,&image.value));
    checked(api()->CreateTensorWithDataAsOrtValue(impl->memory,inputMask.data(),inputMask.size()*sizeof(float),maskShape,4,ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,&maskTensor.value));
    const OrtValue* values[]={image.value,maskTensor.value};const char* names[]={"image","mask"};const char* outputs[]={"output"};OrtValue* generated=nullptr;
    auto status=api()->Run(impl->session,impl->run,names,values,2,outputs,1,&generated);output.value=generated;checked(status);
    if(impl->cancelled.load())throw std::runtime_error("LaMa cleanup cancelled");
    validateTensor(output.value,ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,{1,3,side,side});float* rgb=nullptr;checked(api()->GetTensorMutableData(output.value,reinterpret_cast<void**>(&rgb)));
    for(int i=0;i<3*plane;++i)if(!std::isfinite(rgb[i]))throw std::runtime_error("LaMa produced non-finite pixels");
    std::vector<int32_t> patch(size_t(cropWidth)*cropHeight+4,0);patch[0]=left;patch[1]=top;patch[2]=cropWidth;patch[3]=cropHeight;
    for(int y=0;y<cropHeight;++y)for(int x=0;x<cropWidth;++x){
        if(!mask[(top+y)*width+left+x])continue;
        const float fx=std::clamp((x+.5f)*side/cropWidth-.5f,0.f,float(side-1)),fy=std::clamp((y+.5f)*side/cropHeight-.5f,0.f,float(side-1));
        const int x0=int(fx),y0=int(fy),x1=std::min(x0+1,side-1),y1=std::min(y0+1,side-1);const float dx=fx-x0,dy=fy-y0;uint32_t pixel=0xff000000u;
        for(int c=0;c<3;++c){const float* channel=rgb+c*plane;
            const float value=(channel[y0*side+x0]*(1-dx)+channel[y0*side+x1]*dx)*(1-dy)+(channel[y1*side+x0]*(1-dx)+channel[y1*side+x1]*dx)*dy;
            pixel|=uint32_t(std::lround(std::clamp(value,0.f,1.f)*255))<<(16-c*8);}
        patch[4+y*cropWidth+x]=int32_t(pixel);
    }
    return patch;
}
