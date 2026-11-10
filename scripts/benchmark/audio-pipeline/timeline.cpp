#include <algorithm>
#include <array>
#include <chrono>
#include <cinttypes>
#include <cstdint>
#include <cstdio>
#include <limits>
#include <optional>
#include <stdexcept>
#include <vector>

namespace {

// FilmCraft derives packet placement from the prior decoded sample count,
// except when a timestamp reveals a gap or overlap. This experiment models
// that technique at the PCM boundary, after the demuxer has converted PTS
// into signed sample positions and applied codec-specific trimming.
// https://github.com/storytold/filmcraft/blob/f4090d7b34a467a1bcabc91652d166a94f68dfbe/crates/codecs/src/audio.rs#L443-L478

struct Packet {
    std::optional<int64_t> pts;
    std::optional<int64_t> frames;
    bool new_epoch = false;
};

enum class Issue : uint8_t { none, missing_anchor, unknown_length, invalid_length, overflow, backward_jump };

struct Placement {
    std::optional<int64_t> start;
    Issue issue = Issue::none;
};

enum class Strategy { append, timestamps, clock };

struct Case {
    const char* name;
    int64_t rate;
    std::vector<Packet> packets;
    std::vector<Placement> expected;
};

const char* label(Strategy strategy) {
    switch (strategy) {
        case Strategy::append: return "hearth_pcm_append";
        case Strategy::timestamps: return "timestamp_only";
        case Strategy::clock: return "sample_clock";
    }
    throw std::logic_error("Unknown timeline strategy");
}

uint64_t distance(int64_t a, int64_t b) {
    // Unsigned subtraction also handles opposite signs and INT64_MIN.
    return a >= b ? uint64_t(a) - uint64_t(b) : uint64_t(b) - uint64_t(a);
}

void place(const Case& input, Strategy strategy, std::vector<Placement>& output) {
    const uint64_t tolerance = static_cast<uint64_t>((input.rate + 999) / 1000);
    const uint64_t max_backward_jump = static_cast<uint64_t>(2 * input.rate);
    std::optional<int64_t> next;
    bool awaiting_epoch = false;
    if (strategy == Strategy::append) next = 0;
    output.clear();
    for (const auto& packet : input.packets) {
        std::optional<int64_t> start;
        switch (strategy) {
            case Strategy::append:
                // Desktop WAV helpers append returned PCM bytes starting at zero.
                start = next;
                break;
            case Strategy::timestamps:
                start = packet.pts;
                break;
            case Strategy::clock:
                if (packet.new_epoch) {
                    next.reset();
                    awaiting_epoch = false;
                }
                if (awaiting_epoch) {
                    output.push_back({std::nullopt, Issue::backward_jump});
                    continue;
                }
                if (next && (!packet.pts || distance(*packet.pts, *next) <= tolerance)) {
                    start = next;
                } else if (next && packet.pts && *packet.pts < *next &&
                           distance(*packet.pts, *next) > max_backward_jump) {
                    // A demuxer must unwrap its own clock or mark an explicit epoch.
                    // Quarantine this position rather than treating it as an overlap.
                    output.push_back({std::nullopt, Issue::backward_jump});
                    next.reset();
                    awaiting_epoch = true;
                    continue;
                } else {
                    start = packet.pts;
                }
                break;
        }

        next.reset();
        if (packet.frames && *packet.frames < 0) {
            output.push_back({std::nullopt, Issue::invalid_length});
        } else if (!start) {
            output.push_back({std::nullopt, Issue::missing_anchor});
        } else if (!packet.frames) {
            // This position is usable, but it cannot predict the next start.
            output.push_back({start, Issue::unknown_length});
        } else if (*start > std::numeric_limits<int64_t>::max() - *packet.frames) {
            output.push_back({std::nullopt, Issue::overflow});
        } else {
            output.push_back({start, Issue::none});
            next = *start + *packet.frames;
        }
    }
}

// Independent fixture clock. The expected positions come from the decoded
// sample count and explicit edits. The input PTS independently rounds those
// positions to milliseconds, as the FilmCraft OBS-remux regression does.
int64_t round_to_millisecond(int64_t samples, int64_t rate) {
    const int64_t magnitude = samples < 0 ? -samples : samples;
    const int64_t milliseconds = (magnitude * 1000 + rate / 2) / rate;
    const int64_t quantized = (milliseconds * rate + 500) / 1000;
    return samples < 0 ? -quantized : quantized;
}

Case aac_case(const char* name, int64_t rate, int64_t count, bool edits) {
    Case result{name, rate, {}, {}};
    result.packets.reserve(count);
    result.expected.reserve(count);
    for (int64_t i = 0; i < count; ++i) {
        // Multiples of 441 AAC blocks align exactly to a millisecond on both
        // rates, so the edit anchors have an exact, independently known PTS.
        const int64_t edit = !edits || i < 441 ? 0 : i < 882 ? rate / 10 : rate / 20;
        const int64_t expected = i * 1024 + edit;
        result.packets.push_back({round_to_millisecond(expected, rate), 1024});
        result.expected.push_back({expected});
    }
    return result;
}

Case incomplete_case() {
    return {"trim_and_incomplete_metadata", 48'000,
            {{-96, 32}, {std::nullopt, 64}, {4, std::nullopt},
             {std::nullopt, 128}, {256, 16}, {std::nullopt, 1}, {273, -1}, {400, 16}},
            {{-96}, {-64}, {0, Issue::unknown_length},
             {std::nullopt, Issue::missing_anchor}, {256}, {272},
             {std::nullopt, Issue::invalid_length}, {400}}};
}

Case clock_boundary_case() {
    constexpr int64_t raw_wrap = int64_t{1} << 32;
    constexpr int64_t maximum = std::numeric_limits<int64_t>::max();
    return {"wrap_epoch_and_overflow", 48'000,
            {{raw_wrap - 1024, 1024}, {0, 1024}, {1024, 1024}, {0, 1024, true},
             {std::nullopt, 1024}, {maximum - 3, 4}, {std::nullopt, 1}, {4096, 1024},
             {std::numeric_limits<int64_t>::min(), 1}},
            {{raw_wrap - 1024}, {std::nullopt, Issue::backward_jump}, {std::nullopt, Issue::backward_jump}, {0}, {1024},
             {std::nullopt, Issue::overflow}, {std::nullopt, Issue::missing_anchor}, {4096},
             {std::nullopt, Issue::backward_jump}}};
}

void score(const Case& input, Strategy strategy) {
    std::vector<Placement> output;
    output.reserve(input.packets.size());
    place(input, strategy, output);
    if (output.size() != input.expected.size()) {
        throw std::runtime_error("Timeline fixture length mismatch");
    }
    size_t position_mismatches = 0;
    size_t status_mismatches = 0;
    size_t unresolved = 0;
    uint64_t max_start_error = 0;
    for (size_t i = 0; i < output.size(); ++i) {
        const auto& actual = output[i];
        const auto& expected = input.expected[i];
        position_mismatches += actual.start != expected.start;
        status_mismatches += actual.issue != expected.issue;
        unresolved += !actual.start;
        if (actual.start && expected.start) {
            max_start_error = std::max(max_start_error, distance(*actual.start, *expected.start));
        }
    }
    if (strategy == Strategy::clock && (position_mismatches || status_mismatches)) {
        throw std::runtime_error("Sample clock diverged from the fixture oracle");
    }
    std::printf(
        "{\"suite\":\"timeline\",\"metric\":\"placement\",\"case\":\"%s\",\"path\":\"%s\","
        "\"rate_hz\":%" PRId64 ",\"packets\":%zu,\"position_mismatches\":%zu,\"status_mismatches\":%zu,"
        "\"unresolved_positions\":%zu,\"max_shared_start_error_samples\":%" PRIu64 "}\n",
        input.name, label(strategy), input.rate, input.packets.size(),
        position_mismatches, status_mismatches, unresolved, max_start_error);
}

volatile uint64_t measured_placements = 0;

void measure(const Case& input) {
    constexpr std::array paths{Strategy::append, Strategy::timestamps, Strategy::clock};
    constexpr size_t trials = 9;
    std::array<std::array<double, trials>, paths.size()> times{};
    std::vector<Placement> output;
    output.reserve(input.packets.size());
    for (size_t trial = 0; trial < trials + 1; ++trial) {
        for (size_t offset = 0; offset < paths.size(); ++offset) {
            const size_t path = (trial + offset) % paths.size();
            const auto started = std::chrono::steady_clock::now();
            place(input, paths[path], output);
            const auto ended = std::chrono::steady_clock::now();
            // Consume the placements outside the timing interval.
            uint64_t checksum = 0;
            for (const auto& packet : output) {
                checksum += static_cast<uint64_t>(packet.start.value_or(0));
            }
            measured_placements = checksum;
            if (trial > 0) {
                times[path][trial - 1] = std::chrono::duration<double, std::nano>(ended - started).count()
                    / static_cast<double>(input.packets.size());
            }
        }
    }
    for (size_t path = 0; path < paths.size(); ++path) {
        auto& samples = times[path];
        std::sort(samples.begin(), samples.end());
        std::printf(
            "{\"suite\":\"timeline\",\"metric\":\"speed\",\"case\":\"%s\",\"path\":\"%s\","
            "\"rate_hz\":%" PRId64 ",\"packets\":%zu,\"trials\":%zu,\"median_ns_per_packet\":%.3f,"
            "\"p95_ns_per_packet\":%.3f}\n",
            input.name, label(paths[path]), input.rate, input.packets.size(), trials,
            samples[trials / 2], samples.back());
    }
}

}  // namespace

void run_timeline_benchmark() {
    const std::array fixtures{
        aac_case("obs_aac_48k", 48'000, 2'048, false),
        aac_case("obs_aac_44100", 44'100, 2'048, false),
        aac_case("gap_and_overlap_48k", 48'000, 1'024, true),
        aac_case("gap_and_overlap_44100", 44'100, 1'024, true),
        incomplete_case(),
        clock_boundary_case(),
    };
    for (const auto& fixture : fixtures) {
        for (const auto strategy : {Strategy::append, Strategy::timestamps, Strategy::clock}) {
            score(fixture, strategy);
        }
    }
    measure(aac_case("obs_aac_44100_long", 44'100, 120'000, true));
}
