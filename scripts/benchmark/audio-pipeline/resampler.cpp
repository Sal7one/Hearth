#include "../../../common-jni/src/main/cpp/common/audio_utils.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string_view>
#include <vector>

extern "C" {
void* hearth_bench_src_create(std::uint32_t input_rate);
void hearth_bench_src_destroy(void*);
std::size_t hearth_bench_src_push(void*, const float*, std::size_t, float*, std::size_t);
std::size_t hearth_bench_src_finish(void*, float*, std::size_t);
}

namespace {
constexpr unsigned kOutputRate = 16000;
constexpr unsigned kCandidateLookahead = 64;
constexpr std::size_t kInputBlock = 4096;
constexpr double kTau = 6.28318530717958647692528676656;
constexpr std::size_t kError = std::numeric_limits<std::size_t>::max();

enum class Method { Baseline, Candidate };

const char* method_name(Method method) {
    return method == Method::Baseline ? "baseline" : "candidate";
}

void require(bool passed, const char* message) {
    if (!passed) throw std::runtime_error(message);
}

class Resampler {
public:
    Resampler(Method method, unsigned rate, std::size_t maximum_input)
        : method_(method), baseline_bypass_(method == Method::Baseline && rate == kOutputRate),
          state_(nullptr, hearth_bench_src_destroy) {
        if (method_ == Method::Baseline) {
            if (baseline_bypass_) return;
            require(baseline_.configure(static_cast<int>(rate), kOutputRate), "invalid baseline rate");
            baseline_output_.reserve(maximum_input);
        } else {
            state_.reset(hearth_bench_src_create(rate));
            require(static_cast<bool>(state_), "invalid candidate rate");
            // Downsampling or bypass. A single push cannot emit more samples than it accepts.
            candidate_output_.resize(std::max<std::size_t>(maximum_input, kCandidateLookahead));
        }
    }

    std::size_t push(const float* input, std::size_t count) {
        if (baseline_bypass_) {
            // Whisper validates 16 kHz PCM and borrows the caller's input directly.
            const auto status = stt::AudioUtils::validateFinitePcm(input, count);
            require(status == stt::AudioStatus::OK, stt::audioStatusMessage(status));
            borrowed_input_ = input;
            return count;
        }
        if (method_ == Method::Baseline) {
            baseline_output_.clear();
            const auto result = baseline_.push(input, count, baseline_output_);
            require(static_cast<bool>(result), stt::audioStatusMessage(result.status));
            return result.written;
        }
        const auto written = hearth_bench_src_push(state_.get(), input, count,
            candidate_output_.data(), candidate_output_.size());
        require(written != kError, "candidate rejected input or output buffer");
        return written;
    }

    std::size_t finish() {
        if (method_ == Method::Baseline) return 0;
        const auto written = hearth_bench_src_finish(state_.get(), candidate_output_.data(), candidate_output_.size());
        require(written != kError, "candidate could not flush tail");
        return written;
    }

