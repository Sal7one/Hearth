#ifndef STT_ENGINE_ROUTER_H
#define STT_ENGINE_ROUTER_H

#include "engine_interface.h"
#include "common/lease_registry.h"
#include "stt_config.h"
#include <cstdint>
#include <memory>
#include <utility>

namespace stt {

enum class EngineType {
    WHISPER = 1,
    VOSK = 2,
    ONNX = 4
};

/**
 * Routes requests to the appropriate engine implementation.
 */
class EngineRouter {
    struct EngineEntry {
        EngineEntry(EngineType type, std::unique_ptr<IEngine> engine)
            : type(type), engine(std::move(engine)) {}

        const EngineType type;
        std::unique_ptr<IEngine> engine;
    };

    using EngineRegistry = concurrency::LeaseRegistry<EngineEntry, std::int64_t>;

public:
    class EngineLease {
    public:
        EngineLease() = default;
        EngineLease(EngineLease&&) noexcept = default;
        EngineLease& operator=(EngineLease&&) noexcept = default;

        IEngine* operator->() const noexcept { return lease_->engine.get(); }
        explicit operator bool() const noexcept { return static_cast<bool>(lease_); }

        EngineLease(const EngineLease&) = delete;
        EngineLease& operator=(const EngineLease&) = delete;

    private:
        friend class EngineRouter;
        explicit EngineLease(EngineRegistry::Lease lease)
            : lease_(std::move(lease)) {}

        EngineRegistry::Lease lease_;
    };

    static EngineRouter& getInstance();
    
    // Get capability mask
    int getCapabilityMask() const { return STT_CAPABILITY_MASK; }
    
    // Engine management
    int64_t createEngine(EngineType type);
    EngineLease getEngine(int64_t handle, EngineType expectedType) const;
    // Invalidates immediately, then waits for outstanding leases. Never call
    // from a thread that still owns a lease for the same handle.
    void destroyEngine(int64_t handle, EngineType expectedType);
    void destroyAll();

    // Thread-safe cancellation. Flips the cancel flag on every live
    // engine; running operations observe it at their next safe point.
    // Does NOT destroy engines.
    void cancelAll();

    // Check availability
    bool isEngineAvailable(EngineType type) const;

#if defined(STT_ENGINE_ROUTER_TESTING)
    int64_t insertEngineForTesting(EngineType type, std::unique_ptr<IEngine> engine) {
        return engines_.insert(
            std::make_unique<EngineEntry>(type, std::move(engine)));
    }
#endif

private:
    EngineRouter() = default;
    ~EngineRouter();
    
    EngineRegistry engines_;
};

} // namespace stt

#endif // STT_ENGINE_ROUTER_H
