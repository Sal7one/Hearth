#ifndef SCOPED_TIMER_H
#define SCOPED_TIMER_H

#include <chrono>
#include <string>
#include <unordered_map>
#include <mutex>
#include <atomic>
#include <cstdint>
#include "logging.h"
#include "json_utils.h"

namespace stt {

/**
 * Global performance statistics collector.
 * Defined first so ScopedTimer can reference it.
 */
class PerformanceStats {
public:
    struct Stats {
        int64_t totalUs = 0;
        int64_t minUs = INT64_MAX;
        int64_t maxUs = 0;
        uint64_t count = 0;
        
        double avgUs() const { return count > 0 ? static_cast<double>(totalUs) / count : 0.0; }
        double avgMs() const { return avgUs() / 1000.0; }
    };
    
    static void record(const char* name, int64_t durationUs) {
        std::lock_guard<std::mutex> lock(mutex_);
        
        auto& stats = stats_[name];
        stats.totalUs += durationUs;
        stats.minUs = std::min(stats.minUs, durationUs);
        stats.maxUs = std::max(stats.maxUs, durationUs);
        stats.count++;
    }
    
    static Stats get(const char* name) {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = stats_.find(name);
        return it != stats_.end() ? it->second : Stats{};
    }
    
    static std::unordered_map<std::string, Stats> getAll() {
        std::lock_guard<std::mutex> lock(mutex_);
        return stats_;
    }
    
    static void reset() {
        std::lock_guard<std::mutex> lock(mutex_);
        stats_.clear();
    }
    
    static void reset(const char* name) {
        std::lock_guard<std::mutex> lock(mutex_);
        stats_.erase(name);
    }
    
    /**
     * Get stats as JSON string.
     */
    static std::string toJson() {
        std::lock_guard<std::mutex> lock(mutex_);

        JsonValue::Object values;
        values.reserve(stats_.size());
        for (const auto& [name, stats] : stats_) {
            values.emplace_back(name, JsonValue::object({
                {"count", stats.count},
                {"totalMs", stats.totalUs / 1000.0},
                {"avgMs", stats.avgMs()},
                {"minMs", stats.minUs / 1000.0},
                {"maxMs", stats.maxUs / 1000.0}
            }));
        }
        return JsonUtils::stringify(JsonValue::object(std::move(values)));
    }
    
    /**
     * Log all stats.
     */
    static void logAll() {
        std::lock_guard<std::mutex> lock(mutex_);
        
        LOG_I("PerfStats", "=== Performance Statistics ===");
        for (const auto& [name, stats] : stats_) {
            LOG_I("PerfStats", "%s: avg=%.2fms, min=%.2fms, max=%.2fms, count=%zu",
                  name.c_str(), stats.avgMs(), stats.minUs / 1000.0, 
                  stats.maxUs / 1000.0, static_cast<size_t>(stats.count));
        }
    }

private:
    static inline std::mutex mutex_;
    static inline std::unordered_map<std::string, Stats> stats_;
};

/**
 * RAII timer for performance profiling.
 * 
 * Usage:
 * ```cpp
 * void transcribe() {
 *     ScopedTimer timer("transcribe");
 *     // ... do work ...
 * } // Logs duration on destruction
 * ```
 */
class ScopedTimer {
public:
    explicit ScopedTimer(const char* name, bool logOnDestroy = true)
        : name_(name)
        , logOnDestroy_(logOnDestroy)
        , start_(std::chrono::steady_clock::now()) {}
    
    ~ScopedTimer() {
        auto end = std::chrono::steady_clock::now();
        auto duration = std::chrono::duration_cast<std::chrono::microseconds>(end - start_);
        durationUs_ = duration.count();
        
        if (logOnDestroy_) {
            if (durationUs_ >= 1000000) {
                LOG_I("Timer", "%s: %.2f s", name_, durationUs_ / 1000000.0f);
            } else if (durationUs_ >= 1000) {
                LOG_I("Timer", "%s: %.2f ms", name_, durationUs_ / 1000.0f);
            } else {
                LOG_I("Timer", "%s: %ld μs", name_, static_cast<long>(durationUs_));
            }
        }
        
        // Record to global stats
        PerformanceStats::record(name_, durationUs_);
    }
    
    /**
     * Get elapsed time so far (without stopping).
     */
    int64_t elapsedUs() const {
        auto now = std::chrono::steady_clock::now();
        return std::chrono::duration_cast<std::chrono::microseconds>(now - start_).count();
    }
    
    int64_t elapsedMs() const { return elapsedUs() / 1000; }
    
    /**
     * Get final duration (only valid after destruction or explicit stop).
     */
    int64_t durationUs() const { return durationUs_; }

private:
    const char* name_;
    bool logOnDestroy_;
    std::chrono::steady_clock::time_point start_;
    int64_t durationUs_ = 0;
};

// Convenience macros
#define STT_TIMER_CONCAT_INNER(left, right) left##right
#define STT_TIMER_CONCAT(left, right) STT_TIMER_CONCAT_INNER(left, right)
#define PROFILE_SCOPE(name) stt::ScopedTimer STT_TIMER_CONCAT(_timer_, __LINE__)(name)
#define PROFILE_SCOPE_SILENT(name) stt::ScopedTimer STT_TIMER_CONCAT(_timer_, __LINE__)(name, false)

#ifdef VERBOSE_LOGGING
#define PROFILE_VERBOSE(name) PROFILE_SCOPE(name)
#else
#define PROFILE_VERBOSE(name) ((void)0)
#endif

} // namespace stt

#endif // SCOPED_TIMER_H
