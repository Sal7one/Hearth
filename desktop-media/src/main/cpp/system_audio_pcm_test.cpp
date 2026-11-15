#include "system_audio_pcm.h"
#include <cassert>
#include <limits>
#include <vector>

int main() {
    hearth::SystemAudioQueue queue;
    hearth::SystemAudioPcm pcm([&](const uint8_t* bytes, size_t size) { return queue.push(bytes, size); });
    std::vector<float> samples(1601, 0.5f);
    pcm.floats(samples.data(), samples.size());
    std::fill(samples.begin(), samples.end(), -1.0f);
    pcm.finish();
    pcm.finish();
    queue.ready();
    queue.finish();
    assert(queue.waitReady());
    auto frame = queue.pop();
    assert(frame.size == 3200 && frame.bytes[0] == 0 && frame.bytes[1] == 64);
    frame = queue.pop();
    assert(frame.size == 2 && frame.bytes[0] == 0 && frame.bytes[1] == 64);
    assert(!queue.pop().size && queue.error().empty());

    hearth::SystemAudioQueue mixed;
    hearth::SystemAudioPcm mixedPcm([&](const uint8_t* bytes, size_t size) { return mixed.push(bytes, size); });
    const float negative = -1.5f;
    mixedPcm.floats(&negative, 1);
    std::vector<uint8_t> input(3203, 254);
    mixedPcm.signed16(input.data() + 1, 1601);
    input.assign(input.size(), 0);
    mixedPcm.finish();
    mixed.finish();
    frame = mixed.pop();
    assert(frame.size == 3200 && frame.bytes[0] == 1 && frame.bytes[1] == 128);
    assert(std::all_of(frame.bytes + 2, frame.bytes + frame.size, [](uint8_t value) { return value == 254; }));
    frame = mixed.pop();
    assert(frame.size == 4 && std::all_of(frame.bytes, frame.bytes + frame.size, [](uint8_t value) { return value == 254; }));
    assert(!mixed.pop().size);

    hearth::SystemAudioQueue bounded;
    const uint8_t pair[] = {0, 1};
    for (size_t i = 0; i < hearth::kSystemAudioQueueFrames; ++i) assert(bounded.push(pair, sizeof(pair)));
    for (size_t i = 0; i < hearth::kSystemAudioQueueFrames * 2; ++i) {
        const uint8_t next[] = {static_cast<uint8_t>(i + 1), 1};
        frame = bounded.pop();
        assert(frame.size == sizeof(pair));
        const auto expected = i < hearth::kSystemAudioQueueFrames ? 0 : i - hearth::kSystemAudioQueueFrames + 1;
        assert(bounded.push(next, sizeof(next)));
        assert(frame.bytes[0] == expected && frame.bytes[1] == 1);
    }
    assert(!bounded.push(pair, sizeof(pair)));
    assert(bounded.error().find("overflow") != std::string::npos);
    bounded.fail("later failure");
    bounded.finish();
    assert(!bounded.pop().size && bounded.error().find("overflow") != std::string::npos);

    hearth::SystemAudioQueue invalid;
    const uint8_t odd[] = {1, 2, 3};
    assert(!invalid.push(odd, sizeof(odd)) && !invalid.error().empty());
    hearth::SystemAudioPcm rejected([](const uint8_t*, size_t) { return true; });
    const float nan = std::numeric_limits<float>::quiet_NaN();
    bool threw = false;
    try { rejected.floats(&nan, 1); } catch (const std::runtime_error&) { threw = true; }
    assert(threw);

    hearth::SystemAudioQueue cancelled;
    cancelled.finish();
    assert(!cancelled.waitReady() && !cancelled.push(pair, sizeof(pair)));
    return 0;
}
