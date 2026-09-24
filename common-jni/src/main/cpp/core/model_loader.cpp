#include "model_loader.h"
#include "model_integrity.h"
#include "logging.h"
#include "../jni/jni_attachment.h"
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <array>
#include <atomic>
#include <cerrno>
#include <fcntl.h>
#include <sys/stat.h>
#include <dirent.h>
#include <cstdio>
#include <unistd.h>

namespace stt {

namespace {

constexpr std::size_t kCopyBufferBytes = 64 * 1024;
std::atomic<std::uint64_t> g_tempFileSequence{1};

bool isSafeCacheName(const std::string& name) {
    return !name.empty() && name != "." && name != ".." &&
           name.find('/') == std::string::npos && name.find('\\') == std::string::npos &&
           name.find('\0') == std::string::npos;
}

bool readAssetDigest(
    AAsset* asset,
    std::int64_t expectedSize,
    Sha256::Digest& digest,
    std::string& error
) {
    Sha256 hash;
    std::array<std::uint8_t, kCopyBufferBytes> buffer{};
    std::int64_t total = 0;
    while (true) {
        const int count = AAsset_read(asset, buffer.data(), buffer.size());
        if (count < 0) {
            error = "Failed to read complete model asset";
            return false;
        }
        if (count == 0) break;
        if (total > expectedSize - count) {
            error = "Model asset length changed while hashing";
            return false;
        }
        (void)hash.update(buffer.data(), static_cast<std::size_t>(count));
        total += count;
    }
    if (total != expectedSize) {
        error = "Model asset length changed while hashing";
        return false;
    }
    digest = hash.digest();
    return true;
}

bool writeAll(int fd, const std::uint8_t* data, std::size_t size) {
    std::size_t written = 0;
    while (written < size) {
        const ssize_t count = ::write(fd, data + written, size - written);
        if (count < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        if (count == 0) return false;
        written += static_cast<std::size_t>(count);
    }
    return true;
}

bool syncDirectory(const std::string& path) {
    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC | O_DIRECTORY | O_NOFOLLOW);
    if (fd < 0) return false;
    const bool synced = ::fsync(fd) == 0;
    const bool closed = ::close(fd) == 0;
    return synced && closed;
}

} // namespace

ModelLoader& ModelLoader::getInstance() {
    static ModelLoader instance;
    return instance;
}

void ModelLoader::init(JavaVM* vm, jobject assetManager) {
    vm_ = vm;
    if (assetManager) {
        JNIEnv* env = getEnv();
        if (env) {
            assetManager_ = env->NewGlobalRef(assetManager);
        }
    }
}

JNIEnv* ModelLoader::getEnv() {
    return jni::getEnvForVm(vm_);
}

std::string ModelLoader::loadFromAssets(const std::string& assetPath, ModelLoadProgress progress) {
    lastError_.clear();
    if (!assetManager_) {
        lastError_ = "AssetManager not initialized";
        return "";
    }
    
    JNIEnv* env = getEnv();
    if (!env) {
        lastError_ = "Failed to get JNI environment";
        return "";
    }
    
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager_);
    if (!mgr) {
        lastError_ = "Failed to get native AssetManager";
        return "";
    }
    
    // Open asset
    AAsset* asset = AAssetManager_open(mgr, assetPath.c_str(), AASSET_MODE_STREAMING);
    if (!asset) {
        lastError_ = "Asset not found: " + assetPath;
        return "";
    }
    
    const off64_t size = AAsset_getLength64(asset);
    if (size <= 0 || static_cast<std::uint64_t>(size) > ModelIntegrityLimits::kMaxSingleFileBytes) {
        AAsset_close(asset);
        lastError_ = "Model asset is empty or exceeds the 8 GiB limit";
        return "";
    }
    
    // Determine cache path
    std::string filename = assetPath;
    size_t pos = filename.find_last_of('/');
    if (pos != std::string::npos) {
        filename = filename.substr(pos + 1);
    }
    if (cacheDir_.empty() || !isSafeCacheName(filename)) {
        AAsset_close(asset);
        lastError_ = "Invalid model cache path";
        return "";
    }
    std::string cachePath = cacheDir_ + "/" + filename;

    Sha256::Digest assetDigest{};
    if (!readAssetDigest(asset, size, assetDigest, lastError_)) {
        AAsset_close(asset);
        return "";
    }
    AAsset_close(asset);

    const std::string expectedSha256 = sha256ToHex(assetDigest);
    const ModelPathDigestResult cached = digestPath(cachePath, expectedSha256);
    if (cached) {
        lastError_.clear();
        LOG_I("ModelLoader", "Using verified cached model: %s", cachePath.c_str());
        return cachePath;
    }

    asset = AAssetManager_open(mgr, assetPath.c_str(), AASSET_MODE_STREAMING);
    if (!asset) {
        lastError_ = "Asset disappeared before staging: " + assetPath;
        return "";
    }

    std::string tempPath;
    int outputFd = -1;
    for (int attempt = 0; attempt < 64 && outputFd < 0; ++attempt) {
        tempPath = cachePath + ".partial-" +
            std::to_string(static_cast<long long>(::getpid())) + "-" +
            std::to_string(g_tempFileSequence.fetch_add(1, std::memory_order_relaxed));
        outputFd = ::open(
            tempPath.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600
        );
        if (outputFd < 0 && errno != EEXIST) break;
    }
    if (outputFd < 0) {
        AAsset_close(asset);
        lastError_ = "Failed to create model staging file";
        return "";
    }

