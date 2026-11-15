#include "manga_line_ocr.h"

#include "manga_line_geometry.h"
#include "model_path.h"
#include "../common/job_system.h"
#include "../onnx/include/onnxruntime/onnxruntime_c_api.h"

#include <array>
#include <atomic>
#include <numeric>

namespace hearth::ocr {
namespace {

constexpr int kRecognizerHeight = 48;
constexpr int kRecognizerMaxWidth = 1600;
constexpr int kRecognizerClasses = 19055;
constexpr int kMaxWorkers = 6;

const OrtApi& api() {
    static const auto* instance = OrtGetApiBase()->GetApi(ORT_API_VERSION);
    if (!instance) throw std::runtime_error("ONNX Runtime API unavailable for Manga line OCR");
    return *instance;
}

template<class T, auto Release>
struct OrtRelease {
    void operator()(T* value) const noexcept { (api().*Release)(value); }
};

template<class T, auto Release>
using Owned = std::unique_ptr<T, OrtRelease<T, Release>>;

using Env = Owned<OrtEnv, &OrtApi::ReleaseEnv>;
using SessionOptions = Owned<OrtSessionOptions, &OrtApi::ReleaseSessionOptions>;
using Memory = Owned<OrtMemoryInfo, &OrtApi::ReleaseMemoryInfo>;
using Session = Owned<OrtSession, &OrtApi::ReleaseSession>;
using RunOptions = Owned<OrtRunOptions, &OrtApi::ReleaseRunOptions>;
using Value = Owned<OrtValue, &OrtApi::ReleaseValue>;
using Shape = Owned<OrtTensorTypeAndShapeInfo, &OrtApi::ReleaseTensorTypeAndShapeInfo>;

void check(OrtStatus* raw) {
    const Owned<OrtStatus, &OrtApi::ReleaseStatus> status(raw);
    if (status) throw std::runtime_error(api().GetErrorMessage(status.get()));
}

template<class Handle, class Create>
Handle makeOrt(Create create) {
    typename Handle::pointer raw = nullptr;
    auto* status = create(&raw);
    Handle value(raw);
    check(status);
    return value;
}

struct Model {
    Session session;
    std::string input;
    std::string output;
};

Model openModel(const OrtEnv* env, const std::string& path, int threads) {
    const auto options = makeOrt<SessionOptions>(api().CreateSessionOptions);
    check(api().SetIntraOpNumThreads(options.get(), threads));
    check(api().SetInterOpNumThreads(options.get(), 1));
    check(api().SetSessionGraphOptimizationLevel(options.get(), ORT_ENABLE_ALL));
    check(api().AddSessionConfigEntry(options.get(), "session.intra_op.allow_spinning", "0"));
    auto session = makeOrt<Session>([&](auto** result) {
        return api().CreateSession(env, nativeModelPath(path).c_str(), options.get(), result);
    });
    size_t inputs = 0, outputs = 0;
    check(api().SessionGetInputCount(session.get(), &inputs));
    check(api().SessionGetOutputCount(session.get(), &outputs));
    if (inputs != 1 || outputs != 1) throw std::runtime_error("Manga line OCR requires one image input and one tensor output");
    OrtAllocator* allocator = nullptr;
    check(api().GetAllocatorWithDefaultOptions(&allocator));
    auto name = [&](auto getName) {
        char* raw = nullptr;
        auto* status = getName(session.get(), 0, allocator, &raw);
        const auto release = [allocator](char* value) { allocator->Free(allocator, value); };
        std::unique_ptr<char, decltype(release)> value(raw, release);
        check(status);
        if (!value) throw std::runtime_error("Manga line OCR tensor name is missing");
        return std::string(value.get());
    };
    auto input = name(api().SessionGetInputName);
    auto output = name(api().SessionGetOutputName);
    return {std::move(session), std::move(input), std::move(output)};
}

template<size_t Rank>
std::array<int64_t, Rank> outputShape(const Value& value) {
    const auto info = makeOrt<Shape>([&](auto** result) { return api().GetTensorTypeAndShape(value.get(), result); });
    ONNXTensorElementDataType type;
    size_t rank = 0;
    check(api().GetTensorElementType(info.get(), &type));
    check(api().GetDimensionsCount(info.get(), &rank));
    if (type != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT || rank != Rank)
        throw std::runtime_error("Manga line OCR output tensor has an unexpected type or rank");
    std::array<int64_t, Rank> shape{};
    check(api().GetDimensions(info.get(), shape.data(), shape.size()));
    return shape;
}

const float* outputData(const Value& value) {
    void* data = nullptr;
    check(api().GetTensorMutableData(value.get(), &data));
    if (!data) throw std::runtime_error("Manga line OCR returned an empty tensor");
    return static_cast<const float*>(data);
}

int checkedWorkers(int workers) {
    if (workers < 1 || workers > kMaxWorkers) throw std::invalid_argument("Manga line OCR worker count must be 1–6");
    return workers;
}

void checkImage(const uint32_t* pixels, int width, int height) {
    if (!pixels || width < 1 || height < 1 || width > MangaLineOcr::maxImageSide || height > MangaLineOcr::maxImageSide)
        throw std::invalid_argument("Manga line OCR image must be at most 2048 pixels per side");
}

struct ImageView {
    const uint32_t* first;
    int width, height, xStep, yStep;
};

struct Sample {
    int low, high;
    int lowWeight, highWeight;
};

constexpr int kResizeWeight = 1 << 11;

std::vector<Sample> sampleAxis(int size, int target, bool horizontal) {
    std::vector<Sample> samples(target);
    const double ratio = 1.0 / (double(target) / size);
    for (int i = 0; i < target; ++i) {
        const float coordinate = float((i + .5) * ratio - .5);
        const int first = int(std::floor(coordinate));
        const bool edge = first < 0 || first >= size - 1;
        const float fraction = horizontal && edge ? 0.f : coordinate - first;
        const int low = std::clamp(first, 0, size - 1), high = std::clamp(first + 1, 0, size - 1);
        samples[i] = {low, high, int(std::nearbyint((1.f - fraction) * kResizeWeight)),
                     int(std::nearbyint(fraction * kResizeWeight))};
    }
    return samples;
}

using Normalizer = std::array<std::array<float, 256>, 3>;

const Normalizer& normalizer(bool detector) {
    static const auto detection = [] {
        Normalizer values{};
        const float mean[] = {.485f, .456f, .406f}, stddev[] = {.229f, .224f, .225f};
        for (int channel = 0; channel < 3; ++channel)
            for (int pixel = 0; pixel < 256; ++pixel)
                values[channel][pixel] = (pixel / 255.f - mean[channel]) / stddev[channel];
        return values;
    }();
    static const auto recognition = [] {
        Normalizer values{};
        for (int pixel = 0; pixel < 256; ++pixel) values[0][pixel] = pixel / 127.5f - 1.f;
        values[1] = values[2] = values[0];
        return values;
    }();
    return detector ? detection : recognition;
}

std::vector<float> resizeBgr(const ImageView& source, int width, int height, bool detector) {
    const auto horizontal = sampleAxis(source.width, width, true);
    const auto vertical = sampleAxis(source.height, height, false);
    const auto& normalized = normalizer(detector);
    const auto plane = size_t(width) * height;
    std::vector<float> tensor(3 * plane);
    const bool half = source.width == 2 * width && source.height == 2 * height;
    for (int y = 0; y < height; ++y) {
        const auto [y0, y1, wy0, wy1] = vertical[y];
        const auto* top = source.first + y0 * source.yStep;
        const auto* bottom = source.first + y1 * source.yStep;
        for (int x = 0; x < width; ++x) {
            const auto [x0, x1, wx0, wx1] = horizontal[x];
            const int left = x0 * source.xStep, right = x1 * source.xStep;
            const uint32_t tl = top[left], tr = top[right], bl = bottom[left], br = bottom[right];
            for (int channel = 0; channel < 3; ++channel) {
                const auto shift = channel * 8;
                const int a = (tl >> shift) & 255, b = (tr >> shift) & 255;
                const int c = (bl >> shift) & 255, d = (br >> shift) & 255;
                // Match the reference's separable 8-bit interpolation rounding before normalization.
                const int topMix = a * wx0 + b * wx1, bottomMix = c * wx0 + d * wx1;
                const int upper = (wy0 * (topMix >> 4)) >> 16;
                const int lower = (wy1 * (bottomMix >> 4)) >> 16;
                const int pixel = half ? (a + b + c + d + 2) >> 2 : (upper + lower + 2) >> 2;
                tensor[channel * plane + y * width + x] = normalized[channel][pixel];
            }
        }
    }
    return tensor;
}

int recognizerWidth(const Box& box) {
    const int longer = std::max(box.width, box.height);
    const int shorter = std::min(box.width, box.height);
    return std::clamp(int(std::nearbyint(double(kRecognizerHeight) * longer / shorter)), 16, kRecognizerMaxWidth);
}

ImageView cropView(const uint32_t* pixels, int width, const Box& box) {
    const auto* topLeft = pixels + size_t(box.y) * width + box.x;
    if (box.height > box.width) {
        // CCW rotation maps (x, y) to (originalWidth - 1 - y, x).
        return {topLeft + box.width - 1, box.height, box.width, width, -1};
    }
    return {topLeft, box.width, box.height, 1, width};
}

} // namespace

struct MangaLineOcr::Impl {
    const Env env = makeOrt<Env>([](auto** result) { return api().CreateEnv(ORT_LOGGING_LEVEL_ERROR, "hearth-manga-lines", result); });
    const Memory memory = makeOrt<Memory>([](auto** result) { return api().CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, result); });
    const Model detector;
    const Model recognizer;
    std::mutex pageMutex;
    std::mutex runMutex;
    std::array<OrtRunOptions*, kMaxWorkers> activeRuns{};
    std::atomic<bool> cancelled{false};
    stt::JobSystem pool;

