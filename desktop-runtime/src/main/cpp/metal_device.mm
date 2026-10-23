#import <Metal/Metal.h>
#include <cstring>

extern "C" bool hearth_desktop_metal_device_present() {
    @autoreleasepool {
        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        const bool present = device != nil;
        [device release];
        return present;
    }
}

extern "C" bool hearth_desktop_metal_device_description(char* output, size_t capacity) {
    @autoreleasepool {
        id<MTLDevice> device=MTLCreateSystemDefaultDevice();
        const char* name=device ? device.name.UTF8String : nullptr;
        const bool valid=name && capacity>std::strlen(name);
        if (valid) std::memcpy(output,name,std::strlen(name)+1);
        [device release]; return valid;
    }
}
