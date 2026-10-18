#include "../marian_decoder.h"
#include <algorithm>
#include <cstdlib>
#include <iostream>
#include <unordered_set>

struct OrtValue {
    void* data = nullptr;
    std::vector<int64_t> shape;
    std::vector<float> owned;
};
struct OrtStatus {};

namespace {
using stt::marian::detail::kDecoderPastCount;
constexpr size_t vocab = 5;
std::unordered_set<OrtValue*> live;
std::array<const OrtValue*, 4> fixedInputs{};
std::array<const OrtValue*, kDecoderPastCount> previous{};
std::array<const OrtValue*, kDecoderPastCount> first{};
int creates, views, allocations, reads, runs, releases, statusReleases;
int failCreate, failRun, finishAfter;
bool failRead, contractOkay;
OrtStatus injected;

void require(bool condition, const char* label) {
    if (!condition) { std::cerr << "FAIL: " << label << '\n'; std::exit(1); }
}
OrtValue* value(const int64_t* shape, size_t size) {
    auto* result = new OrtValue;
    result->shape.assign(shape, shape + size);
    live.insert(result);
    return result;
}
OrtStatus* ORT_API_CALL view(const OrtMemoryInfo*, void* data, size_t,
                            const int64_t* shape, size_t size,
                            ONNXTensorElementDataType, OrtValue** out) noexcept {
    ++views;
    if (++creates == failCreate) return &injected;
    *out = value(shape, size);
    (*out)->data = data;
    return nullptr;
}
OrtStatus* ORT_API_CALL allocate(OrtAllocator*, const int64_t* shape, size_t size,
                                ONNXTensorElementDataType, OrtValue** out) noexcept {
    ++allocations;
    if (++creates == failCreate) return &injected;
    *out = value(shape, size);
    size_t count = 1;
    for (size_t i = 0; i < size; ++i) count *= static_cast<size_t>(shape[i]);
    (*out)->owned.resize(count);
    (*out)->data = (*out)->owned.data();
    return nullptr;
}
OrtStatus* ORT_API_CALL read(OrtValue* tensor, void** out) noexcept {
    ++reads;
    if (failRead) return &injected;
    *out = tensor->data;
    return nullptr;
}
void ORT_API_CALL release(OrtValue* tensor) noexcept {
    require(live.erase(tensor) == 1, "OrtValue released exactly once");
    ++releases;
    delete tensor;
}
void ORT_API_CALL releaseStatus(OrtStatus*) noexcept { ++statusReleases; }
const char* ORT_API_CALL message(const OrtStatus*) noexcept { return "injected provider failure"; }
OrtStatus* ORT_API_CALL run(OrtSession*, const OrtRunOptions*, const char* const*,
                           const OrtValue* const* inputs, size_t inputCount,
                           const char* const*, size_t outputCount, OrtValue** outputs) noexcept {
    contractOkay &= inputCount == 28 && outputCount == 25;
    contractOkay &= *static_cast<const uint8_t*>(inputs[3]->data) == (runs == 0 ? 0 : 1);
    contractOkay &= *static_cast<const int64_t*>(inputs[0]->data) == (runs == 0 ? 4 : 1);
    for (size_t i = 0; i < 4; ++i) {
        if (runs == 0) fixedInputs[i] = inputs[i];
        contractOkay &= inputs[i] == fixedInputs[i];
    }
    for (size_t i = 0; i < kDecoderPastCount; ++i) {
        if (runs == 0) {
            contractOkay &= inputs[4 + i]->shape == std::vector<int64_t>({1, 8, 0, 64});
        } else {
            const bool encoder = i % 4 >= 2;
            contractOkay &= inputs[4 + i] == (encoder ? first[i] : previous[i]);
            contractOkay &= live.count(const_cast<OrtValue*>(inputs[4 + i])) == 1;
        }
    }
    auto* logits = static_cast<float*>(outputs[0]->data);
    std::fill(logits, logits + vocab, -10.0F);
    logits[4] = 100.0F; // pad must be masked without modifying the shared buffer.
    logits[runs + 1 == finishAfter ? 0 : 1] = 1.0F;
    const size_t outputsToCreate = runs == failRun ? 7 : kDecoderPastCount;
    for (size_t i = 0; i < outputsToCreate; ++i) {
        const bool encoder = i % 4 >= 2;
        const int64_t shape[4] = {encoder && runs != 0 ? 0 : 1, 8,
            encoder ? 2 : runs + 1, 64};
        outputs[1 + i] = value(shape, 4);
        previous[i] = outputs[1 + i];
        if (runs == 0) first[i] = outputs[1 + i];
    }
    return runs++ == failRun ? &injected : nullptr;
}
void reset() {
    require(live.empty(), "no retained values between captions");
    creates = views = allocations = reads = runs = releases = statusReleases = 0;
    failCreate = -1; failRun = -1; finishAfter = 4;
    failRead = false; contractOkay = true;
}
OrtApi api() {
    OrtApi result{};
    result.CreateTensorWithDataAsOrtValue = view;
    result.CreateTensorAsOrtValue = allocate;
    result.GetTensorMutableData = read;
    result.ReleaseValue = release;
    result.GetErrorMessage = message;
    result.ReleaseStatus = releaseStatus;
    result.Run = run;
    return result;
}

bool translate(const OrtApi& ort, std::vector<int64_t>& ids, std::string& error,
               int cap = 128, int stopCall = -1, int64_t pad = 4) {
    const float hidden[1024]{};
    const int64_t attention[2] = {1, 1};
    int calls = 0;
    return stt::marian::detail::runGreedyDecoder(&ort, nullptr, nullptr, nullptr, nullptr,
        nullptr, nullptr, hidden, attention, 2, vocab, pad, 0, cap, ids, &error,
        [&calls, stopCall](std::string* cause) {
            if (calls++ != stopCall) return false;
            *cause = "cancelled";
            return true;
        });
}
}