    bool staged = true;
    std::array<std::uint8_t, kCopyBufferBytes> buffer{};
    Sha256 stagedHash;
    std::int64_t bytesRead = 0;
    while (true) {
        const int count = AAsset_read(asset, buffer.data(), buffer.size());
        if (count < 0) {
            staged = false;
            lastError_ = "Failed to read complete model asset";
            break;
        }
        if (count == 0) break;
        if (bytesRead > size - count ||
            !writeAll(outputFd, buffer.data(), static_cast<std::size_t>(count))) {
            staged = false;
            lastError_ = "Failed to write complete model staging file";
            break;
        }
        (void)stagedHash.update(buffer.data(), static_cast<std::size_t>(count));
        bytesRead += count;
        if (progress) {
            progress(bytesRead, size);
        }
    }

    if (bytesRead != size || !sha256Equal(stagedHash.digest(), assetDigest)) {
        staged = false;
        if (lastError_.empty()) lastError_ = "Model asset changed while staging";
    }
    if (staged && ::fsync(outputFd) != 0) {
        staged = false;
        lastError_ = "Failed to sync model staging file";
    }
    if (::close(outputFd) != 0 && staged) {
        staged = false;
        lastError_ = "Failed to close model staging file";
    }
    AAsset_close(asset);

    if (!staged || ::rename(tempPath.c_str(), cachePath.c_str()) != 0) {
        if (staged) lastError_ = "Failed to atomically publish cached model";
        (void)::unlink(tempPath.c_str());
        return "";
    }
    if (!syncDirectory(cacheDir_)) {
        (void)::unlink(cachePath.c_str());
        (void)syncDirectory(cacheDir_);
        lastError_ = "Failed to durably publish cached model";
        return "";
    }

    const ModelPathDigestResult installed = digestPath(cachePath, expectedSha256);
    if (!installed) {
        (void)::unlink(cachePath.c_str());
        lastError_ = "Published model failed SHA-256 verification";
        return "";
    }

    lastError_.clear();
    LOG_I("ModelLoader", "Cached verified model: %s (%ld bytes)", cachePath.c_str(), static_cast<long>(size));
    return cachePath;
}

std::string ModelLoader::loadFromSAF(const std::string& safUri, ModelLoadProgress progress) {
    // SAF loading requires Java callback - implemented in Kotlin
    // This is a placeholder that returns the URI for Kotlin to handle
    lastError_ = "SAF loading must be done from Kotlin layer";
    return "";
}

std::string ModelLoader::loadFromPath(const std::string& path) {
    const ModelPathDigestResult inspected = digestPath(path);
    if (!inspected) {
        lastError_ = std::string("Invalid model path: ") +
            modelIntegrityErrorMessage(inspected.error);
        return "";
    }
    lastError_.clear();
    return path;
}

void ModelLoader::clearCache() {
    if (cacheDir_.empty()) return;
    
    DIR* dir = opendir(cacheDir_.c_str());
    if (!dir) return;
    
    struct dirent* entry;
    while ((entry = readdir(dir)) != nullptr) {
        if (entry->d_type == DT_REG) {
            std::string path = cacheDir_ + "/" + entry->d_name;
            remove(path.c_str());
        }
    }
    closedir(dir);
}

int64_t ModelLoader::getCacheSize() {
    if (cacheDir_.empty()) return 0;
    
    int64_t total = 0;
    DIR* dir = opendir(cacheDir_.c_str());
    if (!dir) return 0;
    
    struct dirent* entry;
    struct stat st;
    while ((entry = readdir(dir)) != nullptr) {
        if (entry->d_type == DT_REG) {
            std::string path = cacheDir_ + "/" + entry->d_name;
            if (stat(path.c_str(), &st) == 0) {
                total += st.st_size;
            }
        }
    }
    closedir(dir);
    return total;
}

std::vector<ModelInfo> ModelLoader::listCachedModels() {
    std::vector<ModelInfo> models;
    if (cacheDir_.empty()) return models;
    
    DIR* dir = opendir(cacheDir_.c_str());
    if (!dir) return models;
    
    struct dirent* entry;
    struct stat st;
    while ((entry = readdir(dir)) != nullptr) {
        if (entry->d_type == DT_REG) {
            std::string path = cacheDir_ + "/" + entry->d_name;
            if (stat(path.c_str(), &st) == 0) {
                ModelInfo info;
                info.name = entry->d_name;
                info.path = path;
                info.source = ModelSource::CACHE;
                info.sizeBytes = st.st_size;
                models.push_back(info);
            }
        }
    }
    closedir(dir);
    return models;
}

std::vector<ModelInfo> ModelLoader::listAssetModels() {
    std::vector<ModelInfo> models;
    // Asset listing requires Java - implemented in Kotlin
    return models;
}

bool ModelLoader::validateModel(const std::string& path) {
    const ModelPathDigestResult inspected = digestPath(path);
    if (!inspected) {
        lastError_ = std::string("Model validation failed: ") +
            modelIntegrityErrorMessage(inspected.error);
        return false;
    }
    lastError_.clear();
    return true;
}

std::string ModelLoader::getModelChecksum(const std::string& path) {
    const ModelPathDigestResult inspected = digestPath(path);
    if (!inspected) {
        lastError_ = std::string("Model checksum failed: ") +
            modelIntegrityErrorMessage(inspected.error);
        return "";
    }
    lastError_.clear();
    return inspected.digestHex();
}

} // namespace stt
