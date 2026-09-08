#ifndef COMMON_NATIVE_REGISTRY_H
#define COMMON_NATIVE_REGISTRY_H

// Portable typed handle registry with lifetime-bearing borrows.
//
// This header is part of the reusable core and must compile off-Android
// (iOS, macOS, Linux, Windows host tests): it includes no <jni.h> and no
// <android/...> headers. Handles are int64_t, which is bit-identical to
// Android's jlong, so JNI adapters pass handles through unchanged. The
// historical `jni` namespace name is kept so existing call sites stay valid;
// nothing in this header depends on JNI.
//
// JNI-specific convenience macros live in jni/native_registry.h, which
// forwards here.

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <shared_mutex>
#include <unordered_map>
#include <utility>
#include <vector>

namespace jni {

/** Public handle domains. Values are persisted only for the process lifetime. */
enum class NativeHandleKind : std::uint16_t {
    Invalid = 0,
    Vad = 1,
    FfmpegStream = 2,
    TtsEngine = 3,
    VisionStream = 4,
    VisionFrame = 5,
    VisionDetector = 6,
};

/**
 * Generates positive, monotonic, non-reused int64_t handles with a domain tag.
 * CounterBits is configurable so exhaustion can be tested without billions of
 * allocations; production registries use the full 48-bit counter.
 */
template <NativeHandleKind Kind, unsigned CounterBits = 48>
class NativeHandleSource {
    static_assert(Kind != NativeHandleKind::Invalid, "A real handle kind is required");
    static_assert(CounterBits > 0 && CounterBits <= 48, "Counter must fit below the kind tag");

public:
    std::int64_t next() noexcept {
        std::uint64_t current = next_.load(std::memory_order_relaxed);
        while (current <= kMaxCounter) {
            if (next_.compare_exchange_weak(
                    current, current + 1,
                    std::memory_order_relaxed,
                    std::memory_order_relaxed)) {
                const std::uint64_t encoded =
                    (static_cast<std::uint64_t>(Kind) << kKindShift) | current;
                return static_cast<std::int64_t>(encoded);
            }
        }
        return 0;
    }

    static bool matches(std::int64_t handle) noexcept {
        if (handle <= 0) return false;
        const auto encoded = static_cast<std::uint64_t>(handle);
        const auto kind = static_cast<NativeHandleKind>(encoded >> kKindShift);
        const std::uint64_t counter = encoded & kCounterMask;
        return kind == Kind && counter != 0 && counter <= kMaxCounter;
    }

private:
    static constexpr unsigned kKindShift = 48;
    static constexpr std::uint64_t kCounterMask = (std::uint64_t{1} << kKindShift) - 1;
    static constexpr std::uint64_t kMaxCounter =
        (std::uint64_t{1} << CounterBits) - 1;

    std::atomic<std::uint64_t> next_{1};
};

template <NativeHandleKind Kind>
NativeHandleSource<Kind>& nativeHandleSource() {
    static NativeHandleSource<Kind> source;
    return source;
}

/**
 * Typed native handle table with lifetime-bearing borrows.
 *
 * acquire() permits concurrent access when T is independently thread-safe.
 * acquireSerialized() additionally holds a per-object mutex and is the safe
 * default for mutable shared objects. retire() atomically unpublishes a handle;
 * exclusive cleanup then waits for every outstanding borrow without holding
 * the table mutex. Public handles carry a domain tag and never alias a handle
 * from another native subsystem.
 */
template <typename T, NativeHandleKind Kind>
class NativeRegistry {
    struct Node {
        explicit Node(std::unique_ptr<T> value) : object(std::move(value)) {}

        std::unique_ptr<T> object;
        mutable std::shared_mutex lifetime;
        mutable std::mutex operation;
    };

public:
    class Retired;

    class Lease {
    public:
        Lease() = default;
        Lease(Lease&&) noexcept = default;
        Lease& operator=(Lease&& other) noexcept {
            if (this == &other) return *this;
            lock_ = {};
            node_.reset();
            node_ = std::move(other.node_);
            lock_ = std::move(other.lock_);
            return *this;
        }