    Impl(const std::string& detectorPath, const std::string& recognizerPath, int workers)
        : detector(openModel(env.get(), detectorPath, workers)), recognizer(openModel(env.get(), recognizerPath, 1)), pool(workers) {}

    void ensureActive() const {
        if (cancelled.load()) throw std::runtime_error("Manga line OCR cancelled");
    }

    struct ActiveRun {
        Impl& owner;
        const RunOptions options = makeOrt<RunOptions>(api().CreateRunOptions);
        std::array<OrtRunOptions*, kMaxWorkers>::iterator slot;

        explicit ActiveRun(Impl& reader) : owner(reader) {
            std::lock_guard<std::mutex> lock(owner.runMutex);
            owner.ensureActive();
            slot = std::find(owner.activeRuns.begin(), owner.activeRuns.end(), nullptr);
            if (slot == owner.activeRuns.end()) throw std::logic_error("Manga line OCR inference exceeded its worker bound");
            *slot = options.get();
        }

        ~ActiveRun() {
            std::lock_guard<std::mutex> lock(owner.runMutex);
            *slot = nullptr;
        }
    };

    Value infer(const Model& model, std::vector<float>& data, int width, int height) {
        const ActiveRun run(*this);
        const int64_t dims[] = {1, 3, height, width};
        const auto tensor = makeOrt<Value>([&](auto** result) {
            return api().CreateTensorWithDataAsOrtValue(memory.get(), data.data(), data.size() * sizeof(float),
                dims, 4, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, result);
        });
        const char* inputs[] = {model.input.c_str()};
        const char* outputs[] = {model.output.c_str()};
        const OrtValue* values[] = {tensor.get()};
        auto result = makeOrt<Value>([&](auto** output) {
            return api().Run(model.session.get(), run.options.get(), inputs, values, 1, outputs, 1, output);
        });
        ensureActive();
        return result;
    }

