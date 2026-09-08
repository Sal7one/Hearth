#ifndef SECURE_MEMORY_H
#define SECURE_MEMORY_H

#include <cstddef>
#include <cstdlib>
#include <limits>
#include <memory>
#include <new>
#include <string>
#include <type_traits>
#include <vector>

namespace stt {

template<typename T>
inline constexpr bool isSecureMemoryType =
    std::is_trivially_copyable<T>::value && std::is_trivially_destructible<T>::value;

/**
 * Best-effort in-process zeroization for byte-like, trivial storage.
 *
 * This does not prevent copies, paging, crash-dump exposure, or privileged
 * inspection. It is appropriate only when a caller has identified genuinely
 * sensitive transient data; model weights and checksums are not secrets.
 */
class SecureMemory {
public:
    static void secureZero(void* ptr, std::size_t size) noexcept {
        if (!ptr || size == 0) return;
        volatile auto* bytes = static_cast<volatile unsigned char*>(ptr);
        while (size-- != 0) *bytes++ = 0;
    }

    // ptr must have been returned by malloc/calloc/realloc.
    static void secureFree(void* ptr, std::size_t size) noexcept {
        secureZero(ptr, size);
        std::free(ptr);
    }

    static void secureZeroString(std::string& value) noexcept {
        if (!value.empty()) secureZero(value.data(), value.size());
        value.clear();
    }

    template<typename T>
    static void secureZeroVector(std::vector<T>& values) noexcept {
        static_assert(
            isSecureMemoryType<T>,
            "secureZeroVector requires trivially copyable, trivially destructible elements"
        );
        if (!values.empty()) secureZero(values.data(), values.size() * sizeof(T));
        values.clear();
    }
};

template<typename T>
class SecureBuffer {
    static_assert(
        isSecureMemoryType<T>,
        "SecureBuffer requires a trivially copyable, trivially destructible element type"
    );

public:
    explicit SecureBuffer(std::size_t count) noexcept {
        if (count == 0 || count > std::numeric_limits<std::size_t>::max() / sizeof(T)) return;
        data_ = new (std::nothrow) T[count]();
        if (data_) size_ = count;
    }

    ~SecureBuffer() { destroy(); }

    SecureBuffer(const SecureBuffer&) = delete;
    SecureBuffer& operator=(const SecureBuffer&) = delete;

    SecureBuffer(SecureBuffer&& other) noexcept
        : data_(other.data_), size_(other.size_) {
        other.data_ = nullptr;
        other.size_ = 0;
    }

    SecureBuffer& operator=(SecureBuffer&& other) noexcept {
        if (this != &other) {
            destroy();
            data_ = other.data_;
            size_ = other.size_;
            other.data_ = nullptr;
            other.size_ = 0;
        }
        return *this;
    }

    T* data() noexcept { return data_; }
    const T* data() const noexcept { return data_; }
    std::size_t size() const noexcept { return size_; }
    std::size_t sizeBytes() const noexcept { return size_ * sizeof(T); }
    bool valid() const noexcept { return data_ != nullptr; }

private:
    void destroy() noexcept {
        if (data_) {
            SecureMemory::secureZero(data_, sizeBytes());
            delete[] data_;
        }
        data_ = nullptr;
        size_ = 0;
    }

    T* data_ = nullptr;
    std::size_t size_ = 0;
};

template<typename T>
struct SecureDeleter {
    static_assert(
        isSecureMemoryType<T>,
        "SecureDeleter requires a trivially copyable, trivially destructible element type"
    );

    std::size_t count = 0;

    explicit SecureDeleter(std::size_t elementCount = 0) noexcept : count(elementCount) {}

    void operator()(T* ptr) const noexcept {
        if (!ptr) return;
        if (count <= std::numeric_limits<std::size_t>::max() / sizeof(T)) {
            SecureMemory::secureZero(ptr, count * sizeof(T));
        }
        delete[] ptr;
    }
};

template<typename T>
using SecureUniquePtr = std::unique_ptr<T[], SecureDeleter<T>>;

template<typename T>
SecureUniquePtr<T> makeSecureArray(std::size_t count) noexcept {
    static_assert(
        isSecureMemoryType<T>,
        "makeSecureArray requires a trivially copyable, trivially destructible element type"
    );
    if (count == 0 || count > std::numeric_limits<std::size_t>::max() / sizeof(T)) {
        return SecureUniquePtr<T>(nullptr, SecureDeleter<T>());
    }
    return SecureUniquePtr<T>(new (std::nothrow) T[count](), SecureDeleter<T>(count));
}

} // namespace stt

#endif // SECURE_MEMORY_H
