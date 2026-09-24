#include "audio_gate.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <vector>

using stt::AudioGate;
using stt::AudioGateConfig;

namespace {
int checks = 0;
void check(bool ok, const char* what) {
    ++checks;
    if (!ok) {
        std::fprintf(stderr, "audio_gate FAIL: %s\n", what);
        std::exit(1);
    }
}

// The UtteranceSegmenter configuration: a pure per-frame energy threshold.
AudioGateConfig perFrame() {
    AudioGateConfig c;
    c.rmsThresholdDb = -45.0f;  // 0.0056234 linear
    c.minActiveSamples = 320;
    c.minSilenceSamples = 320;
    c.smoothingAlpha = 1.0f;
    c.attackAlpha = 1.0f;
    c.zeroCrossingMax = 1.0f;
    return c;
}

std::vector<float> tone(size_t n, float amplitude) {
    std::vector<float> out(n);
    for (size_t i = 0; i < n; ++i) {
        out[i] = amplitude * std::sin(2.0f * 3.14159265f * 440.0f * static_cast<float>(i) / 16000.0f);
    }
    return out;
}

bool feed(AudioGate& gate, const std::vector<float>& frame, int times) {
    bool active = false;
    for (int i = 0; i < times; ++i) active = gate.analyzeFloat(frame.data(), frame.size()).isActive;
    return active;
}
}  // namespace

int main() {
    const float nan = std::numeric_limits<float>::quiet_NaN();
    const float inf = std::numeric_limits<float>::infinity();
    const std::vector<float> zeros(320, 0.0f);
    const std::vector<float> nanFrame(320, nan);
    const std::vector<float> infFrame(320, inf);
    const std::vector<float> speech = tone(320, 0.3f);  // RMS ~0.212, ZCR ~0.055

    // Threshold edges, float and int16 paths (-45 dBFS = 0.0056234).
    {
        AudioGate gate(perFrame());
        const std::vector<float> above(320, 0.0057f), below(320, 0.0055f);
        check(gate.analyzeFloat(above.data(), above.size()).isActive, "float just above threshold");
        check(!gate.analyzeFloat(below.data(), below.size()).isActive, "float just below threshold");
        const std::vector<int16_t> above16(320, 187), below16(320, 180);
        gate.reset();
        check(gate.analyze(above16.data(), above16.size()).isActive, "int16 just above threshold");
        check(!gate.analyze(below16.data(), below16.size()).isActive, "int16 just below threshold");
        const std::vector<int16_t> fullScale(320, -32768);
        const auto loud = gate.analyze(fullScale.data(), fullScale.size());
        check(loud.isActive && std::fabs(loud.rmsLevel - 1.0f) < 1e-6f, "int16 -32768 RMS is exactly full scale");
        check(!gate.analyzeFloat(nullptr, 320).isActive && !gate.analyzeFloat(zeros.data(), 0).isActive,
              "null/empty input is inactive");
    }

    // Default hysteresis: 3200 active samples before opening.
    {
        AudioGate gate;
        check(!feed(gate, speech, 9), "not active after 9 frames (2880 samples)");
        check(feed(gate, speech, 1), "active on the 10th frame (3200 samples)");
        check(feed(gate, zeros, 5), "rides through a 100 ms pause");
        check(!feed(gate, zeros, 40), "closes after sustained silence");
    }

    // ZCR ceiling rejects alternating-sign noise however loud it is.
    {
        AudioGate gate;
        std::vector<float> alternating(320);
        for (size_t i = 0; i < alternating.size(); ++i) alternating[i] = (i % 2) ? -0.5f : 0.5f;
        check(!feed(gate, alternating, 20), "ZCR 1.0 never opens the default gate");
    }

    // reset() clears hysteresis and the smoothed level.
    {
        AudioGate gate;
        check(feed(gate, speech, 10), "active before reset");
        gate.reset();
        const auto r = gate.analyzeFloat(zeros.data(), zeros.size());
        check(!r.isActive && r.rmsLevel == 0.0f, "reset forgets the previous utterance");
    }

    // A NaN frame must not poison the smoothed level (was: silence forever).
    {
        AudioGate gate;
        const auto r = gate.analyzeFloat(nanFrame.data(), nanFrame.size());
        check(!r.isActive && std::isfinite(r.rmsLevel), "NaN frame is inactive with a finite level");
        check(feed(gate, speech, 10), "speech after NaN still opens on the 10th frame");
        const auto mid = gate.analyzeFloat(nanFrame.data(), nanFrame.size());
        check(mid.isActive && std::isfinite(mid.rmsLevel), "NaN inside speech keeps hysteresis and level");
        check(feed(gate, speech, 1), "speech continues after a mid-utterance NaN frame");
    }
    {
        AudioGate gate(perFrame());  // alpha == 1: 0 * NaN would still poison
        gate.analyzeFloat(nanFrame.data(), nanFrame.size());
        check(feed(gate, speech, 1), "per-frame config recovers on the next speech frame");
    }

    // An Inf frame must not pin the gate open (was: speech forever).
    {
        AudioGate gate;
        check(!feed(gate, infFrame, 12), "Inf frames never open the gate");
        check(!feed(gate, zeros, 40), "silence after Inf stays closed");
        check(feed(gate, speech, 10), "speech after Inf opens normally");
    }

    // Active regions over int16 audio: one region covering the tone.
    {
        std::vector<int16_t> audio(80000, 0);
        for (size_t i = 16000; i < 48000; ++i) {
            audio[i] = static_cast<int16_t>(9830.0f * std::sin(2.0f * 3.14159265f * 440.0f * static_cast<float>(i) / 16000.0f));
        }
        AudioGate gate;
        const auto regions = gate.getActiveRegions(audio.data(), audio.size());
        check(regions.size() == 1, "one active region");
        check(regions[0].first >= 16000 && regions[0].first <= 16000 + 3200, "region starts at the tone onset");
        check(regions[0].second > 48000 && regions[0].second <= 80000, "region ends after the tone with hangover");
        check(gate.getActiveRegions(nullptr, 10).empty(), "null input has no regions");
    }

    std::printf("audio_gate: %d checks PASS\n", checks);
    return 0;
}
