#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <limits>
#include <stdexcept>
#include <utility>
#include <vector>

// Inspired by the multiresolution waveform overview in SoundCraft:
// https://github.com/storytold/soundcraft/blob/ef58a90f4a418a282a15fc71d2e03c3d8e8f40fa/crates/audio-io/src/peaks.rs
// Both paths use the same immutable PCM, integer sample ranges, and finite-sample policy.
namespace {

constexpr size_t block_size = 32;
constexpr size_t fanout = 4;

struct Range {
    size_t begin;
    size_t end;
};

struct Summary {
    float minimum = std::numeric_limits<float>::infinity();
    float maximum = -std::numeric_limits<float>::infinity();
    double energy = 0;

    void add(float sample) {
        if (!std::isfinite(sample)) sample = 0;
        minimum = std::min(minimum, sample);
        maximum = std::max(maximum, sample);
        energy += static_cast<double>(sample) * sample;
    }

    void merge(const Summary& other) {
        minimum = std::min(minimum, other.minimum);
        maximum = std::max(maximum, other.maximum);
        energy += other.energy;
    }
};

struct Measurement {
    float minimum = 0;
    float maximum = 0;
    double rms = 0;
};

Summary scan(const std::vector<float>& pcm, size_t begin, size_t end) {
    Summary result;
    for (size_t i = begin; i < end; ++i) result.add(pcm[i]);
    return result;
}

Measurement measure(Summary summary, size_t count) {
    if (count == 0) return {};
    return {summary.minimum, summary.maximum, std::sqrt(summary.energy / static_cast<double>(count))};
}

Range clamp_range(Range range, size_t size) {
    range.begin = std::min(range.begin, size);
    range.end = std::max(range.begin, std::min(range.end, size));
    return range;
}

Measurement scan_range(const std::vector<float>& pcm, Range range) {
    range = clamp_range(range, pcm.size());
    return measure(scan(pcm, range.begin, range.end), range.end - range.begin);
}

class WaveformOverview {
public:
    explicit WaveformOverview(const std::vector<float>& pcm) : pcm_(pcm) {
        if (pcm.empty()) return;
        const size_t n = pcm.size();
        auto& leaf = levels_.emplace_back();
        leaf.reserve(n / block_size + (n % block_size != 0));
        for (size_t i = 0; i < n;) {
            const size_t end = i + std::min(block_size, n - i);
            leaf.push_back(scan(pcm, i, end));
            i = end;
        }
        while (levels_.back().size() > 1) {
            const auto& previous = levels_.back();
            std::vector<Summary> level;
            level.reserve(previous.size() / fanout + (previous.size() % fanout != 0));
            for (size_t i = 0; i < previous.size();) {
                const size_t end = i + std::min(fanout, previous.size() - i);
                Summary merged;
                for (; i < end; ++i) merged.merge(previous[i]);
                level.push_back(merged);
            }
            levels_.push_back(std::move(level));
        }
    }

    Measurement query(Range range) const {
        range = clamp_range(range, pcm_.size());
        const size_t count = range.end - range.begin;
        const size_t first_block = range.begin / block_size + (range.begin % block_size != 0);
        const size_t last_block = range.end / block_size;
        if (first_block >= last_block) return measure(scan(pcm_, range.begin, range.end), count);

        Summary sum = scan(pcm_, range.begin, first_block * block_size);
        sum.merge(scan(pcm_, last_block * block_size, range.end));
        size_t left = first_block;
        size_t right = last_block;
        for (const auto& level : levels_) {
            // Consume unaligned blocks at both edges, then aggregate the remaining interior.
            while (left < right && left % fanout != 0) sum.merge(level[left++]);
            while (left < right && right % fanout != 0) sum.merge(level[--right]);
            if (left == right) break;
            left /= fanout;
            right /= fanout;
        }
        return measure(sum, count);
    }

