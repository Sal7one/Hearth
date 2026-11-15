// Windows twin of common-jni/src/main/cpp/common/model_integrity.h for the desktop adapters.
//
// The shared header walks a model tree with POSIX descriptor calls (openat, fstatat,
// fdopendir, pread) that MSVC does not provide. This header supplies the part of that API
// the desktop backends use, with the same identity contract and the same fail-closed rules:
//
//   SHA-256("model-tree-sha256-v1\0" || each bytewise-sorted entry), where an entry is
//   uint64-be(path byte length), UTF-8 path bytes with '/' separators, uint64-be(file size)
//   and the raw 32-byte SHA-256 content digest.
//
// Symbolic links, junctions and other non-regular entries are errors, never skipped.
#ifndef STT_MODEL_INTEGRITY_H
#define STT_MODEL_INTEGRITY_H

#include "sha256.h"

#include <algorithm>
#include <array>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <string>
#include <string_view>
#include <system_error>
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

enum class ModelPathKind : std::uint8_t { NONE = 0, REGULAR_FILE, DIRECTORY_TREE };

inline std::string sha256ToHex(const Sha256::Digest& digest) {
    static constexpr char kHex[] = "0123456789abcdef";
    std::string encoded(digest.size() * 2, '0');
    for (std::size_t i = 0; i < digest.size(); ++i) {
        encoded[i * 2] = kHex[digest[i] >> 4U];
        encoded[i * 2 + 1] = kHex[digest[i] & 0x0fU];
    }
    return encoded;
}

struct ModelPathDigestResult {
    ModelIntegrityError error = ModelIntegrityError::NONE;
    ModelPathKind kind = ModelPathKind::NONE;
    Sha256::Digest digest{};
    std::size_t fileCount = 0;
    std::uint64_t aggregateBytes = 0;
    int systemError = 0;

    explicit operator bool() const noexcept { return error == ModelIntegrityError::NONE; }
    std::string digestHex() const { return sha256ToHex(digest); }
};

