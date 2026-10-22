#import <ScreenCaptureKit/ScreenCaptureKit.h>
#import <CoreMedia/CoreMedia.h>
#import <Foundation/Foundation.h>
#include "system_audio_pcm.h"
#include <atomic>
#include <chrono>
#include <climits>
#include <cstdio>
#include <cstdlib>
#include <fcntl.h>
#include <memory>
#include <poll.h>
#include <signal.h>
#include <thread>
#include <unistd.h>

// stdout: HSA1, then u32le(byteCount)+owned PCM16 mono16k; zero length ends a normal stream.
static bool writeBytes(const uint8_t* bytes, size_t count, const std::shared_ptr<hearth::SystemAudioQueue>& queue) {
    while (count) {
        if (!queue->error().empty()) return false;
        const ssize_t written = ::write(STDOUT_FILENO, bytes, count);
        if (written > 0) { bytes += written; count -= static_cast<size_t>(written); continue; }
        if (written < 0 && errno == EINTR) continue;
        if (written < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            pollfd fd{STDOUT_FILENO,POLLOUT,0}; const int status=::poll(&fd,1,100);
            if (status >= 0 && !(fd.revents & (POLLERR|POLLHUP|POLLNVAL))) continue;
            if (status < 0 && errno == EINTR) continue;
        }
        queue->fail("System audio receiver closed its PCM pipe"); return false;
    }
    return true;
}
static bool writeSize(size_t count, const std::shared_ptr<hearth::SystemAudioQueue>& queue) {
    const uint8_t bytes[]{static_cast<uint8_t>(count),static_cast<uint8_t>(count>>8),static_cast<uint8_t>(count>>16),static_cast<uint8_t>(count>>24)};
    return writeBytes(bytes,sizeof(bytes),queue);
}

@interface HearthSystemAudio : NSObject <SCStreamOutput, SCStreamDelegate> {
@public
    std::shared_ptr<hearth::SystemAudioQueue> queue;
    std::unique_ptr<hearth::SystemAudioPcm> pcm;
    std::atomic<bool> finished, stopping, started;
    bool startPending;
    SCStream* stream;
    dispatch_queue_t audioQueue;
}
- (instancetype)initWithQueue:(std::shared_ptr<hearth::SystemAudioQueue>)value;
- (void)begin:(pid_t)parent;
- (void)requestStop;
- (void)fail:(NSString*)message;
@end