        T* get() const noexcept { return node_ ? node_->object.get() : nullptr; }
        T* operator->() const noexcept { return get(); }
        T& operator*() const noexcept { return *get(); }
        explicit operator bool() const noexcept { return get() != nullptr; }

        Lease(const Lease&) = delete;
        Lease& operator=(const Lease&) = delete;

    private:
        friend class NativeRegistry;
        friend class SerializedLease;
        explicit Lease(std::shared_ptr<Node> node)
            : node_(std::move(node)), lock_(node_->lifetime) {}

        std::shared_ptr<Node> node_;
        std::shared_lock<std::shared_mutex> lock_;
    };

    class SerializedLease {
    public:
        SerializedLease() = default;
        SerializedLease(SerializedLease&&) noexcept = default;
        SerializedLease& operator=(SerializedLease&& other) noexcept {
            if (this == &other) return *this;
            operation_ = {};
            lifetime_ = {};
            lifetime_ = std::move(other.lifetime_);
            operation_ = std::move(other.operation_);
            return *this;
        }

        T* get() const noexcept { return lifetime_.get(); }
        T* operator->() const noexcept { return get(); }
        T& operator*() const noexcept { return *lifetime_.get(); }
        explicit operator bool() const noexcept { return static_cast<bool>(lifetime_); }

        SerializedLease(const SerializedLease&) = delete;
        SerializedLease& operator=(const SerializedLease&) = delete;

    private:
        friend class NativeRegistry;
        explicit SerializedLease(Lease lifetime)
            : lifetime_(std::move(lifetime)),
              operation_(lifetime_.node_->operation) {}

        Lease lifetime_;
        std::unique_lock<std::mutex> operation_;
    };

    class ExclusiveLease {
    public:
        ExclusiveLease() = default;
        ExclusiveLease(ExclusiveLease&&) noexcept = default;
        ExclusiveLease& operator=(ExclusiveLease&& other) noexcept {
            if (this == &other) return *this;
            lock_ = {};
            node_.reset();
            node_ = std::move(other.node_);
            lock_ = std::move(other.lock_);
            return *this;
        }

        T* get() const noexcept { return node_ ? node_->object.get() : nullptr; }
        T* operator->() const noexcept { return get(); }
        T& operator*() const noexcept { return *get(); }
        explicit operator bool() const noexcept { return get() != nullptr; }

        std::unique_ptr<T> extract() noexcept {
            return node_ ? std::move(node_->object) : nullptr;
        }

        ExclusiveLease(const ExclusiveLease&) = delete;
        ExclusiveLease& operator=(const ExclusiveLease&) = delete;

    private:
        friend class Retired;
        explicit ExclusiveLease(std::shared_ptr<Node> node)
            : node_(std::move(node)), lock_(node_->lifetime) {}

        std::shared_ptr<Node> node_;
        std::unique_lock<std::shared_mutex> lock_;
    };

    class Retired {
    public:
        Retired() = default;
        Retired(Retired&&) noexcept = default;
        Retired& operator=(Retired&&) noexcept = default;

        /** Only call methods explicitly documented as concurrently safe. */
        T* signalTarget() const noexcept {
            return node_ ? node_->object.get() : nullptr;
        }

        explicit operator bool() const noexcept { return signalTarget() != nullptr; }

        ExclusiveLease lockExclusive() && {
            if (!node_) return {};
            return ExclusiveLease(std::move(node_));
        }

        Retired(const Retired&) = delete;
        Retired& operator=(const Retired&) = delete;

    private:
        friend class NativeRegistry;
        explicit Retired(std::shared_ptr<Node> node) : node_(std::move(node)) {}

        std::shared_ptr<Node> node_;
    };

