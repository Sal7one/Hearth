#ifndef STT_MODEL_INTEGRITY_H
#define STT_MODEL_INTEGRITY_H

#include "sha256.h"

#include <algorithm>
#include <array>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <dirent.h>
#include <fcntl.h>
#include <limits>
#include <string>
#include <string_view>
#include <sys/stat.h>
#include <unistd.h>
#include <utility>
#include <vector>

namespace stt {

enum class ModelIntegrityError : std::uint8_t {
    NONE = 0,
    MISSING_EXPECTED_SHA256,
    INVALID_EXPECTED_SHA256,
    SHA256_MISMATCH,
    EMPTY_MODEL_TREE,
    TOO_MANY_TREE_ENTRIES,
    TREE_TOO_LARGE,
    FILE_TOO_LARGE,
    INVALID_TREE_PATH,
    TREE_PATH_TOO_DEEP,
    DUPLICATE_TREE_PATH,
    NON_REGULAR_TREE_ENTRY,
    PATH_OPEN_FAILED,
    PATH_STAT_FAILED,
    PATH_READ_FAILED,
    PATH_CHANGED,
    UNSUPPORTED_PATH_TYPE
};

struct ModelIntegrityLimits {
    static constexpr std::uint64_t kMaxSingleFileBytes = 8ULL * 1024ULL * 1024ULL * 1024ULL;
    static constexpr std::uint64_t kMaxTreeBytes = 16ULL * 1024ULL * 1024ULL * 1024ULL;
    static constexpr std::size_t kMaxTreeFiles = 100000;
    static constexpr std::size_t kMaxRelativePathDepth = 32;
    static constexpr std::size_t kMaxRelativePathBytes = 4096;
};

struct ExpectedSha256 {
    Sha256::Digest bytes{};

    static bool parse(
        std::string_view encoded,
        ExpectedSha256& output,
        ModelIntegrityError& error
    ) noexcept {
        if (encoded.empty()) {
            error = ModelIntegrityError::MISSING_EXPECTED_SHA256;
            return false;
        }
        if (encoded.size() != output.bytes.size() * 2) {
            error = ModelIntegrityError::INVALID_EXPECTED_SHA256;
            return false;
        }

        Sha256::Digest decoded{};
        for (std::size_t i = 0; i < decoded.size(); ++i) {
            const int high = decodeNibble(encoded[i * 2]);
            const int low = decodeNibble(encoded[i * 2 + 1]);
            if (high < 0 || low < 0) {
                error = ModelIntegrityError::INVALID_EXPECTED_SHA256;
                return false;
            }
            decoded[i] = static_cast<std::uint8_t>(
                (static_cast<unsigned>(high) << 4U) | static_cast<unsigned>(low)
            );
        }

        output.bytes = decoded;
        error = ModelIntegrityError::NONE;
        return true;
    }

private:
    static int decodeNibble(char value) noexcept {
        if (value >= '0' && value <= '9') return value - '0';
        if (value >= 'a' && value <= 'f') return value - 'a' + 10;
        return -1;
    }
};

struct ModelIntegrityResult {
    ModelIntegrityError error = ModelIntegrityError::NONE;
    Sha256::Digest observedSha256{};