namespace model_integrity_detail {

struct TreeEntry {
    std::string relativePath;
    std::uint64_t sizeBytes = 0;
    Sha256::Digest contentSha256{};
};

inline bool isLink(std::filesystem::file_type type) noexcept {
    return type == std::filesystem::file_type::symlink || type == std::filesystem::file_type::junction;
}

inline void updateUint64BigEndian(Sha256& hash, std::uint64_t value) noexcept {
    std::uint8_t encoded[8]{};
    for (std::size_t i = 0; i < 8; ++i) encoded[i] = static_cast<std::uint8_t>(value >> ((7 - i) * 8));
    (void)hash.update(encoded, sizeof(encoded));
}

/** Hashes exactly the size and write time observed before the read; a change is an error. */
inline bool digestRegularFile(const std::filesystem::path& file, std::uint64_t size, Sha256::Digest& digest,
                              ModelIntegrityError& error) {
    std::error_code code;
    const auto written = std::filesystem::last_write_time(file, code);
    if (code) { error = ModelIntegrityError::PATH_STAT_FAILED; return false; }
    std::ifstream input(file, std::ios::binary);
    if (!input) { error = ModelIntegrityError::PATH_OPEN_FAILED; return false; }
    std::array<char, 64 * 1024> buffer{};
    Sha256 hash;
    std::uint64_t offset = 0;
    while (offset < size) {
        const std::uint64_t remaining = size - offset;
        const std::size_t wanted = remaining < buffer.size() ? static_cast<std::size_t>(remaining) : buffer.size();
        input.read(buffer.data(), static_cast<std::streamsize>(wanted));
        if (static_cast<std::size_t>(input.gcount()) != wanted) { error = ModelIntegrityError::PATH_READ_FAILED; return false; }
        (void)hash.update(buffer.data(), wanted);
        offset += wanted;
    }
    if (input.peek() != std::ifstream::traits_type::eof() || std::filesystem::file_size(file, code) != size || code ||
        std::filesystem::last_write_time(file, code) != written || code) {
        error = ModelIntegrityError::PATH_CHANGED;
        return false;
    }
    digest = hash.digest();
    return true;
}

inline bool collectTreeEntries(const std::filesystem::path& directory, const std::string& prefix, std::size_t depth,
                               std::vector<TreeEntry>& entries, std::uint64_t& aggregateBytes, ModelIntegrityError& error) {
    if (depth > ModelIntegrityLimits::kMaxRelativePathDepth) { error = ModelIntegrityError::TREE_PATH_TOO_DEEP; return false; }
    std::error_code code;
    std::filesystem::directory_iterator iterator(directory, code);
    if (code) { error = ModelIntegrityError::PATH_OPEN_FAILED; return false; }
    for (const std::filesystem::directory_iterator end; iterator != end; iterator.increment(code)) {
        if (code) { error = ModelIntegrityError::PATH_READ_FAILED; return false; }
        const std::string name = iterator->path().filename().u8string();
        if (name.empty() || name == "." || name == ".." || name.find('/') != std::string::npos ||
            name.find('\\') != std::string::npos ||
            prefix.size() + name.size() + 1 > ModelIntegrityLimits::kMaxRelativePathBytes) {
            error = ModelIntegrityError::INVALID_TREE_PATH;
            return false;
        }
        const std::string relativePath = prefix.empty() ? name : prefix + "/" + name;
        const auto status = iterator->symlink_status(code);
        if (code) { error = ModelIntegrityError::PATH_STAT_FAILED; return false; }
        if (isLink(status.type())) { error = ModelIntegrityError::NON_REGULAR_TREE_ENTRY; return false; }
        if (status.type() == std::filesystem::file_type::directory) {
            if (!collectTreeEntries(iterator->path(), relativePath, depth + 1, entries, aggregateBytes, error)) return false;
        } else if (status.type() == std::filesystem::file_type::regular) {
            if (entries.size() >= ModelIntegrityLimits::kMaxTreeFiles) { error = ModelIntegrityError::TOO_MANY_TREE_ENTRIES; return false; }
            const std::uint64_t size = std::filesystem::file_size(iterator->path(), code);
            if (code) { error = ModelIntegrityError::PATH_STAT_FAILED; return false; }
            if (size > ModelIntegrityLimits::kMaxSingleFileBytes) { error = ModelIntegrityError::FILE_TOO_LARGE; return false; }
            if (size > ModelIntegrityLimits::kMaxTreeBytes - aggregateBytes) { error = ModelIntegrityError::TREE_TOO_LARGE; return false; }
            TreeEntry entry;
            entry.relativePath = relativePath;
            entry.sizeBytes = size;
            if (!digestRegularFile(iterator->path(), size, entry.contentSha256, error)) return false;
            entries.push_back(std::move(entry));
            aggregateBytes += size;
        } else {
            error = ModelIntegrityError::NON_REGULAR_TREE_ENTRY;
            return false;
        }
    }
    return true;
}

} // namespace model_integrity_detail

/**
 * Hashes a regular file or directory tree without following its final path or any
 * tree-entry link. An empty expectedSha256 computes identity only; a non-empty value
 * is a strict lowercase trusted digest to verify. The path is UTF-8.
 */
