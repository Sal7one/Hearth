#ifndef RING_BUFFER_H
#define RING_BUFFER_H

#include <vector>
#include <cstring>
#include <algorithm>
#include <cstddef>
#include <cmath>
#include <cassert>
#include <cstdint>
#include <limits>
#include <stdexcept>
#include <type_traits>
#include <utility>

namespace stt {

/**
 * High-performance GROWING ring buffer with power-of-two optimization.
 * Uses bitmask instead of modulo for O(1) index wrapping.
 * 
 * IMPORTANT SEMANTICS:
 * - This is a GROWING buffer, NOT a fixed-size circular buffer that overwrites old data.
 * - It auto-expands when capacity is exceeded (like std::vector).
 * - For fixed-size ring that drops old samples, use FixedRingBuffer (if needed).
 * 
 * Thread safety: NOT thread-safe. Caller must synchronize all access.
 * In multi-threaded contexts (audio thread + engine thread), wrap access in a mutex
 * or use single-producer-single-consumer patterns.
 * 
 * Invariants (debug-asserted):
 * - mask_ == buffer_.size() - 1 when buffer_.size() > 0
 * - head_ < buffer_.size() when size_ > 0
 * - size_ <= buffer_.size()
 */
template<typename T>
class RingBuffer {
public:
    static_assert(std::is_trivially_copyable<T>::value,
                  "RingBuffer requires trivially copyable samples");
    /**
     * Create ring buffer with specified capacity.
     * Capacity is rounded up to next power of two for performance.
     */
    explicit RingBuffer(size_t capacity = 0) {
        if (capacity > 0) {
            reserve(capacity);
        }
    }
    
    /**
     * Reserve capacity (rounds up to power of two).
     */
    void reserve(size_t capacity) {
        if (capacity <= buffer_.size()) return;
        const size_t newCapacity = nextPowerOfTwo(capacity);
        if (newCapacity > buffer_.max_size()) {
            throw std::length_error("RingBuffer capacity exceeds vector max_size");
        }

        // Allocate once, then copy the two possible contiguous regions. Keep
        // the old buffer intact if allocation fails.
        std::vector<T> grown(newCapacity);
        if (size_ > 0) {
            const size_t first = std::min(size_, buffer_.size() - head_);
            std::memcpy(grown.data(), buffer_.data() + head_, first * sizeof(T));
            if (size_ > first) {
                std::memcpy(grown.data() + first, buffer_.data(),
                            (size_ - first) * sizeof(T));
            }
        }
        buffer_.swap(grown);
        mask_ = newCapacity - 1;
        head_ = 0;
        
        // Debug: verify invariant
        assertInvariants();
    }
    
    void clear() {
        head_ = 0;
        size_ = 0;
    }
    
    size_t size() const { return size_; }
    size_t capacity() const { return buffer_.size(); }
    bool empty() const { return size_ == 0; }
    
    /**
     * Push data to the back of the buffer.
     * Automatically grows if needed.
     */
    void push_back(const T* data, size_t count) {
        if (count == 0) return;
        if (!data) throw std::invalid_argument("RingBuffer push source is null");
        if (count > std::numeric_limits<size_t>::max() - size_) {
            throw std::length_error("RingBuffer size overflow");
        }

        // A caller may append its own contiguousRegion(). Copy that source
        // before growth or a wrapped write can invalidate/overwrite it.
        std::vector<T> aliasedSource;
        if (!buffer_.empty()) {
            const auto begin = reinterpret_cast<std::uintptr_t>(buffer_.data());
            const auto source = reinterpret_cast<std::uintptr_t>(data);
            const size_t bytes = buffer_.size() * sizeof(T);
            if (source >= begin && source - begin < bytes) {
                const size_t offsetBytes = static_cast<size_t>(source - begin);
                if (offsetBytes % sizeof(T) != 0 ||
                    count > (bytes - offsetBytes) / sizeof(T)) {
                    throw std::invalid_argument("RingBuffer push source exceeds storage");
                }
                aliasedSource.assign(data, data + count);
                data = aliasedSource.data();
            }
        }
        ensureCapacity(size_ + count);
        
        size_t tail = (head_ + size_) & mask_;
        size_t firstChunk = std::min(count, buffer_.size() - tail);
        
        std::memcpy(&buffer_[tail], data, firstChunk * sizeof(T));
        if (count > firstChunk) {
            std::memcpy(&buffer_[0], data + firstChunk, (count - firstChunk) * sizeof(T));
        }
        
        size_ += count;
    }
    
    void push_back(const std::vector<T>& data) {
        push_back(data.data(), data.size());
    }
    
    /**
     * Pop data from the front.
     */
    size_t pop_front(T* dest, size_t count) {
        count = std::min(count, size_);
        if (count == 0) return 0;
        
        size_t firstChunk = std::min(count, buffer_.size() - head_);
        
        if (dest) {
            std::memcpy(dest, &buffer_[head_], firstChunk * sizeof(T));
            if (count > firstChunk) {
                std::memcpy(dest + firstChunk, &buffer_[0], (count - firstChunk) * sizeof(T));
            }
        }
        
        head_ = (head_ + count) & mask_;
        size_ -= count;
        
        return count;
    }
    
    /**
     * Discard samples from the front without copying.
     */
    void discard_front(size_t count) {
        count = std::min(count, size_);
        head_ = (head_ + count) & mask_;
        size_ -= count;
    }
    
