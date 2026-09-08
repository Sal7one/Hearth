#ifndef STT_MODEL_LOADER_H
#define STT_MODEL_LOADER_H

#include <string>
#include <vector>
#include <functional>
#include <jni.h>

namespace stt {

enum class ModelSource {
    ASSETS,      // app/src/main/assets/
    INTERNAL,    // app internal storage
    EXTERNAL,    // SAF-accessible external storage
    CACHE        // app cache directory
};

struct ModelInfo {
    std::string name;
    std::string path;
    ModelSource source;
    int64_t sizeBytes;
    std::string checksum;
};

using ModelLoadProgress = std::function<void(int64_t bytesLoaded, int64_t totalBytes)>;

class ModelLoader {
public:
    static ModelLoader& getInstance();
    
    // Initialize with JNI context (call from JNI_OnLoad)
    void init(JavaVM* vm, jobject assetManager);
    
    // Load model from various sources
    // Returns path to loaded model (may be cached copy)
    std::string loadFromAssets(const std::string& assetPath, ModelLoadProgress progress = nullptr);
    std::string loadFromSAF(const std::string& safUri, ModelLoadProgress progress = nullptr);
    std::string loadFromPath(const std::string& path);
    
    // Cache management
    void setCacheDir(const std::string& dir) { cacheDir_ = dir; }
    std::string getCacheDir() const { return cacheDir_; }
    void clearCache();
    int64_t getCacheSize();
    
    // Model discovery
    std::vector<ModelInfo> listCachedModels();
    std::vector<ModelInfo> listAssetModels();
    
    // Validation. Directories use the deterministic model-tree SHA-256 contract.
    bool validateModel(const std::string& path);
    std::string getModelChecksum(const std::string& path);
    
    // Error handling
    std::string getLastError() const { return lastError_; }

private:
    ModelLoader() = default;
    
    JavaVM* vm_{nullptr};
    jobject assetManager_{nullptr};
    std::string cacheDir_;
    std::string lastError_;
    
    JNIEnv* getEnv();
    bool copyAssetToCache(const std::string& assetPath, const std::string& destPath, ModelLoadProgress progress);
};

} // namespace stt

#endif // STT_MODEL_LOADER_H