    size_t auxiliary_bytes() const {
        size_t bytes = levels_.capacity() * sizeof(std::vector<Summary>);
        for (const auto& level : levels_) bytes += level.capacity() * sizeof(Summary);
        return bytes;
    }

    size_t levels() const { return levels_.size(); }

private:
    const std::vector<float>& pcm_;
    std::vector<std::vector<Summary>> levels_;
};

void check(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

double verify(const Measurement& expected, const Measurement& actual) {
    check(expected.minimum == actual.minimum && expected.maximum == actual.maximum,
          "Waveform min/max differ from direct scan");
    const double error = std::fabs(expected.rms - actual.rms);
    check(std::isfinite(error) && error <= 1e-10, "Waveform RMS differs from direct scan");
    return error;
}

uint32_t next_random(uint32_t& seed) {
    seed ^= seed << 13;
    seed ^= seed >> 17;
    seed ^= seed << 5;
    return seed;
}

std::vector<float> make_signal(size_t count) {
    std::vector<float> signal(count);
    uint32_t seed = 0xcd79fd42;
    for (size_t i = 0; i < count; ++i) {
        const auto sample = static_cast<int32_t>(next_random(seed) >> 16) - 32768;
        const float scale = 1.0f / static_cast<float>(32768u << ((i / 12001) % 5));
        signal[i] = i % 15017 == 0 ? 0.99f : static_cast<float>(sample) * scale;
    }
    return signal;
}

size_t verify_boundaries() {
    const std::vector<float> empty;
    verify({}, WaveformOverview(empty).query({0, 12}));
    const std::vector<float> values{
        0.5f, -1.0f, 0.25f, std::numeric_limits<float>::quiet_NaN(),
        std::numeric_limits<float>::infinity(), -std::numeric_limits<float>::infinity(),
    };
    const WaveformOverview known(values);
    verify({-1.0f, 0.5f, std::sqrt((0.25 + 1.0 + 0.0625) / 6)}, known.query({0, values.size()}));
    verify({}, known.query({3, 6}));

    // Exercise unaligned edges, incomplete leaf blocks, reversed and clamped ranges,
    // and hierarchy lengths that are not powers of four.
    constexpr std::array<size_t, 10> sizes{1, 17, 31, 32, 33, 63, 127, 128, 129, 4003};
    uint32_t seed = 0x78d2f134;
    for (size_t n : sizes) {
        const auto pcm = make_signal(n);
        const WaveformOverview overview(pcm);
        const std::array<Range, 8> ranges{{
            {0, n}, {0, std::numeric_limits<size_t>::max()}, {n, n},
            {n - 1, n}, {32, 31}, {0, 32}, {31, 33}, {1, n},
        }};
        for (auto range : ranges) verify(scan_range(pcm, range), overview.query(range));
        for (size_t i = 0; i < 128; ++i) {
            const size_t start = next_random(seed) % (n + 32);
            const size_t end = start + next_random(seed) % (n + 32);
            verify(scan_range(pcm, {start, end}), overview.query({start, end}));
        }
    }
    return 3 + sizes.size() * (8 + 128);
}

constexpr size_t columns = 512;
constexpr size_t viewports = 10;
constexpr size_t trials = 5;
using Clock = std::chrono::steady_clock;
volatile double timing_checksum = 0;

void benchmark_zoom(const char* zoom, const std::vector<float>& pcm,
                    const WaveformOverview& overview, size_t samples_per_column,
                    double build_ms) {
    std::vector<Range> ranges;
    ranges.reserve(columns * viewports);
    uint32_t seed = 0x9e1345f0;
    for (size_t viewport = 0; viewport < viewports; ++viewport) {
        // Deterministic pans at a fixed zoom; every viewport uses the same ranges on both paths.
        const size_t span = samples_per_column * columns;
        const size_t offset = next_random(seed) % (pcm.size() - span + 1);
        for (size_t column = 0; column < columns; ++column) {
            const size_t start = offset + samples_per_column * column;
            ranges.push_back({start, start + samples_per_column});
        }
    }
    std::vector<Measurement> baseline(ranges.size()), cached(ranges.size());
    auto run = [&](bool cache) {
        auto& output = cache ? cached : baseline;
        const auto begin = Clock::now();
        if (cache) {
            for (size_t i = 0; i < ranges.size(); ++i) output[i] = overview.query(ranges[i]);
        } else {
            for (size_t i = 0; i < ranges.size(); ++i) output[i] = scan_range(pcm, ranges[i]);
        }
        const auto end = Clock::now();
        double consumed = 0;
        for (const auto& item : output) consumed += item.minimum + item.maximum + item.rms;
        timing_checksum = consumed;
        return std::chrono::duration<double, std::milli>(end - begin).count() / viewports;
    };
    run(false);
    run(true);
    double max_rms_error = 0;
    for (size_t i = 0; i < ranges.size(); ++i) {
        max_rms_error = std::max(max_rms_error, verify(baseline[i], cached[i]));
    }
    std::array<double, trials> raw_times{}, cached_times{};
    for (size_t i = 0; i < trials; ++i) {
        if (i % 2 == 0) {
            raw_times[i] = run(false);
            cached_times[i] = run(true);
        } else {
            cached_times[i] = run(true);
            raw_times[i] = run(false);
        }
    }
    std::sort(raw_times.begin(), raw_times.end());
    std::sort(cached_times.begin(), cached_times.end());
    const double raw = raw_times[trials / 2];
    const double cache = cached_times[trials / 2];
    std::printf("{\"suite\":\"waveform\",\"metric\":\"query\",\"zoom\":\"%s\","
                "\"columns_per_viewport\":%zu,\"viewports\":%zu,\"trials\":%zu,\"samples_per_column\":%zu,"
                "\"raw_median_ms_per_viewport\":%.6f,\"cached_median_ms_per_viewport\":%.6f,"
                "\"raw_p95_ms_per_viewport\":%.6f,\"cached_p95_ms_per_viewport\":%.6f,"
                "\"query_speedup\":%.4f,\"compared_ranges\":%zu,\"min_max_exact\":true,"
                "\"max_rms_abs_error\":%.12g,\"break_even_viewports\":",
                zoom, columns, viewports, trials, samples_per_column, raw, cache,
                raw_times.back(), cached_times.back(), raw / cache, ranges.size(), max_rms_error);
    if (raw > cache) std::printf("%.0f", std::ceil(build_ms / (raw - cache)));
    else std::printf("null");
    std::printf("}\n");
}

}  // namespace

void run_waveform_benchmark() {
    const size_t boundary_checks = verify_boundaries() + 2;
    constexpr size_t sample_rate = 48'000;
    const auto pcm = make_signal(180 * sample_rate + 13);
    const auto started = Clock::now();
    const WaveformOverview overview(pcm);
    const double build_ms = std::chrono::duration<double, std::milli>(Clock::now() - started).count();
    for (const Range full_span : {Range{0, pcm.size()}, Range{1, pcm.size() - 1}}) {
        verify(scan_range(pcm, full_span), overview.query(full_span));
    }
    std::printf("{\"suite\":\"waveform\",\"metric\":\"build\",\"channels\":1,\"sample_rate_hz\":%zu,"
                "\"samples\":%zu,\"pcm_bytes\":%zu,\"auxiliary_bytes\":%zu,\"levels\":%zu,"
                "\"build_ms\":%.6f,\"boundary_checks\":%zu}\n",
                sample_rate, pcm.size(), pcm.size() * sizeof(float), overview.auxiliary_bytes(),
                overview.levels(), build_ms, boundary_checks);
    benchmark_zoom("close", pcm, overview, 16, build_ms);
    benchmark_zoom("medium", pcm, overview, 512, build_ms);
    benchmark_zoom("wide", pcm, overview, 8192, build_ms);
}
