#pragma once
#include <cstdint>
#include <cstdio>
#include <stdexcept>
#include <string>

// Canonical mono PCM16/16k WAV, bounded to one hour. No untrusted size reaches an allocation.
class WavOutput {
    FILE* file_ = nullptr;
    uint32_t bytes_ = 0;
    static void le32(FILE* f, uint32_t v) { for (int i=0;i<4;++i) std::fputc(static_cast<int>((v>>(8*i))&255),f); }
    static void le16(FILE* f, uint16_t v) { std::fputc(v&255,f); std::fputc(v>>8,f); }
public:
    explicit WavOutput(FILE* file) : file_(file) {
        if (!file_) throw std::runtime_error("Cannot create decoded audio output");
        if (std::fseek(file_,44,SEEK_SET)) throw std::runtime_error("Cannot initialize WAV header");
    }
    WavOutput(const WavOutput&) = delete;
    WavOutput& operator=(const WavOutput&) = delete;
    ~WavOutput() { if(file_) std::fclose(file_); }
    void write(const void* data, size_t count) {
        constexpr uint32_t maximum = 16000U*2U*60U*60U;
        if (count%2 || count > maximum-bytes_) throw std::runtime_error("Audio exceeds the one-hour PCM limit");
        if (std::fwrite(data,1,count,file_)!=count) throw std::runtime_error("Cannot write decoded audio");
        bytes_+=static_cast<uint32_t>(count);
    }
    void finish() {
        if (!bytes_) throw std::runtime_error("Media contains no decoded audio samples");
        if (std::fseek(file_,0,SEEK_SET)) throw std::runtime_error("Cannot finalize WAV header");
        std::fwrite("RIFF",1,4,file_);le32(file_,36+bytes_);std::fwrite("WAVEfmt ",1,8,file_);
        le32(file_,16);le16(file_,1);le16(file_,1);le32(file_,16000);le32(file_,32000);le16(file_,2);le16(file_,16);
        std::fwrite("data",1,4,file_);le32(file_,bytes_);
        if (std::fflush(file_) || std::ferror(file_)) throw std::runtime_error("Cannot flush WAV output");
    }
};