    /**
     * Peek at front data without removing.
     */
    size_t peek_front(T* dest, size_t count) const {
        count = std::min(count, size_);
        if (count == 0) return 0;
        if (!dest) throw std::invalid_argument("RingBuffer peek destination is null");
        
        size_t firstChunk = std::min(count, buffer_.size() - head_);
        std::memcpy(dest, &buffer_[head_], firstChunk * sizeof(T));
        
        if (count > firstChunk) {
            std::memcpy(dest + firstChunk, &buffer_[0], (count - firstChunk) * sizeof(T));
        }
        
        return count;
    }
    
    /**
     * Get linearized copy of entire buffer contents.
     */
    std::vector<T> toVector() const {
        std::vector<T> result(size_);
        peek_front(result.data(), size_);
        return result;
    }
    
    /**
     * Check if current data is contiguous (not wrapped around).
     * If true, data() returns a valid pointer to all size_ elements.
     */
    bool isContiguous() const {
        return size_ == 0 || size_ <= buffer_.size() - head_;
    }
    
    /**
     * Get contiguous region for zero-copy access.
     * Returns pointer and count of contiguous samples from head.
     * Use this for feeding engines directly without copying.
     */
    std::pair<const T*, size_t> contiguousRegion() const {
        if (size_ == 0) return {nullptr, 0};
        size_t contiguous = std::min(size_, buffer_.size() - head_);
        return {&buffer_[head_], contiguous};
    }
    
    T& operator[](size_t index) {
        return buffer_[(head_ + index) & mask_];
    }
    
    const T& operator[](size_t index) const {
        return buffer_[(head_ + index) & mask_];
    }

protected:
    void ensureCapacity(size_t required) {
        if (required <= buffer_.size()) return;
        const size_t doubled = buffer_.size() <=
            std::numeric_limits<size_t>::max() / 2
                ? buffer_.size() * 2 : std::numeric_limits<size_t>::max();
        size_t newCapacity = std::max(required, doubled);
        newCapacity = std::max(newCapacity, size_t(1024));
        
        reserve(newCapacity);
    }
    
    /**
     * Round up to next power of two.
     * Safe for both 32-bit and 64-bit size_t.
     */
    static size_t nextPowerOfTwo(size_t n) {
        if (n == 0) return 1;
        const size_t highestPower = size_t(1) << (sizeof(size_t) * 8 - 1);
        if (n > highestPower) {
            throw std::length_error("RingBuffer power-of-two capacity overflow");
        }
        n--;
        n |= n >> 1;
        n |= n >> 2;
        n |= n >> 4;
        n |= n >> 8;
        n |= n >> 16;
#if SIZE_MAX > UINT32_MAX
        // Only do 64-bit shift on 64-bit platforms to avoid UB
        n |= n >> 32;
#endif
        return n + 1;
    }
    
    /**
     * Debug-only invariant checks.
     * Call after any operation that modifies buffer_/mask_/head_/size_.
     */
    void assertInvariants() const {
#ifndef NDEBUG
        // mask_ must equal capacity - 1 when buffer is allocated
        if (!buffer_.empty()) {
            assert(mask_ == buffer_.size() - 1 && "mask_ invariant violated");
        }
        // head_ must be within bounds
        if (size_ > 0) {
            assert(head_ < buffer_.size() && "head_ out of bounds");
        }
        // size_ can't exceed capacity
        assert(size_ <= buffer_.size() && "size_ exceeds capacity");
#endif
    }
    
    std::vector<T> buffer_;
    size_t head_ = 0;
    size_t size_ = 0;
    size_t mask_ = 0;  // For power-of-two: capacity - 1
};

/**
 * Specialized float ring buffer with audio-specific operations.
 */
class AudioRingBuffer : public RingBuffer<float> {
public:
    explicit AudioRingBuffer(size_t capacity = 0) : RingBuffer<float>(capacity) {}
    
    /**
     * Apply gain normalization in-place to the last N samples.
     */
    float normalizeLastN(size_t count, float targetPeak = 0.9f, float minPeak = 0.0001f) {
        count = std::min(count, size());
        if (count == 0) return 1.0f;
        
        float maxVal = 0.0f;
        size_t startIdx = size() - count;
        for (size_t i = 0; i < count; ++i) {
            float absVal = std::abs((*this)[startIdx + i]);
            if (absVal > maxVal) maxVal = absVal;
        }
        
        if (maxVal < minPeak || maxVal >= targetPeak) {
            return 1.0f;
        }
        
        float gain = targetPeak / maxVal;
        
        for (size_t i = 0; i < count; ++i) {
            (*this)[startIdx + i] *= gain;
        }
        
        return gain;
    }
    
    /**
     * Pad buffer to minimum size with zeros.
     * Optimized: uses batch push instead of per-sample.
     */
    void padToSize(size_t minSize) {
        if (size() >= minSize) return;
        
        size_t padCount = minSize - size();
        ensureCapacity(size() + padCount);
        
        // Use a local zero buffer for efficient batch padding
        constexpr size_t BATCH_SIZE = 1024;
        float zeros[BATCH_SIZE] = {0.0f};  // Zero-initialized
        
        while (padCount > 0) {
            size_t chunk = std::min(padCount, BATCH_SIZE);
            push_back(zeros, chunk);
            padCount -= chunk;
        }
    }
};

} // namespace stt

#endif // RING_BUFFER_H