    std::int64_t store(std::unique_ptr<T> object) {
        if (!object) return 0;
        const std::int64_t handle = nativeHandleSource<Kind>().next();
        if (handle == 0) return 0;
        auto node = std::make_shared<Node>(std::move(object));

        std::lock_guard<std::mutex> lock(mutex_);
        const auto [position, inserted] = objects_.emplace(handle, std::move(node));
        (void)position;
        if (!inserted) return 0;
        return handle;
    }

    Lease acquire(std::int64_t handle) const {
        if (!NativeHandleSource<Kind>::matches(handle)) return {};
        std::lock_guard<std::mutex> lock(mutex_);
        const auto found = objects_.find(handle);
        return found == objects_.end() ? Lease{} : Lease(found->second);
    }

    SerializedLease acquireSerialized(std::int64_t handle) const {
        auto lifetime = acquire(handle);
        return lifetime ? SerializedLease(std::move(lifetime)) : SerializedLease{};
    }

    Retired retire(std::int64_t handle) {
        if (!NativeHandleSource<Kind>::matches(handle)) return {};
        std::lock_guard<std::mutex> lock(mutex_);
        const auto found = objects_.find(handle);
        if (found == objects_.end()) return {};
        std::shared_ptr<Node> node = std::move(found->second);
        objects_.erase(found);
        return Retired(std::move(node));
    }

    bool remove(std::int64_t handle) {
        auto retired = retire(handle);
        if (!retired) return false;
        auto exclusive = std::move(retired).lockExclusive();
        auto object = exclusive.extract();
        return object != nullptr;
    }

    std::unique_ptr<T> extract(std::int64_t handle) {
        auto retired = retire(handle);
        if (!retired) return nullptr;
        auto exclusive = std::move(retired).lockExclusive();
        return exclusive.extract();
    }

    std::vector<Retired> retireAll() {
        std::vector<Retired> retired;
        std::lock_guard<std::mutex> lock(mutex_);
        retired.reserve(objects_.size());
        for (auto& [handle, node] : objects_) {
            (void)handle;
            retired.push_back(Retired(std::move(node)));
        }
        objects_.clear();
        return retired;
    }

    std::vector<Lease> snapshot() const {
        std::vector<Lease> leases;
        std::lock_guard<std::mutex> lock(mutex_);
        leases.reserve(objects_.size());
        for (const auto& [handle, node] : objects_) {
            (void)handle;
            leases.push_back(Lease(node));
        }
        return leases;
    }

    void clear() {
        auto retired = retireAll();
        for (auto& item : retired) {
            auto exclusive = std::move(item).lockExclusive();
            (void)exclusive.extract();
        }
    }

    bool exists(std::int64_t handle) const {
        if (!NativeHandleSource<Kind>::matches(handle)) return false;
        std::lock_guard<std::mutex> lock(mutex_);
        return objects_.find(handle) != objects_.end();
    }

    std::size_t size() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return objects_.size();
    }

    NativeRegistry() = default;
    NativeRegistry(const NativeRegistry&) = delete;
    NativeRegistry& operator=(const NativeRegistry&) = delete;

private:
    mutable std::mutex mutex_;
    std::unordered_map<std::int64_t, std::shared_ptr<Node>> objects_;
};

/** Default guard: lifetime-safe and serialized for mutable shared state. */
template <typename T, NativeHandleKind Kind>
class HandleGuard {
public:
    HandleGuard(const NativeRegistry<T, Kind>& registry, std::int64_t handle)
        : lease_(registry.acquireSerialized(handle)) {}

    T* get() const noexcept { return lease_.get(); }
    T* operator->() const noexcept { return get(); }
    T& operator*() const noexcept { return *get(); }
    bool valid() const noexcept { return static_cast<bool>(lease_); }
    explicit operator bool() const noexcept { return valid(); }

    HandleGuard(const HandleGuard&) = delete;
    HandleGuard& operator=(const HandleGuard&) = delete;

private:
    typename NativeRegistry<T, Kind>::SerializedLease lease_;
};

} // namespace jni

#endif // COMMON_NATIVE_REGISTRY_H