@implementation HearthSystemAudio
- (instancetype)initWithQueue:(std::shared_ptr<hearth::SystemAudioQueue>)value {
    if ((self=[super init])) {
        queue=value; finished=false; stopping=false; started=false; startPending=false;
        pcm=std::make_unique<hearth::SystemAudioPcm>([value](std::vector<uint8_t> frame) { return value->push(std::move(frame)); });
        audioQueue=dispatch_queue_create("com.sal7one.hearth.system-audio",DISPATCH_QUEUE_SERIAL);
    }
    return self;
}
- (void)fail:(NSString*)message {
    queue->fail(message.UTF8String ?: "macOS system audio capture failed");
    dispatch_async(dispatch_get_main_queue(), ^{ [self requestStop]; });
}
- (void)begin:(pid_t)parent {
    [SCShareableContent getShareableContentExcludingDesktopWindows:YES onScreenWindowsOnly:YES completionHandler:^(SCShareableContent* content,NSError* error) {
        dispatch_async(dispatch_get_main_queue(), ^{
            if (self->finished.load() || self->stopping.load()) return;
            if (error) {
                [self fail:[NSString stringWithFormat:@"System audio access failed. Allow Hearth in System Settings > Privacy & Security > Screen & System Audio Recording, then reopen Hearth if macOS asks. %@",error.localizedDescription]]; return;
            }
            SCDisplay* display=content.displays.firstObject;
            if (!display) { [self fail:@"No display is available for macOS system audio capture"]; return; }
            NSMutableArray<SCRunningApplication*>* excluded=[NSMutableArray array];
            for (SCRunningApplication* app in content.applications)
                if (app.processID==parent || app.processID==getpid()) [excluded addObject:app];
            // excludesCurrentProcessAudio alone excludes only this helper, not Hearth's JVM.
            SCContentFilter* filter=[[SCContentFilter alloc] initWithDisplay:display excludingApplications:excluded exceptingWindows:@[]];
            SCStreamConfiguration* configuration=[SCStreamConfiguration new];
            configuration.capturesAudio=YES; configuration.excludesCurrentProcessAudio=YES;
            configuration.sampleRate=16000; configuration.channelCount=1;
            configuration.width=2; configuration.height=2; configuration.queueDepth=3;
            configuration.minimumFrameInterval=CMTimeMake(1,1); configuration.showsCursor=NO;
            self->stream=[[SCStream alloc] initWithFilter:filter configuration:configuration delegate:self];
            NSError* outputError=nil;
            if (![self->stream addStreamOutput:self type:SCStreamOutputTypeAudio sampleHandlerQueue:self->audioQueue error:&outputError]) {
                [self fail:outputError.localizedDescription ?: @"Cannot attach the macOS system audio output"]; return;
            }
            // No screen or microphone output is attached or persisted.
            self->startPending=true;
            [self->stream startCaptureWithCompletionHandler:^(NSError* startError) {
                dispatch_async(dispatch_get_main_queue(), ^{
                    self->startPending=false;
                    if (startError) {
                        [self fail:[NSString stringWithFormat:@"System audio could not start. Check Hearth's Screen & System Audio Recording permission in System Settings. %@",startError.localizedDescription]]; return;
                    }
                    self->started=true; self->queue->ready();
                    if (self->stopping.load()) { self->stopping=false; [self requestStop]; }
                });
            }];
        });
    }];
}
- (void)requestStop {
    if (finished.load() || stopping.exchange(true)) return;
    if (!stream) { queue->finish(); finished=true; return; }
    if (!started.load()) {
        if (!startPending) { queue->finish(); finished=true; }
        return; // An outstanding start callback will stop immediately after it completes.
    }
    [stream stopCaptureWithCompletionHandler:^(NSError* error) {
        if (error) self->queue->fail(error.localizedDescription.UTF8String ?: "macOS could not stop system audio capture");
        dispatch_async(self->audioQueue, ^{
            try { if (self->queue->error().empty()) self->pcm->finish(); }
            catch (const std::exception& failure) { self->queue->fail(failure.what()); }
            self->queue->finish(); self->finished=true;
        });
    }];
}
- (void)stream:(SCStream*)sender didStopWithError:(NSError*)error {
    (void)sender;
    if (!stopping.load()) [self fail:error.localizedDescription ?: @"macOS system audio capture stopped unexpectedly"];
}
- (void)stream:(SCStream*)sender didOutputSampleBuffer:(CMSampleBufferRef)sample ofType:(SCStreamOutputType)type {
    (void)sender;
    if (type!=SCStreamOutputTypeAudio || stopping.load() || !CMSampleBufferIsValid(sample) || !CMSampleBufferDataIsReady(sample)) return;
    CMBlockBufferRef retained=nullptr;
    try {
        const auto* format=CMAudioFormatDescriptionGetStreamBasicDescription(CMSampleBufferGetFormatDescription(sample));
        const CMItemCount frames=CMSampleBufferGetNumSamples(sample);
        if (!format || format->mFormatID!=kAudioFormatLinearPCM || format->mSampleRate!=16000 || format->mChannelsPerFrame!=1 || frames<0 || frames>16000 ||
            (format->mFormatFlags & kAudioFormatFlagIsBigEndian)) throw std::runtime_error("macOS returned unsupported system audio; expected 16 kHz mono PCM");
        if (!frames) return;
        AudioBufferList buffers{}; size_t required=0;
        const OSStatus status=CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(sample,&required,&buffers,sizeof(buffers),nullptr,nullptr,
            kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment,&retained);
        if (status!=noErr || buffers.mNumberBuffers!=1 || !buffers.mBuffers[0].mData || buffers.mBuffers[0].mNumberChannels!=1)
            throw std::runtime_error("Cannot read the macOS system audio buffer");
        const AudioBuffer& buffer=buffers.mBuffers[0];
        if ((format->mFormatFlags & kAudioFormatFlagIsFloat) && format->mBitsPerChannel==32 && buffer.mDataByteSize>=static_cast<size_t>(frames)*4)
            pcm->floats(buffer.mData,static_cast<size_t>(frames));
        else if ((format->mFormatFlags & kAudioFormatFlagIsSignedInteger) && format->mBitsPerChannel==16 && buffer.mDataByteSize>=static_cast<size_t>(frames)*2)
            pcm->signed16(buffer.mData,static_cast<size_t>(frames));
        else throw std::runtime_error("macOS returned an unsupported system audio sample format");
    } catch (const std::exception& error) { [self fail:[NSString stringWithUTF8String:error.what()]]; }
    if (retained) CFRelease(retained);
}
@end