    const float* output() const {
        if (baseline_bypass_) return borrowed_input_;
        return method_ == Method::Baseline ? baseline_output_.data() : candidate_output_.data();
    }

private:
    Method method_;
    bool baseline_bypass_;
    const float* borrowed_input_ = nullptr;
    stt::AudioStreamResampler baseline_;
    std::unique_ptr<void, decltype(&hearth_bench_src_destroy)> state_;
    std::vector<float> baseline_output_;
    std::vector<float> candidate_output_;
};

std::size_t expected_samples(std::size_t input_samples, unsigned rate) {
    return input_samples == 0 ? 0 : (input_samples - 1) * kOutputRate / rate + 1;
}

std::vector<float> tone(unsigned rate, double hz) {
    std::vector<float> samples(rate * 2);
    for (std::size_t index = 0; index < samples.size(); ++index) {
        samples[index] = static_cast<float>(std::sin(kTau * hz * index / rate));
    }
    return samples;
}

std::vector<float> convert(Method method, const std::vector<float>& samples, unsigned rate, bool split) {
    Resampler resampler(method, rate, samples.size());
    std::vector<float> output;
    output.reserve(expected_samples(samples.size(), rate));
    constexpr std::array<std::size_t, 8> chunks{1, 733, 16, 7, 4096, 128, 3, 257};
    for (std::size_t pos = 0, chunk = 0; pos < samples.size(); ++chunk) {
        const auto count = std::min(samples.size() - pos, split ? chunks[chunk % chunks.size()] : samples.size());
        const auto written = resampler.push(samples.data() + pos, count);
        if (written != 0) output.insert(output.end(), resampler.output(), resampler.output() + written);
        pos += count;
    }
    const auto tail = resampler.finish();
    if (tail != 0) output.insert(output.end(), resampler.output(), resampler.output() + tail);
    require(resampler.finish() == 0, "finish duplicated output");
    return output;
}

double magnitude(const std::vector<float>& output, double hz) {
    // Exclude both padded edges and inspect steady-state tone amplitude.
    constexpr std::size_t kTrim = kOutputRate / 10;
    require(output.size() > 2 * kTrim, "too few output samples for tone measurement");
    double sine = 0;
    double cosine = 0;
    for (std::size_t index = kTrim; index < output.size() - kTrim; ++index) {
        const double phase = kTau * hz * index / kOutputRate;
        sine += output[index] * std::sin(phase);
        cosine += output[index] * std::cos(phase);
    }
    return 2 * std::hypot(sine, cosine) / (output.size() - 2 * kTrim);
}

double decibels(double amplitude) {
    return 20 * std::log10(std::max(1e-8, amplitude));
}

void verify_buffers() {
    require(hearth_bench_src_create(8000) == nullptr, "candidate accepted unsupported rate");
    std::unique_ptr<void, decltype(&hearth_bench_src_destroy)> state(
        hearth_bench_src_create(48000), hearth_bench_src_destroy);
    require(static_cast<bool>(state), "could not create candidate");
    std::vector<float> input(400, 0.5F);
    std::vector<float> output(400);
    require(hearth_bench_src_push(state.get(), input.data(), input.size(), nullptr, 0) == kError,
        "candidate accepted insufficient capacity");
    input[0] = std::numeric_limits<float>::infinity();
    require(hearth_bench_src_push(state.get(), input.data(), input.size(), output.data(), output.size()) == kError,
        "candidate accepted invalid PCM");
    input[0] = 0.5F;
    const auto written = hearth_bench_src_push(state.get(), input.data(), input.size(), output.data(), output.size());
    require(written != kError, "valid input failed after invalid push");
    require(hearth_bench_src_finish(state.get(), nullptr, 0) == kError, "finish accepted insufficient capacity");
    const auto tail = hearth_bench_src_finish(state.get(), output.data(), output.size());
    require(tail != kError && written + tail == expected_samples(input.size(), 48000), "wrong count after retry");
    require(hearth_bench_src_push(state.get(), input.data(), input.size(), output.data(), output.size()) == kError,
        "candidate accepted input after finish");
}

void measure_quality(Method method, unsigned rate, const std::vector<float>& pass, const std::vector<float>& edge) {
    const auto full = convert(method, pass, rate, false);
    const auto split = convert(method, pass, rate, true);
    require(full == split, "chunk boundaries changed sample values");
    require(full.size() == expected_samples(pass.size(), rate), "stream sample count changed");
    require(convert(method, {}, rate, true).empty(), "empty stream produced audio");
    for (std::size_t count : {1, 2, 63, 64, 65, 66, 67, 101, 1019}) {
        std::vector<float> short_input(pass.begin(), pass.begin() + count);
        short_input.front() = -1.0F;
        const auto short_full = convert(method, short_input, rate, false);
        require(short_full.size() == expected_samples(count, rate), "incorrect short stream flush count");
        require(short_full == convert(method, short_input, rate, true), "chunked short stream changed output");
        if (rate == kOutputRate) require(short_full == short_input, "same-rate conversion changed first sample");
    }
    if (rate == kOutputRate) require(full == pass, "same-rate conversion changed PCM");
    const double passband_db = decibels(magnitude(full, 1000));
    const double edge_db = decibels(magnitude(convert(method, edge, rate, true), 6500));
    require(std::abs(passband_db) < 0.1, "1 kHz passband gain changed");
    if (method == Method::Candidate) require(std::abs(edge_db) < 0.2, "6.5 kHz passband gain changed");
    double alias_db = 0;
    if (rate != kOutputRate) {
        alias_db = decibels(magnitude(convert(method, tone(rate, 10000), rate, true), 6000));
        if (method == Method::Candidate) require(alias_db < -65.0, "candidate alias rejection below target");
    }
    const unsigned lookahead = method == Method::Candidate && rate != kOutputRate ? kCandidateLookahead : 0;
    std::printf("{\"suite\":\"resampler\",\"metric\":\"quality\",\"method\":\"%s\",\"input_rate_hz\":%u,"
        "\"output_rate_hz\":%u,\"output_samples\":%zu,\"passband_1khz_db\":%.4f,\"passband_6500hz_db\":%.4f,"
        "\"lookahead_source_samples\":%u,\"lookahead_ms\":%.4f,\"chunk_invariant\":true,"
        "\"same_rate_exact\":%s,\"alias_10khz_to_6khz_dbfs\":",
        method_name(method), rate, kOutputRate, full.size(), passband_db, edge_db, lookahead, 1000.0 * lookahead / rate,
        rate == kOutputRate ? "true" : "false");
    if (rate == kOutputRate) std::printf("null}\n");
    else std::printf("%.4f}\n", alias_db);
}

double percentile(const std::vector<double>& sorted, double fraction) {
    return sorted[static_cast<std::size_t>(std::ceil(sorted.size() * fraction)) - 1];
}

void measure_speed(Method method, unsigned rate, unsigned chunk_ms, const std::vector<float>& samples) {
    using Clock = std::chrono::steady_clock;
    const auto chunk_size = rate * chunk_ms / 1000;
    constexpr unsigned kWarmups = 2;
    constexpr unsigned kRuns = 20;
    std::vector<double> timings;
    timings.reserve(kRuns * (samples.size() / chunk_size + 1));
    double total_ns = 0;
    double checksum = 0;
    for (unsigned run = 0; run < kWarmups + kRuns; ++run) {
        // Measure the steady-state kernel, excluding allocation and phase-bank construction.
        Resampler resampler(method, rate, chunk_size);
        std::size_t output_samples = 0;
        for (std::size_t pos = 0; pos < samples.size(); pos += chunk_size) {
            const auto count = std::min(samples.size() - pos, static_cast<std::size_t>(chunk_size));
            const auto start = Clock::now();
            const auto written = resampler.push(samples.data() + pos, count);
            const double elapsed = std::chrono::duration<double, std::nano>(Clock::now() - start).count();
            if (run >= kWarmups) {
                timings.push_back(elapsed);
                total_ns += elapsed;
            }
            output_samples += written;
            if (written != 0) checksum += resampler.output()[written / 2];
        }
        output_samples += resampler.finish();
        require(output_samples == expected_samples(samples.size(), rate), "benchmark output sample count changed");
    }
    std::sort(timings.begin(), timings.end());
    const auto audio_seconds = static_cast<double>(kRuns) * samples.size() / rate;
    std::printf("{\"suite\":\"resampler\",\"metric\":\"speed\",\"method\":\"%s\",\"input_rate_hz\":%u,"
        "\"chunk_ms\":%u,\"chunks\":%zu,\"p50_us\":%.3f,\"p95_us\":%.3f,\"p99_us\":%.3f,"
        "\"wall_ms_per_audio_second\":%.4f,\"production_bypass_control\":%s,\"checksum\":%.6f}\n",
        method_name(method), rate, chunk_ms, timings.size(),
        percentile(timings, 0.5) / 1000, percentile(timings, 0.95) / 1000, percentile(timings, 0.99) / 1000,
        total_ns / 1e6 / audio_seconds, rate == kOutputRate ? "true" : "false", checksum);
}

unsigned checked_rate(unsigned rate) {
    require(rate == 16000 || rate == 44100 || rate == 48000, "choose input rate 16000, 44100, or 48000");
    return rate;
}
} // namespace

