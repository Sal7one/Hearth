#include "audio_utils.h"

#include <cassert>
#include <cstdio>
#include <limits>

int main() {
    using stt::AudioStatus;
    using stt::AudioUtils;

    const float finite[] = {-1.0f, 0.0f, 0.5f, 1.0f};
    assert(AudioUtils::validateFinitePcm(finite, 4) == AudioStatus::OK);
    assert(AudioUtils::validateFinitePcm(nullptr, 0) == AudioStatus::OK);
    assert(AudioUtils::validateFinitePcm(nullptr, 1) == AudioStatus::INVALID_ARGUMENT);

    const float nanFrame[] = {0.1f, std::numeric_limits<float>::quiet_NaN(), 0.2f};
    const float infFrame[] = {0.1f, std::numeric_limits<float>::infinity(), 0.2f};
    assert(AudioUtils::validateFinitePcm(nanFrame, 3) == AudioStatus::NON_FINITE);
    assert(AudioUtils::validateFinitePcm(infFrame, 3) == AudioStatus::NON_FINITE);
    assert(AudioUtils::validateFinitePcm(finite, 4) == AudioStatus::OK);

    std::puts("audio_utils_ingress: 6 checks PASS");
}