    explicit operator bool() const noexcept {
        return error == ModelIntegrityError::NONE;
    }
};

inline bool sha256Equal(
    const Sha256::Digest& first,
    const Sha256::Digest& second
) noexcept {
    std::uint8_t difference = 0;
    for (std::size_t i = 0; i < first.size(); ++i) {
        difference = static_cast<std::uint8_t>(difference | (first[i] ^ second[i]));
    }
    return difference == 0;
}

inline std::string sha256ToHex(const Sha256::Digest& digest) {
    static constexpr char kHex[] = "0123456789abcdef";
    std::string encoded(digest.size() * 2, '0');
    for (std::size_t i = 0; i < digest.size(); ++i) {
        encoded[i * 2] = kHex[digest[i] >> 4U];
        encoded[i * 2 + 1] = kHex[digest[i] & 0x0fU];
    }
    return encoded;
}

inline ModelIntegrityResult verifySha256(
    const Sha256::Digest& observed,
    std::string_view expectedHex
) noexcept {
    ModelIntegrityResult result;
    result.observedSha256 = observed;

    ExpectedSha256 expected;
    if (!ExpectedSha256::parse(expectedHex, expected, result.error)) return result;
    if (!sha256Equal(observed, expected.bytes)) {
        result.error = ModelIntegrityError::SHA256_MISMATCH;
    }
    return result;
}

enum class ModelTreeEntryType : std::uint8_t {
    REGULAR_FILE = 0,
    DIRECTORY,
    SYMBOLIC_LINK,
    OTHER
};

struct ModelTreeEntry {
    std::string relativePath;
    std::uint64_t sizeBytes = 0;
    Sha256::Digest contentSha256{};
    ModelTreeEntryType type = ModelTreeEntryType::REGULAR_FILE;
};

struct ModelTreeDigestResult {
    ModelIntegrityError error = ModelIntegrityError::NONE;
    Sha256::Digest digest{};
    std::size_t fileCount = 0;
    std::uint64_t aggregateBytes = 0;