    std::vector<int> readLine(const uint32_t* pixels, int pageWidth, const Box& box, int width) {
        ensureActive();
        auto input = resizeBgr(cropView(pixels, pageWidth, box), width, kRecognizerHeight, false);
        const auto output = infer(recognizer, input, width, kRecognizerHeight);
        const auto dims = outputShape<3>(output);
        if (dims[0] != 1 || dims[1] < 1 || dims[1] > (kRecognizerMaxWidth + 7) / 8 || dims[2] != kRecognizerClasses)
            throw std::runtime_error("Manga line OCR output does not match the pinned dictionary");
        return ctc(outputData(output), int(dims[1]), kRecognizerClasses).tokens;
    }
};

MangaLineOcr::MangaLineOcr(const std::string& detector, const std::string& recognizer, int workers)
    : impl(std::make_unique<Impl>(detector, recognizer, checkedWorkers(workers))) {}

MangaLineOcr::~MangaLineOcr() = default;

std::vector<Box> MangaLineOcr::detect(const uint32_t* pixels, int width, int height, int maxSide) {
    checkImage(pixels, width, height);
    if (maxSide < 320 || maxSide > 960) throw std::invalid_argument("Manga line OCR detector resolution must be 320–960 pixels");
    std::lock_guard<std::mutex> lock(impl->pageMutex);
    impl->ensureActive();
    const int longer = std::max(width, height), shorter = std::min(width, height);
    const double scale = longer > 2 * shorter
        ? std::min(1.0, std::sqrt(.75 * maxSide * maxSide / (width * height)))
        : double(maxSide) / longer;
    const auto resized = [scale](int size) { return std::max(32, int(std::nearbyint(size * scale / 32)) * 32); };
    const int modelWidth = resized(width), modelHeight = resized(height);
    auto input = resizeBgr({pixels, width, height, 1, width}, modelWidth, modelHeight, true);
    const auto output = impl->infer(impl->detector, input, modelWidth, modelHeight);
    const auto dims = outputShape<4>(output);
    if (dims != std::array<int64_t, 4>{1, 1, modelHeight, modelWidth})
        throw std::runtime_error("Manga line OCR detector returned an unexpected map size");
    auto boxes = mangaRegions(outputData(output), modelWidth, modelHeight, width, height);
    impl->ensureActive();
    return boxes;
}

