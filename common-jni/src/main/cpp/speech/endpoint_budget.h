#pragma once
#include <cstdint>

namespace stt::speech {
// A continuously changing hypothesis must eventually become a real engine final.
// Use decoded audio time, not wall time; silence without text never forces EOU.
class EndpointBudget {
    int64_t limit_, start_ = -1;
public:
    explicit EndpointBudget(int maxMs) : limit_(int64_t(maxMs) * 16) {}
    bool observe(bool hasText, bool final, int64_t samples) {
        if (final) { start_ = -1; return false; }
        if (!hasText) return false;
        if (start_ < 0) start_ = samples;
        if (samples - start_ < limit_) return false;
        start_ = samples;
        return true;
    }
    void reset() { start_ = -1; }
};
}
