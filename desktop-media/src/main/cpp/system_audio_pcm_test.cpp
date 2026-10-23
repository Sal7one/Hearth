#include "system_audio_pcm.h"
#include <cassert>
#include <limits>

int main() {
    hearth::SystemAudioQueue queue;
    hearth::SystemAudioPcm pcm([&](std::vector<uint8_t> frame) { return queue.push(std::move(frame)); });
    std::vector<float> samples(1601, 0.5f);
    pcm.floats(samples.data(), samples.size());
    std::fill(samples.begin(), samples.end(), -1.0f); // The queue must own its PCM.
    pcm.finish(); queue.ready(); queue.finish();
    assert(queue.waitReady());
    std::vector<uint8_t> frame;
    assert(queue.pop(frame) && frame.size() == 3200 && frame[0] == 0 && frame[1] == 64);
    assert(queue.pop(frame) && frame.size() == 2 && frame[0] == 0 && frame[1] == 64);
    assert(!queue.pop(frame) && queue.error().empty());
    hearth::SystemAudioQueue bounded;
    for (size_t i=0; i<hearth::kSystemAudioQueueFrames; ++i) assert(bounded.push(std::vector<uint8_t>(3200)));
    assert(!bounded.push(std::vector<uint8_t>(3200)));
    assert(bounded.error().find("overflow") != std::string::npos);
    assert(!bounded.pop(frame));
    hearth::SystemAudioQueue invalid;
    assert(!invalid.push(std::vector<uint8_t>(3)) && !invalid.error().empty());
    hearth::SystemAudioPcm rejected([](std::vector<uint8_t>) { return true; });
    const float nan = std::numeric_limits<float>::quiet_NaN();
    bool threw=false; try { rejected.floats(&nan,1); } catch (const std::runtime_error&) { threw=true; }
    assert(threw);
    hearth::SystemAudioQueue cancelled; cancelled.finish(); assert(!cancelled.waitReady());
    return 0;
}
