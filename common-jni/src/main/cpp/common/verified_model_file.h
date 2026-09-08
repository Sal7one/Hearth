#ifndef STT_VERIFIED_MODEL_FILE_H
#define STT_VERIFIED_MODEL_FILE_H

#include "model_integrity.h"

#include <array>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <fcntl.h>
#include <limits>
#include <string>
#include <string_view>
#include <cstring>
#include <sys/stat.h>
#include <unistd.h>

namespace stt {

enum class VerifiedModelError : std::uint8_t {
    NONE = 0,
    INVALID_ARGUMENT,
    MISSING_EXPECTED_SHA256,
    INVALID_EXPECTED_SHA256,
    OPEN_FAILED,
    STAT_FAILED,
    NOT_REGULAR_FILE,
    EMPTY_FILE,
    FILE_TOO_LARGE,
    READ_OUT_OF_BOUNDS,
    READ_FAILED,
    FILE_CHANGED,
    SHA256_MISMATCH
};

/**
 * Owns the exact descriptor whose bytes were size-checked and SHA-256 verified.
 *
 * Callers must parse through readExactAt() instead of reopening the path. The
 * source should be an immutable app-private snapshot: a descriptor prevents
 * path replacement, but it cannot prevent another writer changing the inode.
 */
class VerifiedModelFile {
public:
    VerifiedModelFile() = default;
    ~VerifiedModelFile() { reset(); }

    VerifiedModelFile(const VerifiedModelFile&) = delete;
    VerifiedModelFile& operator=(const VerifiedModelFile&) = delete;

    VerifiedModelFile(VerifiedModelFile&& other) noexcept {
        moveFrom(other);
    }

    VerifiedModelFile& operator=(VerifiedModelFile&& other) noexcept {
        if (this != &other) {
            reset();
            moveFrom(other);
        }
        return *this;
    }

    bool open(
        const std::string& path,
        std::uint64_t maximumSize,
        std::string_view expectedSha256
    ) {
        reset();

        if (path.empty() || path.find('\0') != std::string::npos || maximumSize == 0 ||
            maximumSize > ModelIntegrityLimits::kMaxSingleFileBytes) {
            setError(VerifiedModelError::INVALID_ARGUMENT, EINVAL);
            return false;
        }

        ExpectedSha256 expected;
        ModelIntegrityError integrityError = ModelIntegrityError::NONE;
        if (!ExpectedSha256::parse(expectedSha256, expected, integrityError)) {
            setError(mapIntegrityError(integrityError), EINVAL);
            return false;
        }

        const int opened = ::open(
            path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK
        );
        if (opened < 0) {
            setError(VerifiedModelError::OPEN_FAILED, errno);
            return false;
        }

        struct stat before {};
        if (::fstat(opened, &before) != 0) {
            const int savedErrno = errno;
            ::close(opened);
            setError(VerifiedModelError::STAT_FAILED, savedErrno);
            return false;
        }
        if (!S_ISREG(before.st_mode)) {
            ::close(opened);
            setError(VerifiedModelError::NOT_REGULAR_FILE, 0);
            return false;
        }
        if (before.st_size <= 0) {
            ::close(opened);
            setError(VerifiedModelError::EMPTY_FILE, 0);
            return false;
        }

        const auto fileSize = static_cast<std::uint64_t>(before.st_size);
        if (fileSize > maximumSize ||
            fileSize > static_cast<std::uint64_t>(std::numeric_limits<std::size_t>::max())) {
            ::close(opened);
            setError(VerifiedModelError::FILE_TOO_LARGE, EFBIG);
            return false;
        }

        Sha256 hash;
        std::array<std::uint8_t, 64 * 1024> buffer{};
        std::uint64_t offset = 0;
        while (offset < fileSize) {
            const std::uint64_t remaining = fileSize - offset;
            const std::size_t chunk = remaining < buffer.size()
                ? static_cast<std::size_t>(remaining)
                : buffer.size();
            int readErrno = 0;
            std::size_t completed = 0;
            if (!readAt(opened, offset, buffer.data(), chunk, completed, readErrno)) {
                ::close(opened);
                setError(VerifiedModelError::READ_FAILED, readErrno);
                return false;
            }
            (void)hash.update(buffer.data(), chunk);
            offset += static_cast<std::uint64_t>(chunk);
        }

        struct stat after {};
        if (::fstat(opened, &after) != 0) {
            const int savedErrno = errno;
            ::close(opened);
            setError(VerifiedModelError::STAT_FAILED, savedErrno);
            return false;
        }
        if (!sameSnapshot(before, after)) {
            ::close(opened);
            setError(VerifiedModelError::FILE_CHANGED, 0);
            return false;
        }

        const Sha256::Digest observed = hash.digest();
        if (!sha256Equal(observed, expected.bytes)) {
            ::close(opened);
            observedSha256_ = observed;
            setError(VerifiedModelError::SHA256_MISMATCH, 0);
            return false;
        }

        fd_ = opened;
        size_ = fileSize;
        snapshot_ = after;
        observedSha256_ = observed;
        verified_ = true;
        readerOffset_ = 0;
        readFailed_ = false;
        setError(VerifiedModelError::NONE, 0);
        return true;
    }

