#include "manga_line_ocr.h"
#include "../common/lease_registry.h"
#include "../jni/jni_helper.h"

#include <array>

namespace {

using hearth::ocr::Box;
using hearth::ocr::MangaLineOcr;
stt::concurrency::LeaseRegistry<MangaLineOcr> models;

std::string modelPath(JNIEnv* env, jbyteArray bytes) {
    const int size = bytes ? env->GetArrayLength(bytes) : 0;
    if (size < 1 || size > 4096) throw std::invalid_argument("Invalid Manga line OCR model path");
    std::string path(size, '\0');
    env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(path.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Cannot read Manga line OCR model path");
    if (path.find('\0') != std::string::npos) throw std::invalid_argument("NUL in Manga line OCR model path");
    return path;
}

std::vector<uint32_t> pagePixels(JNIEnv* env, jintArray argb, int width, int height) {
    if (!argb || width < 1 || height < 1 || width > MangaLineOcr::maxImageSide || height > MangaLineOcr::maxImageSide
        || env->GetArrayLength(argb) != width * height)
        throw std::invalid_argument("Invalid Manga line OCR pixels");
    std::vector<uint32_t> pixels(static_cast<size_t>(width) * height);
    env->GetIntArrayRegion(argb, 0, width * height, reinterpret_cast<jint*>(pixels.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Cannot read Manga line OCR pixels");
    return pixels;
}

std::vector<Box> cropBoxes(JNIEnv* env, jintArray flat) {
    const int size = flat ? env->GetArrayLength(flat) : -1;
    if (size < 0 || size > 4 * MangaLineOcr::maxLines || size % 4 != 0)
        throw std::invalid_argument("Invalid Manga line OCR crop coordinates");
    std::array<jint, 4 * MangaLineOcr::maxLines> values;
    if (size != 0) env->GetIntArrayRegion(flat, 0, size, values.data());
    if (env->ExceptionCheck()) throw std::runtime_error("Cannot read Manga line OCR crop coordinates");
    std::vector<Box> boxes;
    boxes.reserve(size / 4);
    for (int i = 0; i < size; i += 4) boxes.push_back({values[i], values[i + 1], values[i + 2], values[i + 3], 0.f});
    return boxes;
}

jobjectArray newRows(JNIEnv* env, size_t size) {
    const auto rowType = env->FindClass("[I");
    if (!rowType) return nullptr;
    const auto rows = env->NewObjectArray(static_cast<jsize>(size), rowType, nullptr);
    env->DeleteLocalRef(rowType);
    return rows;
}

bool writeRow(JNIEnv* env, jobjectArray rows, size_t index, const jint* values, size_t size) {
    const auto row = env->NewIntArray(static_cast<jsize>(size));
    if (!row) return false;
    if (size != 0) env->SetIntArrayRegion(row, 0, static_cast<jsize>(size), values);
    if (!env->ExceptionCheck()) env->SetObjectArrayElement(rows, static_cast<jsize>(index), row);
    env->DeleteLocalRef(row);
    return !env->ExceptionCheck();
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL Java_com_sal7one_common_1jni_ocr_MangaLineNative_create(
    JNIEnv* env, jobject, jbyteArray detector, jbyteArray recognizer, jint workers) {
    try {
        const auto detectorPath = modelPath(env, detector);
        const auto recognizerPath = modelPath(env, recognizer);
        const auto handle = models.insert(std::make_unique<MangaLineOcr>(detectorPath, recognizerPath, workers));
        if (!handle) throw std::runtime_error("Manga line OCR handle limit reached");
        return handle;
    } catch (const std::exception& error) {
        jni::throwIllegalStateException(env, error.what());
    } catch (...) {
        jni::throwIllegalStateException(env, "Manga line OCR initialization failed");
    }
    return 0;
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_com_sal7one_common_1jni_ocr_MangaLineNative_detect(
    JNIEnv* env, jobject, jlong id, jintArray argb, jint width, jint height, jint maxSide) {
    try {
        auto model = models.acquire(id);
        if (!model) throw std::invalid_argument("Manga line OCR is closed");
        const auto pixels = pagePixels(env, argb, width, height);
        const auto boxes = model->detect(pixels.data(), width, height, maxSide);
        const auto rows = newRows(env, boxes.size());
        if (!rows) return nullptr;
        for (size_t i = 0; i < boxes.size(); ++i) {
            const auto& box = boxes[i];
            const jint values[] = {box.x, box.y, box.width, box.height,
                static_cast<jint>(std::clamp(box.score, 0.f, 1.f) * 10000)};
            if (!writeRow(env, rows, i, values, std::size(values))) return nullptr;
        }
        return rows;
    } catch (const std::exception& error) {
        jni::throwIllegalStateException(env, error.what());
    } catch (...) {
        jni::throwIllegalStateException(env, "Manga line detection failed");
    }
    return nullptr;
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_com_sal7one_common_1jni_ocr_MangaLineNative_recognize(
    JNIEnv* env, jobject, jlong id, jintArray argb, jint width, jint height, jintArray flatBoxes) {
    try {
        auto model = models.acquire(id);
        if (!model) throw std::invalid_argument("Manga line OCR is closed");
        const auto boxes = cropBoxes(env, flatBoxes);
        const auto pixels = pagePixels(env, argb, width, height);
        const auto tokens = model->recognize(pixels.data(), width, height, boxes);
        const auto rows = newRows(env, tokens.size());
        if (!rows) return nullptr;
        for (size_t i = 0; i < tokens.size(); ++i)
            if (!writeRow(env, rows, i, tokens[i].data(), tokens[i].size())) return nullptr;
        return rows;
    } catch (const std::exception& error) {
        jni::throwIllegalStateException(env, error.what());
    } catch (...) {
        jni::throwIllegalStateException(env, "Manga line recognition failed");
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_ocr_MangaLineNative_cancel(JNIEnv*, jobject, jlong id) {
    auto model = models.acquire(id);
    if (model) model->cancel();
}

extern "C" JNIEXPORT void JNICALL Java_com_sal7one_common_1jni_ocr_MangaLineNative_destroy(JNIEnv*, jobject, jlong id) {
    auto retired = models.retire(id);
    if (auto* model = retired.signalTarget()) model->cancel();
    auto exclusive = std::move(retired).lockExclusive();
}