std::vector<std::vector<int>> MangaLineOcr::recognize(const uint32_t* pixels, int width, int height,
                                                       const std::vector<Box>& boxes) {
    checkImage(pixels, width, height);
    if (boxes.size() > maxLines) throw std::invalid_argument("Manga line OCR can read at most 64 regions per page");
    std::lock_guard<std::mutex> lock(impl->pageMutex);
    impl->ensureActive();
    std::vector<int> widths;
    widths.reserve(boxes.size());
    for (const auto& box : boxes) {
        if (box.x < 0 || box.y < 0 || box.width < 1 || box.height < 1 || box.width > width || box.height > height
            || box.x > width - box.width || box.y > height - box.height)
            throw std::invalid_argument("Manga line OCR crop is outside the image");
        widths.push_back(recognizerWidth(box));
    }
    std::vector<std::vector<int>> rows(boxes.size());
    std::vector<size_t> order(boxes.size());
    std::iota(order.begin(), order.end(), size_t(0));
    std::stable_sort(order.begin(), order.end(), [&](size_t a, size_t b) { return widths[a] > widths[b]; });
    std::atomic<size_t> next{0};
    std::atomic<bool> failed{false};
    const auto work = [&] {
        try {
            while (!failed.load(std::memory_order_relaxed)) {
                const auto position = next.fetch_add(1, std::memory_order_relaxed);
                if (position >= order.size()) return;
                const auto index = order[position];
                rows[index] = impl->readLine(pixels, width, boxes[index], widths[index]);
            }
        } catch (...) {
            failed.store(true, std::memory_order_relaxed);
            throw;
        }
    };
    const auto count = std::min(boxes.size(), impl->pool.workerCount());
    std::vector<std::future<void>> futures;
    futures.reserve(count);
    try {
        for (size_t i = 0; i < count; ++i) futures.push_back(impl->pool.submitWithResult<void>(work));
    } catch (...) {
        failed.store(true, std::memory_order_relaxed);
        impl->pool.waitAll();
        throw;
    }
    stt::detail::waitForJobFutures(futures);
    impl->ensureActive();
    return rows;
}

void MangaLineOcr::cancel() noexcept {
    impl->cancelled.store(true);
    std::lock_guard<std::mutex> lock(impl->runMutex);
    for (auto* options : impl->activeRuns) {
        if (options) if (auto* status = api().RunOptionsSetTerminate(options)) api().ReleaseStatus(status);
    }
}

} // namespace hearth::ocr
