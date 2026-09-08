#ifndef STT_LEASE_REGISTRY_H
#define STT_LEASE_REGISTRY_H

#include <cstddef>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <shared_mutex>
#include <type_traits>
#include <unordered_map>
#include <utility>
#include <vector>

namespace stt::concurrency {

/**
 * A dependency-free, thread-safe handle table with lifetime-bearing borrows.
 *
 * acquire() returns a shared lease held for the complete operation. retire()
 * immediately invalidates the public handle, while the returned Retired token
 * keeps the object alive and can signal cancellation before waiting for an
 * exclusive cleanup lease. Handles are monotonic and never reused.
 */
template <typename T, typename Handle = std::int64_t>
class LeaseRegistry {
    static_assert(std::is_integral_v<Handle>, "LeaseRegistry handles must be integral");

    struct Node {
        explicit Node(std::unique_ptr<T> object) : object(std::move(object)) {}

        std::unique_ptr<T> object;
        mutable std::shared_mutex gate;
    };

public:
    class Retired;

    class Lease {
    public:
        Lease() = default;
        Lease(Lease&&) noexcept = default;
        Lease& operator=(Lease&& other) noexcept {
            if (this == &other) return *this;
            // The lock must release before its Node owner. The declaration
            // order alone does not make move-assignment safe.
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
        friend class LeaseRegistry;
        explicit Lease(std::shared_ptr<Node> node)
            : node_(std::move(node)), lock_(node_->gate) {}

        std::shared_ptr<Node> node_;
        std::shared_lock<std::shared_mutex> lock_;
    };

    class ExclusiveLease {
    public:
        ExclusiveLease() = default;
        ExclusiveLease(ExclusiveLease&&) noexcept = default;
        ExclusiveLease& operator=(ExclusiveLease&& other) noexcept {
            if (this == &other) return *this;
            // See Lease::operator=(): never destroy a shared_mutex while this
            // object still owns a lock referring to it.
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

        ExclusiveLease(const ExclusiveLease&) = delete;
        ExclusiveLease& operator=(const ExclusiveLease&) = delete;

    private:
        friend class Retired;
        explicit ExclusiveLease(std::shared_ptr<Node> node)
            : node_(std::move(node)), lock_(node_->gate) {}

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
        friend class LeaseRegistry;
        explicit Retired(std::shared_ptr<Node> node) : node_(std::move(node)) {}

        std::shared_ptr<Node> node_;
    };

    Handle insert(std::unique_ptr<T> object) {
        if (!object) return invalidHandle();
        auto node = std::make_shared<Node>(std::move(object));

        std::lock_guard<std::mutex> lock(mutex_);
        if (nextHandle_ == invalidHandle()) return invalidHandle();
        const Handle handle = nextHandle_;
        nextHandle_ = handle == std::numeric_limits<Handle>::max()
            ? invalidHandle()
            : static_cast<Handle>(handle + 1);
        nodes_.emplace(handle, std::move(node));
        return handle;
    }

    Lease acquire(Handle handle) const {
        if (handle == invalidHandle()) return {};
        std::lock_guard<std::mutex> lock(mutex_);
        const auto found = nodes_.find(handle);
        if (found == nodes_.end()) return {};
        // Acquire the shared gate while the map lock still proves this node is
        // live. retire() cannot remove it and win the exclusive gate in between.
        return Lease(found->second);
    }

    Retired retire(Handle handle) {
        if (handle == invalidHandle()) return {};
        std::lock_guard<std::mutex> lock(mutex_);
        const auto found = nodes_.find(handle);
        if (found == nodes_.end()) return {};
        std::shared_ptr<Node> node = std::move(found->second);
        nodes_.erase(found);
        return Retired(std::move(node));
    }

    std::vector<Retired> retireAll() {
        std::vector<Retired> retired;
        std::lock_guard<std::mutex> lock(mutex_);
        retired.reserve(nodes_.size());
        for (auto& [handle, node] : nodes_) {
            (void)handle;
            retired.push_back(Retired(std::move(node)));
        }
        nodes_.clear();
        return retired;
    }

    std::vector<Lease> snapshot() const {
        std::vector<Lease> leases;
        std::lock_guard<std::mutex> lock(mutex_);
        leases.reserve(nodes_.size());
        for (const auto& [handle, node] : nodes_) {
            (void)handle;
            leases.push_back(Lease(node));
        }
        return leases;
    }

    bool contains(Handle handle) const {
        if (handle == invalidHandle()) return false;
        std::lock_guard<std::mutex> lock(mutex_);
        return nodes_.find(handle) != nodes_.end();
    }

    std::size_t size() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return nodes_.size();
    }

    static constexpr Handle invalidHandle() noexcept { return Handle{0}; }

    LeaseRegistry() = default;
    LeaseRegistry(const LeaseRegistry&) = delete;
    LeaseRegistry& operator=(const LeaseRegistry&) = delete;

private:
    mutable std::mutex mutex_;
    std::unordered_map<Handle, std::shared_ptr<Node>> nodes_;
    Handle nextHandle_ = Handle{1};
};

} // namespace stt::concurrency

#endif // STT_LEASE_REGISTRY_H
