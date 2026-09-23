#ifndef BUFFER_POOL_H
#define BUFFER_POOL_H

#include <vector>
#include <memory>
#include <mutex>
#include <atomic>
#include <algorithm>
#include <cstddef>
#include <cstring>
#include <cstdint>
#include <utility>

namespace stt {

template<typename T> class PooledBuffer;

/**
 * Thread-safe buffer pool for hot-path allocations.
 * 
 * Eliminates malloc/free overhead in real-time audio processing.
 * Uses a simple free-list with mutex (lock-free version available for extreme cases).
 * 
 * Usage:
 * ```cpp
 * BufferPool<float> pool(4096, 16);  // 16 buffers of 4096 floats each
 * 
 * float* buffer = pool.acquire();
 * if (buffer) {
 *     // Use buffer...
 *     pool.release(buffer);
 * }
 * ```
 */
template<typename T>
class BufferPool {
public:
    /**
     * Create a pool with fixed-size buffers.
     * @param bufferSize Number of elements per buffer
     * @param poolCapacity Number of buffers in the pool
     */
    BufferPool(size_t bufferSize, size_t poolCapacity)
        : bufferSize_(bufferSize)
        , capacity_(poolCapacity) {
        
        // Pre-allocate all buffers
        storage_.reserve(poolCapacity);
        freeList_.reserve(poolCapacity);
        inUse_.resize(poolCapacity, false);
        
        for (size_t i = 0; i < poolCapacity; ++i) {
            auto buffer = std::make_unique<T[]>(bufferSize);
            freeList_.push_back(i);
            storage_.push_back(std::move(buffer));
        }
    }
    
    /**
     * Acquire a buffer from the pool.
     * @return Pointer to buffer, or nullptr if pool exhausted
     */
    T* acquire() {
        std::lock_guard<std::mutex> lock(mutex_);
        
        if (freeList_.empty()) {
            // Pool exhausted - could optionally grow here
            return nullptr;
        }
        
        const size_t slot = freeList_.back();
        freeList_.pop_back();
        inUse_[slot] = true;
        ++activeCount_;
        
        return storage_[slot].get();
    }

    /** Prefer this handle when the borrower does not need the raw-pointer API. */
    PooledBuffer<T> acquirePooled();
    
    /**
     * Release a buffer back to the pool.
     * @param buffer Pointer previously returned by acquire()
     */
    bool tryRelease(T* buffer) {
        if (!buffer) return false;
        std::lock_guard<std::mutex> lock(mutex_);
        for (size_t slot = 0; slot < storage_.size(); ++slot) {
            if (storage_[slot].get() != buffer) continue;
            if (!inUse_[slot]) return false; // Double release, in every build mode.
            inUse_[slot] = false;
            freeList_.push_back(slot);
            --activeCount_;
            return true;
        }
        return false; // Foreign or interior pointer.
    }

    void release(T* buffer) { (void)tryRelease(buffer); }
    
    /**
     * Acquire a buffer and zero it.
     */
    T* acquireZeroed() {
        T* buffer = acquire();
        if (buffer) {
            std::fill_n(buffer, bufferSize_, T{});
        }
        return buffer;
    }
    
    // Pool statistics
    size_t bufferSize() const { return bufferSize_; }
    size_t capacity() const { return capacity_; }
    size_t available() const { 
        std::lock_guard<std::mutex> lock(mutex_);
        return freeList_.size(); 
    }
    size_t active() const { return activeCount_.load(); }
    
    /** Rebuild the free list without reclaiming buffers held by borrowers. */
    void resetAll() {
        std::lock_guard<std::mutex> lock(mutex_);
        freeList_.clear();
        for (size_t slot = 0; slot < storage_.size(); ++slot) {
            if (!inUse_[slot]) freeList_.push_back(slot);
        }
    }

private:
    size_t bufferSize_;
    size_t capacity_;
    std::vector<std::unique_ptr<T[]>> storage_;
    std::vector<size_t> freeList_;
    std::vector<bool> inUse_;
    mutable std::mutex mutex_;
    std::atomic<size_t> activeCount_{0};
};

/**
 * RAII guard for automatic buffer release.
 */
template<typename T>
class PooledBuffer {
public:
    PooledBuffer(BufferPool<T>& pool) : pool_(pool), buffer_(pool.acquire()) {}
    ~PooledBuffer() { if (buffer_) pool_.release(buffer_); }
    