int main() {
    const auto ort = api();
    std::vector<int64_t> ids;
    std::string error;
    reset();
    require(translate(ort, ids, error), "greedy decode completes");
    require(ids == std::vector<int64_t>({1, 1, 1}), "same masked greedy tokens and EOS removal");
    require(contractOkay, "encoder caches retained; decoder values fed back by identity; invariant views reused");
    require(views == 5 && allocations == 1 && reads == 1 && runs == 4,
            "constant wrappers and logits allocation, no cache or logits readbacks");
    require(live.empty(), "success frees all caches and tensors");
    for (int failure = 1; failure <= 6; ++failure) {
        reset(); failCreate = failure;
        require(!translate(ort, ids, error), "tensor setup error surfaced");
        require(error.find("injected provider failure") != std::string::npos && statusReleases == 1,
                "native tensor error retained and released");
        require(live.empty(), "partial tensor setup freed");
    }
    reset(); failRead = true;
    require(!translate(ort, ids, error) && live.empty(), "logits setup failure cleaned");
    for (int failure : {0, 2}) {
        reset(); failRun = failure;
        require(!translate(ort, ids, error), "Run error surfaced");
        require(error.find("injected provider failure") != std::string::npos,
                "Run provider cause retained");
        require(contractOkay && live.empty(), "partial Run outputs and prior caches released");
    }
    for (int stop : {0, 1, 4, 5}) {
        reset();
        require(!translate(ort, ids, error, 128, stop) && error == "cancelled",
                "cancellation checked around every Run");
        require(live.empty(), "cancelled decoder freed");
    }
    reset(); finishAfter = 1000;
    require(!translate(ort, ids, error, 3) && runs == 3,
            "output cap prevents extra token Run");
    require(error.find("incomplete text was not published") != std::string::npos && live.empty(),
            "cap remains a failure with no resource leak");
    reset();
    require(!translate(ort, ids, error, 128, -1, 5) && creates == 0,
            "invalid token index rejected before tensor access");
    reset(); finishAfter = 128;
    require(translate(ort, ids, error) && ids.size() == 127 && contractOkay && live.empty(),
            "long cache chain preserves ownership and cross-attention");
    std::cout << "marian_decoder_test: PASS; 128 decoder runs: " << views
              << " input views, " << allocations << " logits allocation, " << reads
              << " tensor readback; zero wrapper-side KV/logits copies\n";
}