    bool open(const std::string& path, std::string_view expectedSha256) {
        return open(path, ModelIntegrityLimits::kMaxSingleFileBytes, expectedSha256);
    }

    bool readExactAt(std::uint64_t offset, void* output, std::size_t byteCount) {
        if (fd_ < 0) {
            if (output && byteCount != 0) std::memset(output, 0, byteCount);
            readFailed_ = true;
            setError(VerifiedModelError::INVALID_ARGUMENT, EBADF);
            return false;
        }
        if (byteCount != 0 && !output) {
            readFailed_ = true;
            setError(VerifiedModelError::INVALID_ARGUMENT, EINVAL);
            return false;
        }
        if (byteCount != 0) std::memset(output, 0, byteCount);
        if (offset > size_ || static_cast<std::uint64_t>(byteCount) > size_ - offset) {
            readFailed_ = true;
            setError(VerifiedModelError::READ_OUT_OF_BOUNDS, EINVAL);
            return false;
        }
        if (byteCount == 0) return true;

        std::size_t completed = 0;
        int readErrno = 0;
        if (!readAt(fd_, offset, output, byteCount, completed, readErrno)) {
            readFailed_ = true;
            setError(VerifiedModelError::READ_FAILED, readErrno);
            return false;
        }
        setError(VerifiedModelError::NONE, 0);
        return true;
    }

    /**
     * Sequential vendor-loader callback. A short read leaves the unread tail
     * zeroed and records a sticky failure because the pinned Whisper parser
     * ignores callback read counts.
     */
    std::size_t read(void* output, std::size_t requestedBytes) noexcept {
        if (requestedBytes == 0) return 0;
        if (!output) {
            readFailed_ = true;
            setError(VerifiedModelError::INVALID_ARGUMENT, EINVAL);
            return 0;
        }
        std::memset(output, 0, requestedBytes);
        if (fd_ < 0 || readFailed_) {
            readFailed_ = true;
            setError(VerifiedModelError::READ_FAILED, fd_ < 0 ? EBADF : lastErrno_);
            return 0;
        }

        const std::uint64_t remaining = readerOffset_ <= size_ ? size_ - readerOffset_ : 0;
        if (remaining == 0) {
            setError(VerifiedModelError::NONE, 0);
            return 0;
        }
        const std::size_t available = remaining < static_cast<std::uint64_t>(requestedBytes)
            ? static_cast<std::size_t>(remaining)
            : requestedBytes;
        std::size_t completed = 0;
        int readErrno = 0;
        const bool readSucceeded = available != 0 &&
            readAt(fd_, readerOffset_, output, available, completed, readErrno);
        readerOffset_ += static_cast<std::uint64_t>(completed);
        if (!readSucceeded || completed != requestedBytes) {
            readFailed_ = true;
            setError(VerifiedModelError::READ_FAILED, readErrno);
            return completed;
        }

        setError(VerifiedModelError::NONE, 0);
        return completed;
    }

    bool eof() const noexcept {
        return readFailed_ || readerOffset_ >= size_;
    }

    void close() noexcept {
        if (fd_ >= 0) {
            if (verified_) {
                struct stat current {};
                if (::fstat(fd_, &current) != 0) {
                    readFailed_ = true;
                    setError(VerifiedModelError::STAT_FAILED, errno);
                } else if (!sameSnapshot(snapshot_, current)) {
                    readFailed_ = true;
                    setError(VerifiedModelError::FILE_CHANGED, 0);
                }
            }
            ::close(fd_);
        }
        fd_ = -1;
    }

    static std::size_t readCallback(
        void* context, void* output, std::size_t requestedBytes
    ) noexcept {
        if (!context) {
            if (output && requestedBytes != 0) std::memset(output, 0, requestedBytes);
            return 0;
        }
        return static_cast<VerifiedModelFile*>(context)->read(output, requestedBytes);
    }

    static bool eofCallback(void* context) noexcept {
        return !context || static_cast<VerifiedModelFile*>(context)->eof();
    }

    static void closeCallback(void* context) noexcept {
        if (context) static_cast<VerifiedModelFile*>(context)->close();
    }

    bool snapshotUnchanged() {
        if (fd_ < 0) {
            readFailed_ = true;
            setError(VerifiedModelError::INVALID_ARGUMENT, EBADF);
            return false;
        }
        struct stat current {};
        if (::fstat(fd_, &current) != 0) {
            readFailed_ = true;
            setError(VerifiedModelError::STAT_FAILED, errno);
            return false;
        }
        if (!sameSnapshot(snapshot_, current)) {
            readFailed_ = true;
            setError(VerifiedModelError::FILE_CHANGED, 0);
            return false;
        }
        return true;
    }

    void reset() noexcept {
        close();
        size_ = 0;
        snapshot_ = {};
        observedSha256_.fill(0);
        verified_ = false;
        readerOffset_ = 0;
        readFailed_ = false;
        lastError_ = VerifiedModelError::NONE;
        lastErrno_ = 0;
    }

