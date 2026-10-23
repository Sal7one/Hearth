#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mfreadwrite.h>
#include <wrl/client.h>
#include <sstream>
#include "wav_output.h"
using Microsoft::WRL::ComPtr;
static void check(HRESULT result, const char* operation) {
    if (FAILED(result)) { std::ostringstream message; message<<operation<<" (HRESULT 0x"<<std::hex<<static_cast<unsigned long>(result)<<")"; throw std::runtime_error(message.str()); }
}
int wmain(int argc, wchar_t** argv) {
    if (argc!=3) { std::fprintf(stderr,"Usage: hearth-media-decode input output.wav\n");return 2; }
    bool com=false,mf=false;
    try {
        check(CoInitializeEx(nullptr,COINIT_MULTITHREADED),"COM initialization failed");com=true;
        check(MFStartup(MF_VERSION,MFSTARTUP_LITE),"Media Foundation initialization failed");mf=true;
        ComPtr<IMFSourceReader> reader;
        check(MFCreateSourceReaderFromURL(argv[1],nullptr,&reader),"No supported media source");
        check(reader->SetStreamSelection(MF_SOURCE_READER_ALL_STREAMS,FALSE),"Cannot configure source tracks");
        check(reader->SetStreamSelection(MF_SOURCE_READER_FIRST_AUDIO_STREAM,TRUE),"No audio track found");
        ComPtr<IMFMediaType> type;check(MFCreateMediaType(&type),"Cannot configure audio decoder");
        check(type->SetGUID(MF_MT_MAJOR_TYPE,MFMediaType_Audio),"Cannot set audio type");
        check(type->SetGUID(MF_MT_SUBTYPE,MFAudioFormat_PCM),"Cannot select PCM audio");
        check(type->SetUINT32(MF_MT_AUDIO_NUM_CHANNELS,1),"Cannot select mono audio");
        check(type->SetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND,16000),"Cannot select 16k audio");
        check(type->SetUINT32(MF_MT_AUDIO_BITS_PER_SAMPLE,16),"Cannot select PCM16");
        check(type->SetUINT32(MF_MT_AUDIO_BLOCK_ALIGNMENT,2),"Cannot configure PCM frame size");
        check(type->SetUINT32(MF_MT_AUDIO_AVG_BYTES_PER_SECOND,32000),"Cannot configure PCM byte rate");
        check(reader->SetCurrentMediaType(MF_SOURCE_READER_FIRST_AUDIO_STREAM,nullptr,type.Get()),"This codec cannot be converted to PCM16/16k");
        FILE* file=nullptr;_wfopen_s(&file,argv[2],L"wb");WavOutput wav(file);
        while (true) {
            DWORD flags=0;ComPtr<IMFSample> sample;
            check(reader->ReadSample(MF_SOURCE_READER_FIRST_AUDIO_STREAM,0,nullptr,&flags,nullptr,&sample),"Audio decoding failed");
            if (flags&MF_SOURCE_READERF_ERROR) throw std::runtime_error("System media source reported an error");
            if (flags&MF_SOURCE_READERF_CURRENTMEDIATYPECHANGED) throw std::runtime_error("Audio format changed during decoding");
            if (sample) {
                ComPtr<IMFMediaBuffer> buffer;check(sample->ConvertToContiguousBuffer(&buffer),"Cannot access decoded audio");
                BYTE* data=nullptr;DWORD length=0;check(buffer->Lock(&data,nullptr,&length),"Cannot lock decoded audio");
                try { wav.write(data,length); } catch (...) { buffer->Unlock();throw; }
                check(buffer->Unlock(),"Cannot unlock decoded audio");
            }
            if (flags&MF_SOURCE_READERF_ENDOFSTREAM) break;
        }
        wav.finish(); reader.Reset();type.Reset();
        MFShutdown();CoUninitialize();return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr,"%s\n",error.what());_wremove(argv[2]);
        if(mf)MFShutdown();if(com)CoUninitialize();return 1;
    }
}
