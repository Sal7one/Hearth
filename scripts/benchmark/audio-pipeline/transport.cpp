#include "../../../desktop-media/src/main/cpp/system_audio_pcm.h"
#include "legacy_system_audio_pcm.h"

#include <array>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <exception>
#include <limits>
#include <thread>

extern "C" {
void* hearth_bench_pcm_ring_create();
void hearth_bench_pcm_ring_destroy(void* ring);
int32_t hearth_bench_pcm_ring_push(const void* ring, const uint8_t* input, size_t length);
int32_t hearth_bench_pcm_ring_pop(const void* ring, uint8_t* output, size_t capacity, size_t* written);
void hearth_bench_pcm_ring_finish(const void* ring);
}

namespace {
constexpr size_t frameBytes = hearth::kSystemAudioFrameBytes;
constexpr size_t frameSamples = frameBytes / 2;
constexpr size_t capacity = hearth::kSystemAudioQueueFrames;
constexpr int32_t empty = 0, frame = 1, ended = 2, invalid = -1, overflow = -2, closed = -3;
using Bytes = std::array<uint8_t, frameBytes>;

struct FrameView {
    const uint8_t* data = nullptr;
    size_t size = 0;
};

class LegacySystemPipeline {
public:
    void submit(const uint8_t* data, size_t size) { pcm.signed16(data, size / 2); }
    void submit_float(const float* data, size_t size) { pcm.floats(data, size); }
    void finish() { pcm.finish(); queue.finish(); }
    FrameView read() {
        return queue.pop(received) ? FrameView{received.data(), received.size()} : FrameView{};
    }
    bool failed() const { return !queue.error().empty(); }

private:
    legacy_hearth::SystemAudioQueue queue;
    legacy_hearth::SystemAudioPcm pcm{[this](std::vector<uint8_t> data) { return queue.push(std::move(data)); }};
    std::vector<uint8_t> received;
};

class SystemPipeline {
public:
    void submit(const uint8_t* data, size_t size) { pcm.signed16(data, size / 2); }
    void submit_float(const float* data, size_t size) { pcm.floats(data, size); }
    void finish() { pcm.finish(); queue.finish(); }
    FrameView read() {
        const auto frame = queue.pop();
        return {frame.bytes, frame.size};
    }
    bool failed() const { return !queue.error().empty(); }

private:
    hearth::SystemAudioQueue queue;
    hearth::SystemAudioPcm pcm{[this](const uint8_t* data, size_t size) { return queue.push(data, size); }};
};

void pack_sample(float sample, uint8_t* output) {
    if (!std::isfinite(sample)) throw std::runtime_error("System audio returned non-finite samples");
    const auto quantized = static_cast<uint16_t>(std::lround(std::clamp(sample, -1.0f, 1.0f) * 32767.0f));
    output[0] = static_cast<uint8_t>(quantized);
    output[1] = static_cast<uint8_t>(quantized >> 8);
}

// Same slot count, cursor ownership and caller-owned receive copy as the Rust
// implementation. This separates the queue design from the language choice.
class CppRing {
public:
    int32_t push(const uint8_t* input, size_t size) {
        const auto state = status.load(std::memory_order_acquire);
        if (state != empty) return state == ended ? closed : state;
        if (!input || size == 0 || size > frameBytes || size % 2 != 0)
            return fail(invalid);
        const auto h = head.load(std::memory_order_relaxed);
        if (h - tail.load(std::memory_order_acquire) >= capacity) return fail(overflow);
        auto& slot = slots[h % capacity];
        std::memcpy(slot.bytes.data(), input, size);
        slot.size = size;
        head.store(h + 1, std::memory_order_release);
        return frame;
    }
    int32_t pop(Bytes& output, size_t& written) {
        written = 0;
        const auto state = status.load(std::memory_order_acquire);
        if (state < 0) return state;
        const auto t = tail.load(std::memory_order_relaxed);
        if (t == head.load(std::memory_order_acquire)) return state;
        const auto& slot = slots[t % capacity];
        const auto size = slot.size;
        std::memcpy(output.data(), slot.bytes.data(), size);
        tail.store(t + 1, std::memory_order_release);
        const auto after = status.load(std::memory_order_acquire);
        if (after < 0) return after;
        written = size;
        return frame;
    }
    void finish() {
        int32_t expected = empty;
        status.compare_exchange_strong(expected, ended, std::memory_order_release, std::memory_order_relaxed);
    }

private:
    int32_t fail(int32_t reason) {
        status.store(reason, std::memory_order_release);
        return reason;
    }
    struct Slot { Bytes bytes; size_t size; };
    alignas(64) std::atomic<size_t> head{0};
    alignas(64) std::atomic<size_t> tail{0};
    std::atomic<int32_t> status{empty};
    std::array<Slot, capacity> slots{};
};

class RustRing {
public:
    RustRing() = default;
    RustRing(const RustRing&) = delete;
    RustRing& operator=(const RustRing&) = delete;
    ~RustRing() { hearth_bench_pcm_ring_destroy(handle); }
    int32_t push(const uint8_t* input, size_t size) {
        return hearth_bench_pcm_ring_push(handle, input, size);
    }
    int32_t pop(Bytes& output, size_t& written) {
        return hearth_bench_pcm_ring_pop(handle, output.data(), output.size(), &written);
    }
    void finish() { hearth_bench_pcm_ring_finish(handle); }

private:
    void* handle = hearth_bench_pcm_ring_create();
};

template <typename Ring>
class FixedPipeline {
public:
    void submit(const uint8_t* data, size_t size) {
        // Complete a previous short buffer before sending full frames directly.
        if (buffered != 0) {
            const auto take = std::min(size, frameBytes - buffered);
            std::memcpy(pending.data() + buffered, data, take);
            buffered += take;
            data += take;
            size -= take;
            if (buffered == frameBytes) publish_pending();
        }
        while (size >= frameBytes) {
            push(data, frameBytes);
            data += frameBytes;
            size -= frameBytes;
        }
        if (size > 0) {
            std::memcpy(pending.data(), data, size);
            buffered = size;
        }
    }
    void submit_float(const float* data, size_t size) {
        while (size > 0) {
            const auto take = std::min(size, (frameBytes - buffered) / 2);
            auto* output = pending.data() + buffered;
            for (size_t i = 0; i < take; ++i) pack_sample(data[i], output + i * 2);
            buffered += take * 2;
            data += take;
            size -= take;
            if (buffered == frameBytes) publish_pending();
        }
    }
    void finish() {
        if (buffered > 0) publish_pending();
        ring.finish();
    }
    FrameView read() {
        size_t size = 0;
        const auto result = ring.pop(received, size);
        if (result < 0) failure = true;
        return result == frame ? FrameView{received.data(), size} : FrameView{};
    }
    bool failed() const { return failure; }

private:
    void push(const uint8_t* data, size_t size) {
        if (ring.push(data, size) != frame) throw std::runtime_error("Fixed audio ring rejected a frame");
    }
    void publish_pending() {
        push(pending.data(), buffered);
        buffered = 0;
    }
    Ring ring;
    Bytes pending{}, received{};
    size_t buffered = 0;
    bool failure = false;
};

using CppPipeline = FixedPipeline<CppRing>;
using RustPipeline = FixedPipeline<RustRing>;
using Clock = std::chrono::steady_clock;

void check(bool result, const char* reason) {
    if (!result) throw std::runtime_error(reason);
}

void check_frame(FrameView got, const uint8_t* expected, size_t size) {
    check(got.size == size && std::memcmp(got.data, expected, size) == 0, "PCM byte or ordering mismatch");
}

template <typename Pipeline>
void check_pipeline() {
    Pipeline pipeline;
    std::array<uint8_t, frameBytes + 12> input{};
    for (size_t i = 0; i < input.size(); ++i) input[i] = static_cast<uint8_t>(i * 13 + i / 13);
    const auto expected = input;
    constexpr size_t split = 778;
    pipeline.submit(input.data(), split);
    pipeline.submit(input.data() + split, input.size() - split);
    input.fill(0);
    pipeline.finish();
    check_frame(pipeline.read(), expected.data(), frameBytes);
    check_frame(pipeline.read(), expected.data() + frameBytes, 12);
    check(pipeline.read().size == 0 && !pipeline.failed(), "Unexpected frame or error after draining");

    Pipeline slow_reader;
    for (size_t i = 0; i < capacity; ++i) slow_reader.submit(expected.data(), frameBytes);
    bool rejected = false;
    try { slow_reader.submit(expected.data(), frameBytes); }
    catch (const std::runtime_error&) { rejected = true; }
    check(rejected, "Full queue accepted an extra frame");
    check(slow_reader.read().size == 0 && slow_reader.failed(), "Overflow was not visible to the reader");

    Pipeline float_pipeline;
    constexpr std::array<float, 7> levels{-1.5f, -1.0f, -0.5f, 0.0f, 0.5f, 1.0f, 1.5f};
    std::array<float, frameSamples + 3> floats{};
    std::array<uint8_t, frameBytes + 6> quantized{};
    for (size_t i = 0; i < floats.size(); ++i) {
        floats[i] = levels[i % levels.size()];
        pack_sample(floats[i], quantized.data() + i * 2);
    }
    float_pipeline.submit_float(floats.data(), split);
    float_pipeline.submit_float(floats.data() + split, floats.size() - split);
    float_pipeline.finish();
    check_frame(float_pipeline.read(), quantized.data(), frameBytes);
    check_frame(float_pipeline.read(), quantized.data() + frameBytes, 6);
    check(float_pipeline.read().size == 0 && !float_pipeline.failed(), "Unexpected frame after float drain");
    const float nan = std::numeric_limits<float>::quiet_NaN();
    bool rejected_nan = false;
    try { Pipeline bad; bad.submit_float(&nan, 1); }
    catch (const std::runtime_error&) { rejected_nan = true; }
    check(rejected_nan, "Pipeline did not reject non-finite audio");
}

template <typename Ring>
void check_ring_errors() {
    Ring ring;
    Bytes data{};
    size_t count = 0;
    check(ring.pop(data, count) == empty, "New ring was not empty");
    check(ring.push(data.data(), 3) == invalid, "Ring accepted odd-length PCM");
    ring.finish();
    check(ring.pop(data, count) == invalid, "Finish masked terminal invalid-frame error");
    check(ring.push(data.data(), 2) == invalid, "Ring reopened after terminal error");
}

struct Timings {
    std::vector<int64_t> submit_ns;
    std::vector<int64_t> handoff_ns;
};

constexpr size_t warmupFrames = 128;
constexpr size_t measuredFrames = 2'048;
constexpr size_t totalFrames = warmupFrames + measuredFrames;
constexpr size_t inFlight = capacity / 2;
constexpr size_t trials = 5;

template <typename Pipeline, typename Submit>
void measure(const std::vector<Bytes>& inputs, const Submit& submit, Timings& out) {
    Pipeline pipeline;
    std::vector<Clock::time_point> started(totalFrames);
    std::atomic<size_t> produced{0}, consumed{0};
    std::atomic<bool> abort{false};
    std::exception_ptr reader_error;
    // Both readers use the same publication fence and bounded in-flight limit.
    // This compares saturated queue/framing work without varying the wake policy.
    std::thread reader([&] {
        try {
            for (size_t i = 0; i < totalFrames; ++i) {
                while (produced.load(std::memory_order_acquire) <= i) {
                    if (abort.load(std::memory_order_acquire)) return;
                    std::this_thread::yield();
                }
                const auto data = pipeline.read();
                const auto finished = Clock::now();
                check_frame(data, inputs[i].data(), frameBytes);
                if (i >= warmupFrames) {
                    out.handoff_ns.push_back(std::chrono::duration_cast<std::chrono::nanoseconds>(
                        finished - started[i]).count());
                }
                consumed.store(i + 1, std::memory_order_release);
            }
        } catch (...) {
            reader_error = std::current_exception();
            abort.store(true, std::memory_order_release);
        }
    });
    try {
        for (size_t i = 0; i < totalFrames; ++i) {
            while (i - consumed.load(std::memory_order_acquire) >= inFlight) {
                if (abort.load(std::memory_order_acquire)) throw std::runtime_error("PCM reader failed");
                std::this_thread::yield();
            }
            started[i] = Clock::now();
            submit(pipeline, inputs[i], i);
            const auto finished = Clock::now();
            produced.store(i + 1, std::memory_order_release);
            if (i >= warmupFrames) {
                out.submit_ns.push_back(std::chrono::duration_cast<std::chrono::nanoseconds>(
                    finished - started[i]).count());
            }
        }
        pipeline.finish();
    } catch (...) {
        abort.store(true, std::memory_order_release);
        reader.join();
        if (reader_error) std::rethrow_exception(reader_error);
        throw;
    }
    reader.join();
    if (reader_error) std::rethrow_exception(reader_error);
    check(pipeline.read().size == 0 && !pipeline.failed(), "Queue did not drain after cross-thread test");
}

int64_t percentile(const std::vector<int64_t>& sorted, size_t pct) {
    return sorted[(sorted.size() * pct + 99) / 100 - 1];
}

void report(const char* label, const char* scenario, Timings& timings) {
    auto& submit = timings.submit_ns;
    auto& handoff = timings.handoff_ns;
    std::sort(submit.begin(), submit.end());
    std::sort(handoff.begin(), handoff.end());
    std::printf(
        "{\"suite\":\"transport\",\"path\":\"%s\",\"scenario\":\"%s\","
        "\"frame_samples\":%zu,\"trials\":%zu,\"frames\":%zu,\"max_in_flight\":%zu,"
        "\"p50_submit_ns\":%lld,\"p95_submit_ns\":%lld,\"p99_submit_ns\":%lld,"
        "\"p50_handoff_ns\":%lld,\"p95_handoff_ns\":%lld,\"p99_handoff_ns\":%lld,"
        "\"pcm_verified\":true}\n",
        label, scenario, frameSamples, trials, submit.size(), inFlight,
        static_cast<long long>(percentile(submit, 50)),
        static_cast<long long>(percentile(submit, 95)),
        static_cast<long long>(percentile(submit, 99)),
        static_cast<long long>(percentile(handoff, 50)),
        static_cast<long long>(percentile(handoff, 95)),
        static_cast<long long>(percentile(handoff, 99)));
}
template <typename Submit>
void compare_paths(const char* scenario, const std::vector<Bytes>& inputs, const Submit& submit) {
    std::array<Timings, 4> timings;
    for (auto& run : timings) {
        run.submit_ns.reserve(trials * measuredFrames);
        run.handoff_ns.reserve(trials * measuredFrames);
    }
    for (size_t run = 0; run < trials; ++run) {
        for (size_t offset = 0; offset < timings.size(); ++offset) {
            switch ((run + offset) % timings.size()) {
            case 0: measure<LegacySystemPipeline>(inputs, submit, timings[0]); break;
            case 1: measure<CppPipeline>(inputs, submit, timings[1]); break;
            case 2: measure<RustPipeline>(inputs, submit, timings[2]); break;
            case 3: measure<SystemPipeline>(inputs, submit, timings[3]); break;
            }
        }
    }
    report("hearth_system_audio_pcm", scenario, timings[0]);
    report("cpp_fixed_spsc", scenario, timings[1]);
    report("rust_fixed_spsc", scenario, timings[2]);
    report("hearth_preallocated", scenario, timings[3]);
}
}  // namespace

