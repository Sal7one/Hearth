#include "engine_router.h"
#include "logging.h"

// Check generated config or definitions
#if defined(WITH_WHISPER) && WITH_WHISPER
#include "whisper/whisper_engine.h"
#endif

#if defined(WITH_VOSK) && WITH_VOSK
#include "vosk/vosk_engine.h"
#endif

#if defined(WITH_ONNX) && WITH_ONNX
#include "onnx/onnx_engine.h"
#endif

namespace stt {

EngineRouter& EngineRouter::getInstance() {
    static EngineRouter instance;
    return instance;
}

EngineRouter::~EngineRouter() {
    try {
        destroyAll();
    } catch (...) {
        // Registry-owned concrete engine destructors perform a final blocking
        // drain, so member destruction remains safe even if bulk setup fails.
        LOG_E(TAG_ROUTER, "Unexpected exception during router destruction");
    }
}

int64_t EngineRouter::createEngine(EngineType type) {
    std::unique_ptr<IEngine> engine;
    
    switch (type) {
#if defined(WITH_WHISPER) && WITH_WHISPER
        case EngineType::WHISPER:
            engine = std::make_unique<WhisperEngine>();
            break;
#else
        case EngineType::WHISPER:
            LOG_E(TAG_ROUTER, "Whisper support not compiled in");
            break;
#endif
#if defined(WITH_VOSK) && WITH_VOSK
        case EngineType::VOSK:
            engine = std::make_unique<VoskEngine>();
            break;
#else
        case EngineType::VOSK:
            LOG_E(TAG_ROUTER, "Vosk support not compiled in");
            break;
#endif
#if defined(WITH_ONNX) && WITH_ONNX
        case EngineType::ONNX:
            engine = std::make_unique<OnnxEngine>();
            break;
#else
        case EngineType::ONNX:
            LOG_E(TAG_ROUTER, "ONNX support not compiled in");
            break;
#endif
        default:
            LOG_E(TAG_ROUTER, "Unknown engine type: %d", static_cast<int>(type));
            return 0;
    }
    
    if (!engine) {
        LOG_E(TAG_ROUTER, "Failed to create engine type: %d", static_cast<int>(type));
        return 0;
    }
    
    auto entry = std::make_unique<EngineEntry>(type, std::move(engine));
    const int64_t handle = engines_.insert(std::move(entry));
    if (handle == EngineRegistry::invalidHandle()) {
        LOG_E(TAG_ROUTER, "Engine handle registry exhausted");
        return 0;
    }
    
    LOG_I(TAG_ROUTER, "Created engine handle: %ld, type: %d", static_cast<long>(handle), static_cast<int>(type));
    return handle;
}

EngineRouter::EngineLease EngineRouter::getEngine(
    int64_t handle,
    EngineType expectedType
) const {
    auto lease = engines_.acquire(handle);
    if (!lease || lease->type != expectedType) return {};
    return EngineLease(std::move(lease));
}

void EngineRouter::destroyEngine(int64_t handle, EngineType expectedType) {
    // Type-check through a strong lease before invalidating the handle. Engine
    // types are immutable and handles are never reused, so dropping this lease
    // before retire() cannot turn a wrong-type handle into a valid target.
    auto lease = engines_.acquire(handle);
    if (!lease || lease->type != expectedType) return;
    lease = {};

    auto retired = engines_.retire(handle);
    if (!retired) return;

    // Unpublish first, then ask in-flight work to stop before waiting for its
    // strong leases. No registry mutex is held while engine code executes.
    try {
        retired.signalTarget()->engine->beginShutdown();
    } catch (...) {
        LOG_E(TAG_ROUTER, "Engine shutdown signal threw for handle: %ld",
              static_cast<long>(handle));
    }
    try {
        auto engine = std::move(retired).lockExclusive();
        engine->engine->release();
    } catch (...) {
        LOG_E(TAG_ROUTER, "Engine cleanup threw for handle: %ld",
              static_cast<long>(handle));
    }
    LOG_I(TAG_ROUTER, "Destroyed engine handle: %ld", static_cast<long>(handle));
}

void EngineRouter::destroyAll() {
    auto retired = engines_.retireAll();
    for (auto& engine : retired) {
        try {
            engine.signalTarget()->engine->beginShutdown();
        } catch (...) {
            LOG_E(TAG_ROUTER, "Engine shutdown signal threw during bulk destroy");
        }
    }
    for (auto& engine : retired) {
        try {
            auto exclusive = std::move(engine).lockExclusive();
            exclusive->engine->release();
        } catch (...) {
            LOG_E(TAG_ROUTER, "Engine cleanup threw during bulk destroy");
        }
    }
    LOG_I(TAG_ROUTER, "Destroyed %zu engine(s)", retired.size());
}

void EngineRouter::cancelAll() {
    auto engines = engines_.snapshot();
    for (auto& engine : engines) {
        try {
            engine->engine->cancel();
        } catch (...) {
            LOG_E(TAG_ROUTER, "Engine cancellation threw");
        }
    }
    LOG_I(TAG_ROUTER, "Cancelled %zu engine(s)", engines.size());
}

bool EngineRouter::isEngineAvailable(EngineType type) const {
    int mask = STT_CAPABILITY_MASK;
    return (mask & static_cast<int>(type)) != 0;
}

} // namespace stt