int main(int argc,char** argv) {
    @autoreleasepool {
        if (@available(macOS 13.3,*)) {
            if (argc==2 && std::strcmp(argv[1],"--probe")==0) {
                std::puts("HEARTH_SYSTEM_AUDIO_PROTOCOL=1\nAVAILABLE=1\nPERMISSION=not-requested\nSAMPLE_RATE=16000\nCHANNELS=1\nEXCLUDES_HEARTH_PROCESS=1"); return 0;
            }
            if (argc!=3 || std::strcmp(argv[1],"capture")!=0) { std::fprintf(stderr,"Usage: hearth-system-audio --probe | capture parent-pid\n"); return 2; }
            char* end=nullptr; const long parent=std::strtol(argv[2],&end,10);
            if (!end || *end || parent<=0 || parent>INT_MAX) { std::fprintf(stderr,"Invalid Hearth process id\n"); return 2; }
            signal(SIGPIPE,SIG_IGN);
            fcntl(STDIN_FILENO,F_SETFL,fcntl(STDIN_FILENO,F_GETFL)|O_NONBLOCK);
            fcntl(STDOUT_FILENO,F_SETFL,fcntl(STDOUT_FILENO,F_GETFL)|O_NONBLOCK);
            auto queue=std::make_shared<hearth::SystemAudioQueue>();
            HearthSystemAudio* capture=[[HearthSystemAudio alloc] initWithQueue:queue];
            std::thread writer([queue] {
                if (!queue->waitReady()) return;
                const uint8_t magic[]{'H','S','A','1'};
                if (!writeBytes(magic,sizeof(magic),queue)) return;
                std::vector<uint8_t> frame;
                while (queue->pop(frame)) if (!writeSize(frame.size(),queue) || !writeBytes(frame.data(),frame.size(),queue)) return;
                if (queue->error().empty()) (void)writeSize(0,queue);
            });
            [capture begin:static_cast<pid_t>(parent)];
            using clock=std::chrono::steady_clock;
            const auto began=clock::now(); auto stopAt=clock::time_point::max(); std::string command;
            while (!capture->finished.load()) {
                CFRunLoopRunInMode(kCFRunLoopDefaultMode,0.05,false);
                char bytes[64]; const ssize_t count=::read(STDIN_FILENO,bytes,sizeof(bytes));
                if (count==0) [capture requestStop];
                else if (count>0) for (ssize_t i=0;i<count;++i) {
                    if (bytes[i]=='\n') {
                        if (command=="STOP") [capture requestStop];
                        else [capture fail:@"Invalid system audio control command"];
                        command.clear();
                    } else { command+=bytes[i]; if (command.size()>16) { [capture fail:@"Oversized system audio control command"]; break; } }
                }
                if (!queue->error().empty()) [capture requestStop];
                if (!capture->started.load() && clock::now()-began>std::chrono::minutes(2)) [capture fail:@"System audio did not start within two minutes; check macOS screen and audio recording permission"];
                if (capture->stopping.load() && stopAt==clock::time_point::max()) stopAt=clock::now();
                if (stopAt!=clock::time_point::max() && clock::now()-stopAt>std::chrono::seconds(10)) { queue->fail("macOS did not stop system audio capture within ten seconds"); break; }
            }
            queue->finish(); writer.join();
            const auto error=queue->error();
            if (!error.empty()) { std::fprintf(stderr,"%s\n",error.c_str()); return 1; }
            return 0;
        } else { std::fprintf(stderr,"System audio requires macOS 13.3 or newer\n"); return 1; }
    }
}