inline ModelPathDigestResult digestPath(const std::string& path, std::string_view expectedSha256 = {}) {
    ModelPathDigestResult result;
    if (path.empty() || path.find('\0') != std::string::npos) {
        result.error = ModelIntegrityError::INVALID_TREE_PATH;
        result.systemError = EINVAL;
        return result;
    }
    try {
        const std::filesystem::path root = std::filesystem::u8path(path);
        std::error_code code;
        const auto status = std::filesystem::symlink_status(root, code);
        if (code || model_integrity_detail::isLink(status.type()) || status.type() == std::filesystem::file_type::not_found) {
            result.error = ModelIntegrityError::PATH_OPEN_FAILED;
            result.systemError = code.value();
            return result;
        }
        if (status.type() == std::filesystem::file_type::regular) {
            const std::uint64_t size = std::filesystem::file_size(root, code);
            if (code) { result.error = ModelIntegrityError::PATH_STAT_FAILED; return result; }
            if (size == 0) { result.error = ModelIntegrityError::EMPTY_MODEL_TREE; return result; }
            if (size > ModelIntegrityLimits::kMaxSingleFileBytes) { result.error = ModelIntegrityError::FILE_TOO_LARGE; return result; }
            if (!model_integrity_detail::digestRegularFile(root, size, result.digest, result.error)) return result;
            result.kind = ModelPathKind::REGULAR_FILE;
            result.fileCount = 1;
            result.aggregateBytes = size;
        } else if (status.type() == std::filesystem::file_type::directory) {
            std::vector<model_integrity_detail::TreeEntry> entries;
            std::uint64_t aggregateBytes = 0;
            if (!model_integrity_detail::collectTreeEntries(root, {}, 1, entries, aggregateBytes, result.error)) return result;
            if (entries.empty() || aggregateBytes == 0) { result.error = ModelIntegrityError::EMPTY_MODEL_TREE; return result; }
            std::sort(entries.begin(), entries.end(), [](const auto& first, const auto& second) {
                return std::lexicographical_compare(first.relativePath.begin(), first.relativePath.end(),
                    second.relativePath.begin(), second.relativePath.end(),
                    [](char left, char right) { return static_cast<std::uint8_t>(left) < static_cast<std::uint8_t>(right); });
            });
            for (std::size_t i = 1; i < entries.size(); ++i) {
                if (entries[i - 1].relativePath == entries[i].relativePath) { result.error = ModelIntegrityError::DUPLICATE_TREE_PATH; return result; }
            }
            Sha256 hash;
            static constexpr char kDomain[] = "model-tree-sha256-v1";
            (void)hash.update(kDomain, sizeof(kDomain)); // Includes the required trailing NUL.
            for (const auto& entry : entries) {
                model_integrity_detail::updateUint64BigEndian(hash, static_cast<std::uint64_t>(entry.relativePath.size()));
                (void)hash.update(entry.relativePath.data(), entry.relativePath.size());
                model_integrity_detail::updateUint64BigEndian(hash, entry.sizeBytes);
                (void)hash.update(entry.contentSha256.data(), entry.contentSha256.size());
            }
            result.kind = ModelPathKind::DIRECTORY_TREE;
            result.digest = hash.digest();
            result.fileCount = entries.size();
            result.aggregateBytes = aggregateBytes;
        } else {
            result.error = ModelIntegrityError::UNSUPPORTED_PATH_TYPE;
            return result;
        }
    } catch (const std::exception&) {
        result.error = ModelIntegrityError::PATH_READ_FAILED;
        return result;
    }
    if (!expectedSha256.empty()) {
        if (expectedSha256.size() != 64 ||
            expectedSha256.find_first_not_of("0123456789abcdef") != std::string_view::npos) {
            result.error = ModelIntegrityError::INVALID_EXPECTED_SHA256;
        } else if (result.digestHex() != expectedSha256) {
            result.error = ModelIntegrityError::SHA256_MISMATCH;
        }
    }
    return result;
}

inline const char* modelIntegrityErrorMessage(ModelIntegrityError error) noexcept {
    switch (error) {
        case ModelIntegrityError::NONE: return "none";
        case ModelIntegrityError::MISSING_EXPECTED_SHA256: return "expected SHA-256 is required";
        case ModelIntegrityError::INVALID_EXPECTED_SHA256: return "expected SHA-256 must contain exactly 64 lowercase hexadecimal characters";
        case ModelIntegrityError::SHA256_MISMATCH: return "model SHA-256 does not match the trusted digest";
        case ModelIntegrityError::EMPTY_MODEL_TREE: return "model tree must contain non-empty regular-file content";
        case ModelIntegrityError::TOO_MANY_TREE_ENTRIES: return "model tree exceeds the file-count limit";
        case ModelIntegrityError::TREE_TOO_LARGE: return "model tree exceeds the aggregate size limit";
        case ModelIntegrityError::FILE_TOO_LARGE: return "model file exceeds the size limit";
        case ModelIntegrityError::INVALID_TREE_PATH: return "model tree contains an invalid relative UTF-8 path";
        case ModelIntegrityError::TREE_PATH_TOO_DEEP: return "model tree path exceeds the depth limit";
        case ModelIntegrityError::DUPLICATE_TREE_PATH: return "model tree contains a duplicate relative path";
        case ModelIntegrityError::NON_REGULAR_TREE_ENTRY: return "model tree contains a symlink or non-regular entry";
        case ModelIntegrityError::PATH_OPEN_FAILED: return "failed to open model path without following symlinks";
        case ModelIntegrityError::PATH_STAT_FAILED: return "failed to stat opened model path";
        case ModelIntegrityError::PATH_READ_FAILED: return "failed to read complete model content";
        case ModelIntegrityError::PATH_CHANGED: return "model path changed while it was being hashed";
        case ModelIntegrityError::UNSUPPORTED_PATH_TYPE: return "model path is neither a regular file nor a directory";
    }
    return "unknown model integrity error";
}

} // namespace stt

#endif // STT_MODEL_INTEGRITY_H