    explicit operator bool() const noexcept {
        return error == ModelIntegrityError::NONE;
    }
};

namespace model_integrity_detail {

inline bool isUtf8Continuation(std::uint8_t value) noexcept {
    return (value & 0xc0U) == 0x80U;
}

inline bool isValidUtf8(std::string_view value) noexcept {
    std::size_t i = 0;
    while (i < value.size()) {
        const auto first = static_cast<std::uint8_t>(value[i]);
        if (first <= 0x7fU) {
            ++i;
            continue;
        }
        if (first >= 0xc2U && first <= 0xdfU) {
            if (i + 1 >= value.size() ||
                !isUtf8Continuation(static_cast<std::uint8_t>(value[i + 1]))) return false;
            i += 2;
            continue;
        }
        if (first >= 0xe0U && first <= 0xefU) {
            if (i + 2 >= value.size()) return false;
            const auto second = static_cast<std::uint8_t>(value[i + 1]);
            const auto third = static_cast<std::uint8_t>(value[i + 2]);
            if (!isUtf8Continuation(second) || !isUtf8Continuation(third)) return false;
            if (first == 0xe0U && second < 0xa0U) return false;
            if (first == 0xedU && second >= 0xa0U) return false;
            i += 3;
            continue;
        }
        if (first >= 0xf0U && first <= 0xf4U) {
            if (i + 3 >= value.size()) return false;
            const auto second = static_cast<std::uint8_t>(value[i + 1]);
            const auto third = static_cast<std::uint8_t>(value[i + 2]);
            const auto fourth = static_cast<std::uint8_t>(value[i + 3]);
            if (!isUtf8Continuation(second) || !isUtf8Continuation(third) ||
                !isUtf8Continuation(fourth)) return false;
            if (first == 0xf0U && second < 0x90U) return false;
            if (first == 0xf4U && second >= 0x90U) return false;
            i += 4;
            continue;
        }
        return false;
    }
    return true;
}

inline ModelIntegrityError validateRelativePath(std::string_view path) noexcept {
    if (path.empty() || path.size() > ModelIntegrityLimits::kMaxRelativePathBytes ||
        path.front() == '/' || path.back() == '/' || path.find('\0') != std::string_view::npos ||
        path.find('\\') != std::string_view::npos || !isValidUtf8(path)) {
        return ModelIntegrityError::INVALID_TREE_PATH;
    }

    std::size_t depth = 0;
    std::size_t componentStart = 0;
    while (componentStart < path.size()) {
        const std::size_t slash = path.find('/', componentStart);
        const std::size_t componentEnd = slash == std::string_view::npos ? path.size() : slash;
        const std::string_view component = path.substr(
            componentStart, componentEnd - componentStart
        );
        if (component.empty() || component == "." || component == "..") {
            return ModelIntegrityError::INVALID_TREE_PATH;
        }
        ++depth;
        if (depth > ModelIntegrityLimits::kMaxRelativePathDepth) {
            return ModelIntegrityError::TREE_PATH_TOO_DEEP;
        }
        if (slash == std::string_view::npos) break;
        componentStart = slash + 1;
    }
    return ModelIntegrityError::NONE;
}

inline bool bytewisePathLess(const ModelTreeEntry* first, const ModelTreeEntry* second) noexcept {
    return std::lexicographical_compare(
        first->relativePath.begin(), first->relativePath.end(),
        second->relativePath.begin(), second->relativePath.end(),
        [](char left, char right) {
            return static_cast<std::uint8_t>(left) < static_cast<std::uint8_t>(right);
        }
    );
}

inline void updateUint64BigEndian(Sha256& hash, std::uint64_t value) noexcept {
    std::uint8_t encoded[8]{};
    for (std::size_t i = 0; i < 8; ++i) {
        const unsigned shift = static_cast<unsigned>((7 - i) * 8);
        encoded[i] = static_cast<std::uint8_t>(value >> shift);
    }
    (void)hash.update(encoded, sizeof(encoded));
}

} // namespace model_integrity_detail

/**
 * Computes the cross-language model directory identity contract:
 *
 * SHA-256("model-tree-sha256-v1\\0" || each bytewise-sorted entry), where an
 * entry is uint64-be(path byte length), UTF-8 path bytes, uint64-be(file size),
 * and the raw 32-byte SHA-256 content digest.
 *
 * The caller remains responsible for obtaining entries through a no-follow
 * directory walk. Entry types are explicit so symlinks and non-regular nodes
 * fail closed instead of silently disappearing from the identity.
 */
inline ModelTreeDigestResult computeModelTreeSha256(
    const std::vector<ModelTreeEntry>& entries
) {
    ModelTreeDigestResult result;
    if (entries.empty()) {
        result.error = ModelIntegrityError::EMPTY_MODEL_TREE;
        return result;
    }
    if (entries.size() > ModelIntegrityLimits::kMaxTreeFiles) {
        result.error = ModelIntegrityError::TOO_MANY_TREE_ENTRIES;
        return result;
    }

    std::vector<const ModelTreeEntry*> sorted;
    sorted.reserve(entries.size());
    for (const ModelTreeEntry& entry : entries) {
        if (entry.type != ModelTreeEntryType::REGULAR_FILE) {
            result.error = ModelIntegrityError::NON_REGULAR_TREE_ENTRY;
            return result;
        }
        const ModelIntegrityError pathError =
            model_integrity_detail::validateRelativePath(entry.relativePath);
        if (pathError != ModelIntegrityError::NONE) {
            result.error = pathError;
            return result;
        }
        if (entry.sizeBytes > ModelIntegrityLimits::kMaxSingleFileBytes) {
            result.error = ModelIntegrityError::FILE_TOO_LARGE;
            return result;
        }
        if (entry.sizeBytes > ModelIntegrityLimits::kMaxTreeBytes - result.aggregateBytes) {
            result.error = ModelIntegrityError::TREE_TOO_LARGE;
            return result;
        }
        result.aggregateBytes += entry.sizeBytes;
        sorted.push_back(&entry);
    }
    if (result.aggregateBytes == 0) {
        result.error = ModelIntegrityError::EMPTY_MODEL_TREE;
        return result;
    }

    std::sort(sorted.begin(), sorted.end(), model_integrity_detail::bytewisePathLess);
    for (std::size_t i = 1; i < sorted.size(); ++i) {
        if (sorted[i - 1]->relativePath == sorted[i]->relativePath) {
            result.error = ModelIntegrityError::DUPLICATE_TREE_PATH;
            return result;
        }
    }

    Sha256 hash;
    static constexpr char kDomain[] = "model-tree-sha256-v1";
    (void)hash.update(kDomain, sizeof(kDomain)); // Includes the required trailing NUL.
    for (const ModelTreeEntry* entry : sorted) {
        model_integrity_detail::updateUint64BigEndian(
            hash, static_cast<std::uint64_t>(entry->relativePath.size())
        );
        (void)hash.update(entry->relativePath.data(), entry->relativePath.size());
        model_integrity_detail::updateUint64BigEndian(hash, entry->sizeBytes);
        (void)hash.update(entry->contentSha256.data(), entry->contentSha256.size());
    }
    result.digest = hash.digest();
    result.fileCount = sorted.size();
    return result;
}

enum class ModelPathKind : std::uint8_t {
    NONE = 0,
    REGULAR_FILE,
    DIRECTORY_TREE
};

struct ModelPathDigestResult {
    ModelIntegrityError error = ModelIntegrityError::NONE;
    ModelPathKind kind = ModelPathKind::NONE;
    Sha256::Digest digest{};
    std::size_t fileCount = 0;
    std::uint64_t aggregateBytes = 0;
    int systemError = 0;