    bool isOpen() const noexcept { return fd_ >= 0; }
    bool healthy() const noexcept { return verified_ && !readFailed_; }
    bool readFailed() const noexcept { return readFailed_; }
    std::uint64_t size() const noexcept { return size_; }
    const Sha256::Digest& observedSha256() const noexcept { return observedSha256_; }
    std::string observedSha256Hex() const { return sha256ToHex(observedSha256_); }
    VerifiedModelError lastError() const noexcept { return lastError_; }
    int lastErrno() const noexcept { return lastErrno_; }

    static const char* errorMessage(VerifiedModelError error) noexcept {
        switch (error) {
            case VerifiedModelError::NONE: return "none";
            case VerifiedModelError::INVALID_ARGUMENT: return "invalid argument";
            case VerifiedModelError::MISSING_EXPECTED_SHA256:
                return "expected SHA-256 is required";
            case VerifiedModelError::INVALID_EXPECTED_SHA256:
                return "expected SHA-256 must contain exactly 64 lowercase hexadecimal characters";
            case VerifiedModelError::OPEN_FAILED: return "failed to open model";
            case VerifiedModelError::STAT_FAILED: return "failed to stat model";
            case VerifiedModelError::NOT_REGULAR_FILE: return "model is not a regular file";
            case VerifiedModelError::EMPTY_FILE: return "model is empty";
            case VerifiedModelError::FILE_TOO_LARGE: return "model exceeds configured size limit";
            case VerifiedModelError::READ_OUT_OF_BOUNDS: return "model read is out of bounds";
            case VerifiedModelError::READ_FAILED: return "failed to read complete model bytes";
            case VerifiedModelError::FILE_CHANGED: return "model changed during or after verification";
            case VerifiedModelError::SHA256_MISMATCH:
                return "model SHA-256 does not match the trusted digest";
        }
        return "unknown verified model error";
    }

private:
    static VerifiedModelError mapIntegrityError(ModelIntegrityError error) noexcept {
        switch (error) {
            case ModelIntegrityError::MISSING_EXPECTED_SHA256:
                return VerifiedModelError::MISSING_EXPECTED_SHA256;
            case ModelIntegrityError::INVALID_EXPECTED_SHA256:
                return VerifiedModelError::INVALID_EXPECTED_SHA256;
            case ModelIntegrityError::SHA256_MISMATCH:
                return VerifiedModelError::SHA256_MISMATCH;
            case ModelIntegrityError::NONE:
                return VerifiedModelError::NONE;
            default:
                return VerifiedModelError::INVALID_ARGUMENT;
        }
    }

    static bool readAt(
        int fd,
        std::uint64_t offset,
        void* output,
        std::size_t byteCount,
        std::size_t& completed,
        int& readErrno
    ) noexcept {
        auto* destination = static_cast<std::uint8_t*>(output);
        completed = 0;
        while (completed < byteCount) {
            const std::size_t remaining = byteCount - completed;
            const std::size_t chunk = remaining > static_cast<std::size_t>(
                std::numeric_limits<ssize_t>::max()
            ) ? static_cast<std::size_t>(std::numeric_limits<ssize_t>::max()) : remaining;
            const std::uint64_t absoluteOffset = offset + static_cast<std::uint64_t>(completed);
            if (absoluteOffset > static_cast<std::uint64_t>(
                    std::numeric_limits<off_t>::max()
                )) {
                readErrno = EOVERFLOW;
                return false;
            }

            const ssize_t count = ::pread(
                fd,
                destination + completed,
                chunk,
                static_cast<off_t>(absoluteOffset)
            );
            if (count < 0) {
                if (errno == EINTR) continue;
                readErrno = errno;
                return false;
            }
            if (count == 0) {
                readErrno = 0;
                return false;
            }
            completed += static_cast<std::size_t>(count);
        }
        readErrno = 0;
        return true;
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

    void moveFrom(VerifiedModelFile& other) noexcept {
        fd_ = other.fd_;
        size_ = other.size_;
        snapshot_ = other.snapshot_;
        observedSha256_ = other.observedSha256_;
        verified_ = other.verified_;
        readerOffset_ = other.readerOffset_;
        readFailed_ = other.readFailed_;
        lastError_ = other.lastError_;
        lastErrno_ = other.lastErrno_;

        other.fd_ = -1;
        other.size_ = 0;
        other.snapshot_ = {};
        other.observedSha256_.fill(0);
        other.verified_ = false;
        other.readerOffset_ = 0;
        other.readFailed_ = false;
        other.lastError_ = VerifiedModelError::NONE;
        other.lastErrno_ = 0;
    }

    void setError(VerifiedModelError error, int systemError) const noexcept {
        lastError_ = error;
        lastErrno_ = systemError;
    }

    int fd_ = -1;
    std::uint64_t size_ = 0;
    struct stat snapshot_ {};
    Sha256::Digest observedSha256_{};
    bool verified_ = false;
    std::uint64_t readerOffset_ = 0;
    bool readFailed_ = false;
    mutable VerifiedModelError lastError_ = VerifiedModelError::NONE;
    mutable int lastErrno_ = 0;
};

} // namespace stt

#endif // STT_VERIFIED_MODEL_FILE_H