void run_transport_benchmark() {
    check_pipeline<LegacySystemPipeline>();
    check_pipeline<SystemPipeline>();
    check_pipeline<CppPipeline>();
    check_pipeline<RustPipeline>();
    check_ring_errors<CppRing>();
    check_ring_errors<RustRing>();

    std::vector<Bytes> inputs(totalFrames);
    uint32_t state = 0xe357cd79;
    for (auto& input : inputs) {
        for (auto& byte : input) {
            state ^= state << 13;
            state ^= state >> 17;
            state ^= state << 5;
            byte = static_cast<uint8_t>(state);
        }
    }
    compare_paths("bounded_cross_thread_pcm16", inputs, [](auto& pipeline, const Bytes& input, size_t) {
        pipeline.submit(input.data(), input.size());
    });
    std::vector<std::array<float, frameSamples>> float_frames(totalFrames);
    for (size_t i = 0; i < totalFrames; ++i) {
        auto& input = inputs[i];
        auto& floats = float_frames[i];
        for (size_t j = 0; j < frameSamples; ++j) {
            const auto bits = static_cast<uint32_t>(input[j * 2]) |
                (static_cast<uint32_t>(input[j * 2 + 1]) << 8);
            floats[j] = (static_cast<float>(bits) - 32768.0f) / 32768.0f;
            pack_sample(floats[j], input.data() + j * 2);
        }
    }
    compare_paths("bounded_cross_thread_float32", inputs, [&](auto& pipeline, const Bytes&, size_t i) {
        pipeline.submit_float(float_frames[i].data(), frameSamples);
    });
}