    explicit operator bool() const noexcept {
        return error == ModelIntegrityError::NONE;
    }

    std::string digestHex() const { return sha256ToHex(digest); }
};

namespace model_integrity_detail {

class ScopedFd {
public:
    explicit ScopedFd(int fd = -1) noexcept : fd_(fd) {}
    ~ScopedFd() { if (fd_ >= 0) ::close(fd_); }

    ScopedFd(const ScopedFd&) = delete;
    ScopedFd& operator=(const ScopedFd&) = delete;

    int get() const noexcept { return fd_; }

private:
    int fd_;
};

class ScopedDir {
public:
    explicit ScopedDir(DIR* dir = nullptr) noexcept : dir_(dir) {}
    ~ScopedDir() { if (dir_) ::closedir(dir_); }

    ScopedDir(const ScopedDir&) = delete;
    ScopedDir& operator=(const ScopedDir&) = delete;

    DIR* get() const noexcept { return dir_; }

private:
    DIR* dir_;
};

inline bool sameFileSnapshot(const struct stat& first, const struct stat& second) noexcept {
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

inline bool sameOpenedNode(const struct stat& named, const struct stat& opened) noexcept {
    return named.st_dev == opened.st_dev && named.st_ino == opened.st_ino &&
           (named.st_mode & S_IFMT) == (opened.st_mode & S_IFMT);
}

inline bool digestRegularFileFd(
    int fd,
    const struct stat& before,
    Sha256::Digest& digest,
    ModelIntegrityError& error,
    int& systemError
) noexcept {
    if (!S_ISREG(before.st_mode)) {
        error = ModelIntegrityError::NON_REGULAR_TREE_ENTRY;
        return false;
    }
    if (before.st_size < 0 ||
        static_cast<std::uint64_t>(before.st_size) > ModelIntegrityLimits::kMaxSingleFileBytes) {
        error = ModelIntegrityError::FILE_TOO_LARGE;
        systemError = EFBIG;
        return false;
    }

    const std::uint64_t size = static_cast<std::uint64_t>(before.st_size);
    std::array<std::uint8_t, 64 * 1024> buffer{};
    Sha256 hash;
    std::uint64_t offset = 0;
    while (offset < size) {
        const std::uint64_t remaining = size - offset;
        const std::size_t wanted = remaining < buffer.size()
            ? static_cast<std::size_t>(remaining)
            : buffer.size();
        std::size_t completed = 0;
        while (completed < wanted) {
            const std::uint64_t absolute = offset + static_cast<std::uint64_t>(completed);
            const ssize_t count = ::pread(
                fd,
                buffer.data() + completed,
                wanted - completed,
                static_cast<off_t>(absolute)
            );
            if (count < 0) {
                if (errno == EINTR) continue;
                error = ModelIntegrityError::PATH_READ_FAILED;
                systemError = errno;
                return false;
            }
            if (count == 0) {
                error = ModelIntegrityError::PATH_READ_FAILED;
                systemError = 0;
                return false;
            }
            completed += static_cast<std::size_t>(count);
        }
        (void)hash.update(buffer.data(), wanted);
        offset += static_cast<std::uint64_t>(wanted);
    }

    struct stat after {};
    if (::fstat(fd, &after) != 0) {
        error = ModelIntegrityError::PATH_STAT_FAILED;
        systemError = errno;
        return false;
    }
    if (!sameFileSnapshot(before, after)) {
        error = ModelIntegrityError::PATH_CHANGED;
        return false;
    }
    digest = hash.digest();
    return true;
}

inline bool appendRelativePath(
    const std::string& prefix,
    const char* name,
    std::string& output,
    ModelIntegrityError& error
) {
    const std::size_t nameBytes = std::char_traits<char>::length(name);
    const std::size_t separatorBytes = prefix.empty() ? 0 : 1;
    if (nameBytes > ModelIntegrityLimits::kMaxRelativePathBytes ||
        prefix.size() > ModelIntegrityLimits::kMaxRelativePathBytes - nameBytes ||
        separatorBytes > ModelIntegrityLimits::kMaxRelativePathBytes - prefix.size() - nameBytes) {
        error = ModelIntegrityError::INVALID_TREE_PATH;
        return false;
    }
    output.clear();
    output.reserve(prefix.size() + separatorBytes + nameBytes);
    output.append(prefix);
    if (!prefix.empty()) output.push_back('/');
    output.append(name, nameBytes);
    error = validateRelativePath(output);
    return error == ModelIntegrityError::NONE;
}

inline bool collectTreeEntries(
    int directoryFd,
    const std::string& prefix,
    std::vector<ModelTreeEntry>& entries,
    std::uint64_t& aggregateBytes,
    ModelIntegrityError& error,
    int& systemError
) {
    struct stat directoryBefore {};
    if (::fstat(directoryFd, &directoryBefore) != 0) {
        error = ModelIntegrityError::PATH_STAT_FAILED;
        systemError = errno;
        return false;
    }
    if (!S_ISDIR(directoryBefore.st_mode)) {
        error = ModelIntegrityError::UNSUPPORTED_PATH_TYPE;
        return false;
    }

    const int duplicateFd = ::fcntl(directoryFd, F_DUPFD_CLOEXEC, 0);
    if (duplicateFd < 0) {
        error = ModelIntegrityError::PATH_OPEN_FAILED;
        systemError = errno;
        return false;
    }
    DIR* rawDirectory = ::fdopendir(duplicateFd);
    if (!rawDirectory) {
        const int savedErrno = errno;
        ::close(duplicateFd);
        error = ModelIntegrityError::PATH_OPEN_FAILED;
        systemError = savedErrno;
        return false;
    }
    ScopedDir directory(rawDirectory);

    errno = 0;
    while (dirent* entry = ::readdir(directory.get())) {
        const char* name = entry->d_name;
        if ((name[0] == '.' && name[1] == '\0') ||
            (name[0] == '.' && name[1] == '.' && name[2] == '\0')) {
            errno = 0;
            continue;
        }

        std::string relativePath;
        if (!appendRelativePath(prefix, name, relativePath, error)) return false;

        struct stat named {};
        if (::fstatat(directoryFd, name, &named, AT_SYMLINK_NOFOLLOW) != 0) {
            error = ModelIntegrityError::PATH_STAT_FAILED;
            systemError = errno;
            return false;
        }
        if (S_ISLNK(named.st_mode)) {
            error = ModelIntegrityError::NON_REGULAR_TREE_ENTRY;
            return false;
        }

        if (S_ISDIR(named.st_mode)) {
            const int childRaw = ::openat(
                directoryFd,
                name,
                O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK | O_DIRECTORY
            );
            if (childRaw < 0) {
                error = ModelIntegrityError::PATH_OPEN_FAILED;
                systemError = errno;
                return false;
            }
            ScopedFd child(childRaw);
            struct stat childOpened {};
            if (::fstat(child.get(), &childOpened) != 0) {
                error = ModelIntegrityError::PATH_STAT_FAILED;
                systemError = errno;
                return false;
            }
            if (!sameOpenedNode(named, childOpened) || !S_ISDIR(childOpened.st_mode)) {
                error = ModelIntegrityError::PATH_CHANGED;
                return false;
            }
            if (!collectTreeEntries(
                    child.get(), relativePath, entries, aggregateBytes, error, systemError
                )) return false;
        } else if (S_ISREG(named.st_mode)) {
            if (entries.size() >= ModelIntegrityLimits::kMaxTreeFiles) {
                error = ModelIntegrityError::TOO_MANY_TREE_ENTRIES;
                return false;
            }
            if (named.st_size < 0 ||
                static_cast<std::uint64_t>(named.st_size) >
                    ModelIntegrityLimits::kMaxSingleFileBytes) {
                error = ModelIntegrityError::FILE_TOO_LARGE;
                systemError = EFBIG;
                return false;
            }
            const std::uint64_t fileSize = static_cast<std::uint64_t>(named.st_size);
            if (fileSize > ModelIntegrityLimits::kMaxTreeBytes - aggregateBytes) {
                error = ModelIntegrityError::TREE_TOO_LARGE;
                systemError = EFBIG;
                return false;
            }

            const int fileRaw = ::openat(
                directoryFd, name, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK
            );
            if (fileRaw < 0) {
                error = ModelIntegrityError::PATH_OPEN_FAILED;
                systemError = errno;
                return false;
            }
            ScopedFd file(fileRaw);
            struct stat fileOpened {};
            if (::fstat(file.get(), &fileOpened) != 0) {
                error = ModelIntegrityError::PATH_STAT_FAILED;
                systemError = errno;
                return false;
            }
            if (!sameOpenedNode(named, fileOpened) || !S_ISREG(fileOpened.st_mode) ||
                named.st_size != fileOpened.st_size) {
                error = ModelIntegrityError::PATH_CHANGED;
                return false;
            }

            ModelTreeEntry modelEntry;
            modelEntry.relativePath = std::move(relativePath);
            modelEntry.sizeBytes = fileSize;
            if (!digestRegularFileFd(
                    file.get(), fileOpened, modelEntry.contentSha256, error, systemError
                )) return false;
            entries.push_back(std::move(modelEntry));
            aggregateBytes += fileSize;
        } else {
            error = ModelIntegrityError::NON_REGULAR_TREE_ENTRY;
            return false;
        }
        errno = 0;
    }
    if (errno != 0) {
        error = ModelIntegrityError::PATH_READ_FAILED;
        systemError = errno;
        return false;
    }

    struct stat directoryAfter {};
    if (::fstat(directoryFd, &directoryAfter) != 0) {
        error = ModelIntegrityError::PATH_STAT_FAILED;
        systemError = errno;
        return false;
    }
    if (!sameFileSnapshot(directoryBefore, directoryAfter)) {
        error = ModelIntegrityError::PATH_CHANGED;
        return false;
    }
    return true;
}

} // namespace model_integrity_detail

/**
 * Hashes a regular file or directory tree without following its final path or
 * any tree-entry symlink. An empty expectedSha256 computes identity only; a
 * non-empty value is a strict lowercase trusted digest to verify.
 */
inline ModelPathDigestResult digestPath(
    const std::string& path,
    std::string_view expectedSha256 = {}
) {
    ModelPathDigestResult result;
    if (path.empty() || path.find('\0') != std::string::npos) {
        result.error = ModelIntegrityError::INVALID_TREE_PATH;
        result.systemError = EINVAL;
        return result;
    }

    const int rawFd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    if (rawFd < 0) {
        result.error = ModelIntegrityError::PATH_OPEN_FAILED;
        result.systemError = errno;
        return result;
    }
    model_integrity_detail::ScopedFd fd(rawFd);

    struct stat root {};
    if (::fstat(fd.get(), &root) != 0) {
        result.error = ModelIntegrityError::PATH_STAT_FAILED;
        result.systemError = errno;
        return result;
    }

    if (S_ISREG(root.st_mode)) {
        if (root.st_size <= 0) {
            result.error = ModelIntegrityError::EMPTY_MODEL_TREE;
            return result;
        }
        if (!model_integrity_detail::digestRegularFileFd(
                fd.get(), root, result.digest, result.error, result.systemError
            )) return result;
        result.kind = ModelPathKind::REGULAR_FILE;
        result.fileCount = 1;
        result.aggregateBytes = static_cast<std::uint64_t>(root.st_size);
    } else if (S_ISDIR(root.st_mode)) {
        std::vector<ModelTreeEntry> entries;
        std::uint64_t aggregateBytes = 0;
        if (!model_integrity_detail::collectTreeEntries(
                fd.get(), {}, entries, aggregateBytes, result.error, result.systemError
            )) return result;
        const ModelTreeDigestResult tree = computeModelTreeSha256(entries);
        if (!tree) {
            result.error = tree.error;
            return result;
        }
        result.kind = ModelPathKind::DIRECTORY_TREE;
        result.digest = tree.digest;
        result.fileCount = tree.fileCount;
        result.aggregateBytes = tree.aggregateBytes;
    } else {
        result.error = ModelIntegrityError::UNSUPPORTED_PATH_TYPE;
        return result;
    }

    if (!expectedSha256.empty()) {
        const ModelIntegrityResult integrity = verifySha256(result.digest, expectedSha256);
        if (!integrity) result.error = integrity.error;
    }
    return result;
}

inline const char* modelIntegrityErrorMessage(ModelIntegrityError error) noexcept {
    switch (error) {
        case ModelIntegrityError::NONE:
            return "none";
        case ModelIntegrityError::MISSING_EXPECTED_SHA256:
            return "expected SHA-256 is required";
        case ModelIntegrityError::INVALID_EXPECTED_SHA256:
            return "expected SHA-256 must contain exactly 64 lowercase hexadecimal characters";
        case ModelIntegrityError::SHA256_MISMATCH:
            return "model SHA-256 does not match the trusted digest";
        case ModelIntegrityError::EMPTY_MODEL_TREE:
            return "model tree must contain non-empty regular-file content";
        case ModelIntegrityError::TOO_MANY_TREE_ENTRIES:
            return "model tree exceeds the file-count limit";
        case ModelIntegrityError::TREE_TOO_LARGE:
            return "model tree exceeds the aggregate size limit";
        case ModelIntegrityError::FILE_TOO_LARGE:
            return "model file exceeds the size limit";
        case ModelIntegrityError::INVALID_TREE_PATH:
            return "model tree contains an invalid relative UTF-8 path";
        case ModelIntegrityError::TREE_PATH_TOO_DEEP:
            return "model tree path exceeds the depth limit";
        case ModelIntegrityError::DUPLICATE_TREE_PATH:
            return "model tree contains a duplicate relative path";
        case ModelIntegrityError::NON_REGULAR_TREE_ENTRY:
            return "model tree contains a symlink or non-regular entry";
        case ModelIntegrityError::PATH_OPEN_FAILED:
            return "failed to open model path without following symlinks";
        case ModelIntegrityError::PATH_STAT_FAILED:
            return "failed to stat opened model path";
        case ModelIntegrityError::PATH_READ_FAILED:
            return "failed to read complete model content";
        case ModelIntegrityError::PATH_CHANGED:
            return "model path changed while it was being hashed";
        case ModelIntegrityError::UNSUPPORTED_PATH_TYPE:
            return "model path is neither a regular file nor a directory";
    }
    return "unknown model integrity error";
}

} // namespace stt

#endif // STT_MODEL_INTEGRITY_H
