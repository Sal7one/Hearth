#ifndef TTS_ROUTER_H
#define TTS_ROUTER_H

#include "tts_engine_interface.h"
#include "../common/native_registry.h"

#include <cstdint>
#include <memory>

namespace common_jni {
namespace tts {

/**
 * TTS Engine Router - mirrors STT EngineRouter pattern.
 * 
 * Thread-safe singleton for managing TTS engine instances.
 * Handles engine lifecycle, handle management, and routing.
 */
class TtsRouter {
    using EngineRegistry =
        jni::NativeRegistry<ITtsEngine, jni::NativeHandleKind::TtsEngine>;

public:
    using EngineLease = EngineRegistry::SerializedLease;
    using EngineSignalLease = EngineRegistry::Lease;

    static TtsRouter& getInstance() {
        static TtsRouter instance;
        return instance;
    }
    
    // =========================================================================
    // Engine Lifecycle
    // =========================================================================
    
    /**
     * Create a new engine instance.
     * 
     * @param type Engine type to create
     * @return Handle (>0) or -1 on error
     */
    int64_t createEngine(TtsEngineType type) {
        if (!isTtsEngineAvailable(type)) {
            return -1;
        }
        
        auto engine = tts::createTtsEngine(type);
        if (!engine) {
            return -1;
        }
        
        const int64_t handle = engines_.store(std::move(engine));
        return handle > 0 ? handle : -1;
    }
    
    /**
     * Get engine by handle.
     * 
     * @param handle Engine handle
     * @return Serialized lifetime lease, or an empty lease if not found
     */
    EngineLease getEngine(int64_t handle) {
        return engines_.acquireSerialized(handle);
    }

    /** Lifetime-only lease for methods explicitly documented thread-safe. */
    EngineSignalLease getEngineForSignal(int64_t handle) {
        return engines_.acquire(handle);
    }
    
    /**
     * Destroy engine and release resources.
     * 
     * @param handle Engine handle
     * @return true if engine was found and destroyed
     */
    bool destroyEngine(int64_t handle) {
        auto retired = engines_.retire(handle);
        if (!retired) {
            return false;
        }

        // cancel() is explicitly thread-safe and wakes a running synthesis so
        // exclusive teardown does not wait for an avoidably long operation.
        try {
            retired.signalTarget()->cancel();
        } catch (...) {
        }

        auto exclusive = std::move(retired).lockExclusive();
        try {
            exclusive->release();
        } catch (...) {
        }
        (void)exclusive.extract();
        return true;
    }
    
    /**
     * Destroy all engines.
     */
    void destroyAllEngines() {
        auto retired = engines_.retireAll();
        for (auto& engine : retired) {
            try {
                engine.signalTarget()->cancel();
            } catch (...) {
            }
        }
        for (auto& engine : retired) {
            auto exclusive = std::move(engine).lockExclusive();
            try {
                exclusive->release();
            } catch (...) {
            }
            (void)exclusive.extract();
        }
    }

    /**
     * Thread-safe cancel: flips the cancel flag on every live engine.
     * Running synthesize()/synthesizeStreaming() calls observe it at
     * their next safe point. Does NOT destroy engines.
     */
    void cancelAll() {
        auto engines = engines_.snapshot();
        for (auto& engine : engines) {
            if (engine) engine->cancel();
        }
    }
    
    // =========================================================================
    // Engine Queries
    // =========================================================================
    
    /**
     * Check if engine type is available.
     */
    bool isEngineAvailable(TtsEngineType type) const {
        return tts::isTtsEngineAvailable(type);
    }
    
    /**
     * Get list of available engine types.
     */
    std::vector<TtsEngineType> getAvailableEngines() const {
        return tts::getAvailableTtsEngines();
    }
    
    /**
     * Get number of active engine instances.
     */
    size_t getActiveEngineCount() const {
        return engines_.size();
    }
    
private:
    TtsRouter() = default;
    ~TtsRouter() { destroyAllEngines(); }
    
    // Non-copyable
    TtsRouter(const TtsRouter&) = delete;
    TtsRouter& operator=(const TtsRouter&) = delete;
    
    EngineRegistry engines_;
};

} // namespace tts
} // namespace common_jni

#endif // TTS_ROUTER_H
