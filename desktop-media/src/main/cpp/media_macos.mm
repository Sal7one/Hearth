#import <AVFoundation/AVFoundation.h>
#import <Foundation/Foundation.h>
#include "wav_output.h"
#include <memory>

int main(int argc, char** argv) {
    @autoreleasepool {
        if (argc!=3) { std::fprintf(stderr,"Usage: hearth-media-decode input output.wav\n"); return 2; }
        try {
            NSURL* url=[NSURL fileURLWithPath:[NSString stringWithUTF8String:argv[1]]];
            AVURLAsset* asset=[AVURLAsset URLAssetWithURL:url options:@{AVURLAssetPreferPreciseDurationAndTimingKey:@NO}];
            AVAssetTrack* track=[[asset tracksWithMediaType:AVMediaTypeAudio] firstObject];
            if (!track) throw std::runtime_error("No supported audio track found in this media file");
            NSError* error=nil;
            AVAssetReader* reader=[[AVAssetReader alloc] initWithAsset:asset error:&error];
            if (!reader) throw std::runtime_error(error.localizedDescription.UTF8String ?: "AVAssetReader initialization failed");
            NSDictionary* settings=@{AVFormatIDKey:@(kAudioFormatLinearPCM), AVSampleRateKey:@16000,
                AVNumberOfChannelsKey:@1, AVLinearPCMBitDepthKey:@16, AVLinearPCMIsFloatKey:@NO,
                AVLinearPCMIsBigEndianKey:@NO, AVLinearPCMIsNonInterleaved:@NO};
            AVAssetReaderTrackOutput* output=[[AVAssetReaderTrackOutput alloc] initWithTrack:track outputSettings:settings];
            output.alwaysCopiesSampleData=NO;
            if (![reader canAddOutput:output]) throw std::runtime_error("The system cannot decode this audio format");
            [reader addOutput:output];
            if (![reader startReading]) throw std::runtime_error(reader.error.localizedDescription.UTF8String ?: "AVFoundation decoding failed");
            WavOutput wav(std::fopen(argv[2],"wb"));
            while (CMSampleBufferRef sample=[output copyNextSampleBuffer]) {
                CMBlockBufferRef block=CMSampleBufferGetDataBuffer(sample);
                const size_t length=block?CMBlockBufferGetDataLength(block):0;
                try {
                    if (length>4*1024*1024 || length%2) throw std::runtime_error("System decoder returned an invalid audio buffer");
                    if (length) {
                        auto bytes=std::make_unique<uint8_t[]>(length);
                        if (CMBlockBufferCopyDataBytes(block,0,length,bytes.get())!=kCMBlockBufferNoErr)
                            throw std::runtime_error("Cannot read system audio buffer");
                        wav.write(bytes.get(),length);
                    }
                } catch (...) { CFRelease(sample); [reader cancelReading]; throw; }
                CFRelease(sample);
            }
            if (reader.status!=AVAssetReaderStatusCompleted)
                throw std::runtime_error(reader.error.localizedDescription.UTF8String ?: "System media decoding did not complete");
            wav.finish(); return 0;
        } catch (const std::exception& error) { std::fprintf(stderr,"%s\n",error.what()); std::remove(argv[2]); return 1; }
    }
}
