#include "system_audio_resample.h"
#include <algorithm>
#include <cassert>
#include <cmath>
#include <vector>

static std::vector<float> tone(unsigned rate, double hertz, double seconds) {
    std::vector<float> samples(static_cast<size_t>(rate * seconds));
    for (size_t i = 0; i < samples.size(); ++i) samples[i] = static_cast<float>(0.5 * std::sin(2 * 3.14159265358979323846 * hertz * static_cast<double>(i) / rate));
    return samples;
}
static std::vector<float> resample(unsigned rate, const std::vector<float>& input, size_t chunk) {
    hearth::SystemAudioResampler resampler(rate);
    std::vector<float> output;
    for (size_t offset = 0; offset < input.size(); offset += chunk) resampler.push(input.data() + offset, std::min(chunk, input.size() - offset), output);
    return output;
}
static double rms(const std::vector<float>& samples, size_t skip) {
    double sum = 0; size_t count = 0;
    for (size_t i = skip; i + skip < samples.size(); ++i) { sum += double(samples[i]) * samples[i]; ++count; }
    return std::sqrt(sum / static_cast<double>(count));
}

int main() {
    // Speech-band content keeps its level and duration at the two common mixer rates.
    for (unsigned rate : {48000u, 44100u, 96000u, 16000u}) {
        const auto output = resample(rate, tone(rate, 1000, 1.0), 480);
        assert(output.size() > 15950 && output.size() <= 16000);
        assert(std::fabs(rms(output, 400) - 0.5 / std::sqrt(2.0)) < 0.01);
    }
    // Content above the 8 kHz output Nyquist rate is removed rather than aliased into speech.
    assert(rms(resample(48000, tone(48000, 10000, 1.0), 480), 400) < 0.02);
    assert(rms(resample(44100, tone(44100, 12000, 1.0), 441), 400) < 0.02);
    // Capture packet boundaries never change the samples.
    const auto source = tone(48000, 700, 0.5);
    assert(resample(48000, source, 1) == resample(48000, source, 4801));
    // Interleaved integer and float frames average to the same mono level.
    std::vector<float> mono;
    const uint8_t stereo16[]{0xe8, 0x03, 0xb8, 0x0b, 0x00, 0x80, 0x00, 0x80}; // 1000, 3000 | -32768, -32768
    hearth::systemAudioDownmix(stereo16, 2, 2, hearth::SystemAudioSample::Int16, mono);
    assert(mono.size() == 2 && std::fabs(mono[0] - 2000 / 32768.0f) < 1e-6f && mono[1] == -1.0f);
    const float floats[]{0.25f, -0.75f};
    hearth::systemAudioDownmix(reinterpret_cast<const uint8_t*>(floats), 1, 2, hearth::SystemAudioSample::Float32, mono);
    assert(mono.size() == 1 && std::fabs(mono[0] + 0.25f) < 1e-6f);
    const uint8_t mono24[]{0x00, 0x00, 0x40}; // 0x400000 = half scale
    hearth::systemAudioDownmix(mono24, 1, 1, hearth::SystemAudioSample::Int24, mono);
    assert(std::fabs(mono[0] - 0.5f) < 1e-6f);
    bool rejected = false;
    const float invalid[]{NAN};
    try { hearth::systemAudioDownmix(reinterpret_cast<const uint8_t*>(invalid), 1, 1, hearth::SystemAudioSample::Float32, mono); } catch (const std::runtime_error&) { rejected = true; }
    assert(rejected);
    rejected = false;
    try { hearth::SystemAudioResampler unsupported(4000); } catch (const std::runtime_error&) { rejected = true; }
    assert(rejected);
    return 0;
}
