#ifndef HEARTH_SYSTEM_AUDIO_PCM_H
#define HEARTH_SYSTEM_AUDIO_PCM_H

#include <algorithm>
#include <array>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <functional>
#include <mutex>
#include <stdexcept>
#include <string>

namespace hearth {
constexpr size_t kSystemAudioFrameBytes = 3200; // 100 ms, 16 kHz mono PCM16.
constexpr size_t kSystemAudioQueueFrames = 16;
struct SystemAudioFrame {
    const uint8_t* bytes = nullptr;
    size_t size = 0;
};

/** Capture callbacks never wait for inference or a pipe. An overload is a visible error. */
class SystemAudioQueue {
public:
    bool push(const uint8_t* bytes, size_t size) {
        bool wakeReader;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (closed_) return false;
            if (!bytes || !size || size > kSystemAudioFrameBytes || size % 2)
                return failLocked("System audio returned an invalid PCM16 frame");
            if (count_ == kSystemAudioQueueFrames)
                return failLocked("System audio queue overflow: recognition cannot keep up; use a smaller model");
            auto& slot = frames_[(front_ + count_) % frames_.size()];
            std::memcpy(slot.bytes.data(), bytes, size);
            slot.size = size;
            wakeReader = count_++ == 0;
        }
        if (wakeReader) condition_.notify_one();
        return true;
    }
    void ready() { std::lock_guard<std::mutex> lock(mutex_); ready_ = true; condition_.notify_all(); }
    bool waitReady() {
        std::unique_lock<std::mutex> lock(mutex_);
        condition_.wait(lock, [&] { return ready_ || closed_; });
        return ready_ && error_.empty();
    }
    // One reader may use the returned bytes until its next pop().
    SystemAudioFrame pop() {
        std::unique_lock<std::mutex> lock(mutex_);
        condition_.wait(lock, [&] { return count_ || closed_; });
        if (!error_.empty() || !count_) return {};
        const auto& slot = frames_[front_];
        front_ = (front_ + 1) % frames_.size();
        --count_;
        return {slot.bytes.data(), slot.size};
    }
    void finish() { std::lock_guard<std::mutex> lock(mutex_); closed_ = true; condition_.notify_all(); }
    void fail(std::string error) { std::lock_guard<std::mutex> lock(mutex_); failLocked(std::move(error)); }
    std::string error() const { std::lock_guard<std::mutex> lock(mutex_); return error_; }
private:
    bool failLocked(std::string error) {
        if (error_.empty()) error_ = std::move(error);
        closed_ = true;
        count_ = 0;
        condition_.notify_all();
        return false;
    }
    struct Frame { std::array<uint8_t, kSystemAudioFrameBytes> bytes; size_t size; };
    mutable std::mutex mutex_;
    std::condition_variable condition_;
    // The extra slot keeps the frame held by the pipe writer out of the producer's reach.
    std::array<Frame, kSystemAudioQueueFrames + 1> frames_;
    size_t front_ = 0, count_ = 0;
    bool ready_ = false, closed_ = false;
    std::string error_;
};

/** Emits borrowed PCM synchronously. The consumer copies the bytes before returning. */
class SystemAudioPcm {
public:
    explicit SystemAudioPcm(std::function<bool(const uint8_t*, size_t)> emit) : emit_(std::move(emit)) {}
    void floats(const void* bytes, size_t frames) {
        const auto* source = static_cast<const uint8_t*>(bytes);
        while (frames) {
            const size_t take = std::min(frames, (pending_.size() - size_) / 2);
            auto* output = pending_.data() + size_;
            for (size_t i = 0; i < take; ++i) {
                float sample;
                std::memcpy(&sample, source + i * sizeof(float), sizeof(sample));
                if (!std::isfinite(sample)) throw std::runtime_error("System audio returned non-finite samples");
                const auto bits = static_cast<uint16_t>(std::lround(std::clamp(sample, -1.0f, 1.0f) * 32767.0f));
                output[i * 2] = static_cast<uint8_t>(bits);
                output[i * 2 + 1] = static_cast<uint8_t>(bits >> 8);
            }
            size_ += take * 2;
            source += take * sizeof(float);
            frames -= take;
            if (size_ == pending_.size()) publishPending();
        }
    }
    void signed16(const void* bytes, size_t frames) {
        const auto* source = static_cast<const uint8_t*>(bytes);
        if (size_ && frames) {
            const size_t take = std::min(frames, (pending_.size() - size_) / 2);
            std::memcpy(pending_.data() + size_, source, take * 2);
            size_ += take * 2;
            source += take * 2;
            frames -= take;
            if (size_ == pending_.size()) publishPending();
        }
        constexpr size_t frameSamples = kSystemAudioFrameBytes / 2;
        while (frames >= frameSamples) {
            publish(source, kSystemAudioFrameBytes);
            source += kSystemAudioFrameBytes;
            frames -= frameSamples;
        }
        if (frames) {
            size_ = frames * 2;
            std::memcpy(pending_.data(), source, size_);
        }
    }
    void finish() { if (size_) publishPending(); }
private:
    void publishPending() {
        const size_t size = size_;
        size_ = 0;
        publish(pending_.data(), size);
    }
    void publish(const uint8_t* bytes, size_t size) {
        if (!emit_(bytes, size)) throw std::runtime_error("System audio capture output stopped accepting frames");
    }
    std::function<bool(const uint8_t*, size_t)> emit_;
    std::array<uint8_t, kSystemAudioFrameBytes> pending_;
    size_t size_ = 0;
};
}
#endif
