#import <Metal/Metal.h>

extern "C" bool hearth_desktop_metal_device_present() {
    @autoreleasepool {
        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        const bool present = device != nil;
        [device release];
        return present;
    }
}
