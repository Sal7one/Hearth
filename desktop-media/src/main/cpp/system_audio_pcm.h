#ifndef HEARTH_SYSTEM_AUDIO_PCM_H
#define HEARTH_SYSTEM_AUDIO_PCM_H

#include <algorithm>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <deque>
#include <functional>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace hearth {
constexpr size_t kSystemAudioFrameBytes = 3200; // 100 ms, 16 kHz mono PCM16.
constexpr size_t kSystemAudioQueueFrames = 16;

/** Capture callbacks never wait for inference or a pipe. An overload is a visible error. */
class SystemAudioQueue {
public:
    bool push(std::vector<uint8_t> frame) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (closed_) return false;
        if (frame.empty() || frame.size() > kSystemAudioFrameBytes || frame.size() % 2)
            return failLocked("System audio returned an invalid PCM16 frame");
        if (frames_.size() >= kSystemAudioQueueFrames)
            return failLocked("System audio queue overflow: recognition cannot keep up; use a smaller model");
        frames_.push_back(std::move(frame)); condition_.notify_all(); return true;
    }
    void ready() { std::lock_guard<std::mutex> lock(mutex_); ready_ = true; condition_.notify_all(); }
    bool waitReady() {
        std::unique_lock<std::mutex> lock(mutex_);
        condition_.wait(lock, [&] { return ready_ || closed_; }); return ready_ && error_.empty();
    }
    bool pop(std::vector<uint8_t>& frame) {
        std::unique_lock<std::mutex> lock(mutex_);
        condition_.wait(lock, [&] { return !frames_.empty() || closed_; });
        if (!error_.empty() || frames_.empty()) return false;
        frame = std::move(frames_.front()); frames_.pop_front(); return true;
    }
    void finish() { std::lock_guard<std::mutex> lock(mutex_); closed_ = true; condition_.notify_all(); }
    void fail(std::string error) { std::lock_guard<std::mutex> lock(mutex_); failLocked(std::move(error)); }
    std::string error() const { std::lock_guard<std::mutex> lock(mutex_); return error_; }
private:
    bool failLocked(std::string error) {
        if (error_.empty()) error_ = std::move(error);
        closed_ = true; frames_.clear(); condition_.notify_all(); return false;
    }
    mutable std::mutex mutex_;
    std::condition_variable condition_;
    std::deque<std::vector<uint8_t>> frames_;
    bool ready_ = false, closed_ = false;
    std::string error_;
};

/** Owns copies of callback data; outputs fixed, bounded chunks and one partial chunk at normal stop. */
class SystemAudioPcm {
public:
    explicit SystemAudioPcm(std::function<bool(std::vector<uint8_t>)> emit) : emit_(std::move(emit)) { pending_.reserve(kSystemAudioFrameBytes); }
    void floats(const void* bytes, size_t frames) {
        const auto* source = static_cast<const uint8_t*>(bytes);
        for (size_t i = 0; i < frames; ++i) {
            float sample; std::memcpy(&sample, source + i * sizeof(float), sizeof(sample));
            if (!std::isfinite(sample)) throw std::runtime_error("System audio returned non-finite samples");
            append(static_cast<int16_t>(std::lround(std::clamp(sample, -1.0f, 1.0f) * 32767.0f)));
        }
    }
    void signed16(const void* bytes, size_t frames) {
        const auto* source = static_cast<const uint8_t*>(bytes);
        for (size_t i = 0; i < frames; ++i) append(static_cast<int16_t>(uint16_t(source[i*2]) | (uint16_t(source[i*2+1]) << 8)));
    }
    void finish() { if (!pending_.empty()) publish(); }
private:
    void append(int16_t sample) {
        pending_.push_back(static_cast<uint8_t>(sample & 255));
        pending_.push_back(static_cast<uint8_t>((static_cast<uint16_t>(sample) >> 8) & 255));
        if (pending_.size() == kSystemAudioFrameBytes) publish();
    }
    void publish() {
        auto frame = std::move(pending_); pending_.clear(); pending_.reserve(kSystemAudioFrameBytes);
        if (!emit_(std::move(frame))) throw std::runtime_error("System audio capture output stopped accepting frames");
    }
    std::function<bool(std::vector<uint8_t>)> emit_;
    std::vector<uint8_t> pending_;
};
}
#endif
