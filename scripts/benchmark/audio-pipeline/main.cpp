#include <charconv>
#include <exception>
#include <iostream>
#include <stdexcept>
#include <string_view>

void run_transport_benchmark();
void run_resampler_benchmark();
void run_meter_benchmark();
void run_timeline_benchmark();
void run_mix_benchmark();
void run_waveform_benchmark();
void run_pixels_benchmark();
void run_probe_benchmark();
void resample_file(const char* method, const char* input_raw, const char* output_raw, unsigned input_rate);

namespace {
struct Suite {
    std::string_view name;
    void (*run)();
};

constexpr Suite suites[] = {
    {"transport", run_transport_benchmark},
    {"resampler", run_resampler_benchmark},
    {"meter", run_meter_benchmark},
    {"timeline", run_timeline_benchmark},
    {"mix", run_mix_benchmark},
    {"waveform", run_waveform_benchmark},
    {"pixels", run_pixels_benchmark},
    {"probe", run_probe_benchmark},
};
}

int main(int argc, char** argv) {
    try {
        const std::string_view command = argc > 1 ? argv[1] : "all";
        if (argc == 6 && command == "resample") {
            const std::string_view rate_string = argv[5];
            unsigned rate = 0;
            const auto end = rate_string.data() + rate_string.size();
            const auto result = std::from_chars(rate_string.data(), end, rate);
            if (result.ec != std::errc{} || result.ptr != end || rate == 0) {
                throw std::invalid_argument("Invalid source sample rate");
            }
            resample_file(argv[2], argv[3], argv[4], rate);
            return 0;
        }
        if (argc <= 2 && command == "all") {
            for (const auto& suite : suites) suite.run();
            return 0;
        }
        if (argc <= 2) {
            for (const auto& suite : suites) {
                if (command == suite.name) {
                    suite.run();
                    return 0;
                }
            }
        }
        std::cerr << "Usage: hearth-audio-bench [all";
        for (const auto& suite : suites) std::cerr << '|' << suite.name;
        std::cerr << "]\n"
                  << "       hearth-audio-bench resample <baseline|candidate> <input.s16le> <output.s16le> <source-rate>\n";
        return 2;
    } catch (const std::exception& error) {
        std::cerr << "Benchmark failed: " << error.what() << '\n';
        return 1;
    }
}