    T* get() { return buffer_; }
    const T* get() const { return buffer_; }
    T& operator[](size_t i) { return buffer_[i]; }
    const T& operator[](size_t i) const { return buffer_[i]; }
    
    bool valid() const { return buffer_ != nullptr; }
    explicit operator bool() const { return valid(); }
    
    // Non-copyable
    PooledBuffer(const PooledBuffer&) = delete;
    PooledBuffer& operator=(const PooledBuffer&) = delete;
    
    // Movable
    PooledBuffer(PooledBuffer&& other) noexcept 
        : pool_(other.pool_), buffer_(other.buffer_) {
        other.buffer_ = nullptr;
    }

private:
    BufferPool<T>& pool_;
    T* buffer_;
};

template<typename T>
PooledBuffer<T> BufferPool<T>::acquirePooled() { return PooledBuffer<T>(*this); }

// ============================================================================
// Specialized Audio Buffer Pool
// ============================================================================

/**
 * Pre-configured audio buffer pool for 16kHz audio.
 */
class AudioBufferPool {
public:
    static constexpr size_t BUFFER_SAMPLES = 16000;  // 1 second at 16kHz
    static constexpr size_t POOL_SIZE = 32;

    struct Stats {
        size_t availableInt16;
        size_t availableFloat;
    };
    
    static AudioBufferPool& getInstance() {
        static AudioBufferPool instance;
        return instance;
    }

    static bool wasInitialized() noexcept {
        return initialized_.load(std::memory_order_acquire);
    }

    /** A stats request alone must not allocate the 3 MB singleton. */
    static Stats snapshotStats() {
        if (!wasInitialized()) return {POOL_SIZE, POOL_SIZE};
        auto& pool = getInstance();
        return {pool.availableInt16(), pool.availableFloat()};
    }
    
    // int16 buffers
    int16_t* acquireInt16() { return int16Pool_.acquire(); }
    void releaseInt16(int16_t* buffer) { int16Pool_.release(buffer); }
    
    // float buffers
    float* acquireFloat() { return floatPool_.acquire(); }
    void releaseFloat(float* buffer) { floatPool_.release(buffer); }
    
    // Statistics
    size_t availableInt16() const { return int16Pool_.available(); }
    size_t availableFloat() const { return floatPool_.available(); }

private:
    AudioBufferPool() 
        : int16Pool_(BUFFER_SAMPLES, POOL_SIZE)
        , floatPool_(BUFFER_SAMPLES, POOL_SIZE) {
        initialized_.store(true, std::memory_order_release);
    }

    static inline std::atomic<bool> initialized_{false};
    
    BufferPool<int16_t> int16Pool_;
    BufferPool<float> floatPool_;
};

/**
 * RAII guard for audio buffers.
 */
class PooledAudioBuffer {
public:
    enum class Type { INT16, FLOAT };
    
    PooledAudioBuffer(Type type) : type_(type) {
        if (type == Type::INT16) {
            int16Data_ = AudioBufferPool::getInstance().acquireInt16();
        } else {
            floatData_ = AudioBufferPool::getInstance().acquireFloat();
        }
    }
    
    ~PooledAudioBuffer() {
        if (type_ == Type::INT16 && int16Data_) {
            AudioBufferPool::getInstance().releaseInt16(int16Data_);
        } else if (floatData_) {
            AudioBufferPool::getInstance().releaseFloat(floatData_);
        }
    }
    
    int16_t* int16() { return int16Data_; }
    float* floatPtr() { return floatData_; }
    bool valid() const { return int16Data_ || floatData_; }
    
    // Non-copyable
    PooledAudioBuffer(const PooledAudioBuffer&) = delete;
    PooledAudioBuffer& operator=(const PooledAudioBuffer&) = delete;

private:
    Type type_;
    int16_t* int16Data_ = nullptr;
    float* floatData_ = nullptr;
};

} // namespace stt

#endif // BUFFER_POOL_H
