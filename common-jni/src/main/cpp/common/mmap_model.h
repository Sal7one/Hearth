#ifndef MMAP_MODEL_H
#define MMAP_MODEL_H

#include "model_integrity.h"

#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <fcntl.h>
#include <limits>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <utility>

namespace stt {

/**
 * Read-only, bounded mapping for an already trusted model snapshot.
 *
 * Mapping a file does not make a downstream parser zero-copy, and MAP_PRIVATE
 * is not an immutable snapshot. External or concurrently writable inputs must
 * first be copied and verified in app-private storage.
 */
class MmapModel {
public:
    MmapModel() = default;
    ~MmapModel() { unload(); }

    MmapModel(const MmapModel&) = delete;
    MmapModel& operator=(const MmapModel&) = delete;

    MmapModel(MmapModel&& other) noexcept { moveFrom(other); }

    MmapModel& operator=(MmapModel&& other) noexcept {
        if (this != &other) {
            unload();
            moveFrom(other);
        }
        return *this;
    }

    bool load(const std::string& path) {
        return load(path, ModelIntegrityLimits::kMaxSingleFileBytes);
    }

    bool load(const std::string& path, std::uint64_t maximumSize) {
        unload();
        lastError_.clear();
        lastErrno_ = 0;

        if (path.empty() || path.find('\0') != std::string::npos || maximumSize == 0 ||
            maximumSize > ModelIntegrityLimits::kMaxSingleFileBytes) {
            return fail("Invalid model path or size limit", EINVAL);
        }

        fd_ = ::open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
        if (fd_ < 0) return fail("Failed to open model without following symlinks", errno);

        struct stat before {};
        if (::fstat(fd_, &before) != 0) {
            const int savedErrno = errno;
            unload();
            return fail("Failed to stat model", savedErrno);
        }
        if (!S_ISREG(before.st_mode)) {
            unload();
            return fail("Model is not a regular file", 0);
        }
        if (before.st_size <= 0) {
            unload();
            return fail("Model is empty", 0);
        }

        const auto fileSize = static_cast<std::uint64_t>(before.st_size);
        if (fileSize > maximumSize ||
            fileSize > static_cast<std::uint64_t>(std::numeric_limits<std::size_t>::max())) {
            unload();
            return fail("Model exceeds configured mapping limit", EFBIG);
        }
        size_ = static_cast<std::size_t>(fileSize);

        data_ = ::mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd_, 0);
        if (data_ == MAP_FAILED) {
            const int savedErrno = errno;
            data_ = nullptr;
            unload();
            return fail("Failed to map model", savedErrno);
        }

        struct stat after {};
        if (::fstat(fd_, &after) != 0) {
            const int savedErrno = errno;
            unload();
            return fail("Failed to stat mapped model", savedErrno);
        }
        if (!sameSnapshot(before, after)) {
            unload();
            return fail("Model changed while it was being mapped", 0);
        }
        snapshot_ = after;

        // A hint only. Mapping validity does not depend on kernel advice support.
        (void)::madvise(data_, size_, MADV_SEQUENTIAL);
        return true;
    }

    void unload() noexcept {
        if (data_) ::munmap(data_, size_);
        if (fd_ >= 0) ::close(fd_);
        data_ = nullptr;
        size_ = 0;
        fd_ = -1;
        snapshot_ = {};
    }

    const void* data() const noexcept { return data_; }
    std::size_t size() const noexcept { return size_; }
    bool isLoaded() const noexcept { return data_ != nullptr; }
    const std::string& getLastError() const noexcept { return lastError_; }
    int getLastErrno() const noexcept { return lastErrno_; }

    bool snapshotUnchanged() const noexcept {
        if (fd_ < 0) return false;
        struct stat current {};
        return ::fstat(fd_, &current) == 0 && sameSnapshot(snapshot_, current);
    }

    bool prefetch() {
        if (!isLoaded()) return fail("Model is not mapped", EINVAL);
        if (::madvise(data_, size_, MADV_WILLNEED) != 0) {
            return fail("Failed to prefetch mapped model", errno);
        }
        return true;
    }

    bool release() {
        if (!isLoaded()) return fail("Model is not mapped", EINVAL);
        if (::madvise(data_, size_, MADV_DONTNEED) != 0) {
            return fail("Failed to release mapped model pages", errno);
        }
        return true;
    }

private:
    bool fail(const char* message, int systemError) {
        lastError_ = message;
        lastErrno_ = systemError;
        return false;
    }

    static bool sameSnapshot(const struct stat& first, const struct stat& second) noexcept {
        if (first.st_dev != second.st_dev || first.st_ino != second.st_ino ||
            first.st_mode != second.st_mode || first.st_size != second.st_size) {
            return false;
        }
#if defined(__APPLE__)
        return first.st_mtimespec.tv_sec == second.st_mtimespec.tv_sec &&
               first.st_mtimespec.tv_nsec == second.st_mtimespec.tv_nsec &&
               first.st_ctimespec.tv_sec == second.st_ctimespec.tv_sec &&
               first.st_ctimespec.tv_nsec == second.st_ctimespec.tv_nsec;
#else
        return first.st_mtim.tv_sec == second.st_mtim.tv_sec &&
               first.st_mtim.tv_nsec == second.st_mtim.tv_nsec &&
               first.st_ctim.tv_sec == second.st_ctim.tv_sec &&
               first.st_ctim.tv_nsec == second.st_ctim.tv_nsec;
#endif
    }

    void moveFrom(MmapModel& other) noexcept {
        data_ = other.data_;
        size_ = other.size_;
        fd_ = other.fd_;
        snapshot_ = other.snapshot_;
        lastError_ = std::move(other.lastError_);
        lastErrno_ = other.lastErrno_;

        other.data_ = nullptr;
        other.size_ = 0;
        other.fd_ = -1;
        other.snapshot_ = {};
        other.lastErrno_ = 0;
    }

    void* data_ = nullptr;
    std::size_t size_ = 0;
    int fd_ = -1;
    struct stat snapshot_ {};
    std::string lastError_;
    int lastErrno_ = 0;
};

} // namespace stt

#endif // MMAP_MODEL_H