void run_resampler_benchmark() {
    verify_buffers();
    for (const auto rate : {16000U, 44100U, 48000U}) {
        const auto pass = tone(rate, 1000);
        const auto edge = tone(rate, 6500);
        for (const auto method : {Method::Baseline, Method::Candidate}) {
            measure_quality(method, rate, pass, edge);
        }
        std::vector<float> mixed = pass;
        for (std::size_t index = 0; index < mixed.size(); ++index) {
            mixed[index] = (mixed[index] + edge[index]) * 0.45F;
        }
        for (const auto chunk_ms : {10U, 50U}) {
            measure_speed(Method::Baseline, rate, chunk_ms, mixed);
            measure_speed(Method::Candidate, rate, chunk_ms, mixed);
        }
    }
}

void resample_file(const char* method, const char* input_raw, const char* output_raw, unsigned input_rate) {
    checked_rate(input_rate);
    const std::string_view selection(method);
    require(selection == "baseline" || selection == "candidate", "choose baseline or candidate");
    std::error_code error;
    require(!std::filesystem::equivalent(input_raw, output_raw, error), "PCM input and output paths are the same");
    std::ifstream input(input_raw, std::ios::binary);
    require(static_cast<bool>(input), "could not open input PCM");
    std::ofstream output(output_raw, std::ios::binary);
    require(static_cast<bool>(output), "could not open output PCM");
    const auto mode = selection == "baseline" ? Method::Baseline : Method::Candidate;
    Resampler resampler(mode, input_rate, kInputBlock);
    std::array<unsigned char, 2 * kInputBlock> bytes{};
    std::array<float, kInputBlock> pcm{};
    const auto write_output = [&](std::size_t samples) {
        for (std::size_t index = 0; index < samples; ++index) {
            const auto sample = static_cast<int>(std::round(std::clamp(resampler.output()[index], -1.0F, 1.0F) * 32768.0F));
            const auto encoded = static_cast<std::uint16_t>(std::clamp(sample, -32768, 32767));
            bytes[2 * index] = static_cast<unsigned char>(encoded & 0xff);
            bytes[2 * index + 1] = static_cast<unsigned char>(encoded >> 8);
        }
        output.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(2 * samples));
        require(static_cast<bool>(output), "could not write output PCM");
    };
    while (input) {
        input.read(reinterpret_cast<char*>(bytes.data()), bytes.size());
        const auto byte_count = static_cast<std::size_t>(input.gcount());
        require(byte_count % 2 == 0, "partial PCM16 sample at end of input");
        if (byte_count == 0) break;
        for (std::size_t index = 0; index < byte_count / 2; ++index) {
            const int bits = bytes[2 * index] + 256 * bytes[2 * index + 1];
            pcm[index] = (bits < 32768 ? bits : bits - 65536) / 32768.0F;
        }
        write_output(resampler.push(pcm.data(), byte_count / 2));
    }
    require(input.eof() && !input.bad(), "could not read input PCM");
    write_output(resampler.finish());
    output.close();
    require(static_cast<bool>(output), "could not close output PCM");
}
